# 先锋车机 USB 适配与 DiPlay 局域网调试

本模块把已在 usbcheck 成功验证的 USB 接管方式接入正式 DiPlay，并提供手机占用 USB 时的 TCP 调试。它不实现另一套 CarPlay 协议，不自动授予 VPN 权限。

整体数据流、线程调度、缓冲与性能卡点见根目录 [lwIP 技术架构文档](../../2026-10-14-DiPlay接入lwIP后的技术架构与性能卡点.md)。首次成功后重连仍不稳定，当前最新失败位于 USB 网络发送恢复，不能将下述成功记录理解为稳定性已解决。

## 按优化方案编写的新版本

`0.2.19-pioneer-lwip6-hud-test` / versionCode 49 已完成代码和本机验证，尚未进行车机实测。新增单独USB发送线程、有界FIFO、分阶段截止时间、有限接口/整轮恢复、触控MOVE合并、显式端口租约及分段直方图。原有视频参考链和媒体参数保持基线，先用新计时定位再做参数对照。完整实现与验证边界见根目录架构文档第17节。

TCP调试新增 `lwip-diagnostics --mode ON|OFF`，可以即时切换聚合计时做观测开销对照；`status`包含会话阶段时间线和队列状态。现有 `stop`、`start-wired`、`logs`、`export`、`update`仍可使用。

## 最新车机实测：lwIP 已成功建立有线 CarPlay

成功版 `0.2.18-pioneer-lwip3-hud-test` / versionCode 46，包名仍为 `com.gtmc.app.fengyunyx.b819`。车机采用新线、USB充电设置0.5A。配对/MFi通过，lwIP TCP握手、AirPlay加密、事件/视频TCP、媒体/导航UDP均建立，硬件解码首帧与持续视频、触控往返、AudioTrack播放进度有日志。实际听感、麦克风/Siri、重启/拔插重连和长时稳定性尚未全面验证。

目标USB适配启用时默认LWIP，不建立TUN，不依赖VPN授权。原VPN只作为可切换对照保留；普通车机继续原方案。TCP调试保持LAN8721可达。lwIP从NCM帧学习手机IPv6/MAC，以本APP UID的本机端口转发到127.0.0.1，代理源127.0.0.2；NTP/麦克风手机端口各有轻量注册调用。原CarPlay加密、音视频逻辑保留。实现原理见 [LWIP验证路线.md](LWIP验证路线.md)，实测见 [成功记录](../evidence/pioneer-usb-integration-20261014/lwIP有线CarPlay成功记录.md)。

网络路线切换：先stop，等待会话结束，执行 `network-backend --mode VPN` 或 `network-backend --mode LWIP`，再start-wired。VPN对照仍需授权且受本车机tun0接口过滤；不要把原VPN失败误判为USB失败。`network`读取固定系统诊断项，export包括network.json。lwIP状态由status.networkBackend/lwipActive/lwipStatus确认。

下文的USB1/VPN内容是初次接入时的记录，最新进展以上述成功版及证据为准。

## 已实现与待实测

- USB：直接断开当前配置的内核驱动 → 立即切换到 CarPlay 配置 → 读回确认 → 申请并持续持有接口。代码来自已实测成功的 usbcheck native 路径，通过 Android 已授权的 FD 操作，不需要 root。拒绝主动断开 usbfs 持有者；连接关闭沿用原 transport 的资源管理。
- `AUTO` 仅匹配本次车机：Android 7.1/API 25、MODEL `SABRESD-MX6DQ` 或下划线形式、DEVICE 以 `tamago` 开头。其他设备默认为原路径。`ON`/`OFF` 可用于明确启用或对照，下次建立连接生效。
- 配对：适配启用时，在同一 Lockdown 连接上等待信任。保留生产配对记录、身份与恢复逻辑，没有搬入测试 APP 每次进程重新生成记录的方式。
- 后续：继续使用 DiPlay 原有 carkit/iAP2、MFi、VPN/NCM、AirPlay 与音视频处理。VPN 仍在原顺序接入，不把它伪装成可用，也不绕过系统权限。
- TCP：状态、两类文件日志、含轮转日志的 ZIP、启动有线、停止会话、重试 VPN、USB 模式切换、同包名同签名 APK 上传与系统安装确认。
- VPN 下保留 LAN：目标车机或 TCP 调试已启动时，允许 IPv4 继续使用原网络，避免 IPv6-only VPN 阻断本 APP 的 IPv4 调试连接；CarPlay 的 fe80::/64 仍走原 IPv6 VPN。

