# mpvEx 项目约定

通用行为以当前 Codex 配置和会话指令为准；这里只保留项目差异。

- 恢复入口：`PROJECT_CONTEXT.md`、`PROJECT_ISSUES.md`，再核对 Git；涉及重构时读取 `REFACTOR_PLAN.md`。历史记录不构成自动待办。
- 主仓库是本目录；不操作 `../mpvEx_New/`。
- 项目为 Android/Kotlin/Compose/libmpv。构建沿用 Gradle Wrapper；现有常用参数 `--no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`，按改动选窄检查。
- 用户默认要求覆盖安装：Standard Release，原 applicationId、原签名、递增 versionCode；交付 `../release/`，不默认提供共存 Debug 包。
- 签名参数在本机 `../.private/release-signing.json`，只在签名时读取，不输出、不提交；密码用受限进程环境传递。
- 发布包检查包名、版本递增、旧包证书一致、对齐及签名；本地签名与交付不代表设备验收或远端发布。
- 不把私有连接信息、凭据和远端文件名写入记录或新增日志。

历史改动查 Git，不再维护重复的 WORK_PROGRESS.md / modify.md 流水。
