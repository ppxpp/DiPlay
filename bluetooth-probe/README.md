# CarPlay 蓝牙协商测试

独立测试 APK，1280×720 横屏，包名 `com.shilapi.xcertplay.btprobe`。依赖当前 DiPlay `shared`，最低 Android 6.0。

在项目根执行 `./gradlew :bluetooth-probe:assembleDebug`。输出 `bluetooth-probe/build/outputs/apk/debug/bluetooth-probe-debug.apk`。

上下文与接口证据见 [先锋蓝牙协商上下文](../../先锋蓝牙协商上下文.md)。第一版尚未接通先锋字节收发，不代表完成 iAP2/MFi/无线 CarPlay。后续以实机分阶段验证为主。
