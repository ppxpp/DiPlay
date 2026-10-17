package com.shihab.diplay.btproxy.ui

import android.widget.LinearLayout
import com.shihab.diplay.btproxy.R

/** Bounded link diagnostics. Each action reports its last structured outcome inline. */
class DiagnosticsActivity : ProxyActivity() {

    override val titleText: String get() = getString(R.string.diagnostics_section)

    override fun buildContent(parent: LinearLayout) {
        val card = card()
        card.addView(theme.note(getString(R.string.diagnostics_intro)))
        card.addView(theme.button(getString(R.string.query_sdp), ButtonRole.TONAL) { controller.querySdp() })
        card.addView(theme.note(getString(R.string.diagnostics_sdp_note)))
        card.addView(outcomeLine("SDP"))
        card.addView(theme.button(getString(R.string.connect_rfcomm), ButtonRole.TONAL) { controller.connectRfcomm() })
        card.addView(theme.note(getString(R.string.diagnostics_rfcomm_note)))
        card.addView(outcomeLine("RFCOMM"))
        card.addView(theme.button(getString(R.string.handshake_iap2), ButtonRole.TONAL) { controller.handshakeIap2() })
        card.addView(theme.note(getString(R.string.diagnostics_iap2_note)))
        card.addView(outcomeLine("IAP2"))
        card.addView(theme.button(getString(R.string.disconnect), ButtonRole.OUTLINED) { controller.disconnectProbe() })
        theme.normalizeSpacing(card)
        parent.addView(card)
        if (controller.gatewayPausedForDiagnostics) parent.addView(pausedCard())
    }
}
