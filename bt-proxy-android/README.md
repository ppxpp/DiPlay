# DP无线外挂（`:bt-proxy-android`）

独立 Android 应用模块，应用名 **DP无线外挂**，把旧 Android 手机变成 DiPlay 的无线外接设备：
与 iPhone 完成蓝牙协商（iAP2/RFCOMM），并通过 Wi-Fi 网络把字节透传给车机端。
使用 Kotlin 与原生 Android SDK（只额外依赖 `androidx.appcompat` 与 `androidx.core` 取 DayNight、
insets 与返回分发），不依赖 `:common`、`:shared`，不打包 iAP2/MFi 身份素材。
Android 最低版本为 6.0（API 23），包名为 `com.shihab.diplay.btproxy`，可与 DiPlay 同时安装。

## 构建与运行

沿用仓库的 JDK 25、Android SDK 和 Gradle wrapper，参见 [构建说明](../docs/BUILD.md)。
在 DiPlay 根目录执行：

```sh
./gradlew --configure-on-demand :bt-proxy-android:assembleDebug
adb install -r bt-proxy-android/build/outputs/apk/debug/bt-proxy-android-debug.apk
```

也可以在 Android Studio 中选择 `bt-proxy-android` 模块，运行到 Android A。
`--configure-on-demand` 用于只配置当前模块，避免其他模块触发无关的 NDK 安装。
启动后申请蓝牙权限，打开系统蓝牙设置完成 iPhone 配对，再返回查看环境状态。

## 当前范围

已实现蓝牙权限申请、已配对设备选择、SDP 服务查询、指定 iAP2 UUID 的真实 RFCOMM
连接、主动断开及日志复制。Android 12+ 的扫描权限用于连接前取消系统扫描；应用不发起设备扫描。
SDP 查询 12 秒超时，RFCOMM 建连 15 秒超时，成功后只接收观察 10 秒并自动关闭。
连接在独立线程执行，超时、取消及页面销毁会关闭 Socket。被动观察不发送数据；
“验证 iAP2 双向握手”使用复用的协议源代码进行有界诊断，不执行身份识别或认证。
日志记录阶段、连接耗时、接收字节数和错误，保留最近 200 行；不记录原始协议载荷。
日志同时保存在内存与 `files/probe-log.txt`，旋转、切页和重启进程后仍能回看。

## 界面

界面复用 DiPlay 的手写 View 设计语言：色值放在 `res/values/colors.xml` 与 `res/values-night/colors.xml`
（取自 `:common` 的 `DiPlayPalette`），形状、间距与控件由 `ui/ProxyTheme.kt` 和 `ui/ProxyComponents.kt`
统一提供，跟随系统 day/night。

每个页面是独立的 Activity，顶部使用 AndroidX 标准动作栏（`ui/ProxyActivity.kt` 提供 Toolbar、
标题与返回箭头，二级页 `setDisplayHomeAsUpEnabled`，`android.R.id.home` 与系统返回键都回到上级页面）：

- `MainActivity` 首屏自上而下是：前置条件入口（`3/5 就绪 · 蓝牙开关`）、目标 iPhone 入口、
  启动服务 / 关闭服务开关（含一句话状态，如 `等待开启蓝牙`），随后是运行状态（链路阶段、双向字节数、
  会话时长、监听与对端端口）与诊断、日志、设置入口；动作栏副标题显示整体状态
  （环境就绪 / 前置条件未完成 / 透传中）。整页可滚动。
- `ui/ReadinessActivity` 前置条件清单（每项带状态与动作）、`ui/DevicesActivity` 目标设备选择、
  `ui/DiagnosticsActivity` 开发者诊断（SDP / RFCOMM / iAP2 握手，结果内联显示）、
  `ui/LogActivity` 运行日志（按 phase 着色、过滤 chips、自动滚动、复制/清空）、
  `ui/SettingsActivity` 设置（配对码、本机蓝牙地址、打开蓝牙设置）。
- `ui/AboutActivity` 关于：本应用的定位、作用与用法，从设置页进入。

二级页只渲染本页内容，不再共享首屏的卡片。

状态由前台服务经进程内单例 `GatewayRuntime` 推送到界面，不再每秒轮询 `SharedPreferences`；
`gateway` 配置项 `status`/`ports`/`iphone` 仍按原语义写入，供前台服务与 ADB 排查使用。
`RfcommGatewayServer` 通过结构化 `stateListener` 上报阶段，服务不再用日志子串反推状态。

