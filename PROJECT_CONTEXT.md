# 项目当前状态

更新：2026-10-05（Asia/Shanghai）

- 项目：mpvEx，基于 libmpv 的 Android 视频播放器；Kotlin、Compose、Room、Koin，网络文件通过本地 NanoHTTPD 代理播放。主仓库 `/root/mpvex/mpvEx`；不操作相邻 mpvEx_New。
- 当前任务：WebDAV 视频进度两侧为 0、关闭视频再播放恢复；已按用户要求修复并编译。代码提交 `b031b1d`。
- 修复：当前会话 PLAYBACK_RESTART 可补齐遗漏的加载确认；保留事件代次、事件路径及 MPV 当前路径校验，与 FILE_LOADED 共用一次性确认，避免重复恢复历史进度。
- 证据边界：静态代码确认原加载门控没有恢复入口，但没有设备日志证明用户首次加载具体失败条件；仍待用户实际验收。
- 验证：10 项 PlaybackSessionTest 全部通过（0 failures/errors/skipped）；Standard Debug 编译成功；5 种 APK 签名校验均通过。没有功能/设备/私有网络测试。
- 交付：`/root/mpvex/release/webdav-progress-b031b1d-debug/` 包含五种 Debug APK、SHA256SUMS、check.log、build.log。包名带 `.debug`，可与原 Release 并存；未覆盖既有包。未设备安装或服务重启、未推送。
- 检查使用 `--no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`；测试任务 `:app:testStandardDebugUnitTest --tests app.marlboroadvance.mpvex.ui.player.PlaybackSessionTest`，编译任务 `:app:assembleStandardDebug`。
- 后续：用户验收首次打开 WebDAV 视频的两侧时间；若仍异常，采集加载/重启事件、代次、路径匹配结果及原始 MPV 时间值。既有 R1–R4、5 项原版遗留问题和重叠 GET 风险保持未修复，详见 PROJECT_ISSUES.md。
- 历史 Release 代码为 `325ac17`；之前审查和验证见 Git、WORK_PROGRESS.md、modify.md，不替代本轮设备验收。
