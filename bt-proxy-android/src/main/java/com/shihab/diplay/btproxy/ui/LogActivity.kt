package com.shihab.diplay.btproxy.ui

import android.widget.LinearLayout
import com.shihab.diplay.btproxy.R

/** The retained log, with phase filters, auto-scroll, copy and clear. */
class LogActivity : ProxyActivity() {

    override val titleText: String get() = getString(R.string.log_section)

    private var panel: LogPanel? = null

    override fun buildContent(parent: LinearLayout) {
        val existing = panel ?: LogPanel(this, theme, controller) { confirmClearLog() }.also { panel = it }
        parent.addView(reattach(existing.view), LinearLayout.LayoutParams(-1, -1))
        existing.refresh()
    }

    override fun onLogChanged() {
        panel?.refresh()
    }
}
