package com.shihab.diplay.btproxy.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import com.shihab.diplay.btproxy.ProxySnapshot
import com.shihab.diplay.btproxy.ProxyStage
import com.shihab.diplay.btproxy.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * The relay track, read left to right the way the bytes travel: DiPlay, Wi-Fi TCP, this phone's
 * proxy, the RFCOMM channel, then the iPhone.
 */
class PipelineView(context: Context, private val theme: ProxyTheme) : View(context) {

    data class Stage(val label: String, val state: GlyphState)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = theme.spPx(ProxyTheme.TYPE_CAPTION)
    }
    private var breath: ValueAnimator? = null
    private var pulse = 0f

    var stages: List<Stage> = emptyList()
        set(value) {
            field = value
            applyBreath()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val dot = theme.dp(DOT_DP)
        val label = caption.descent() - caption.ascent()
        setMeasuredDimension(width, (dot + theme.dp(GAP_DP) + label + theme.dp(PAD_BOTTOM_DP)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (stages.isEmpty()) return
        val slot = width.toFloat() / stages.size
        val dot = theme.dp(DOT_DP).toFloat()
        val centreY = theme.dp(PAD_TOP_DP).toFloat() + dot / 2f
        rule.strokeWidth = theme.dp(2).toFloat()
        // Baseline rail first, then the segments that are actually established.
        rule.color = theme.outline
        canvas.drawLine(slot / 2f, centreY, width - slot / 2f, centreY, rule)
        stages.forEachIndexed { index, stage ->
            val centreX = slot * index + slot / 2f
            if (index + 1 < stages.size && stage.state == GlyphState.READY) {
                rule.color = theme.success
                val radius = dot / 2f
                canvas.drawLine(centreX + radius, centreY, centreX + slot - radius, centreY, rule)
            }
        }
        stages.forEachIndexed { index, stage ->
            val centreX = slot * index + slot / 2f
            val colour = colourOf(stage.state)
            val radius = dot / 2f
            if (stage.state == GlyphState.ACTIVE) {
                rule.color = colour
                rule.alpha = (90 * (1f - pulse)).toInt().coerceIn(0, 255)
                rule.style = Paint.Style.STROKE
                rule.strokeWidth = theme.dp(2).toFloat()
                canvas.drawCircle(centreX, centreY, radius + dot * 0.35f * pulse, rule)
                rule.alpha = 255
            }
            fill.style = Paint.Style.FILL
            fill.color = when (stage.state) {
                GlyphState.PENDING -> theme.surface
                else -> colour
            }
            canvas.drawCircle(centreX, centreY, radius, fill)
            if (stage.state == GlyphState.PENDING) {
                rule.style = Paint.Style.STROKE
                rule.strokeWidth = theme.dp(2).toFloat()
                rule.color = theme.outline
                canvas.drawCircle(centreX, centreY, radius - rule.strokeWidth / 2f, rule)
            }
            caption.color = if (stage.state == GlyphState.PENDING) theme.muted else theme.text
            // A label never runs into its neighbour: it shrinks to fit its own slot instead.
            val base = theme.spPx(ProxyTheme.TYPE_CAPTION)
            val room = slot - theme.dp(LABEL_PAD_DP)
            val measured = caption.measureText(stage.label)
            caption.textSize = if (measured > room) base * room / measured else base
            canvas.drawText(stage.label, centreX, centreY + dot / 2f + theme.dp(GAP_DP) - caption.ascent(), caption)
        }
    }

    private fun colourOf(state: GlyphState) = when (state) {
        GlyphState.READY -> theme.success
        GlyphState.ACTIVE -> theme.accent
        GlyphState.BLOCKED -> theme.danger
        GlyphState.PENDING -> theme.muted
    }

    private fun applyBreath() {
        breath?.cancel()
        breath = null
        if (stages.none { it.state == GlyphState.ACTIVE }) {
            pulse = 0f
            return
        }
        breath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulse = (sin((it.animatedValue as Float) * PI * 2).toFloat() + 1f) / 2f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        breath?.cancel()
        breath = null
        super.onDetachedFromWindow()
    }

    companion object {
        const val DOT_DP = 14
        const val GAP_DP = 8
        const val PAD_TOP_DP = 6
        const val PAD_BOTTOM_DP = 6
        const val LABEL_PAD_DP = 10

        /** B talks to this phone over Wi-Fi, this phone talks to the iPhone over RFCOMM. */
        fun stagesFor(snapshot: ProxySnapshot, context: Context): List<Stage> {
            val relaying = snapshot.stage == ProxyStage.RELAYING
            val connecting = snapshot.stage == ProxyStage.RFCOMM_CONNECTING
            val peerAttached = snapshot.stage == ProxyStage.AUTHENTICATED || snapshot.stage == ProxyStage.RFCOMM_CONNECTING || relaying
            fun stage(ready: Boolean, active: Boolean, blocked: Boolean = false) = when {
                ready -> GlyphState.READY
                active -> GlyphState.ACTIVE
                blocked -> GlyphState.BLOCKED
                else -> GlyphState.PENDING
            }
            // One failure, one red mark: the rest stay pending so the track does not read as broken.
            if (snapshot.blocked) {
                return listOf(
                    Stage(context.getString(R.string.stage_diplay), GlyphState.PENDING),
                    Stage(context.getString(R.string.stage_wifi), GlyphState.PENDING),
                    Stage(context.getString(R.string.stage_proxy), GlyphState.BLOCKED),
                    Stage(context.getString(R.string.stage_rfcomm), GlyphState.PENDING),
                    Stage(context.getString(R.string.stage_iphone), GlyphState.PENDING),
                )
            }
            return listOf(
                Stage(context.getString(R.string.stage_diplay), stage(relaying, snapshot.stage == ProxyStage.DISCOVERED || peerAttached)),
                Stage(context.getString(R.string.stage_wifi), stage(snapshot.serviceRunning, false)),
                Stage(context.getString(R.string.stage_proxy), stage(relaying, snapshot.serviceRunning && !relaying)),
                Stage(context.getString(R.string.stage_rfcomm), stage(relaying, connecting)),
                Stage(context.getString(R.string.stage_iphone), stage(snapshot.targetAddress.isNotBlank(), relaying)),
            )
        }
    }
}
