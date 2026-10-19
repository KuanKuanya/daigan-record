# 第三阶段独立验收与项目进度

日期：2026-10-18。验收基线：`f485b7e` 加验收开始时的第三阶段未提交工作区；分支 `codex/phase-2-undo-fix`。

**结论：第三阶段退回修改，不进入第四阶段。** 当前属于“第三阶段主要实现已写入，尚未形成可验收交付”，不能称为第三阶段完成或 V1 完成。

## 1. 规划进度

以 `2026-10-17-agent-handoffs.md` 的五阶段为准；原 android-v1 的 Task 1–8 与五阶段不是同一编号体系。

| 阶段 | 实际状态 | 依据 |
|---|---|---|
| 1 官方底座、持久化、账号与导航 | 已验收 | 固定上游来源、v1/v2 Schema、账号与归档实现及 phase-1-fix-1-review |
| 2 双类型订单、游戏日、打卡、补录、附件、续期 | 已验收 | `phase-2-fix-2.md` 最终通过记录；`f485b7e` 保留有效撤销批次的最终修复 |
| 3 收款、欠款、统计、明细 | 实现中，独立验收不通过 | 工作区有 v4、收款/更正 Repository、余额模型、真实统计与明细页面；存在下述合同错误，测试入口编译失败，缺正式交付 |
| 4 模板编辑、游戏内容、导入导出 | 未开始 | 设置仍导向待实现页面，没有模板业务存储/文件合同 |
| 5 备份恢复、提醒、整体加固与最终 APK | 未开始 | 设置入口保留待实现说明，没有备份恢复状态机或提醒实现 |

阶段 3 才是第一个日常核心闭环测试版；阶段 5 才是完整 V1 候选。不得用“五阶段已到第三阶段”推导机械的 60% 完成度。

## 2. 阅读和审查范围

清点项目文件树、Git 状态/提交、构建配置、资源/Manifest、Schema、全部规划和阶段交付文档；重点追踪当前订单 → 收款 → 更正 → 余额 → 统计 → 设置流水的写入和订阅链，并检查账号归档、任务/续期/附件与既有测试的兼容关系。二进制图标、Gradle wrapper、历史截图以及生成目录不作为源码逐行审查对象。第三阶段没有交付截图，因此未把第二阶段截图当作第三阶段视觉证据。

没有修改产品源码，没有提交或重置原工作区。新增独立验收测试 `Phase3AcceptanceReviewTest.kt`、本报告和修复 prompt。为绕过原测试入口遗漏，验收过程中临时适配过两个测试文件的构造参数；验证后恢复原始内容。适配不改变产品逻辑或原测试断言，适配后结果不能冒充原始工作区全部测试通过。

## 3. 已有实现与边界

- Room v4 新增 `receipts`、`receipt_corrections`，生产 DI 注册了前向迁移；v1/v2/v3 历史 schema 未被此实现改写。迁移 SQL 没有为旧单伪造收款。
- 金额为 Long 分；解析拒绝非正实收、过多小数和单笔过大值，汇总使用 checked addition。余额从价格及 ACTIVE 流水推导。
- 收款在事务中校验订单类型、期次归属、余额、ONE_TIME 全额规则；唯一 operationId 索引提供持久化防重复基础。
- 建单首款在订单事务中写入；续期继续使用原订单 ID，产生独立价格的新期。更正保留原流水和审计记录。
- 统计按实际收款时间和初始建单时间筛选；待收列表使用全部订单及全部期次，不排除归档资料；设置有独立收款明细页面。
- 支付与更正有协程启动前的同步防重复门；归档任务保持只读，财务入口明确允许结算历史债务。

这些是代码事实，不代表所有事务/迁移/设备边界均已验证。

## 4. 必须修复的问题

### R1 / P1：两个原测试入口未适配，完整验证无法运行

