# 完整资料替换与中断恢复

恢复服务先在私有目录解密、解包并导入隔离的 Room 数据库进行验证。预览不会写入现有资料。用户明确确认后，DatasetGate 排除业务写入，AttachmentSync 排除截图发布/替换/删除，所有破坏性步骤直到恢复判断完成都在不可取消区间内运行。

## 提交协议

1. 写入并同步 PREPARED 日志：随机恢复 ID、私有暂存/回滚路径和阶段，无业务内容或密码。
2. 在同一业务与附件锁内建立完整逻辑回滚 ZIP，写入 ROLLBACK_READY。
3. 即使原截图为空，也先建立原目录，再将其重命名为 `attachments.bak-<id>`。同步父目录。此时杀进程也能找回原目录。
4. 将验证后的截图目录改名为 live，先前文件已同步，再同步父目录，写入 FILES_SWAPPED。
5. 单个 Room 事务删除并重建所有业务行及提醒设置；生成新 datasetInstanceId，清空本机提醒投递记录，同时写入 lastRestoreId。**该事务是唯一提交点**。提交后使旧表单/排队写入失效。
6. 写入 DB_COMMITTED。在同一个不可取消、持有资料访问许可的区间内，取消旧提醒并按恢复后的偏好重建调度，等待 WorkManager 持久提交完成；成功后才持久删除日志，再清除数据库提交标记，最后删除旧文件和回滚临时内容。提醒重建失败保留日志和标记，启动恢复重复重建，不能仅用 KEEP 沿用旧时间。删除失败可以遗留无害临时副本，不能回滚已提交数据库。

启动必须先运行 `recoverIfNeeded()`，成功之前禁止正常页面、仓库和提醒 worker 读取/写入。正常未提交异常在放开锁前恢复原截图；SQLite 自动回滚未提交事务。进程死亡后由数据库标记决定：标记等于日志 ID，保留新库和新截图并完成清理；否则恢复旧截图，保留 SQLite 原事务数据。阶段文字仅用于诊断，不能替代数据库提交标记。恢复可重复调用。

日志损坏时保留所有现场并阻止打开资料，不能猜测使用哪个文件目录。磁盘故障导致重命名、同步或日志清除失败同样应显示可重试错误，不得清空应用数据或在不完整资料上继续业务操作。此机制保证正常应用进程死亡的恢复；不能修复设备物理损坏或文件系统整体丢失。

暂存目录的全局清理只发生在启动恢复阶段。用户运行期间取消导入/完成导出只清理自己会话，避免删除并行导出或准备中的导入。

## 验证入口

JVM：`com.daigan.record.backup.BackupCryptoTest`、`BackupArchiveTest`。覆盖 AES-GCM 往返/篡改/密码边界、完整表归档往返、路径穿越、未知条目、校验和不一致。

设备：`com.daigan.record.backup.BackupRestoreRecoveryTest`。真实 Room 和私有文件，验证目录覆盖所有生产表列、普通/加密备份在全新数据库还原账号密码/任务/截图/提醒设置、6 个恢复转换点的异常、关闭重开与重复恢复、原附件目录不存在、损坏日志保留现场。另验证提交点取消外层任务仍完成提醒重建，以及提醒写入失败时恢复标记保留到重试成功。仅文档选择器和通知发送/调度使用替身。

真实进程死亡单独运行，不能把预期的 instrumentation 终止当作普通测试通过：

```sh
adb shell am instrument -w -r -e class com.daigan.record.backup.BackupRestoreRecoveryTest#processDeathPrepare -e crashAt FILES_SWAPPED com.daigan.record.test/com.daigan.record.HiltTestRunner
adb shell am instrument -w -r -e class com.daigan.record.backup.BackupRestoreRecoveryTest#processDeathRecover com.daigan.record.test/com.daigan.record.HiltTestRunner
```

实际 runner 名称以项目配置为准。对 PREPARED、ROLLBACK_READY、ORIGINAL_FILES_MOVED、FILES_SWAPPED、DATABASE_COMMITTED、DB_COMMITTED 分别运行。第一条应因应用主动杀进程终止；第二条在独立进程验证完整原/新资料和日志清除。两次之间不要卸载或清空测试应用缓存。无 crashAt 的普通全套测试不会杀进程。完整设备验证证据由第五阶段交接报告记录，本说明不宣称未运行的测试通过。
