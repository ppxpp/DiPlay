package com.shihab.diplay.btproxy.ui

import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import com.shihab.diplay.btproxy.R
import com.shihab.diplay.btproxy.ReadinessAction

/** Pairing material and the system entries an installer needs. */
class SettingsActivity : ProxyActivity() {

    override val titleText: String get() = getString(R.string.page_settings)

    override fun buildContent(parent: LinearLayout) {
        val card = card()
        card.addView(theme.title(getString(R.string.pairing_section)))
        card.addView(theme.note(getString(R.string.pairing_hint)))
        val code = controller.pairingCode()
        if (code == null) {
            card.addView(theme.body(getString(R.string.pairing_unavailable)))
            card.addView(
                theme.compactButton(getString(R.string.action_fill), ButtonRole.OUTLINED) { askForBluetoothAddress() },
            )
        } else {
            card.addView(
                theme.label(
                    code.chunked(4).joinToString("  "),
                    ProxyTheme.TYPE_CODE,
                    theme.accent,
                    medium = true,
                    mono = true,
                ).apply { gravity = Gravity.CENTER },
            )
            controller.pairingAddress()?.let { mac ->
                card.addView(theme.caption("${getString(R.string.pairing_local_mac)} · $mac"))
            }
            card.addView(
                theme.compactButton(getString(R.string.pairing_copy_code), ButtonRole.TONAL) {
                    controller.copyText(code)
                },
            )
        }
        theme.normalizeSpacing(card)
        parent.addView(card)

        val links = card()
        links.addView(
            theme.navRow(
                getString(R.string.open_bluetooth_settings),
                getString(R.string.pairing_mac_hint),
                theme.muted,
            ) { perform(ReadinessAction.BLUETOOTH_SETTINGS) },
        )
        links.addView(
            theme.navRow(
                getString(R.string.action_refresh),
                getString(R.string.readiness_title),
                theme.muted,
            ) { controller.refreshEnvironment() },
        )
        theme.normalizeSpacing(links)
        parent.addView(links)

        val about = card()
        about.addView(
            theme.navRow(
                getString(R.string.page_about),
                getString(R.string.version_caption, packageVersion()),
                theme.muted,
            ) { startActivity(Intent(this, AboutActivity::class.java)) },
        )
        theme.normalizeSpacing(about)
        parent.addView(about)
    }
}
