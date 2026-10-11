# Android A RFCOMM 实测记录

日期：2026-10-11。Android A：小米 13（2211133C），Android 15 / API 35。
对端：用户已完成配对的 iPhone；具体 iOS 版本尚未记录。
应用：`com.shihab.diplay.btproxy`，本地 `:bt-proxy-android:assembleDebug` 构建。

## 结果

- 目标 iAP2 UUID：`00000000-deca-fade-deca-deafdecacafe`，与 DiPlay `CarPlayController` 一致。
- 系统配对状态：`bondState=12`（BONDED）。
- SDP 实际返回目标 UUID，`targetPresent=true`。
- 首次 `BluetoothSocket.connect()` 成功，耗时 416 毫秒，`socketConnected=true`。
- 首次连接后接收观察 10 秒，无数据，`bytesIn=0 bytesOut=0`，自动关闭正常。
- 再次建立同一服务连接成功，耗时 304 毫秒，`socketConnected=true`；再次观察 10 秒无数据并正常关闭。

关键日志（略去设备名称和地址）：

```text
19:10:16.236 session=380045883 phase=RFCOMM result=CONNECTED elapsedMs=416 socketConnected=true protocol=UNVERIFIED
19:10:26.238 session=380045883 phase=RFCOMM result=CONNECTED_NO_DATA elapsedMs=10418 bytesIn=0 bytesOut=0
19:10:36.864 phase=SDP result=RECEIVED targetPresent=true
19:11:18.592 session=380108351 phase=RFCOMM result=CONNECTED elapsedMs=304 socketConnected=true protocol=UNVERIFIED
19:11:28.596 session=380108351 phase=RFCOMM result=CONNECTED_NO_DATA elapsedMs=10308 bytesIn=0 bytesOut=0
```

## 后续主动握手与远程认证

以下为设备角色调整前的结果（Android A 为小米 13，Android B 为模拟器）：

- 19:18 的有界主动握手成功，真实发送 44 字节、接收 51 字节，iAP2 链路进入 NORMAL，
  收到 `0x1d00 StartIdentification`。双向收发和链路握手已有证据。
- 19:35 的 TCP 透传验证中，模拟器完成 iAP2 链路、身份识别和配件认证。
  iPhone 返回 `0x1d02 IdentificationAccepted` 与 `0xaa05 AuthenticationSucceeded`。
  Android A 仅透传，认证素材与挑战签名全部在 Android B 处理。
- 首次远程认证接收 167 字节、发送 1130 字节；第二次再次通过认证。
- 模拟器的 Existing Wi-Fi 检查失败：实际虚拟 Wi-Fi 与配置路由器 SSID 不一致。
  当时未验证 AirPlay 或完整 CarPlay，不应以认证通过代替画面、音频或触控验证。

## 当前设备角色

用户随后改为两台真机：Nexus 5（Android 6.0.1 / API 23）为 Android A，
小米 13（Android 15 / API 35）为 Android B，不再使用模拟器。
代理 APK 最低版本已调整为 API 23，并已安装到 Nexus 5；DiPlay 已安装到小米 13。
两台手机分别具有同一局域网的 `192.168.2.157`、`192.168.2.152` 地址。
旧配置的验证结果不能直接代替新组合的实际验证。

### 双真机实测

- 19:45:27，Nexus 5 代理与 iPhone 建立 RFCOMM；小米 13 的远程 iAP2 链路就绪。
- 19:45:28，小米收到 `0xaa05 AuthenticationSucceeded`，接收 167 字节、发送 1130 字节；
  身份识别与 MFi 认证通过，代理两方向的计数与 B 一致。
- 小米的 Existing Wi-Fi 检查通过：`wlan0 / 192.168.2.152`，5 GHz 信道 36。
- 主 CarPlay 控制器已接入远程字节流；收到 `0x4e0d available=true` 与 `0x4300`，
  发送 Wi-Fi 配置 `0x5703` 和启动请求 `0x4301`。
- 初期未收到 iPhone 的 AirPlay TCP，30 秒后触发 FIRST_TCP_TIMEOUT，不能判定完整 CarPlay 成功。
- Nexus 曾自动使用 iPhone 的蓝牙个人热点（`bt-pan / 172.20.10.x`），关闭热点后恢复
  Nexus 到小米的 Wi-Fi 探测。小米到 iPhone `192.168.2.148` 的三次探测均成功。
