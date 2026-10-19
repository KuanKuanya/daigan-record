# Phase 3 fix 1 独立复验

日期：2026-10-18。被审查提交：`510396a`（实现 `9aa4cf4`）；此前已验收基线 `f485b7e`。

**结论：上次问题已有实质修复，本轮仍退回一项建单提交生命周期问题；暂不进入第四阶段。**

本次未修改产品源码或既有测试，只新增 `Phase3SubmissionReviewTest.kt`、本报告及英文修复 prompt。未提交、重置或覆盖原工作区的独立评审文件。

## 1. 上次验收项核对

| 原问题 | 本轮代码及交付证据 |
|---|---|
| JVM/设备测试构造参数遗漏 | 已正式修复；本次可直接编译两套测试，无需临时适配 |
| 建单首款整体幂等 | v5 新增 `order_creation_operations`；请求指纹和事务结果支持相同命令返回原订单、不同载荷拒绝；无首款也支持 |
| 亚毫秒重试冲突 | `PersistedInstant` 统一毫秒精度；record/amend 规范化后比较；新增数据库重开测试 |
| 更正忽略原因 | 规范化更正请求包含 reason，并校验完整载荷 |
| 首款实际到账时间缺失 | 表单、Draft、命令恢复、事务及指纹已接入实际到账时刻；有九月收款/十月建单测试 |
| 缺专项测试与交付 | 新增 17 项真实 Room 财务合同测试、持续订阅测试、v3/v4→v5 迁移测试、指纹/编码测试；交付报告、文档及 19 张截图已提交 |
| 财务快照多 Flow 拼接 | 统计与 Settings 流水改为事务读取完整台账；有持续订阅完整性测试 |

v1/v2/v3 Schema 与第二阶段已验收基线无差异。数据库升级保持前向迁移，没有清库回退。

上述修复解决了原仓储合同缺陷，但 UI 的成功提交生命周期还有下述遗漏。

## 2. R1 / P1：成功后尚未离开表单，又把重复提交当作新订单

位置：`app/src/main/java/com/daigan/record/feature/orders/NewOrderViewModel.kt:352`–364。

当前成功分支按以下顺序处理：

1. 删除 pending fingerprint 和 pending command。
2. 立即生成新的建单 operationId 和首款 operationId。
3. 将 `saving` 设为 false，重新允许提交。
4. 向无 replay 的 `createdOrderId` SharedFlow 发出跳转事件。

这把“数据库提交成功”直接当作“用户明确开始另一张新订单”。如果页面还未离开，或成功结果发生在导航 collector 暂时不存在的间隙，同一份可见表单仍可再提交；第二次获得新操作 ID，绕过已正确实现的仓储幂等。同一订单意图可以生成两张订单；启用首款时也可以生成第二笔独立首款记录。

新增独立用例：

`Phase3SubmissionReviewTest.repeatedTapBeforeLeavingSuccessfulFormMustNotCreateANewCommand`

用例使用真实 `NewOrderViewModel`、合法带首款的短期表单和记录调用的 Repository，第一次成功后保持表单与未消费跳转状态，再次提交相同表单。实际：**期望 1 个建单身份，得到 2 个**。这是 ViewModel 合同的确定性复现；本次未声称已在物理设备上复现某种点击时序或强制进程死亡。

真实 Repository 仅按 operationId 去重，不会按相同订单内容合并正常新单，因此不能依赖 Repository 修复此 UI 状态错误。成功事件使用无 replay 的 SharedFlow，晚订阅者也不能恢复已丢失的成功结果。

修复要求：成功后的表单进入终态，保留已提交命令和 orderId，直到导航得到确认/表单离开；仅明确的新建流程取得新 ID。成功结果在 collector 重新订阅和适用的状态恢复时可重取，不能以 delay/debounce 或单纯增加 replay 掩盖身份提前轮换。新增提交、失败重试、成功待导航、collector 重连与成功状态恢复测试；原仓储财务测试全部保留。

## 3. 交付描述需要校正

