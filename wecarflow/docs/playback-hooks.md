# WeCarFlow 播放保护入口

基线为 backup/WeCarFlow.apk，版本 2.7.1.36971303。本文描述静态反编译验证结果；实机音频、焦点和多进程效果尚待验证。

## 补丁 API

`scripts/playback_hooks.py` 暴露 `HOOKS` 和 `patch_playback(root)`。root 是 apktool 解包根目录。返回 `dict[str, str]`，键为相对路径、值为完整补丁后文本，函数不写磁盘。所有目标文件、准确方法声明及必要局部寄存器先验证，随后生成全部修改。重复调用保留现有标记，不重复注入。未知版本或目标缺失应立即停止构建。

依赖运行时方法：

- `GuardRuntime.shouldBlock()Z`：须跨主进程和 `:coreService` 获取同一个保护状态，并在连接状态确认前处理进程重启的自动恢复窗口。
- `GuardRuntime.register(Ljava/lang/Object;)V`：弱引用登记实际播放器。在保护开启后新建的播放器也应被纳入暂停与状态确认。

入口保护使用方法已有的 v0/v1，不扩展寄存器，不改变 p 参数映射。所有普通目标都是 void，阻止时 `return-void`；`x0/m.a(m$o,String)I` 返回 `-1`，与原方法的无效索引返回一致；MediaSession 的受保护媒体键返回 `true`，明确消费事件。目标代码的暂停、停止、音量、音源注册和音频焦点方法保持原实现。

## 已验证的路径

| 路径 | 静态行为与补丁 |
| --- | --- |
| `w0/b$a.a/b(CabinKeyEvent)` | PAL 长按/普通事件。仅 5 播放切换、8 上一首、9 下一首提前返回，其它键进入原实现；避免长按任务与本机切歌 |
| `MediaButtonIntentReceiver.onReceive` | 79 耳机钩子、85 播放切换、87/88 切歌、126 播放被阻止；127 暂停、86 停止及音量键继续通过 |
| `MediaPlaybackService$k.onMediaButtonEvent` | 相同受保护按键在长按状态和 seek 变化前消费；显式暂停继续通过 |
| `MediaPlaybackService$k` | onPlay、按 ID/搜索/URI 播放、下一首/上一首、队列项及内部 a/b 切歌进入前阻止 |
| `play/k.s`、`play/l.w` | 用户/UI 播放和播放代理；在错误重试、重新查询歌单或发送 play 前阻止 |
| `play/k.a(IZ)`、`a(I,String)`、`c(I)` | 回调播放状态、异步播放/切歌和当前歌曲改变前阻止 |
| `x0/h` | `:coreService` 原生 PlayList 的 resetList、指定索引、自动/手动 next/pre |
| `x0/m` | 主进程 PlayListManager 的 next/pre、指定索引、notifyChange 和队列替换；待完成歌曲查询的 onMusicLoadSuccess 以及实际 prepend/append/resetList 入口再检查 |
| `s0/b/f.e(Z)`、`a(Message)`、`C()` | 发起播放、已经排队的 MSG_PLAY 和策略启动分别检查，避免仅阻止请求但迟到任务仍执行 |
| `s0/b/g.i()` | PlayerManager.start 在接口调用前检查 |
| `s0/c/d/b.start()`、`j()` | 实际 Exo 播放与 live window/playlist stuck 重试；`l1.b(true)` 前阻止 |
| `s0/c/f/c.start()`、`c$b.onPrepared` | ThumbPlayer 实际启动及异步 prepare 直接启动两条路径均检查 |

## 暂停实现

`PlayerManager` 构造器可创建 Exo `s0/c/d/b` 或 Thumb `s0/c/f/c`。两者均实现 `s0/c/b`，具有 public `pause()V`、`isPlaying()Z`；构造返回前登记这两种具体对象。Thumb 并非仅视频，构造选择表明其也可能承担音乐播放。

保护先写入共享阻止状态，然后在播放器所属主线程调用 `s0/b/f.E().d(false)`，或桥接 `play/k.y().r()`（其最终调用 pauseBySys → d(false)）。该路径移除排队播放消息，暂停当前策略并同步播放状态；false 避免走用户暂停分支主动释放焦点。随后对已登记对象调用 public pause 并查询 isPlaying；Exo 的操作须在创建线程/主线程运行。

不要调用 stop/release、不停用包、不注销 PAL、不替换全局音量或共享音源。暂停失败应记录为失败，不能仅因阻止开关已经开启就宣称正在播放的音频已经停下。

## 边界与联调

