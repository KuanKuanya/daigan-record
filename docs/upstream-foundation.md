# 上游底座与工具链记录

本文件记录 Phase 1 采用的官方架构底座、固定提交、验证证据，以及为满足产品与工具链约束所做的全部改动。
来源检查与构建日期：2026-10-17。

## 1. 上游来源

| 项目 | 值 |
|---|---|
| 仓库 | https://github.com/android/architecture-templates |
| 分支 | `base` |
| 固定提交 | `cdb6de476e1889c88947946c28d900b3537dc867` |
| 提交说明 | `Update all dependencies (#189)`（2025-11-20） |
| 许可证 | Apache License 2.0（仓库根目录 `LICENSE` 原样保留） |
| 获取方式 | `git clone --branch base https://github.com/android/architecture-templates.git`，随后 `git archive <sha>` 导入工作区 |

### 为什么不用分支最新提交

克隆时 `base` 分支 HEAD 为 `5467a8dcca30a81b4e31fda5863dd6c65836de0d`（2026-10-05，
`Update all dependencies (base) (#214)`）。该提交配置为：

- AGP 9.4.1、Kotlin 2.4.20、compileSdk / targetSdk 37、Navigation 3、Compose BOM 2026.09.00

本机只安装了 Android SDK Platform `android-36.1`（API 36.1）与 build-tools 36.1.0，
且本环境无法访问 `dl.google.com`（TCP 超时），无法下载 SDK 37。按
`docs/superpowers/plans/2026-10-17-architecture-foundation.md` 的要求，未为追随分支最新状态而升级工具链，
因此选择仍使用 Navigation Compose、compileSdk 36 的历史提交，并保留其整套导航与测试实现。

## 2. 底座基线验证（未修改业务代码）

基线在项目源码树之外的临时目录中构建（`/tmp/arch-baseline`，不属于交付物）：

```bash
git archive cdb6de476e1889c88947946c28d900b3537dc867 | tar -x -C /tmp/arch-baseline
export JAVA_HOME=/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

结果：`BUILD SUCCESSFUL in 6m 48s`，`54 actionable tasks: 54 executed`。Room schema 正常导出到
`app/schemas/android.template.data.local.database.AppDatabase/1.json`，说明 Schema 导出与 KSP 配置可用。
基线包含的 Hilt、Room、ViewModel、Flow、Navigation Compose 与测试骨架随后被沿用。

## 3. 固定版本组合

来自上游 `gradle/libs.versions.toml`（未跨项目混用版本目录）：

| 组件 | 版本 |
|---|---|
| Gradle Wrapper | 9.2.1 |
| Android Gradle Plugin | 8.13.1 |
| Kotlin | 2.2.21 |
| KSP | 2.3.2（Room 注解处理） |
| kapt | 随 Kotlin（Hilt 注解处理，沿用上游写法） |
| Compose BOM | 2025.11.01 |
| Room | 2.8.4 |
| Hilt | 2.57.2 |
| Navigation Compose | 2.9.6 |
| Lifecycle | 2.10.0 |
| Coroutines | 1.10.2 |
| JUnit | 4.13.2 |
| androidx.test core / ext / runner | 1.7.0 / 1.3.0 / 1.7.0 |

本阶段新增（同一版本目录，版本与其他条目一并固定）：

| 组件 | 版本 | 用途与理由 |
|---|---|---|
| `androidx.compose.material:material-icons-extended` | 由 Compose BOM 管理 | 使用与设计稿一致的导航与操作图标 |
| `androidx.room:room-testing` | 2.8.4（与 Room 同版本） | `MigrationTestHelper` |
| `androidx.test.espresso:espresso-core` | 3.7.0 | Compose UI 测试传递引入的 3.5.0 在 API 36 调用已移除的 `InputManager.getInstance()` 而崩溃 |
| `kotlinx-serialization-json` (androidTest) / `serialization-core` (implementation) | 1.9.0 | `room-testing` 的生成序列化器需要较新运行时；传递固定为 1.7.3 会导致 `AbstractMethodError`。Phase 4 起 JSON 运行时也用于模板文件编解码（`implementation`），版本不变，未引入序列化编译器插件 |

## 4. 本机环境

| 项 | 值 |
|---|---|
| 操作系统 | macOS（darwin 27.0.1, arm64） |
| 构建 JDK | Microsoft OpenJDK 21.0.4（`JAVA_HOME` 显式指定） |
| 系统默认 java | 24.0.2（未用于本构建） |
| Android SDK 平台 | `platforms/android-36.1`（Android 16，API 36.1） |
| Build Tools | 36.1.0 |
| 设备测试 | AVD `Medium_Phone_API_36.1`（API 36.1），无物理设备 |
| 网络限制 | `dl.google.com` 不可达 |

### 网络适配（不写入仓库）

`dl.google.com` 在本环境连接超时，Google Maven 依赖无法直接下载。为完成构建，本机在
`~/.gradle/init.d/google-mirror.init.gradle.kts` 中把 `google()` 重定向到公共镜像
`https://maven.aliyun.com/repository/google`。该文件**不在仓库内**，不改变任何依赖版本；
在可正常访问 Google Maven 的环境中，直接使用项目原有的 `settings.gradle.kts` 仓库配置即可。

