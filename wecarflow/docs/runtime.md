# 播放保护运行时

Binder descriptor 保持 `com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1`。事务 1 接收回调 Binder、返回 int boolean；事务 2 接收回调 Binder；事务 3 查询 ready；事务 4 接收回调 Binder、title/artist/album、duration/elapsed long、playing int boolean。回调事务 1 接收 int 媒体命令。没有新增调用方签名白名单。

门禁在暂停之前开启。ready 只有已登记且仍运行的播放器进程暂停检查成功才置 true；中央桥接不创建原播放器用于暂停；暂停失败事务 1 返回 false，门禁保持关闭，下一次 acquire 重试。每次 acquire 都更换 revision，旧暂停完成、旧死亡通知不会清理新会话。release 仅清除当前 callback；v1 同一个 Binder 内没有 generation，因此同一个 Binder 对应的过期 release 无法从有效 release 中区分，客户端仍须串行化实际连接/断开。

暂停先通过已核对的中央管理器 `s0.b.f` 私有 `n:Handler` 调用 `removeMessages(2)` 清除 MSG_PLAY2，再调用 `E().d(false)` 暂停。`d(false)` 本身不会清除 MSG_PLAY2；`d(true)` 可能释放共享音频焦点，故不能使用。随后对已注册 Exo/Thumb 对象执行暂停并复核 `isPlaying()`。Exo 无条件调用 pause，确保 buffering 时也清除 playWhenReady；Thumb 已播放时调用 pause，未开始状态由启动门禁阻止。等待主线程最多 5 秒，超时移除未执行任务，任务执行前检查同一 revision。生命周期锁不会跨越主线程等待、Provider call、界面桥或媒体回调。

`GuardStateProvider` 在 Application 之前提供进程 Context。独立 `:carplayguard` 保存内存会话（源码字段 `centralProcess`）；原主进程及 `:coreService` 通过私有 Provider 同步读取门禁并登记已运行 peer Binder，查询异常时拒绝播放。中央桥接仅初始化方控与保护，跳过原 FlowApplication 业务。`getRevision()` / `isRevisionCurrent(long)` 供仪表桥作异步任务过期检查。服务调用仪表桥显式 revision 重载：`beginRemote(Context,long)`、`updateRemote(Context,...,boolean,long)`、`restoreLocal(long)`，传入原始 acquire ticket 或清理后 revision。所有仪表 begin/update/restore 调用检查执行时的门禁及 revision，restore 不能覆盖后来 acquire 的远程显示。

## 中央桥接进程重启的限制

Binder v1 没有持久可验证的连接标识。中央桥接进程死亡会丢失 callback Binder 和会话；原播放器主进程退出本身不会清空中央会话；在不修改 DiPlay 的条件下，磁盘布尔值无法区分仍连接的 CarPlay 与已经断开或死亡的旧客户端。故没有写入可能长期锁住本机播放的持久门禁。已有 DiPlay 必须在保护服务断开后重新绑定并 acquire；重绑前的自动播放窗口仍需实机验证，不能宣称无缝重启保护。完全消除该窗口需 DiPlay 提供可同步验证的当前会话，或在包启动恢复播放前完成保护服务重绑定握手。

纯 Java 测试：编译 `GuardSession.java` 和 `tests/runtime/GuardSessionTest.java` 后执行 `com.tencent.wecarflow.carplayguard.GuardSessionTest`，覆盖暂停失败、重试、迟到成功、旧 Binder release、旧死亡和有效断开。

`GuardPauseTest` 在纯 JVM 中执行真实暂停适配器，确认排队 MSG_PLAY2 在中央暂停之前移除，其它消息保留且从未使用 byUser=true，覆盖断开后旧排队播放仍执行的回归。

## 异步准备回调

`markPlayback(Object)` 在新 setDataSource 或通过门禁的主动 start 入口记录弱引用播放器与当前会话 revision，不在 pause 时更新。`shouldBlockPrepared(Object)` 只允许门禁未启用、已有标记且原始 revision 等于当前 revision 的回调；没有标记或 Provider 查询失败时拒绝。中央桥接读取同一 SESSION 快照，peer 从中央 Provider 读取 revision。acquire 与 release/death 都递增 revision，因此连接前已排队的准备回调即便在断开后迟到，也不能自行恢复播放；用户在断开后重新主动播放会创建当前 revision 标记。

`PlaybackEpochsTest` 执行真实弱标记状态，覆盖无标记、连接期间、断开后的旧准备、主动重播、死亡清理和查询失败撤销标记。


跨中央进程重启时，内存 revision 的编号可能重新开始；完整的 process epoch 协议尚未实现。
旧 prepared 回调跨重启是否可能与新编号相撞，仍需单独验证，不把单进程 revision 检查当成跨重启证明。
