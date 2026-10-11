package com.shilapi.xcertplay.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.shilapi.xcertplay.orchestration.RemoteRfcommGateway
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Resolve a provisioned gateway identity afresh on every connection; never persist its IP. */
object RemoteGatewayDiscovery {
    @Suppress("DEPRECATION")
    fun connect(context: Context, gateway: RemoteRfcommGateway, log: (String) -> Unit): RemoteRfcommDuplexStream {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val candidates = LinkedBlockingQueue<NsdServiceInfo>()
        val failure = AtomicReference<String?>()
        val expectedName = RemoteRfcommProtocol.serviceName(gateway.gatewayBluetoothMac)
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                failure.set("Gateway discovery failed code=$code")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == expectedName || info.serviceName.startsWith("$expectedName (")) {
                    candidates.offer(info)
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        log("Gateway discovery started service=$expectedName")
        nsd.discoverServices(RemoteRfcommProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        try {
            while (System.nanoTime() < deadline) {
                failure.get()?.let { throw IOException(it) }
                val candidate = candidates.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val resolved = AtomicReference<NsdServiceInfo?>()
                val done = CountDownLatch(1)
                nsd.resolveService(candidate, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        log("Gateway discovery resolve failed code=$code")
                        done.countDown()
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolved.set(info)
                        done.countDown()
                    }
                })
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || !done.await(remaining, TimeUnit.NANOSECONDS)) break
                val info = resolved.get() ?: continue
                val identity = info.attributes["gateway"]?.decodeToString()
                if (!identity.equals(gateway.gatewayBluetoothMac, ignoreCase = true)) continue
                val host = info.host as? Inet4Address ?: continue
                if (info.port !in 1..65535) continue
                log("Gateway discovered address=${host.hostAddress} port=${info.port} source=nsd")
                val stream = RemoteRfcommDuplexStream.connect(host.hostAddress!!, info.port, gateway.token, log)
                if (!stream.gatewayBluetoothMac.equals(gateway.gatewayBluetoothMac, ignoreCase = true)) {
                    stream.close()
                    throw IOException("Gateway identity mismatch")
                }
                return stream
            }
            throw IOException("Gateway not discovered within 15 seconds; check Android A proxy and Wi-Fi")
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }
}
