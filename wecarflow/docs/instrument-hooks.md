# WeCarFlow 原生仪表适配记录

基线：`backup/WeCarFlow.apk`，2.7.1.36971303 / 22101710。保留原生 `com.tencent.wecarflow.launcherwidget.UpdateWidgetService`，不迁移 QQ 替代服务，不迁入旧仪表探针。

## 代码和补丁接口

- `patch-src/com/tencent/wecarflow/carplayguard/InstrumentBridge.java`：维护外部歌曲快照、订阅、控制转发、发布任务和本机恢复查询。
- `scripts/instrument_hooks.py`：`patch_instrument(root) -> dict[str, str]` 返回两个相对路径的修改内容，不写磁盘。校验原生方法、字段、分派结构以及恰好 8 个 Messenger 发送点。允许完整补丁重复运行，拒绝半完成补丁及协议漂移。
- 原生 `onCreate` / `onDestroy` 加生命周期回调，原生 `$y.handleMessage` 在进入用户协议延期分支前调用 `handle`。3001 连接和 3002 删除继续执行原生逻辑；播放保护期间的媒体控制、1006/1007 查询和1019/1020查询由桥消费；搜索、收藏、登录等保持原生处理。
- 将 8 处原生 `Messenger.send` 替换为 `sendNative`，保持寄存器布局和原有异常处理。保护时屏蔽本机元数据、播放事件、进度、队列边界、歌曲播放状态与歌词；2010 内嵌元数据替换为外部快照，保留导航字段；其它消息原样发送。

运行时 API：`onCreate(Context)`、`onDestroy(Context)`、`handle(Message): boolean`、`sendNative(Messenger,Message)`。保护运行时使用带会话版本的 `beginRemote(Context,long)`、`updateRemote(Context,String,String,String,long,long,boolean,long): boolean`、`restoreLocal(long)`；无版本的便捷重载保留。运行时提供 `isGuarded()`、`getRevision()`、`forwardMediaCommand(int): boolean`。

## 已从原生 smali 验证的协议

| 请求或消息 | 原生路径 | payload |
| --- | --- | --- |
| 3001 / 3002 | `$y` → synthetic c/d → private a/c(Messenger) | 连接列表增加 / 删除 |
| 1006 → 2007 | `$y` → synthetic a → private o(Messenger) | `media_playing` Boolean |
| 1007 → 2008 | `$y` → synthetic b → private f(Messenger) | 当前歌曲 Bundle |
| 原生进度 2006 | private a(JJ) | `media_progress_current` Long、`media_progress_total` Long、`media_type` String |
| 1019 → 2016 | `$y` packed-switch 0x3f9 → synthetic f → private k(Messenger) | `is_first` Boolean |
| 2017 | private l(Messenger) | `is_last` Boolean |
| 2012 | private h(Messenger) | `media_index` Integer |
| 2013 | private h(Message) | 当前本机列表 JSON |

注意：原生 `$y` 中没有 1020 的分派分支，但有对应 2017 `is_last` 发送方法。适配层只在外部会话期间增加 1020 → 2017 false，非保护期间不改原生行为。2016/2017 与 2012/2013 不得混淆。

歌曲 Bundle 字段：`media_type`、`media_name`、`media_author`、`media_group_name`、`media_image`、`media_uuid` 为 String；`media_duration`、`media_duration_current` 为 Long；`media_index`、`media_playmode`、`key_play_mode` 为 Integer；空歌曲、收藏、模式切换、播放变更和队列边界标志为 Boolean。这里不使用 `MediaInfo` Parcelable。

3001 连接接收元数据、Boolean 播放状态和进度；1007 单独订阅只接收元数据；1006 单独订阅只接收播放状态。相同 Binder 的重复或组合订阅合并，每次快照每种消息只发一次。歌曲字段未变时不重复发布元数据，播放状态未变时不重复发布 Boolean 状态；最新位置仍更新并发布给连接客户端。死亡链接和发送失败均移除订阅。

适配层按保护 Binder 的 NowPlaying 约定接收毫秒。原生 `BaseMediaBean.itemDuration` 是 Long，`BeanUtils` 从 `BaseSongItemBean.song_play_time` 原样赋值；原生仪表歌曲时长为空时回退到 `play/k.d()` → `play/l.f()` → `s0/b/f.e()` → 播放策略 `c()` 的 getDuration。当前位置经 `play/k.g()` → `play/l.i()` → `s0/b/f.c()` → 策略 `b()`。原生 `ui/search/b/a$d` 和 `ui/k/a/f` 将 `song_play_time` 直接交给 `utils/j0.a(I)`，其按 `/0x3c` 格式化秒，没有除以 1000；因此原生元数据 `media_duration` 为秒。适配层将输入毫秒除以 1000 后写入这个 Long 字段；`media_duration_current`、`media_progress_current` 和 `media_progress_total` 保持毫秒。播放时基于 `SystemClock.elapsedRealtime` 推进，时长已知时限制在时长以内；暂停时冻结。开始会话先发布空歌曲和未播放状态。同步快照及主线程延迟任务均核对源会话版本；每次快照递增任务序列，过期发布和旧进度 tick 不执行。连续更新在主线程发布前合并时保留元数据和状态变更标记，避免后到的纯进度更新吞掉歌曲变更。

## 控制和断开恢复

1001 NEXT → 4，1002 PREVIOUS → 5，1003 PLAY → 1，1004 PAUSE / 1005 STOP → 2。转发后不自行更改播放状态，等待 iPhone 的真实 NowPlaying。命令进入转发队列时，按原生方法返回 Integer 0：4016 `next_result`、4015 `pre_result`、4018 `play_result`、4019 `pause_result`。这个结果表示接受请求，不表示 iPhone 已执行；STOP 和原生一致不发送结果。未验证“外部控制失败”对应的原生结果码，拒绝时不猜测失败码，也不发送成功。

断开后在主线程通过原生服务对象反射调用精确的 `private f(Messenger)` 查询当前本机歌曲，以及 `private o(Messenger)` 查询本机真实播放状态。按记录的订阅类型查询，不调用播放、恢复或切歌。生命周期销毁时解除死亡链接、清理客户端并作废旧任务；新连接仍由原生服务管理。反射失败记录日志，不用空白外部信息覆盖本机数据。

## 验证和限制

运行 `python3 -m unittest discover -s tests -p 'test_instrument*.py' -v`。

3 项测试执行通过：补丁结构及幂等、协议漂移和半补丁拒绝、真实 InstrumentBridge Java 的可控 Android stub 行为测试。后者覆盖数据类型、客户端分组、进度推算/冻结/封顶、外部媒体命令、原生无关请求、原生迟到元数据屏蔽、会话和任务版本隔离、原生查询恢复、Binder 死亡和服务销毁。

JVM stub 验证不代替车机仪表联调。具体桌面对 2008 查询结果和 2003/2005 事件的 UI 刷新差异、1006/1007 请求被当作订阅的实际兼容性、iPhone 字段，应由实机验证。当前未生成外部歌词，也不向本机队列注入 CarPlay 曲目。
