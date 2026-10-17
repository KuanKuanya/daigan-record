# Phase 1 修复交接 · fix-1

日期：2026-10-17
状态：**修复完成，交回独立复验。未自我标记为通过，未进入第二阶段。**
被修复基线：`b1716cf`（实现提交 `b4fe60f`），修复提交：`d75fad55b0fd9a7bbf4fdaca78624c0f68a4084e`。
工作区中未提交的 `docs/handoffs/phase-1-review.md` 原样保留，未纳入任何提交。

## 1. 修复内容一览

| 验收项 | 修复 |
|---|---|
| R1 用归档时间戳充当批次身份 | 新增显式持久化归档批次 `archiveBatchId`；老板恢复按批次恢复账号，不再比较 `archivedAt` |
| R1 重复请求覆盖批次 | 归档/恢复均为幂等空操作；SQL 与仓储事务内双重守卫，批次不会被重写或重新盖时间戳 |
| R2 父级归档时仍可单独恢复子账号 | 仓储在同一写事务内拒绝账号写入；UI 在老板归档时不提供任何可用账号操作 |
| R3 归档计数文案不准确 | 只声明总数与“其中 N 个随老板归档”，不再把独立归档账号算作随老板归档 |
| R3 新建标题 | `OwnerFormScreen` 新建标题改为“新增” |
| Schema 变更 | 数据库版本 2，保留 v1 schema，新增 `MIGRATION_1_2` 并注册到生产 builder |
| 旧数据 | 保守策略：不推测来源，恢复老板只恢复老板本身，账号保留归档并可逐个手动恢复 |
| 回归测试 | 真实 Room 回归 7 例、迁移 3 例、归档页 UI 4 例、老板详情 UI 用例修正 + 新增，另有 JVM 单测 |

## 2. 根因与实现

### 2.1 R1：归档归属不能由时间戳推断

**根因**：`OwnerRepository.restoreOwner` 把 `owner.archivedAt` 作为匹配条件，调用
`GameAccountDao.restoreArchivedWithTimestamp(ownerId, archivedAt)`。而 `archiveAccount` 与
`archiveOwner` 都使用 `timeProvider.now()` 的毫秒值，两个独立操作完全可能落在同一毫秒
（同一帧内的连续点击、时钟回拨、系统时间精度）。时间戳是被写入的数据，不是身份。

**修复**（`data/local/entity/*.kt`、`data/local/dao/*.kt`、`data/repository/OwnerRepository.kt`）：

- `owners.archiveBatchId`、`game_accounts.archiveBatchId`（TEXT，可空）。
- 归档老板：在写事务内生成 `UUID` 批次，先写老板行，再把**当时仍未归档**的账号写入同一批次
  （`WHERE ownerId = :ownerId AND archivedAt IS NULL`）。已经单独归档的账号不会被“收养”。
- 恢复老板：读取老板行的批次后再清理，只恢复
  `ownerId = :ownerId AND archiveBatchId = :batchId AND archivedAt IS NOT NULL` 的账号。
  时间戳不再参与判断。
- 单独归档账号：写 `archivedAt`，`archiveBatchId` 显式为 `NULL`（`archiveIndependently`）。
- 幂等：`archiveOwner` / `restoreOwner` / `archiveAccount` / `restoreAccount` 都先在事务内读取当前状态，
  状态不符直接返回；SQL 层同时带 `archivedAt IS NULL` / `IS NOT NULL` 守卫。因此重复请求既不报错，
  也不会新建批次、重盖 `archivedAt` 或清空已有批次。
- 领域模型：`GameAccount.archivedWithOwner` 由 `archiveBatchId != null` 派生（仅映射层使用），
  UI 不再需要接触列名。

### 2.2 R2：父级归档期间子账号只读（仓储为最终防线）

**根因**：`GameAccountRepository.restoreAccount` 不检查老板状态，`OwnerDetailScreen` 的
`AccountCard` 在 `readOnly` 分支无条件渲染可点击“恢复”按钮；旧 UI 用例还把该错误行为固化为断言。

**修复**：

- `GameAccountRepository` 新增私有 `requireActiveOwner(ownerId)`，在 `createAccount` /
  `updateAccount` / `archiveAccount` / `restoreAccount` 的 `withTransaction` 块内调用，
  失败抛出 `IllegalArgumentException(ARCHIVED_OWNER_ACCOUNT_MESSAGE)`，事务整体回滚，没有部分写入。
- `updateAccount` 额外拒绝“账号自身已归档”的编辑（`游戏账号已归档，请先恢复后再编辑`）。
- `ARCHIVED_OWNER_ACCOUNT_MESSAGE`（“老板已归档，请先恢复老板后再操作游戏账号”）是仓储与 UI 共用的常量。
- 过期表单：`AccountFormViewModel` 加载老板时记录 `ownerArchived`，`save()` 在发起写入前直接拒绝并给出
  同一提示；`AccountFormScreen` 显示说明并禁用保存按钮。即使表单是在归档前打开的，仓储事务仍会兜底拒绝。