日志也可通过 ADB 获取（多设备时添加 `-s <Android A 序列号>`）：

```sh
adb logcat -d -s BtProxyProbe:I '*:S'
adb shell run-as com.shihab.diplay.btproxy cat files/probe-log.txt
```

代理启动后自动通过前台服务监听所有本机 IPv4 地址的 `39877` 端口，使用应用私有令牌认证单个连接，再连接选定
iPhone 的 RFCOMM。使用 CPBT 帧封装原始双向字节；代理不解释 iAP2 数据或加载认证素材。
首次启动尝试从系统读取真实本机蓝牙地址，受限时通过 A 的“配对信息”入口补充；地址自动保存到私有 `files/gateway-bluetooth-mac.txt`，令牌自动生成并保存在
`files/gateway-token.txt`，禁止将令牌写入日志或 Git。

当前小米 13 为 Android A，Nexus 5 为 Android B。两台设备连接同一路由器，或 B 加入 A 的热点，
B 通过 Wi-Fi TCP 直接连接 A。B 在 DiPlay 的“A 设备”页面查找并配对 A，凭据保存在不备份的私有配置中。已有 debug `files/remote-probe.json` 会迁移，正式版本也使用应用内配对配置。
认证、AirPlay、音视频和触控仍由 B 处理。
A 发布 `_diplay-proxy._tcp.` 服务；B 根据预先配对的 A 蓝牙设备标识发现服务，
解析当前 IP 和端口，再使用私有令牌认证。不广播令牌，也不保存发现到的 IP。
服务名冲突造成的系统自动重命名可被识别；解析后还会核对设备标识与认证响应。
无需 ADB forward/reverse；USB 可以仅用于安装和日志，也可以在启动后拔掉。
Wi-Fi 未连接时代理会显示启动失败；Wi-Fi 切换或 A 的 IP 改变后，重启代理和连接即可重新发现，
无需更新 B 的 IP 配置。发现等待最多 15 秒，失败时不会退回固定 IP。
代理使用 connectedDevice 前台服务，退出页面不会关闭隧道；手动停止会暂停自动启动，点击启动后恢复。
单个已配对的 iPhone 自动选用；多台时使用已保存的选择，没有选择时在 A 页面确认目标。
UDP `39876` 为发现兼容入口；回应不包含令牌，B 以响应来源地址作为 TCP 目标，NSD 为回退。
首次添加新 A 时，从 A 的“配对信息”获取配对码，在 B 验证后保存；无需电脑写入令牌或固定 IP。
Android 限制读取本机蓝牙地址时，A 的首次配置页面允许填写系统设置中的地址；固件硬件应直接读取自身地址。
当前实现是打开代理应用后自动启动，尚未实现 Android 系统开机自启动。
Socket 连接成功不等于双向协议收发、认证或完整 CarPlay 成功。
按 [PoC 技术方案](../../CarPlay_Gateway_Mobile_PoC_Technical_Plan.md) 继续验证后续链路。

## 临时固定连接码（2026-10-12）

外接端设备与 DiPlay 车机端统一使用代码常量 `123456`。DiPlay 的“代理设备 · 查找与连接”
页面发现代理后自动保存设备身份，点击连接即可，不再要求首次输入配对码。
旧私有令牌会自动迁移为固定连接码。IP 和端口继续通过发现获取，不内置对端 IP。
此临时开发模式覆盖上文首次配对码流程；iPhone 与代理设备的蓝牙配对仍由系统完成。

## 车机防火墙下的发现（2026-10-13）

车机 OEM 防火墙（`iptables_rules.sh`）只放行入站 UDP `9911/9912/5656`，且代理按请求源端口
回包——DiPlay 的 UDP 广播发现按 `9911 → 5656 → 9912` 顺序占用源端口，全部被占时才回落
临时端口并记录告警日志。外接端无需改动。已删除此前的 `DEVELOPMENT_DIRECT_CONNECTION`
固定 IP 直连开关：IP 和端口始终通过发现获取，不内置对端 IP。
验证日志：`Gateway broadcast discovery bound source port=…`（钉口成功）与
`Gateway discovered address=… source=udp-broadcast`（发现成功）。
