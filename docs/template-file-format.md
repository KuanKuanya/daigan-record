# 模板文件格式（Phase 4）

本文件定义“代肝记录”通过 Android 系统文档选择器导入/导出的游戏模板文件。
文件只包含模板内容与格式元数据，**不包含**老板、账号、密码、订单、期次、打卡、收款或
任何其他业务数据。

- 编码：UTF-8（严格解码，非法字节会被拒绝）
- 扩展名：`.json`
- MIME 类型：`application/json`（导出时使用；导入时系统选择器按文档选择，扩展名不被信任）
- 当前格式：`format = "daigan-template"`，`formatVersion = 1`
- 大小上限：256 KB
- 一个文件只包含一个模板

模板 JSON 不是完整业务备份。它不能恢复订单、收款、截图或老板资料。

## 1. 结构

```json
{
  "format": "daigan-template",
  "formatVersion": 1,
  "template": {
    "id": "tpl-example",
    "gameName": "示例游戏",
    "templateVersion": "2026.10",
    "gameVersionNotes": "可选说明，可为 null",
    "timeZone": "Asia/Shanghai",
    "dailyRefresh": "05:00",
    "weeklyRefreshDay": "MONDAY",
    "weeklyRefreshTime": "05:00",
    "categories": [
      {
        "id": "cat-daily",
        "name": "日常",
        "tasks": [
          {
            "id": "task-checkin",
            "categoryId": "cat-daily",
            "title": "每日签到",
            "progressType": "CHECKBOX",
            "startValue": "0",
            "targetValue": "1",
            "unit": null,
            "recurrence": "DAILY",
            "weekdays": [],
            "everyNDays": null
          }
        ]
      }
    ]
  }
}
```

### 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `format` | string | 是 | 必须精确等于 `daigan-template` |
| `formatVersion` | integer | 是 | 必须精确等于 `1` |
| `template` | object | 是 | 模板内容 |

除这三个键以外的顶层字段一律拒绝（例如误选的订单导出或其他应用的文件）。

### `template`

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `id` | string | 是 | 1–100 字符；在文件内与所有分类/任务 ID 一起唯一 |
| `gameName` | string | 是 | 去空白后 1–40 字符 |
| `templateVersion` | string | 是 | 去空白后 1–30 字符 |
| `gameVersionNotes` | string \| null | 否 | ≤ 300 字符 |
| `timeZone` | string | 是 | 合法 IANA 时区，例如 `Asia/Shanghai` |
| `dailyRefresh` | string | 是 | `HH:mm`（00:00–23:59） |
| `weeklyRefreshDay` | string | 是 | `MONDAY` … `SUNDAY` |
| `weeklyRefreshTime` | string | 是 | `HH:mm` |
| `categories` | array | 是 | 1–20 个分类 |

模板对象内的未知字段一律拒绝。

### 分类

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `id` | string | 是 | 1–100 字符；文件内唯一 |
| `name` | string | 是 | 去空白后 1–30 字符 |
| `tasks` | array | 是 | 至少 1 个；全部模板合计 ≤ 100 个 |

### 任务

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `id` | string | 是 | 1–100 字符；文件内唯一 |
| `categoryId` | string | 是 | 必须等于所在分类的 `id`（引用关系校验） |
| `title` | string | 是 | 去空白后 1–40 字符 |
| `progressType` | string | 是 | `CHECKBOX` 或 `NUMERIC` |
| `startValue` | string/number | 是 | 数值；≤ 3 位小数；不得为负；不超过 10^12 |
| `targetValue` | string/number | 是 | 数值；≤ 3 位小数；必须大于起始值 |
| `unit` | string \| null | 否 | ≤ 10 字符；`CHECKBOX` 时忽略 |
| `recurrence` | string | 是 | `ONE_TIME` / `DAILY` / `WEEKLY` / `SELECTED_WEEKDAYS` / `EVERY_N_DAYS` |
| `weekdays` | array | 否 | `MONDAY` … `SUNDAY`；`SELECTED_WEEKDAYS` 时至少 1 个 |
| `everyNDays` | integer \| null | 否 | 1–365；仅 `EVERY_N_DAYS` 使用 |

