package com.shilapi.xcertplay.transport

/**
 * The last wired USB attempt of this launch: the configuration-switch result, the kernel's binding
 * snapshot, and the failure verdict. The diagnostic export and usbcheck both render this object, so
 * a bug report shows the evidence the session actually recorded instead of a re-derivation.
 */
object UsbSessionDiagnostics {
    private val lock = Any()
    private var attempt: String? = null
    private var bindings: String? = null
    private var verdict: UsbVerdict.Verdict? = null

    /** Records a fresh attempt; any verdict from an earlier attempt is no longer the last word. */
    fun recordAttempt(line: String) = synchronized(lock) {
        attempt = line
        verdict = null
    }

    fun recordSnapshot(summary: String) = synchronized(lock) { bindings = summary }

    fun recordVerdict(value: UsbVerdict.Verdict) = synchronized(lock) { verdict = value }

    /** The verdict of the current attempt, or null when nothing failed so far. */
    fun verdict(): UsbVerdict.Verdict? = synchronized(lock) { verdict }

    fun clear() = synchronized(lock) {
        attempt = null
        bindings = null
        verdict = null
    }

    fun report(): String = synchronized(lock) {
        listOfNotNull(
            attempt?.let { "lastConfigAttempt=$it" },
            bindings?.let { "lastBindings=$it" },
            verdict?.let { "${it.line}\nremedy=${it.remedy}" },
        ).joinToString("\n").ifEmpty { "no wired USB attempt recorded this launch" }
    }
}
