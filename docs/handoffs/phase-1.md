# Phase 1 交接报告

日期：2026-10-17
状态：实现完成，等待外部验收。未开始第二阶段。

> 后续修复：独立验收退回的 R1/R2 问题已由 `docs/handoffs/phase-1-fix-1.md` 修复。
> 本文第 4 节“归档老板与其账号共享时间戳，恢复时只还原同一时间戳的账号”、第 7 节第 3 条
> 以及第 4 节“数据库版本 1”的描述**已被该修复取代**（现为数据库版本 2，按显式归档批次恢复）。
> 其余内容仍为当时的历史记录，未改写。

## 1. 本阶段交付内容

- 以 Google `android/architecture-templates`（`base` 分支，固定提交
  `cdb6de476e1889c88947946c28d900b3537dc867`）为真实源码底座，导入并改造为单 `app` 模块的
  `com.daigan.record`，保留 Compose、ViewModel、Flow、Room、Hilt、Navigation Compose 与测试骨架。
- 真实 Room/SQLite 持久化（schema 版本 1）：老板、游戏账号、老板编号序列；导出 schema 入库；
  无 `fallbackToDestructiveMigration`。
- 老板与游戏账号管理：新增/编辑老板昵称、不可变自动编号、多账号、凭据查看与复制（无额外验证门槛）、
  归档与恢复、独立归档记录页。
- 导航：`今日事项`（默认，含 `托管打卡` / `短期任务` 标签）、`账号信息`、`数据统计` 三个主页面与底部导航；
  右上角设置进入**独立**页面（有返回、无底部导航），含 `收款明细`、`游戏模板`、`提醒设置`、`备份与恢复` 四个入口。
- 未实现功能全部展示诚实的空/待实现状态，生产数据库没有写入任何演示订单、收入或打卡。
- `docs/upstream-foundation.md`（上游来源、工具链、基线验证、改动清单）与 `docs/data-model.md`
  （已实现表结构 + 后续阶段关系设计）。

明确**未实现**：订单、托管期次、打卡、历史补录、截图附件、续期、收款、欠款、统计、模板编辑与导入导出、
提醒、备份恢复、应用锁/生物识别（产品上也不需要）。

## 2. 上游底座

| 项 | 值 |
|---|---|
| 来源 | https://github.com/android/architecture-templates（`base`） |
| 固定提交 | `cdb6de476e1889c88947946c28d900b3537dc867`（2025-11-20，`Update all dependencies (#189)`） |
| 未采用的 HEAD | `5467a8dcca30a81b4e31fda5863dd6c65836de0d`：AGP 9.4.1 / compileSdk 37 / Navigation 3，本机无 SDK 37 且无法下载 |
| 许可证 | Apache License 2.0，`LICENSE` 原样保留；派生文件保留 AOSP 版权头 |
| 基线验证 | 未修改源码在 `/tmp/arch-baseline` 构建：`BUILD SUCCESSFUL in 6m 48s`（`assembleDebug` + `testDebugUnitTest`） |

工具链：Gradle 9.2.1、AGP 8.13.1、Kotlin 2.2.21、KSP 2.3.2、Compose BOM 2025.11.01、Room 2.8.4、
Hilt 2.57.2、Navigation Compose 2.9.6、JDK 21.0.4、compileSdk/targetSdk 36、minSdk 26、build-tools 36.1.0。
版本细节与全部改动见 `docs/upstream-foundation.md`。

## 3. 项目结构与边界

```
app/src/main/java/com/daigan/record/
├── MainActivity.kt / RecordApplication.kt
├── core/time/TimeProvider.kt
├── data/local/           RecordDatabase, entity/, dao/, converter/, di/DatabaseModule
├── data/model/           Owner, GameAccount, AccountDraft, LoginMethod, OwnerValidation, OwnerNumber
├── data/repository/      OwnerRepository, GameAccountRepository（接口 + Default 实现）
├── data/di/DataModule.kt
├── feature/accounts/     AccountsScreen/VM, OwnerDetailScreen/VM, OwnerFormScreen/VM,
│                         AccountFormScreen/VM, ArchivedRecordsScreen/VM, AccountsUiState
├── feature/today/        TodayScreen/VM（空状态）
├── feature/stats/        StatsScreen（空状态）
├── feature/settings/     SettingsScreen, PendingFeatureScreen
└── ui/                   navigation/, components/, theme/
```

