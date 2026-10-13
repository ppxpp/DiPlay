# 当前上下文与开发进展

更新日期：2026-10-11。本文用于后续开发交接，记录现状，不是实施计划。

## 1. 当前目标与工作方式

目标是在 DiPlay 中使用外置 A 提供 iPhone 蓝牙引导，B 保留 CarPlay 协议与音视频处理。
最终 A 为无屏专用硬件，用户正常使用只需供电，通过 B 的 DiPlay 管理；Android 手机 A
只是当前的临时验证端。A 必须支持 5 GHz Wi-Fi，这是已确认的硬件约束。

用户要求快速验证，避免过度计划、强制 TDD、每次新增单元测试或默认全量回归。
以必要的模块构建、真机验证和日志为证据。不能用构建成功或蓝牙认证代替完整 CarPlay。
工作要求见 [PROJECT_AGENT_REQUIREMENTS.md](PROJECT_AGENT_REQUIREMENTS.md) 与根目录 AGENTS.md。

最新工作范围：**先整理 A/B 管理需求，尚未开始实现管理页面或管理接口。**
用户之前问过管理/升级实现，但已明确改为先分析需求；后续不能把先前讨论当成已完成开发。

## 2. 仓库与提交

- 工作目录：`/Users/zhouhao/work/project/carplay`。
- Git 仓库：其中的 `DiPlay` 子目录；上级目录本身不是 Git 仓库。
- 分支：`main`。
- 最新提交：`01d551b4`，`Add remote Bluetooth proxy discovery and management requirements`。
- 该提交包含 27 个文件：代理模块、远程透传、自动发现、打包调整、验证记录和管理需求。
- 已提交本地，尚未推送远程。
- 上级 `agent.md` 的要求已同步到仓库内 `docs/PROJECT_AGENT_REQUIREMENTS.md`。
- 本文为该提交之后新整理的交接文档，尚未提交。

## 3. 当前设备分工

| 角色 | 设备 | ADB 序列号 | 应用 |
|---|---|---|---|
| A | Nexus 5，Android 6.0.1 / API 23 | `08b4e9a20de78f14` | `com.shihab.diplay.btproxy`，代理 0.5.0 |
| B | 小米 13，Android 15 / API 35 | `e090fd5c` | `com.shihab.diplay.hudtest`，DiPlay debug |
| iPhone | 当前测试 iPhone | — | 系统蓝牙与无线 CarPlay |

当前使用路由器 Wi-Fi，最后验证地址：A `192.168.2.157`，B `192.168.2.152`，
iPhone `192.168.2.148`。这些只是诊断记录，不是应用的固定地址配置。
早期“小米作为 A、模拟器作为 B”的记录属于历史阶段，现在不再使用模拟器。

## 4. 当前链路

```text
蓝牙引导：iPhone ⇄ Bluetooth Classic / RFCOMM ⇄ A ⇄ Wi-Fi TCP / CPBT ⇄ B
CarPlay： iPhone ⇄ 路由器 Wi-Fi（或 B 热点）⇄ B 的 AirPlay / CarPlay
```

A 只建立蓝牙连接并透传原始字节，不解析业务 iAP2，不保存 MFi 认证素材。
B 执行 iAP2、Identification、MFi、网络引导、AirPlay、视频、音频及触控。
Wi-Fi iAP2 接管后，关闭蓝牙 bootstrap 是正常移交，不等于 CarPlay 断开。

A/B 已移除 ADB forward/reverse，数据通过 Wi-Fi 直接传输。USB 留作安装与日志。
本轮确认两台 Android 仍连接电脑；尚未收到拔掉 USB 后持续运行的用户确认。

## 5. 自动发现现状

- A 发布 Android NSD / mDNS 服务 `_diplay-proxy._tcp.`。
- 服务名由 A 蓝牙设备标识生成，TXT 包含公开标识和协议版本；不包含认证令牌。
- A 在本机 Wi-Fi IPv4 监听 TCP `39877`，发现服务提供当前端口。
- B 私有 `files/remote-probe.json` 保存 `gatewayBluetoothMac` 与 `token`。
  已删除 `host` 和 `port`，没有固定 IP 回退。
