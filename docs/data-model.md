# 数据模型（Phase 1 + Phase 2 已实现）

Room/SQLite 是业务资料唯一事实来源。本文件只描述**已经实现并经过迁移测试**的表；
收款流水、游戏模板库、提醒与备份仍属于后续阶段，未创建空表，也没有占位实现。

Schema 导出位置：`app/schemas/com.daigan.record.data.local.RecordDatabase/`。

| 版本 | 文件 | 状态 |
|---|---|---|
| 1 | `1.json` | **只读历史夹具**，不再修改 |
| 2 | `2.json` | **只读历史夹具**，Phase 1 验收基线 |
| 3 | `3.json` | 当前生产版本（Phase 2） |

`exportSchema = true`，没有 `fallbackToDestructiveMigration`。生产构建通过
`RecordDatabase.ALL_MIGRATIONS` 注册 `MIGRATION_1_2` 与 `MIGRATION_2_3`。

类型转换器：

| Kotlin 类型 | SQLite 类型 | 说明 |
|---|---|---|
| `Instant` | INTEGER | epoch 毫秒（UTC）；所有“实际记录时间” |
| `LocalDate` | TEXT | ISO-8601 `yyyy-MM-dd`；本地日期/游戏日期 |
| `BigDecimal` | TEXT | `toPlainString()`，保留用户输入的精确小数 |
| 枚举（订单类型/进度/周期/批次等） | TEXT | 枚举名 |
| `Boolean` | INTEGER | 0/1 |

金额一律为整数分（`priceCents`），不存在浮点金额。

## 1. Phase 1 表（版本 2 起未再改动）

### `owners`

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | TEXT | 主键，UUID |
| `number` | INTEGER | 非空，唯一索引，不可修改 |
| `nickname` | TEXT | 非空，1–30 字符 |
| `createdAt` / `updatedAt` | INTEGER | 非空 |
| `archivedAt` | INTEGER | 可空；非空表示已归档 |
| `archiveBatchId` | TEXT | 可空；老板归档批次 |

### `game_accounts`

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | TEXT | 主键 |
| `ownerId` | TEXT | → `owners.id`，`ON DELETE RESTRICT` |
| `gameName` / `server` / `uid` / `loginAccount` | TEXT | 非空 |
| `loginMethod` | TEXT | `PASSWORD` / `VERIFICATION_CODE` |
| `password` | TEXT | 可空；验证码登录必须为 NULL |
| `createdAt` / `updatedAt` | INTEGER | 非空 |
| `archivedAt` | INTEGER | 可空 |
| `archiveBatchId` | TEXT | 可空；仅随老板归档时写入 |

### `owner_number_sequence`

单行计数器（`id = 0`），编号在同一事务中 `UPDATE → SELECT → INSERT` 分配；
`owners.number` 唯一索引是并发最后防线。归档不归还编号。

### 归档不变量（Phase 2 仍然有效）

- 老板归档：同一事务内生成 `archiveBatchId`，老板与当时未归档的账号共享批次。
- 老板恢复：只恢复批次相同的账号；独立归档账号保持归档。
- 独立归档账号：`archiveBatchId` 保持 NULL。
- 重复归档/恢复幂等，不重写时间戳或批次。
- 老板归档期间，其账号写入一律在同一事务中拒绝。

Phase 2 新增的**归档守卫**（见第 4 节）在归档老板/账号时要求没有未完成短期单，
且没有未到期托管期。

## 2. Phase 2 表（数据库版本 3）

### `orders` — 订单

| 列 | 类型 | 约束 / 语义 |
|---|---|---|
| `id` | TEXT | 主键；**续期不变** |
| `accountId` | TEXT | → `game_accounts.id`，`ON DELETE RESTRICT`，索引 |
| `type` | TEXT | `SHORT_TASK` / `HOSTING` |
| `title` | TEXT | 非空 |
| `priceCents` | INTEGER | 短期单整单总价；托管单为 NULL（价格属于期次） |
| `paymentMode` | TEXT | `ONE_TIME` / `INSTALLMENTS`；仅记录选择，不产生收款 |
| `deadlineDate` | TEXT | 可空；短期单交付期限（按订单游戏日判断逾期） |
| `notes` | TEXT | 可空 |
| `gameZoneId` | TEXT | 显式时区，例如 `Asia/Shanghai` |
| `dailyRefreshMinutes` | INTEGER | 每日刷新，距零点分钟 |
| `weeklyRefreshDay` | INTEGER | ISO 1–7（周一=1） |
| `weeklyRefreshMinutes` | INTEGER | 每周刷新，距零点分钟 |
| `createdAt` / `updatedAt` | INTEGER | 非空 |
| `completedAt` | INTEGER | 短期单全部任务完成时非空；重新打开任务时清空 |

