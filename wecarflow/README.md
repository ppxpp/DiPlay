# WeCarFlow CarPlay 播放保护与仪表适配

项目位于 `DiPlay/wecarflow`，作为 DiPlay 仓库的普通源码目录统一提交和推送。原包、密钥及构建产物仅保留本地。

从 `.private/WeCarFlow.apk` 重新反编译开发；不继承旧仪表探针补丁。基线包名 `com.tencent.wecarflow`，版本 `2.7.1.36971303 / 22101710`，SHA-256：

```text
e8c1671fc005527de3bb49b6733069c701cd5f86c5ec851e79b29aa09bcf7111
```

## 构建

需要 JDK 17、Python 3、apktool 3.0.1 和 baseline.json 指定的 Android SDK。`ANDROID_HOME` / `ANDROID_SDK_ROOT` 可指定 SDK；`ANDROID_BUILD_TOOLS_DIR` 可指定工具版本。

```sh
./scripts/verify.sh
./scripts/repack.sh build
./scripts/repack.sh print-output
```

默认只构建。安装单独执行 `./scripts/repack.sh install`；没有自动卸载、清数据、降级或修改网络规则。`all` 明确执行构建及安装。

签名与 DiPlay 共用工作区的同一份配置：本项目 `.signing/signing.properties` 指向的共享 keystore（默认 `.signing/repack.keystore`，由 `DiPlay/scripts/provision-signing.sh` 生成）。`QQMUSIC_KEYSTORE`、`QQMUSIC_KEY_ALIAS`、`QQMUSIC_KEYSTORE_PASS`、`QQMUSIC_KEY_PASS` 可逐项覆盖该文件。密码通过环境交给 apksigner 与 keytool，不写入日志或参数。产物证书必须与 keystore 自身的证书一致；配置缺失时构建直接失败，不再回退默认密码。`.private/qqmusic-reference.apk` 存在时仅作附加对照，不再阻塞构建。

基线APK可通过 `WECARFLOW_BASE_APK` 指定。上述私有输入已随本次迁移复制到 `.private/`，`.private/` 与 `.signing/` 都不提交 Git，不依赖旧项目的相对路径。

`release.json` 管理 WeCarFlow 独立版本。正式产物在 `release/`，附 SHA-256、证书摘要、基线摘要及版本 JSON。

## 结构

- `apktool/`：保留的反编译对照树；发布时不以它作为输入。
- `build/repack/decoded/`：每次发布从固定原包重建并应用补丁的工作树。
- `patch-src/`：保护协议、暂停适配、请求修订与原生仪表桥的 Java 源码。
- `scripts/playback_hooks.py`：PAL、媒体键、队列、播放启动及请求回调的精确补丁。
- `scripts/instrument_hooks.py`：保留原服务，接入生命周期、消息分发和发送过滤。
- `scripts/apply_patches.py`：先验证全部入口并编译，然后更新 smali 和 Manifest；重复执行幂等。
- `build/baseline/`：此次反编译的未改动副本及无业务修改构建。仅为本机验证，不提交。
- `build/archive/`：旧探针工作区和原脚本归档。
- `build/patch-report.json`：原始/改后文件摘要及进程清单。
- `docs/`：入口覆盖、协议、验证及限制。

重新建立工作区时运行 `python3 scripts/prepare_baseline.py` 生成未修改的测试基线；发布脚本自行生成 `build/repack/decoded/`，保留根目录对照树。

## 行为及依赖

现有 DiPlay 客户端显式绑定 `com.tencent.wecarflow.carplayguard.PlaybackGuardService`，使用 Binder v1 获取保护、发布 iPhone 歌曲并接收桌面控制回调。真实连接时 acquire，实际断开时 release；后台、暂停或仅导航不解除保护。没有活动 DiPlay 握手时，WeCarFlow 保持本机原功能。

保护门禁先于暂停开启，ready 只在已有播放暂停检查成功后返回。保留音量与共享焦点；显式取消中央播放消息，避免解除后执行旧消息。来源修订过滤旧查询、重试和准备完成回调。

仪表沿用原生 UpdateWidgetService：CarPlay 期间阻止本机歌曲覆盖，发布外部歌名、歌手、播放态、秒制元数据时长与毫秒制进度。订阅按协议分组；桌面控制只回传受理结果，播放态依赖真实 iPhone 数据。解除后查询本机状态，恢复显示但不调用播放。

## 验证边界