- B 每次连接重新发现并解析地址，核对标识，完成令牌认证后再核对响应标识。
- 发现等待最多 15 秒；服务名称冲突时支持系统自动增加的名称后缀。
- 两端仍需预先配置相同令牌和 A 的标识；尚无用户可操作的首次绑定界面。
- 当前远程接入配置仅在 B 的 debug 版读取，release 尚未开放此配置入口。

当前同路由器自动发现已验证；换网、DHCP 地址变化和 B 热点下重新发现尚未专门验证。

## 6. 已验证结果与限制

| 项目 | 证据与结论 |
|---|---|
| 真实双向蓝牙透传 | 主动 iAP2 交互及两方向计数通过 |
| iAP2 / Identification / MFi | iPhone 返回识别接受和 `0xaa05 AuthenticationSucceeded` |
| 小米热点 CarPlay | AirPlay 配对、SETUP/RECORD、Wi-Fi iAP2 认证、视频渲染通过；用户确认触控和声音正常 |
| 切回路由器 | 同样成功；用户再次确认触控和声音正常，不能认定之前失败由路由器或 5 GHz 导致 |
| A/B Wi-Fi TCP 直连 | A 确认连接来源是 B 的 Wi-Fi 地址，ADB 转发列表为空；认证、AirPlay及首帧通过 |
| 自动发现迭代 | B 删除 IP 配置后约 93 ms 发现 A，继续完成认证、AirPlay和首帧；该次未重复用户音频确认 |
| 构建 | A 代理和 B mobile debug 均构建成功 |

未验证：专用硬件固件、首次无屏配置、后台/上电自动运行、硬件 OTA、长时间稳定性、
更多机型、通话、Siri/麦克风、完全无电脑的冷启动闭环。

热点成功实验同时用了 MANUAL 模式和实际信道 36 的 debug 覆盖，不是只换了网络。
之后路由器 EXISTING_WIFI 使用系统读到的信道 36、未启用热点覆盖，也成功。
初次失败根因仍未确定，不把某一次成功当作单一因素的因果证明。

详细时间线见 [ANDROID_A_RFCOMM_VALIDATION.md](ANDROID_A_RFCOMM_VALIDATION.md)。

## 7. 私有素材与构建

- 已按用户授权从 `DiPlay-0.2.17.apk` 提取实验性认证素材。
- 项目副本：`.private/runtime-assets/offline-mfi/identity.pk8`、`certificate.p7b`。
- 当前小米热点配置：`.private/runtime-assets/network/hotspot.json`。
- mobile 默认从此目录打包；可用 `DIPLAY_AUTH_ASSETS_DIR` 覆盖。认证文件缺失时构建明确失败。
- 热点配置作为首次配置默认值；不覆盖已保存配置，不能代替系统热点的实际开启。
- `.private/` 被 Git 忽略；认证素材、热点密码、令牌、安装包和私有日志没有纳入提交。
- 私有实测日志/截图位于 `.private/validation/`。A 的 APK 不包含 MFi 素材。

常用构建：

```sh
ANDROID_HOME=/Users/zhouhao/Library/Android/sdk ./gradlew --configure-on-demand \
  :bt-proxy-android:assembleDebug :mobile:assembleDebug
```

SDK 的 adb 路径为 `/Users/zhouhao/Library/Android/sdk/platform-tools/adb`。
仓库使用 Gradle 配置的 JDK 25。具体构建与私有素材说明见 [BUILD.md](BUILD.md)。

## 8. 管理需求与下一步边界

需求文档：[ANDROID_A_MANAGEMENT_REQUIREMENTS.md](ANDROID_A_MANAGEMENT_REQUIREMENTS.md)。

已整理：B 原生管理页、设备发现与授权、首次配网、状态、连接控制、网络、日志、升级、
自动运行及恢复能力；管理通道需独立于透传，以便正常移交后仍能观察 A。

关键待确认项：

1. 未联网 A 的首次配网/授权方式，以及无屏故障恢复路径。
2. iPhone 只辅助配网与授权，还是另提供完整管理入口。
3. Android APK 升级验证与硬件 OTA 的先后、升级来源及双芯片升级要求。
4. 一 A 对一 B 或多 B 授权下的会话转移策略。
5. 最终硬件组成与实际蓝牙、配网、升级/回滚能力。

后续开发应先确定必要需求，再实现平台无关的管理接口和 B 页面，用 Android A 验证。
不能将 Android Activity/ADB 操作写成最终产品依赖；也不能把“升级入口存在”当作 OTA 已完成。
