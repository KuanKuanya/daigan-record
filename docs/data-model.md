# 数据模型（Phase 1–5）

Room/SQLite 是业务资料唯一事实来源。本文件描述已实现的持久化结构；第五阶段实际验证证据见 `docs/handoffs/phase-5.md`。
提醒设置、投递去重与本机资料身份自第五阶段加入。游戏模板库自 Phase 4 起是真实
持久化表，但只是**新建订单时的默认值**，不是业务记录的事实来源。

Schema 导出位置：`app/schemas/com.daigan.record.data.local.RecordDatabase/`。

| 版本 | 文件 | 状态 |
|---|---|---|
| 1 | `1.json` | **只读历史夹具**，不再修改 |
| 2 | `2.json` | **只读历史夹具**，Phase 1 验收基线 |
| 3 | `3.json` | **只读历史夹具**，Phase 2 验收基线 |
| 4 | `4.json` | 历史版本：Phase 3 首版收款表 |
| 5 | `5.json` | 历史版本：Phase 3 验收基线 |
| 6 | `6.json` | 只读历史版本（Phase 4 模板库与来源列） |
| 7 | `7.json` | 当前版本（提醒设置、投递记录、本机资料身份） |

`exportSchema = true`，没有 `fallbackToDestructiveMigration`。生产构建通过
`RecordDatabase.ALL_MIGRATIONS` 注册 `MIGRATION_1_2`、`MIGRATION_2_3`、
`MIGRATION_3_4`、`MIGRATION_4_5`、`MIGRATION_5_6` 与 `MIGRATION_6_7`。

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

Phase 2 新增的**归档守卫**（见第 5 节）在归档老板/账号时要求没有未完成短期单，
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
| `occurrenceKey` | TEXT | 稳定键，见第 5 节 |
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

## 3. Phase 3 表（数据库版本 4 → 5）

### `receipts` — 实收流水

一条收款行代表**实际收到**的一笔钱。金额只来自用户输入，绝不从约定价格、完成状态
或期次推算。

| 列 | 类型 | 约束 / 语义 |
|---|---|---|
| `id` | TEXT | 主键 |
| `operationId` | TEXT | 非空，唯一索引；收款命令的幂等键 |
| `orderId` | TEXT | → `orders.id`，`RESTRICT`，索引 |
| `periodId` | TEXT | 可空，→ `hosting_periods.id`，`RESTRICT`，索引；托管收款必填，短期单必须为 NULL |
| `amountCents` | INTEGER | 整数分，1 … `Money.MAX_CENTS`（99,999,999.99 元） |
| `receivedAt` | INTEGER | **实际到账时刻**（epoch 毫秒，UTC），可早于录入时间 |
| `channel` | TEXT | `WECHAT` / `ALIPAY` / `BANK` / `CASH` / `OTHER` |
| `note` | TEXT | 可空；仅 `OTHER` 保存用户备注，其他渠道为 NULL |
| `status` | TEXT | `ACTIVE` / `REPLACED` / `VOIDED` |
| `replacedByReceiptId` | TEXT | 可空；`REPLACED` 指向替换行 |
| `createdAt` | INTEGER | 录入时刻（与 `receivedAt` 明确分离） |

### `receipt_corrections` — 更正审计

更正**不删除**原收款行。VOID 把原行置为 `VOIDED`；AMEND 插入一条新的 `ACTIVE`
行并把原行置为 `REPLACED`。审计行保存前后值，历史永远可读。

| 列 | 类型 | 语义 |
|---|---|---|
| `id` | TEXT | 主键 |
| `operationId` | TEXT | 非空，唯一索引；更正命令的幂等键 |
| `receiptId` | TEXT | → `receipts.id`；被更正的原收款 |
| `kind` | TEXT | `AMEND` / `VOID` |
| `reason` | TEXT | 可空；**参与幂等冲突判定**的审计原因 |
| `previousAmountCents` / `previousReceivedAt` / `previousChannel` / `previousNote` | | 更正前快照 |
| `newReceiptId` | TEXT | 可空；AMEND 生成的新收款行 |
| `newAmountCents` / `newReceivedAt` / `newChannel` / `newNote` | | AMEND 后快照；VOID 全为 NULL |
| `createdAt` | INTEGER | 更正录入时刻 |

