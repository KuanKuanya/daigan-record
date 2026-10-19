# 第二阶段修复复验：未通过

2026-10-17，HEAD 仍为 b109f83，第二阶段及修复均未提交。

## 实际验证

普通构建先因旧生成的 Hilt Factory 构造参数不匹配失败。随后强制重新生成以排除缓存影响：

`./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain`

仍以 BUILD FAILED 结束，实际失败项是 compileDebugAndroidTestKotlin，不能标记设备测试通过，也不能使用先前 106 测试报告作为修复结果。

## 必须完成

1. Phase2ManualProbeTest.kt 仍给 DefaultOrderRepository 传 timeProvider 而非 ticker；DefaultAttachmentRepository 缺少 sync 参数。
2. OrderDetailScreenTest.kt 与 TodayScreenTest.kt 仍传两个参数的旧回调，新 UI 回调要求 taskId、occurrenceKey 与值共三个参数。必须更新用例并实际断言事件归属，不能删用例绕过。
3. 尚无完整回归测试覆盖新 AttachmentSync 并发协议、TimeTicker 前台跨界、未来补录及双周窗口历史访问。已有源文件增加不等于这些测试已经完成。
4. R4 只保护了单项勾选和数值进度：completeHostingOrder/completeAllHosting 及其调用端仍不传展示时的 occurrence/window 身份，跨界时仍会根据提交时的时间完成新日任务。需要补批量操作的快照校验。
5. 测试调度器 ManualBoundaryWaiter 用一个 Channel.CONFLATED，advanceTo 只发送一个元素，只会唤醒一个等待者，却声称唤醒所有。多订阅者边界测试会漏唤醒或挂起；应采用可控广播/每订阅者独立屏障，并增加多订阅者测试及超时。
6. docs/handoffs/phase-2.md 和 phase-2-fix-1.md 均不存在，数据模型文档尚未充分更新修复语义。完成后需要提交。

## 已见的修复进展

周窗口已改为实际时间交集；历史页面可列同日两个周窗口；Repository 在创建记录之前拒绝未来补录；截图发布和清理增加共享互斥；时间读取引入 TimeTicker。上述进展保留，不要求重新实现整个阶段。

本次未改产品源码，未运行不具备编译条件的设备测试或宣称新版 APK 通过。下一轮先完成集成和回归，再复验四项原问题。
