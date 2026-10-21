# 当前状态

更新：2026-10-17。主仓库 `/root/mpvex/mpvEx`；Android/Kotlin/Compose/Room/libmpv。

- 当前授权：持续按 REFACTOR_PLAN.md 顺序优化、编译并交付原包名/签名的 Standard Release；不推送，不操作相邻仓库。不改全局 Codex 配置。
- 已落地：native 网关、加载/定位/暂停有界通道、native 条目身份、原子核心播放状态、退出快照及资源租约；通知/PiP 共用核心控制与状态。其他控件与完整元数据尚未收口。
- P2：配置/字体/脚本后台构建完整内容快照，原子发布、引擎租约与失败回退；界面先绘制准备提示再初始化 native（初始化仍在主线程）。当前文件优先，数据库按固定 ID 快照读取最多 100 项窗口；目录通过单次令牌交接，代理客户端按需创建。本地历史采用 URI/规范路径身份，歧义旧文件名记录保留但不自动迁移。
- P3：GET 请求彼此独立，换片/退休关闭旧请求及输入流；FTP 重连采用新实例，SMB 部分建流失败逆序释放。认证 WebDAV native 直连/自动回退尚未启用，边界见 ISSUES。
- 本批 P4：目录刷新及结果发布核对文件版本；单消费者缩略图调度立即退休取消请求，最多 64 个待执行项，新等待者不加入旧 native 工作。缓存只分块删除明确失效路径；播放列表翻页修正当前项记忆。
- 本批 P6：历史/默认设置/自动选轨共享会话及用户交互有效性校验；普通输入只取消恢复，不取消定位。外部字幕迟到添加核对代次；倍速、轨道、同步设置和缩放分别可配置。内置轨道按唯一描述匹配，保留关闭状态，旧数字 ID/歧义不猜测；数据库 11→12 新增描述字段，外部字幕按原 URI 匹配主次选择，未知或歧义描述不猜测。
- 网络目录收藏已接入目录顶栏和网络页；只存 connectionId + path，数据库 12→13 新增表，连接删除级联清理，旧页面不能为已删除连接新增收藏。
- 外部字幕读取在 IO；sub-add 进入加载/定位共用后台串行通道（最多 16 项附加工作），换片/拒绝/退休确定关闭未交付资源；原 URI 映射用于主次字幕恢复，同名不同文件重置附加轨道，迟到扫描核对代次。
- 常用控件已迁移到 typed PlaybackControls：倍速/手势、音轨、章节、解码器、音调校正和音频/字幕同步设置；参数按属性合并（最多 64 个不同设置）；文件级设置换片清除，实例级统计/Anime4K 保留至执行，退休全部清除，附加字幕与加载同一后台执行者。字幕样式、视频滤镜/去色带与声道也已接入 typed 控制；统计/Anime4K 已进入同一后台通道，其他元数据和部分业务兼容入口仍待迁移。
- 时间线、轨道/章节列表、轨道选择、倍速及解码器常用状态按当前代次原子发布；原生属性事件仅标记失效，实际读取在 IO，返回后核对代次。列表只在结构变更时解析；换片/退休清空，不继承全局属性缓存。连续音轨/字幕操作按最新未确认意图计算，有 10 秒回退上限。
- 附加音轨已改为 IO 打开独立资源租约，audio-add 按代次进入同一有界队列；迟到结果不写入新视频，未交付资源确定关闭，原 URI 映射供稳定轨道描述使用。外部音轨 URI 尚未持久化，不能宣称重启后自动恢复。
- 逐帧/截图接入 typed 控制和同一串行队列；暂停与单帧命令按组执行，截图等待命令完成并核对非空文件，不再固定等待。调用方取消后等待正在执行的 native 工作结束才释放文件；MediaStore 空输出/发布失败及取消会清理未发布条目，临时文件使用独立名称。面板时间线改用当前代次快照，旧面板关闭不恢复新视频。
- 统计页按已执行选择计算显示切换，连续点击合并后不误切换；Anime4K 着色器准备与应用串行，移除面板独立 IO 作业及空回调。视频宽高随代次发布，用于面板分辨率判断；换片清空，旧代次结果不回填。
- 最新已交付：代码 `9764f73`，Release 1.3.1-rev1020.5，versionCode 154（ABI 1540–1544）；GitHub Release 已发布 7 个 APK（Standard 5 ABI + PlayStore universal + F-Droid arm64），本地构建/签名并手动发布；1.3.1-rev1020.4 因缺少 `subfont.ttf` 导致播放配置失败，已标注弃用。历史交付在 `../release-github-1020.5/`、`release-rev1020.4`（已弃用）、`release-rev1020.3/` 等。
- 当前 PATH 无可用 ADB 命令；未安装、未做启动健康或功能/性能验收，WebDAV 1–2 秒等待与进度恢复尚无设备结论。不要把首次 UI 绘制当视频首帧。
- 会话恢复（2026-10-21）：新增 `SessionStateStore`，持久化最后 tab 与网络目录（connectionId + 名称 + path）；冷启动时恢复 tab 并在栈顶重建 `NetworkBrowserScreen`，返回连接列表时清除目录记录。修复系统内存压力杀进程后（`ApplicationExitInfo` 记录 `umms1_normal_mem_pressure`）重新打开落到首页的问题。本地构建约定：`-Pabi=arm64-v8a` 只出 arm64 APK；全 ABI/PlayStore/F-Droid 交给 workflow，且运行 workflow 前必须先询问。
- 播放配置修复（2026-10-21，rev1020.5）：mpv-android 1.0.0 不再内置 `subfont.ttf`，配置快照按固定列表复制资源时抛异常，导致播放提示「无法准备播放器配置」；改为仅复制实际存在的内置资源。
- 流程约定：**每次触发 GitHub Actions workflow 前必须先询问用户**，默认使用本地构建与手动发布，避免浪费 Actions 次数与时间。
- 音量与播放器收尾（2026-10-20，rev1020.4）：物理音量键改走 `AudioManager.adjustStreamVolume(STREAM_MUSIC)`，跟随系统步进与上下限、不再在最大音量后继续加 mpv 增益；右侧滑动保持原有映射与增益逻辑。补上游播放器改动：`attachBaseContext` 统一 fontScale、支持显式 `file_path` extra、退出时同步写入播放状态、播放列表跳过已删除的本地文件。
- 上游合并（2026-10-20）：已合入 `marlboro-advance/mpvEx` 的 40 个提交（基线 `4151a45`，上游 1.3.1）；版本定为 `1.3.1-rev1020.4`（versionCode 153）。决策：移除 Wyzie 在线字幕搜索、保留网络缩略图开关与 `LocalMediaIdentity`、保留本分支 WebDAV/播放器实现并吸收上游缺失文件跳过/字幕提升/媒体信息 HTTP 支持。依赖切换到 Maven `mpv-android 1.0.0` 与 `mediainfo-android 1.0.2`（`KeyMapping` → `keyMapping` 已适配）。冲突解决后 `:app:testStandardDebugUnitTest` 与 `:app:assembleStandardRelease` 通过；设备回归待手机重新连接。
- CI 发布（2026-10-20）：`.github/workflows/release.yml` 改为单 job 构建 Standard/PlayStore/F-Droid、逐 ABI zipalign+签名、校验包名/版本、生成 SHA-256 并发布非 draft Release；新增 `workflow_dispatch`（输入已存在的 tag）。仓库 Secrets 已配置 `SIGNING_KEYSTORE`/`SIGNING_KEY_ALIAS`/`SIGNING_STORE_PASSWORD`/`KEY_PASSWORD`（仅名称记录）。本次仅部署，未触发运行。
- 已发布：GitHub Release `v1.2.9-rev1020.3`（LetMeDecay/mpvEx），7 个 APK（Standard 5 ABI + Play Store universal + F-Droid arm64）与 SHA-256 清单；tag 指向 `123bc3b`，`master` 已推送。
- 死代码与方向修正（2026-10-20，rev1020.3）：删除不可达的 NetworkStreamingProvider（所有协议都走代理，provider 的 getUri 分支恒不执行）、NetworkClient.getFileUri、代理内未使用的 getStream/getStreamWithOffsetGeneric、CircularRevealShape、未使用的视频工厂与 seek-with-text、11 个无人处理的 user-data 观察；删除下一集预取链路（它写入的 RangeSegmentCache 播放路径并不读取，切集白下约 4MB）。时长/分辨率探测新增 MP4/Matroska 容器头直接解析（WebDAV 有界 head 1MB + tail 2MB，带鉴权），解析失败仍回退原有 MMR+代理路径；用真实 rclone 文件验证 S01E01/S01E00 时长与分辨率解析正确。
- 浏览器/播放健壮性（2026-10-20，rev1020.2）：目录列表改为派生排序流（不再把排序结果写回源流，避免每次元数据更新二次排序）；列目录按 href 路径剔除集合自身，不再假设服务器把自身排第一；加载 20s 未就绪时标记 Failed，控件显示加载失败而不是一直转圈；WebDAV 播放请求在 408/429/5xx 或传输失败时、且响应体尚未交付前重试一次（客户端错误不重试）。设备验证：Bangumi→Fate→Season 1 列表无自身条目、14 集正常显示，WebDAV 播放正常；单测新增重试 3 例通过。
- 启动/预览优化（2026-10-20，rev1020.1）：无外部配置目录时用输入指纹复用配置快照，未变更启动不再拷贝/哈希 15MB 字体（设备上 copy+sha256 约 40ms，冷盘更高）；内置资源每版本解包一次并硬链接进快照；点击播放时取消该目录的时长/缩略图探测并预热 WebDAV 上游连接；缩略图失败回退按解码模式批量取时间戳（批量不可用时回退单张）；磁盘预览缓存限制 256MB 并按 mtime 淘汰。设备验证：连续两次启动第二次无 stage 目录、快照目录 mtime 不变，本地与 WebDAV 播放正常。
- 黑屏根因（2026-10-20，设备已确认）：`BaseMPVView.initialize()` 在末尾才注册 SurfaceHolder 回调，而本应用把 native 初始化推迟到首个界面绘制之后；此时 SurfaceView 的 surface 已经创建，`surfaceCreated` 不再回调，`MPVView.surfaceReady` 始终为 false，加载一直等待 surface。设备证据：mpv 日志 0.007s 初始化后无 `wid`、143s 内没有 `loadfile`；退到后台再回前台重建 surface 后立即 attach 并起播。修复：`initialize()` 之后调用 `MPVView.adoptExistingSurface()`，接管已存在的 surface（含尺寸）。设备验证：本机视频与 WebDAV 均在 wid 0.007s、loadfile 0.056s、0.1s 起播。
- 当前黑屏修复：审查 OpenCode 的 147 版；用户尚未测试该版，原设备根因仍未确认。实际播放按活动 streamId 使用独立 HTTP 适配：直接 GET/HEAD，不以 PROPFIND 大小校验；保留上游 200/206/416、长度和范围，接受合法缩短 206，未知长度使用 chunked，不伪造定位能力。播放不再拼接探测缓存；后台探测继续严格 Range/缓存边界。认证策略未变，HTTP 失败只记录状态码，不记录远端异常消息。
- 本批验证：20 项 WebDAV 播放/探测与请求资源检查通过（47s）；148 / 1.2.9-rev1017 首次 Release 在 R8/lintVital 阶段遭内核 OOM kill（pid 已核对）；改用单 worker、1536MiB heap 和 2 个 JVM 处理器重试，Release/R8/lintVital 已通过（3m12s），已签名交付。日志/清单见 `.tmp/webdav-black-screen-20261017/`；前批历史查 Git。
- 下一步：用户验证同一 WebDAV 文件的画面/声音/进度与定位；若仍失败，按代理 HTTP 状态和 native END_FILE/Surface 日志定位。其余重构待本问题确认后继续，边界见 PROJECT_ISSUES.md。