- `OwnerDetailScreen.AccountCard` 改为三分支：
  - 老板已归档 → 只显示“已归档 · 老板归档期间只能查看”“恢复老板后可单独恢复此账号。”，**无任何按钮**；
  - 老板在用、账号已归档 → “恢复”按钮；
  - 都在用 → 原有“编辑账号 / 归档账号 / 禁用的接单入口”。
- 归档老板时仍保留头部“恢复老板”操作；老板恢复后，归档账号在详情与归档记录页可逐个恢复。
- `OwnerDetailScreenTest` 原 `archivedOwnerShowsRestoreInsteadOfEditing` 断言“存在子账号恢复按钮”，
  已改写为 `archivedOwnerOffersNoAccountActionsButKeepsTheAccountsReadable`：断言凭据仍可查看、
  `恢复` / `新增账号` / `编辑账号` / `归档账号` **均不存在**、`恢复老板` 仍可用；并新增
  `activeOwnerCanRestoreAnIndividuallyArchivedAccount` 覆盖“老板在用时可单独恢复”。

### 2.3 R3：文案

- `ArchivedRecordsScreen.archivedOwnerAccountSummary()`：
  - 全部随老板归档：`N 个随老板归档的游戏账号`；
  - 存在其他归档来源：`共 N 个游戏账号，其中 M 个随老板归档`（不再把其余账号说成随老板归档）；
  - 无账号：`归档时没有游戏账号`。
- 老板详情归档提示改为“该老板已归档：账号只能查看，恢复老板后可单独恢复账号。”
- `OwnerFormScreen` 新建标题：“新增老板” → “新增”。
- `ArchivedRecordsViewModel` 恢复成功提示由“已恢复老板及其账号”改为“已恢复老板”，避免对独立归档账号作出承诺。

## 3. Schema 与迁移

`app/src/main/java/com/daigan/record/data/local/RecordDatabase.kt`

- `RECORD_DATABASE_VERSION = 2`（同时供测试引用）。
- `MIGRATION_1_2`：仅两条
  `ALTER TABLE owners ADD COLUMN archiveBatchId TEXT` /
  `ALTER TABLE game_accounts ADD COLUMN archiveBatchId TEXT`。不改写任何已有行。
- `ALL_MIGRATIONS` 在 `DatabaseModule` 的 `Room.databaseBuilder(...).addMigrations(*ALL_MIGRATIONS)` 注册；
  仍然**没有** `fallbackToDestructiveMigration`。
- `app/schemas/.../1.json` 保持原样（与 `b1716cf` 完全一致，未修改）；新增并提交 `2.json`。
- `PersistenceProbeTest` 打开真实应用库时同样注册迁移（设备上可能是旧版本文件）。

### 旧数据（v1）归档来源策略（保守，已写入 `docs/data-model.md`）

- 所有 v1 行的 `archiveBatchId` 为 `NULL`，**不根据时间戳相等去猜测归属**；
- 恢复一个 v1 遗留的已归档老板时，只恢复老板本身，其账号全部保持归档（宁可少恢复，不误恢复）；
- 这些账号在老板恢复后仍可见、可逐个手动恢复，也可重新归档老板以建立新的明确批次；
- 界面只声明已知事实（总数 + 其中随老板归档数），不声称来源不明的账号“随老板归档”。

## 4. 回归测试与“先红后绿”证据

### 4.1 修复前（在 `b1716cf` 代码 + 新测试上执行）

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.data.repository.ArchiveOwnershipRegressionTest
```

`Starting 7 tests` → **4 failed / 3 passed**：

| 用例 | 失败信息（摘要） |
|---|---|
| `independentArchiveAtTheSameInstantIsNotOwnedByTheOwnerArchive` | `expected:<[withOwnerId]> but was:<[independentId, withOwnerId]>`（独立归档账号被误恢复） |
| `ownerRestoreKeepsIndependentlyArchivedAccountInTheArchivedList` | `expected:<[independentId]> but was:<[]>`（归档列表丢失该账号） |
| `repeatedAccountArchiveAndRestoreRequestsAreIdempotent` | 重复归档把 `archivedAt` 从 03:00 改写为 04:00 |
| `accountWritesAreRejectedWhileTheOwnerIsArchivedWithoutChangingStoredData` | `Expected the operation to be rejected, but it succeeded`（父级归档仍可写子账号） |

UI 用例：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.ui.OwnerDetailScreenTest
```

