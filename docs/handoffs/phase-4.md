# Phase 4 — 可编辑游戏模板与模板文件交换

日期：2026-10-18。实现基线：`70b9730`（交接 `9805407`，Phase 3 最终验收
`docs/handoffs/phase-3-fix-2-review.md` 通过）。分支：`codex/phase-2-undo-fix`（本地，
未合并、未推送、未发布）。
本次实现提交：`edc33ae`（`feat(phase-4): persistent editable templates and template file
exchange`）。本文与 `docs/handoffs/screenshots/phase-4/` 为其后续文档提交。

Phase 4 只交付模板库、模板编辑、账号/建单默认值与模板文件交换。备份恢复、提醒、
云服务、发布签名与最终 V1 整体验收**未开始**。模板 JSON 不是业务数据备份。

## 1. 交付的用户流程

- 设置 → 游戏模板：真实模板列表（内置/自建/导入标识、版本、刷新规则、分类与任务数）。
- 新建/编辑模板：游戏名称、模板版本、游戏版本备注、时区、每日/每周刷新时间、
  分类与任务的增删改、勾选/数值进度、起始值/目标值/单位、五种重复周期。
- 账号创建时可选择一个**可选**默认模板；只在以后为该账号建单时预填，不强制。
- 建单（必须从老板 → 游戏账号进入）：选择模板后填入适合当前订单类型的任务与刷新
  规则；仍然可以逐项修改、增删任务；切换订单类型时仅在任务未被编辑过时重新取默认值。
- 导出：系统“创建文档”选择器写出 UTF-8 JSON，默认文件名为
  `daigan-template-<游戏名>-<模板版本>.json`。
- 导入：系统“打开文档”选择器读取文件 → 完整预览（游戏/版本/分类/任务/刷新/冲突）→
  替换 / 另存为 / 取消；取消不改动任何内容。
- 删除模板：确认后删除模板内容，清空账号的默认模板引用；已有订单、期次、打卡与收款
  完全不变。

已有账号和订单**不需要**模板即可继续查看、打卡、补录、收款和续期。

## 2. 持久化、Schema 与迁移

数据库版本 5 → 6（`app/schemas/.../6.json`，历史 `1.json`–`5.json` 原样保留）。

新增表：

- `game_templates`：游戏名、模板版本、版本备注、时区、每日/每周刷新、来源
  （`BUILT_IN`/`USER`/`IMPORTED`）、`builtInKey`（唯一索引）、`sortOrder`、时间戳。
- `template_categories`：模板下的分类，`templateId` 外键 `RESTRICT`。
- `template_tasks`：分类下的任务；`templateId`/`categoryId` 外键 `RESTRICT`；记录方式、
  精确十进制起始/目标值、单位、重复周期、星期、间隔天数、顺序。
- `template_seed_state`：每个内置模板的永久种子标记；删除内置模板后**不会**在下次启动
  复活，用户编辑也不会被覆盖。

新增可空列（不伪造历史数据）：

- `game_accounts.defaultTemplateId`：可空，**不是外键**；删除模板时在同一事务内清空，
  悬空值按“无默认模板”处理。
- `orders.sourceTemplateId` / `sourceTemplateName` / `sourceTemplateVersion`：创建时的
  纯文本来源快照，无外键。Phase 3 及更早订单保持 NULL，不补写任何模板版本。

`MIGRATION_5_6` 只创建新表/索引/外键并添加可空列；不插入模板内容，不改写旧行。
`ALL_MIGRATIONS` 注册 1→2→3→4→5→6，无破坏性回退。

内置模板来自 `app/src/main/assets/templates/*.json`，首次安装由仓储按种子标记写入；
初始化是幂等的，且不会重复覆盖用户编辑或导入。

## 3. 内置内容与证据

顺序：重返未来：1999 → 鸣潮 → 通用模板。来源、区服、核实日期与不确定性见
`docs/game-template-sources.md`。摘要：

