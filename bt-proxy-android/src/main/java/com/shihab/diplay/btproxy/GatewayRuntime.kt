package com.shihab.diplay.btproxy

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Where the relay is right now, from nothing running to fully relaying. */
enum class ProxyStage { IDLE, STARTING, LISTENING, DISCOVERED, AUTHENTICATED, RFCOMM_CONNECTING, RELAYING, STOPPED, FAILED }

/**
 * Everything the proxy screen needs about a running or stopped gateway.
 *
 * Mutations must happen on the main thread. `RfcommGatewayServer` reports from relay threads, so
 * [GatewayService] posts those callbacks before updating this snapshot.
 */
data class ProxySnapshot(
    val stage: ProxyStage = ProxyStage.IDLE,
    val headline: String = "",
    val detail: String = "",
    val listeningPort: Int = 0,
    val discoveryPort: Int = 0,
    val discoveryPeer: String = "",
    val dataPeer: String = "",
    val bytesFromIphone: Long = 0,
    val bytesToIphone: Long = 0,
    val sessionStartedMs: Long = 0,
    val targetName: String = "",
    val targetAddress: String = "",
    val serviceRunning: Boolean = false,
    val blocked: Boolean = false,
) {
    /** Counter-only changes repaint the live region instead of rebuilding the screen. */
    fun structureEquals(other: ProxySnapshot) =
        stage == other.stage &&
            serviceRunning == other.serviceRunning &&
            blocked == other.blocked &&
            headline == other.headline &&
            detail == other.detail &&
            targetAddress == other.targetAddress &&
            listeningPort == other.listeningPort &&
            discoveryPort == other.discoveryPort
}

/** Process-wide hand-off from the foreground service to the UI, replacing the old polling loop. */
object GatewayRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(ProxySnapshot) -> Unit>()

    @Volatile
    var snapshot = ProxySnapshot()
        private set

    fun update(transform: (ProxySnapshot) -> ProxySnapshot) {
        val next = transform(snapshot)
        if (next == snapshot) return
        snapshot = next
        main.post { listeners.forEach { it(next) } }
    }

    fun addListener(listener: (ProxySnapshot) -> Unit) {
        listeners.add(listener)
        main.post { listener(snapshot) }
    }

    fun removeListener(listener: (ProxySnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
