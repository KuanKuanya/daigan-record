# 代肝记录

面向个人游戏代肝从业者的离线 Android 应用。第一阶段（Phase 1）交付可构建的真实持久化底座、
三个主导航页面、独立设置页，以及老板与游戏账号管理。

- 应用名：`代肝记录`
- applicationId / namespace：`com.daigan.record`
- minSdk 26，compileSdk / targetSdk 36
- 全离线：Manifest 未声明 `INTERNET` 权限，Android 自动云备份与设备迁移均已关闭
- 中文界面，跟随系统深浅色

## 当前阶段能力

| 能力 | 状态 |
|---|---|
| 今日事项（托管打卡 / 短期任务标签） | 页面与诚实空状态，真实任务在第二阶段 |
| 账号信息：老板与游戏账号增改、凭据查看复制、归档恢复 | 已实现，Room 持久化 |
| 数据统计 | 诚实空状态，真实统计在第三阶段 |
| 设置（收款明细 / 游戏模板 / 提醒设置 / 备份与恢复） | 独立页面与待实现说明 |
| 订单、打卡、收款、模板、提醒、备份 | 未实现（不在本阶段范围） |

## 构建与验证

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest   # 需要已启动的模拟器或设备
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`

## 结构

```
app/src/main/java/com/daigan/record/
├── core/time/          可注入时间源
├── data/local/         Room 实体、DAO、数据库、类型转换器
├── data/model/         领域模型、表单校验、老板编号格式
├── data/repository/    Repository 接口与实现（唯一数据访问边界）
├── data/di/            Hilt 绑定
├── feature/accounts/   老板与账号列表、详情、表单、归档
├── feature/today/      今日事项（空状态）
├── feature/stats/      数据统计（空状态）
├── feature/settings/   独立设置页与待实现页
└── ui/                 主题、导航、通用组件
```

数据流固定为 Compose → ViewModel → Repository → DAO → Room；ViewModel 不接触 DAO 或文件。

## 文档

- 需求：`docs/superpowers/specs/2026-10-17-game-work-manager-design.md`
- 架构补充：`docs/superpowers/plans/2026-10-17-architecture-foundation.md`
- 上游底座：`docs/upstream-foundation.md`
- 数据模型：`docs/data-model.md`
- 阶段交接：`docs/handoffs/phase-1.md`

## 上游来源与许可

源码底座来自 Google `android/architecture-templates` 的 `base` 分支，固定提交
`cdb6de476e1889c88947946c28d900b3537dc867`，遵循 Apache License 2.0（见 `LICENSE`）。
派生文件的版权声明已保留，具体改动见 `docs/upstream-foundation.md`。