- 重返未来：1999：`Asia/Shanghai`，每日 05:00、每周一 05:00。每日/每周刷新有多方攻略
  与官方维护时段/公告转载支撑；没有找到官方规则页直接写明刷新时刻，已在模板备注中
  标注“条目为常用称呼、以游戏内为准”。
- 鸣潮：`Asia/Shanghai`（亚服），每日 04:00、每周一 04:00。官方微博活动说明与官方
  更新说明转载、社区 Wiki 交叉印证；其他区服的服务器时区不同，模板可编辑。
- 通用模板：明确标注“不代表任何具体游戏的官方规则”，并覆盖每日/每周/指定星期/
  每隔 2 天/一次性五种周期，方便直接修改。

**数值目标是可编辑示例，不是官方数值**（例如“消耗活性 100”“结晶波片 120”）。
无法核实的细节没有冒充已核实数据。

## 4. 模板文件格式

详见 `docs/template-file-format.md`。要点：

- `format = "daigan-template"`、`formatVersion = 1`、UTF-8、单模板、≤ 256 KB。
- 只包含模板内容与格式元数据；**不包含**老板、账号、密码、订单、收款或截图。
- 导入在写库前校验：大小、严格 UTF-8、JSON、格式/版本、未知字段、ID 必填/长度/唯一、
  分类/任务数量、`categoryId` 引用、枚举、时区、刷新时间、数值小数位/范围、与编辑器
  共享的草稿规则。任一步失败 → 零写入。
- 同名冲突按规范化游戏名判定：替换（复用库内模板 ID，内容原子替换）、另存为
  （新模板，名称追加“（导入）”“（导入 2）”…）、取消（不写）。
- 交互式导入会重新分配模板/分类/任务 ID，避免与无关模板冲突；内置种子保留资产中的
  稳定 ID。
- 只使用系统文档选择器返回的 URI，不申请宽泛存储权限，不引入网络/云服务。

## 5. 快照与不可变合同

- 模板是**创建默认值**：建单在单个事务中把任务写入 `task_definitions`、刷新写入
  `orders`/`hosting_periods`，并写入来源文本；订单不引用模板行。
- 编辑、导入覆盖、替换或删除模板不会写订单、期次、发生记录、收款、更正或附件。
- 续期从旧期任务复制新 ID 的快照，与模板无关。
- 删除模板只删除模板内容、清空账号默认模板，保留种子标记。
- 模板来源不进入建单命令指纹；进程恢复后的重试仍是同一条命令。旧订单来源为 NULL，
  不伪造版本。

**一次性任务**：`RecurrenceType.ONE_TIME` 是显式扩展。`OccurrenceRules` 对它不产生任何
发生记录；托管草稿校验拒绝它，托管建单只取周期任务；短期单使用它但本来就不生成发生
记录。没有把一次性任务静默映射成 `DAILY`。`OccurrenceRulesTest`/`TemplateDraftTest`
覆盖这一点，Phase 1–3 的每日/每周语义未改。

## 6. 界面与可用性

- 模板库、编辑器、导入预览都是独立页面/对话框，返回与取消明确；未保存修改在返回时
  弹出“放弃这次修改？”（包括系统返回键）。
- 空列表、长名称、长备注、窄屏、大字体、深浅主题均使用可滚动容器和换行文本；
  模板卡片按钮在窄屏仍可点击。
- 模板选择器只影响当前表单；账号默认模板可随时改为“不使用”。

## 7. 验证

环境：JDK 21.0.4（Microsoft OpenJDK，
`/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home`），Android SDK
`/Users/francis/Library/Android/sdk`，Gradle 9.2.1，模拟器 `Medium_Phone_API_36.1`
（Android 16 / API 36，arm64，1080×2400）。命令均加 `--offline`。

### JVM 单元测试（强制重新执行）

```text
./gradlew :app:testDebugUnitTest --rerun --offline --console=plain
-> BUILD SUCCESSFUL
   XML: 22 suites, 125 tests, 0 failures, 0 errors, 0 skipped
```

### 完整设备套件（最终提交代码）

