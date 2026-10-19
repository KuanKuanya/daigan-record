# 第一阶段独立验收

检查日期：2026-10-17。被审查提交：b1716cf（实现提交 b4fe60f）。

结论：退回修复，暂不进入第二阶段。未修改产品源码。

## 已核实

- 官方模板导入提交、固定上游来源说明、Hilt/Repository/Room 边界、Schema 导出、外键约束与无破坏性迁移配置已存在。
- 本次运行单元测试、Lint 与 APK 构建命令成功；随后用 --rerun-tasks 重新执行测试。单元测试 XML 确认 28 测试、0 失败、0 错误。
- Lint 报告 0 Error、0 Fatal、28 Warning。
- 本次重新执行模拟器测试：35/35 完成，0 skipped、0 failed（Medium_Phone_API_36.1，Android 16）。现有测试未覆盖 R1，且其中一个 UI 用例固化了 R2 的错误行为。

## 必须修复

### R1：不能用归档时间充当归档批次身份

OwnerRepository.restoreOwner 和 GameAccountDao.restoreArchivedWithTimestamp 根据 ownerId + archivedAt 判断随老板归档的账号。时间以毫秒存储，独立归档与老板归档可以相同，时钟回拨也会重复。按已提交 Schema 及相同 SQL 在内存 SQLite 复现：先独立归档 old，随后归档老板和 active，时间都为 1000，恢复老板后 old 和 active 的 archivedAt 都成 NULL；old 应继续归档。

改为明确持久化归档来源/批次或独立归档状态，不能通过延时、提高时间精度或测试时推进时钟掩盖。增加真实 Room 的同时间、重复归档、连续恢复重归档测试。若变更 Schema，保留 v1 并编写真实迁移及数据保留测试。旧数据缺少归档来源时不可声称能精确恢复，应采取有记录的保守策略保留账号并允许手动恢复。

### R2：父级归档时仍可单独恢复子账号

OwnerDetailScreen 的 archivedAccounts 无条件传入 onRestore，并在 AccountCard 的 readOnly 分支显示可点击恢复按钮。GameAccountRepository.restoreAccount 也不检查父级，直接清空 archivedAt。可由老板详情归档后点击子账号恢复，得到父级 archivedAt 非空、子账号 NULL；违背恢复前只读说明，也破坏归档汇总/后续接单状态约束。

父级归档时账号只能查看，禁止编辑、单独恢复/再归档和新增。约束同时放在 Repository 的同一事务内，避免只隐藏按钮；不存在或不符合状态的操作返回可理解失败且不写入。老板恢复后允许独立账号恢复。OwnerDetailScreenTest 目前反而断言归档老板下存在“恢复”子账号按钮，必须更正该用例。

## 一并修正的小问题

- ArchivedRecordsScreen 用全部归档账号数却称“随老板归档”，包含此前独立归档账号，应改为准确的总数文案或按明确来源计数。
- 用户已指定名称“新增”，OwnerFormScreen 的新建标题仍为“新增老板”，改为“新增”。

## 修复边界

仅修复第一阶段，不加入订单、收款或其他第二阶段能力。不因现有测试全绿而跳过上述回归。修复前后验证、迁移策略及真实命令结果写入 phase-1-fix-1.md，提交后交回本对话复验。