数据流：Compose → ViewModel → Repository → DAO → Room。ViewModel 只依赖 Repository 接口，
不接触 DAO，也不读写附件文件。所有写操作是 `suspend`，读取是 `Flow`。

### Repository 接口

```kotlin
interface OwnerRepository {
    fun observeActiveOwners(): Flow<List<OwnerSummary>>
    fun observeArchivedOwners(): Flow<List<OwnerSummary>>
    fun observeOwner(ownerId: String): Flow<OwnerDetail?>
    suspend fun findOwner(ownerId: String): Owner?
    suspend fun createOwner(nickname: String): Owner          // 事务内分配不可变编号
    suspend fun renameOwner(ownerId: String, nickname: String) // 编号不变
    suspend fun archiveOwner(ownerId: String)                  // 老板 + 当前账号同一时间戳归档
    suspend fun restoreOwner(ownerId: String)                  // 只恢复随老板归档的账号
}

interface GameAccountRepository {
    fun observeArchivedAccounts(): Flow<List<ArchivedAccount>>
    suspend fun findAccount(accountId: String): GameAccount?
    suspend fun createAccount(ownerId: String, draft: AccountDraft): GameAccount
    suspend fun updateAccount(accountId: String, draft: AccountDraft): GameAccount
    suspend fun archiveAccount(accountId: String)
    suspend fun restoreAccount(accountId: String)
}
```

领域模型：`Owner(id, number, nickname, createdAt, archived)`、`GameAccount(id, ownerId, gameName,
server, uid, loginAccount, loginMethod, password, createdAt, archived)`、`AccountDraft`（含
`normalized()` 与 `validate()`）、`OwnerSummary`、`OwnerDetail`、`ArchivedAccount`。

## 4. 数据库与迁移测试

- 数据库：`app/src/main/java/com/daigan/record/data/local/RecordDatabase.kt`，版本 1。
- 表：`owners`、`game_accounts`、`owner_number_sequence`。
- `owners.number` 唯一索引；编号由单行计数器在**同一事务**内 `UPDATE → SELECT → INSERT` 分配，
  唯一索引作为并发最后防线（失败整体回滚并有限重试）。单测见下。
- `game_accounts.ownerId` 外键 → `owners.id`，`ON DELETE RESTRICT`；索引 `ownerId`、`(ownerId, archivedAt)`。
- 归档代替删除：无删除入口；归档老板与其账号共享时间戳，恢复时只还原同一时间戳的账号。
- 密码规则：仅密码登录存储密码；切换验证码登录时仓储层写 NULL；验证码从不入库。
- 导出 schema：`app/schemas/com.daigan.record.data.local.RecordDatabase/1.json`（已提交）。
- 迁移基础设施：`MigrationInfrastructureTest` 将已提交的 v1 schema 作为**隔离的历史夹具**，
  用 `MigrationTestHelper.createDatabase(..., 1)` 建立数据库、写入原始 SQL 行，再
  `runMigrationsAndValidate(..., 1, true)` 校验当前 schema 且数据完好；另断言 schema 资源存在。
  Phase 1 没有生产版本升级，因此**未伪造**数据库版本提升；后续版本按同一夹具模式追加真实迁移测试。
- `androidTest` 资源目录指向 `app/schemas`（`app/build.gradle.kts` 的 `sourceSets`），供测试读取历史 schema。

## 5. 实际执行的验证

环境：macOS arm64；JDK 21.0.4（Microsoft，`JAVA_HOME` 显式指定；系统默认 `java` 为 24，不用于构建）；
Android SDK Platform 36.1、build-tools 36.1.0；模拟器 AVD `Medium_Phone_API_36.1`（Android 16 / API 36.1）。
无物理设备。