```text
./gradlew :app:connectedDebugAndroidTest --offline --console=plain
-> BUILD SUCCESSFUL in 7m 21s
   175 tests, 0 failures, 0 errors, 0 skipped（1 个 result XML）
```

其中新增 28 个设备用例：`MigrationV5ToV6Test`（2）、`BuiltInTemplateContentTest`（5）、
`GameTemplateRepositoryTest`（9）、`TemplateOrderSnapshotTest`（3）、
`TemplateFileExchangeTest`（4）、`TemplateLibraryUiTest`（5）。Phase 1–3 的 147 个
设备用例全部保留并通过。

### Lint、构建与空白检查

```text
./gradlew :app:lintDebug :app:assembleDebug --offline --console=plain
-> BUILD SUCCESSFUL
   Lint: 0 Error/Fatal；与 Phase 3 相同的 2 Warning（ObsoleteSdkInt）与 2 Hint
   （AutoboxingStateCreation），无新增问题
git diff --check
-> 无输出
```

调试 APK：

- 路径：`/Users/francis/Documents/Project/代肝记录软件/app/build/outputs/apk/debug/app-debug.apk`
- 大小：21,368,523 字节
- SHA-256：`d8f0d7e0e6f372c5c6ce44afbf13a24e162d59e853cc261c02685ded2fcfdb83`

测试确实执行：JVM 使用 `--rerun`；设备套件输出逐项进度并重新安装 APK，不是
UP-TO-DATE 结果。

## 8. 设备走查（真实模拟器）

安装/清理数据后通过真实 UI 操作并截图，目录 `docs/handoffs/screenshots/phase-4/`。
以下均为本轮 Phase 4 证据，未复用 Phase 3 截图。

| 步骤 | 截图 |
|---|---|
| 干净启动的今日事项 | `01-today-fresh.png` |
| 设置 → 游戏模板列表（三款内置） | `02-template-library.png` |
| 打开编辑器（内置通用模板） | `03-template-editor.png` |
| 新建模板：长名称、默认值、分类/任务（`04-template-editor-long-name.png` 为更早一次未保存的编辑尝试，返回时已放弃） | `04-template-editor-new.png`、`04b-…scrolled.png`、`04c-…filled.png` |
| 添加第二个任务并设为一次性 | `05-template-editor-two-tasks.png` |
| 保存后列表出现自建模板（长名称换行） | `06-library-with-new-template.png`、`07-library-new-template-visible.png` |
| 创建老板 | `08-owner-created.png` |
| 账号表单：可选默认模板、选中提示 | `09-account-form.png`、`10-account-template-chips.png`、`11-account-form-filled.png` |
| 账号保存 | `12-account-created.png` |
| 建托管单：账号默认模板自动预填 | `13-new-hosting-order-template-filled.png`、`14-hosting-order-tasks.png` |
| 托管单详情（只含周期任务） | `15-hosting-order-detail.png` |
| 完成一次托管打卡 | `16-hosting-task-completed.png` |
| 切到短期任务：只取一次性任务 | `17-short-order-template-filled.png` |
| 短期单详情/完成 | `18-short-order-detail.png`、`19-short-task-completed.png` |
| 编辑模板任务名并保存 | `20-template-task-renamed.png`、`21-library-after-template-edit.png` |
| 旧托管单仍显示旧任务名 | `22-old-order-unchanged.png` |
| 导出：系统选择器与默认文件名 | `23-export-picker.png`、`24-export-success.png` |
| 导入：选择文件 | `25-import-picker.png` |
| 导入预览（含同名冲突与两个选项） | `26-import-preview.png` |
| 另存为结果（“（导入）”） | `27-import-save-as-result.png` |
| 再次导入 → 替换预览 → 替换结果 | `28-import-replace-preview.png`、`29-import-replace-result.png` |
| 取消导入（提示“模板库没有变化”） | `30-import-cancelled.png` |
| 替换后旧订单仍不变 | `31-order-after-replace.png` |
| 删除模板确认与结果 | `32-delete-template-confirm.png`、`33-template-deleted.png` |
| 删除后旧订单仍可读 | `34-order-after-template-delete.png` |
| 强制停止并重启后模板仍在 | `35-restart-persistence.png` |
| 深色主题 | `36-dark-theme-library.png` |
| 1.5 倍字体：列表与编辑器 | `37-large-font-library.png`、`38-large-font-editor.png` |
| 720×1600 / 320dpi 窄屏：列表与编辑器 | `39-narrow-library.png`、`40-narrow-editor.png` |
| 最终构建：模板选择、托管单详情（新“本期快照”文案） | `42-final-new-order-template.png`、`43-final-order-detail.png` |

