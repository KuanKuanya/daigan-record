# 第四阶段独立验收报告

后续修复状态：三个问题已修复，133 项单元测试和 177 项设备测试通过，见 [修复交接](phase-4-fix-1.md)。以下保留修复前验收证据。

日期：2026-10-18。验收 HEAD：`a90eedd`；主要实现：`edc33ae`。

**结论：退回修复，暂不进入第五阶段。** 模板库、编辑、默认模板、文件交换及数据库迁移已实现，但发现三项可复现的业务缺陷。原有测试通过并不足以覆盖以下边界。本次仅新增独立回归测试及验收文档，没有修改产品代码。

## 已核对的交付

核对第四阶段交接、方案、数据库与迁移、模板仓库、文件编解码、建单及待提交恢复、模板页面和现有测试。模板采用持久化数据；内置种子有永久标记；导入支持预览、替换、另存为和取消；历史订单采用任务与来源文字快照。历史 schema 1–5 在第四阶段差异中未被改写。

抽查已有导入预览与旧订单不变的截图，未发现明显布局问题。此次没有重新操作完整的系统文件选择器流程，因此截图只作为既有交接证据，不算本轮手工端到端复测。

## 必须修复

### R1 / P1：旧版待提交订单无法恢复

位置：`app/src/main/java/com/daigan/record/feature/orders/PendingOrderCommandCodec.kt:126`、`:166`。

第四阶段新增模板来源字段，但解码所谓可选字段仍调用要求 key 存在的 `text()`。第三阶段有效的 v1 待提交数据没有这些字段，因而整个解码返回 null。升级后的待提交表单及其原命令身份不能按原机制恢复，破坏第三阶段已验收的恢复与重试能力。

独立用例 `Phase4PendingCompatibilityReviewTest` 使用有效表单编码并移除第四阶段新增的三个来源字段，模拟旧版真实格式；断言解码成功失败。要求缺失的新可选字段按旧版语义处理，同时继续严格校验必填字段。保留原创建/收款 operation ID、时间、任务及金额，不能用重新生成命令掩盖恢复失败。

### R2 / P1：短期订单没有保存模板刷新规则

位置：`app/src/main/java/com/daigan/record/feature/orders/NewOrderViewModel.kt:635`、`app/src/main/java/com/daigan/record/data/model/OrderDrafts.kt:65`、`app/src/main/java/com/daigan/record/data/repository/OrderRepository.kt:553`。

建单界面套用了模板刷新规则，但短期订单草稿没有携带日历；仓库创建时重新使用设备时区与临时 04:00 规则。用户看到的模板默认值与保存结果不一致。按现有游戏日计算机制，这也可能造成截止日附近的今日归属和逾期判断错误。

独立设备用例 `Phase4ShortCalendarReviewTest` 通过真实 ViewModel 和 Room 创建使用 05:00 模板的短期订单，验证界面值与持久化快照一致。设备测试已实际失败：预期 `05:00`，实际 `04:00`。后续游戏日与逾期断言因前述失败未执行；逾期影响为基于现有计算路径的分析。修复应保存建单时的规则快照，覆盖时区和刷新边界；重试不能重新读取可变模板。必须兼容旧待提交命令、已有创建操作及其指纹，不能重写历史订单。

### R3 / P2：较早的模板读取覆盖较新的用户选择

位置：`app/src/main/java/com/daigan/record/feature/orders/NewOrderViewModel.kt:264`、`:307`、`:321`、`:333`。

异步读取没有请求代次或失效检查。先选 A、再选 B，若 A 最后返回，会把 B 覆盖；读取 A 期间清除模板并编辑任务，A 返回后仍会重新套用模板。自动切换订单类型的重新套用路径也只在读取前判断任务是否编辑，返回后没有重新检查。

`Phase4TemplateSelectionReviewTest` 的两个确定性用例分别控制读取完成顺序，均失败：预期 B 实际 A；预期无模板实际 A。修复须保证最新意图生效，清除、切换类型、提交及相关编辑使过期结果失效，异步完成时重新核验当前表单状态。不能仅靠延时或禁用按钮绕过。

## 验证证据

- 原有 JVM 套件：22 个套件、126 项，通过。
- 原有设备套件：175 项，通过，0 失败、0 错误、0 跳过。
- 新增独立 JVM 回归：3 项，3 项失败，与 R1 和 R3 对应；已保留测试源码。
- 新增独立设备回归：1 项，1 项失败；模拟器 Medium_Phone_API_36.1 / Android 16，实际快照 04:00 与期望 05:00 不符。
- Debug 构建通过；Lint 报告为 0 Error、2 Warning、2 Hint。
- 综合命令成功，耗时 6m28s，98 个任务中 5 个执行、93 个 up-to-date；不是全量清缓存重建。JVM 使用 `--rerun`，设备套件实际执行完 175 项。
- APK SHA-256：`d8f0d7e0e6f372c5c6ce44afbf13a24e162d59e853cc261c02685ded2fcfdb83`。

本轮临时日志：`/tmp/phase4-acceptance.log`、`/tmp/phase4-independent-unit-all.log`、`/tmp/phase4-independent-room.log`。原完整设备结果另存 `/tmp/phase4-original-device.xml`，避免定向测试覆盖报告后混淆计数。

原套件命令：

```sh
./gradlew :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest --offline --no-build-cache --continue
```

独立回归命令：

```sh
./gradlew :app:testDebugUnitTest --rerun --tests 'com.daigan.record.review.Phase4*' --offline --no-build-cache
./gradlew :app:connectedDebugAndroidTest --offline --no-build-cache -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.review.Phase4ShortCalendarReviewTest
```

## 文档与来源补充

内置游戏模板的“已确认”措辞需与直接证据一致。维护开始时间不能单独证明日常刷新时间；本轮访问官方首页只能确认页面提供的版本信息，不能据此确认全部任务和刷新规则。无法直接核实的默认值应明确标为可编辑、待核实，不应编造相反规则作为修复依据。参考：https://re.bluepoch.com/ 。

## 下一步

执行同目录 `phase-4-fix-1-prompt.md`，先修复以上三项并保留独立回归，再重跑完整检查并更新交接。第四阶段复验通过后，才按既定方案推进第五阶段；此次不提前实施备份恢复、提醒或云服务。
