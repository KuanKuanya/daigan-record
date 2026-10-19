# 第一阶段修复独立复验

日期：2026-10-17。
结论：第一阶段修复验收通过，可以进入第二阶段。本次未发现需再次退回的阻塞项。

审查提交：b109f83；修复代码提交：d75fad55b0fd9a7bbf4fdaca78624c0f68a4084e。

## 代码复核

- R1：归档来源使用持久化 UUID 批次，老板恢复只恢复相同批次的账号；独立账号不加入批次。重复操作有事务与状态守卫，不覆盖原批次。
- R2：账号新增、编辑、归档、恢复均在事务内检查老板在用状态；归档账号编辑同样被拒绝。详情不再显示归档老板下的账号恢复按钮，老板恢复入口保留。
- 归档数量与新建页面标题已修正；原有错误 UI 断言已替换为禁止子账号恢复的断言。
- v1 schema 与 b1716cf 字节完全一致，v2 新增可空批次字段；生产 builder 注册 MIGRATION_1_2。独立使用导出 v1 DDL 和相同 ALTER 语句验证原行、编号及凭据保留。
- 新增真实 Room 的同时间归档、重复操作、多轮恢复、父级约束和迁移用例；未见削弱原约束或扩展第二阶段功能。

## 本次验证

执行（显式使用 JDK 21 与本机 SDK）：

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest --rerun-tasks --console=plain`

单元测试报告：31 tests、0 failures、0 errors、0 skipped。

模拟器测试：50 tests、0 failures、0 errors、0 skipped，Android 16 / API 36.1。上述完整命令 BUILD SUCCESSFUL，89 项任务重新执行，未使用测试结果缓存。

独立 Lint 命令：`./gradlew :app:lintDebug --console=plain`，BUILD SUCCESSFUL；0 Error、0 Fatal、28 Warning（固定依赖版本及既有资源提示）。

## 兼容性说明

旧 v1 账号没有可靠归档来源，升级不猜测。恢复旧版老板后，账号保持归档并可逐个恢复。此策略保留全部资料，符合本次修复要求。

设备覆盖为 Android 16 / API 36.1 模拟器，不代表已验证全部最低支持版本或物理设备。

本次仅新增本验收记录，未修改产品源码。原有 phase-1-review.md 保留。后续第二阶段应以 b109f83 及其 v2 Schema 为基线。
