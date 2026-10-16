package com.shilapi.xcertplay.pioneer

/**
 * Archived local alternatives without demonstrated benefit over upstream. Keep their source for
 * comparison, but do not enable them through stale preferences or firmware detection.
 * Proven USB batching, native transport and vehicle routing are independent of these switches.
 */
object LocalConnectionExperiments {
    const val TOUCH_COALESCING = false
    const val WIRED_START_REQUEST_RETRIES = false
    const val SHORT_CONTROL_POLL = false
    const val PRE_HANDSHAKE_DRAIN = false
    const val LOCKDOWN_PENDING_CONNECTION_REUSE = false
    const val WIRED_RECOVERY_BUDGET = false
    const val USB_CONFIGURATION_REUSE = false
    const val NATIVE_LONG_INITIAL_WRITE = false
    const val NCM_INTERFACE_REBUILD = false
    const val NATIVE_CHACHA = false

    fun summary(): String = "upstream-first touchCoalescing=$TOUCH_COALESCING " +
        "startRetries=$WIRED_START_REQUEST_RETRIES shortControlPoll=$SHORT_CONTROL_POLL " +
        "preHandshakeDrain=$PRE_HANDSHAKE_DRAIN pendingPairReuse=$LOCKDOWN_PENDING_CONNECTION_REUSE " +
        "wiredRecoveryBudget=$WIRED_RECOVERY_BUDGET configurationReuse=$USB_CONFIGURATION_REUSE " +
        "nativeLongInitialWrite=$NATIVE_LONG_INITIAL_WRITE ncmInterfaceRebuild=$NCM_INTERFACE_REBUILD " +
        "nativeChacha=$NATIVE_CHACHA"
}