本机验证包括干净基线构建、Java/d8/smali 编译、补丁幂等与漂移拒绝、真实 Java 桥协议/进度测试、保护生命周期/队列取消/来源修订测试、最终 APK 对齐和签名一致性检查。

本次 carplay.5 只完成本地验证，尚未安装车机。此前车机已经迁移到共享签名，
当前新包可按相同签名覆盖更新，不需要卸载或清除数据。原厂签名或其他改包设备应先比较
已安装证书，不可把“首次迁移可能需要卸载”套用到这台已迁移车机。

v1 没有反向会话查询或请求级 generation。中央桥接进程重启会丢失内存保护，须 DiPlay 重绑并 acquire；重绑前不能承诺零播放窗口。同一个回调 Binder 被复用时，迟到的旧 release/update 入站请求也无法区分；内部修订只保护已接受的异步任务。完全消除这两类窗口需要双方扩展协议。未加入可能永久锁住播放的磁盘布尔开关。详细入口及并发边界见 `docs/playback-hooks.md`、`docs/runtime.md`。

## CarPlay 使用提示（carplay.2）

活动保护生效时，用户首次打开或从后台回到应用任意现有页面会看到提示，建议使用CarPlay音乐。内部切换页面不重复打扰。点击“关闭”或返回键可以关掉提示，保护仍保持；勾选“本次不再提示”后主进程重启前不再弹，断开再连接也不会清除该勾选。仅退到后台不算重启。

提示复用既有保护状态，不根据USB插线或界面是否在前台猜测连接。没有活动保护就不弹。应用已显示、DiPlay稍后才完成保护握手时，会补一次提示；断开保护时关闭过期提示。所有现有Activity均在主进程，由Application生命周期统一处理，不逐页修改，也不申请悬浮窗权限。

`GuardNotice`负责提示窗口，`GuardNoticePolicy`只保存内存中的提示状态，两者不调用保护的acquire/release。`tests/test_guard_notice.py`执行实际Java回调，检查无保护不弹、迟到握手、内部跳页、再次进入、勾选抑制、断开重连仍抑制、重启恢复以及保护不受影响。该测试使用Android替身，不代替车机UI实测。

双方策略与提示规则的通俗说明见[DiPlay与WeCarFlow冲突处理及弹窗说明](../docs/vehicle-adaptation/2026-10-16-2238-DiPlay与WeCarFlow冲突处理及弹窗说明.md)。


## 仅启动 DiPlay 桥接（carplay.3）

保护服务和中央状态提供者位于 `:carplayguard` 独立进程，跳过原 FlowApplication 业务初始化，仅登记 PAL 前后曲。DiPlay v90 起启动时预绑定这项服务，CarPlay 实际连接后才 acquire；不会为了绑定、暂停、提示或歌曲同步拉起原主进程。已运行的主进程和核心进程通过 Binder 登记，中央重启时重新登记。

实际安装说明以 [2026-10-17 16:01 改造与验证记录](../docs/vehicle-adaptation/2026-10-17-1601-DiPlay仅启动WeCarFlow方控桥接的改造与验证.md) 为准。当前车机 carplay.2 已确认与新包签名一致，可以覆盖升级，不需要卸载或清数据。该现场确认不代表其他原厂签名安装也能覆盖。

## 当前维护方式（2026-10-17）

中央保护状态位于独立 `:carplayguard` 进程，源码字段命名为 `centralProcess`，
与原应用主进程区分。当前车机已使用共享签名，可覆盖升级，不需要卸载或清数据；
原厂签名设备首次迁移的规则不适用于这台已迁移车机。

DiPlay 仓库只维护 `patch-src/`、`scripts/`、`tests/`、`docs/`、`release.json`、基线信息和说明。
反编译的 `apktool/`、原包、密钥、生成 smali、构建 APK 均不进入版本控制。
`scripts/repack.py` 从校验 SHA-256 的原包重新解包，再应用当前补丁并编译桥接源码，
不能依赖旧 marker 就假定生成源码已经更新。工具和原包信息见 `baseline.json`。

从新的源码 checkout 运行测试前，先把合法的原包放入 `.private/WeCarFlow.apk`，运行
`python3 scripts/prepare_baseline.py`。它校验原包摘要并生成测试需要的 `build/baseline/`。
然后运行 `scripts/verify.sh`。测试依赖原包对应的类结构，不把原包和完整反编译树提交到 Git。

发布包的实际中间源码在 `build/repack/decoded/`；根目录旧 `apktool/` 仅供对照，
修改它不会自动成为发布补丁。功能修改应放到 `patch-src/` 或明确的补丁脚本里。
