# DiPlay 与 WeCarFlow 自研适配全分支 Review（2026-10-17 17:55）

> 后续处理：上述 13 项问题已完成代码修复及本地定向验证，昼夜测试入口已清理。
> 最新修复、安装阻塞与待验证情况见 [2026-10-17 22:06 修复说明](2026-10-17-2206-DiPlay与WeCarFlow审查问题修复及昼夜调试清理说明.md)。本报告保留审查当时的记录。

## 结论先看这里

整体方向合理：把先锋硬件适配独立出来、让 lwIP 只有一个线程操作、把 WeCarFlow 桥接与原播放器分进程，都是应当保留的设计。当前更像持续验证中的车机适配分支，尚不适合直接视为已完成整理的稳定发布分支。

本轮确认 **13 项代码问题：3 项 P1、10 项 P2**。P1 表示优先修复，P2 表示有明确触发路径、应安排处理；低频或未在车机复现的条件会单独标明。这里“确认”指代码调用链/状态变化可以推出问题，不表示每项都已在车机上触发。

最先处理：旧广播拉起 WeCarFlow 原业务、代理等待阻塞取消，以及 CI 与认证资源要求不匹配。其次统一连接资源释放和调试服务的并发边界。不要先大范围搬文件或重写已经跑通的 lwIP/认证链路。

**此次只读审查。没有修改业务源码、提交 Git、安装应用、连接或操作车机。** 仅运行本地定向检查并生成本报告。保持车机现状；v96 的实车结果仍未知。

## 审查范围与历史口径

- 分支：`codex/car-latency-validation-20261016`，HEAD：`601d0a54`，包含当前未提交及未跟踪的适配源码。
- 对照最近合入的上游 `1b1ac5f2`，并检查自研的 10 个第一父链提交。没有把 merge 引入的上游代码全部算成我们的开发，也没有把已替换旧分支当作当前运行路径。
- 范围包括：先锋 USB/lwIP/JNI、蓝牙代理与发现、AirPlay 接缝、音视频/麦克风、方控/仪表/播放保护、WeCarFlow 原生补丁、日夜模式、免责声明、TCP 调试/升级、签名/构建/CI。
- 使用分组独立审查，再由主审查者复核关键调用链。第三方 lwIP/加密库原文未重新做算法审计；实验工具主要检查入口、取消和资源边界，没有逐行验证所有实验命令。
- 这是全分支适配审查，不能保证找出所有缺陷；历史提交检查变更清单及其当前有效逻辑，关键问题追溯来源，不逐行重复审计每个已淘汰版本。
- 根目录 `wecarflow` 及父目录目前没有 Git 仓库，无法审出新版 WeCarFlow 的完整逐次提交历史；已审当前补丁/生成脚本与旧适配的差异。此项本身是需要补齐的维护缺口。

| 自研提交 | 内容与审查处理 |
| --- | --- |
| `01d551b4` | 外置蓝牙代理、发现和管理起点；追溯连接取消与协议握手 |
| `e4fa5e5b` | Android 兼容、车机音频和代理服务；追溯声音路由与服务复用 |
| `f858ec18` | vehicle 接口、PAL 方控、播放保护、仪表；核对各通道能力和所有权 |
| `84b01a18` | 取消保护服务签名限制；按用户授权决定看待，未将该选择列为缺陷 |
| `77dee7b1` | 简化保护及调用方限制；核对后续会话/死亡清理，没有要求恢复被取消限制 |
| `38833555` | PAL 依赖加载和持续保护；核对异步加载、桥接/会话接入 |
| `ce39d7cf` | 先锋 lwIP、USB、端口租约和诊断；重点检查 native 资源与取消 |
| `c3bc9398` | 合上游；以第二父 `1b1ac5f2` 及之前自研差异交叉确认归属 |
| `2a365435` | 连接/延迟证据、日夜模式及实验收敛；区分诊断与正式策略 |
| `601d0a54` | 已验证固件有线音频时钟默认；保留窄设备范围，不建议恢复已否定实验 |

## 问题清单

