# 先锋车机 CarPlay 无声与播放线程阻塞排查

日期：2026-10-13。设备：SABRESD_MX6DQ，Android 7.1.2 / API 25。

## 根因与参考对照

CarPlay 已收到、解密并解码媒体 AAC 和导航 Opus；无声不能归因于 RTP 没到达。
旧版普通 AudioAttributes 进入 SPEAKER 输出，`AudioOut_D` 长期停在 HAL 写入，
`Frames written=4608` 不再增长。播放器随后队列堆满，退出时线程仍可能卡在
`AudioTrack.WRITE_BLOCKING`，AudioFlinger 留下旧进程的活动轨道。

对照本地高德播放工程、QQ 音乐及其车机 PAL 对接工程，再读取车机实际框架，确认：

| 用途 | 厂商流类型 / contentType | 实际输出 | MediaManager 令牌 |
| --- | --- | --- | --- |
| 媒体 | 401：STREAM_TAI_WECARFLOW_MEDIA | FRONT / legacy 11 | com.tencent.wecarflow.media |
| 导航 | 400：STREAM_AMAP_NAVI_GUIDE | MIX / legacy 10 | com.autonavi.amapauto |

先锋的 `AudioAttributes.Builder.setLegacyStreamType()` 支持这些扩展值；
AudioTrack 构造器从 contentType 生成令牌。普通 `USAGE_MEDIA` 无法替代它。
Android 7 的焦点请求也须保留 400/401，不能按 usage 折叠成 STREAM_MUSIC=3。

车机当时主音源为 205（蓝牙），媒体焦点请求返回 0；导航混音焦点返回 1。
实机验证 `MediaController.change2Source(1, 300)` 可选择前排车载媒体，
`MainToken` 随之从蓝牙变为 `com.tencent.wecarflow.media`。
第一个参数为 DISPLAY_ID_FRONT=1，第二个参数为 SOURCE_ID_TAI_AUDIO=300。

该主音源也被本机 `com.tencent.wecarflow` 使用。切换可能启动本机音乐，因此
通过其明确导出的 MediaButtonIntentReceiver 发送 MEDIA_PAUSE，防止音乐叠加。
未修改高德、QQ 音乐 APK 或车机系统。

## 修改

- `PioneerAudioRouting.kt`：仅在 API 25 且框架确有 400/401 和 setTokenName 接口时启用。
  默认媒体使用 401，语音使用 400；用户明确选定的媒体、导航流仍优先。
- `AndroidMediaSink.kt`：在上述固件上请求厂商焦点、选择媒体音源，导航独立混音。
  媒体取得播放权后暂停本机音源播放器。媒体与语音分别持有焦点；不能先 abandon 401
  再 request 400，否则先锋会把主音源切到 OFF。语音轨道共用独立、计数管理的混音焦点。
- `AudioFocusRequestCompat.kt`：支持可选的厂商 streamType；普通 Android 保留原逻辑。
- 先锋 PCM 写入使用非阻塞调用，连续无进展最多等待 250ms；线程可响应退出和释放。
  其它设备仍使用原有阻塞写入。

## 连接稳定性范围

本次首先保留现场日志，包含一次初始握手 peer EOF（尚无活动流），自动重试后成功。
代理蓝牙在 AirPlay iAP 隧道建立后断开是链路移交，不能单独当成 CarPlay 断线。
已连接会话持续收到画面、控制和音频。旧播放线程无法释放是已确认的问题，本次修复。
不能据此声称所有无线掉线均已解决；最终实机观察结果见下方验证记录。

## 验证记录

- 首版源码构建成功；媒体和导航均出现持续增长的 playbackHeadFrames。
- 导航：5 秒窗口播放进度增长 236339 帧，writeErrors=0。
- 媒体：5 秒窗口播放进度增长约 240744 帧，writeErrors=0。
- 选择主音源实验成功，Current Source ID=300，MainToken 正确。
- 焦点兼容、自动让音和音轨兼容定向检查通过；新增用例验证 Android 7 不丢失厂商流类型。
- 最终 APK 已覆盖安装，包 UID 仍为 10087；车上 APK 与本地产物 SHA-256 一致：
  `bc439e804b2b8ffdb05ab1de2e6e42984e8331f50685d692b1d2c68fd387da7e`。
- 最终版 31 项定向检查通过，包含厂商流类型保留、导航/助手不释放媒体焦点的回归用例。
- 本机 QQ 音乐媒体会话显示 state=2（暂停），CarPlay 显示 state=3（播放）。
- 最终版首个会话 07:10:54 至 07:13:25，无自行断线；媒体、导航每 5 秒播放进度均
  增长约 239909 帧，末段 dropped=0、writeErrors=0、underruns=0、rebuffers=0。
- 第一次主动重连成功，07:13:50 恢复会话，自动选择媒体音源并暂停本机播放器。
- FM 选择返回成功，导航混音期间可同时观察到 source=201、mix=400；CarPlay 未断线。
  蓝牙选择返回成功，source=205。这里使用系统音源接口验证，未实际按物理 MODE 键。
- 第二次主动重连成功，07:16:39 恢复会话。两次重连之间 FRONT 输出不再留下已退出
  DiPlay 进程的音轨。本轮开始前 SPEAKER 输出已积累的旧版轨道仍存在，未重启系统服务。
- 第二次重连后再次恢复 iPhone QQ 音乐播放，最终会话保持连接。
  07:18:01 自动选择媒体音源；截至 07:18:30，MainToken 正确，媒体每 5 秒
  播放进度增长约 240745 帧，dropped=0、writeErrors=0、underruns=0、rebuffers=0。
  实际车内听感和长时间无线稳定性尚未由本次工具验证。

安装包备份：[DiPlay-CarPlay-Pioneer-Audio-20261013.apk](../../artifacts/DiPlay-CarPlay-Pioneer-Audio-20261013.apk)。
DiPlay 仓库内的原始产物为 `mobile/build/outputs/apk/debug/mobile-debug.apk`。

原始日志、截图和构建记录保存在 `.private/validation/2026-10-13-audio/`，不纳入 Git。
系统输出与播放进度的验证不能代替车内实际听感确认。
