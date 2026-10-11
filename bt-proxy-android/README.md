# Android A 蓝牙代理

独立 Android 应用模块 `:bt-proxy-android`，用于旧 Android 手机上的蓝牙代理 PoC。
使用 Kotlin 和原生 Android SDK，不依赖 `:common`、`:shared`，不打包 iAP2/MFi 身份素材。
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
页面旋转或重新启动会开始新的页面日志。

日志也可通过 ADB 获取（多设备时添加 `-s <Android A 序列号>`）：

```sh
adb logcat -d -s BtProxyProbe:I '*:S'
adb shell run-as com.shihab.diplay.btproxy cat files/probe-log.txt
```

“启动 Wi-Fi 蓝牙透传代理”监听本机 Wi-Fi IPv4 地址的 `39877` 端口，使用应用私有令牌认证单个连接，再连接选定
iPhone 的 RFCOMM。使用 CPBT 帧封装原始双向字节；代理不解释 iAP2 数据或加载认证素材。
部署时在应用私有目录写入真实本机地址 `files/gateway-bluetooth-mac.txt`，令牌保存在
`files/gateway-token.txt`，禁止将令牌写入日志或 Git。

当前 Nexus 5 为 Android A，小米 13 为 Android B。两台设备连接同一路由器或 B 的热点，
B 通过 Wi-Fi TCP 直接连接 A。B 的 debug 版本读取私有 `files/remote-probe.json`
（字段 `gatewayBluetoothMac`、`token`），诊断入口和主 CarPlay 控制器均支持此透传流。
认证、AirPlay、音视频和触控仍由 B 处理。release 版本不读取此调试配置。
A 发布 `_diplay-proxy._tcp.` 服务；B 根据预先配对的 A 蓝牙设备标识发现服务，
解析当前 IP 和端口，再使用私有令牌认证。不广播令牌，也不保存发现到的 IP。
服务名冲突造成的系统自动重命名可被识别；解析后还会核对设备标识与认证响应。
无需 ADB forward/reverse；USB 可以仅用于安装和日志，也可以在启动后拔掉。
Wi-Fi 未连接时代理会显示启动失败；Wi-Fi 切换或 A 的 IP 改变后，重启代理和连接即可重新发现，
无需更新 B 的 IP 配置。发现等待最多 15 秒，失败时不会退回固定 IP。
代理尚未使用前台服务，验证时保持页面在前台；退出页面或销毁 Activity 会关闭隧道。
Socket 连接成功不等于双向协议收发、认证或完整 CarPlay 成功。
按 [PoC 技术方案](../../CarPlay_Gateway_Mobile_PoC_Technical_Plan.md) 继续验证后续链路。
