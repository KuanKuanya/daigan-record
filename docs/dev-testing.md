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

## 0.5 准备一个本地测试目标

本机已配好一套可长期使用的模拟器（`daigan_test`，API 36 / arm64-v8a）：

```sh
./tools/dev-emulator.sh start     # 启动并等待 boot 完成
./tools/dev-emulator.sh status    # 看状态
./tools/dev-emulator.sh stop      # 关闭
```

启动参数固定为 **4 GiB 内存 / 4 核 / 不加载快照**。这是有原因的：
历史记录里模拟器曾因资源不足出现桌面与 System UI 无响应，
造成界面回归**偶发失败**（[测试与验收](testing.md) 记录了这次事故）。
不要为了"启动快"而调小内存或加 `-snapshot`。

首次在一台新机器上准备：

```sh
sdkmanager "system-images;android-36;google_apis;arm64-v8a"
avdmanager create avd -n daigan_test \
  -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6
```

模拟器起来后 `adb devices` 会看到 `emulator-5554`。
**连了真机时，Gradle 会把测试跑在所有设备上**，
用 `ANDROID_SERIAL=emulator-5554` 指定只跑模拟器。

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

改界面时如果每次手动点半天造数据，会很痛苦。四个办法，从快到慢：

### 办法 A：直查 / 直写数据库（最快，调试用）

**在模拟器上**用 `run-as`（不需要 root，也不影响 adbd）：

```sh
DB=/data/data/com.kuankuan.daigan/databases/daigan-record.db

# 看表
adb shell "run-as com.kuankuan.daigan sqlite3 $DB '.tables'"

# 看数据
adb shell "run-as com.kuankuan.daigan sqlite3 $DB 'SELECT number,nickname FROM owners;'"

# schema 版本
adb shell "run-as com.kuankuan.daigan sqlite3 $DB 'PRAGMA user_version;'"
```

**在真机或 root 设备**上用 `su`：

```sh
adb shell "su -c 'sqlite3 $DB \"SELECT number,nickname FROM owners;\"'"
```

> ⚠️ **两条纪律**
> 1. 直接写库会绕过归档守卫、幂等键、枚举校验和业务规则，可能造出应用层
>    不可能出现的数据。**只用来复现界面问题，不要提交这样的数据。**
> 2. **不要在不掌控生命周期的远程设备上跑 `adb root`** —— 它会重启 adbd，
>    连同端口转发一起断掉，设备可能再也连不上。要 root 先确认设备是自己的。

严谨地拉库（避免 WAL 里未 checkpoint 的数据丢失）：

```sh
# 备份到 app 自己的 files 目录，再读出来
adb shell "run-as com.kuankuan.daigan sqlite3 $DB '.backup files/x.db'"
adb shell "run-as com.kuankuan.daigan cat files/x.db" > /tmp/x.db
sqlite3 /tmp/x.db "PRAGMA integrity_check;"   # 应输出 ok
```

> `run-as` 是以 app 身份执行的，**没有权限写 `/sdcard`**，
> 所以 `.backup` 的目标要放在 app 私有目录，再用 `cat` 读出来。
> 直接 `cat` 数据库文件则可能拿到缺少 WAL 数据的残缺副本。

### 办法 B：注意内置模板是**懒加载**的

首次启动后数据库里 `game_templates` 和 `template_seed_state` **都是空的**，这是**正常行为**。
`ensureBuiltInTemplates()` 只在三个地方触发：

- 打开「新增游戏账号」表单（`AccountFormViewModel`）
- 打开「游戏模板」库（`TemplateLibraryViewModel`）
- 打开「新增订单」页（`NewOrderViewModel`）

所以调试模板相关功能时，**先打开一次模板库**，再去看数据库。
实测：打开前 `game_templates` 为 0 条；打开后立刻变成 1 条
（`tpl-generic | 通用模板 | BUILT_IN`），`template_seed_state` 记下
`generic` / `reverse-1999` / `wuthering-waves` 三个种子键
（后两个是历史键，记上是为了让删掉的内置模板不会在下次启动复活）。

### 办法 C：用应用内备份做"往返测试"

这个最接近真实使用：

1. 在**旧版本** app 里造好数据 → 设置 → 备份与恢复 → 导出 `.dgbk`
2. 装新版本 → 设置 → 备份与恢复 → 从备份恢复
3. 验证数据完整

备份格式在 v1.6 → v1.7 之间**刻意没动**，所以上游的 `.dgbk` 能直接恢复。

### 办法 D：写一个设备测试来造数据

`app/src/androidTest/java/com/kuankuan/daigan/testing/TestFixtures.kt` 里已有
`owner()`、`account()`、`ownerDetail()`、`shortOrderSummary()`、`hostingOrderForToday()`、
`shortWorkOrderForToday()`、`shortOrderDetailForUi()`、`activeHostingDetailForUi()` 等
fixture 工厂函数。加新数据形态时在同一个文件里扩展，保持一致。

## 3. 调试技巧

### 找到控件坐标再点击

不要靠肉眼猜坐标，`uiautomator dump` 能直接给出每个控件的 bounds：

```sh
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | tr '>' '>\n' \
  | grep -oE 'text="[^"]+"[^>]*bounds="[^"]+"'
```

输出形如 `text="游戏模板" ... bounds="[84,532][255,593]"`，
点它就是 `adb shell input tap 169 562`（取中心点）。

这套 `dump → tap → screencap` 组合能写成一串脚本，用来手动走流程并留证据。

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
| **在别人的远程设备上跑 `adb root`** | adbd 重启会连带断掉端口转发，设备可能再也连不上 | 用模拟器，或用 `run-as` 代替 root |
| **多设备时 Gradle 在所有设备上跑测试** | 真机 + 模拟器同时连着会跑两遍，还可能互相干扰 | `ANDROID_SERIAL=emulator-5554 ./gradlew ...` |

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