### 5.1 单元测试

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew :app:testDebugUnitTest
```

结果：**28 个测试，0 失败，0 错误**。覆盖：编号格式 B001/B1000、昵称 30 字限制、草稿归一化与
密码/验证码规则、长中文取值上限、ViewModel 的创建/编辑/校验/切换登录方式/保存完成状态、
老板列表状态、今日事项标签与注入日期。

### 5.2 Lint

```bash
./gradlew :app:lintDebug
```

结果：**0 error，28 warning**。警告均为：依赖有新版本提示（`GradleDependency` 12、
`NewerVersionAvailable` 12、`AndroidGradlePluginVersion` 2，属固定版本策略下的提示）与
`ObsoleteSdkInt` 2（上游 `drawable-v24` / `mipmap-anydpi-v26` 目录在 minSdk 26 下冗余，纯资源提示）。
没有为变绿而抑制或删除检查。

### 5.3 Debug APK

```bash
./gradlew :app:assembleDebug
```

结果：`BUILD SUCCESSFUL`。

- 绝对路径：`/Users/francis/Documents/Project/代肝记录软件/app/build/outputs/apk/debug/app-debug.apk`
- 大小：19,744,849 字节（debug 签名，未混淆）
- SHA-256：`9975a7b5e1994a0f87f471c67abd6bc74f4ffffeb904cfd7c01ec92d9617b7fb`

### 5.4 设备（模拟器）测试

```bash
./gradlew :app:connectedDebugAndroidTest
```

结果：**35 个测试在 `Medium_Phone_API_36.1(AVD) - 16` 上全部通过**，`BUILD SUCCESSFUL`。

测试类与覆盖：

| 测试 | 覆盖 |
|---|---|
| `OwnerRepositoryTest`（真实 Room，文件库） | 20 个并发创建得到唯一连续编号 1–20；改昵称不改编号；同老板多账号；跨老板账号隔离；密码→验证码后数据库密码为 NULL；归档/恢复老板连同账号；单账号归档/恢复；账号必须属于存在的老板；归档后编号不复用 |
| `RecordDatabaseTest`（真实 Room） | 外键拒绝无主账号；编号唯一索引拒绝重复；RESTRICT 阻止删除有账号的老板；关闭并重开数据库后记录仍在；活动/归档查询分离；按老板计数隔离；序列递增 |
| `MigrationInfrastructureTest` | v1 历史 schema 夹具创建 + 原始数据 + schema 校验；schema 资源存在 |
| `NavigationTest`（Hilt + 空内存库） | 默认进入今日事项、两个标签切换；账号信息仅“新增”创建入口且无“新增订单”；设置独立页无底部导航；设置子页待实现文案与返回；数据统计无金额 |
| `AccountsScreenTest` | 空状态；按老板分组显示编号与账号摘要；30 字中文昵称 + 2 倍字体下关键元素仍在 |
| `OwnerDetailScreenTest` | 密码默认隐藏、可显示与复制；验证码账号不显示密码；接单入口存在但禁用并标注第二阶段；“暂无订单”空状态；长游戏名 + 2 倍字体下操作仍可见；归档老板显示恢复 |
| `PersistenceProbeTest` | 向**真实应用数据库文件**写入老板+账号，供跨进程复核（见 5.5） |

### 5.5 进程重启与界面级持久化（人工执行，模拟器）

1. 安装最终 APK，界面创建老板 `FinalCheck` → 列表显示 `FinalCheck` / `B001`。
2. `adb shell am force-stop com.daigan.record`，再 `am start` 重新启动，进入账号信息：
   `FinalCheck`、`B001`、`暂无游戏账号` 仍在（截图：`/tmp/phase1-accounts-light.png`，
   深色模式：`/tmp/phase1-today-dark.png`，未入库）。
3. 更完整的界面流程（较早同一份代码的 APK，SHA-256 `8a357b5b…`）：新增老板 `UITestOwner`（B001）
   → 新增账号 `Wuthering / CN / 1001 / acc1`（验证码登录）→ 详情显示账号、禁用的“为这个账号接新单”
   与“暂无订单” → 强制停止并重启 → 列表显示“1 个游戏账号 · Wuthering” → 归档老板 → 列表清空、
   “归档记录 (1)” → 归档记录页恢复老板 → 老板与账号回到列表。
4. 跨进程数据库探针（两次独立 instrumentation 进程）：

```bash
adb shell am instrument -w -e class 'com.daigan.record.data.PersistenceProbeTest#aSeedData' \
  com.daigan.record.test/com.daigan.record.HiltTestRunner   # OK (1 test)
adb shell am force-stop com.daigan.record
adb shell am instrument -w -e class 'com.daigan.record.data.PersistenceProbeTest#bVerifyDataPersisted' \
  com.daigan.record.test/com.daigan.record.HiltTestRunner   # OK (1 test)
