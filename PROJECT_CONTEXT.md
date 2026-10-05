# 项目当前状态

更新：2026-10-05（Asia/Shanghai）

- 主仓库 `/root/mpvex/mpvEx`，Android/Kotlin/Compose/libmpv 播放器；不操作相邻 mpvEx_New。
- 当前任务已完成：用户要求直接覆盖更新。Release 1.2.9-rev1005，基础 versionCode 131，各 ABI 为 1310–1314，构建提交 `a7458fd`；沿用正式包名与旧 Release 签名证书。
- 代码提交：`9084f51` 消除 HEAD 多余远端视频 GET；`8ad7b17` 后台预加载 MPVLib native 库（不创建播放实例），增加 PlayerStartup 分阶段日志。
- 分析报告：REEX_STARTUP_REVIEW.md。reex 使用 Flutter/libmpv，Java 层可确认 Activity 内 Flutter 引擎复用；不能证实 MPV 预热/复用或完整 Dart AOT 播放链。mpvEx 在主线程准备配置及初始化 MPV，真正 loadfile 等待 Surface 创建。
- 边界：HEAD 优化发生于后续建流，不解释界面前的等待。native 预加载主要针对每进程首次打开，同时增加仅浏览时的 native 内存占用；没有证据证明每次 1–2 秒延迟已消失。
- 验证：两批 Kotlin 编译通过；最终 Standard Debug 构建通过；五种 APK 的 apksigner verify 全部通过。本次未新增或运行单测/功能/设备/网络测试。前批进度恢复的 10 项测试属于 `b031b1d` 验证，不混作性能测量。
- 当前交付：`/root/mpvex/release/app-standard-*-release.apk` 及对应 idsig。五包证书均与旧版一致，包名保持 app.marlboroadvance.mpvex，版本码递增，zipalign/v4 校验及移动后 APK SHA256 通过；验证清单 release-rev1005-verification.json，构建/签名日志 release-rev1005-*.log。旧 Debug 子目录是历史产物，用户明确以后默认交付覆盖更新版。
- 本次 Release 构建成功（3m48s），不重复已验证业务逻辑的测试；无连接设备，未安装，未运行功能测试或推送。构建输出与本次 scratch 已清理，日志保留在 release。reex 原始 APK 不变。
- 下一步：用户验收冷/热打开。若仍慢，用 `adb logcat -s PlayerStartup:D` 查看 queuePreparationMs、clickToActivityMs、configPreparationMs、mpvInitializationMs、activitySetupMs、activityToFirstDrawMs。日志没有路径/凭据；firstDraw 不是视频首帧完成的证据。
- 前批：`b031b1d` 修复当前文件 RESTART 补齐加载确认；首次事件为何未被接受仍缺设备证据。既有 R1–R4、5 项原版遗留和重叠 GET 风险保持未修复，见 PROJECT_ISSUES.md。
