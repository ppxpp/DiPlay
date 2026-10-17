package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.io.IOException

/** Owns discovery/handshake resources until a stream is published by its connection generation. */
class RemoteGatewayConnection : Closeable {
    @Volatile private var cancelled = false
    private var resource: Closeable? = null

    fun checkActive() {
        if (cancelled) throw IOException("Gateway connection cancelled")
    }

    @Synchronized fun track(value: Closeable) {
        if (cancelled) {
            runCatching { value.close() }
            checkActive()
        }
        check(resource == null) { "Gateway connection already owns a resource" }
        resource = value
    }

    @Synchronized fun release(value: Closeable) {
        if (resource === value) resource = null
    }

    override fun close() {
        val owned = synchronized(this) {
            cancelled = true
            resource.also { resource = null }
        }
        runCatching { owned?.close() }
    }
}
