# V1 提醒行为

三个独立开关与时间保存在 Room，初始全部关闭，并随完整备份恢复。时间是手机本地时间；业务是否应提醒使用订单/托管期次保存的游戏时区、每日刷新、独立每周刷新规则。

- 短期截止：游戏日已到或超过截止日且订单未完成，每个手机本地日最多提醒一次；未设期限不提醒。
- 托管到期：仅最新期次的最后一个游戏日提醒，到期后停止；续期后使用新期次快照。
- 未完成：当前仍有效期次中当前发生槽未完成的托管任务，以及未完成短期任务。每周发生槽采用实际刷新窗口，每 N 天锚定期次起始日；历史补录不抵扣今天，撤销完成后恢复资格。归档老板/账号不提醒。

每类别每日最多一次成功投递，Room 保存投递记录。读取最新数据、判定、投递与日志在同一数据库事务中，进程内互斥避免重复；整个操作取得 DatasetGate 共享许可，完整恢复不能中途替换数据。Android 通知与 SQLite 不能跨系统原子提交：极端进程死亡在投递成功到日志提交之间可能再次提交同类别通知，但固定通知 ID 会替换旧通知而不叠加。

调度采用每类别一个 WorkManager 唯一链。日常启动/恢复前台/业务更新 KEEP 已排队任务，防止不断推迟系统延迟的提醒。工作完成时 APPEND_OR_REPLACE 排入下一个本地日，避免 REPLACE 取消运行中的自身。修改设置、系统时钟/时区广播和数据恢复会取消旧链并重建。所有入队及取消操作等待 WorkManager 数据库提交后返回，恢复流程必须完成调度协调才移除恢复日志。重启由 WorkManager 持久调度恢复。延迟执行使用执行当时的游戏日重新判定，已过期托管不会补发旧到期提醒。当天时间已过之后才打开开关或修改时间，会从下一次配置时刻开始；不会立即补发。

Android 13+ 开启开关时请求通知权限。拒绝后设置仍保存，其他业务可继续；页面提供系统通知设置入口，并在回到页面时刷新状态。全局通知关闭或类别通知渠道关闭均不投递。通知不包含账号登录名、密码或验证码；锁屏采用 private 可见性。单订单通知打开订单详情，汇总通知打开今日事项。权限拒绝不写成功投递日志。

WorkManager 不保证精确分钟，也不使用常驻服务、精确闹钟或联网约束。应用启动恢复失败时 Worker 等待就绪屏障；数据库或排队异常让 WorkManager 重试，协程取消不被吞掉。系统强行停止应用后，Android 可能直到再次启动才运行后台工作。

## 验证入口

- JVM: `com.daigan.record.notifications.ReminderRulesTest`：刷新边界、到期包含首尾、归档/完成、补录/撤销、独立每周刷新、自定义周期、DST、独立设置、汇总导航标识。
- 设备: `ReminderPersistenceTest`：真实 Room 设置持久化、最新完成/归档、并发去重、次日提醒、拒绝通知、配置时间前不发送；使用确定时钟和记录调度器，不等待实际期限。
- 设备: `ReminderWorkManagerTest`：真实 WorkManager 重复刷新保持同一唯一任务与关闭取消。
- 设备: `ReminderPermissionTest`：Android 13+ 通过真实系统 POST_NOTIFICATION app-op 禁止后无法投递，并提供正确系统设置目标；测试恢复原 app-op 状态。实际权限对话框拒绝仍需系统 UI 验证（进程内 pm revoke 会杀死测试进程）。

这些是测试入口，不代表已经运行通过；实际运行结果以 Phase 5 交接报告为准。物理重启、OEM 省电延迟、跨真实 DST 运行和通知点击的系统 UI 流程需要设备验证，不能由纯规则测试宣称完成。

官方依据：[唯一工作策略](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)、[自定义 WorkerFactory](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)、[通知运行时权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)。
