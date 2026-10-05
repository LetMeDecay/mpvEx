# reex 与 mpvEx 播放启动路径静态对照

日期：2026-10-05。用户现象：WebDAV 点击后约 1–2 秒播放器界面才出现；reex 体验更快。

## 方法与边界

- 输入：本地 `reex-arm64-v8a-release-v1.8.9.apk`，SHA-256：`d71d4ac33ab8f1ebe9745bc0eb5660a570226d469a8dcd802be0a6f4a3a483ff`。
- 工具：本地 apktool 2.7.0 静态拆解、smali、ELF 字符串；javap 检查本项目 `app/libs/mpv-android-lib-v0.0.1.aar`。
- 未安装/运行 reex，未访问网络或用户服务器；没有耗时测量。Dart AOT 核心未恢复成源代码，字符串仅用于识别组件，不作为调用时序证据。

## 已确认的差异

1. reex 包含 libflutter.so、libapp.so、libmpv.so、libmpvclient.so；主类 `xyz.re.player.ex.MainActivity` 基于 Flutter Activity。smali 的 `B(Context)` 返回已有字段 k 中的 FlutterEngine，否则创建并保存；onCreate 主要委托 Flutter 父类。只能确认该 Activity 的 Flutter 引擎复用，不能推导 MPV 实例复用或预热、WebDAV 直连策略。
2. mpvEx 的 NetworkBrowserViewModel.playVideo 在主线程协程中准备全目录队列并启动独立 PlayerActivity。WebDavClient 构造没有网络 connect，因此不能把注册队列称为逐文件网络握手。
3. PlayerActivity.onCreate 在主线程依次 setupMPV、音频/控件/媒体会话等；setupMPV 同步复制必要资源、同步用户配置/字体目录，然后 player.initialize。
4. AAR 的 MPVLib 静态初始化调用 System.loadLibrary("mpv") 和 System.loadLibrary("player")，其余为观察者、协程作用域和属性容器；没有调用 create/init。可独立提前完成库加载。
5. AAR 的 BaseMPVView.initialize 顺序执行 MPVLib.create、配置、initOptions、MPVLib.init、postInitOptions、Surface 回调注册及属性观察。playFile 只保存路径，surfaceCreated 才发出 loadfile。因此 native/配置准备及 UI 初始化都位于真正文件加载之前。
6. AAR Utils.copyAssets 会比较文件长度，已有匹配文件不会重复完整复制；不能把每次等待都归因于复制大字体。

## 本次处理

- 后台预加载 MPVLib 类及 native 库，不创建/共享播放实例，不改变 MPV 配置及销毁语义。主要可能改善每进程首次播放；若用户每次都慢，这项不是完整答案。提前加载会增加仅浏览时的 native 内存占用。
- 增加 PlayerStartup 日志：queuePreparationMs（附队列数量）、clickToActivityMs、configPreparationMs、mpvInitializationMs、activitySetupMs、activityToFirstDrawMs、nativeLibrariesMs。均为单调时钟差值，不记录路径、服务器或凭据。firstDraw 是绘制回调，不是已测得屏幕展示/视频首帧时间。
- 前批提交 9084f51 消除 HEAD 多余远端 GET，但用户已明确等待发生在界面前，因此不将 HEAD 修复解释为本次 1–2 秒根因。
- 不贸然复用 MPV 实例或修改 Surface 生命周期：现有 BaseMPVView、观察者、配置、退出清理对单实例有严格假设，需要进一步证据与专项设计。

## 下一判定

安装本批 Debug 包后，由用户观察冷/热打开体验；如仍慢，读取 PlayerStartup 阶段耗时：queuePreparation 较高查目录大小/队列；configPreparation 较高查 SAF 配置/字体目录；mpvInitialization 较高查 native 初始化/配置；activitySetup 与 firstDraw 差距较大查布局/Surface。没有这些数据不能声称已解释或消除 1–2 秒延迟。
