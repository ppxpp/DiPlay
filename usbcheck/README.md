# 车机 USB CarPlay 协商入口验证

目标：通过 iPhone USB 打开 `com.apple.carkit.service`，完成 iAP2 链路及身份协商，收到 `IdentificationAccepted (0x1d02)` 后主动关闭。允许 Lockdown 配对及其 TLS；不调用 MFi 认证，不创建 VPN/AirPlay，不启动 CarPlay 会话。成功标记：`NEGOTIATION_ENTRY_OK` 和 `MFi_AUTH_NOT_STARTED`。仅 USB 接口占用成功不算最终成功。

## 首次安装

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :usbcheck:assembleDebug
adb install -r usbcheck/build/outputs/apk/debug/usbcheck-debug.apk
adb shell am start -n com.tencent.wecarflow/com.shilapi.xcertplay.usbcheck.UsbCheckActivity
```

包名 `com.tencent.wecarflow`，版本 `0.3.0-usbcheck`，debug APK 使用 Android debug 签名。如果已有同名包签名不同，覆盖会失败；不要自动卸载原包，先确认该包是否是需要保留的原厂应用。不同旧测试包的 USB 授权不会自动转移到本包，插入后需要重新允许。

可选：首次仍有 USB ADB 时执行 `adb shell pm grant com.tencent.wecarflow android.permission.READ_LOGS`，若 ROM 支持则导出可包含更多系统日志。执行失败也能运行检查；TCP 不会取得 shell/root 权限，dmesg 仍可能不可读。

## LAN 调试

1. 暂停 DiPlay 的会话及后台 USB 使用，让电脑与车机处于同一局域网。
2. 打开 USB 检查 APP，查看首页 IPv4、实际 TCP 端口和口令。
3. 首选 9911，绑定失败后尝试 9912、8721，绝不使用随机端口；5656 留给高德。绑定成功不代表网络放行，需要客户端拿到真实 `status` 响应。
4. 插入并解锁 iPhone，允许 USB 授权和信任。重新枚举时可能再次授权。

以下 IP 与口令均为示例，以首页显示为准：

```sh
export USBCHECK_TOKEN='首页口令'
python3 usbcheck/tools/usbcheck.py --host 192.168.105.77 --port 9911 status
python3 usbcheck/tools/usbcheck.py --host 192.168.105.77 --port 9911 run
python3 usbcheck/tools/usbcheck.py --host 192.168.105.77 --port 9911 logs --follow
python3 usbcheck/tools/usbcheck.py --host 192.168.105.77 --port 9911 stop
python3 usbcheck/tools/usbcheck.py --host 192.168.105.77 --port 9911 export --output usbcheck-report.zip
python3 usbcheck/tools/usbcheck.py --host 192.168.105.77 --port 9911 update usbcheck/build/outputs/apk/debug/usbcheck-debug.apk
```

更新前停止检查；APK 必须同包名、同签名。客户端完成上传后车机打开系统安装器，需要在车机确认。更新会结束进程，安装后重新打开 APP，再连接。若车机缺失安装器或禁止安装，日志明确提示，需恢复 USB ADB 安装。应用缓存保留已上传 APK，可在 `cache/usbcheck-update-*.apk` 找到。

导出需等待检查结束，ZIP 包含报告、完整 APP 文件日志、可读的 logcat/dmesg 和环境信息；系统日志受权限限制，没有日志不代表没有错误。日志文件跨检查保留，界面只展示最近 900 条。“清空日志”只清界面，不删证据。配对素材仅存在进程内存，不进入导出包；APP 重启后重新配对。

服务在页面退出后仍运行。恢复 DiPlay 前用首页“退出并关闭调试”，确保 USB 资源与监听端口释放。首次安装必须预先完成，APK 上传功能不能解决首次部署。

若无法连接，先检查本机 `route -n get <车机IP>` 是否走真实 LAN 网卡，避免 Clash/TUN 假连接。车机禁止 ICMP，ping 失败不能证明离线；ARP 是局域网旁证，有效 JSON status 才是应用通信证据。

## 三轮实验

每轮先拔插 iPhone，恢复 USB 状态，再运行；不要同时运行 DiPlay 或其他 USB 测试应用。

- `run --mode NEGOTIATE`：默认路径。读回实际活动配置，验证 USBMUX/NCM，再进行 Lockdown → carkit → iAP2 身份协商。各阶段独立显示；只有最终身份协商通过才输出成功标记。
- `run --mode USB_ONLY`：仅定位 USB 阻塞，不配对。全部通过仍不代表到达协商入口。
- `run --mode DETACH_NEGOTIATE`：对照实验，在模式请求之前，强制 claim/release 当前活动配置的 Apple 接口，再走默认协商检查。不是全局设备解绑；实验失败/结束后通过拔插恢复驱动。
- `run --mode NATIVE_NEGOTIATE`：默认路径仍失败时再使用。使用 UsbManager 授权的 FD，记录 SET_CONFIGURATION、GETDRIVER、CLAIM 的返回值和 errno。仅在 EBUSY 且读到非 usbfs 内核驱动时尝试 DISCONNECT_CLAIM（排除 usbfs）；成功的接口保持在同一连接上，继续实际协商路径。此模式不是提权，不保证厂商内核支持原子申请。

- `run --mode DISCONNECT_NEGOTIATE`：2026-10-14 真机原子申请成功、释放后切配置仍 EBUSY 的针对性对照。读取实际活动配置，只直接断开该配置的驱动，不经过 claim/release，然后紧接 SET_CONFIGURATION。遇到 usbfs 持有者不抢占，逐个记录断开 errno；目标配置读回及完整协商检查仍是通过标准。

- `run --mode LOW_POWER_DISCONNECT`：在首次授权及重新枚举后发送 Apple USB Power Capability 请求，extra current 为 0（不增加 USB 基础充电能力），然后执行已验证的直接断开驱动/切配置路径。请求拒绝则停止，成功仅代表手机接受请求；是否降低实际电流、避免过流掉线需要真机对照。本模式不修改系统过流保护。

对于插入后不到 1 秒就掉线的情况，先恢复 USB 端口、打开 APP、暂不插手机，再执行：

```sh
/usr/bin/python3 usbcheck/tools/usbcheck.py --host 192.168.105.77 --port 9911 arm --mode LOW_POWER_DISCONNECT
```

`arm` 只对下一次 Apple USB 插入触发一次检查，由 APP 自己处理 attach 事件，不依赖 TCP 轮询。启动后解除预设，重新枚举不再重复启动。`status` 返回 armedMode，`stop` 也会解除预设。重启进程后需要重新 arm。仍需要系统 USB 授权，未获授权前无法发送低充电请求。

## 如何判断

- `NEGOTIATION_ENTRY_OK`：目标通过，已进入 USB iAP2 身份协商并在 MFi 认证之前停止；不等于完整 CarPlay 已可用。
- 活动配置不是目标：优先排查配置切换，不把描述符列出配置 6 当成配置已激活。
- errno 16 / EBUSY：结合同一接口 GETDRIVER 判断。driver=usbfs 表示用户态接口持有，先排查其他连接；原子路径不会抢占它。
- errno 1 / EPERM 或 13 / EACCES：授权 FD 的操作仍被拒绝，说明该调用路径存在权限/策略限制；不能仅凭此判断硬件不支持。
- errno 22 / EINVAL 或 2 / ENOENT：检查配置、接口号、alternate setting 是否匹配，不先归因为缺少 root。
- errno 19 / ENODEV：设备已拔出或重枚举，重新发现和授权。
- 原子申请 errno 25 / ENOTTY：该 ioctl 不被支持，默认 Android API 是对照路径。
- `UNKNOWN`：证据不足；没有驱动绑定不能证明其他进程占用。

当前验证范围见开发交付消息：目标模块构建和本机协议冒烟不替代真实车机 USB 实测。只有真实日志显示上述成功标记才能判定目标达成。

0.2.5 在 usbcheck 配对等待中保持同一 Lockdown 连接与证书，不在用户输入密码时每秒重连/重设 UntrustedHostBUID。生产调用仍默认使用原路径，等待真机结果后再决定是否推广。导出 logcat 采集全部日志缓冲区。

0.2.6 修正测试身份生命周期：重新生成配对证书时同时生成新 HostID/SystemBUID，不复用旧 preferences 中的身份。成功记录仍只在内存中复用；不删除 iPhone 的既有配对记录。

## MFi / NCM / VPN capability check (0.3.0)

Use the replacement data cable. `run --mode MFI_AUTH` reuses direct driver disconnect, configuration readback, Lockdown and iAP2 identification, then executes the provisioned offline MFi exchange. Only iPhone AA05 means authentication passed. The existing private identity is packaged from `.private/runtime-assets/offline-mfi` (or DIPLAY_AUTH_ASSETS_DIR), with no generated replacement or remote fallback.

After authentication, a bounded raw NCM IPv6 probe sends ICMPv6 Echo to ff02::1, handles neighbor discovery for fe80::2, and counts received Ethernet frames without VPN. A matching Echo Reply proves a round trip. No reply is inconclusive because CarPlayStartSession/network session has not been advertised; the report marks this skipped rather than claiming a USB fault.

`status.vpn` and export vpn-status.json distinguish authorization, availability of the consent Activity, and existence of /dev/tun and /dev/net/tun. No tunnel is established by this probe. Missing consent UI does not prove the kernel lacks VPN support. MFI_AUTH stops without starting a complete AirPlay/CarPlay session. The NCM tx count records send attempts; a matching ICMPv6 Echo Reply is the evidence for actual bidirectional delivery.

2026-10-14 device result (0.3.0, replacement cable): Lockdown, carkit, iAP2 identification and MFi AA05 passed. Raw USB NCM received 20 Ethernet frames including a matching ICMPv6 Echo Reply, without VPN. `/dev/tun` and the VPN consent Activity exist; this app is not authorized and actual tunnel establishment was not tested. See `evidence/usbcheck-20261014/验证记录.md` and `mfi-ncm-roundtrip-success.zip`.

### Minimal VPN demo (0.3.3)

Use the `验证 VPN / TUN` button or TCP `vpn-demo` command, then accept the Android VPN consent dialog on the head unit. Results are persisted in `status.vpn.demo` and exported in vpn-status.json. The demo uses the same key Builder settings as DiPlay: fe80::2/64, route fe80::/64, MTU 1500, blocking TUN, and addAllowedApplication(packageName). It binds an IPv6 UDP socket with the actual TUN scope, reads its probe packet from TUN, writes a checksummed simulated peer reply, and checks the socket receives the matching payload/source. It closes the tunnel after success or bounded timeouts. While the IPv6-only VPN is active, LAN debug responses may pause; retry after the test closes it.

This isolates real VpnService establishment, scoped routing and bidirectional TUN I/O from USB. A PASS combined with the separately confirmed raw USB NCM roundtrip demonstrates both necessary transports are available; it does not verify the integrated TUN↔NCM bridge or a complete AirPlay session.

Current head-unit result: **FAIL before TUN creation**. `com.android.vpndialogs` is not installed, launching consent throws ActivityNotFoundException, and direct ordinary-app `establish()` without consent returns null. No ADB/appops authorization was granted. The user specifically requires an ordinary app with no privileged authorization dependency. Therefore the current firmware's normal VPN path is unavailable; kernel TUN support after privileged authorization remains untested. 0.3.3 records both failures and checks actual ActivityInfo instead of relying on explicit Intent.resolveActivity.

0.3.4 adds `打开 VPN 系统设置`: starts ACTION_VPN_SETTINGS with FLAG_ACTIVITY_NEW_TASK, falling back to ACTION_SETTINGS with the same flag if unavailable. The head unit can open its VPN settings screen, but currently shows an empty VPN list and no app authorization entry. Adding a legacy VPN profile does not grant this app permission to use VpnService. Return to the test app and rerun the demo to measure authorization after any manual settings change.
