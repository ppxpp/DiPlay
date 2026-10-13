package com.shilapi.xcertplay.vehicle.adapters.pioneer

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.vehicle.api.MediaCommand
import com.shilapi.xcertplay.vehicle.api.SteeringWheelInputSource
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest

/** One PAL registration per process. A stopped session leaves an inert callback. */
internal object PalSteeringWheelInputAdapter : SteeringWheelInputSource {
    private var appContext: Context? = null
    @Volatile private var consumer: ((MediaCommand) -> Unit)? = null
    @Volatile private var report: (String) -> Unit = {}
    private var initialized = false
    private var registeredManager: Any? = null
    private var serviceCallback: Any? = null
    private var keyCallback: Any? = null
    private var lastEvent: String? = null
    private var lastAt = 0L

    fun configure(context: Context) { appContext = context.applicationContext }

    @Synchronized
    override fun start(onCommand: (MediaCommand) -> Unit, report: (String) -> Unit) {
        consumer = onCommand
        this.report = report
        lastEvent = null
        if (initialized) return
        val context = appContext ?: return
        try {
            val loader = loadSdk(context)
            // Fail here rather than crashing the SDK's asynchronous worker thread.
            loader.loadClass("com.google.gson.Gson")
            val manager = loader.loadClass("com.tencent.tai.pal.client.PALServiceManager")
            val callbackType = loader.loadClass("com.tencent.tai.pal.client.PALServiceManager\$d")
            serviceCallback = proxy(loader, callbackType, "DiPlayPalService") { name, _ ->
                when (name) {
                    "onConnected" -> synchronized(this) { register(loader, manager) }
                    "onDisconnected" -> synchronized(this) {
                        registeredManager = null
                        this.report("Vehicle: PAL disconnected")
                    }
                    "onError" -> this.report("Vehicle: PAL service error")
                }
                null
            }
            initialized = true
            manager.getMethod("a", Context::class.java, callbackType).invoke(null, context, serviceCallback)
            report("Vehicle: PAL initialization requested")
        } catch (error: Exception) {
            initialized = false
            report("Vehicle: PAL unavailable ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    @Synchronized
    override fun stop() { consumer = null; lastEvent = null }

    private fun loadSdk(context: Context): ClassLoader {
        // Provision this asset locally; proprietary SDK bytes are not committed to the fork.
        val bytes = context.assets.open("vehicle/pal-client.dex").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // Android 7 names optimized output after the input DEX. Sharing a directory
        // makes the optimizer overwrite/delete its input (DexPathList then becomes empty).
        val directory = File(context.filesDir, "vehicle-pal-sdk").apply { mkdirs() }
        val optimized = File(context.codeCacheDir, "vehicle-pal-opt").apply { mkdirs() }
        val dex = File(directory, "pal-$digest.dex")
        if (!dex.exists()) {
            val temporary = File(directory, "pal.tmp")
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.renameTo(dex)) { "PAL dex install failed" }
        }
        dex.setReadOnly()
        return DexClassLoader(dex.absolutePath, optimized.absolutePath, null, context.classLoader)
    }

    private fun register(loader: ClassLoader, managerClass: Class<*>) {
        try {
            val cabinType = loader.loadClass("com.tencent.tai.pal.client.c")
            val cabin = managerClass.getMethod("a", Class::class.java).invoke(null, cabinType) ?: return
            if (registeredManager === cabin) return
            val listenerType = loader.loadClass("com.tencent.tai.pal.client.c\$a")
            keyCallback = proxy(loader, listenerType, "DiPlayPalKeys") { name, args ->
                when (name) {
                    "i" -> {
                        val filterType = loader.loadClass("com.tencent.tai.pal.input.KeyFilter")
                        filterType.getConstructor().newInstance().also { filter ->
                            val add = filterType.getMethod("addFilter", Int::class.javaPrimitiveType)
                            add.invoke(filter, 8)
                            add.invoke(filter, 9)
                        }
                    }
                    "b" -> { args?.firstOrNull()?.let(::onEvent); null }
                    else -> null // Long presses and volume stay system-owned.
                }
            }
            cabinType.getMethod("a", listenerType).invoke(cabin, keyCallback)
            registeredManager = cabin
            report("Vehicle: PAL previous/next listener registered (shared input)")
        } catch (error: Exception) {
            report("Vehicle: PAL registration failed ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    @Synchronized
    private fun onEvent(event: Any) {
        val target = consumer ?: return
        runCatching {
            val type = event.javaClass
            val key = type.getMethod("getKeyCode").invoke(event) as Int
            val action = type.getMethod("getAction").invoke(event) as Int
            report("Vehicle: PAL key=$key action=$action")
            if (action != 1) return
            val command = when (key) { 8 -> MediaCommand.PREVIOUS; 9 -> MediaCommand.NEXT; else -> return }
            val signature = "$key:${type.getMethod("getSource").invoke(event)}:" +
                "${type.getMethod("getDownTime").invoke(event)}:${type.getMethod("getEventTime").invoke(event)}"
            val now = SystemClock.elapsedRealtime()
            if (signature == lastEvent && now - lastAt < 250) return
            lastEvent = signature
            lastAt = now
            target(command)
        }.onFailure { report("Vehicle: PAL event rejected ${it.javaClass.simpleName}") }
    }

    private fun proxy(loader: ClassLoader, type: Class<*>, label: String,
                      callback: (String, Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "toString" -> label
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> callback(method.name, args)
            }
        }
}
