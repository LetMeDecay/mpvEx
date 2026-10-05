# 项目当前状态

更新：2026-10-05（Asia/Shanghai）

- 主仓库 `/root/mpvex/mpvEx`，Android/Kotlin/Compose/libmpv 播放器；不操作相邻 mpvEx_New。
- 当前任务：用户要求可覆盖更新的 Release，版本提升至 1.2.9-rev1005 / 131，保留正式包名及既有签名。待构建和签名交付；前批功能代码保持不变。
- 代码提交：`9084f51` 消除 HEAD 多余远端视频 GET；`8ad7b17` 后台预加载 MPVLib native 库（不创建播放实例），增加 PlayerStartup 分阶段日志。
- 分析报告：REEX_STARTUP_REVIEW.md。reex 使用 Flutter/libmpv，Java 层可确认 Activity 内 Flutter 引擎复用；不能证实 MPV 预热/复用或完整 Dart AOT 播放链。mpvEx 在主线程准备配置及初始化 MPV，真正 loadfile 等待 Surface 创建。
- 边界：HEAD 优化发生于后续建流，不解释界面前的等待。native 预加载主要针对每进程首次打开，同时增加仅浏览时的 native 内存占用；没有证据证明每次 1–2 秒延迟已消失。
- 验证：两批 Kotlin 编译通过；最终 Standard Debug 构建通过；五种 APK 的 apksigner verify 全部通过。本次未新增或运行单测/功能/设备/网络测试。前批进度恢复的 10 项测试属于 `b031b1d` 验证，不混作性能测量。
- 交付：`/root/mpvex/release/webdav-startup-8ad7b17-debug/`，含五种 Debug APK、SHA256SUMS、编译/构建日志及对照报告；可与 Release 并存，包含前批进度恢复。没有设备安装或服务部署，未覆盖既有包，未推送。
- 原始 APK 保持不变；本次 task scratch 在交付后清理。对照结论和 APK 哈希已保留于报告。
- 下一步：用户验收冷/热打开。若仍慢，用 `adb logcat -s PlayerStartup:D` 查看 queuePreparationMs、clickToActivityMs、configPreparationMs、mpvInitializationMs、activitySetupMs、activityToFirstDrawMs。日志没有路径/凭据；firstDraw 不是视频首帧完成的证据。
- 前批：`b031b1d` 修复当前文件 RESTART 补齐加载确认；首次事件为何未被接受仍缺设备证据。既有 R1–R4、5 项原版遗留和重叠 GET 风险保持未修复，见 PROJECT_ISSUES.md。