- USB 重新连接曾清除电脑侧 ADB forward，导致 HELLO 阶段 EOF；重建 forward / reverse 后恢复。
- 19:53:34，Nexus 从真实 Wi-Fi 访问小米 `7000` 端口的 `/info`，获得 `HTTP/1.1 200 OK`。
  该连接来自诊断客户端 Nexus，**不是 iPhone 的 CarPlay 连接**，不能将相应 TCP 计数作为
  iPhone 入站或完整会话成功证据。
- 19:54 的系统 NSD 对比中，Android `servicediscovery` 报告 DiPlay `_airplay._tcp`
  注册成功，当前仍未确认来自 iPhone 的配对、SETUP、视频帧、音频或触控。

当前透传通过电脑 USB：A 使用 `adb forward tcp:39877 tcp:39877`，B 使用
`adb reverse tcp:39877 tcp:39877`，B 的私有配置指向 `127.0.0.1:39877`。
认证素材只随 DiPlay B 打包；A 不包含认证文件。验证时保持代理前台。

### 小米系统热点下的 CarPlay 成功验证（20:09–20:11）

- 改用小米系统热点，Nexus 与 iPhone 均加入。小米 AP 为 `wlan2 / 192.168.105.7`，
  iPhone 为 `192.168.105.67`；ADB 观察到实际 5 GHz 信道 36。
- Android 公共 API 读不到该热点信道，原先向 iPhone 报告 0。此轮通过 debug 私有
  `remote-probe.json` 提供实测信道 36，并仅在当前 AP 地址匹配时用于 Wi-Fi 引导。
  首次修改后连接成功；这支持信道信息是关键因素，但并非严格控制的因果实验。
- 20:09:43，真正的 iPhone 完成 AirPlay `pair-setup`、`pair-verify`、`auth-setup`、
  `SETUP`、`RECORD`；会话日志确认 peer 为 `192.168.105.67`。
- 20:09:44，Wi-Fi iAP2 隧道再次收到 `0xaa05 AuthenticationSucceeded`，
  蓝牙 bootstrap 正常退出，控制器进入 `WirelessActive`；视频解密、H.264 解码和首帧渲染成功。
- 初次显示由竖屏转横屏触发一次控制器重建；20:10:01 自动重新建立会话，
  20:10:02 在 `2400x1080` 下再次渲染首帧，随后持续收到视频数据。
- 用户在小米上切换音乐并播放，明确反馈“触控正常，有声音”。日志同时确认媒体
  AAC-LC / 48 kHz 持续播放；观察窗口中 `dropped=0`、`writeErrors=0`、`rebuffers=0`。
- 至 20:11:07，重建后的会话已持续约 65 秒；本轮核心 CarPlay 链路、视频、触控、
  媒体音频已有真机证据。未验证通话、Siri/麦克风、长时间稳定性及其他机型。
- 已将小米保存的热点配置复制到 Git 忽略的 `.private/runtime-assets/network/hotspot.json`，
  随 mobile APK 内置；首次启动只填充尚未配置的手动热点，不覆盖已有配置或之后的修改。
  APK 内配置与本地文件逐字节一致，更新安装后原配置保留。系统热点仍需自行开启。
- 上述信道覆盖目前依赖 debug 部署文件，不能据此宣称普通 release 安装已自动识别热点信道。
  USB forward/reverse 仍为 A/B 间透传路径，尚未验证完全脱离电脑的透传部署。

私有截图和节选日志保存在 `.private/validation/2026-10-11-carplay*`，不进入 Git。

### 切回路由器的对比验证（20:16 起）

- 关闭小米热点，三台设备重新连接路由器同一 5 GHz SSID。
  小米 `wlan0 / 192.168.2.152`、Nexus `192.168.2.157`、iPhone `192.168.2.148`。
- 保持已安装 APK 不变，仅切回 `EXISTING_WIFI` 并重启会话；A/B 仍使用 USB ADB 转发。
  Existing Wi-Fi 从系统连接信息读取信道 36；手动热点的 debug 信道覆盖没有启用。
  Bonjour 使用此前已有的 system NSD 分支。
- 20:16:47 蓝牙 iAP2 认证成功；StartSession 后约 604 ms 收到 iPhone 的 AirPlay TCP。
  20:16:48 完成 `pair-setup`、`pair-verify`、`auth-setup`、`SETUP`、`RECORD`，
  会话 peer 为 `192.168.2.148`；首帧渲染、Wi-Fi 隧道认证、`WirelessActive` 均成功。
