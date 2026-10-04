# 项目当前状态

更新：2026-10-05（Asia/Shanghai）

- 项目：mpvEx，基于 libmpv 的 Android 视频播放器；Kotlin、Compose、Room、Koin，SMB/FTP/WebDAV 通过本地 NanoHTTPD 代理播放。
- 主仓库：`/root/mpvex/mpvEx`；不操作相邻 `mpvEx_New`。恢复时先读 WORK_PROGRESS.md、外层 CONTEXT.md、modify.md，再结合本文件和 PROJECT_ISSUES.md 核对 Git。
- 当前状态（2026-10-05）：用户报告 WebDAV 首次播放进度两侧为 0、关闭视频重开恢复，授权修复并编译。已补充当前会话 PLAYBACK_RESTART 的加载确认入口，保留路径/代次隔离并防止重复初始化；10 项 PlaybackSessionTest 全部通过，待编译交付 Standard Debug APK。实际故障触发条件尚未真机证实。
- 审查基线：本地 Git 原版 1.2.9 `8cf9547` → 审查起点 `c4c13bd`；176 文件，新增 11228 行、删除 4920 行，包含文档/资源。起点工作区干净。未 fetch，未声称与最新上游比较。
- 主要差异：网络元数据/缩略图缓存与预热、首页网络入口、目录排序与整目录连播、WebDAV 区间缓存/共享读取/取消、播放状态与定位控制、语言切换/中文资源、数据库 8→11；移除 Ambient Mode 和 Lua 功能。
- 审查覆盖：重点阅读网络代理及区间辅助类、缓存/调度、网络浏览、连接仓库、播放器加载/定位/退出调用链；抽查迁移、语言及设置差异。不代表逐行穷尽全部代码。
- 结果：确认 4 项本分支静态代码缺陷；旧报告的 5 项缺陷属于原版遗留，仍未修复；重叠 GET 保持未验证风险。详细证据、触发条件和恢复方向见 PROJECT_ISSUES.md。
- 本轮代码检查：Git 基线差异、调用链及取消时序分析；原版遗留项来源核对。未重复已有 84 项单测、未构建、未运行真机/网络/功能测试。临时 AAR 字节码读取未改变仓库，也不作为运行验证证据。
- 本轮改动：仅 PROJECT_CONTEXT.md、PROJECT_ISSUES.md、WORK_PROGRESS.md 和 modify.md 退出记录；同步更新外层 CONTEXT.md（不受主仓库 Git 管理）。无业务代码修改；主仓库记录随本批本地提交，不推送。
- 部署：本轮仅审查/记录，无需安装或重启；没有适用的服务部署操作。APK 未重新生成，既有交付代码仍为 `325ac17`，历史签名包位于外层 `release/`，本轮未重新验证。
- 已有代码检查命令：`./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:testStandardDebugUnitTest :app:checkUserVisibleStrings :app:assembleStandardDebug`；后续应按实际补丁缩小检查范围。
- 已有交付流程：`:app:assembleStandardRelease` 后按既有本地流程签名、校验，再移入外层 `release/`；没有设备安装/服务重启流程。不在记录中保存签名凭据。
- 检查：退出前确认审查提交 `634c52d` 已存在且工作区原本干净；本次仅检查记录差异，不重复运行代码验证。无待部署代码。
- 下一步：结束工作，等待用户新指令；只有用户要求修复时才继续 R1–R4。功能验收留给用户。

最近历史：2026-09-07 交付代码 `325ac17`；2026-09-22 恢复相对原版的审查。更早细节见 Git 历史与既有交付记录。
