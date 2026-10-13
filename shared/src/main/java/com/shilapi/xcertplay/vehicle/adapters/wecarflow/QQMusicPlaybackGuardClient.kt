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

/** Explicit, acknowledged lease. The peer releases it when this process's Binder dies. */
internal class QQMusicPlaybackGuardClient(
    context: Context,
    private val report: (String) -> Unit,
    private val onLost: () -> Unit,
    private val onCommand: (MediaCommand) -> Boolean,
) : LocalPlaybackGuardClient {
    private val context = context.applicationContext
    private val owner = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val uid = context.packageManager.getApplicationInfo(COMPONENT.packageName, 0).uid
            check(Binder.getCallingUid() == uid) { "Untrusted media command callback" }
            val command = when (data.readInt()) {
                1 -> MediaCommand.PLAY; 2 -> MediaCommand.PAUSE
                4 -> MediaCommand.NEXT; 5 -> MediaCommand.PREVIOUS
                else -> null
            }
            val accepted = !closed && command != null && onCommand(command)
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
        }
        override fun onServiceDisconnected(name: ComponentName) { peer = null; if (!closed) onLost() }
        override fun onBindingDied(name: ComponentName) { peer = null; if (!closed) onLost() }
        override fun onNullBinding(name: ComponentName) { connected.countDown() }
    }

    override fun acquire(): Boolean {
        try {
            synchronized(this) {
                if (closed) return false
                if (!bound) bound = context.bindService(Intent().setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    report("Vehicle: native playback protection unavailable; companion service missing")
                    return false
                }
            }
            if (!connected.await(1500, TimeUnit.MILLISECONDS) || closed) return false
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
            request.writeStrongBinder(owner)
            if (!service.transact(code, request, response, 0)) false else {
                response.readException()
                response.readInt() == 1
            }
        } finally { request.recycle(); response.recycle() }
    }

    /** Fallback for the fixed WeCarFlow widget service; publish only under our own lease. */
    fun publish(snapshot: NowPlayingSnapshot): Boolean {
        if (closed) return false
        val service = peer ?: return false
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeStrongBinder(owner)
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
            report("Vehicle: instrument fallback failed ${error.javaClass.simpleName}: ${error.message}")
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
