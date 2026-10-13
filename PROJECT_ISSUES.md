# 未解决问题

更新：2026-10-13。以下是证据和风险清单，不是自动修复队列；原审查行号可能随重构变化，应按符号定位。

## 待设备证据

- WebDAV 点击后界面等待 1–2 秒：已有 native 库预加载与 PlayerStartup 分段日志；没有冷/热启动耗时结果，不能宣称已解决。分析见 REEX_STARTUP_REVIEW.md。重构按 REFACTOR_PLAN.md 分阶段推进。
- 进度两侧为 0：`b031b1d` 已增加 RESTART 加载确认恢复；首次 FILE_LOADED 未被接受的具体条件和设备验收结果仍未知。
- 活动播放 URL 新 GET 会取消旧请求：是否中断 MPV 合法重叠读取仍需请求时序证据，不能仅凭代码假设确认。

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

## 重构边界

- reex Dart AOT 播放链未完整还原；已确认架构和推测必须区分。
- MPV AAR 将实例初始化与 Surface 生命周期耦合；先封装访问边界，再验证实例与渲染拆分，不能在首批同时替换。
- 无当前设备验收或私有服务器数据；未执行功能测试。旧测试结果不能替代新架构验证。
