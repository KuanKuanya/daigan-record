# 开发期测试指南

面向**改代码的人**：怎么在开发过程中快速验证改动。本文只讲"怎么测"，
业务规则见 [数据模型](data-model.md)、[提醒行为](reminder-behavior.md)。

## 0. 一分钟速查

| 我想干什么 | 命令 |
|---|---|
| 逻辑改错了？先跑纯 JVM 测试（**秒级，不用设备**） | `./gradlew :app:testDebugUnitTest` |
| 只跑某个测试类 | `./gradlew :app:testDebugUnitTest --tests '*ReminderRulesTest'` |
| 装到设备上看看 | `./gradlew :app:installDebug` |
| 改了 UI，想看真机效果 | `./gradlew :app:installDebug && adb shell am start -n com.kuankuan.daigan/com.kuankuan.daigan.MainActivity` |
| 跑设备测试（UI 自动化） | `./gradlew :app:connectedDebugAndroidTest` |
| 只跑一个设备测试类 | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kuankuan.daigan.ui.TodayScreenTest` |
| 静态检查 | `./gradlew :app:lintDebug` |
| 提交前全套 | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` |
| 出正式签名包 | `./gradlew :app:assembleRelease` |

**核心原则**：**改业务逻辑先跑单元测试，不要一上来就装设备。** 单元测试是纯 JVM，
跑 36 个文件只要几十秒；设备测试要编译安装启动，分钟级。

## 1. 四层测试回路

```
第 4 层  CI 托管环境      每次 push 自动跑     ~5 分钟     兜底
   ↑
第 3 层  connectedAndroidTest   真机/模拟器 UI 自动化   ~2-10 分钟   界面回归
   ↑
第 2 层  testDebugUnitTest      纯 JVM 逻辑测试         ~40 秒     业务规则
   ↑
第 1 层  手动点 + 截图 + 直查数据库   你自己的眼睛      实时       找感觉
```

### 第 1 层：手动验证

改完 UI 先用眼睛看。关键命令：

```sh
# 装最新 debug 包
./gradlew :app:installDebug

# 启动
adb shell am start -n com.kuankuan.daigan/com.kuankuan.daigan.MainActivity

# 截图（存到本地直接看）
adb exec-out screencap -p > /tmp/shot.png

# 强制停止再启动，模拟"冷启动"
adb shell am force-stop com.kuankuan.daigan
```

**清数据重来**（换包名后清的是新包的数据）：

```sh
adb shell pm clear com.kuankuan.daigan
```

### 第 2 层：单元测试（纯 JVM）

不需要设备，秒级。覆盖业务规则、日期计算、幂等、账务口径。

```sh
./gradlew :app:testDebugUnitTest              # 全部
./gradlew :app:testDebugUnitTest --tests '*HostingCalendarModelTest'
./gradlew :app:testDebugUnitTest --rerun-tasks  # 强制重跑（改完测试代码时）
```

报告：`app/build/reports/tests/testDebugUnitTest/index.html`

**写测试时**：测试放在 `app/src/test/java/com/kuankuan/daigan/`，
用**注入的 `TimeProvider`** 控制时间，不要用 `System.currentTimeMillis()`。
`core/time/TimeProvider.kt` 就是为这个设计的 —— 刷新边界、跨月、DST 都要靠它才能测。

### 第 3 层：设备测试（UI 自动化）

54 个测试文件，覆盖界面交互、数据库迁移、备份恢复、提醒权限。

```sh
# 全部（注意：会卸掉 app 和测试 apk）
./gradlew :app:connectedDebugAndroidTest

# 单个类 —— 开发时最常用
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kuankuan.daigan.ui.OrderDetailScreenTest

# 按类名过滤（可以匹配多个）
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kuankuan.daigan.ui.*ScreenTest
```

报告：`app/build/reports/androidTests/connected/debug/index.html`
XML：`app/build/outputs/androidTest-results/connected/debug/**/*.xml`

**几个必须单独跑的入口**：备份恢复的 6 个"进程死亡"检查点
（`BackupRestoreRecoveryTest#processDeathPrepare` / `#processDeathRecover`）
在普通全量套件里**有意跳过**，因为它们要主动杀进程。要单独跑：

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kuankuan.daigan.backup.BackupRestoreRecoveryTest#processDeathPrepare
```

### 第 4 层：CI

`push` / `pull_request` 自动触发：单元测试 → Debug 构建 → Lint → 上传 APK 产物。
workflow 在 `.github/workflows/build_and_local_tests.yml`。

设备 workflow（`instrumented_tests.yml`）是**手动触发**的，因为它要开模拟器，慢。

## 2. 造数据

改界面时如果每次手动点半天造数据，会很痛苦。三个办法，从快到慢：

### 办法 A：直接写数据库（最快，调试用）

设备 root 后可以直查 SQLite。**只在开发调试时用**，绕过了应用层的校验规则：

```sh
DB=/data/data/com.kuankuan.daigan/databases/daigan-record.db

# 看表
adb shell "su -c 'sqlite3 $DB \".tables\"'"

# 看某表数据
adb shell "su -c 'sqlite3 $DB \"SELECT number,nickname FROM owners;\"'"

# schema 版本
adb shell "su -c 'sqlite3 $DB \"PRAGMA user_version;\"'"

