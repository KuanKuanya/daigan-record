# 第二阶段验收：需修复后复验

日期：2026-10-17。基线 HEAD b109f83，第二阶段实现尚未提交。验收方未修改产品源码，新增 Phase2CalendarReviewTest.kt 回归用例。

## 阻塞项

### R1 周刷新前出现周常空档

OccurrenceRules.slotForNow 使用真实 weeklyWindow，但 weeklySlot 又要求 today 落在 startDate..endDate（结束标签为起始日+6）。每日 04:00、每周一 05:00 的上海时区配置，在 2026-10-19 周一 04:30，游戏日已经周一，仍有效的旧周窗口却标为 10-12..10-18，任务被返回 NULL。应该在 05:00 前继续显示旧周任务，05:00 后切换新周。

已新增 JVM 回归用例：com.daigan.record.review.Phase2CalendarReviewTest.weeklyTaskRemainsActionableBetweenDailyAndWeeklyRefresh。历史查询用代表时刻猜周窗口，也会在一个游戏日跨两个周窗口时无法精确查旧窗口；需使用明确 occurrence/window 身份选择，不能仅用 midday 掩盖。

### R2 可以补录未来完成

BackfillViewModel.selectDate 仅限制在托管起止日，BackfillScreen 不限制未来选择；setHistoricalCompletion 计算 today 后没有拒绝 date>today，直接 ensureOccurrence 并完成。这允许未开始的未来托管和未来日常被预先打卡。

修复要求：在 Repository 事务内拒绝未来游戏日/未开始周期，且在创建发生记录之前拒绝。UI 也限制选择范围，对尚未开始期次显示只读状态。过去和当前的合法更正保留。

### R3 截图清理可删除正在保存或刚保存的文件

AttachmentRepository.add/replace 先 commit 最终文件后再提交引用；cleanupOrphans 先读取引用快照再遍历删除所有未列入快照的最终文件，二者无共享互斥或在途保护。RecordApplication 启动后异步执行 cleanupOrphans，可与用户保存并发。

交错一：新最终文件落地→清理读无引用→删除文件→保存引用成功，产生缺图记录。交错二：清理读取旧快照→新附件完全保存→清理仍用旧快照删除新文件。应建立共享的保存/替换/清理协议，覆盖完整文件及数据库切换，不仅重查一次或添加延时。添加可控并发测试证明两种交错安全。

### R4 时间变化不驱动界面刷新

observeTodayHosting/observeTodayShort 在创建 Flow 时捕获 now；数据库变化也继续使用旧时间。TodayScreen 只在 RESUMED 时 refresh，一直停留前台跨刷新时间不会重算；OrderDetail 也只靠数据库事件，返回应用时无独立刷新机制。随后点击写操作使用新时间，可能把用户以为属于旧日的动作写入新日。

应以可测试时间信号/刷新边界驱动读取，详情、今日、历史保持一致，前台跨日/跨周/到期切换不依赖数据库写入或重启；动作携带展示的 occurrence/window 身份，失效时刷新并提示，不静默改到另一天。

## 交付缺口

第二阶段没有 docs/handoffs/phase-2.md，所有改动未提交。修复需基于当前工作区继续，不能重建或回滚第一阶段。补完整的交接报告、迁移说明、真实手动流程验证和提交。

## 验证边界

本次执行单元测试与 assembleDebug 命令成功（Gradle 复用既有结果）。已有报告显示 54 单元测试、106 模拟器测试通过；不能把这些既有结果说成覆盖了本次发现的问题。本次未重跑完整设备测试。

新增周边界用例实际执行：1 test、1 failed，失败为 active weekly occurrence must remain visible until 05:00，直接复现 R1。该用例保留给实施者修复，不能删除或弱化。

本次 Lint 报 lintAnalyzeDebugUnitTest FAILED 后未正常结束，已取消本次检查；不能沿用旧报告声称最新 Lint 通过。修复阶段需单独重跑并给出具体分析失败原因与结果。