### `order_creation_operations` — 建单命令结果（版本 5 新增）

| 列 | 类型 | 约束 / 语义 |
|---|---|---|
| `operationId` | TEXT | 主键；整条建单命令的稳定标识 |
| `orderType` | TEXT | `SHORT_TASK` / `HOSTING`；必须与重试请求一致 |
| `fingerprint` | TEXT | 规范化请求指纹；决定“同一命令”还是“冲突载荷” |
| `orderId` | TEXT | → `orders.id`，`RESTRICT`，索引；命令的存储结果 |
| `receiptId` | TEXT | 可空；首笔实收的存储结果 |
| `createdAt` | INTEGER | 命令首次成功提交时刻 |

**没有为历史订单补写本表。** v3/v4 的订单在幂等机制之前创建，伪造一个
`operationId` 会把它们误认为某次新命令的重试。

### 有效收款与欠款（实收语义）

- 只有 `status = ACTIVE` 的收款计入实收；`REPLACED` / `VOIDED` 为 0。
- 短期单：一件应收 = 订单 `priceCents`，`paid = Σ ACTIVE receipts(periodId IS NULL)`。
- 托管单：每期一件应收 = 该期 `priceCents`，
  `paid = Σ ACTIVE receipts(periodId = 该期)`；价格属于期次，续期不会改写旧期。
- `outstanding = max(0, price - paid)`；只有 `outstanding > 0` 的款项进入待收列表。
- 实收统计按 `receivedAt` 落在报表时区窗口内汇总；欠款是**全时段**未收金额，
  不受月份筛选影响。
- 归档、已完成与已到期订单的欠款仍可收取；工作写入（任务/打卡/批量）仍被归档守卫拒绝。
- 续期保留旧期欠款与旧流水，新期从零流水开始，`orders` 行数不增加。

### 幂等、时间精度与并发

| 命令 | 幂等键 | 相同重试 | 冲突载荷 |
|---|---|---|---|
| 建单（含首笔） | `order_creation_operations.operationId` | 返回原订单/原收款 | 拒绝（`CREATION_CONFLICT_MESSAGE`） |
| 收款 | `receipts.operationId` | 返回原收款 `alreadyApplied=true` | 拒绝 |
| 更正 | `receipt_corrections.operationId` | 返回原审计，历史不增行 | 拒绝（含不同 `reason`） |
| 续期 | `hosting_periods.renewalOperationId` | 返回原期次 | 拒绝 |
| 批量完成 | `completion_batches.operationId` | 返回原批次 | 拒绝 |

- 时间统一为 **epoch 毫秒**（`PersistedInstant.normalize`）：写入、规范化命令、
  请求指纹、重试比较与返回值使用同一精度。亚毫秒 `Instant` 在重试时不会误判冲突。
- 收款比较字段：订单、期次、金额、`receivedAt`（毫秒）、渠道、规范化备注。
- 更正比较字段：原收款、类型、金额、`receivedAt`（毫秒）、渠道、备注、审计原因。
- 建单在**一个事务**内写入操作结果、订单、任务、托管期次与可选首笔收款；
  任一校验或收款失败导致整体回滚。
- 并发相同命令由唯一索引兜底：落败事务回滚后读取获胜事务的已提交结果。
- `receivedAt` 是**实际到账**时间，允许历史值；`now` 只用于“不能晚于当前时间”的
  校验与 `createdAt` 录入时间。

## 4. Phase 4 表（数据库版本 6）

模板库是“创建时的默认值”，不是业务记录。订单在创建时把自己的任务、刷新规则与
模板来源复制成独立快照，**没有任何业务表通过外键引用模板**，因此修改、替换、导入覆盖
或删除模板都不会改动已有订单、价格、打卡、收款或历史日期归属。