`phase-3-fix-1.md` 第 6 节称订单详情、统计及收款明细均从同一个 `LedgerSnapshotSource` 派生。实际 `OrderRepository.observeOrderDetail` 仍独立 combine owner/tasks/occurrences/attachments/periods/receipts/corrections 的 DAO Flow。持续订阅测试订阅了台账、统计、流水，订单详情只在最后调用 `first()`，没有证明详情的每次连续发射都来自该事务台账快照。

本次未复现详情产生错误余额，不将文档差异冒充已证实的数据错误；但应如实修正文档，必要时补详情持续订阅一致性验证。

完整设备运行中，`NavigationTest.switchingToShortTaskTabShowsItsOwnEmptyState` 一次未找到“还没有短期任务”。该用例在点击后立即断言，页面数据经异步 Flow 加载；尚不能仅凭该次失败判断是页面功能错误还是测试等待不足。单独复跑结果记录在验证节，原完整运行的失败不会因复跑而被抹去。

`phase-3.md` 仍明确披露：未执行有债归档的人工界面流程、未强制提交中进程死亡、未专门检查长自由文本。本轮不能把自动化合同测试或普通重启截图当作上述人工场景全部完成。

本轮抽查交付截图 `05-void-audit-and-recovered-balance.png`、`16-explicit-historical-period-payment.png`、`18-detail-large-font.png`：可见作废/更正后余额、手选历史期次以及放大字体的详情布局。这属于复查实施者截图，不是本轮重新执行人工全流程。

## 4. 本次新鲜验证

使用 JDK 21、本机 Android SDK、已连接模拟器。执行：

```text
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest --offline --no-build-cache --rerun-tasks --continue
```

- JVM XML：17 suites，**82 tests，81 passed，1 failure，0 errors/skips**。失败仅为本次新增成功提交回归；交付前已有 81 项全部通过。
- instrumentation 最终 XML：**144 tests，143 passed，1 failure，0 errors/skips**，Medium_Phone_API_36.1 / Android 16。唯一失败为 `NavigationTest.switchingToShortTaskTabShowsItsOwnEmptyState`；原五项独立财务验收、17 项财务合同、迁移及订阅一致性测试均通过。
- Lint：**0 Error/Fatal，2 Warning，2 Hint**（资源版本目录冗余及 long state 提示）。
- `assembleDebug` 完成；完整组合命令因上述 JVM 与 UI 失败而 **BUILD FAILED in 9m 48s**，98 tasks 全部实际执行。
- `git diff --check` 通过。原有产品与测试文件没有本轮改动。
- 本轮 APK：`/Users/francis/Documents/Project/代肝记录软件/app/build/outputs/apk/debug/app-debug.apk`；SHA-256 `092cfb0dccdb7915b462fd94e22e56adcea713da575558eeaff89671f6f5bcb0`。这是重新构建的 debug 产物，不是本轮已验收通过的交付。

针对空状态断言的单独复跑命令：

```text
./gradlew :app:connectedDebugAndroidTest --offline --no-build-cache -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.ui.NavigationTest#switchingToShortTaskTabShowsItsOwnEmptyState
```

单独复跑：**BUILD SUCCESSFUL in 32s；1 test，0 failures/errors/skips**。因此该 UI 失败本轮未能稳定复现，根因尚未证实；应排查异步就绪等待或跨用例状态，不应直接宣称产品功能错误，也不能把原完整套件改写为 144/144 通过。

完整套件原始 XML 已保留到 `/tmp/phase3-reacceptance-full-device.xml`，组合运行日志 `/tmp/phase3-reacceptance.log`；单独复跑日志 `/tmp/phase3-reacceptance-navigation-retry.log`。这些是本机临时证据路径，关键结果已摘要记录在本报告。

完整测试执行过程中没有临时修改构造入口或绕过测试。最终复验状态以本报告本轮结果为准，不能因旧报告全绿而忽略新增合同失败。

## 5. 下一步

按英文 `docs/handoffs/phase-3-fix-2-prompt.md` 修复本轮剩余问题，保留新独立回归，再交回复验。当前阶段仍为 Phase 3 修复待验收；第四阶段模板工作未获本轮验收放行。