`Finished 6 tests` → **1 failed / 5 passed**：
`archivedOwnerOffersNoAccountActionsButKeepsTheAccountsReadable` 报
`Did not expect any node but found '1' node that satisfies: (Text ... contains '恢复')`，即归档老板下仍存在子账号“恢复”按钮。

### 4.2 新增/更新的测试（真实 Room 与 Compose）

- `app/src/androidTest/.../data/repository/ArchiveOwnershipRegressionTest.kt`（7 例，文件库真实 Room）：
  同一毫秒独立归档 + 老板归档；恢复后独立归档仍在；重复归档/恢复幂等且批次不变（直接断言实体相等，
  含 `archiveBatchId`）；三轮连续归档/恢复；父级归档时创建/编辑/归档/恢复全部拒绝且库内实体逐字段不变；
  归档老板下无法建账号；老板恢复后可单独恢复。批次断言还包括：老板批次非空、随老板归档账号批次相同、
  独立归档账号批次为 `NULL`、`OwnerSummary.accountsArchivedWithOwnerCount == 1`。
- `app/src/androidTest/.../data/local/MigrationV1ToV2Test.kt`（3 例）：以提交的 `1.json` 建 v1 库并写入
  2 位老板、3 个账号（含同一毫秒的歧义归档）、编号序列；迁移后校验版本 2、数据逐字段保留、批次列全为 `NULL`；
  以 `Room.databaseBuilder + ALL_MIGRATIONS`（生产同款）打开旧库，恢复遗留老板后两个账号仍归档，
  再逐个手动恢复成功；断言 v1 与 v2 schema 资源都存在。
- `app/src/androidTest/.../ui/ArchivedRecordsScreenTest.kt`（4 例）：归档计数只统计随老板归档的数量、
  全部随老板归档时的精确数量、无账号文案、独立归档账号在老板在用时可“恢复账号”。
- `app/src/androidTest/.../ui/OwnerDetailScreenTest.kt`（6 例，修正 1 例 + 新增 1 例）。
- `app/src/test/.../data/model/OwnerSummaryTest.kt`（2 例）：归档来源计数。
- `app/src/test/.../feature/accounts/AccountFormViewModelTest.kt`（新增 1 例）：老板已归档时的过期表单被拒绝，
  不产生 `createdDrafts`，`saveError` 为统一提示。
- `MigrationInfrastructureTest` 由“v1 夹具自我校验”升级为“v1 夹具 → 执行 `ALL_MIGRATIONS` → 校验当前 schema
  且数据完整”，并遍历所有已导出 schema 资源（检查没有被削弱）。
- `PersistenceProbeTest` 增加迁移注册。

## 5. 验证命令与结果

环境：macOS arm64；JDK 21.0.4（Microsoft，`JAVA_HOME` 显式指定）；Android SDK Platform 36.1 /
build-tools 36.1.0；AVD `Medium_Phone_API_36.1`（Android 16，API 36.1）。

