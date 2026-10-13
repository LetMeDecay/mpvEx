# 当前状态

更新：2026-10-13。主仓库 `/root/mpvex/mpvEx`；Android/Kotlin/Compose/Room/libmpv。

- 当前授权：按 Codex 全局配置精简项目规则/上下文；完整重构方案落盘后开始分阶段编码。已读取 `/root/.codex/config.toml` 的 developer_instructions，不修改全局配置。
- 设计入口：REFACTOR_PLAN.md；P0/P1a 已完成；P1b 核心加载/事件/退出链已收口：首次加载入队、native 条目身份、销毁调用计数及停止前最终快照。完整 UI 状态/控件意图汇聚与 P2–P6 仍待推进。
- 记录整理：AGENTS 只保留项目差异；删除 WORK_PROGRESS.md / modify.md 重复流水，历史在 Git；外层 CONTEXT 仅留索引；签名凭据已移到仓库外权限受限配置，记录不含凭据。
- 最新已交付：Release 1.2.9-rev1013，代码 `8a7a768`，基础 versionCode 132（ABI 1320–1324）；`../release/` 五种 APK + idsig 已覆盖旧包。原包名/签名、版本递增、16KB ZIP 对齐及签名验证通过；交付哈希与校验包一致。
- 已有功能改动：进度恢复 `b031b1d`、HEAD 修正 `9084f51`、native 库预加载 `8ad7b17`；不把既有构建/测试结果算作新架构验证。
- 本批验证：Standard Debug 编译与 24 项针对性单元测试通过；Standard Release/R8/lintVital 构建通过（3m26s）。日志和交付校验清单保留 `.tmp/playback-identity-20261013/`。ADB 无设备，未安装或做启动健康/功能/性能验收；不推送。
- 未解决问题仅见 PROJECT_ISSUES.md；reex 静态证据见 REEX_STARTUP_REVIEW.md。
- 下一步：按 REFACTOR_PLAN.md §12 推进状态汇聚及 P2；不重复已通过检查，不宣称全部重构或性能验收完成。
