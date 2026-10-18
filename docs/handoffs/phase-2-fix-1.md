# Phase 2 修复交接 · fix-1

日期：2026-10-18
状态：**修复与验证完成，交回独立复验。未自我标记为通过，未进入第三阶段。**
被修复基线：`b109f83`（第一阶段 fix-1 文档提交；第二阶段实现当时全部未提交）。
修复提交：`886912f6ee07c3368d1be37c52c2f1cc67cdfe99`。

工作区原有的评审文件（`phase-2-review.md`、`phase-2-fix-1-review.md`、
`phase-2-progress-review.md`、`phase-1*.md`）**内容未改动、未删除、未纳入本次提交**。
第一阶段的迁移、归档守卫与其回归用例全部保留。

## 1. 设备测试挂起：确认原因

### 1.1 现场证据（先检查，未盲目重跑）

挂起的那次 `connectedDebugAndroidTest` 声明 117 项测试，只完成了前两个
`PersistenceProbeTest`，第三项 `Phase2ManualProbeTest.seedExpiredHostingOrderForManualRenewal`
只有开始、没有结束。检查时：

| 观察 | 值 |
|---|---|
| 设备端 instrumentation | `dumpsys activity` 中 `Active instrumentation: Instrumentation #0: {com.daigan.record.test/com.daigan.record.HiltTestRunner}`，仍在活动 |
| 设备端 `am instrument` 客户端 | `am instrument -w -r -e additionalTestOutputDir … -e testTimeoutSeconds 31536000 com.daigan.record.test/com.daigan.record.HiltTestRunner` 仍在 |
| 宿主 UTP runner | 存活约 40 分钟（父进程为 Gradle daemon） |
| 目标进程 CPU | `com.daigan.record` 占用 **96.2%**，累计 CPU 时间 **28:33.54** |
| 宿主 Gradle 客户端 | 已不在（只剩 daemon），因此这是一次**无人消费结果**的孤儿设备运行 |

结论：不是“慢”，而是目标进程在单核忙等；测试框架的 `testTimeoutSeconds` 被 AGP 设为
31536000（一年），所以框架本身永远不会中断它。

### 1.2 取消方式（只取消该次调用）

```
adb shell am force-stop com.daigan.record      # 停止该次 instrumentation 与其目标进程
kill -TERM <该次运行的 UTP runner pid>          # 只结束这一个测试运行器
```

随后确认：设备上不再有 `com.daigan.record` 进程，`Active instrumentation` 为空，
宿主只剩空闲的 Gradle daemon。**没有**终止其它 agent、daemon 或构建。

### 1.3 根因（时钟域混用）

```kotlin
// 修复前
class RealBoundaryWaiter : BoundaryWaiter {
    override suspend fun await(now: Instant, target: Instant) {
        val millis = Duration.between(Instant.now(), target).toMillis()   // 忽略 now
        if (millis > 0) delay(millis)
    }
}
```

| 环节 | 行为 |
|---|---|
| 探针时钟 | 固定 `2026-10-05T02:00:00Z`（`TimeProvider { fixed }`） |
| `TimeTicker` | 用该时钟算出目标边界（如 2026-10-05 的次日 04:00） |
| `RealBoundaryWaiter` | 用**真实** `Instant.now()`（当时 2026-10-17）计算等待时长 → 结果为负 |
| 结果 | 等待不 `delay` 直接返回；固定时钟不前进 → 下一轮又发出**同一个** `Instant` |
| `distinctUntilChanged` | 在把值交给下游之前就丢弃重复值，**不会**产生挂起点 |
| `flow` 循环 | 循环体里再没有任何挂起点，`first()` 的取消也无法插入 → 单核忙等，永不结束 |

`isActive` 检查只读取状态，不足以构成取消点；`distinctUntilChanged` 不是取消机制。

### 1.4 修复

`app/src/main/java/com/daigan/record/core/time/TimeTicker.kt`

1. **同一时钟域**：等待时长由调用方传入的 `(now, target)` 计算，禁止读取第二个时钟。
   历史上（或冻结）的注入时钟下，目标相对该时钟是未来，等待会真正挂起。
2. **永不返回而不挂起**：`delay(max(millis, 200ms))`。零长度等待不会挂起，`delay` 也就不构成
   取消点；200ms 下限同时是取消检查节拍（`RealBoundaryWaiter.MIN_SUSPEND_MILLIS`）。