### 5.1 单元测试

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew :app:testDebugUnitTest
```

结果：**31 个测试，0 失败，0 错误，0 跳过**（`app/build/test-results/testDebugUnitTest/*.xml` 汇总；
修复前基线 28 个）。

### 5.2 Lint

```bash
./gradlew :app:lintDebug
```

结果：**0 Error / 0 Fatal / 28 Warning**，与 `b1716cf` 基线数量一致。
警告构成：`GradleDependency` 12、`NewerVersionAvailable` 12、`AndroidGradlePluginVersion` 2、
`ObsoleteSdkInt` 2（全部为固定版本与上游资源的既有提示）。未抑制或删除任何检查。
（修复过程中引入的 `ModifierParameter` 警告已通过调整参数顺序消除。）

> 说明：一次把 `lintDebug` 与 `connectedDebugAndroidTest` 放在同一 Gradle 调用中时，lint 报了
> `Unexpected failure during lint analysis ... this is a bug in lint` 的分析器崩溃；单独重跑
> `:app:lintDebug` 正常完成。上文记录的是单独重跑的结果。

### 5.3 Debug APK

```bash
./gradlew :app:assembleDebug
```

结果：`BUILD SUCCESSFUL`。

- 绝对路径：`/Users/francis/Documents/Project/代肝记录软件/app/build/outputs/apk/debug/app-debug.apk`
- 大小：19,427,962 字节
- SHA-256：`6d80959dc58bd7a1e09edcde436b294dc8ed0d23cbf6c99037157bf6c429fcd0`

### 5.4 模拟器（真实 Room + Compose UI）

```bash
./gradlew :app:connectedDebugAndroidTest
```

结果：**50 个测试在 `Medium_Phone_API_36.1(AVD) - 16` 上全部通过**，0 failed / 0 skipped，
`BUILD SUCCESSFUL`（修复前基线 35 个）。按类分布：

| 测试类 | 数量 |
|---|---|
| `ArchiveOwnershipRegressionTest` | 7 |
| `MigrationV1ToV2Test` | 3 |
| `MigrationInfrastructureTest` | 2 |
| `RecordDatabaseTest` | 7 |
| `OwnerRepositoryTest` | 10 |
| `PersistenceProbeTest` | 2 |
| `ArchivedRecordsScreenTest` | 4 |
| `OwnerDetailScreenTest` | 6 |
| `AccountsScreenTest` | 3 |
| `NavigationTest` | 6 |

### 5.5 设备上的真实 v1 → v2 迁移与界面复核（手工，模拟器）

用提交的 v1 DDL 与 `room_master_table` 身份哈希在本地构造 v1 库（2 位老板、3 个账号、编号序列 3，
其中两个账号与老板归档时间同为 2000ms），安装 APK 后放入应用私有目录，再从界面打开“账号信息”
触发 Room 打开数据库：

1. `PRAGMA user_version` 由 **1 变 2**；无 Room/迁移异常日志，进程正常存活。
2. 数据逐项保留：`旧在用老板/B001`、`旧归档老板/B002`、两个账号的 `uid`、`loginMethod`
   （`PASSWORD` / `VERIFICATION_CODE`）、`password`（`pass-b` 保留、验证码账号为 NULL）、
   `archivedAt=2000`、`owner_number_sequence=3`。
3. 所有旧行 `archiveBatchId` 为 `NULL`（没有凭空生成来源）。
4. 归档记录页文案：`共 2 个游戏账号，其中 0 个随老板归档`（不声称随老板归档）。
5. 恢复该遗留老板后：两个账号**仍为归档**，出现在“已归档游戏账号”并可“恢复账号”（保守策略生效）。
6. 再次归档该老板后打开详情：显示“该老板已归档：账号只能查看，恢复老板后可单独恢复账号。”
   与每张卡的“已归档 · 老板归档期间只能查看 / 恢复老板后可单独恢复此账号。”，
   页面中 `text="恢复"` 命中数为 **0**，头部 `content-desc="恢复老板"` 仍存在。
7. 账号信息页顶部按钮文本为“新增”。

## 6. 未执行项（NOT RUN）

| 项 | 原因 |
|---|---|
| Release 构建与签名 | 本阶段只要求可构建的 debug 测试包；正式签名属于发布前工作 |
| 物理设备测试 | 环境无物理设备，仅有 AVD `Medium_Phone_API_36.1` |
| API 26–35 运行时测试 | 本机只安装 API 36.1 系统镜像 |
| 第二阶段能力（订单/收款/统计等） | 本次修复边界明确不含第二阶段，未新增任何此类代码 |
| 备份恢复、通知、截图附件 | 不在 Phase 1 与本次修复范围 |
| 自动化 UI 层的“过期表单”端到端用例 | 由 `AccountFormViewModelTest`（JVM）+ 仓储真实 Room 用例覆盖；未写完整导航级 UI 用例 |

## 7. 已知限制

1. **旧数据无法自动还原账号归属**：v1 只有时间戳，恢复遗留老板后其账号需要手动逐个恢复。
   这是有意选择（不误恢复优先），已写入 `docs/data-model.md`。
2. **“随老板归档”的唯一来源是 `archiveBatchId`**：任何绕过仓储直接改库的操作都不会被识别，
   但这不影响界面与正常流程。
3. **归档计数文案**：对“全部随老板归档”和“混合来源”给出两种措辞；旧数据一律按混合来源展示
   （`0 个随老板归档`），不会猜测。
4. **账号编辑**：账号自身已归档时仓储拒绝更新（UI 也不提供入口），需先恢复账号。
5. **并发**：一致性依赖 Room 写事务与 SQLite 单写者串行化；跨进程同时写入不会产生半个事务，
   但多窗口同时操作同一老板时，后提交者会看到最新状态并按最新状态拒绝或空操作。

## 8. Git

- 被修复基线：`b1716cf`（未改写历史）。
- 修复提交：`d75fad55b0fd9a7bbf4fdaca78624c0f68a4084e`
  `fix(phase-1): explicit archive provenance and parent archive consistency`
  （含代码、测试、`2.json`、`docs/data-model.md` 与 `docs/handoffs/phase-1.md` 的说明）。
- 本交接文档在修复提交之后单独提交，以便把已核实的 SHA 写入报告。
- `docs/handoffs/phase-1-review.md` 是验收方未提交的文件，**保持未跟踪、未修改**。
- 未提交任何签名密钥、密码或私有账号资料；模拟器上的验证数据仅存在于测试设备。

## 9. 停止点

R1/R2/R3 已按验收意见修复，回归测试、迁移测试、单测、Lint、APK 与模拟器测试均已执行并记录。
**Phase 1 是否通过由独立复验判定，本次不自评通过；未开始第二阶段。**
