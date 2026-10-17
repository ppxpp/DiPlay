package com.shihab.diplay.btproxy.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shihab.diplay.btproxy.ProxyController
import com.shihab.diplay.btproxy.R

/**
 * The log page owns its own scroll region, so the row list never nests inside another scroller.
 * At most 200 lines are retained, which makes a plain row container cheaper to reason about than an
 * AdapterView.
 */
class LogPanel(
    private val context: Context,
    private val theme: ProxyTheme,
    private val controller: ProxyController,
    private val onClearRequested: () -> Unit,
) {

    private val chips = theme.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }
    private val counter = theme.caption(context.getString(R.string.log_count, 0))
    private val rows = theme.column()
    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(rows)
    }
    private var renderedSignature = ""

    private val filters = listOf<Pair<String?, String>>(
        null to context.getString(R.string.log_filter_all),
        "GATEWAY" to "GATEWAY",
        "DISCOVERY" to "DISCOVERY",
        "SDP" to "SDP",
        "RFCOMM" to "RFCOMM",
        "IAP2" to "IAP2",
        "ACL" to "ACL",
        "PAIRING" to "PAIRING",
    )

    val view: View = theme.column().apply {
        // The page container is a FrameLayout, which needs its own LayoutParams type.
        layoutParams = FrameLayout.LayoutParams(-1, -1)
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(chips)
                layoutParams = theme.blockGap()
            },
        )
        addView(
            theme.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
                addView(theme.compactButton(context.getString(R.string.action_copy), ButtonRole.GHOST) { controller.copyLog() })
                addView(theme.compactButton(context.getString(R.string.action_clear), ButtonRole.GHOST) { onClearRequested() })
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = theme.dp(4) }
            },
        )
        addView(
            theme.switchRow(
                context.getString(R.string.log_autoscroll),
                null,
                controller.autoScroll,
            ) { controller.autoScroll = it }.first,
        )
        addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    init {
        rebuildChips()
    }

    private fun rebuildChips() {
        chips.removeAllViews()
        filters.forEach { (phase, label) ->
            chips.addView(
                theme.chip(label, controller.logFilter == phase) {
                    controller.logFilter = phase
                    rebuildChips()
                    renderedSignature = ""
                    refresh()
                }.apply {
                    layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                        marginEnd = theme.dp(8)
                    }
                },
            )
        }
    }

    fun refresh() {
        val lines = controller.filteredLogLines()
        counter.text = if (lines.isEmpty()) {
            context.getString(R.string.log_empty)
        } else {
            context.getString(R.string.log_count, lines.size)
        }
        val signature = lines.size.toString() + '\n' + lines.lastOrNull().orEmpty() + '\n' + (lines.firstOrNull().orEmpty())
        if (signature == renderedSignature) return
        renderedSignature = signature
        rows.removeAllViews()
        lines.forEach { rows.addView(rowView(styled(it))) }
        if (controller.autoScroll) {
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun rowView(text: CharSequence) = TextView(context).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ProxyTheme.TYPE_MONO)
        setTextColor(theme.text)
        setPaddingRelative(theme.dp(4), theme.dp(5), theme.dp(4), theme.dp(5))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun styled(line: String): CharSequence {
        val builder = SpannableStringBuilder(line)
        fun paint(range: IntRange, colour: Int, bold: Boolean = false) {
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            builder.setSpan(ForegroundColorSpan(colour), range.first, range.last + 1, flags)
            if (bold) builder.setSpan(StyleSpan(Typeface.BOLD), range.first, range.last + 1, flags)
        }
        TIMESTAMP.find(line)?.let { paint(it.range, theme.muted) }
        KEY_PHASE.find(line)?.let { paint(it.range, theme.accent) }
        KEY_RESULT.find(line)?.let { match ->
            val value = match.value.substringAfter("=")
            paint(
                match.range,
                when {
                    value.contains("FAIL") || value.contains("TIMEOUT") || value.contains("REJECT") ||
                        value.contains("MISSING") || value.contains("ERROR") || value.contains("DEAD") -> theme.danger
                    value.contains("NO_DATA") || value.contains("CANCEL") || value.contains("BUSY") ||
                        value.contains("UNAVAILABLE") -> theme.warning
                    value.contains("VERIFIED") || value.contains("CONNECTED") || value.contains("AUTHENTICATED") ||
                        value.contains("RECEIVED") || value.contains("REGISTERED") -> theme.success
                    else -> theme.text
                },
                bold = true,
            )
        }
        return builder
    }

    companion object {
        private val TIMESTAMP = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")
        private val KEY_PHASE = Regex("phase=\\w+")
        private val KEY_RESULT = Regex("result=\\w+")
    }
}
