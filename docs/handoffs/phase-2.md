# Phase 2 交接报告

日期：2026-10-17 / 2026-10-18
状态：**实现、修复与验证完成，交回独立复验。未自我标记为通过，未进入第三阶段。**
基线：`b109f83`（第一阶段 fix-1 文档提交）。第二阶段实现提交：`886912f6ee07c3368d1be37c52c2f1cc67cdfe99`。

> 本阶段的独立验收意见与两轮复验意见分别见 `docs/handoffs/phase-2-review.md`、
> `docs/handoffs/phase-2-fix-1-review.md`、`docs/handoffs/phase-2-progress-review.md`。
> 这些评审文件由验收方撰写，本次未修改、未删除、未纳入提交。
> 其中“设备测试挂起”的定位与修复单独记录在 `docs/handoffs/phase-2-fix-1.md`。

## 1. 本阶段交付内容

### 1.1 领域模型与持久化

- 订单两类：`HOSTING`（托管，含期次）与 `SHORT_TASK`（短期任务）。
- 托管期次 `HostingPeriod`：序号、起止日期（含首尾）、期次价格、期次刷新规则快照、`renewalOperationId`。
- 任务定义 `TaskDefinition`：勾选/数值两种记录方式、数值目标与单位、重复规则
  （每日 / 每周 / 指定周几 / 每 N 天）。
- 打卡发生记录 `TaskOccurrence`：`occurrenceKey` 决定归属（`D:yyyy-MM-dd` 或 `W:周起始日`），
  另存 `attributedDate`、`recordedAt`、`backfilled`、`value`、`batchId`，唯一索引
  `(taskDefinitionId, occurrenceKey)` 防止重复打卡。
- 批量完成批次 `CompletionBatch` 与批次项 `CompletionBatchItem`：`operationId` 幂等，
  撤销只回滚该批次写入的字段。
- 截图附件 `Attachment`：只归属一个任务或一次打卡（数据库 CHECK + 仓储校验）。
- 归档守卫：未完成的短期任务、未到期的托管期次会阻止归档账号/老板；已被归档的父级
  拒绝任何子级写入（复用第一阶段的归档批次语义）。

### 1.2 时间语义（`core/time` 与 `data/model/OccurrenceRules.kt`）

三个日期概念显式分开，全部由纯函数 `GameCalendar` 计算，可固定时钟测试：

- **本地日历日**：时区内的自然日。
- **游戏日**：以每日刷新时间（默认 04:00）为起点，刷新前仍属前一日。
- **周窗口**：`[每周刷新时刻, +7 天)` 半开区间；一个游戏日可能同时接触关闭窗口与开启窗口
  （周一 04:00–05:00），历史查询会同时列出两者，不用“中午时刻猜测”掩盖歧义。
  周常任务在整个窗口内只有一个 occurrence。

`TimeTicker` 把“时间推进”变成可注入的信号：订阅时先发当前时刻，然后在
`min(下一个日/周边界, 现在 + 1 小时)` 处唤醒重发。今日页、订单详情、历史页共用同一信号，
因此跨日、跨周、到期切换不依赖数据库写入或应用重启。

### 1.3 界面

- `TodayScreen`：`托管打卡` / `短期任务` 两标签；批量完成（整单 / 全部），已完成订单折叠区；
  已到期订单显示“已到期 · 待续期”并只给“查看并续期”，不提供整单完成。
- `OrderDetailScreen`：期次卡片、今日打卡、本期打卡记录、往期记录（含任务快照与历史标记）、
  补录入口、续期入口、截图条（添加/替换/删除，不可解码时显示“无法预览”）。
- `BackfillScreen`（补录/更正）：日期选择范围 = 本期开始日到今天；未来日期在 UI 与仓储事务内
  双重拒绝；补录条目显示归属游戏日期、实际记录时间与“补录”标记。
- `NewOrderScreen`：托管/短期建单、任务编辑、刷新规则、价格与收款方式。
- `AccountsScreen` / `OwnerDetailScreen`：账号下的订单摘要与入口。

### 1.4 关键不变量

