# 代肝记录

面向个人游戏代肝从业者的离线 Android 应用。当前交付到 **Phase 4（游戏模板与模板文件交换）**：
在 Phase 1–3 的老板/账号、短期与托管订单、打卡补录、实收/欠款、更正审计与统计之上，
增加了可持久化编辑的游戏模板库、按模板建单的默认值，以及通过系统文档选择器完成的
UTF-8 JSON 模板导入/导出。

- 应用名：`代肝记录`
- applicationId / namespace：`com.daigan.record`
- minSdk 26，compileSdk / targetSdk 36
- 全离线：Manifest 未声明 `INTERNET` 权限，Android 自动云备份与设备迁移均已关闭
- 中文界面，跟随系统深浅色
- 数据库版本 6（v1–v3 为只读历史 schema，v4/v5 为 Phase 3 历史版本，v6 为当前版本）

## 当前阶段能力

| 能力 | 状态 |
|---|---|
| 账号信息：老板与游戏账号增改、凭据查看复制、归档恢复 | 已实现，Room 持久化 |
| 今日事项：托管打卡、短期任务、批量完成与撤销、历史补录 | 已实现（Phase 2） |
| 订单：短期单 / 托管单、任务快照、托管续期、截图引用 | 已实现（Phase 2） |
| 建单首笔实收（可选）与整单幂等 | 已实现；可填实际到账日期时间 |
| 收款与欠款：部分收款、一次结清、期次归属、补录/更正、审计 | 已实现 |
| 数据统计：本月/全部实收、全部待收、待收明细、报表时区 | 已实现 |
| 设置 → 收款明细：有效流水、替换/作废与更正记录 | 已实现 |
| 设置 → 游戏模板：列表、分类/任务编辑、默认值 | 已实现（Phase 4，Room 持久化） |
| 模板文件：系统文档选择器导入预览、同名替换/另存为、导出 | 已实现（Phase 4） |
| 账号默认模板与建单模板预填（任务、刷新规则、来源快照） | 已实现（可选，不强制） |
| 提醒设置、备份与恢复 | 明确显示待实现，不属于本阶段 |
| 云服务、发布签名、导出分享 | 未实现（不在本阶段范围） |

金额一律为整数分存储与计算，不使用浮点。约定价格不会自动变成“已收款”：
只有用户实际录入的收款才计入实收。

模板只是**新建订单时的默认值**：建单时把任务、刷新规则和模板来源复制成订单自己的快照。
之后编辑、替换、导入覆盖或删除模板，都不会改动已有订单、价格、打卡、收款或历史日期归属。
模板 JSON 是单模板交换格式，不是业务数据备份。

## 幂等与时间契约

- 建单命令由 `order_creation_operations` 持久化：相同操作标识 + 相同规范化载荷
  返回原订单与原首笔收款；载荷不同则拒绝；并发相同请求只创建一单。
- 收款、更正、续期、批量完成各有唯一操作标识；相同重试返回原结果，不重复写行。
- 时间统一按 **epoch 毫秒** 比较与持久化；`receivedAt`（实际到账）与 `createdAt`
  （录入时间）严格分离，历史到账时间会进入对应月份的统计。

## 构建与验证

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home
export ANDROID_HOME=/Users/francis/Library/Android/sdk

./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest      # 需要已启动的模拟器或设备
./gradlew :app:lintDebug :app:assembleDebug
git diff --check
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`

## 结构

```
app/src/main/java/com/daigan/record/
├── core/time/          时间源、游戏日历、报表时区/窗口、毫秒精度常量
├── data/local/         Room 实体、DAO、数据库、类型转换器、迁移
├── data/model/         领域模型、金额、余额、收款、统计、模板、表单校验、命令指纹
├── data/template/      模板 JSON 编解码、内置资产、SAF 文件读写
├── data/repository/    Repository 实现、ReceiptLedger、LedgerSnapshotSource
├── data/di/            Hilt 绑定
├── feature/accounts/   老板与账号列表、详情、表单、归档
├── feature/orders/     建单、订单详情、收款/更正对话框、命令恢复编解码
├── feature/today/      今日事项
├── feature/stats/      数据统计（月/全部）
├── feature/settings/   独立设置页、收款明细页
├── feature/templates/  模板库、模板编辑器、导入预览
└── ui/                 主题、导航、通用组件
```

数据流固定为 Compose → ViewModel → Repository → DAO → Room；ViewModel 不接触 DAO 或文件。

## 文档

- 需求：`docs/superpowers/specs/2026-10-17-game-work-manager-design.md`
- 架构补充：`docs/superpowers/plans/2026-10-17-architecture-foundation.md`
- 上游底座：`docs/upstream-foundation.md`
- 数据模型（含 v4/v5 迁移与收款语义）：`docs/data-model.md`
- 阶段交接：`docs/handoffs/phase-4.md`
- 历史交接：`docs/handoffs/phase-1.md`、`docs/handoffs/phase-2.md`、
  `docs/handoffs/phase-3.md`、`docs/handoffs/phase-3-fix-1.md`、`docs/handoffs/phase-3-fix-2.md`
- 模板内容来源与核实：`docs/game-template-sources.md`
- 模板文件格式：`docs/template-file-format.md`

## 上游来源与许可

源码底座来自 Google `android/architecture-templates` 的 `base` 分支，固定提交
`cdb6de476e1889c88947946c28d900b3537dc867`，遵循 Apache License 2.0（见 `LICENSE`）。
派生文件的版权声明已保留，具体改动见 `docs/upstream-foundation.md`。