## 5. 对上游源码的改动

保留的部分：单 `app` 模块、Compose Material 3、ViewModel + StateFlow、Repository 接口、Hilt
（`@HiltAndroidApp`、`@AndroidEntryPoint`、`@HiltViewModel`、`@Binds`、`@Provides` 模块）、
Navigation Compose（字符串路由 + `NavHost`）、`HiltTestRunner`、`TestInstallIn` 测试替换模式、
`collectAsStateWithLifecycle`、Room + KSP、`app/schemas` 导出目录、Apache 版权头。

替换/调整的部分：

1. **包名与应用身份**：`android.template` → `com.daigan.record`；`rootProject.name` → `DaiganRecord`；
   应用名 `代肝记录`；`minSdk` 23 → 26（产品默认值）；`targetSdk` / `compileSdk` 保持 36。
2. **示例业务代码**：删除 `MyModel`/`MyModelScreen`/`MyModelViewModel`/`MyModelRepository`/
   `AppDatabase` 等示例，替换为老板与游戏账号的实体、DAO、Repository、ViewModel 与页面。
   保留其分层方式与可测试性，不另建框架。
3. **数据库**：`RecordDatabase`（导出 schema，版本 1），无 `fallbackToDestructiveMigration`；
   `androidTest` 资源目录指向 `app/schemas`，供 `MigrationTestHelper` 读取历史 schema 夹具。
4. **依赖注入**：`DatabaseModule` 改为 `object`，`DataModule` 绑定两个 Repository 与 `TimeProvider`；
   ViewModel 只依赖 Repository 接口。
5. **主题**：沿用 Compose 主题结构，但改用设计稿固定品牌色（不再使用动态取色），主界面使用
   自定义 `PageHeader` 而非固定高度 `TopAppBar`，避免中文长标题与大字体被裁切；
   `MainActivity` 使用 `enableEdgeToEdge()`，删除已废弃的 `window.statusBarColor` 写法。
6. **Manifest**：`allowBackup=false`，并同时提供 `fullBackupContent` 与 `dataExtractionRules`，
   排除数据库、SharedPreferences 与文件域。原因：数据库保存老板的游戏登录凭据，不应进入系统云备份；
   显式可选加密备份属于 Phase 5。未声明 `INTERNET` 权限。
7. **图标与资源**：自适应图标补充 `monochrome` 层；启动窗口背景色区分深浅模式（`values-night`）。
8. **构建内存**：`org.gradle.jvmargs` 从 `-Xmx2048m` 提升到 `-Xmx4096m`，
   否则 `mergeExtDexDebug` 在引入 `material-icons-extended` 后出现 `OutOfMemoryError`。
