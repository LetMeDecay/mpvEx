# 当前状态

更新：2026-10-13。主仓库 `/root/mpvex/mpvEx`；Android/Kotlin/Compose/Room/libmpv。

- 当前授权：按 Codex 全局配置精简项目规则/上下文；完整重构方案落盘后开始分阶段编码。已读取 `/root/.codex/config.toml` 的 developer_instructions，不修改全局配置。
- 设计入口：REFACTOR_PLAN.md；P0/P1a 已完成：MpvGateway 接入 Activity/ViewModel/Service，保持生命周期与线程语义；其余调用与完整架构尚未迁移。
- 记录整理：AGENTS 只保留项目差异；删除 WORK_PROGRESS.md / modify.md 重复流水，历史在 Git；外层 CONTEXT 仅留索引；签名凭据已移到仓库外权限受限配置，记录不含凭据。
- 最新已交付：Release 1.2.9-rev1005，代码 `a7458fd`；`../release/` 根目录五种 APK + idsig，原包名/签名。历史 Debug 不是默认交付。没有设备安装或性能验收结果。
- 已有功能改动：进度恢复 `b031b1d`、HEAD 修正 `9084f51`、native 库预加载 `8ad7b17`；不把既有构建/测试结果算作新架构验证。
- 验证：差异/引用及迁移等价性检查通过；Standard Debug Kotlin 编译通过（1m14s）。未运行功能测试、未打包或设备安装，不推送；当前已发布 APK 仍为 a7458fd。编译日志暂留 `.tmp/context-refactor-20261013/compile.log`。
- 未解决问题仅见 PROJECT_ISSUES.md；reex 静态证据见 REEX_STARTUP_REVIEW.md。
- 下一步：P1b 迁移剩余 native 入口并设计事件身份/命令顺序；再按计划推进。不要重复 P0/P1a 或已通过检查，不宣称全部重构或性能验收完成。