- 保护依赖运行时跨进程状态正确。上述 Java 门禁不等于 iPhone 会话检测，DiPlay 必须在真实连接/断开时更新。
- 已提交给音频内核的瞬时启动与连接操作可能并发，必须通过开启门禁后暂停、状态核验和实机抓日志确认串音窗口。
- Thumb onPrepared 在保护时提前返回，不发送原有“已播放”通知。断开不自动启动；用户再次播放时需验证该版本的重新 prepare 行为。
- 查询回调在保护期间被丢弃，原有网络调用方可能收不到成功完成回调。解除后用户重新操作正常；验证 UI 加载状态能否自动结束。
- 原生 TTS 包 `wecar/tts` 的 Android MediaPlayer.start 未加门禁，因为它不是本机歌曲播放器。动画 start、语音播报和通用 SDK 没有做全局拦截。
- 播放模式、收藏、搜索及普通歌曲信息重载不是播放行为，保持可用；队列变化及“自动播放”回调在播放链上阻止。仪表数据隔离由独立补丁处理。

验证命令：`python3 scripts/tests/test_playback_hooks.py`。验证了准确方法匹配、完整补丁生成、幂等、PAL 选择性、真实播放器登记和不匹配时整体失败。最终 apktool 编译和设备行为由集成阶段验证。

## 会话修订保护补充

运行时新增 `markPlayback(Object)V` 和 `shouldBlockPrepared(Object)Z`。具体 Exo/Thumb 的 setDataSource 记录发起时修订；public start 在通过当前连接门禁后记录新启动修订。Exo 的 private j() 重试和 Thumb 的 onPrepared(owner) 在启动或重新 setDataSource 前检查捕获修订，拒绝连接前、连接中发起而在断开后迟到的旧准备/重试。

修订补丁使用独立 `carplayguard playback revision` 标记，已应用初版门禁的工作目录可以直接升级，不重建、不删除其它补丁。再次应用保持幂等。新增 `x0/m.e(I)`、`p()`、`q()`、`a(String,String)` 和 `c(m$o)` 门禁，覆盖队列分页/索引变化及 callback continuation 对 lower gate 的绕过。

播放器对象修订仍存在边界：旧 onPrepared 到达前同一个播放器若已被新手动启动重新标记，该对象级修订无法区分两次准备任务。需要新的原生播放器任务标識才能彻底区别此并发情况。


## 查询回调来源修订

对以下每次请求创建的对象，在构造返回前捕获会话修订，再在真实成功/重试/播放回调入口检查：

| 对象 | 门禁方法 | 行为 |
| --- | --- | --- |
| x0/m$c,d,e | 类型化 a(Response,I)、b() | 有声书、广播、混合流加载成功改变队列；b() 重新发起同一次查询 |
| x0/m$f,g,h,l | 类型化 a(Response,I)、b() | 歌手、历史、收藏、歌单加载成功改变队列；b() 重试 |
| x0/m$i | a()、a(ServerErrorMessage) | 查询后启动歌曲；错误回调可能触发下一首/重试 |
| x0/m$a | b(I)、onFinish() | 分页完成后执行 e(I)，变化索引 |
| play/l$d,e | run() | 初始化或延迟指定歌曲启动 |
| play/l$h | a()、a(I) | 每次播放前音效操作的完成/失败均重新发起音乐播放 |
| play/l$b$a | run() | 常驻 listener 发出的单次延迟索引任务 |

`x0/m.a(List)V` 的直接 addAll、持久化和通知路径也增加当前连接门禁。上述每次请求的旧成功/重试回调不会在断开后重新标记播放器并自动恢复播放。一般错误回调、永久 listener、焦点与平台激活和缓存清理没有增加来源修订；错误回调只在实际触发播放处理的 m$i 中阻止。

`play/l$b,c` 是长驻监听器，不能在构造时捕获会话修订，否则经过一次连接/断开便会永久丢弃所有以后正常回调。b 产生的每次 Runnable 单独捕获；c 的平台激活 Runnable 不处理歌曲队列。保留 l$a/g/f、l$i、x0/m$b 原行为。

回调 `.locals 0` 只有在原方法没有显式 v 寄存器引用时才允许增加至 1；原 p 参数寄存器保持对应意义。构造捕获使用 invoke-static/range，避免较多参数导致 p0 的物理寄存器超过普通 invoke 编码范围。

验证同时针对未修改 build/baseline 与当前已有初版补丁工作目录，生成结果一致，再次应用不重复，暂停/停止/音量/焦点主体保持逐字一致。未覆盖的原生迟到任务、永久 listener 在断开后才产生的新任务，以及同一 Thumb 对象同时承载旧/新 prepare 的竞态需实机验证。
