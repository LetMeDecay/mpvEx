# 当前状态

更新：2026-10-13。主仓库 `/root/mpvex/mpvEx`；Android/Kotlin/Compose/Room/libmpv。

- 当前授权：按 Codex 全局配置精简项目规则/上下文；完整重构方案落盘后开始分阶段编码。已读取 `/root/.codex/config.toml` 的 developer_instructions，不修改全局配置。
- 设计入口：REFACTOR_PLAN.md；当前阶段 P0/P1a，先建立 MPV 访问边界，保持生命周期与线程语义。完整架构尚未实现。
- 记录整理：AGENTS 只保留项目差异；删除 WORK_PROGRESS.md / modify.md 重复流水，历史在 Git；外层 CONTEXT 仅留索引；签名凭据已移到仓库外权限受限配置，记录不含凭据。
- 最新已交付：Release 1.2.9-rev1005，代码 `a7458fd`；`../release/` 根目录五种 APK + idsig，原包名/签名。历史 Debug 不是默认交付。没有设备安装或性能验收结果。
- 已有功能改动：进度恢复 `b031b1d`、HEAD 修正 `9084f51`、native 库预加载 `8ad7b17`；不把既有构建/测试结果算作新架构验证。
- 验证：本次配置/设计整理待差异与引用检查；P1a 编码/编译待完成。无服务部署或安装；不推送。
- 未解决问题仅见 PROJECT_ISSUES.md；reex 静态证据见 REEX_STARTUP_REVIEW.md。
- 下一步：完成 P1a 窄编译，记录真实结果；后续 P1b 统一事件身份与命令顺序，不能自动宣称全部重构完成。
