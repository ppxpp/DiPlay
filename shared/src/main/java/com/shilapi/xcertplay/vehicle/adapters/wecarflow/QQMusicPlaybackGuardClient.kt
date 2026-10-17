package com.shilapi.xcertplay.vehicle.adapters.wecarflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.vehicle.api.LocalPlaybackGuardClient
import com.shilapi.xcertplay.vehicle.api.MediaCommand
import com.shilapi.xcertplay.vehicle.api.NowPlayingSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Connection protection switch; Binder supplies media callbacks and process-death cleanup. */
internal class QQMusicPlaybackGuardClient(
    context: Context,
    private val report: (String) -> Unit,
    private val onLost: () -> Unit,
    private val onCommand: (MediaCommand) -> Boolean,
    private val onAvailable: () -> Unit,
) : LocalPlaybackGuardClient {
    private val context = context.applicationContext
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val command = when (data.readInt()) {
                1 -> MediaCommand.PLAY; 2 -> MediaCommand.PAUSE
                4 -> MediaCommand.NEXT; 5 -> MediaCommand.PREVIOUS
                else -> null
            }
            val duplicate = if (code == 2) {
                val signature = data.readString().orEmpty()
                !com.shilapi.xcertplay.vehicle.adapters.pioneer.PalSteeringWheelInputAdapter.acceptBridgeEvent(signature)
            } else false
            val accepted = !closed && command != null && (duplicate || onCommand(command))
            reply?.writeNoException()
            reply?.writeInt(if (accepted) 1 else 0)
            return true
        }
    }
    private val connected = CountDownLatch(1)
    @Volatile private var peer: IBinder? = null
    @Volatile private var closed = false
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            peer = service
            connected.countDown()
            onAvailable()
        }
        override fun onServiceDisconnected(name: ComponentName) { peer = null; if (!closed) onLost() }
        override fun onBindingDied(name: ComponentName) { peer = null; if (!closed) onLost() }
        override fun onNullBinding(name: ComponentName) { connected.countDown() }
    }

    fun prepare(): Boolean = synchronized(this) {
        if (closed) return@synchronized false
        val service = context.packageManager.getServiceInfo(COMPONENT, 0)
        if (service.processName != "com.tencent.wecarflow:carplayguard") {
            report("Vehicle: bridge warm-up skipped; companion upgrade required")
            return@synchronized false
        }
        if (!bound) bound = context.bindService(Intent().setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE)
        report("Vehicle: isolated companion warm-up bound=$bound; playback not requested")
        bound
    }

    override fun acquire(): Boolean {
        try {
            synchronized(this) {
                if (closed) return false
                val service = context.packageManager.getServiceInfo(COMPONENT, 0)
                if (service.processName != "com.tencent.wecarflow:carplayguard") {
                    report("Vehicle: protection requires isolated companion upgrade; original app will not be started")
                    return false
                }
                if (!bound) bound = context.bindService(Intent().setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    report("Vehicle: native playback protection unavailable; companion service missing")
                    return false
                }
            }
            if (!connected.await(1500, TimeUnit.MILLISECONDS) || closed) {
                report("Vehicle: protection bind pending; will acquire when service connects")
                return false
            }
            val accepted = transact(1)
            report("Vehicle: native playback protection acknowledged=$accepted")
            return accepted
        } catch (error: Exception) {
            report("Vehicle: native playback protection failed ${error.javaClass.simpleName}: ${error.message}")
            return false
        }
    }

    private fun transact(code: Int): Boolean {
        val service = peer ?: return false
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeStrongBinder(callback)
            if (!service.transact(code, request, response, 0)) false else {
                response.readException()
                response.readInt() == 1
            }
        } finally { request.recycle(); response.recycle() }
    }

    /** Full retained media state for WeCarFlow widgets, independently of CEAM text output. */
    fun publish(snapshot: NowPlayingSnapshot): Boolean {
        if (closed) return false
        val service = peer ?: return false
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeStrongBinder(callback)
            request.writeString(snapshot.title)
            request.writeString(snapshot.artist)
            request.writeString(snapshot.album)
            request.writeLong(snapshot.durationMillis)
            request.writeLong(snapshot.elapsedMillis)
            request.writeInt(if (snapshot.playing) 1 else 0)
            if (!service.transact(4, request, response, 0)) false else {
                response.readException()
                response.readInt() == 1
            }
        } catch (error: Exception) {
            report("Vehicle: companion media state failed ${error.javaClass.simpleName}: ${error.message}")
            false
        } finally { request.recycle(); response.recycle() }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            runCatching { transact(2) }
                .onFailure { report("Vehicle: protection release failed ${it.javaClass.simpleName}") }
            peer = null
            if (bound) runCatching { context.unbindService(connection) }
            bound = false
        }
    }

    companion object {
        private val COMPONENT = ComponentName("com.tencent.wecarflow", "com.tencent.wecarflow.carplayguard.PlaybackGuardService")
        private const val DESCRIPTOR = "com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1"
    }
}
