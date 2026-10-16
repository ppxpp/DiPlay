package com.shilapi.xcertplay.connection

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.progress.ConnectionOutcome
import com.shilapi.xcertplay.orchestration.progress.ConnectionProgress
import com.shilapi.xcertplay.orchestration.progress.ConnectionStep
import java.text.NumberFormat

/** A self-contained waiting-screen widget. Transport events go to the model, never this view. */
internal class ConnectionProgressPanel(context: Context) : LinearLayout(context) {
    private val history = ConnectionProgressHistory(context)
    private var model: ConnectionProgress? = null
    private var palette = DiPlayPalette.DARK
    private val summary = text(15f, true)
    private val nodes = LinearLayout(context).apply { orientation = VERTICAL }
    private val estimate = text(12f)
    private val cells = mutableListOf<Pair<TextView, TextView>>()
    private var layoutKey: Pair<Int, List<ConnectionStep>>? = null
    private var tickerPosted = false
    private var initialized = false
    private val tick = object : Runnable {
        override fun run() {
            tickerPosted = false
            if (!isShown || !isAttachedToWindow) return
            render()
            if (model?.snapshot()?.outcome in listOf(ConnectionOutcome.CONNECTING, ConnectionOutcome.RETRYING)) schedule()
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(summary, LayoutParams(-1, -2))
        addView(nodes, LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        addView(estimate, LayoutParams(-1, -2))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setNight(true)
        render()
        initialized = true
    }

    fun begin(config: CarPlayRuntimeConfig): ConnectionProgress = history.create(config, model).also(::bind)

    fun bind(progress: ConnectionProgress?) {
        model = progress
        render()
        schedule()
    }

    fun retrying() { model?.stop(ConnectionOutcome.RETRYING); render(); schedule() }
    fun failed() { model?.stop(ConnectionOutcome.FAILED); render() }
    fun cancelled() { model?.stop(ConnectionOutcome.CANCELLED); render() }

    fun setNight(night: Boolean) {
        palette = DiPlayPalette.of(night)
        background = GradientDrawable().apply { setColor(palette.surface); cornerRadius = dp(12).toFloat() }
        render()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); schedule() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); tickerPosted = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!initialized) return
        if (visibility == VISIBLE) schedule() else { removeCallbacks(tick); tickerPosted = false }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!initialized) return
        if (visibility == VISIBLE) schedule() else { removeCallbacks(tick); tickerPosted = false }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (initialized && w != oldw) render()
    }

    private fun schedule() {
        if (!tickerPosted && isAttachedToWindow && isShown) { tickerPosted = true; postDelayed(tick, 500) }
    }

    private fun render() {
        val state = model?.snapshot()
        val steps = state?.steps ?: ConnectionStep.entries
        val columns = if (width >= dp(560)) 6 else 3
        val key = columns to steps
        if (layoutKey != key) {
            layoutKey = key
            cells.clear()
            nodes.removeAllViews()
            steps.chunked(columns).forEach { group ->
                val row = LinearLayout(context)
                group.forEach { _ ->
                    val label = text(13f, true)
                    val time = text(12f)
                    val cell = LinearLayout(context).apply {
                        orientation = VERTICAL
                        setPadding(dp(3), dp(4), dp(3), dp(4))
                        addView(label, LayoutParams(-1, -2))
                        addView(time, LayoutParams(-1, -2))
                    }
                    row.addView(cell, LayoutParams(0, -2, 1f))
                    cells += label to time
                }
                nodes.addView(row, LayoutParams(-1, -2))
            }
        }
        summary.setTextColor(palette.primaryText)
        estimate.setTextColor(palette.secondaryText)
        if (state == null) {
            summary.text = context.getString(R.string.connect_progress_waiting)
            estimate.text = context.getString(R.string.connect_progress_learning)
        } else {
            val expected = state.expectedMillis
            summary.text = context.getString(R.string.connect_progress_summary,
                NumberFormat.getIntegerInstance().format(state.attempt), seconds(state.elapsedMillis), seconds(state.overallMillis))
            estimate.text = when (state.outcome) {
                ConnectionOutcome.RETRYING -> context.getString(R.string.connect_progress_retrying)
                ConnectionOutcome.FAILED -> context.getString(R.string.connect_progress_failed)
                ConnectionOutcome.CANCELLED -> context.getString(R.string.connect_progress_cancelled)
                ConnectionOutcome.COMPLETE -> context.getString(R.string.connect_progress_complete, seconds(state.elapsedMillis))
                ConnectionOutcome.CONNECTING -> when {
                    state.waitingForUser -> context.getString(R.string.connect_progress_user_wait)
                    expected == null -> context.getString(R.string.connect_progress_learning)
                    state.elapsedMillis >= expected || (state.remainingMillis ?: 0) == 0L ->
                        context.getString(R.string.connect_progress_overrun)
                    else -> context.getString(R.string.connect_progress_estimate,
                        seconds(state.remainingMillis ?: 0), seconds(expected),
                        NumberFormat.getIntegerInstance().format(state.historySamples))
                }
            }
        }
        steps.forEachIndexed { index, step ->
            val current = state != null && state.current == step && state.outcome != ConnectionOutcome.COMPLETE
            val visited = state != null && step in state.visited
            val finished = visited && !current
            val color = when {
                current && state?.outcome == ConnectionOutcome.FAILED -> palette.danger
                current -> palette.accent
                finished -> palette.success
                else -> palette.secondaryText
            }
            val marker = when { current -> "●"; finished -> "✓"; else -> "○" }
            val (label, time) = cells[index]
            label.text = "$marker ${context.getString(title(step))}"
            label.setTextColor(color)
            time.text = when {
                visited -> context.getString(R.string.connect_progress_seconds, seconds(state!!.durations.getValue(step)))
                state?.outcome == ConnectionOutcome.COMPLETE -> context.getString(R.string.connect_progress_skipped)
                else -> context.getString(R.string.connect_progress_pending)
            }
            time.setTextColor(palette.secondaryText)
        }
    }

    private fun title(step: ConnectionStep) = when (step) {
        ConnectionStep.PREPARATION -> R.string.connect_progress_preparation
        ConnectionStep.DEVICE -> R.string.connect_progress_device
        ConnectionStep.CONTROL -> R.string.connect_progress_control
        ConnectionStep.NETWORK -> R.string.connect_progress_network
        ConnectionStep.SESSION -> R.string.connect_progress_session
        ConnectionStep.VIDEO -> R.string.connect_progress_video
    }

    private fun seconds(millis: Long): String = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1
    }.format(millis / 1000.0)

    private fun text(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        gravity = Gravity.START
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
