package com.shihab.diplay.btproxy.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.min

enum class GlyphState { READY, ACTIVE, BLOCKED, PENDING }

/** Status mark drawn from paths, so it never depends on a check-mark glyph existing in the font. */
class GlyphView(context: Context, private val theme: ProxyTheme) : View(context) {

    var state: GlyphState = GlyphState.PENDING
        set(value) {
            if (field == value) return
            field = value
            applyPulse()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Path()
    private var pulse: ValueAnimator? = null

    private fun colorOf() = when (state) {
        GlyphState.READY -> theme.success
        GlyphState.ACTIVE -> theme.accent
        GlyphState.BLOCKED -> theme.danger
        GlyphState.PENDING -> theme.muted
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = theme.dp(SIZE_DP)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val centreX = width / 2f
        val centreY = height / 2f
        val ring = size * 0.07f
        val colour = colorOf()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        paint.color = colour
        val outer = size / 2f - ring / 2f
        canvas.drawCircle(centreX, centreY, outer, paint)
        if (state == GlyphState.PENDING) return
        val inner = outer - ring
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centreX, centreY, inner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = theme.onAccent
        paint.strokeWidth = size * 0.1f
        when (state) {
            GlyphState.READY -> {
                mark.reset()
                mark.moveTo(centreX - size * 0.21f, centreY + size * 0.01f)
                mark.lineTo(centreX - size * 0.05f, centreY + size * 0.15f)
                mark.lineTo(centreX + size * 0.22f, centreY - size * 0.14f)
                canvas.drawPath(mark, paint)
            }
            GlyphState.BLOCKED -> {
                canvas.drawLine(centreX, centreY - size * 0.2f, centreX, centreY + size * 0.06f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(centreX, centreY + size * 0.2f, size * 0.055f, paint)
            }
            GlyphState.ACTIVE -> canvas.drawCircle(centreX, centreY, size * 0.12f, paint.apply { style = Paint.Style.FILL })
            GlyphState.PENDING -> Unit
        }
    }

    private fun applyPulse() {
        pulse?.cancel()
        pulse = null
        if (state != GlyphState.ACTIVE) {
            alpha = 1f
            return
        }
        pulse = ValueAnimator.ofFloat(1f, 0.4f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }

    companion object {
        const val SIZE_DP = 22
    }
}

/** Selection mark for the target-device rows. */
class RadioMark(context: Context, private val theme: ProxyTheme) : View(context) {

    var checked = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = theme.dp(SIZE_DP)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val centreX = width / 2f
        val centreY = height / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.09f
        paint.color = if (checked) theme.accent else theme.outline
        val outer = size / 2f - paint.strokeWidth / 2f
        canvas.drawCircle(centreX, centreY, outer, paint)
        if (!checked) return
        paint.style = Paint.Style.FILL
        paint.color = theme.accent
        canvas.drawCircle(centreX, centreY, outer * 0.52f, paint)
    }

    companion object {
        const val SIZE_DP = 22
    }
}

/** Branded toggle: framework Switch keeps Lollipop's grey chrome and ignores the palette. */
class SwitchView(context: Context, private val theme: ProxyTheme) : View(context) {

    var checked = false
        set(value) {
            if (field == value) return
            field = value
            animateThumb()
            contentDescription = if (value) "已开启" else "已关闭"
        }

    var onToggle: ((Boolean) -> Unit)? = null
    private var thumbFraction = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        contentDescription = "已关闭"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(theme.dp(WIDTH_DP), theme.dp(HEIGHT_DP))
    }

    override fun onDraw(canvas: Canvas) {
        val height = height.toFloat()
        val width = width.toFloat()
        val inset = height * 0.18f
        bounds.set(0f, 0f, width, height)
        paint.style = Paint.Style.FILL
        paint.color = if (checked) theme.trackOn else theme.outline
        canvas.drawRoundRect(bounds, height / 2f, height / 2f, paint)
        val radius = height / 2f - inset
        val centreX = inset + radius + thumbFraction * (width - 2f * (inset + radius))
        paint.color = theme.surface
        canvas.drawCircle(centreX, height / 2f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = height * 0.06f
        paint.color = if (checked) theme.trackOn else theme.outline
        canvas.drawCircle(centreX, height / 2f, radius - paint.strokeWidth / 2f, paint)
    }

    private fun animateThumb() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(thumbFraction, if (checked) 1f else 0f).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                thumbFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        checked = !checked
        onToggle?.invoke(checked)
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    companion object {
        const val WIDTH_DP = 48
        const val HEIGHT_DP = 28
    }
}

/** Path-drawn disclosure chevron, so navigation affordances do not depend on a font glyph. */
class ChevronView(context: Context, private val theme: ProxyTheme) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = theme.dp(2).toFloat()
        color = theme.muted
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(theme.dp(WIDTH_DP), theme.dp(HEIGHT_DP))
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        canvas.drawLine(width * 0.25f, height * 0.18f, width * 0.75f, height * 0.5f, paint)
        canvas.drawLine(width * 0.75f, height * 0.5f, width * 0.25f, height * 0.82f, paint)
    }

    companion object {
        const val WIDTH_DP = 10
        const val HEIGHT_DP = 18
    }
}
