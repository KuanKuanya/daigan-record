# 数据模型（Phase 1 实现 + 后续阶段关系设计）

Room/SQLite 是业务资料唯一事实来源。本文件分两部分：已实现的第一阶段表结构，以及为后续阶段
预留的关系设计。**后续阶段的表尚未创建**，不存在空表或推测性迁移。

Schema 导出位置：`app/schemas/com.daigan.record.data.local.RecordDatabase/1.json`
（`exportSchema = true`，提交到仓库；无 `fallbackToDestructiveMigration`）。

## 1. 已实现的表（数据库版本 1）

类型转换器：`Instant ↔ Long`（epoch 毫秒，UTC）、`LoginMethod ↔ String`（枚举名）。

### `owners` — 老板

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | TEXT | 主键，UUID 字符串 |
| `number` | INTEGER | 非空，**唯一索引**，由序列分配，不可修改 |
| `nickname` | TEXT | 非空，去掉首尾空白，1–30 字符 |
| `createdAt` | INTEGER | 非空，`Instant` |
| `updatedAt` | INTEGER | 非空，`Instant` |
| `archivedAt` | INTEGER | 可空，`Instant`；非空表示已归档 |

索引：`number`（唯一）、`archivedAt`。

### `game_accounts` — 游戏账号

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | TEXT | 主键，UUID |
| `ownerId` | TEXT | 非空，外键 → `owners.id`，`ON DELETE RESTRICT`，`ON UPDATE CASCADE` |
| `gameName` | TEXT | 非空（本阶段直接填写，第四阶段接入模板） |
| `server` | TEXT | 非空（区服） |
| `uid` | TEXT | 非空（游戏 UID，文本以保留前导零） |
| `loginAccount` | TEXT | 非空 |
| `loginMethod` | TEXT | 非空，`PASSWORD` / `VERIFICATION_CODE` |
| `password` | TEXT | 可空；仅密码登录时非空，验证码登录必须为 NULL |
| `createdAt` / `updatedAt` | INTEGER | 非空 |
| `archivedAt` | INTEGER | 可空 |

索引：`ownerId`；`(ownerId, archivedAt)`。插入顺序通过 SQLite `rowid` 作为次级排序键保持稳定。

### `owner_number_sequence` — 老板编号序列

单行计数器（`id = 0`，`nextNumber` 从 1 开始），保证编号单调、不复用、跨进程重启有效。

### 关键不变量与实现方式

1. **编号分配事务安全**：`createOwner` 在单个 `withTransaction` 中依次执行
   `INSERT OR IGNORE`（初始化序列）→ `UPDATE nextNumber = nextNumber + 1`（获取写锁）→
   `SELECT nextNumber - 1` → `INSERT owners`。并发事务被 SQLite 串行化，随后读取不会看到同一个值；
   `owners.number` 的唯一索引是最后防线，违反时最多重试 3 次（事务整体回滚，不会重复占用编号）。
2. **昵称可改、编号不变**：`renameOwner` 只更新 `nickname`/`updatedAt`，UI 不提供编号编辑。
3. **外键强制**：外键声明 + Room 默认开启 `foreign_keys`；删除仍有账号的老板被 RESTRICT 拒绝。
   Phase 1 没有删除入口，只有归档。
4. **凭据规则**：仓储层统一归一化：验证码登录时 `password` 写为 NULL；切回密码登录而未填写密码会被校验拒绝。
   验证码本身从不写入任何表。凭据不写日志、不进入报告或分析。
5. **归档语义**：
   - 归档老板：同一事务内把老板及其当前未归档账号写入同一个 `archivedAt` 时间戳。
   - 恢复老板：只恢复 `archivedAt` 与该时间戳完全相同的账号，因此此前单独归档的账号不会被误恢复。
   - 单独归档/恢复账号：更新 `archivedAt` 与 `updatedAt`。
   - 归档记录页分别列出“已归档老板”（含随其归档的账号）与“已归档账号”（所属老板仍在用）。
6. **排序**：老板按 `number` 升序；账号按 `createdAt`、`rowid` 升序；归档账号按 `updatedAt` 降序。
7. **迁移策略**：任何表结构变化都必须提升版本并提供 `Migration`，用 `MigrationTestHelper`
   针对提交的历史 schema 测试。Phase 1 不伪造版本升级；已有基础设施测试见
   `app/src/androidTest/java/com/daigan/record/data/local/MigrationInfrastructureTest.kt`。

## 2. 后续阶段关系设计（未实现）