3. **显式取消点**：每轮循环先 `currentCoroutineContext().ensureActive()`；`safeTarget` 保证严格
   晚于 `now`，`nextBoundary` 返回非未来值时退回 `now + MAX_WAIT`。
4. **文档化边界**：`distinctUntilChanged` 只去重，不承担取消语义；`BoundaryWaiter` 的契约明确
   要求两个参数同域。

`app/src/androidTest/.../data/Phase2ManualProbeTest.kt`

- 探针改用 `MutableClock` + `ManualBoundaryWaiter`（确定性等待器），不再把“冻结的历史时钟”
  与“真实世界调度”混在一起；`first()` 取消时停在可取消的 `gate.await()` 上。

`app/src/androidTest/.../testing/OrderTestEnvironment.kt`（测试调度器加固）

- 目标边界已被越过时直接返回并 `yield()`，让 ticker 重算，而不是停在过去的边界上；
- `advanceTo` 只允许前进，并唤醒该目标上的全部等待者；
- `awaitParked` / `awaitParkedAt` 超时时抛出带期望值的 `AssertionError`，而不是裸
  `TimeoutCancellationException`。

## 2. 新增的有界回归测试

`app/src/test/java/com/daigan/record/core/time/TimeTickerTest.kt`（7 例，纯 JVM）：

| 用例 | 证明 |
|---|---|
| `fixedHistoricalClockCannotCreateAHotLoop` | 2026-10-05 冻结时钟 + `RealBoundaryWaiter`：500ms 真实时间内时钟只被读取 **1** 次且订阅仍在挂起。测试用“读取超过 50 次就抛异常”的时钟代替超时，热循环会立刻失败而不是拖死构建 |
| `firstReturnsImmediatelyOnAFixedHistoricalClock` | `first()` 有界返回注入时钟的值，且只读一次时钟 |
| `firstCancelsAParkedTickerAndTheNextCollectionSeesTheNewInstant` | `first()` 取消后不残留挂起订阅；下一次订阅看到新时刻 |
| `collectorCancellationTerminatesPromptly` | `cancelAndJoin()` 在 2s 内完成且等待者被清理 |
| `oneClockAdvanceWakesEverySubscriber` | 一次 `advanceTo` 唤醒**两个**订阅者，两者都收到新时刻与新游戏日 |
| `dailyRefreshIsScheduledFromTheInjectedClock` | 目标就是用注入时钟算出的 04:00 边界；跨过后游戏日推进；远边界按 1 小时上限挂起 |
| `weeklyRefreshBoundaryStillSwitchesTheActiveWindow` | 周一 04:30 仍是旧周 `W:2026-10-12`，05:00 后切到 `W:2026-10-19` |

**判别力验证**：临时把 `RealBoundaryWaiter` 改回 `Instant.now()` 版本，
`fixedHistoricalClockCannotCreateAHotLoop` 立即 FAILED；恢复修复后 7/7 通过。
因此这不是“加超时掩盖”，而是会真实失败的回归。

## 3. 本轮另外修正的 3 个真实缺陷

复验过程中，关键回归首次运行有 2 例失败、UI 套件有 2 例失败。逐个定位后：

1. **`HostingOccurrenceTest.completeAllHostingIsRejectedAfterTheWeeklyWindowCrossedOnTheSameGameDay`**
   注释说“周一 05:00 之前”，却把时钟设为 `2026-10-19T02:00Z`（上海 10:00，周刷新早已发生），
   于是正确地拿到 `W:2026-10-19` 而断言 `W:2026-10-12`。**是测试时间写错，不是产品错**：
   改为 `2026-10-18T20:30Z`（周一 04:30）与 `2026-10-18T21:00Z`（周一 05:00），断言不变。

2. **`AttachmentTest.concurrentCleanupTakesItsSnapshotAfterAnInFlightPublicationCommits` 超时**
   `cleanupOrphans` 先判断附件目录是否存在，目录不存在就直接返回，导致测试用的
   “快照后屏障”永远不会触发。修复在 `DefaultAttachmentRepository.cleanupOrphans`：
   引用快照与钩子移到目录检查之前，仍在同一把锁内。这既让并发协议在所有路径可观测，
   也保持了原语义（快照与删除同锁，发布要么已提交、要么在等锁）。