# 把库拉到本地用 GUI 工具看
adb shell "su -c 'cat $DB' " > /tmp/daigan.db
```

> ⚠️ **警告**：直接写库会绕过归档守卫、幂等键、枚举校验和业务规则，
> 可能造出应用层不可能出现的数据。**只用来复现界面问题，不要提交这样的数据。**
> 同时注意 WAL 模式：直接 `cat` 拿到的库可能缺未 checkpoint 的数据，
> 严谨的做法是 `sqlite3 $DB ".backup /sdcard/x.db"` 再拉。

### 办法 B：用应用内备份做"往返测试"

这个最接近真实使用：

1. 在**旧版本** app 里造好数据 → 设置 → 备份与恢复 → 导出 `.dgbk`
2. 装新版本 → 设置 → 备份与恢复 → 从备份恢复
3. 验证数据完整

备份格式在 v1.6 → v1.7 之间**刻意没动**，所以上游的 `.dgbk` 能直接恢复。

### 办法 C：写一个设备测试来造数据

`app/src/androidTest/java/com/kuankuan/daigan/testing/TestFixtures.kt` 里已有
`owner()`、`account()`、`ownerDetail()`、`shortOrderSummary()`、`hostingOrderForToday()`、
`shortWorkOrderForToday()`、`shortOrderDetailForUi()`、`activeHostingDetailForUi()` 等
fixture 工厂函数。加新数据形态时在同一个文件里扩展，保持一致。

## 3. 调试技巧

### 直接看数据库里的真实值

日期、时区、金额这类字段最容易出玄学问题，直查最快：

```sh
DB=/data/data/com.kuankuan.daigan/databases/daigan-record.db
adb shell "su -c 'sqlite3 $DB \"SELECT gameDate, occurrenceKey, backfilled FROM task_occurrences LIMIT 20;\"'"
```

### 看日志

```sh
# 只看 app 的日志
adb logcat --pid=$(adb shell pidof com.kuankuan.daigan)

# 崩溃栈
adb logcat -d | grep -A 30 "FATAL EXCEPTION"

# 实时过滤
adb logcat | grep -iE "daigan|AndroidRuntime"
```

### 复现通知

提醒要等到特定时间点才能验证，用 adb 直接触发：

```sh
# 改系统时间（需要 root）
adb shell su -c "date 102016352026.00"   # 2026-10-20 16:35
adb shell su -c "am broadcast -a android.intent.action.TIME_SET"

# 看通知是否投递
adb shell dumpsys notification --noredact | grep -A 5 daigan
```

### 测深色模式 / 大字体

```sh
adb shell "cmd uimode night yes"      # 深色
adb shell "cmd uimode night no"       # 浅色
adb shell "settings put system font_scale 1.5"   # 大字体
adb shell "settings put system font_scale 1.0"
```

### 测时区

```sh
adb shell "setprop persist.sys.timezone America/New_York"
adb shell "su -c 'date'"
```

游戏日计算依赖时区，跨时区测试时改这个比改系统设置可靠。

## 4. 改代码时的常见坑

| 坑 | 说明 | 怎么办 |
|---|---|---|
| **改了包名后构建莫名失败** | `app/build/` 里有改名前的 KAPT 生成代码残留，报"找不到 xxx 的类文件" | `./gradlew clean` 后重建 |
| **改了 `local.properties` 报错** | 它是 gitignore 的本机文件，clone 后需要自己写 `sdk.dir=` | 见 [README](../README.md#开发与项目资料) |
| **Release 包签名不对** | `keystore.properties` 不在仓库里 | 见 [上游来源记录](upstream-foundation.md#9-正式签名) |
| **单元测试里时间相关断言失败** | 用了真实系统时间 | 注入 `TimeProvider` |
| **设备测试偶发失败** | 模拟器资源不足，历史上出现过 System UI ANR | AVD 配 4 GiB 内存 / 4 核，**不要加载快照** |
| **connectedAndroidTest 把 app 卸载了** | Gradle 跑完会卸载 app 和测试 apk | 跑完再 `installDebug` 装回来 |
| **改了 schema 但没写迁移** | 启动直接崩 | 加 `MIGRATION_x_y` + 导出新 schema JSON，见 `data-model.md` |
| **改了 `app/schemas/` 下的历史夹具** | 迁移测试依赖它们 | 历史 schema 是**只读夹具**，不要改 |

## 5. 提交前检查清单

```sh
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- [ ] 单元测试全绿
- [ ] Lint 没有新增 Error
- [ ] 设备上手动过一遍改动的路径（**用真机，不只用模拟器**）
- [ ] 深色模式和大字体下看一眼
- [ ] 如果改了数据库：迁移测试通过，schema JSON 已导出并提交
- [ ] 如果改了业务规则：PRD 对应条目已更新
- [ ] 敏感信息没进仓库（`keystore.properties`、`local.properties`、`.jks`）

## 6. 相关文档

| 文档 | 用途 |
|---|---|
| [测试与验收](testing.md) | 历史各版本的测试记录与实测结论 |
| [数据模型](data-model.md) | 表结构、迁移、幂等规则 |
| [提醒行为](reminder-behavior.md) | 提醒判定与去重逻辑 |
| [备份文件格式](backup-format.md) | `.dgbk` 格式与加密信封 |
| [上游来源记录](upstream-foundation.md) | 签名配置、本机构建环境 |
