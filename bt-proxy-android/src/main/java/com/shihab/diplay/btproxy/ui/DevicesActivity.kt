package com.shihab.diplay.btproxy.ui

import android.widget.LinearLayout
import com.shihab.diplay.btproxy.R
import com.shihab.diplay.btproxy.ReadinessAction

/** Single-choice list of bonded phones, replacing the old Spinner. */
class DevicesActivity : ProxyActivity() {

    override val titleText: String get() = getString(R.string.target_section)

    override fun buildContent(parent: LinearLayout) {
        val card = card()
        if (controller.devices.isEmpty()) {
            card.addView(theme.note(getString(R.string.target_empty)))
            card.addView(
                theme.compactButton(getString(R.string.open_bluetooth_settings), ButtonRole.OUTLINED) {
                    perform(ReadinessAction.PAIR_SETTINGS)
                },
            )
        } else {
            controller.devices.forEach { device ->
                card.addView(
                    theme.deviceRow(
                        name = controller.deviceLabel(device),
                        detail = device.address.takeLast(8),
                        marker = if (controller.snapshot.targetAddress == device.address) {
                            getString(R.string.target_current)
                        } else {
                            null
                        },
                        selected = device.address == controller.selectedAddress,
                    ) { controller.selectDevice(device.address) },
                )
            }
        }
        theme.normalizeSpacing(card)
        parent.addView(card)
    }
}