| 不变量 | 实现位置 |
|---|---|
| 批量完成只作用于“展示时的那一天/那一周” | `DisplayedHostingSnapshot`（periodId、gameDate、每任务 occurrenceKey）随点击传入，仓储事务内逐项比对，任一不符即整批拒绝且不写任何 occurrence |
| 补录不得写入未来 | `setHistoricalCompletion` 在事务内、创建 occurrence 之前拒绝未来游戏日与未开始期次 |
| 同一操作重试不产生第二份批次 | `operationId` 唯一索引 + 幂等返回 |
| 撤销只还原该批次的字段 | 批次项保存前值，撤销时比对现值，冲突项保留并报告 |
| 截图文件与数据库引用不可分裂 | `AttachmentSync` 单互斥：add/replace 从写最终文件前到提交行、delete 从删行到删文件、cleanup 从读引用快照到删完，全程持锁 |
| 清理不得删除在途或已引用文件 | `cleanupOrphans` 在同一锁内先读引用快照（含空目录场景），再删除未引用文件与过期临时文件 |
| 续期不改写历史 | 新期次从“今天的游戏日”开始，旧期次、价格、任务快照、打卡、截图全部保留；订单标识不变 |

## 2. 数据库与迁移

- 版本：`RECORD_DATABASE_VERSION = 3`；`MIGRATION_1_2`、`MIGRATION_2_3` 注册到生产 builder，
  无 `fallbackToDestructiveMigration`。
- 导出 schema：`app/schemas/com.daigan.record.data.local.RecordDatabase/{1,2,3}.json`，
  同时作为 `MigrationTestHelper` 的历史 fixture（androidTest assets）。
- 文档：`docs/data-model.md` 已更新为“已实现表结构 + 后续阶段关系”，并写明补录、批次、
  归档与截图协议语义。

## 3. 与评审意见的对应

| 评审项 | 处理 |
|---|---|
| R1 周刷新前周常出现空档 | 周常 slot 改由 `weeklyWindow(at)` 决定归属，不再要求 `today` 落在 `startDate..endDate` 标签内；周窗口改为真实时间交集；同日跨两个窗口时历史页显式列出两个。保留验收方新增的 `Phase2CalendarReviewTest`（5 例） |
| R2 可补录未来完成 | 仓储事务内先拒绝，再创建 occurrence；`BackfillScreen` 日期范围限制 + 未开始期次只读 |
| R3 清理可删除在途/刚保存文件 | `AttachmentSync` 共享互斥覆盖“最终文件落地 + 数据库行提交”的完整区间，cleanup 快照与删除同锁；两个交错各有可控并发测试 |
| R4 时间变化不驱动界面刷新 | `TimeTicker` 注入式时间信号驱动今日/详情/历史；动作携带展示身份，失效时拒绝并提示刷新，不静默改到另一天 |
| fix-1 第 1 条（探针构造参数） | 已修正并接入 `ticker`/`sync` |
| fix-1 第 2 条（UI 回调三参数） | 已更新并按“事件携带展示身份”断言 |
| fix-1 第 3 条（缺回归） | 已补：附件并发 2 例、未来补录 3 例、双周窗口历史、调度多订阅者 1 例、TimeTicker 7 例 |
| fix-1 第 4 条（批量快照） | `completeHostingOrder`/`completeAllHosting` 接收展示快照并在事务内校验 |
| fix-1 第 5 条（调度器只唤醒一个） | `ManualBoundaryWaiter` 改为每等待者独立 gate；`advanceTo` 唤醒全部；补多订阅者有界测试 |
| fix-1 第 6 条（文档/提交） | 本文与 `phase-2-fix-1.md`；实现与修复分两次提交 |

## 4. 验证结果（全部在本工作区实际执行）

命令中的 `./gradlew` 均来自仓库自带 wrapper（Gradle 9.2.1，JDK 21.0.4）。

| # | 命令 | 结果 |
|---|---|---|
| 1 | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.data.Phase2ManualProbeTest --console=plain` | BUILD SUCCESSFUL in 51s；`Starting 1 tests` / `Finished 1 tests`（修复前此用例无限挂起） |
| 2 | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.data.repository.HostingOccurrenceTest,com.daigan.record.data.repository.BatchCompletionTest,com.daigan.record.data.repository.AttachmentTest,com.daigan.record.testing.ManualBoundarySchedulerTest --console=plain` | BUILD SUCCESSFUL in 1m 7s；35 tests，0 failed |
| 3 | `./gradlew :app:testDebugUnitTest --console=plain` | BUILD SUCCESSFUL；**67 tests，0 failed，0 skipped**（13 个测试类） |
| 4 | `./gradlew :app:connectedDebugAndroidTest --console=plain` | BUILD SUCCESSFUL in 5m 27s；`Starting 117 tests` / `Finished 117 tests`，**117 tests，0 failed，0 skipped**（20 个设备测试类） |
| 5 | `./gradlew :app:lintDebug :app:assembleDebug --console=plain` | BUILD SUCCESSFUL；lint **0 errors, 28 warnings**（全部为 `libs.versions.toml` 的依赖版本提示，沿用第一阶段固定版本）；`app-debug.apk` 构建成功 |