索引：`accountId`、`(accountId, type)`、`(type, completedAt)`。
一个账号可以同时拥有多张短期单和多张托管单。

### `hosting_periods` — 托管期次

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | TEXT | 主键 |
| `orderId` | TEXT | → `orders.id`，`RESTRICT` |
| `periodIndex` | INTEGER | 从 0 开始，续期递增 |
| `startDate` / `endDate` | TEXT | 游戏时区日期，**首尾均含** |
| `priceCents` | INTEGER | 本期约定价格（整数分） |
| `gameZoneId` / `dailyRefreshMinutes` / `weeklyRefreshDay` / `weeklyRefreshMinutes` | | 本期规则快照，续期后不改写旧期 |
| `createdAt` | INTEGER | 非空 |
| `renewalOperationId` | TEXT | 可空；唯一索引；续期幂等 |

唯一索引：`(orderId, periodIndex)`、`(orderId, startDate)`、`renewalOperationId`。

### `task_definitions` — 任务配置快照

短期单任务 `periodId = NULL`；托管任务全部挂在具体期次下。

| 列 | 类型 | 语义 |
|---|---|---|
| `id` | TEXT | 主键 |
| `orderId` | TEXT | → `orders.id`，`RESTRICT` |
| `periodId` | TEXT | 可空，→ `hosting_periods.id`，`RESTRICT` |
| `title` | TEXT | 非空 |
| `progressType` | TEXT | `CHECKBOX` / `NUMERIC` |
| `startValue` / `targetValue` / `currentValue` | TEXT | 精确十进制；短期单当前值持久化 |
| `unit` | TEXT | 可空 |
| `recurrence` | TEXT | `DAILY` / `WEEKLY` / `SELECTED_WEEKDAYS` / `EVERY_N_DAYS` |
| `weekdays` | TEXT | 可空；ISO 星期号逗号分隔，如 `1,3,5` |
| `everyNDays` | INTEGER | 可空；N≥1，**以本期间开始日为锚点** |
| `sortOrder` | INTEGER | 表单顺序 |
| `completedAt` | INTEGER | 短期单任务完成时间；托管任务恒为 NULL |
| `completionBatchId` | TEXT | 可空；短期批量完成所属批次 |
| `createdAt` / `updatedAt` | INTEGER | 非空 |

### `task_occurrences` — 托管打卡发生记录

| 列 | 类型 | 语义 |
|---|---|---|
| `id` | TEXT | 主键 |
| `taskDefinitionId` | TEXT | → `task_definitions.id`，`RESTRICT` |
| `occurrenceKey` | TEXT | 稳定键，见第 3 节 |
| `gameDate` | TEXT | 归属游戏日期；周任务为窗口起始日 |
| `windowStart` / `windowEnd` | TEXT | 可空；周任务窗口（已按托管期裁剪） |
| `completedAt` | INTEGER | 可空；实际完成/记录时刻 |
| `recordedAt` | INTEGER | 可空；实际录入时刻（补录时与历史日期不同） |
| `backfilled` | INTEGER | 非空；该次记录属于历史补录 |
| `completionBatchId` | TEXT | 可空；批量完成所属批次 |
| `value` | TEXT | 可空；数值进度 |
| `createdAt` | INTEGER | 非空 |

**唯一索引 `(taskDefinitionId, occurrenceKey)`** 是重复生成与重复点击的最终防线。
索引：`taskDefinitionId`、`gameDate`、`completionBatchId`。

### `completion_batches` / `completion_batch_items` — 批量完成审计

`completion_batches`：`id`、`operationId`（唯一）、`scope`
（`HOSTING_ORDER` / `HOSTING_ALL` / `SHORT_ORDER`）、`targetId`、`gameDate`、
`createdAt`、`undoneAt`。

`completion_batch_items` 保存每项的**应用前**与**应用后**状态：

- 归属：`occurrenceId` 或 `taskDefinitionId`（二者择一）。
- 前值：`previousCompletedAt`、`previousRecordedAt`、`previousBackfilled`、
  `previousValue`、`previousCompletionBatchId`。
- 后值：`appliedCompletedAt`、`appliedRecordedAt`、`appliedBackfilled`、
  `appliedValue`、`appliedCompletionBatchId`。

撤销只回滚本批次实际改动的项；若当前行不再等于 `applied*`（后续独立编辑），
该项记为冲突并保留最新结果，不会覆盖。

### `attachments` — 截图引用

