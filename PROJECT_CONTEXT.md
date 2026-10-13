# 当前状态

更新：2026-10-13。主仓库 `/root/mpvex/mpvEx`；Android/Kotlin/Compose/Room/libmpv。

- 当前授权：按 Codex 全局配置精简项目规则/上下文；完整重构方案落盘后开始分阶段编码。已读取 `/root/.codex/config.toml` 的 developer_instructions，不修改全局配置。
- 设计入口：REFACTOR_PLAN.md；P0/P1a 已完成；P1b 本批迁移剩余应用侧 native 入口并引入 PlaybackCommandQueue（加载/定位各一个待执行槽）。完整 Coordinator、native 事件身份及 stop/destroy 排序仍待收口。
- 记录整理：AGENTS 只保留项目差异；删除 WORK_PROGRESS.md / modify.md 重复流水，历史在 Git；外层 CONTEXT 仅留索引；签名凭据已移到仓库外权限受限配置，记录不含凭据。
- 最新已交付：Release 1.2.9-rev1005，代码 `a7458fd`；`../release/` 根目录五种 APK + idsig，原包名/签名。历史 Debug 不是默认交付。没有设备安装或性能验收结果。
- 已有功能改动：进度恢复 `b031b1d`、HEAD 修正 `9084f51`、native 库预加载 `8ad7b17`；不把既有构建/测试结果算作新架构验证。
- 本批验证：委托迁移等价性/直接引用检查通过；Standard Debug 编译及 15 项针对性单元测试通过（队列 5、PlaybackSession 10）。日志保留 `.tmp/playback-p1b-20261013/check-final.log`。未做功能/性能测试、未打包新 APK；ADB 无连接设备，未安装；已发布 APK 仍为 a7458fd，不推送。
- 未解决问题仅见 PROJECT_ISSUES.md；reex 静态证据见 REEX_STARTUP_REVIEW.md。
- 下一步：按 REFACTOR_PLAN.md §12 收敛首次 Surface 加载、绑定 native playlist_entry_id、统一停止/销毁和历史快照；之后推进 P2。不要重复已通过检查，不宣称全部重构或性能验收完成。
