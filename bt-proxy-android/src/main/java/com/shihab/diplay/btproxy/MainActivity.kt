package com.shihab.diplay.btproxy

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.btproxy.ui.ButtonRole
import com.shihab.diplay.btproxy.ui.DevicesActivity
import com.shihab.diplay.btproxy.ui.DiagnosticsActivity
import com.shihab.diplay.btproxy.ui.LogActivity
import com.shihab.diplay.btproxy.ui.PipelineView
import com.shihab.diplay.btproxy.ui.ProxyActivity
import com.shihab.diplay.btproxy.ui.ReadinessActivity
import com.shihab.diplay.btproxy.ui.SettingsActivity
import com.shihab.diplay.btproxy.ui.SwitchView
import com.shihab.diplay.btproxy.ui.metricCell
import com.shihab.diplay.btproxy.ui.navRow

/**
 * Entry screen: the start switch, the iPhone choice, the readiness summary and the live relay
 * state. Everything else opens its own activity.
 */
class MainActivity : ProxyActivity() {

    override val titleText: String get() = getString(R.string.app_name)
    override val isEntryScreen: Boolean get() = true

    private val pipeline by lazy { PipelineView(this, theme) }
    private var headlineView: TextView? = null
    private var detailView: TextView? = null
    private var metricDown: TextView? = null
    private var metricUp: TextView? = null
    private var metricSession: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening the app is the old auto-start hook; the service reads the flag itself.
        GatewayService.start(this)
    }

    override fun buildContent(parent: LinearLayout) {
        val snapshot = controller.snapshot
        parent.addView(
            scrollable(pageBody().apply {
                addView(masterCard(snapshot))
                addView(statusCard(snapshot))
                addView(entriesCard())
                if (controller.gatewayPausedForDiagnostics) addView(pausedCard())
            }),
        )
        renderLive(snapshot)
    }

    /** Prerequisites first, the iPhone choice next, and the service action as the last word. */
    private fun masterCard(snapshot: ProxySnapshot): android.view.View {
        val card = card()
        val items = controller.readiness()
        val blockers = items.filter { it.state != ReadinessState.READY }
        card.addView(
            theme.navRow(
                title = getString(R.string.nav_readiness),
                value = if (blockers.isEmpty()) {
                    getString(R.string.readiness_all_set)
                } else {
                    getString(
                        R.string.readiness_short_summary,
                        items.size - blockers.size,
                        items.size,
                        blockers.joinToString("、") { it.title },
                    )
                },
                valueColour = if (blockers.isEmpty()) theme.success else theme.warning,
            ) { startActivity(Intent(this, ReadinessActivity::class.java)) },
        )
        card.addView(
            theme.navRow(
                title = getString(R.string.nav_target),
                value = targetSummary(snapshot),
                valueColour = if (controller.devices.isEmpty()) theme.danger else theme.text,
            ) { startActivity(Intent(this, DevicesActivity::class.java)) },
        )
        val toggle = SwitchView(this, theme).apply { checked = snapshot.serviceRunning }
        headlineView = theme.note("").apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(
            theme.row().apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(
                    theme.column().apply {
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = theme.dp(12) }
                        addView(
                            theme.title(
                                getString(
                                    if (snapshot.serviceRunning) R.string.master_switch_stop
                                    else R.string.master_switch_start,
                                ),
                            ),
                        )
                        addView(headlineView)
                    },
                )
                addView(toggle)
            },
        )
        toggle.onToggle = { on -> if (on) controller.startGateway() else controller.stopGateway() }
        theme.normalizeSpacing(card)
        return card
    }

    private fun targetSummary(snapshot: ProxySnapshot): String {
        val device = controller.devices.firstOrNull { it.address == controller.selectedAddress }
            ?: controller.devices.firstOrNull { it.address == snapshot.targetAddress }
        return if (device == null) {
            getString(R.string.target_none)
        } else {
            "${controller.deviceLabel(device)} · ${device.address.takeLast(8)}"
        }
    }

    private fun statusCard(snapshot: ProxySnapshot): android.view.View {
        val card = card()
        card.addView(theme.title(getString(R.string.status_section)))
        card.addView(reattach(pipeline))
        val down = theme.metricCell("—", getString(R.string.metric_down), theme.text)
        val up = theme.metricCell("—", getString(R.string.metric_up), theme.text)
        val session = theme.metricCell("--:--", getString(R.string.metric_session), theme.text)
        metricDown = down.value
        metricUp = up.value
        metricSession = session.value
        card.addView(
            theme.row().apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(down.container)
                addView(up.container)
                addView(session.container)
            },
        )
        detailView = theme.mono("")
        card.addView(detailView)
        theme.normalizeSpacing(card)
        return card
    }

    private fun entriesCard(): android.view.View {
        val card = card()
        card.addView(
            theme.navRow(
                getString(R.string.diagnostics_section),
                getString(R.string.diagnostics_entry_summary),
                theme.muted,
            ) { startActivity(Intent(this, DiagnosticsActivity::class.java)) },
        )
        card.addView(
            theme.navRow(
                getString(R.string.log_section),
                getString(R.string.log_entry_summary),
                theme.muted,
            ) { startActivity(Intent(this, LogActivity::class.java)) },
        )
        card.addView(
            theme.navRow(
                getString(R.string.page_settings),
                getString(R.string.settings_entry_summary),
                theme.muted,
            ) { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        theme.normalizeSpacing(card)
        return card
    }

    override fun onLiveChanged(snapshot: ProxySnapshot) {
        renderLive(snapshot)
    }

    override fun onLogChanged() {
        renderToolbar()
        renderLive(controller.snapshot)
    }

    private fun renderLive(snapshot: ProxySnapshot) {
        pipeline.stages = PipelineView.stagesFor(snapshot, this)
        headlineView?.text = snapshot.headline.ifBlank { getString(R.string.gateway_idle) }
        headlineView?.setTextColor(
            when {
                snapshot.blocked -> theme.danger
                snapshot.stage == ProxyStage.RELAYING -> theme.success
                snapshot.serviceRunning -> theme.text
                else -> theme.muted
            },
        )
        metricDown?.text = ProxyController.humanBytes(snapshot.bytesFromIphone)
        metricUp?.text = ProxyController.humanBytes(snapshot.bytesToIphone)
        metricSession?.text = ProxyController.duration(snapshot.sessionStartedMs)
        detailView?.text = listOf(
            if (snapshot.listeningPort > 0) {
                getString(R.string.detail_ports, snapshot.listeningPort, snapshot.discoveryPort)
            } else {
                getString(R.string.detail_ports_unknown)
            },
            getString(R.string.detail_discovery, snapshot.discoveryPeer.ifBlank { "—" }),
            getString(R.string.detail_data, snapshot.dataPeer.ifBlank { "—" }),
        ).joinToString("\n")
    }
}