| 列 | 类型 | 语义 |
|---|---|---|
| `id` | TEXT | 稳定附件 ID |
| `taskDefinitionId` | TEXT | 可空；短期任务截图 |
| `occurrenceId` | TEXT | 可空；托管打卡截图 |
| `relativePath` | TEXT | 应用私有目录 `files/attachments/` 下的文件名 |
| `mimeType` | TEXT | 非空 |
| `sizeBytes` | INTEGER | 非空 |
| `createdAt` | INTEGER | 非空 |

`taskDefinitionId` 与 `occurrenceId` 必须恰好一个非空（仓储层校验）。
外键均 `RESTRICT`。数据库不保存 Base64，也不保存外部 URI。

## 3. 日期与周期语义（实现于 `core/time/GameCalendar.kt`、`data/model/OccurrenceRules.kt`）

三个概念严格区分：

1. **本地日历日期**：存储时区中的民用日期。日期选择器使用它。
2. **游戏日期**：以“每日刷新时间”为起点的一天。若当前本地时间早于每日刷新，
   游戏日期为前一天。每日、指定星期、每隔 N 天任务都归属游戏日期。
3. **每周发生窗口**：`[配置星期 + 配置时间的刷新时刻, +7 天)`。周任务每个窗口
   恰好一条发生记录；窗口可跨多个游戏日，但不会按天复制。窗口展示日期为起始日
   与起始日 + 6 天；实际起止时刻用于判断可执行性。

发生键（`taskDefinitionId` 已按期次隔离，因此全局唯一索引只做最后防线）：

| 周期 | 键格式 | 归属日期 |
|---|---|---|
| 每日 | `D:<gameDate>` | 当天游戏日 |
| 指定星期 | `S:<gameDate>` | 当天游戏日 |
| 每隔 N 天 | `E:<gameDate>` | 从期次 `startDate` 起第 `k*N` 天 |
| 每周 | `W:<windowStartLocalDate>` | 窗口起始日（展示时裁剪到托管期） |

- 所有任务只在 `[startDate, endDate]` 内存在；未来开始日不生成工作；到期日之后
  不再生成新任务。
- 周任务窗口与托管期只部分重叠时仍保留一条发生记录，但键使用**未裁剪**的窗口起始日，
  因此同一窗口在期次两端保持稳定；`windowStart`/`windowEnd` 存展示用裁剪值。
- 历史补录按所选游戏日期定位周窗口；同一周内选择任意日期都指向同一条记录。
  若周刷新时刻落在游戏日中间，补录以该游戏日开始后 12 小时的时刻判定窗口，
  这是 Phase 2 的明确规则；真实游戏模板（Phase 4）若需要更细规则必须新增强制迁移。

**续期与每日刷新**：续期开始日 = 操作时刻在**该期存储时区**中的游戏日期。

- 刷新前续期：游戏日期仍是前一天，新期从该游戏日开始，当天剩余窗口即可打卡。
- 刷新后续期：新期从当天游戏日开始。
- 任何情况下都只生成 `startDate` 及之后的发生记录，不会回溯到旧期或新期开始日前。

## 4. 仓储接口与事务边界

```kotlin
interface OrderRepository {
    fun observeOrdersForOwner(ownerId: String): Flow<List<OrderSummary>>
    fun observeOrderDetail(orderId: String): Flow<OrderDetail?>
    fun observeTodayHosting(): Flow<List<TodayHostingOrder>>
    fun observeTodayShort(): Flow<List<ShortWorkOrder>>

    suspend fun createShortOrder(draft: ShortOrderDraft): String
    suspend fun createHostingOrder(draft: HostingOrderDraft): String

    suspend fun setShortTaskCompleted(taskId: String, completed: Boolean)
    suspend fun updateShortTaskProgress(taskId: String, value: BigDecimal)
    suspend fun completeShortOrder(orderId: String, operationId: String): BulkCompletionResult

    suspend fun setOccurrenceCompleted(taskId: String, completed: Boolean)
    suspend fun updateOccurrenceProgress(taskId: String, value: BigDecimal)
    suspend fun completeHostingOrder(orderId: String, operationId: String): BulkCompletionResult
    suspend fun completeAllHosting(operationId: String): BulkCompletionResult
    suspend fun undoBatch(batchId: String): UndoResult

    suspend fun setHistoricalCompletion(
        orderId: String, periodId: String, taskId: String,
        date: LocalDate, completed: Boolean,
    ): Boolean

    suspend fun renewHosting(
        orderId: String, operationId: String,
        durationDays: Int, priceCents: Long,
    ): RenewalResult
}

interface AttachmentRepository {
    fun observeForOrder(orderId: String): Flow<List<Attachment>>
    suspend fun addAttachment(taskDefinitionId: String?, occurrenceId: String?, sourceUri: Uri): Attachment
    suspend fun replaceAttachment(attachmentId: String, sourceUri: Uri): Attachment
    suspend fun deleteAttachment(attachmentId: String)
    fun fileFor(attachment: Attachment): File
    suspend fun cleanupOrphans(): Int
}
```