- `app/src/test/java/com/daigan/record/review/Phase2UndoReviewTest.kt:190` 缺 `receiptRepository`、`timeProvider`，全部 JVM 测试在编译阶段中断。
- `app/src/androidTest/java/com/daigan/record/data/Phase2ManualProbeTest.kt:85` 创建 `DefaultOrderRepository` 时缺 `receiptDao`、`receiptLedger`，全部设备测试在编译阶段中断。
- 此处不是某两个测试失败，而是原始工作区两套测试均无法开始执行。修复必须保留 Phase 2 已验收断言。
- 临时适配后，`NavigationTest.statsScreenShowsRealZeroesInsteadOfInventedNumbers` 还实际失败：测试查找“本月实收”，页面展示“2026 年 10 月实收”。这是测试与页面语义不一致的集成问题，不能把它当作财务计算错误，也不能继续宣称全套 UI 回归通过。
- `OrderDetailScreenTest` 两例还因为新增财务区后出现多个相同文案/金额而失败：`shortDetailShowsTasksPriceAndARealPaymentSection` 查“分批收款”找到 2 个节点；`expiredHostingShowsRenewalAndHistoricalCorrection` 查“¥300.00”找到 2 个节点。应限定被测区域/语义，继续验证原业务行为，不能靠删掉原验收点解决。

### R2 / P1：首款建单不具备命令整体幂等

位置：`OrderRepository.kt:505`、`:554`，`NewOrderViewModel.kt` 的 `initialPaymentOperationId`。

两种建单每次生成新订单 ID，托管还生成新期次 ID，再用原首款 operationId 入账。同一请求成功后重试，会与旧首款的 orderId/periodId 不一致，被判为“该操作标识已被另一笔收款使用”，不能返回原订单。事务可回滚第二份订单，但这只能防止部分落库，不能满足相同命令重试返回原结果。无首款命令则没有建单去重标识。ViewModel 的首款 UUID 也没有写入 SavedStateHandle。

应把整个建单请求/结果作为同一个持久化幂等操作，而不是只给内部收款分配 ID。

### R3 / P2：收款/更正幂等比较与数据库时间精度不一致

位置：`ReceiptLedger.kt:283`、`:297`；`Converters.kt` 的 `InstantConverters`。

数据库把 Instant 截为 epoch 毫秒；`matches` 直接与原始请求 Instant 比较。包含亚毫秒值的同一请求再次提交，读回时间与原请求不同，会误判冲突。当前普通支付弹窗把时间转为毫秒降低了 UI 触发机会，但 Repository 合同仍错误，不能以弹窗实现替代持久化边界保障。

应在请求规范化、写入和比较时使用相同精度，并覆盖更正、数据库重开后的重试。

### R4 / P2：更正操作复用 ID 时忽略审计原因

位置：`ReceiptLedger.kt:291`。

VOID 只比 kind 和 receiptId，AMEND 也忽略 reason。用相同 operationId、同一流水、不同原因重试会返回 alreadyApplied，丢弃新的载荷而不报告冲突。原因是审计合同的一部分，应该规范化后参与一致性校验。

### R5 / P1：建单首款不能记录实际到账日期时间

位置：`OrderDrafts.kt` 的 `InitialPaymentDraft`、`NewOrderScreen.kt` 首款表单、`OrderRepository.kt:631`。

首款只支持金额、渠道、备注，没有实际到账时间，Repository 强制 `receivedAt = now`。例如 10 月建单补记 9 月已经收到的款，必定记入 10 月；事后再进入更正页面不能替代建单时应有的输入。需要添加实际日期时间并区分实际到账时刻与录入时刻。

### R6 / P1：第三阶段专项测试和正式交付缺失

验收开始时没有真实财务合同专项测试、ReportingCalendar/Statistics 专项测试或带 v3 业务数据的 v3→v4 迁移测试。原 v1/v2 起点迁移测试虽然以当前版本为终点，也不能证明已有 v3 订单、期次、打卡、批次和附件升级无损。

