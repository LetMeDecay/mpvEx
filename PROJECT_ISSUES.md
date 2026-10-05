# 问题与恢复记录

更新：2026-09-22（Asia/Shanghai）。基线 `8cf9547`，审查代码 `c4c13bd`。
本轮有 4 项确认的本分支问题、5 项原版遗留问题和 1 项未验证风险，均未修复。
以下确认指静态代码及调用链证据成立，不代表已进行设备复现。
历史审查提交 `634c52d`；R1–R4 和原版遗留问题仍未修复。当前 WebDAV 修复如下。

## 界面出现前等待 1–2 秒：reex 对照与预加载（2026-10-05）

- 用户补充：WebDAV 点击后播放器界面迟迟出现；本地 reex APK 表现更好。该阶段早于 HEAD 建流，不能把前批 HEAD 优化当作根因修复。
- 静态分析、APK 哈希及可确认/不可确认的区别见 REEX_STARTUP_REVIEW.md。
- 处理：应用后台提前加载 MPVLib native 库（不创建播放实例）；增加无敏感数据的 PlayerStartup 阶段日志。仅可能改善冷启动；每次等待的具体耗时仍待设备证据。
- 代价：仅浏览时也会载入 native 库，增加常驻内存。未改变播放器实例生命周期、Surface 或配置语义。
- Kotlin 编译、最终 Standard Debug 构建及五种 APK 签名校验通过；代码 `8ad7b17`，交付路径见 PROJECT_CONTEXT.md；保留原进度恢复与 HEAD 优化。本次未功能测试。

## 视频打开缓慢：代理 HEAD 多余建流（2026-10-05）

- 按前文 WebDAV 场景分析。serve 对 HEAD 也走实际数据流打开路径；已知文件大小时仍请求远端 GET，等待响应并可能下载随后被丢弃的数据；HEAD 携带 Range 还会进入分段建流。
- 修复：HEAD 忽略 Range，返回原有 MIME、完整文件大小和 Accept-Ranges，但不打开视频体；大小未知时仍保留文件大小查询。GET 路径不变。
- 收益边界：消除 HEAD 的多余视频 GET；未采集实际 MPV 请求轨迹，不能保证用户慢加载必定经过此分支，也没有实测速率/首帧数据。
- 其他观察：PlayerActivity.setupMPV 在主线程同步准备资源与用户配置；若配置目录是慢 SAF 提供方，可能拖慢播放器启动。本次未测到该条件，不改配置同步语义。原有重叠 GET 风险仍未证实，不扩大修复。
- 验证/交付：Standard Debug Kotlin 编译通过，已合入最终 `8ad7b17` Debug 交付；无功能或私有网络测试。

## WebDAV 进度两侧为 0（2026-10-05）

- 用户现象：关闭视频再播放恢复；当前时间和总时长均为 0。
- 静态证据：时间轮询以 PlaybackSession.loaded 为门控；FILE_LOADED 未通过校验时不会打开门控，原 PLAYBACK_RESTART 也要求 loaded，缺少恢复入口。尚无设备日志证明首次加载具体被哪个条件拒绝，不能断言已复现根因。
- 修复：当前文件/代次及 MPV 当前路径均匹配的 PLAYBACK_RESTART 可完成加载确认；FILE_LOADED 与 RESTART 共用一次性确认，防止重复状态恢复。旧代次、不同路径和空路径仍被拒绝。
- 验证：新增遗漏加载恢复、重复通知及跨文件/跨代次拒绝逻辑测试；10 项 PlaybackSessionTest 全部通过；Standard Debug 编译及五种 APK 签名校验通过，代码提交 `b031b1d`。交付见 PROJECT_CONTEXT.md；无真机验收。此前 R1–R4 和原版问题保持未修复。

## 本分支问题

### R1 / P2：正常退出先停止播放，后续保存可能被跳过

- 位置：`app/src/main/java/app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt:683-700`；关联 `:750-753`、`:2083-2085`、`:568-616`。
- 触发：播放过程中通过返回键或界面返回按钮结束 Activity，且未启用手动后台播放。
- 证据：`onPause` 在 `isFinishing` 时发送 `stop`，并跳过保存；`onStop` 才尝试保存，但保存函数要求 mpv 的 path 仍匹配且 time-pos 非空。stop 完成后这些条件不再成立。`onDestroy` 仅等待已有保存任务，没有注释声称的“最终保存”；`setReturnIntent` 也只返回数据给调用者，不写播放数据库。
- 后果：本次续播位置、轨道/速度等状态可能未落库，重开使用旧记录或从头播放。此前先切后台已经保存的记录不等于最终退出进度。
- 来源：原版 onPause 无条件发起保存且没有这个提前 stop；本分支 `da841bb` 引入该退出分支，当前快照保存仍未补齐退出入口。
- 下一步：停止/销毁 mpv 前同步捕获最终状态并交给现有串行保存任务，退出时等待持久化；避免在 stop 后重新读取播放器属性。

### R2 / P2：onNewIntent 的媒体探测重复打开并遗失文件描述符