### `game_templates`

| 列 | 类型 | 约束 / 语义 |
|---|---|---|
| `id` | TEXT | 主键；内置模板使用资产中的稳定 ID |
| `gameName` | TEXT | 非空；1–40 字符 |
| `templateVersion` | TEXT | 非空；由用户维护，例如 `2026.10` |
| `gameVersionNotes` | TEXT | 可空；适用版本与“未核实/示例”说明 |
| `zoneId` | TEXT | 非空；IANA 时区 |
| `dailyRefreshMinutes` | INTEGER | 非空；距零点分钟 |
| `weeklyRefreshDay` | INTEGER | 非空；ISO 1–7 |
| `weeklyRefreshMinutes` | INTEGER | 非空 |
| `origin` | TEXT | `BUILT_IN` / `USER` / `IMPORTED` |
| `builtInKey` | TEXT | 可空；唯一索引；内置模板的稳定种子标识 |
| `sortOrder` | INTEGER | 非空；稳定的库内顺序（通用模板为 0） |
| `createdAt` / `updatedAt` | INTEGER | 非空 |

`index_game_templates_builtInKey` 唯一索引让重复初始化不可能写入第二份内置模板。
`sortOrder` 只影响列表顺序，不参与业务判断。

### `template_categories`

| 列 | 类型 | 约束 |
|---|---|---|
| `id` | TEXT | 主键 |
| `templateId` | TEXT | → `game_templates.id`，`RESTRICT`，索引 |
| `name` | TEXT | 非空；1–30 字符 |
| `sortOrder` | INTEGER | 非空 |

### `template_tasks`

| 列 | 类型 | 约束 / 语义 |
|---|---|---|
| `id` | TEXT | 主键 |
| `templateId` | TEXT | → `game_templates.id`，`RESTRICT`，索引 |
| `categoryId` | TEXT | → `template_categories.id`，`RESTRICT`，索引 |
| `title` | TEXT | 非空；1–40 字符 |
| `progressType` | TEXT | `CHECKBOX` / `NUMERIC` |
| `startValue` / `targetValue` | TEXT | 精确十进制；≤ 3 位小数；目标大于起始 |
| `unit` | TEXT | 可空；≤ 10 字符 |
| `recurrence` | TEXT | `ONE_TIME` / `DAILY` / `WEEKLY` / `SELECTED_WEEKDAYS` / `EVERY_N_DAYS` |
| `weekdays` | TEXT | 可空；ISO 星期号逗号分隔 |
| `everyNDays` | INTEGER | 可空；1–365 |
| `sortOrder` | INTEGER | 非空 |

**`ONE_TIME` 的语义**：模板中的一次性任务只适用于短期单。它没有发生记录，也不会被
映射成 `DAILY`；托管订单在创建时只取周期任务，若模板没有周期任务则明确提示，不静默
改写。短期订单的任务保存 `ONE_TIME`，但短期单本来就不生成发生记录，因此不影响既有
的打卡语义。

### `template_seed_state`

| 列 | 类型 | 约束 |
|---|---|---|
| `seedKey` | TEXT | 主键；稳定种子键；历史游戏键用于旧安装清理，当前新装仅写入 `generic` |
| `appliedAt` | INTEGER | 非空 |

初始化只对“没有标记”的键执行一次。标记在模板被用户删除后仍然保留，因此下次启动
**不会复活**已删除的内置模板，也不会覆盖用户编辑过的内容。

### 业务表中的模板相关列（全部可空）

| 表 | 列 | 语义 |
|---|---|---|
| `game_accounts` | `defaultTemplateId` | 新建订单时预选的模板；**不是外键**。删除模板时在同一事务内清空；悬空值按“无默认模板”处理 |
| `orders` | `sourceTemplateId` | 创建时选用的模板 ID（纯文本，不是外键） |
| `orders` | `sourceTemplateName` | 创建时的游戏名称快照 |
| `orders` | `sourceTemplateVersion` | 创建时的模板版本快照 |

