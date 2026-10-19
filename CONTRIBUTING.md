# 参与开发

本项目基于 Kotlin、Jetpack Compose、Room 和 Hilt，源码遵循仓库中的 [Apache License 2.0](LICENSE)。上游来源及保留声明见 [底座说明](docs/upstream-foundation.md)。

## 开发环境

使用 JDK 21、Android SDK 36 和仓库自带的 Gradle Wrapper。将本机 SDK 路径写入未纳入版本控制的 `local.properties`，不要提交绝对路径、签名密钥或真实账号资料。

## 提交改动

1. 围绕一个明确问题修改，保留历史数据库 schema 和迁移兼容性。
2. 影响业务逻辑时添加或调整有意义的回归测试。
3. 执行 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`；界面、数据库或系统交互改动还须在 Android 设备/模拟器上执行 `:app:connectedDebugAndroidTest`。
4. 执行 `git diff --check`；说明改动、验证结果和限制。

APK、原始测试日志、构建缓存和本机配置不提交到源码仓库。发布流程和版本规则见 [beta 发布说明](docs/beta-release.md)。