### F01 · P1 · 旧暂停广播会启动 WeCarFlow 原主进程

CarPlay 创建媒体音轨后约 0.5 秒，仍显式广播到原厂播放器 receiver。即便只是“暂停”，Android 也需要先启动原主进程和 Application，所以不满足“只启动桥接，其它业务不启动”的要求。播放门禁可以阻止音乐真正开始，却不能撤销原业务初始化。

- 代码：[AndroidMediaSink.kt:129](../../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L129)、[PioneerAudioRouteAdapter.kt:60](../../shared/src/main/java/com/shilapi/xcertplay/vehicle/adapters/pioneer/PioneerAudioRouteAdapter.kt#L60)、[AndroidManifest.xml:117](../../wecarflow/apktool/AndroidManifest.xml#L117)、[FlowApplication.smali:74](../../wecarflow/apktool/smali/com/tencent/wecarflow/FlowApplication.smali#L74)。
- 来源：历史 Pioneer 音频暂停路径（e4fa5e5b 引入、f858ec18 隔离到 adapter），当前独立桥接改造后仍保留。
- 建议：移除这条冷启动广播；通过已经登记的 peer Binder 暂停确实已运行的播放器。不要先检查进程存在再广播，那样仍有进程退出后被重新拉起的竞态。

### F02 · P1 · 连接代理时占用关闭锁，取消可能等待几十秒

远程代理的发现、TCP 连接、HELLO 和 OPEN 握手均在 wirelessResourceLock 内运行。close() 要先取得同一把锁，且尚未完成的 socket 没有发布为可关闭资源，因此用户退出、重新连接或切换目标可能长时间等不到取消。各阶段超时组合可达约 65 秒量级，部分读取还可更长。

- 代码：[CarPlayController.kt:1436](../../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L1436)、[CarPlayController.kt:723](../../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L723)、[RemoteGatewayDiscovery.kt:76](../../shared/src/main/java/com/shilapi/xcertplay/transport/RemoteGatewayDiscovery.kt#L76)。
- 来源：01d551b4 引入，e4fa5e5b / ce39d7cf 扩展后保留。
- 建议：发现和网络等待放到锁外；锁内只登记本次连接身份与资源。关闭应立即标记取消，并能关闭正在握手的 socket；完成时再次检查连接代次。

### F03 · P1 · 当前 CI 与认证资源的构建要求不一致

mobile 的 preBuild 无条件要求本地私有认证文件，但两份 workflow 只注入应用签名，没有注入 CarPlay 认证文件；其中仍标注 source-only / without authentication assets。即使签名配置完整，全新 checkout 也会失败。本机有私有文件，因此正常本地构建掩盖了问题。

- 代码：[build.gradle.kts:85](../../mobile/build.gradle.kts#L85)、[android.yml:45](../../.github/workflows/android.yml#L45)、[build-android-tv.yml:45](../../.github/workflows/build-android-tv.yml#L45)。
- 来源：当前未提交的构建与签名调整。
- 建议：区分“不带运行认证素材的源码验证构建”和“带素材的车机发布构建”；或让私有发布 workflow 明确注入素材。不要把正式素材检查无条件套到 source-only 构建。

### F04 · P2 · 端口注册中途被取消，可能留下没有人释放的监听

registerPort 等待完成通知时如果线程被 interrupt，await 会直接抛出，跳过取消和补偿逻辑。owner 随后仍可能执行 add，但调用者拿不到用于释放的 Closeable。长时间反复初始化、取消，可能累积 native 引用，最终耗尽监听名额。

- 代码：[PioneerLwipRuntime.kt:75](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerLwipRuntime.kt#L75)、[PioneerLwipBridge.kt:93](../../shared/src/main/java/com/shilapi/xcertplay/network/PioneerLwipBridge.kt#L93)。
- 来源：ce39d7cf。
- 建议：把正常超时、失败和中断统一收敛到取消/补偿路径；每一次成功 add 必须有恰好一次 remove，同时保留线程中断状态。

### F05 · P2 · 端口释放队列满时，释放请求永久丢失

lease.close 先标记 released=true，再把 remove 放入有容量上限的队列。队列满时抛异常，但下一次 close 会认为已经释放，不再重试；注册失败时的补偿 remove 也忽略入队失败。这与 F04 是两条独立触发路径。

- 代码：[PioneerLwipRuntime.kt:81](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerLwipRuntime.kt#L81)、[PioneerLwipRuntime.kt:88](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerLwipRuntime.kt#L88)。
- 来源：ce39d7cf。
- 建议：确保释放操作最终可达 owner，例如单独的待释放集合或保留释放通道；不要只吞掉异常，也不要在尚未可靠提交释放时永久标记成功。

### F06 · P2 · 调试连接可占满处理线程，后续状态/停止命令无响应

固定 3 个 worker 配合无界排队。慢速 header、长上传或不读完导出的连接可长期占住 worker，后来的 status/stop 只能排队；持续重试还会积累 socket。socket 读超时不等于完整请求截止时间，也不限制阻塞写。此问题与是否使用口令无关。

- 代码：[PioneerDebugService.kt:37](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerDebugService.kt#L37)、[PioneerDebugService.kt:72](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerDebugService.kt#L72)、[PioneerDebugService.kt:341](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerDebugService.kt#L341)。
- 来源：ce39d7cf 延续至当前。
- 建议：有界队列、总处理时限和写 watchdog；拒绝时立即关闭 socket；上传/导出与轻量控制命令分开限流。不能据此认定今天 TCP 断连就是此原因，现场尚无对应证据。

### F07 · P2 · 蓝牙代理快速重启时，旧线程可能关闭新会话

GatewayService 复用 server，close 后立即 start；旧 accept/relay 线程退出时仍清理共享 bluetooth 和 client 字段。新连接如果已经写入这些字段，旧 finally 会把新蓝牙连接关闭并清空新 client。

- 代码：[RfcommGatewayServer.kt:129](../../bt-proxy-android/src/main/java/com/shihab/diplay/btproxy/RfcommGatewayServer.kt#L129)、[RfcommGatewayServer.kt:196](../../bt-proxy-android/src/main/java/com/shihab/diplay/btproxy/RfcommGatewayServer.kt#L196)、[GatewayService.kt:138](../../bt-proxy-android/src/main/java/com/shihab/diplay/btproxy/GatewayService.kt#L138)。
- 来源：01d551b4 的共享清理设计；e4fa5e5b 增加长期服务复用后，快速重启路径会触发。
- 建议：每次启动有独立的会话对象/代次；finally 只关闭本次本地持有的资源，清空共享字段时核对它仍属于自己。

### F08 · P2 · 蓝牙服务查询完成后，界面可能永久显示忙碌

SDP 查询成功回调和超时回调都只追加日志；真正清空 sdpAddress 的代码在另一个 observeLog 方法里，这两条路径没有调用它。第一次正常完成或超时后，再次查询就持续 BUSY。SDP 指蓝牙服务发现，不是 CarPlay 音视频流。

- 代码：[ProxyController.kt:98](../../bt-proxy-android/src/main/java/com/shihab/diplay/btproxy/ProxyController.kt#L98)、[ProxyController.kt:118](../../bt-proxy-android/src/main/java/com/shihab/diplay/btproxy/ProxyController.kt#L118)、[ProxyController.kt:374](../../bt-proxy-android/src/main/java/com/shihab/diplay/btproxy/ProxyController.kt#L374)、[ProxyController.kt:438](../../bt-proxy-android/src/main/java/com/shihab/diplay/btproxy/ProxyController.kt#L438)。
- 来源：当前未提交的 ProxyController 拆分。
- 建议：统一 finishSdp，在成功、拒绝、超时、取消时直接更新结构化状态；日志记录结果，不负责驱动业务。

### F09 · P2 · 仪表文字发送成功，被错误当成完整音乐状态同步成功

CEAM 这里只发送歌名、歌手和专辑；VehicleMediaRuntime 却只在它失败时才给 WeCarFlow 发布完整状态。CEAM 成功时，WeCarFlow 的播放/暂停状态、进度和远程缓存不会更新，后打开的 widget 也可能拿不到当前歌曲状态。

- 代码：[VehicleMediaRuntime.kt:175](../../shared/src/main/java/com/shilapi/xcertplay/vehicle/runtime/VehicleMediaRuntime.kt#L175)、[CeamInstrumentMediaSink.kt:13](../../shared/src/main/java/com/shilapi/xcertplay/vehicle/adapters/pioneer/CeamInstrumentMediaSink.kt#L13)。
- 来源：f858ec18 延续至当前。
- 建议：仪表文字和 WeCarFlow 完整状态分别同步；或让接口明确自己能发布哪些字段。一个布尔成功值不能代表不同通道具备相同能力。

### F10 · P2 · 升级在会话状态未知时放行，上传期间也没有重新检查

读取主线程状态超时/错误时，snapshot 返回错误字段而没有三项会话字段；update 用 optBoolean 把缺失值当 false，于是通过空闲判断。另外上传开始后，另一个命令仍能启动 CarPlay，安装器打开前没有复查。

- 代码：[PioneerDebugService.kt:466](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerDebugService.kt#L466)、[PioneerDebugRuntime.kt:78](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerDebugRuntime.kt#L78)。
- 来源：ce39d7cf 的上传前检查延续，当前新增双应用升级仍沿用。
- 建议：统一空闲状态校验，未知默认不放行；升级与启动串行管理，至少在交给安装器之前再次确认，避免提示“已停止”但实际又在会话中。

### F11 · P2 · 切后台时在主线程等待解码器，存在明显卡顿窗口

新增先锋 onPause 分支直接调用 SurfaceDetach.await。默认等待 2 秒，加 0.5 秒退出宽限；如果硬件解码器没有及时响应，切换应用/音源的主线程会被同步阻塞。这里为了避免 Surface 被回收时解码器仍使用它，保护目的合理，但当前等待位置会把解码故障放大为界面卡顿。

- 代码：[CarPlayHostActivity.kt:1785](../../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L1785)、[AndroidMediaSink.kt:352](../../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L352)、[AndroidMediaSink.kt:424](../../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L424)。
- 来源：当前未提交的先锋 Surface 生命周期修复。
- 建议：尽早发起离屏准备、统一退场时限并记录 UI 等待耗时；保留释放确认屏障，不可直接删除等待或盲目异步。此处是可证明的最坏等待路径，不是本轮重新测出的真实卡顿时长。

### F12 · P2 · 日夜模式接口初始化失败后，同进程内不再重试

start 在取得控制器、注册监听成功之前就把 started 设为 true。若开机时服务暂未就绪、控制器为空或监听注册失败，后续 start 都直接返回。调试叠层的 queryNow 只展示查询，不会把连续查询结果当成正式模式更新，因此不等价于恢复监听。

- 代码：[CamryDayNight.kt:50](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/CamryDayNight.kt#L50)、[CamryDayNight.kt:62](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/CamryDayNight.kt#L62)、[CamryDayNight.kt:88](../../pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/CamryDayNight.kt#L88)。
- 来源：2a365435 引入的日夜模式订阅，当前未提交修改仍保留该启动状态设计。
- 建议：区分未启动、初始化中、已注册和暂时失败；对可恢复错误有限重试，且成功后避免重复注册。需要模拟服务晚启动或中途重启验证。

### F13 · P2（低频） · native socket 编号过大时存在进程级崩溃风险

自研 pump_tcp 直接把 fd 放入 FD_SET，未检查 FD_SETSIZE。进程 fd 编号达到 1024 时，固定大小的集合可能触发 fortify 终止或越界。限制本模块连接数量不等于限制全进程 fd 编号。

- 代码：[pioneer_lwip.c:182](../../pioneer-adapter/src/main/cpp/pioneer_lwip.c#L182)。
- 来源：ce39d7cf，自研 pump，不是第三方 lwIP 算法原文。
- 建议：此处检查单个 socket，优先改为 poll；或明确拒绝过大的 fd 并走清理流程。NDK 宏边界已核对，但没有证据表明车机当前达到该阈值。

## 代码组织：值得保留的部分

- `vehicle/api → adapters → runtime` 的分层有价值，普通播放器不应直接依赖厂商 Binder 细节。
- WeCarFlow 独立 `:carplayguard`、原业务进程按需登记 Binder 的方向符合“只启动桥接”。需要清掉 F01 旧路径，而不是放弃隔离设计。
- lwIP raw API 的唯一 owner 线程、容量受限的收发队列、已授权 USB fd 的借用方式合理；不建议为整理代码另加一套并发访问模型。
- 音源恢复规则拆为纯策略、异步发送前再核对会话/用户操作、临时焦点有截止时间，这些都比把逻辑直接塞入 Activity 更易验证。
- 固件识别后才使用 400/401 音频流、主媒体与导航混音焦点分开、非阻塞 PCM 写入有时限，属于应保留的实车适配。
- 独立实验 App 与正式 App 分模块是正确的；共享签名、上传时核对目标包/签名以及保留安装器正在使用的文件也合理。

## 代码组织：建议怎样整理

### 1. 把“正式设备策略”从 Debug 单例移出

现在 AirPlay 声明、音频时钟、解码实验、TCP 状态和 UI 回调都依赖 `PioneerDebugRuntime`。这会让正式播放行为看起来像调试开关，还把 Android Handler 初始化带入纯策略测试。

建议形成三层：设备能力识别 → 本次会话的不可变策略 → 调试覆盖值。覆盖值只影响明确声明的实验，正式音频和协议层通过小接口读取策略，不直接依赖调试服务。

### 2. 集中管理资源与取消，不先重写协议

把端口 lease 的 add/remove/中断补偿集中到 owner 管理；代理握手做成可以关闭的独立对象。优先修 F02/F04/F05/F07，让每个 socket、Binder、线程和端口都能回答“谁创建、谁释放、旧会话能否碰新资源”。单纯把大文件拆小不能解决这些问题。

### 3. 页面只处理显示与用户操作

`CarPlayHostActivity` 当前约 7106 行、设置 Activity 约 6619 行；其中相当部分是上游既有体积，不应归罪于本次适配，但继续塞先锋状态、TCP host-ui 命令和 Surface 退场会加大冲突。

建议将适配宿主生命周期、诊断命令、音源恢复放入明确组件，Activity 只 attach/detach。所有设备适配由同一个装配入口协调；现在 `DiPlayVehicleIntegration` 中 runtime 与 sourceRecovery 两套并行生命周期仍可进一步统一。

### 4. 调试命令与状态机分离

`PioneerDebugService` 同时包含读取状态、系统诊断、UI 操作、音源实验和 APK 升级。拆成命令分发表、只读诊断、会话控制、升级协调几个小组件，并复用同一套输入验证/截止时间/空闲判定。

代理诊断用结构化事件更新状态，日志只负责展示。F08 正是“状态靠日志文本间接变化”的直接后果。

### 5. 给两份 WeCarFlow 适配明确身份，并补齐版本控制

根 `wecarflow` 是当前 `com.tencent.wecarflow.s0/x0` 基线；`DiPlay/vehicle-support/wecarflow` 是旧 `com.tencent.qqmusic` 基线，不能当作同一源码的两份拷贝直接合并或删除。

应让当前项目进入版本控制，至少覆盖 patch-src、scripts、tests、release.json 和文档；固定原始包摘要和工具版本，排除密钥、APK 与构建产物。旧目录标明 legacy、目标基线和停止维护状态。打补丁尽量从固定干净基线生成，不能只依赖 marker 跳过而假定旧注入会自动升级。

中央状态当前在 `:carplayguard`，却仍叫 `GuardRuntime.mainProcess`，容易误读成原 UI 主进程；应改为能准确表达“中央状态进程”的名称。文档也需统一当前进程模型、签名和是否需要卸载的信息。

### 6. 提高自研 native 代码可读性，保留第三方边界

`pioneer_lwip.c` 仅约 314 行，却集中 JNI、socket pump、端口注册、邻居学习和诊断，很多行内含多次状态修改。先格式化并提取有名字的内部函数，再按责任拆分；保持唯一 owner。

把实验开关标成“已验证默认 / 仅对照 / 已废弃”，为日夜、麦克风、音源恢复等维护统一的验证矩阵：代码版本、车机包版本、已测场景和结论。避免一份旧交接文档盖过实际已安装版本。

## 仍待验证，不应当作已确认修复或已确认缺陷

1. **MODE/蓝牙切换后音频恢复。** v96 的有界焦点+PLAY逻辑已构建和单测，不能保证 iPhone 从 A2DP 回到 CarPlay；这需要实际确认新 SETUP、音频接收和听感。它也会保守取消任何观察到的暂停，无法可靠地区分所有手机端系统暂停与用户暂停，这是明确的行为限制。
2. **真实电话上行。** 已证明先锋麦克风授权能拿到非零采样，但完整来电/拨号→麦克风→编码/RTP→对端可听尚需闭环。统计非零不等于对方已经听到。
3. **WeCarFlow 中央进程回收与旧回调。** 目前 fail-closed、修订号和 Binder 重登记方向合理；还需验证重启前后的修订是否可能重复，让旧 prepared/retry 误被当成新操作。应考虑进程 epoch，但本轮没有用实车证明穿透。
4. **包更新后的绑定恢复。** `onBindingDied` 没有重绑是 API26+ 扩展场景隐患；当前车机 API25 不会收到这项新回调，不能直接把它算成当前先锋必现故障。实际升级回调与恢复需单测/实机确认。
5. **长时运行与故障注入。** 端口队列满、初始化线程中断、反复快速重连、fd 压力、慢调试客户端、硬件解码器超时均未实车压测。本报告提供触发路径，不把它们冒充已发生事故。
6. **日夜真实灯光与完整前后台。** 已有诊断命令的成功记录仍需与真实车灯/系统状态闭环；没有因为本地代码可以发送命令就宣称所有情境完成。

## 本次实际检查及结果

- DiPlay 定向 Gradle 检查：**73 项通过，0 失败**。涵盖 AudioFocusAutoYield（25）、VehicleMediaFocusRecovery（4）、Android6Compatibility（2）、CarPlayMediaFocusForwarding（30）、PioneerSourceRecoveryPolicy（8）、CarPlayQueuedRecovery（4）。这是本轮新执行的结果，不是引用旧日志。
- WeCarFlow：tests 下 6 项、scripts/tests 下 1 项，**7 项通过**。包含 Java 运行时/门禁/弹窗/协议及补丁选择幂等检查；使用替身，不能代表 Android 多进程行为全部验证。
- `git diff --check` 无空白错误；Python 调试客户端帮助可正常解析。
- 以临时空目录设置 `DIPLAY_AUTH_ASSETS_DIR`，执行 `:mobile:rejectBundledCredentials`，**按预期复现缺认证资源失败**。这是 F03 的验证，不是 GitHub Actions 整体实跑，也没有改动真实素材或签名。
- 未执行全量 CI、全量 lint、原始第三方依赖审计或车机测试；没有安装、重启或清理任何车机 App。

## 建议处理顺序

1. 先修 F01，验证启动 DiPlay / 开始音乐时只有 WeCarFlow 桥接进程，原主进程不会被广播拉起。
2. 修 F02/F04/F05/F07，补充假代理、假 owner 和并发取消测试，先保证取消与释放正确。
3. 修 F03，明确源码验证包与车机发布包，确保全新环境有可重复构建路径。
4. 修 F06/F08/F10，保证局域网调试和升级在慢连接/异常状态下仍可控制。
5. 修 F09/F11/F12/F13，并完成真机音源、电话、日夜及前后台验证。
6. 上述边界稳定后，按组件逐步抽离；每次只移动一个职责，并保持包名、签名、协议及已验证设备默认不变。

本次按“保持现状”和只读 review 执行，以上修复均尚未实施，车机状态保持不变。