没有 `phase-3.md`、第三阶段实现提交、实际完整流程截图、第三阶段重启持久化验证记录；`docs/data-model.md` 仍声明当前 v3、收款未实现，README 仍停留 Phase 1，账号页仍称收款/统计属于后续阶段。

必须补齐原 `phase-3-prompt.md` 的合同矩阵与真实设备流程证据，不能仅交出可构建 UI。

## 5. 需要进一步验证的读取一致性风险

`LedgerSnapshotSource.kt:38` 用多个独立 DAO Flow 的 combine 拼接表状态，没有提供同一次数据库读取事务的快照保证；写事务提交后各查询重发次序可能不同。统计层完整性校验会对“新流水配旧订单/期次列表”抛异常，页面 catch 后该次订阅结束，明细也可能先暂时丢项。

这是静态可见的设计风险，不能冒充已经在设备稳定复现的缺陷。应添加持续订阅场景验证建单首款、续期与更正，必要时改为一致性快照读取。只验证 `first()` 的稳定最终值不足以覆盖该风险。

## 6. 本次验证

所有命令使用 JDK 21 与本机 Android SDK，`--offline`；未改动依赖版本。原始工作区的测试编译失败与临时测试入口适配后的执行结果分开记录。

### 原始工作区

1. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline` 命中旧 kapt Factory 构造缓存；仅 clean 后仍从缓存恢复旧生成代码。此现象不直接作为产品编译缺陷。
2. `./gradlew :app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-build-cache --rerun-tasks`：**BUILD FAILED in 2m 18s**，55 tasks executed；主源码编译成功，最终因 `compileDebugUnitTestKotlin` 的两个缺参错误失败，单元测试没有执行。
3. `./gradlew :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug --offline --no-build-cache --continue`：**BUILD FAILED in 22s**，设备测试编译缺两个参数，未运行设备测试；独立任务 `lintDebug` 与 `assembleDebug` 完成。
4. 新鲜 lint XML：**0 Error/Fatal、2 Warning、2 Hint**。Warning 为 v24/v26 资源目录冗余，Hint 为两个支付日期时间状态可使用专用 long state；离线检查结果不与先前联网依赖版本提示数直接比较。

### 临时仅适配测试构造入口后的独立执行

命令：`./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest --offline --no-build-cache --continue`。

- JVM：XML 14 suites，**73 tests，0 failures/errors/skips**。
- 设备：Medium_Phone_API_36.1，Android 16，sdk_gphone64_arm64，emulator-5554。最终 XML：**123 tests，116 passed，7 failures，0 errors，0 skipped**；完整命令 **BUILD FAILED in 4m 11s**。七个失败为本报告 R2/R3/R4 对应的四个独立财务用例，以及 R1 列出的三个 UI 集成用例。
- 新增独立 Room 验收 5 例：两种首款建单重试、原因冲突和亚毫秒时间重试共 4 例失败；正常分批收款/重复不增款/超收拒绝组合用例 1 例通过。失败保留供修复，不弱化断言。

### 产物与验证限制

- APK：`/Users/francis/Documents/Project/代肝记录软件/app/build/outputs/apk/debug/app-debug.apk`。
- SHA-256：`5b7b3d9e835cdde407c9dadab0430724ae84c1395be288f0a5835d0e259ff361`。
- APK 可构建不代表财务合同或第三阶段验收通过。
- `git diff --check` 通过。两个临时适配文件已与验收前备份逐字节比较并恢复；其中原本已存在的未提交改动继续保留。独立验收测试和文档未提交。
- 未执行第三阶段完整人工付款/续期/结清/更正/归档/重启流程，未补做长文本、大字体、窄屏和深浅主题视觉验收；没有把现有第二阶段截图当作第三阶段证据。

## 7. 下一步

执行 `docs/handoffs/phase-3-fix-1-prompt.md`，完成第三阶段修复及补交，再独立复验。通过后才进入阶段 4 的模板与文件交换；本次不发第四阶段实施令。
