# Phase 3 修复与补交 prompt

在 `/Users/francis/Documents/Project/代肝记录软件` 继续完成第三阶段。本次验收结论为退回修改，不能开始第四阶段。直接实施修复，不要只输出计划。

先读仓库指令、设计、架构补充、分阶段计划、`docs/handoffs/phase-2-fix-2.md`、`docs/handoffs/phase-3-prompt.md` 与 `docs/handoffs/2026-10-18-phase-3-review.md`。当前第三阶段代码仍在工作区；检查 Git 状态，保留所有已有实现、独立验收测试和未跟踪评审文档，不重置、不重建应用、不改动历史 v1/v2/v3 schema。不升级依赖来绕过问题。

完成以下工作后再交回独立验收：

1. **恢复可执行的完整测试基线。** `Phase2UndoReviewTest.kt:190` 仍按旧签名创建 `OrderDetailViewModel`，缺少 `ReceiptRepository` 和 `TimeProvider`，导致全部 JVM 测试在编译阶段中断；`Phase2ManualProbeTest.kt:85` 缺 `receiptDao` 和 `receiptLedger`，导致全部设备测试也无法编译。提供适当测试依赖，保留原撤销/重复点击/失败重试断言；禁止删除、跳过或弱化已验收测试。检查新增统计及收款页面的现有 UI 测试，使断言匹配实际产品语义并能识别真实错误，不能仅移除失败断言。

2. **修复建单加首笔收款的整体幂等。** 两种 `create*Order` 每次重试都会生成新订单/期次 ID；相同首款 operationId 重试随后与旧流水发生冲突，无法返回原结果。为整个建单命令建立持久化唯一操作标识和规范化请求指纹，在同一事务中保存操作结果、订单、任务、期次及首款。相同请求返回原订单及原首款，不同载荷复用 ID 必须拒绝；无首款建单也应避免不明确结果后的重复建单。保证校验失败/收款失败全部回滚，并发重试只创建一份。ViewModel 在协程启动前防重复，提交命令及 ID 在必要的界面恢复/重试期间保持稳定；成功后只允许明确的新操作取得新 ID。

3. **统一时间精度后再判断收款和更正是否重复。** Room `InstantConverters` 存 epoch 毫秒，而 `ReceiptLedger.matches` 比较原始 Instant，带亚毫秒精度的同一请求重试会被错误判冲突。在命令规范化、写入、幂等指纹、结果返回中使用同一持久化精度。覆盖 record 与 amend，并在关闭/重开数据库后重试；不要靠 UI 先截断来规避 Repository 合同。

4. **完整校验更正请求的幂等载荷。** `ReceiptCorrectionEntity.matches` 忽略 reason，VOID 实际只比较类型与 receiptId。相同 operationId 但不同审计原因必须拒绝；金额、实际时间、途径、规范化备注、原因及目标记录都应参与一致性判断。真正完全相同的重试返回原审计及结果，不能改变既有历史。

5. **补齐首笔实收的实际日期时间。** 当前 `InitialPaymentDraft` 没有 receivedAt，Repository 强制 `receivedAt = now`。新增订单表单允许选择实际收款日期时间，默认当前时间，可补记过去时间，并作为稳定请求的一部分传入；记录时间与实际收款时间分离。验证跨月首款统计按实际到账月份归属、订单数按建单月份归属，失败重试不能改变实际时间。

6. **验证财务读取快照与完整业务合同。** `LedgerSnapshotSource` 用多个独立 DAO Flow 的 combine 拼接多个表，不能仅凭写事务声称读取快照一致。给持续订阅中的建单首款、续期、收款、更正增加回归测试，确认不会发布缺订单/缺期次的流水、暂时错误金额或因完整性校验异常永久结束订阅；如存在问题，改用数据库一致性快照读取和统一失效通知，不用任意 delay/debounce 掩盖。补齐原第三阶段要求的真实 Room 测试：部分/全部收款、ONE_TIME 全额、精确分及溢出、同 ID 重试/冲突/并发超收、跨订单期次拒绝、建单首款原子回滚、作废/更正审计及余额、v3→v4 有业务数据迁移及 v1/v2 升级、归档/完成/到期仍可收款且任务不可写、续期保留旧债/旧款且新期实收零、手选旧期支付、本月/累计与时区边界、续期不加单量、跨页面一致及重启持久化。保留 `Phase3AcceptanceReviewTest` 并让所有合同测试通过。

7. **完成文案、交付和证据。** 更新账号页“收款、统计属于后续阶段”的过期说明、README、`docs/data-model.md`；记录 v4 两张新表、迁移、有效收款口径、审计与幂等合同。交付 `docs/handoffs/phase-3.md` 和 `phase-3-fix-1.md`，明确实现提交、实际测试数、设备/系统、未验证项和限制、APK 绝对路径及 SHA-256。不要将本次独立验收报告当成实施者自验交付。

使用 JDK 21 `/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home` 与 SDK `/Users/francis/Library/Android/sdk`，不提交机器路径。至少执行并读取结果：全部 JVM 测试、全部 instrumentation、lintDebug、assembleDebug、git diff --check。若命中旧 kapt 生成文件缓存，关闭构建缓存重新生成，不手改生成文件。禁止用旧报告或旧 APK 冒充新版本证据。

在真实模拟器或设备验证并截图：无首款/有首款建两类单 → 完成任务 → 分批收款 → 到期续期 → 手选历史期结清 → 本月/累计统计和设置收款明细 → 作废/更正后各页同步 → 归档债务结算且任务仍只读 → 重启保留。补查长文本、大字体、窄屏及深浅主题。只能如实报告已经执行的检查。

只修复并完成第三阶段，提交可审查变更，停止等待独立验收；不开始模板、提醒、备份、云服务或正式发布。