历史订单/账号保持 NULL：不伪造模板版本，也不强制重新绑定模板。模板来源只是说明性
标签，不参与价格、完成、续期或日历计算；命令指纹也**不包含**它，因此进程恢复后的
重试仍是同一条建单命令。

### 模板与订单的快照边界

- 应用模板只填充**表单默认值**：任务草稿、时区、每日/每周刷新时间。
- 建单事务把任务写入 `task_definitions`、刷新规则写入 `orders` / `hosting_periods`，
  并写入上面的来源列；模板行不参与订单读取。
- 续期从旧期任务复制新 ID 的任务快照，与模板无关。
- 编辑/替换/删除模板不会产生任何针对订单、期次、发生记录、收款或附件的写操作。
- 删除模板只会删除模板内容、清空账号的 `defaultTemplateId`，并保留 `template_seed_state`。

## 5. 日期与周期语义（实现于 `core/time/GameCalendar.kt`、`data/model/OccurrenceRules.kt`）

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

## 6. 仓储接口与事务边界

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

interface ReceiptRepository {
    fun observeReceiptHistory(): Flow<List<ReceiptHistoryEntry>>
    suspend fun recordPayment(request: PaymentRequest): PaymentResult
    suspend fun correctReceipt(request: ReceiptCorrectionRequest): CorrectionResult
}

interface StatisticsRepository {
    fun observeStatistics(range: StatisticsRange, zone: ZoneId, now: Instant): Flow<Statistics>
    suspend fun loadStatistics(range: StatisticsRange, zone: ZoneId, now: Instant): Statistics
}

/** Transactionally consistent read model shared by stats and receipt history. */
class LedgerSnapshotSource {
    fun observe(): Flow<LedgerSnapshot>
    suspend fun load(): LedgerSnapshot
}

interface GameTemplateRepository {
    fun observeLibrary(): Flow<List<GameTemplateSummary>>
    fun observeTemplate(templateId: String): Flow<GameTemplate?>

    suspend fun findTemplate(templateId: String): GameTemplate?
    suspend fun findAllSummaries(): List<GameTemplateSummary>
    suspend fun findNameConflict(gameName: String): GameTemplateSummary?

    /** Installs missing built-ins once per durable seed key. */
    suspend fun ensureBuiltInTemplates()

    suspend fun saveTemplate(draft: TemplateDraft, templateId: String? = null): String
    suspend fun deleteTemplate(templateId: String)

    /** All-or-nothing: validation happens before the single write transaction. */
    suspend fun importTemplate(
        content: TemplateFileContent,
        resolution: TemplateImportResolution,
    ): ImportedTemplate
}