```

两个进程分别返回 `OK (1 test)`，证明写数据后**进程终止**再读仍存在（区别于仅关闭/重开数据库）。

### 5.6 一次完整命令

最终代码上执行：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest
```

结果：`BUILD SUCCESSFUL in 1m 48s`，35 个设备测试通过，28 个单元测试通过，lint 0 error。

## 6. 未运行的测试及原因

| 未运行项 | 原因 |
|---|---|
| 物理设备测试 | 环境无可用物理设备，仅有 AVD |
| API 26–35 运行时测试 | 本机只安装 API 36.1 系统镜像，未下载其他 API（`dl.google.com` 不可达） |
| 端到端“表单创建”自动化 UI 测试 | 表单保存路径由 ViewModel 单测 + 仓储真实 Room 测试 + 人工 adb 流程覆盖，未写成自动化 UI 用例 |
| 今日事项/数据统计/设置页的大字体 UI 测试 | 大字体仅对账号列表与老板详情有自动化断言，其余页面只做人工检查 |
| 迁移“旧版本→新版本”测试 | Phase 1 只有 v1，没有更早的生产版本；基础设施已用 v1 历史夹具验证，未伪造升级 |
| Release 构建与签名 | 本阶段只要求可构建的 debug 测试包；正式签名属于发布前工作 |
| 备份恢复、通知、截图附件 | 不在 Phase 1 范围 |

## 7. 已知限制

1. **凭据以明文保存在应用私有 SQLite**：无加密、无应用锁/生物识别（产品明确不设身份验证门槛）。
   Android 自动云备份与设备迁移已关闭（`allowBackup=false` + 备份规则排除全部业务域），
   可选加密备份属 Phase 5。访问设备文件系统的攻击者可以读取密码。
2. **密码显示**仅做界面遮挡，点击“显示”即可查看/复制，符合“无需额外验证”的产品要求。
3. **归档粒度**：归档老板会连同其当前账号一起归档；恢复老板只恢复同一归档时间戳的账号。
   归档中的老板不支持单独编辑账号，需先恢复。
4. **列表不分页/不搜索**：个人单机规模下的简化实现，数据量很大时首屏渲染成本上升。
5. **APK 体积**：引入 `material-icons-extended` 后 debug 包约 19.7 MB；如需更小体积可在后续替换为自绘图标。
6. **单语言**：界面中文；除 `app_name` 外文案写在 Compose 代码中，未建立完整 `strings.xml` 资源体系。
7. **构建环境依赖**：需要 JDK 21；本机 `dl.google.com` 不可达，通过 `~/.gradle/init.d/` 的本地镜像脚本
   解析 Google Maven（不随仓库提交、不改版本）。正常网络环境可直接使用项目仓库配置。
8. 模拟器上保留了人工测试数据（`FinalCheck`、`UITestOwner` 等），仅存在于测试设备，不在仓库中。

## 8. Git 与工作区

- 基线导入提交：`98bf36d`（`chore: import android/architecture-templates base @ cdb6de4`）
- 本阶段实现提交：`b4fe60f70b867182c73cadc104eb404ed7721520`
  （`feat(phase-1): persistent owner and game-account foundation`，89 个文件变更）
- 本交接文档在实现提交之后单独提交，便于把已核实的 SHA 写入报告。
- 提交后工作区应为干净状态；`local.properties`、`app/build/`、`.gradle/`、`.kotlin/` 均被忽略。
  未提交任何签名密钥、密码或私有账号资料。

## 9. 下一阶段接口约定（供 Phase 2 使用）

- 订单必须从老板详情中的具体游戏账号发起；现有账号卡片已放置**禁用**的“为这个账号接新单”入口与说明，
  Phase 2 只需替换为可用导航与真实建单流程。
- 老板 `id` 为稳定 UUID，`number` 只增不改；账号 `id` 稳定，`ownerId` 外键为 RESTRICT，
  后续订单表应引用 `game_accounts.id`，不得删除老板/账号（继续用归档）。
- `TimeProvider` 已通过 Hilt 注入，游戏日期/时区规则应在 `core/` 中扩展，测试注入固定时钟。
- 金额请使用整数分；业务表通过 Room `Migration` 增量添加，并同步更新 `docs/data-model.md` 与迁移夹具测试。
