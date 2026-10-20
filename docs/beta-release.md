# Beta 发布准备

当前应用版本为 **1.7 / versionCode 8**，发布标签为 `v1.7-beta.1`。本次版本是
fork 后的**应用身份重构**（应用名、包名、图标、正式签名），业务功能与 v1.6 一致。
上游 v1.6 的修复范围及本地验证见 [v1.6 交接](handoffs/v1.6-user-feedback.md)。

## 提交范围

源码、资源、通用模板、Gradle Wrapper、依赖版本、数据库 schema、测试、CI 和文档进入源码仓库。保留 LICENSE 与派生文件版权声明。

本机的 `local.properties`、IDE/Gradle 缓存、构建输出、`artifacts/` 安装包和原始日志、自动化会话目录、环境变量文件及签名密钥由 `.gitignore` 排除。历史 APK 留在本机，避免二进制反复进入 Git 历史。

## 版本和安装包

- 功能或修复交付递增 `versionName`，同时递增整数 `versionCode`，文件名对应内部版本。
- 当前安装包：`app/build/outputs/apk/release/app-release.apk`（正式签名）；仅作为 Release 附件上传，不纳入 Git 历史。
- 当前预发布标签：`v1.7-beta.1`，绑定本次 v1.7 发布提交。
- 上一版预发布标签：`v1.6-beta.1`，对应上游应用源码提交 `19c30c22f962ff9ad2a40ff358d4bd6828c8a7da`。
- 下载：https://github.com/KuanKuanya/daigan-record/releases
- APK 作为 Release 附件上传，并附 SHA-256。代码标签应对应实际构建源码。

## 签名

- release 签名配置从仓库根目录的 `keystore.properties` 读取；该文件已在 `.gitignore` 中排除。
- 其他机器或 CI 用环境变量 `DAIGAN_KEYSTORE` / `DAIGAN_STORE_PASSWORD` /
  `DAIGAN_KEY_ALIAS` / `DAIGAN_KEY_PASSWORD` 提供同样的值。
- 缺少这些值时 release **仍可编译但不签名**，仅用于本地验证，不得对外分发。
- 密钥本体保存在仓库外的 `~/.daigan-signing/daigan-release.jks`；密码另存于同目录的
  `signing-credentials.txt`（权限 600）。**密钥丢失将无法为已安装用户发布覆盖升级**，
  请自行备份。

## GitHub 检查

构建工作流在 push、pull request 或手动触发时执行单元测试、Lint 与 Debug 构建。设备工作流采用手动触发，在 API 36 x86_64 模拟器上运行；不再保留上游模板的低 API 矩阵和不存在的 customizer 脚本检查。

工作流任务已与本地构建命令对齐；GitHub 托管环境仍需在首次推送后实际运行验证。当前公开仓库为 https://github.com/KuanKuanya/daigan-record ，本地 `origin` 指向本 fork、`upstream` 指向原仓库，源码与图文介绍在主分支维护。

## 后续发布步骤

1. 确认待提交差异只包含本项目文件与必要历史记录。
2. 创建 beta 基线提交并推送，等待 GitHub 构建结果。
3. 用正式签名构建 release 包：`./gradlew :app:assembleRelease`。
4. 创建对应源码标签和 prerelease，上传 APK 与 SHA-256 校验值。

当前设备覆盖为 Android 16 / API 36.1 模拟器；实体设备和 GitHub 托管工作流尚未验收。常规设备套件跳过两个进程死亡专用入口，其历史独立执行结果另见第五阶段交接。