以下仅作为接口与约束约定，供第二阶段起按实际需求落地；不创建空表。

```
owners 1 ── n game_accounts
game_accounts 1 ── n orders
orders 1 ── n hosting_periods
hosting_periods 1 ── n task_definitions (快照)
task_definitions 1 ── n task_occurrences
orders 1 ── n receipts            (hosting_period_id 可空)
task_occurrences 1 ── n attachments
receipts 1 ── n attachments (可选)
game_templates 1 ── n template_tasks (模板库，非订单快照)
```

建议字段与约束（第二阶段起实现时补充精确 DDL）：

- `orders`
  - `id` TEXT 主键（续期不改变订单 ID）
  - `accountId` → `game_accounts.id`（RESTRICT），索引
  - `type`：`HOSTING` / `SHORT_TASK`
  - `title`、`priceCents`（整数分）、`paymentMode`、`createdAt`、`archivedAt`/`closedAt`
  - 订单保存创建时的配置快照（时区、刷新规则、任务列表），模板后续修改不得回写历史订单。
- `hosting_periods`
  - `id`、`orderId` → `orders.id`（RESTRICT）、`startDate`/`endDate`（游戏时区的 `LocalDate`，存
    ISO 文本或 epochDay）、`priceCents`、`periodIndex`
  - 唯一约束建议 `(orderId, periodIndex)` 与 `(orderId, startDate)`，避免重复续期。
- `task_definitions`（订单期次内的任务配置快照）
  - `id`、`periodId` → `hosting_periods.id`、`title`、`progressType`（CHECKBOX / NUMERIC）、
    `startValue`、`targetValue`、`unit`、`recurrence`、`weekdays`、`everyNDays`
  - 数值使用整数或定点，不使用浮点累计。
- `task_occurrences`
  - `id`、`taskDefinitionId`、`gameDate`（ISO 日期）、`completedAt`（可空 `Instant`）、
    `recordedAt`（补录时间）、`backfilled` 标记、`completionBatchId` 可空
  - **唯一约束** `(taskDefinitionId, gameDate)`，保证幂等与重复点击安全。
  - 撤销批次只回滚该批次改变的项，不能覆盖此前已完成项。
- `receipts`
  - `id`、`orderId`、`periodId`（可空，短期单为空）、`amountCents`、`receivedAt`（实际收款时刻）、
    `channel`（微信 / 支付宝 / 其他）、`note`、`operationId`（唯一，防重复提交）、`createdAt`
  - 金额只存整数分；欠款由“期次价格 − 实收”计算，不额外持久化页面余额。
- `attachments`
  - `id`、`ownerType`（任务发生记录 / 收款 / 打卡）、`ownerId`、`relativePath`（应用私有目录）、
    `mimeType`、`sizeBytes`、`checksum`、`createdAt`
  - 先落地文件再提交引用；失败清理临时文件；删除先改引用，延后清理无引用文件。
  - 不把图片 Base64 写入数据库。
- `game_templates` / `template_tasks`
  - `id`、`name`、`templateVersion`、`gameVersionNote`、`timeZone`、`dailyRefresh`、`weeklyRefresh`、
    分类与任务选项、默认进度方式与单位、默认周期
  - 导入导出使用版本化 UTF-8 JSON；模板更新不得改写订单快照。

通用约定：

- 主键统一 UUID 字符串；金额统一整数分；事件时刻统一 `Instant`；游戏日期统一 `LocalDate`；
  游戏时区显式保存，不使用手机零点。
- 归属用显式外键 + 索引；禁止级联删除业务记录（`RESTRICT`），以归档/作废代替删除。
- 所有跨表写入（建单+首笔收款、续期、批量完成/撤销、收款归属）在单个事务中完成，
  并用操作 ID / 唯一索引防止重复。
- 设置类偏好：仅显示偏好可放 DataStore；影响业务与提醒的配置放 Room，避免跨存储版本不一致。

## 3. 测试覆盖（真实 Room）

- `RecordDatabaseTest`：外键拒绝、编号唯一索引、RESTRICT 阻止删除、关闭/重开数据库后数据仍在、归档查询、序列递增。
- `OwnerRepositoryTest`：20 个并发创建得到唯一连续编号、改昵称不改编号、同老板多账号、跨老板账号隔离、
  密码→验证码清除密码、归档/恢复、编号不复用、账号必须属于存在的老板。
- `MigrationInfrastructureTest`：以提交的 v1 schema 为历史夹具创建数据库、写入原始行、
  由 `MigrationTestHelper` 校验当前 schema 与数据。
