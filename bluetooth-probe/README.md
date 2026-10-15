# CarPlay 蓝牙协商测试

独立测试 APK，1280×720 横屏，默认包名 `com.tencent.wecarflow`（沿用车机已安装探针的身份）。依赖当前 DiPlay `shared`，最低 Android 6.0。

在项目根执行 `./gradlew :bluetooth-probe:assembleDebug`。输出 `bluetooth-probe/build/outputs/apk/debug/bluetooth-probe-debug.apk`。

上下文与接口证据见 [先锋蓝牙协商上下文](../../先锋蓝牙协商上下文.md)。第一版尚未接通先锋字节收发，不代表完成 iAP2/MFi/无线 CarPlay。后续以实机分阶段验证为主。

2026-10-14 增加音源清单与短时 PCM 验证。车机框架会将 `com.tencent.wecarflow` 的 AudioTrack 强制映射到 401；比较其它流时，用 `-PprobeApplicationId=com.tencent.mm` 构建对照探针。两种身份在本车机上均已有测试 APK，只使用 `adb install -r -t` 覆盖升级，不卸载。覆盖其它真实应用前应先确认已安装的是探针。

ADB 可启动一次验证（Activity 已运行时也能接收命令）：

```sh
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action audio_inventory
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action bluetooth
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action audio --ei source 300 --ei stream 401 --ei focus_stream 401 --ei duration_ms 6000
```

`audio` 播放低幅度 440Hz 双声道 PCM，非阻塞写入，默认结束后释放音轨、焦点并恢复原音源。可选 `--es token <令牌>` 设置音轨令牌。报告记录请求参数和实际音轨属性；扬声器听感不能用播放头增长代替。蓝牙自动验证仍保留空闲门禁、独立回调槽和本次请求清理规则。

报告：`/sdcard/Android/data/<实际包名>/files/bluetooth-negotiation.txt`。音源、MainToken/MixToken 和输出总线需同时由 ADB 保存 `dumpsys mediamanager.mediamanagerservice`、`dumpsys media.audio_flinger` 与 `dumpsys media.audio_policy`。