补充事实核对：

- 导出的文件确实存在：`/sdcard/Download/daigan-template-Phase4-Long-Template-Name-ABCDEFG-loo-1.0.json`
  （1,716 字节），重新导入成功。
- 替换使用单独准备的文件（任务名改为 `ReplacedTask`、版本 `2.0-replaced`）；替换后
  库内原模板 ID 不变，旧订单仍显示 `Checkin`。
- 删除模板后账号的默认模板被清空（表单显示“不使用”），旧订单详情仍完整可读。
- 重启（`am force-stop` + 重新启动）后，内置模板与“（导入）”副本仍在。

**已执行 vs 未执行：**

- 已执行：上面全部步骤；真实导入/导出流、冲突三分支、重启持久化、三种显示条件。
- 未执行：在导入/保存事务执行中强制进程死亡；在设备上滚动 100 个任务的极端模板；
  物理设备；正式签名；备份恢复和提醒。

## 9. 对既有验收测试的调整（透明说明）

- `NavigationTest.settingsEntryShowsHonestPendingStateAndKeepsBackNavigation` 改为
  `settingsTemplateEntryOpensTheRealLibraryAndKeepsBackNavigation`：游戏模板不再是
  占位页，继续断言“独立子页、无底部导航、返回路径正确”，并改为断言真实模板库。
  这是产品行为按本阶段要求变化，不是删除断言让套件通过。
- `NavigationTest.receiptHistoryIsIndependentUnderSettingsAndStartsEmpty`：新增等待
  异步空状态的条件，原有“还没有收款记录”等断言全部保留（与 Phase 3 修复同类竞态）。
- `Phase3SubmissionReviewTest`、`Phase3SubmissionRoomLifecycleTest`、`NewOrderViewModel`
  相关测试仅新增模板仓储构造参数，原有断言未改。
- 其余 Phase 1–3 测试未删除、未弱化。

## 10. 已知限制与未验证项

- **进程死亡**：导入/保存/建单事务中途的进程死亡没有在设备上强制验证；原子性由
  Room 事务与真实数据库测试覆盖，不等同于进程死亡演练。
- **内容核实**：内置任务名是常用称呼，未逐条核对当前版本；刷新时刻有官方/转载/社区
  交叉证据但没有官方规则页原文（见来源文档）。深眠域等“双周/每月两次”节奏没有专用
  周期类型，只能用可编辑周期近似。
- **走查输入**：设备上通过 `adb shell input text` 输入 ASCII；中文渲染由内置模板与
  自动化测试覆盖。截图中的长名称/版本尾部字符是输入工具的产物，不是校验缺陷；
  长名称本身正好用于 40 字符边界与换行检查。
- **仅模拟器**：无物理设备、无正式发布签名、无云服务。
- 继承 Phase 3 的限制：设备端“提交中进程死亡”和成功表单恢复仍未人工复现；长自由
  文本的人工检查仍不完整。
- 模板 JSON 不是备份：不包含订单、收款、截图或账号资料；完整备份/恢复仍是后续阶段。
- `ONE_TIME` 是模板/短期单适用性标记；它不会被托管订单使用，也不会自动变成每日任务。

## 11. 下一步 / 停止条件

Phase 4 到此停止，等待独立验收。验收通过后再进入 Phase 5（完整备份/恢复、提醒与
整体验证）。本阶段不声称完成 V1：模板、备份、提醒与最终整体设备验证仍分别处于
“已实现/待实现/待整体验证”的不同状态。