当前可构建 APK，原成功证据来自 usbcheck。本 APK 在车机上的 USB、VPN、TCP 和完整会话尚需部署实测；构建通过不能替代这一验证。本轮未执行 ADB VPN 授权、root、系统或防火墙修改。

## 首次部署和手动 VPN 授权

保持正式包名 `com.gtmc.app.fengyunyx.b819`，不覆盖 `com.tencent.wecarflow` 测试 APP。本版版本号为 `0.2.18-pioneer-usb1-hud-test`，versionCode 38。

首次安装需要 USB ADB，或者车机允许的人工安装方式。**上一次的 usbcheck 更新接口拒绝不同包名，不能用它直接替换成正式 DiPlay。**

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :mobile:assembleDebug
adb -s 220739d4f21e56b6 install -r mobile/build/outputs/apk/debug/mobile-debug.apk

# 以下是用户手动执行的首次授权命令，TCP 调试不会执行它。
adb -s 220739d4f21e56b6 shell appops set com.gtmc.app.fengyunyx.b819 ACTIVATE_VPN allow
adb -s 220739d4f21e56b6 shell appops get com.gtmc.app.fengyunyx.b819 ACTIVATE_VPN
```

新增适配和 TCP 指令不执行 ADB 授权。DiPlay 原有“已批准本机 ADB 可用时自动兜底授权”的逻辑保持不变；若满足其条件，仍可能走原兜底。本车机后续按用户计划手动授权。

不要把 BIND_VPN_SERVICE 当作运行时权限去 pm grant。看到 ACTIVATE_VPN allow 后仍需验证实际隧道与数据通信；正常重启通常保留权限，但本车机尚需实测重启后的行为。

先在 usbcheck 点“退出并关闭调试”，确保它不占用 USB。打开正式 DiPlay，在设置的“诊断”页面进入“局域网调试”。该页显示 IPv4、实际端口和会话状态，可启动有线连接、重试 VPN、停止会话。设置入口只在匹配的车机/适配已启用时显示，不增加第二个启动图标。

目标车机以有线方式打开 DiPlay 时会自动启动 TCP 服务；原来选择无线连接时，可从调试页主动启动。它是独立前台服务，停止 CarPlay 后继续运行，便于导出失败报告和更新 APK。要关闭监听，点调试页“关闭 TCP 调试”。重新打开有线页面会再次启动。

## 电脑通过 LAN 调试

电脑与车机处于同一局域网。使用页面显示的 IP，不固定旧热点地址。优先端口 8721，绑定失败再试 9911、9912，均来自车机已知放行清单；不使用随机端口，不占用高德 5656。监听成功还需要真实 status 响应才能认定网络可达。

```sh
/usr/bin/python3 pioneer-adapter/tools/diplay_debug.py --host <车机IP> --port <显示端口> status
/usr/bin/python3 pioneer-adapter/tools/diplay_debug.py --host <车机IP> --port <显示端口> start-wired
/usr/bin/python3 pioneer-adapter/tools/diplay_debug.py --host <车机IP> --port <显示端口> retry-vpn
/usr/bin/python3 pioneer-adapter/tools/diplay_debug.py --host <车机IP> --port <显示端口> logs --follow
/usr/bin/python3 pioneer-adapter/tools/diplay_debug.py --host <车机IP> --port <显示端口> logs --source adapter --follow
/usr/bin/python3 pioneer-adapter/tools/diplay_debug.py --host <车机IP> --port <显示端口> export --output diplay-report.zip
/usr/bin/python3 pioneer-adapter/tools/diplay_debug.py --host <车机IP> --port <显示端口> stop
```

`start-wired` 会停止已有会话、保存有线模式并打开连接页。`retry-vpn` 在连接页重新检查原有启动前置条件；若连接页已关闭，则重新启动有线。它不执行 appops set。启动类命令返回“已排队”，实际是否成功以之后的 status 和日志为准。

`status.vpn.authorized` 仅表示系统授权；`host.vpnReady` 也不是“完整链路已通”的标志。继续查看 `host.stage`、`host.controllerRunning`、`host.screenStreams` 和日志，确认 VPN/NCM attach、MFi、CarPlayStartSession、实际 AirPlay 连接与首帧。

对照原路径：先 stop，再设置 `usb-mode --mode OFF`，随后 start-wired。恢复为 `AUTO` 或明确 `ON`，同样在停止后再重新连接；不要在活动会话中切换。

### 通过 TCP 更新正式 APK

1. 执行 stop。
2. 查询 status，等待 `hostAttached`、`controllerRunning` 和 `backgroundSession` 均为 false。
3. 执行 update：

```sh
/usr/bin/python3 pioneer-adapter/tools/diplay_debug.py --host <车机IP> --port <显示端口> update mobile/build/outputs/apk/debug/mobile-debug.apk
```

车机打开系统安装确认，需用户点击安装。新 APK 必须同包名、同签名。安装会结束进程，完成后重新打开 DiPlay/局域网调试。若车机缺少系统安装器，调试日志会记录失败，需使用人工安装方式或恢复 USB ADB。

状态和控制没有取得 ADB/shell 权限。导出内容为 DiPlay 已脱敏的会话日志、适配模块日志和状态，不打包配对/MFi 素材，不读取任意文件。TCP 命令不再校验口令；同一网络中能够访问该端口的设备可以调用调试功能。客户端的可选 `--token` 参数仅用于兼容尚未升级的旧服务。

TCP 与无线 CarPlay 共用车机有限的允许端口。虽然优先选择 8721 以留出 9911/9912，仍须以实际端口为准；回测原无线方案时可先关闭 TCP 服务。本轮的目标是有线验证。

## 下次上车的最小验证顺序

1. 首次安装、手动 VPN 授权，打开调试页；电脑拿到真实 status，确认 matchedFirmware/pioneerUsbEnabled 和 vpn.authorized。
2. 关闭 usbcheck，使用已成功的新数据线、解锁 iPhone，换接到车机。电脑改用 TCP。
3. start-wired；必要时允许正式包的 USB 访问和手机信任。
4. 日志确认 `PIONEER_USB_CONFIG_OK`、USBMUX/NCM claim、配对、VPN/NCM attach、IdentificationAccepted、AuthenticationSucceeded、CarPlayStartSession。
5. 确认第一帧、触控、音频和再次拔插重连；成功与失败均先导出 ZIP。
6. 重启车机后复查 VPN 授权是否保留，再测连接。不要以只看到授权 allow 判断 VPN 或 CarPlay 已通。

## 同步上游时如何保留适配

主要逻辑放在本目录和一个新增的 `common/.../PioneerDebugIntegration.kt` 文件。原文件只需要保留以下接线：

| 原文件 | 必要接入 |
| --- | --- |
| settings.gradle.kts、shared/common 的构建文件 | 注册模块与依赖 |
| CarPlayController | 给 IphoneUsbHost 的两个 USB 回调、给 NcmUsbBridge 的 claim 回调、目标适配下的配对等待选项 |
| CarPlayVpnService | 目标车机/调试开启时 allowFamily(AF_INET)，保留 LAN |
| DiPlayActivity | 启动集成入口、诊断卡增加一个按钮 |
| CarPlayHostActivity | 启动入口、提供状态/重试回调、销毁时解绑 |
| 各语言 strings.xml | 一个诊断按钮名称 |
| mobile/build.gradle.kts | 本地验证版本号；可随上游版本调整 |

本次复用了前面 usbcheck 排查时已增加的 `IphoneUsbHost` / `NcmUsbBridge` 可选回调与 `LockdownPairingClient.keepConnectionWhilePending`，它们仍是合并时必须保留的支持点。默认 null/false 继续执行原行为。此前的 USB 诊断文件和工作区中其他功能改动不属于本模块新增逻辑。

不要把整个 CarPlayController 或设置页替换为本地版本。同步后检查上述少量调用点和模块依赖即可；功能是否仍可用最终以有线实测为准。

### 网络诊断补充版（versionCode 39）

`0.2.18-pioneer-usb2-hud-test` 把 NCM IPv6 收发包摘要同步到会话日志，status 增加 networkCounters 和 ipv6Sockets，用于区分 USB 收包、VPN 转发与 AirPlay 接受连接。包日志沿用有限记录预算与会话日志脱敏。


### 音乐连续性对照（v74 起）

`audio-clock-trim --mode OFF` 将目标先锋车机的媒体 AudioTrack 恢复为音源采样率；`ON` 恢复上游速度校正。仅用于已识别车机的同会话单变量对照，不保存偏好，进程重启恢复设备默认：v77 起仅已识别的 SABRESD_MX6DQ/tamago/API25 车机有线 LWIP 会话默认固定音源速率，其他设备和连接路径仍默认开启上游校正。v76 的设备级默认曾覆盖该车机无线模式，v77收窄此范围。实测对照见[续测记录](../2026-10-16-车机音乐连续性与视频低延迟续测记录.md)。`status.audioClockTrimEnabled` 是当前有效状态，`audioClockTrimOverride` 为 `null` 时表示自动默认，布尔值表示运行时覆盖；实际生效须同时检查 `Audio: clock trim comparison` 的 `resetResult=0` 和下一窗口 `Audio: io stats` 的 `actualPlaybackRate`。

先保持同一音乐、缓冲、100% 分辨率及 60 帧设置采集数分钟，再按 ON → OFF → ON 比较，每段用完整 `export` 保存报告。新 `Audio: io stats` 分别记录收到的 RTP 总字节（含头部）、送入写入函数的 PCM 字节、AudioTrack 接受的字节、未写完字节与退出原因；RTP 和 PCM 字节不可直接相减。PCM 应满足 `pcmBytes = writtenBytes + unwrittenBytes`，停止会话的末尾窗口单独统计。`stall-timeout` 表示连续零写入达到现有约 250ms 上限，`error-*` 为 AudioTrack 错误，另区分停止、线程中断、异常和无 track。

比较供给不足、重新缓冲、未写完字节及队列趋势；固定速率即使减少断音，若延迟持续增加也不能通过验收。开关不会改变缓存裁剪、反馈进度或重新缓冲策略。


### 本地UI与页面生命周期对照（v78起）

`host-ui --action SETTINGS_OPEN|SETTINGS_CLOSE|BACKGROUND|FOREGROUND` 仅在已识别车机可用，通过主线程执行应用内动作。关闭设置保留未保存编辑确认流程；返回success=false时不要自动丢弃编辑。超时表示动作可能仍会执行，应先检查新鲜status。`host.settingsMenuOpen` 和 `host.hostWindowFocused` 辅助确认页面状态；前后台调用返回不等于首帧恢复，需检查视频计数、Surface日志和计时之外的快照。`UI_SETTINGS_OPEN`分段记录load、baseline、build耗时；v79额外记录rebuilt以核对控件复用。验证记录见[交互与生命周期续测](../2026-10-16-交互卡顿实车对照与生命周期验证记录.md)。

`restart-app` 只用于已识别先锋车机的本应用进程回收验证：响应后由独立线程延迟结束本进程，结束前安排3秒后重新打开本应用。不会清除配对或偏好，但会终止活动会话；以新PID及连接状态确认恢复。`host-ui FOREGROUND` 优先将当前本应用任务带到前台，返回后仍应检查窗口焦点和视频新帧。
