# Beta 发布准备

当前应用版本为 **1.6 / versionCode 7**，发布标签为 `v1.6-beta.1`。修复范围及本地验证见 [v1.6 交接](handoffs/v1.6-user-feedback.md)。

## 提交范围

源码、资源、通用模板、Gradle Wrapper、依赖版本、数据库 schema、测试、CI 和文档进入源码仓库。保留 LICENSE 与派生文件版权声明。

本机的 `local.properties`、IDE/Gradle 缓存、构建输出、`artifacts/` 安装包和原始日志、自动化会话目录、环境变量文件及签名密钥由 `.gitignore` 排除。历史 APK 留在本机，避免二进制反复进入 Git 历史。

## 版本和安装包

- 功能或修复交付递增 `versionName`，同时递增整数 `versionCode`，文件名对应内部版本。
- 当前安装包：`output/releases/v1.6/daigan-record-v1.6-debug.apk`；仅作为 Release 附件上传，不纳入 Git 历史。
- 当前预发布标签：`v1.6-beta.1`，绑定本次 v1.6 发布提交。
- 首次预发布标签：`v1.5-beta.1`，对应应用源码提交 `60fccdc54c1bba59a29de2bd561816311d9f9ff6`。
- 下载：https://github.com/undefinedfrancis/daigan-record/releases/tag/v1.6-beta.1
- APK 作为 Release 附件上传，并附 SHA-256。代码标签应对应实际构建源码。
- 当前包为 debug 签名；不同电脑或 CI 的 debug 密钥可能不同。准备长期覆盖安装分发时，需要固定签名身份，并在仓库外保存密钥。

## GitHub 检查

构建工作流在 push、pull request 或手动触发时执行单元测试、Lint 与 Debug 构建。设备工作流采用手动触发，在 API 36 x86_64 模拟器上运行；不再保留上游模板的低 API 矩阵和不存在的 customizer 脚本检查。

工作流任务已与本地构建命令对齐；GitHub 托管环境仍需在首次推送后实际运行验证。当前公开仓库为 https://github.com/undefinedfrancis/daigan-record ，本地 origin 已关联，源码与图文介绍在主分支维护。

## 后续发布步骤

1. 确定目标 GitHub 仓库地址和可见性，配置对应 remote。
2. 审查待提交差异，确认只包含本项目文件与必要历史记录。
3. 创建 beta 基线提交并推送，等待 GitHub 构建结果。
4. 确定分发签名，创建对应源码标签和 prerelease，上传 APK 与校验值。

当前设备覆盖为 Android 16 / API 36.1 模拟器；实体设备和 GitHub 托管工作流尚未验收。常规设备套件跳过两个进程死亡专用入口，其历史独立执行结果另见第五阶段交接。
