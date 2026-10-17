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

## USB 蓝牙模块测试页（`UsbBtProbeActivity`）

验证 OTG 上的 USB 蓝牙适配器（HCI Rf Transport，USB 类 0xE0）能否由普通 App 直接驱动，用于 CarPlay 协商的前置判断。左侧按钮或 ADB 触发：

```sh
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.UsbBtProbeActivity --es probe_action usb_bt
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.UsbBtProbeActivity --es probe_action usb_bt_fast
```

分步取证：枚举并识别 0xE0/0x01/0x01 接口 → USB 权限（系统弹窗需点"确定"）→ `GET_CONFIGURATION`/占用全部无线接口 → `CLEAR_FEATURE(ENDPOINT_HALT)` → 发 HCI Reset / Read Local Version 并读回事件（中断 IN 与 bulk IN 都轮询，`bulkTransfer` 连续失败后改 `UsbRequest`+`requestWait`）。

`usb_bt_fast` 只做"占用全部接口 + 一条 HCI Reset"，3 秒窗口。**小米/MIUI 会在约 10 秒级回收探针进程**，长测试的尾部证据会丢，因此判定实验用快测跑。

2026-10-16 小米 13（Android 15，无 root）+ Actions ATS2851（`10d7:b012`，绿联 CM591 方案）实测：枚举/权限/占用/配置/写 HCI 命令全部成功，中断 IN 与 bulk IN 在任何读取方式下都收不到 HCI 事件。已排除设备未配置、端点选错、残留 halt、模块需要固件（主线 `btusb.c` 对 `10d7:0xb012` 只有 `BTUSB_ACTIONS_SEMI` 的 `HCI_QUIRK_BROKEN_*` 标记，无 `.firmware`）。剩余歧义与决定性对照实验（换 CSR8510 `0a12:0001` 重跑本页）见报告判定行。该机型内核没有 `btusb`/`/sys/class/bluetooth`，所以 root 后走 BlueZ 也不可用。

## 原生 URB 归因实验（`src/main/cpp/usb_bt_hci.c`）

Java 的 `bulkTransfer` 只回 `-1`，分不清"设备没数据"和"内核拒绝"。原生实验借用同一个已授权 fd，把三件事分开测：

| 判据 | 含义 |
| --- | --- |
| `SYNC_BULK ret=4` | 同步 `USBDEVFS_BULK` 把 HCI Reset 送达设备（USB 层已 ACK），写通路可用 |
| `SYNC_IN ret=-1 errno=110` | 同步读 interrupt IN 返回 `ETIMEDOUT` —— 内核确实去取了，是设备没有数据；权限/参数问题会是 `EPERM/EINVAL/ENOENT` |
| `IN_ARMED / OUT_STATUS / IN_STATUS=none` | 异步 `SUBMITURB` 提交成功但从不完成（同 fd 上同步路径正常）——本 App 的 HCI 传输只能走同步阻塞读写 |

2026-10-16 小米 13（Android 15，无 root）+ Actions ATS2851（`10d7:b012`）两次运行结果一致：

```
GETDRIVER if=0 ret=0 errno=0 driver=usbfs | IN_ARMED=3 | SYNC_BULK ret=4 errno=0 |
IN_STATUS=none reap_errno=0 | IN_EVENTS=0 | OUT_STATUS=none reap_errno=0 | SYNC_IN ret=-1 errno=110
```

判定：**App 的 USB 读写通路可用，模块对 HCI Reset 不回任何事件 → 归因于模块，不是 Android 权限层。**
下一步归因实验：换无固件依赖、Linux 用普通 HCI 即可驱动的模块（CSR8510 `0a12:0001`）跑 `usb_bt_fast`。

报告：`/sdcard/Android/data/<实际包名>/files/bluetooth-negotiation.txt`。音源、MainToken/MixToken 和输出总线需同时由 ADB 保存 `dumpsys mediamanager.mediamanagerservice`、`dumpsys media.audio_flinger` 与 `dumpsys media.audio_policy`。

USB 蓝牙页报告：`run-as com.tencent.wecarflow cat files/reports/usb-bluetooth.txt`。
