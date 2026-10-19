# 测试与验收

## 当前 beta 基线：v1.5（2026-10-19 仓库整理后复核）

本轮未修改应用行为或版本。执行完整单元测试、设备测试、Lint 和 Debug 构建：160 项单元测试全部通过；设备 XML 报告共 219 项，217 通过、2 个进程死亡专用入口按设计跳过，0 失败、0 错误。Lint 为 0 Error、3 Warning、2 Hint。设备为 Android 16 / API 36.1 ARM64 模拟器，无实体设备覆盖。

最终 APK 与 v1.5 原交付包 SHA-256 一致：`23726c900a9ab065c25d3ade92ef1ae30a4ad98a4d86648549c39ed828cc5f03`。原始 JUnit XML、设备 HTML 和 Lint 报告复制到本机 `artifacts/repository-cleanup/evidence/` 后，删除可再生成的构建缓存。GitHub 工作流 YAML 已解析检查；托管环境须在首次推送后另行验证。

详情见 [仓库整理交接](handoffs/beta-repository-cleanup.md)。下方保留各阶段历史测试记录，其计数和界面状态只对应当时版本。

UI 优化、托管日历、提醒时间输入与模板收敛验收（2026-10-19）。完整交付信息见 `handoffs/ui-polish-calendar-templates.md`。

UI 优化全量回归：单元测试 158 项通过；设备全量 XML 共 213 项（211 通过、2 项进程死亡专用入口按设计跳过），0 失败、0 错误。之后独立复核指出空打卡历史缺少“批量完成不会补写历史遗漏”语义提示；已恢复该提示并新增界面回归测试，该测试通过。Lint 0 Error、14 Warning、2 Hint，Debug APK 构建通过。最终补丁只增加界面文字与测试，不改数据库 schema；APK SHA-256 为 `d5d274822fc09b76d4be944a5b0fe7260712c2ae4d25d6f9fc66530e0f41344f`。

测试设备为 Android 16 / API 36.1 模拟器，没有实体手机覆盖。

第五阶段验收记录（2026-10-19）。完整范围、限制与交付信息见 `handoffs/phase-5.md`。

## 执行环境

JDK 21、Gradle Wrapper 9.2.1，离线依赖缓存；Android 16 / API 36.1 ARM64 模拟器。没有实体手机覆盖。

```sh
./gradlew :app:testDebugUnitTest --rerun :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug --offline --no-build-cache --continue
```

## 自动化范围

- 保留原业务、财务、订单日历、模板和撤销回归。
- 备份覆盖全部业务表、附件、加密密码与篡改、格式和资源限制、恢复确认、取消、失败回滚。
- 恢复独占访问、并发写入隔离、过期表单、启动恢复屏障和提醒重建。
- 数据库历史版本 1–6 升级到 7，逐表逐列保留旧数据。
- 提醒规则、持久化、权限拒绝、唯一调度、去重、通知导航。
- 六个进程死亡检查点须分别执行 prepare（主动终止进程）和 recover（新进程验证）；这两个入口在普通全量套件中有意跳过。

## 验收证据

单元测试 152 项通过；设备全量 XML 报告 208 项（206 通过，2 项专用入口跳过），0 失败、0 错误。六个真实进程死亡检查点分别终止进程并在新进程恢复，全部通过。Lint 0 Error、14 Warning、2 Hint，Debug 构建通过。明文/加密 SAF 导出与清空测试应用后的恢复、真实触屏确认、通知权限拒绝、系统设置入口、模拟器重启、深色和大字体页面已经手工检查。

模拟器先前出现桌面和 System UI 无响应，造成中间界面回归失败；保留证据，后续采用 4 GiB 内存、4 核且不加载快照的模拟器完成绿色全量回归。加密测试确认按钮使用可访问性动作，真实触屏确认另通过系统文件选择器恢复检查，不混淆两种覆盖。

最终日志、JUnit XML、Lint 报告、真实进程死亡结果保存在 `artifacts/phase-5/evidence/`。中间诊断日志位于本机 `/tmp/phase5-*.log`。
