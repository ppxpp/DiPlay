# QQ 本机播放保护补丁

维护入口属于 DiPlay；目标为已解包的 `com.tencent.wecarflow` QQ 音乐。脚本不重打、签名或安装 QQ APK，不更改 PAL 接入 runtime；通过精确幂等补丁扩展既有仪表 ClusterMediaBridge/UpdateWidgetService。

```sh
python3 vehicle-support/wecarflow/apply_guard.py \
  --apktool-dir /path/to/qqmusic/apktool/qqmusic
```

需要 JDK、Android SDK 的 `android.jar`/`d8` 与 `apktool`。SDK 从 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 macOS 默认位置读取。`--compile-only` 编译 Java/d8 并生成 smali，不写目标。脚本先验证每个精确方法签名，缺失/重复或没有现有 v0 局部寄存器时直接报错，不按模糊名字猜入口。重跑按标记跳过已存在的注入，重新生成服务 smali 和 manifest 专用块。`last-applied.json` 记录授权方式与覆盖清单。

## IPC 和授权

显式组件为 `com.tencent.wecarflow/com.tencent.wecarflow.carplayguard.PlaybackGuardService`，服务在 QQ 主进程。raw Binder descriptor 为 `com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1`。客户端写 interface token 后：

- code 1：写入 owner IBinder；回复 `writeNoException()` 和 int bool。仅同步置保护并暂停已存在播放器成功后返回 1。
- code 2：写入同一个 owner IBinder；只有相同 UID、相同 owner 可以解除，回复 int bool。
- code 3：无参数；回复当前 guarded ready 的 int bool。
- code 4：写入 owner IBinder、title/artist/album 三个 String、durationMs/elapsedMs 两个 long、playing 的 int bool。相同 UID 和 owner 且 ready 才能发布，回复 int bool 表示接受异步仪表发布请求。缺少现有 bridge 源码时明确不可用，返回 0。

owner IBinder 同时是控制回调：使用相同 descriptor、code 1、int command（1 PLAY、2 PAUSE、4 NEXT、5 PREVIOUS），读取客户端 bool reply。仪表 STOP 映射 PAUSE。控制结果只回复发起请求的 Messenger，不群发；CarPlay 状态以真实元数据回传为准，不靠控制动作猜测 playing。release/death 自动清除 remote，无需额外 code 5。

所有业务事务核验 calling UID 下存在 `com.gtmc.app.fengyunyx.b819`，并核对已安装的该包 UID 与调用 UID 一致。按用户要求不校验 DiPlay 签名证书，不生成证书允许表；后续更换 DiPlay 签名无需修改 QQ 补丁。发布和解除仍需相同 UID、相同 owner 租约，Binder death 处理保留。

单 owner 租约仅存在内存中，Binder death、release、服务销毁解除；不会在磁盘保存 bool，也不会在解除时自动恢复歌曲。暂停屏障失败回复 0 并撤销保护，让客户端使用其回退策略。排队的暂停任务在超时/中断后取消并移除；执行前重新检查主进程保护状态。已经进入播放器暂停方法的调用无法完全撤销。acquire 必须在客户端后台线程执行：每进程主线程暂停等待最多 5 秒，其他进程 provider 冷启动还受系统调度影响。

## 进程与播放入口

当前 manifest 的 QQPlayerServiceNew 和两套播放器都在主进程；额外进程为 `:boot`、`:dexopt`、`:heap_analysis`、`:wns`。主进程非导出 GuardStateProvider 持有静态内存保护状态。每个额外进程生成独立 provider 子类及 authority，在该进程建立上下文、维护播放器弱引用，并提供同步暂停屏障。其他进程在具体命令/播放启动处通过主进程 provider 同步查询保护状态；不依赖跨进程静态 bool，不在 PCM 帧循环查询。provider 仅允许 QQ 同 UID 调用。

构造函数正常返回处登记 AudioPlayerManager、两个 component 播放器和 system AndroidMediaPlayer 的弱引用。首次 acquire 先置保护，再在当前已运行的 QQ 进程主线程同步暂停；不会为暂停检查冷启动 :wns/:dexopt 等进程。稍后启动的进程会通过 provider 查询主进程 gate。暂停方式：中央 manager 调用 `v0(false)`，system 播放器调用 `p()`，component 使用已存在 getter 直接暂停实际 Android MediaPlayer/ITPPlayer，避免仅提交异步 pause lambda 就宣称 ready。ThumbPlayer 仅在已识别的 STARTED=4 状态暂停。

共 25 个入口，准确签名见 apply_guard.py/last-applied.json：

- 新 PlayerManagerImpl 的 play、playSongs、playRadio、playPos、playCachedSongs、next、prev 入口，切换队列之前早退。
- 旧 AudioPlayerManager 的 y0/play、l1/stopAndPlay、g1/startPlay、N0/O0/resume 入口。
- 旧 PlayListManager.B(ZI)I 和新 MusicPlayListManager.z(ZI)I，覆盖 next/prev 共用的方向参数入口；新 MusicPlayListManager.b(ZI)V 覆盖自动下一首。
- AndroidMediaPlayer 与 EdgeThumbPlayer 的 public play、实际执行的 play lambda；旧 system AndroidMediaPlayer.I()V 的实际 MediaPlayer.start 路径。

注入在方法开头复用已有 v0，不增加 locals 或改变参数寄存器。void 返回直接 return-void；int 返回 -1。原始 PAUSE、STOP、音量、焦点、平台服务与 PAL bridge 均可正常执行。由音量触发的播放仍受底层 start 拦截。

## 仪表回退

脚本默认从 apktool 目录推导 QQ 的 `scripts/wecarflow_bridge_src`，也可指定 `--bridge-src`。该目录必须存在原始两份 Java；缺失时编译保护服务但明确关闭 remote bridge，不伪造已有能力。脚本同时更新维护源码和编译生成的 launcherwidget smali，并把仅含 guard 包的 `.class` 保存到 QQ `build/carplayguard/guard-api.jar`（生成物，不提交）。原 `scripts/build_wecarflow_bridge_smali.sh` 会获得可选 jar classpath 的幂等补丁：旧版未启用 guard 时可无 jar 构建；bridge 已启用 guard 而 jar 缺失时明确报错，提示先重新运行 apply_guard.py。原脚本只把 bridge 作为 dex program 编译，guard jar 仅作 classpath，不重复生成 guard smali，也不会删除 carplayguard 包。

native metadata/progress/playing 在 guarded 期间只更新 local 快照，不覆盖 remote。code 4 在 QQ 主线程发布 remote；解除/owner 死亡恢复最后 local 快照，仅恢复显示，不自动播放。遥控按钮 guarded 时直接请求 owner 回调，不注入 QQ NEXT/PREV。remote 允许真实 duration=0，移除原 bridge 的 240000 毫秒兜底和 1000 毫秒下限，未知队列位置/首尾状态不携带猜测值。

## 验证与范围

此次仅完成 Java/d8 编译、生成 smali、补丁方法/manifest 检查和目标 smali 语法检查，不代表已安装或真机验证。QQ APK 重打、QQ 签名、DiPlay 的 bind/acquire/release 时序、手机/车机上原音乐停止与切歌不改队列、客户端被杀后保护解除均待部署后检查。

本轮覆盖当前版本可识别的中央命令及三条实际启动路径；未逐一分析所有独立预览、视频广告、独立 native 播放器或动态热修复替换实现。它们如果绕过上述中央 manager/component/system 路径，不能据此声称已覆盖。后续 QQ 版本变更需重新对照方法签名和调用链，脚本会对已知入口失配报错。