/** SAF document streams; the ViewModel never touches a ContentResolver or a File. */
interface TemplateFileRepository {
    suspend fun read(uri: Uri, maxBytes: Int = TemplateFileCodec.MAX_FILE_BYTES): ByteArray
    suspend fun write(uri: Uri, bytes: ByteArray)
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

**收款与更正**：

- 收款、更正、建单首笔收款共用 `ReceiptLedger`：先做幂等比较，再校验余额与关系，
  最后在事务内写入。重试发生在余额校验之前，因此“已结清后的相同重试”仍返回原收款。
- 托管收款必须显式给出属于该订单的 `periodId`；短期单收款不得携带期次。
  期次属于别的订单、缺失期次或短期单带期次都会被拒绝，且不写入任何行。
- 金额使用整数分；精度超过两位、为 0、为负或超过上限的输入在写库前拒绝。
  `ONE_TIME` 只接受“恰好结清当前款项”的金额；`INSTALLMENTS` 接受不超过余额的部分收款。
- AMEND 的可收上限 = 该款项价格 − 其他有效收款；超过上限拒绝。
- 更正审计行与状态变更在同一事务内完成；重试相同更正返回原审计，不新增行。

**财务快照一致性**：

- `LedgerSnapshotSource` 不再 `combine` 多个独立 DAO Flow，而是在**一个**
  `database.withTransaction` 中读取订单、期次、收款、更正、账号与老板。
- Room 的表失效仍会触发重读（六张表的失效信号合并），任何写入后都会得到一个新的
  完整版本；没有 debounce/延迟掩盖竞态。
- 统计页与收款明细页从这份全局快照派生。订单详情**不**复用该快照：它按订单读取
  自己的订单、账号、老板、任务、发生记录、截图、期次、收款与更正，同样在**一个**
  `withTransaction`（`OrderRepository.readOrderDetail`）内完成，因此每次详情发射
  也是一个完整的数据库版本（收款必有期次、余额等于有效收款之和），但它与全局快照
  是两次独立事务，不声称是同一快照实例。
- 订单详情的持续订阅一致性由 `OrderDetailConsistencyTest` 覆盖；统计/流水/台账的
  跨表一致性由 `LedgerSnapshotConsistencyTest` 覆盖。
- 报表时区：月份窗口按用户时区计算 `[startInclusive, endExclusive)`；实收按
  `receivedAt` 归属，订单数量按 `createdAt` 归属，欠款为全时段金额。
- 归档只影响“工作写入”，不影响读模型与收款；已完成/已到期/已归档订单的欠款
  仍在待收列表中，并可继续收款。

## 7. 截图文件与失败处理

- 来源：`ActivityResultContracts.PickVisualMedia`（系统照片选择器），不申请宽泛存储权限。
- 流程：读取 `contentResolver` → 写入 `files/attachments/<uuid>.tmp` → 移动/复制为
  `files/attachments/<uuid>.<ext>` → 在数据库事务中插入引用。数据库失败则删除新文件。
- 替换：先生成新文件，再在同一事务中插入新行并删除旧行；任一步失败则删除新文件，
  旧行与旧文件保持不变。成功后旧文件才删除。
- 删除：先删除数据库引用，事务提交后再删除文件；失败遗留文件由
  `cleanupOrphans()` 清理（应用启动时调用）。
- 取消选择（`null` URI）或来源不可读：不写任何行，不产生“成功但损坏”的附件。
- 清理只删除未被引用的最终文件与超过 1 小时的临时文件。

## 8. 迁移与测试

- `MIGRATION_1_2`：两条 `ALTER TABLE ... ADD COLUMN archiveBatchId TEXT`，不重写数据。
- `MIGRATION_2_3`：创建 7 张 Phase 2 表、全部显式命名索引与外键；不修改任何旧行。
- `MIGRATION_3_4`：只创建 `receipts` 与 `receipt_corrections`（含唯一索引、外键、
  `replacedByReceiptId` 索引）。**不插入任何收款行**：历史订单的约定价格不等于已收。
- `MIGRATION_4_5`：只创建 `order_creation_operations`（唯一索引 + 指向 `orders` 的
  `RESTRICT` 外键）。**不为历史订单补写**操作行。
- `MIGRATION_5_6`：创建 `game_templates`、`template_categories`、`template_tasks`、
  `template_seed_state` 及唯一/普通索引、外键；给 `game_accounts` 加可空
  `defaultTemplateId`，给 `orders` 加三个可空来源列。**不插入任何模板内容**，不重写
  历史订单/账号；内置模板由仓储按种子标记安装。
- `MigrationV1ToV2Test`：用提交的 `1.json` 建 v1 库，写入真实行，执行全部迁移到当前
  版本；编号、凭据、归档时间保留，批次列为 NULL；生产同款 builder 打开旧库。
- `MigrationV2ToV3Test`：用提交的 `2.json` 建 v2 库，含显式 `archiveBatchId`；升级后
  旧行逐字段保留，新表为空；新外键 `RESTRICT` 生效。
- `MigrationV3ToV5Test`：用提交的 `3.json` 建 v3 库，写入真实老板、账号、短期/托管
  订单、期次、任务、发生记录、完成批次/明细与附件引用；升级后逐项保留，三类新表为空，
  新外键生效。另有 `4.json` → v5 用例，确认既有订单不会得到虚构的建单操作行。
- `MigrationV5ToV6Test`：用提交的 `5.json` 建 v5 库，写入 Phase 3 的老板、账号、
  短期/托管订单、期次、任务、发生记录、完成批次、附件、有效收款、更正审计与建单操作行；
  升级后逐项保留，四张模板表为空，历史订单来源列为 NULL、账号默认模板为 NULL。
  另有“模板删除不影响订单”的真实 SQL 用例。
- `MigrationInfrastructureTest`：遍历 1..当前版本的所有导出 schema，升级并校验数据。
- 真实 Room 测试见 `OrderRepositoryTest`、`HostingOccurrenceTest`、
  `BatchCompletionTest`、`RenewalTest`、`AttachmentTest`、
  `Phase3FinancialContractTest`、`LedgerSnapshotConsistencyTest` 与
  `Phase3AcceptanceReviewTest`。
- Phase 4 模板测试：`GameTemplateRepositoryTest`（初始化幂等、编辑保留、删除不复活、
  重启持久化、账号默认清理、同名另存/替换、非法导入零写入、ID 冲突重映射）、
  `TemplateOrderSnapshotTest`（通用模板建托管/短期单、完成任务、编辑/替换/删除后
  订单快照不变、旧账号旧订单无需模板）、`BuiltInTemplateContentTest`（资产可解析、
  顺序、刷新时刻、五种周期）、`TemplateFileExchangeTest`（UTF-8 往返、超限、不可读文档）。
- JVM：`TemplateDraftTest`、`TemplateFileCodecTest`、`NewOrderTemplateDefaultsTest`、
  `AccountTemplateSelectionTest`。

## 9. 尚未实现（不是本阶段的隐含承诺）

- 应用锁/本机身份验证：产品明确不要求。
- 模板文件是单个模板的交换格式，**不是业务备份**；完整备份/恢复使用独立的 `daigan-backup` 格式。
- 模板在线商店、自动从网络更新模板、跨设备同步、导出/分享报表、云服务与发布签名。
- 深眠域等“双周”或“每月两次”的节奏没有专用周期类型；只能用可编辑的周期近似。


## 10. Phase 5 本机设置与资料替换

迁移 6→7 只新建三张表，不重写任何旧业务行：

| 表 | 主键 | 用途 | 完整备份 |
|---|---|---|---|
| reminder_settings | id=0 | 三类提醒各自开关、手机本地分钟数、更新时间 | 包含 |
| app_settings | id=0 | datasetInstanceId、lastRestoreId、createdAt | 排除，本机重建 |
| reminder_deliveries | category + day | 当地日期成功投递记录，避免重复提醒 | 排除，恢复后清空并重排 |

无设置行时提醒全部关闭。每类时间为 0..1439 分钟；业务资格仍使用订单/期次的游戏时区及日/周刷新快照。

完整备份包含 18 张业务表的原始标识、操作记录、金额、时间、枚举和精确十进制文本，并包含附件文件字节。`app_settings` 与 `reminder_deliveries` 是明确排除的本机协调状态；不备份 Room、SQLite 或 WorkManager 的内部表。

`DatasetGate` 在 Room 事务前取得共享许可；导出和恢复使用串行排他许可。完成替换时递增代次，拒绝旧资料期间排队的写入。取消不泄漏许可；嵌套仓库事务复用当前协程许可。附件发布另由 AttachmentSync 串行保护。

恢复提交在一个 Room 事务里整体删除/重建业务行和提醒设置，并写入新的本机 datasetInstanceId 和 lastRestoreId；文件系统使用阶段日志与目录重命名配合。启动恢复成功前页面及 Worker 不开放。MainActivity 按资料身份重建导航，拒绝恢复旧资料的 Activity 状态。损坏恢复日志及未完成的回滚会锁定资料并提供重试，不自动清库。

参见 `docs/backup-format.md`、`docs/backup-recovery.md`、`docs/reminder-behavior.md`。迁移验证入口 `MigrationV6ToV7Test`，包括旧业务表逐列保留、全历史 schema 链及新旧建库设置一致性。