- 位置：`app/src/main/java/app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt:2353-2359`；关联 `:1240-1244`、`:1402-1409` 和 `ui/player/PlayerUtils.kt:159-165`。
- 触发：已打开的播放器收到新的 content URI，提供方无法返回真实文件路径，走 `tryFileDescriptorFallback`。
- 证据：新增的 `parsePathFromIntent` 并非纯解析，会调用 `resolveUri` → `openContentFd` → `openFileDescriptor(...).detachFd()`，返回第一个 fd URI；随后 `getPlayableUri(intent)` 再次调用同一个解析器并打开第二个 fd。第一个结果仅用于判断有无媒体和代理识别，没有交给播放器，也没有关闭。
- 后果：每次命中此分支至少遗失一个原始描述符，反复打开文件会累积；本地路径解析成功的 content URI 不受此项影响。
- 来源：本分支 `87bfb83` 新增了前一次解析；不把原有 fd 生命周期问题混作本项。
- 下一步：复用一次解析的结果，或使用不打开文件的媒体存在性判断；为所有打开的 fd 明确移交/关闭路径。

### R3 / P2：目录刷新后，同路径新版本继续显示旧缩略图

- 位置：`app/src/main/java/app/marlboroadvance/mpvex/ui/browser/networkstreaming/NetworkBrowserViewModel.kt:175-185`、`:274-275`；关联 `NetworkBrowserScreen.kt:497-504`。
- 触发：页面仍保留某视频缩略图，远端以同名新文件替换它，size/etag/lastModified 已变化，然后下拉刷新目录。
- 证据：loadFiles 更新目录列表和时长，但没有比较 thumbnailVersions 并剔除该路径旧图/旧任务。ensureThumbnail 仅凭路径存在就返回；可见卡片的 LaunchedEffect 也仅以 path 和开关为 key，新版本不会触发重新请求。全局缓存的完整版本身份无法越过这层路径缓存。
- 后果：新的文件大小/日期和时长可以出现，但缩略图仍属于旧内容，直到退出、滚出保留窗口或手动刷新缓存等后续动作。
- 下一步：重载列表时按完整文件版本淘汰图片/在途任务，并以文件版本作为可见项请求的 effect key；发布结果时也校验当前列表版本。

### R4 / P2：新缩略图等待者可能加入已取消、尚未收尾的任务

- 位置：`app/src/main/java/app/marlboroadvance/mpvex/ui/browser/networkstreaming/NetworkMetadataProbe.kt:257-262`；关联 `:231-249`、`:180-181`、`:287-300`。
- 触发：最后一个等待者离开时 native 抽帧仍在运行；取消后尚未结束，用户立即返回同一文件，缓存没有命中。
- 证据：有 runningJob 时仅执行 `runningJob.cancel()`，不立即取消独立的 request.result，也不从 byKey 退休该请求。submit 只检查 `existing.result.isCancelled`，因此在 worker catch CancellationException 前会复用旧请求。旧 worker 随后取消共享结果，新页面等待者也收到取消，无法生成新图。后台任务取消分支存在同类窗口。
- 后果：快速离开/返回可能留下空白预览；浏览器自己的 thumbnailJobs 清理不能修复全局 dispatcher 的这段竞态，路径 effect 不会自动重试。
- 下一步：在锁内同步退休正在取消的请求，使后来等待者建立新请求；保留 native 单消费者和按请求实例清理的约束。后续针对取消尚未结束时重入的时序做窄范围逻辑测试。

## 原版遗留：继续保留，但不归因于本分支新增代码

已通过 `git show 8cf9547:<path>` / diff 核对旧报告中的关键路径：

| 问题 | 来源证据与当前位置 | 下一步 |
| --- | --- | --- |
| 本地不同目录同名文件共享播放记录（P2） | PlayerActivity.kt:3484/3504；原版两处本地分支同样返回 fileName | 统一稳定 URI/路径身份；谨慎处理歧义旧记录 |
| FTP 重连后继续使用旧局部客户端（P2） | clients/FtpClient.kt:109；整个文件与原版相同 | 重连后更新局部引用，按实例清理 |
| SMB 建流/查询失败缺少异常资源清理（P2） | proxy/NetworkStreamingProxy.kt:462/851；原版对应 :331/:757 已有相同问题 | 明确资源移交，异常时逐项回收 |
| 分块 NOT IN 误删有效本地元数据缓存（P2） | database/repository/VideoMetadataCacheRepository.kt:284；该文件和 VideoMetadataDao.kt 均与原版相同 | 计算失效路径，分块 IN 删除；不涉及媒体文件删除 |
| 配置读取 copyTo 两侧流未确定关闭（P3） | ui/preferences/ConfigEditorScreen.kt:100、AdvancedPreferencesScreen.kt:330/359；原版已有相同调用 | 嵌套 use 与 finally 清理 |

上述未写完整路径者均位于 `app/src/main/java/app/marlboroadvance/mpvex/` 下对应模块。
旧详细报告保留在外层 `REVIEW_ASTRA_LOW_2026-09-07.md`；本轮纠正归因，不宣称已修复。

## 未验证风险与边界

- NetworkStreamingProxy.kt:227：活动播放 URL 的新 GET 无条件替换旧请求，是否打断 MPV 合法重叠读取仍缺少真实请求时序，继续单列为风险，不计为确认缺陷。
- 没有本轮构建/单测/真机/私有网络测试；既有 84 项通过仅是历史交付证据，不能说明覆盖上述缺陷。
- 本轮无修复补丁，无待部署代码；审查记录随本批创建本地文档提交。没有适用的服务部署/重启操作；功能验收留给用户。
- 工具限制：环境无 rg，已使用 Git、grep 和定点文件读取；不构成审查阻塞。
- 下一恢复动作：用户若要求修复，先 R1，再 R2/R3/R4；原版遗留项另按用户范围处理。不能根据旧优化授权自动实施。
