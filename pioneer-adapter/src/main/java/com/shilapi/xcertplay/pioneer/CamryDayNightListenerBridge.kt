package com.shilapi.xcertplay.pioneer

import jp.pioneer.ceam.widget.MiscManager.MiscManagerListener

/** Loaded by name only after checking the head unit's CEAM classes. */
class CamryDayNightListenerBridge(private val changed: (Int) -> Unit) : MiscManagerListener() {
    override fun onDayNightChanged(mode: Int) = changed(mode)
}