### 4.1 构建产物

```
app/build/outputs/apk/debug/app-debug.apk            19 MB  (debug)
sha256 65d840aca878aa1d23e81a40f3742d807a7b87a9e7aa74c0c46853fec3683f02
```

设备测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`（1.5 MB）。

## 5. 手动流程验证（模拟器 Medium_Phone_API_36.1 / API 36）

数据由 `Phase2ManualProbeTest.seedExpiredHostingOrderForManualRenewal` 写入**真实应用数据库**
（固定时钟 2026-10-05，模拟一组已过期期次 2026.10.01–2026.10.10，含一条已完成打卡与一张截图），
不向生产代码写入任何演示数据，也不修改设备时钟。

| 步骤 | 观察 | 证据 |
|---|---|---|
| 今日页 | “过期托管探针”显示“已到期 · 待续期”、期次区间与“查看并续期”，无整单完成 | `screenshots/phase-2/01-today-expired.png` |
| 进入详情 | 订单标识、账号、期次价格、旧期历史入口齐全 | `02-detail-expired.png` |
| 本期打卡记录 | 旧期签到：游戏日期 2026.10.05、记录时间 2026.10.05 10:00、截图 1 张且缩略图可渲染 | `03-detail-seeded-record.png` |
| 补录页 | 默认 2026.10.10，提示“只能选择本期开始日到今天（含今天）的游戏日期；未来日期不可补录” | `04-backfill-empty.png` |
| 选择 2026.10.09 并勾选 | 归属“游戏日期 2026.10.09”，实际记录时间 2026.10.18 09:39，出现“补录”标记 | `05-backfill-completed.png` |
| 续期对话框 | “续期 · 原订单第 2 期”，起始为今天游戏日 2026.10.18，30 天，结束 2026.11.16，价格 200.00，明确不创建收款流水 | `06-renewal-dialog.png` |
| 确认续期 | 状态变“托管中”，第 2 期 2026.10.18–2026.11.16，订单标识不变 | `07-renewed-period.png` |
| 往期记录 | “往期 · 第 1 期”标记“历史”，任务快照与价格保留，打卡记录 2 条（含补录条目） | `08-history-preserved.png` |
| 新期打卡 | 在新期完成今日任务并显示今日打卡区 | `09-new-period-checkin.png` |
| 冷启动重启 | 强制停止后重新打开：今日页“已完成 · 1 单”，展开后该单为“今日任务已全部完成” | `10-restart-today.png` |
| 重启后详情 | 第 2 期仍在，本期打卡记录 10.18 一条，往期第 1 期历史仍在 | `11-restart-detail-history.png` |

手动验证使用的设备命令（可复现）：

```
adb shell am instrument -w -e class com.daigan.record.data.Phase2ManualProbeTest \
  com.daigan.record.test/com.daigan.record.HiltTestRunner     # OK (1 test), 0.752s
adb shell am start -n com.daigan.record/.MainActivity
adb exec-out screencap -p > <file>.png
```

## 6. 已知限制

1. **未实现**：收款明细/欠款/统计（第三阶段）、游戏模板与提醒、备份恢复、截图压缩。
2. 刷新规则（04:00 / 周一 05:00）是用户自填的暂定值，界面已标注“用户自填”；官方规则待第四阶段。
3. 每日/每周边界只在应用进程内由 `TimeTicker` 驱动，无常驻后台任务；应用不在前台时
   边界在下次订阅时补算（读取用当前时刻，因此结果正确，只是没有后台通知）。
4. 截图仅复制到私有目录，不压缩、不生成缩略图；损坏文件显示“无法预览”而不是崩溃。
5. 历史页对“一个游戏日跨两个周窗口”的显式选择仅覆盖当期详情，跨期的周常历史仍需人工确认。
6. 设备测试仅在单一 API 36 模拟器（Medium_Phone_API_36.1）上执行，未覆盖真机、深色模式
   与多语言；UI 用例以默认字号 + 双倍字号两档覆盖。
7. lint 仍有 28 条依赖版本提示（未升级 AGP/Compose 等，保持第一阶段锁定的工具链）。

## 7. 提交

- 实现与修复提交：`886912f6ee07c3368d1be37c52c2f1cc67cdfe99`
- 本文与 `phase-2-fix-1.md`、手动证据截图：见随后的一次文档提交。
- 评审文件（`phase-2-review.md`、`phase-2-fix-1-review.md`、`phase-2-progress-review.md`、
  `phase-1*.md` 评审件）保持未跟踪、内容未改动。
