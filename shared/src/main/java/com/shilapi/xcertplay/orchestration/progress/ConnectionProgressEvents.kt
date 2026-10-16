package com.shilapi.xcertplay.orchestration.progress

import com.shilapi.xcertplay.orchestration.CarPlayStatus

/** Maps typed lifecycle events rather than parsing translated status/log strings. */
fun ConnectionProgress.status(status: CarPlayStatus) {
    when (status) {
        CarPlayStatus.DiscoveringMfi, CarPlayStatus.MfiReady -> step(ConnectionStep.PREPARATION)
        CarPlayStatus.WaitingForMfi, CarPlayStatus.RequestingMfiPermission -> step(ConnectionStep.PREPARATION, true)
        CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForReenumeration,
        CarPlayStatus.SelectingConfiguration, CarPlayStatus.OpeningDataPaths,
        CarPlayStatus.ConnectingBluetooth -> step(ConnectionStep.DEVICE)
        CarPlayStatus.WaitingForIphone, CarPlayStatus.RequestingIphonePermission,
        CarPlayStatus.WaitingForPairedIphone -> step(ConnectionStep.DEVICE, true)
        CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady,
        CarPlayStatus.AttachingNetwork -> step(ConnectionStep.NETWORK)
        CarPlayStatus.Pairing, CarPlayStatus.ConnectingControl,
        CarPlayStatus.RunningWireless, CarPlayStatus.RunningControl -> step(ConnectionStep.CONTROL)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> sessionRequested()
        CarPlayStatus.ControlEnded, is CarPlayStatus.Failed -> stop(ConnectionOutcome.FAILED)
    }
}
