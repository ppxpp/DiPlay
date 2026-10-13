package com.shilapi.xcertplay.btprobe

internal data class PairedDevice(val native: Any, val name: String, val address: String, val handle: Int) {
    override fun toString() = "$name · $address"
}

/** 使用车机启动类路径中的 SDK；仅调用查询方法，不改配对和 profile 配置。 */
internal class PioneerAdapter {
    private var type: Class<*>? = null
    private var adapter: Any? = null
    fun initialize() {
        type = Class.forName("jp.pioneer.ceam.bluetooth.BluetoothAdapter")
        adapter = type!!.getMethod("getDefaultAdapter").invoke(null)
            ?: error("先锋适配器为空")
    }
    fun query(name: String): Any? = type!!.getMethod(name).invoke(adapter)
    fun query(name: String, device: PairedDevice): Any? =
        type!!.getMethod(name, device.native.javaClass).invoke(adapter, device.native)
    fun paired(): List<PairedDevice> = (query("getBondedDevices") as? List<*>).orEmpty().filterNotNull().map {
        val cls = it.javaClass
        PairedDevice(it, cls.getMethod("getName").invoke(it).toString(),
            cls.getMethod("getAddress").invoke(it).toString(),
            (cls.getMethod("getHandler").invoke(it) as Number).toInt())
    }
}
