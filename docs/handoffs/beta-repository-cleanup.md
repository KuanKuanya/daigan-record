# v1.5 beta 仓库整理

日期：2026-10-19。应用保持 1.5 / versionCode 6；未创建提交、远程推送、标签或 Release。

## 已清理

- 删除可再生成的 `app/build/`、根 `build/` 与项目级 `.gradle/` 缓存，先保留本轮完整测试证据。
- 对旧 APK 解压目录的 185 个文件逐个与 APK 比对，全部一致后删除该重复目录。
- 比对 v1 与 v1.1 命名包的 SHA-256，确认内容相同；保留 v1.1 文件并更新引用。
- 删除空的临时文件夹、系统 `.DS_Store`，以及只用于 Google 官方示例包装的 `.google/packaging.yaml`。
- 以上生成及重复内容约 392 MB（374 MiB）。整个本机项目由约 709 MiB 减至约 320 MiB；其中 `.git` 历史约 167 MiB、本机交付物约 127 MiB，均未删除。

## 已整理

- README 以当前 beta 能力、可移植构建说明、目录和文档入口为主；增加 CHANGELOG、文档索引和 beta 发布说明。
- 将上游 Google CLA 贡献流程替换成本项目的开发和验证约定；保留 LICENSE、版权声明与上游来源记录。
- Git 忽略规则排除本机安装包/日志、会话目录、缓存、签名密钥和环境配置。
- GitHub 构建工作流使用当前 Gradle 任务和 JDK 21，删除不存在的 customizer 检查；设备测试调整为手动触发的 API 36 x86_64 模拟器。
- 有效的业务源码、回归测试、历史 schema、设计源文件、迁移证据和历史交接保留，不按“旧阶段”名称盲删。

## 核验

- 完整本地构建成功；160 项单元测试通过；设备 XML 219 项中 217 通过、2 个专用入口按设计跳过，无失败/错误。
- Lint：0 Error、3 Warning、2 Hint。
- 新入口文档链接检查通过，两份工作流 YAML 可解析，`git diff --check` 通过。
- 待提交文本扫描未发现常见私钥、GitHub token、AWS 访问密钥或 Google API key 格式命中；这不等同于完整安全审计。
- `artifacts/`、本机路径配置和签名密钥未进入待提交文件列表；原始整理清单和证据保存在本机 `artifacts/repository-cleanup/`。

## 后续

当前没有 Git remote。取得目标 GitHub 仓库地址后，按 [beta 发布说明](../beta-release.md) 审查差异、提交、推送并验证托管工作流，再建立 beta Release。当前包仍为本机 debug 签名，长期连续覆盖安装需固定签名身份。