9. **测试兼容性**：新增 Espresso 3.7.0 与序列化运行时 1.9.0（原因见第 3 节），
   其余测试依赖保持上游版本。

## 6. 版权与许可保留

- 仓库根目录 `LICENSE` 为上游 Apache License 2.0 原文，未修改。
- 直接派生自上游的文件保留原始 `Copyright (C) 2022 The Android Open Source Project` 头：
  `MainActivity.kt`、`RecordApplication.kt`、`ui/theme/{Color,Theme,Type}.kt`、`AndroidManifest.xml`、
  `app/build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`、
  `gradlew`/`gradlew.bat`、`gradle/wrapper/*`、资源文件与 `HiltTestRunner.kt`。
- 本阶段新增的业务与测试代码为项目自有文件，未复制其他示例项目的代码。

## 7. 未采用的做法

- 未使用 `5467a8d`（SDK 37 + Navigation 3）：工具链不可用，且与已安装 SDK 不兼容。
- 未混用 `architecture-samples`、`nowinandroid` 的 Gradle 配置或版本目录；仅参考其
  Repository/Flow 边界思想（见架构补充文档）。
- 未为了“看起来完成”而保留示例 `MyModel` 业务或写入演示数据。

## 8. 分叉与改名记录（2026-10-20）

本仓库是 `https://github.com/undefinedfrancis/daigan-record` 的 fork
（`https://github.com/KuanKuanya/daigan-record`），保留 Apache License 2.0 与上游版权声明。
本地远端约定：`origin` 指向 fork，`upstream` 指向原仓库，便于同步上游修复。

在 fork 基础上完成了一次**应用身份重构**：

| 项 | 上游 | 当前 |
|---|---|---|
| 应用名 | 代肝记录 | 代肝手账 |
| `namespace` / `applicationId` | `com.daigan.record` | `com.kuankuan.daigan` |
| `rootProject.name` | `DaiganRecord` | `DaiganHandbook` |
| Compose 主题函数 | `DaiganRecordTheme` | `DaiganHandbookTheme` |
| 资源样式 | `Theme.DaiganRecord` | `Theme.DaiganHandbook` |
| 启动图标 | 鼠尾草绿猫耳手账 | 靛蓝手账本（见 [图标设计](design/app-icon/README.md)） |

**未改动的兼容性标识**（刻意保留，使上游 v1.6 备份可在本版本恢复）：

- 备份信封 magic `DAIGANBK`、格式标识 `daigan-backup` / `daigan-backup-data`
- 模板交换格式 `daigan-template`
- 备份文件名前缀 `daigan-backup-`
- Room 数据库类名 `RecordDatabase` 与表结构、schema 版本

**需要注意的影响**：

- 换包名等于换了应用私有数据目录，新版本是**全新安装**，不会继承旧包名的数据。
  跨版本迁移必须走应用内的备份导出／恢复流程。
- 备份文件里的表数据不含包名，因此旧备份可以恢复到新包名下。
- Room schema 导出目录随包名改为 `app/schemas/com.kuankuan.daigan.data.local.RecordDatabase/`。
- 旧版发布包使用调试签名，与本仓库的正式签名 keystore 不同，**不能覆盖安装**。

## 9. 正式签名

发布用 keystore 不在仓库内。`app/build.gradle.kts` 从仓库根目录的 `keystore.properties`
读取签名配置（该文件已被 `.gitignore` 排除），也支持用环境变量
`DAIGAN_KEYSTORE` / `DAIGAN_STORE_PASSWORD` / `DAIGAN_KEY_ALIAS` / `DAIGAN_KEY_PASSWORD`
在其他机器与 CI 上提供同样的值。缺少这些值时 release 构建不签名，但仍可正常编译。