3. **两个 UI 用例找不到可点击的“每日签到”**
   - `OrderDetailScreenTest` 用的是**已过期**期次：历史区按设计只读，没有可切换的当期待办。
     新增 `activeHostingDetailForUi()` fixture（同为 `task-hosting` / `每日签到`，期次未过期），
     用例改用活动期次，断言不变（`Triple("task-hosting", "D:2026-10-17", true)`）。
   - `TodayScreenTest` 的同一用例要求点击任务名称触发勾选，而 `CompletionRow` 只有 Checkbox
     带点击。按 Material 标准做法把**整行**改为 `Modifier.toggleable(... role = Checkbox)`，
     `Checkbox(onCheckedChange = null)` 只显示状态：点击区域达到 48dp 最小目标，且不会双触发。
     `onCheckedChange` 回调与携带的 occurrence 身份完全不变。

4. 探针原先写入 4 个任意字节的“截图”，界面只能显示“无法预览”。新增
   `writeTestJpeg()` 生成真实 JPEG，使手动验证能证明截图从保存到渲染的完整链路。

## 4. 精确命令与结果（本工作区实际执行，按顺序）

| # | 命令 | 结果 |
|---|---|---|
| 1 | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.data.Phase2ManualProbeTest --console=plain` | **BUILD SUCCESSFUL in 51s**；`Starting 1 tests` / `Finished 1 tests`。此前该用例无限挂起 |
| 2 | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.data.repository.HostingOccurrenceTest,com.daigan.record.data.repository.BatchCompletionTest,com.daigan.record.data.repository.AttachmentTest,com.daigan.record.testing.ManualBoundarySchedulerTest --console=plain` | **BUILD SUCCESSFUL in 1m 7s**；`Starting 35 tests` / `Finished 35 tests`，0 failed |
| 3 | `./gradlew :app:testDebugUnitTest --console=plain` | **BUILD SUCCESSFUL**；**67 tests / 0 failed / 0 skipped**（13 个类；= 原有 60 + 新增 7） |
| 4 | `./gradlew :app:connectedDebugAndroidTest --console=plain` | **BUILD SUCCESSFUL in 5m 27s**；`Starting 117 tests` / `Finished 117 tests`，**0 failed / 0 skipped**（20 个设备测试类） |
| 5 | `./gradlew :app:lintDebug :app:assembleDebug --console=plain` | **BUILD SUCCESSFUL**；lint **0 errors, 28 warnings**（全部是 `libs.versions.toml` 依赖版本提示）；`app-debug.apk` 成功 |

补充：`adb shell am instrument -w -e class com.daigan.record.data.Phase2ManualProbeTest
com.daigan.record.test/com.daigan.record.HiltTestRunner` → `OK (1 test)`，`Time: 0.752`。

构建产物：`app/build/outputs/apk/debug/app-debug.apk`（19 MB，
sha256 `65d840aca878aa1d23e81a40f3742d807a7b87a9e7aa74c0c46853fec3683f02`）。

## 5. 手动流程验证

完整步骤、截图与观察见 `docs/handoffs/phase-2.md` 第 5 节，证据目录
`docs/handoffs/screenshots/phase-2/`（11 张，覆盖：过期展示 → 详情与截图渲染 →
补录 2026.10.09（归属日 ≠ 实际记录时间）→ 续期第 2 期 → 往期历史保留 →
新期打卡 → 冷启动重启后数据仍在）。

## 6. 保留项与限制

保留（未改动）：

- `MIGRATION_1_2`、`MIGRATION_2_3`、`app/schemas/*.json`（1/2/3）与迁移测试；
- 第一阶段归档批次语义与 `ArchiveOwnershipRegressionTest` 全部用例；
- 验收方新增的 `Phase2CalendarReviewTest`（5 例）及其断言强度；
- 工作区其它未提交文件与评审文档。

限制：

1. 设备验证只在 `Medium_Phone_API_36.1(AVD) - 16` 上完成，未覆盖真机。
2. `TimeTicker` 的 1 小时上限意味着远于 1 小时的边界会分多次唤醒；这是有意的上界保护，
   不是遗漏，测试已按该语义断言。
3. 200ms 最小挂起会让边界最多晚 200ms 触发；刷新边界是分钟级语义，界面无可感知差异。
4. 本轮未新增第三阶段能力；收款、统计、备份仍为空状态。

## 7. 提交

- 实现与修复：`886912f6ee07c3368d1be37c52c2f1cc67cdfe99`
- 本文、`phase-2.md` 与截图：随后的文档提交。

未自我批准第二阶段，未开始第三阶段，等待独立复验。