所有写操作在 `database.withTransaction` 中完成，并在事务内重新读取并校验：

- 账号存在且属于给定老板（建单时同时校验 `ownerId` 与 `account.ownerId`）。
- 老板与账号均未归档；归档状态下返回
  `ARCHIVED_OWNER_ORDER_MESSAGE` / `ARCHIVED_ACCOUNT_ORDER_MESSAGE`
  或工作写入专用提示。
- 托管任务的期次属于该订单；补录任务属于该期次。

**归档守卫**（同一事务）：

- 老板有未完成短期单 → 拒绝归档老板。
- 老板名下最新期次未到期（`游戏日 ≤ endDate`，含未来开始）→ 拒绝归档老板。
- 账号同理，提示改为“游戏账号”。
- 已完成短期单或已到期托管期不阻止归档；归档后订单、期次、发生记录、截图仍可读。

**批量完成/撤销**：

- 批量只处理当前游戏日/当前周窗口内可执行且尚未完成的发生记录，绝不补历史。
- 同一 `operationId` 重放返回同一批次与 `alreadyApplied = true`；不会重复写入。
- 撤销在单事务内逐项比对 `applied*`；冲突项保留最新结果并在 `UndoResult.conflicts`
  中说明。撤销同样受归档写入守卫约束。
- 撤销不触碰批次之外此前已完成的项。

**续期**：

- 只允许 `当前游戏日 > 当前期 endDate`；否则拒绝提前续期。
- 新期 `periodIndex = 旧期 + 1`，`startDate = 当前游戏日`，`endDate = start + 天数 - 1`。
- `renewalOperationId` 唯一；重复提交返回既有期次。
- 从旧期任务复制为**新 ID** 的任务快照，完成状态与当前值重置，旧期数据不动。
- 订单数量不增加，订单 ID 不变；旧期价格保留但不声称已收款。

## 5. 截图文件与失败处理

- 来源：`ActivityResultContracts.PickVisualMedia`（系统照片选择器），不申请宽泛存储权限。
- 流程：读取 `contentResolver` → 写入 `files/attachments/<uuid>.tmp` → 移动/复制为
  `files/attachments/<uuid>.<ext>` → 在数据库事务中插入引用。数据库失败则删除新文件。
- 替换：先生成新文件，再在同一事务中插入新行并删除旧行；任一步失败则删除新文件，
  旧行与旧文件保持不变。成功后旧文件才删除。
- 删除：先删除数据库引用，事务提交后再删除文件；失败遗留文件由
  `cleanupOrphans()` 清理（应用启动时调用）。
- 取消选择（`null` URI）或来源不可读：不写任何行，不产生“成功但损坏”的附件。
- 清理只删除未被引用的最终文件与超过 1 小时的临时文件。

## 6. 迁移与测试

- `MIGRATION_1_2`：两条 `ALTER TABLE ... ADD COLUMN archiveBatchId TEXT`，不重写数据。
- `MIGRATION_2_3`：创建 7 张 Phase 2 表、全部显式命名索引与外键；不修改任何旧行。
- `MigrationV1ToV2Test`：用提交的 `1.json` 建 v1 库，写入真实行，执行全部迁移到当前
  版本；编号、凭据、归档时间保留，批次列为 NULL；生产同款 builder 打开旧库。
- `MigrationV2ToV3Test`：用提交的 `2.json` 建 v2 库，含显式 `archiveBatchId`；升级后
  旧行逐字段保留，新表为空；新外键 `RESTRICT` 生效。
- `MigrationInfrastructureTest`：遍历 1..当前版本的所有导出 schema，升级并校验数据。
- 真实 Room 测试见 `OrderRepositoryTest`、`HostingOccurrenceTest`、
  `BatchCompletionTest`、`RenewalTest`、`AttachmentTest`。

## 7. 尚未实现（不是本阶段的隐含承诺）

- 收款流水、欠款、实收统计与期次归属选择（Phase 3）。本阶段只保存约定价格/收款方式，
  没有 `receipts` 表，也不会把旧期次标记为已收。
- 游戏模板库、模板编辑/导入导出（Phase 4）。当前刷新规则是用户自填的临时值。
- 系统通知/提醒、备份与恢复、加密、应用锁（后续阶段/产品明确不需要）。