`CHECKBOX` 任务的起始/目标值在保存时规范化为 `0`/`1`；文件中仍必须提供有效的数值字段。

**一次性任务**：`ONE_TIME` 明确表示“短期单适用”，不会生成每日打卡。导入到托管订单时，
这类任务不会被自动改成 `DAILY`：应用只把周期任务填入托管单，并明确提示没有可用任务。
短期单只使用 `ONE_TIME` 任务；其订单任务仍保存为一次性，不产生发生记录。

## 2. 校验顺序与失败行为

导入在**写入数据库之前**完成全部校验：

1. 文件大小（> 256 KB 直接拒绝）
2. UTF-8 严格解码
3. JSON 解析
4. `format` / `formatVersion`
5. 未知字段（顶层、模板、分类、任务）
6. ID 必填、长度、全局唯一
7. 分类/任务数量与嵌套结构
8. `categoryId` 引用一致
9. 枚举值（记录方式、重复周期、星期）
10. 时区、刷新时间
11. 数值（小数位、正负、目标大于起始）
12. 与编辑器共享的模板草稿校验（名称长度、空分类、重复周期参数等）

任一步失败：返回可读的中文原因，**模板库不发生任何变化**。取消文件选择同理，
不视为错误、不写任何内容。

## 3. 同名冲突与 ID 策略

冲突判定：已有模板的 `gameName` 去首尾空白、忽略大小写后与导入内容相同。

| 选择 | 行为 |
|---|---|
| 替换 | 复用该模板的库内 ID，删除其旧分类/任务并写入导入内容；`origin` 变为“导入”，内置标识保留；已有订单完全不变 |
| 另存为 | 新建模板，名称追加“（导入）”；若仍冲突则追加“（导入 2）”“（导入 3）”…；超过 40 字符时截断原名 |
| 取消 | 不写任何内容 |

**ID 处理**：文件中的 ID 只用于文件内部的唯一性与引用校验。交互式导入会为模板、分类和
任务重新分配 ID（内置模板首次安装时保留资产中的稳定 ID）。因此：

- “另存为”不会与原文冲突；
- “替换”只影响目标模板的库内内容，不影响其他模板；
- 订单不引用模板行，替换/删除不会改写历史订单的任务、价格、期次或日期归属。

## 4. 导出

- 导出内容来自数据库中的当前模板，UTF-8 JSON，字段与上节一致。
- 只写模板内容；不写老板、账号、密码、订单、收款、截图、统计或设置。
- 文件名默认 `daigan-template-<游戏名>-<模板版本>.json`，非法文件名字符替换为 `-`。
- 通过 `ActivityResultContracts.CreateDocument("application/json")` 写出；不申请存储权限。
- 写入失败（目标不可写、提供方异常）会显示原因，不会留下“成功”提示。

## 5. 兼容性

- 只接受 `formatVersion = 1`。任何其他版本（包括未来版本）都会被明确拒绝，避免旧程序
  误解新字段。
- 未知字段被拒绝而不是静默忽略；新增可选字段必须提升 `formatVersion`，并由新版本导出。
- 字符串长度、集合数量与数值范围可能在未来版本放宽；旧版本仍会按本文件的限制拒绝。

## 6. 隐私与安全

- 文件是普通文本，用户自行决定分享给谁；应用不自动上传，不访问网络。
- 导入的标题/备注只是数据，不会被当作指令执行。
- 严格拒绝未知字段，避免把其他应用的业务数据文件误当作模板合并。
- 应用不申请 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` 或 `MANAGE_EXTERNAL_STORAGE`；
  只使用系统文档选择器返回的 URI。