- 20:17:02 日志确认触控 HID 报文发出；视频持续接收，无观察到的序列间隙。
- 用户确认本轮“触控正常，有声音”；媒体音频播放日志也已采集，路由器下的
  CarPlay 视频、触控和媒体声音验证通过。
- 本轮证明原路由器环境可以运行 CarPlay，不能将之前失败归因于路由器隔离或
  5 GHz 不兼容。iPhone 配对/授权及会话状态经历过热点成功连接，当前结果不足以
  单独确定首次失败原因，也不能证明热点信道 36 覆盖是此前成功的唯一原因。
- 私有证据保存在 `.private/validation/2026-10-11-router-carplay*`。

## A/B Wi-Fi TCP 直连验证（20:22 起）

- Android A 代理升级为 0.4.0，监听本机 Wi-Fi IPv4 的 `39877` 端口；
  没有可用 Wi-Fi 地址时给出启动错误。令牌认证和原始双向 CPBT 帧透传保持不变。
- 当前 A 为 `192.168.2.157`，B 为 `192.168.2.152`。B 私有 `remote-probe.json`
  的 host 更新为 A 的地址。两端 ADB forward/reverse 列表均为空。
- 20:22:56，小米实际连接 `192.168.2.157:39877`；20:22:57 蓝牙 iAP2/MFi 认证通过。
  20:22:58 iPhone 建立 AirPlay 会话、视频首帧渲染、Wi-Fi 隧道再次认证并进入 `WirelessActive`。
- 代理确认认证连接来自 B 的 `192.168.2.152`。B 实际消费 794 字节、发送 1713 字节，
  A 记录从 iPhone 转发 1801 字节、向 iPhone 发送 1713 字节；B 计数不含移交时未消费的数据。
  移交 Wi-Fi iAP2 后
  关闭 RFCOMM 是正常行为。此时 USB 仅用于安装与日志，已不承载 A/B 的数据转发。
- 配置仍为 debug 私有文件，代理仍需保持前台。A 的 DHCP 地址改变时需要更新 B 的 host；
  尚未实现自动发现、后台服务或普通 release 的配置界面。
- 节选日志保存在 `.private/validation/2026-10-11-wifi-direct-evidence.log`。

## A/B 自动发现验证（20:29）

- A 代理 0.5.0 使用 Android NSD 发布 `_diplay-proxy._tcp.` 服务，包含当前端口、
  公开设备标识和协议版本，不包含令牌。停止代理时注销服务。
- B 私有配置已删除 `host` / `port`，只保留 `gatewayBluetoothMac` 与令牌作为配对信息
  （另有此前热点实验的可选字段）。每次连接执行限时 NSD 发现、解析当前 IPv4 和端口，
  检查服务设备标识，完成令牌认证后再核对响应设备标识；不保存发现到的 IP，也不退回固定 IP。
- A 在 20:29:47 发布服务；B 在 20:29:48.208 开始发现，20:29:48.301 解析成功，约 93 ms。
  此次发现得到的地址为 `192.168.2.157:39877`，A 确认认证连接来源为 B 的 `192.168.2.152`。
- 20:29:49 蓝牙 iAP2/MFi 认证通过，20:29:50 iPhone 建立 AirPlay 会话，
  20:29:51 视频首帧渲染、Wi-Fi iAP2 认证和 `WirelessActive` 均成功。
- 配对文件仍通过 debug 部署提供，尚未实现配对 UI；仍要求代理前台运行和网络支持组播。
  本轮验证同一路由器，尚未实测 DHCP 地址变化或切换小米热点后的自动发现。
- 私有证据位于 `.private/validation/2026-10-11-auto-discovery-evidence.log`。

## 初始被动观察的判定范围

已验证这组设备能够通过普通 Android App 发现目标服务，并建立目标 RFCOMM Socket。
应用未发送任何数据，没有完成双向收发验证，也没有解析或执行 iAP2 握手。
静默接收不能证明链路不可用；同样，Socket 建连成功不能代替完整 P1 的双向可用性证据。
上述限制只适用于初始被动观察；后续主动交互与远程认证结果见前文。
本轮未采集 HCI 抓包，未验证连接超时、权限拒绝等失败分支及其他机型兼容性。
