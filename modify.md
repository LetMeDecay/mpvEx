## 相对原版的代码审查（2026-09-22）

- 按最新要求对比本地原版 1.2.9 `8cf9547` 与 `c4c13bd`，重点检查新增网络缓存/调度、代理和播放器调用链。
- 确认 4 项本分支静态缺陷：退出保存时序、onNewIntent 重复解析导致 fd 遗失、目录更新后旧缩略图残留、缩略图取消任务被新等待者复用；详细证据见 [PROJECT_ISSUES.md](PROJECT_ISSUES.md)。
- 旧报告 5 项缺陷均核对到原版来源，保持未修复；重叠 GET 仍为未验证风险。本轮仅保存审查记录并本地提交，不修改业务代码、不构建或部署，功能验收留给用户。

## 整体审查记录与搁置（2026-09-07）

- Astra low 整体静态审查发现本地同名播放记录冲突、FTP 重连引用、SMB 异常资源回收、分块清理误删元数据缓存、配置读取流关闭五项问题；尚未修复，重叠 GET 另列未验证风险。
- 按用户最新要求写入 `/root/mpvex/CONTEXT.md`，并在 WORK_PROGRESS.md 标明暂时搁置；完整报告位于 `/root/mpvex/REVIEW_ASTRA_LOW_2026-09-07.md`。等待明确恢复指令，不自动继续。
- 本次仅保存文档，不修改代码或 APK，不运行测试、构建，不新增推送或发布。

## 草稿清理与上下文整理（2026-09-07）

- 按用户要求删除 WebDAV 候选方案草稿及待实施入口；已完成的优化与修复记录保留。
- 最近预览图 T1＋T2、WebDAV 读取优化及四项质量修复的现行行为、资源边界、提交、验证和 APK 状态已汇总到 `/root/mpvex/CONTEXT.md`；重叠 GET 仍为未验证风险。
- 本次仅调整文档，不改变代码或安装包，不重新编译，不新增推送或发布。

## 本次审查修复交付（2026-09-07）

- 四项已确认问题已按顺序本地提交：fb02b69（路径）、4802b56（探测取消）、39bb24c（片尾门控）、325ac17（保存交接）。第五项合法重叠 GET 风险证据不足，保留真机验证限制，未推测改动。
- 最终 84 项单元测试全部通过，字符串检查、Standard Debug（48s）和 Standard Release（3m）通过。
- 五种 APK＋idsig 已通过 zipalign、v2/v3/v4、版本、ABI 和包内 Git 325ac17 校验并更新到 `/root/mpvex/release/`；移动后十个文件 SHA-256 一致，`app/build` 和 `.kotlin` 已清理。
- 当前版本仍为 1.2.9-rev0906，versionCode 1300–1304；没有访问私有 WebDAV，没有新增推送、tag、发布或上传。详细清单和恢复入口见 WORK_PROGRESS.md。

## 审查修复：图片保存交接及 GET 风险（2026-09-07）

- 已生成图片的界面发布与等待保存队列容量使用不可取消交接；后台取消跳过已完成结果。单一生产者仍等待交接完成才生成下一张，保持保存中、已排队、等待入队最多三张图片。
- 4 项保存队列测试通过；新增显示后取消仍保存/阻止生产第四张、写消费者关闭能释放交接测试。
- 活动播放 URL 每次 GET 替换旧响应的风险仍未验证：本地播放器配置、JNI 桥接和 FFmpeg 二进制字符串不足以确认合法重叠请求的实际时序；本次未据此改变策略，仍需真机请求轨迹。

## 审查修复：片尾网络门控（2026-09-07）

- 片尾停留时释放新元数据任务，保留选中流身份；eof=false 或确认未到片尾的 PLAYBACK_RESTART 恢复播放优先。自动切集和循环分支保持门控，不在仍播放时放行。
- 旧文件 EOF/恢复事件不能改变新文件门控；退休流不会被迟到重播事件复活。4 项策略测试通过，新增终点、重播、切集与退休回归。

## 审查修复：独立探测取消（2026-09-07）

- native 探测取消时永久退休该独立流的请求注册表，立即拒绝迟到 GET/重试；原 finally 负责注销和协议客户端清理，取消 watcher 不执行阻塞 disconnect。
- 播放流临时取消仍允许重新打开。4 项请求生命周期测试通过，新增取消回调未完成时的迟到重试回归测试。

## 审查修复：WebDAV 路径（2026-09-07）

- 客户端、直接 URI 与代理完整/区间 GET 共用基础目录拼接；dav 与 /dav 配置现在一致，空目录、根目录不重复分隔符。
- 新增 WebDavPathTest，覆盖基础目录和文件路径有无斜杠及连接根路径；针对性测试通过。

# mpvEx 修改记录

> 本文件记录相对上游官方 `v1.2.9` 的功能差异。当前版本：`v1.2.9-rev0906`
>
> 官方版本已经提供网络连接与 SMB / FTP / WebDAV 播放能力；本文仅记录本分支相对官方版本新增的功能。

## WebDAV 方案 2 → 3 → 4 → 5（2026-09-07）

- 最终代码 `a4a195c` 的 Standard Release 构建通过（3m7s）；五种 APK＋idsig 经 zipalign、v2/v3/v4、版本、ABI 和包内 Git 校验，已移入 `/root/mpvex/release/`。签名生成时间：北京时间 2026-09-07 01:08:34–01:08:39，版本仍为 1.2.9-rev0906（基础 versionCode 130）。app/build 和 .kotlin 已清理；本批未推送或发布。

- 用户已授权依次实施四项播放读取优化，恢复入口为 [WORK_PROGRESS.md](WORK_PROGRESS.md)。各批次已验证并本地提交；不扩大原低峰推送范围。
- 方案 5 完成：每个代理响应关联取消范围和远端 Call，注销流、替换文件和当前播放地址的新 GET 会清理旧响应；HEAD、元数据独立地址、手势开始和缓存内 seek 不触发播放替换取消。共享下载只有最后一个消费者离开才取消源请求。
- 相同强版本及相同小区间使用独立消费者游标共享渐进读取，最多 4 个共享下载缓冲、合计 8 MiB（不含原 LRU 及普通请求缓冲）；没有空位时走原单流。完整源区间校验后缓存，失败或短读不缓存。预取的协程取消覆盖响应体读取，native 探测增加独立取消观察任务。普通 WebDAV GET 也纳入请求跟踪，有可靠缓存时可复用区间。
- 方案 5 首轮 78 项测试及 Debug 构建通过；补充真实新 GET 替换旧响应的场景后，79 项单元测试、字符串检查及 Standard Debug 构建通过（31s）。本地 HTTP 测试验证了收到响应头后取消实际 Call，未访问用户私有服务。
- 方案 4 完成：小区间响应边读边返回，仅完整消费并校验后缓存；提前关闭、短读或异常不缓存。下一集预取改为实际消费有界响应，并持续检查是否还应预取。新增 3 项流读取测试，72 项单元测试及字符串检查通过（32s）。
- 方案 3 完成：网络播放期间暂停新元数据网络探测，缓存命中照常返回；缓冲充足且播放位置稳定时才进行下一项头尾预取，切换、退出及旧队列释放按实际播放身份更新状态。新增 3 项调度测试，69 项单元测试和字符串检查通过（37s）。
- 方案 2 完成：有强 ETag 的 Range 请求可按顺序复用头部、中间和尾部缓存，只请求缺口；If-Match 与响应检查防止混合文件版本。缺少强验证标识时保留单流，缓存上限不变。新增 6 项测试，66 项单元测试及字符串检查通过（1m11s）。

## 网络预览图 T1＋T2（2026-09-06）

- T1：磁盘缓存最多两个解码任务并行；在文件锁内打开稳定文件句柄，在锁外解码。相同文件版本的缓存读取共享在途任务，发布前再次检查失效代次。
- T2：新预览图通过校验后先进入内存并返回界面，再交给单消费者保存队列；压缩在文件锁外执行，最终替换与清理互斥。保存失败不撤销已显示图片，后续保存仍可继续。
- 保存侧最多持有三张图片（正在写入、已排队、单个生产者等待入队各一张），队列满时暂停后续抽帧；网络缩略图内存 LRU 继续保持 8 MiB。此预算不含界面引用、缓存及解码临时内存。
- 清理和文件失效可在解码/压缩期间执行；旧解码不能重新发布，旧保存不能恢复已清理数据或覆盖新代次。异步保存还检查当前文件身份索引。
- native 抽帧继续串行，本批未增加 WebDAV 下载并发。播放读取优化另见上述已完成记录。
- 验证：60 项单元测试全部通过，字符串检查与 Standard Debug 构建通过（1m37s）。新增并发容量、保存背压和失败恢复测试，并调整原磁盘竞态测试以验证锁外解码/压缩。没有 Android 设备，界面响应、吞吐和功耗仍待真机验证。
- 代码提交 `586edf0` 的 Standard Release 构建通过（3m08s）；五种 APK 和 `.idsig` 已通过 zipalign、v2/v3/v4 签名、版本、ABI 和包内 Git 校验，更新到 `/root/mpvex/release/`。版本仍为 `1.2.9-rev0906`，基础 versionCode 130；构建临时目录已清理。仅本地提交，未上传本批代码或 APK。

## 版本更新与待推送（2026-09-06）

- App 更新为 `1.2.9-rev0906`，基础 versionCode 从 129 增至 130；五种输出继续使用原有架构编码规则（1300–1304）。
- 包含此前 WebDAV 播放时间、进度定位及缓存相关修复；本地签名 APK 统一存放在 `/root/mpvex/release/`。
- 用户授权低峰推送代码到 GitHub；按北京时间 2026-09-07 02:00 安排，仅推送固定的本次提交到 `origin/master`，不创建 tag 或 GitHub Release，不上传 APK 发布附件。
- `ae4170c` 的字符串检查与 Standard Release 构建通过；五种 APK 均通过 zipalign、v2/v3/v4 签名、ABI、版本名、版本代码（1300–1304）及包内 Git 版本验证，已放入 `/root/mpvex/release/`。本次仅调整版本参数，沿用上一轮 56 项通过的单元测试结果。

## WebDAV 播放时间与定位修复（2026-09-06）

- 切换前同步捕获旧文件的完整播放状态，再按顺序写入数据库；数据库等待期间不再读取播放器的当前属性，避免把新文件的位置写到旧文件。恢复记录前等待已提交的保存任务。
- 文件切换和用户定位使用独立的操作标记；历史记录迟到时仍可恢复轨道设置，但不会覆盖用户已经开始的手动定位。无历史记录不再在异步查询结束后重复跳到零。
- 加载/定位命令串行提交，并检查请求所属文件；切换时取消旧的合并定位。文件加载、播放重启和结束回调携带路径及切换标记，旧回调不能继续推进新列表。
- 进度条触摸处理随当前时长和文件更新；切换时重建手势/动画状态，取消拖动不再执行定位，未知时长时禁用进度条。横向滑动使用同一精确时间来源，松手请求绑定开始手势时的文件。
- 切换时清空旧时间线，空位置/时长不再沿用旧值；精确定位等待期间保留目标位置，抵达后跟随实际播放，失败最多等待 10 秒后回到实际位置。普通双击定位继续遵循精确定位偏好。
- 新增 8 项回归测试，覆盖迟到恢复、快速切换/返回同一路径、过期结束回调、连续定位、旧/空采样、定位失败回退及关键帧显示。56 项单元测试、字符串检查及 Standard Debug 构建通过。
- 代码提交 `a79b71d` 的 Standard Release 构建通过（2m53s）；五种 APK 和 `.idsig` 经 zipalign、v2/v3/v4 签名、ABI、版本名及包内 Git 版本验证后，已更新到 `/root/mpvex/release/`。App 版本保持 `1.2.9-rev0821`。
- 无 Android 设备连接，实际 WebDAV、触摸交互和网络定位延迟尚未真机回归；本节取代历史记录中的相关实现说明。

## 本地发布产物目录（2026-09-06）

- 每次本地 Release 编译、签名并验证完成后，将五种 APK 和对应 `.idsig` 统一移动到 `/root/mpvex/release/`，之后再清理编译临时目录。
- 当前 `87bfb83` 的五种 APK 和 `.idsig` 已从工作区根目录移入该目录，移动前后 SHA-256 一致。

## 代码质量问题修复（2026-09-06）

- `onNewIntent` 复用播放路径解析器判断是否有新媒体，恢复 `ACTION_SEND + EXTRA_STREAM`、`EXTRA_TEXT` 和 `uri` 参数；无媒体的通知/PiP 返回继续保留队列，分享的新媒体可替换旧队列。
- 文件版本索引不再猜测含 `::` 历史键的路径边界；无法确认归属的键不参与按路径查找和清理。探测/读取已知文件时按明确的连接 ID 与完整路径登记，按连接清理仍覆盖未分类键。
- 图片读取、编码、临时文件替换和删除使用独立的 IO 协程互斥锁；内存缓存代次锁仅保护元数据与发布判定。图片先编码到临时文件，失败或失效写入不会替换有效图片；初次偏好加载也移到内存锁外。
- 缓存失效通知携带累计的连接/路径范围，StateFlow 合并通知后不会遗漏路径；不相关连接直接跳过，当前目录只重读受影响文件的缓存。文件夹刷新先批量失效，再启动探测，只发送一次失效通知。
- 新增 6 项测试，覆盖文件名前缀冲突、累计/批量通知、IO 编码期间代次读取、失效写入保护、读取与清理交错、编码失败及临时文件清理。48 项单元测试、字符串检查和 Standard Debug 构建通过。
- 代码提交 `87bfb83` 的 Standard Release 构建通过；五个 APK 及 `.idsig` 已更新到 `/root/mpvex/`，均通过 zipalign、ABI、包内 Git 版本和 v2/v3/v4 签名验证。App 版本保持 `1.2.9-rev0821`。
- 未连接 Android 设备，分享入口、Compose 界面和实际 WebDAV 场景尚未真机验证；以下 2026-09-05 交付为历史记录。

## 本轮交付（2026-09-05）

- 按顺序完成连接并发、缓存失效一致性修复，以及缩略图内存、时长请求合并、大目录任务、HTTP 复用、播放队列流释放、缓存版本索引六项调整。
- 最终代码提交为 `a09a850`，五个已签名 Release APK 及对应 `.idsig` 已更新到 `/root/mpvex/`；包内 Git 版本均为 `a09a850`，App 版本保持 `1.2.9-rev0821`。
- 最终 42 项单元测试全部通过；字符串检查、Standard Debug 构建和 Standard Release 构建通过。五个 APK 均通过 zipalign、ABI、包内 Git 版本及 v2/v3/v4 签名验证。
- 尚未连接 Android 设备，真实 WebDAV 服务、画中画、后台播放和界面重建仍需真机验证；未进行吞吐、耗电或实际内存峰值基准测试。
- 本轮各批次记录按提交时的验证结果保留；其中旧 APK 版本和较少测试数量属于历史状态，当前交付以上述结果为准。

## 待发布修复

### 缩略图取消收尾

- 离开浏览界面时同步移除任务登记，重新进入时不会复用已取消但尚未结束的缩略图任务，避免快速返回后图片不加载。
- 42 项单元测试及字符串检查通过。

### 元数据缓存维护

- 初始化时一次性建立文件版本索引；探测完成后只查找同一连接、同一路径的旧缓存，不再逐视频复制并扫描整份 SharedPreferences。
- 旧版本删除与新时长/分辨率写入共用一次 Editor.apply；按连接清理也合并偏好写入。包含特殊 `::` 分隔符的历史路径/ETag 保留兼容查找，不更改现有缓存身份。
- 新增 2 项版本索引隔离与特殊分隔符测试；42 项单元测试、字符串检查及 Standard Debug 构建通过。

### 播放队列代理流释放

- 播放器记录已接管的代理流，在 mpv 确认文件加载后释放不属于新队列且未被当前文件使用的旧流；播放结束且 mpv 销毁后释放全部已接管流。
- 队列所有权跨 Activity 重建保留；通知/PiP 的无媒体 Intent 保留当前队列，新单文件 Intent 正确替换旧队列。流关闭在 IO 线程执行，播放器不清理尚未接管的其他启动请求或临时探测流。
- 浏览启动失败时回收本次已注册的流。新增 3 项队列替换/共享条目/单文件回归测试；40 项单元测试及字符串检查通过。实际 PiP、后台播放和设备重建仍需真机验证。

### WebDAV HTTP 连接复用

- WebDAV Range 请求复用代理内的 OkHttpClient，Range 和认证头逐请求设置；下一项预取复用独立客户端并保留 15 秒调用超时。
- 有界 Range 读取异常也会关闭响应；继续严格检查 206、Content-Range 与响应长度。
- 37 项单元测试及字符串检查通过；未进行真实服务器连接复用/吞吐基准测试。

### 大目录任务控制

- 浏览时长探测、文件夹刷新、启动预热和播放列表探测改为固定数量工作协程循环取任务，最多 6 个；不再为每个文件同时创建等待协程。
- 浏览按当前排序启动时长任务，重新加载时取消上一轮；播放列表优先当前项及相邻项。重新开启缩略图不再主动给整个目录排队。
- 新增 2 项千文件任务数量和取消测试；37 项单元测试及字符串检查通过。

### 时长探测合并

- 相同文件版本及失效代次的时长探测共享在途任务，避免浏览、预热及播放列表重复打开同一文件；获取并发许可后再次检查缓存。
- 仅最后一个等待者取消时取消共享任务；完成或失败后移除在途记录，刷新后的新代次不会复用旧探测。
- 新增 3 项共享/取消/重试测试；35 项单元测试及字符串检查通过。

### 缩略图内存控制

- 网络缩略图全局内存缓存按 Bitmap 实际分配字节计量，使用 8 MiB LRU 上限；淘汰仅释放引用，不回收界面仍在使用的 Bitmap。
- 浏览 ViewModel 仅持有可见及附近预取范围的缩略图；离开界面时释放图片引用并取消该界面的缩略图等待任务。
- 新增 2 项缓存容量/淘汰测试；32 项单元测试及字符串检查通过。8 MiB 为缓存预算，不代表整个应用或解码峰值内存上限。

### 缓存清理与刷新一致性

- 缓存清理按文件、连接或全局递增失效代次，清理与结果写入互斥；清理前启动的探测不能写回旧结果。缩略图队列按失效代次区分请求，刷新不会复用旧任务。
- 浏览界面响应缓存清理并移除失效图片/时长；刷新失败显示占位状态，文件夹刷新逐项检查代次后更新界面。
- 新增 3 项失效代次回归测试；30 项单元测试及字符串检查通过。

### 连接并发修复

- 同一连接的创建、浏览、更新和断开使用独立协程互斥锁，不同连接仍可并行；重复连接复用现有客户端。
- 连接状态采用原子更新；替换、删除及连接失败/取消时释放客户端，旧任务不能重新打开已删除的连接。
- 新增 5 项并发与生命周期回归测试；27 项单元测试及字符串检查通过。

### 网络预热与代理生命周期

- 修复 WebDAV 使用非根基础目录时，启动预热重复拼接基础路径的问题；预热从连接内根目录开始，FTP / SMB 原有路径处理保持不变。
- 移除 MainActivity 销毁时停止全局代理的逻辑，避免主界面重建或销毁中断独立播放器及应用级预热；代理随进程存续，临时探测流仍由调用方单独释放。
- WebDAV 连接检测现在检查基础目录是否存在；返回不存在时报告连接失败，避免错误显示连接成功。
- 本轮仅修复上述逻辑错误，不调整缓存容量、探测并发或请求合并策略。
- 本轮验证：22 项 Standard Debug 单元测试、`checkUserVisibleStrings` 和 `assembleStandardDebug` 通过；未连接 Android 设备，尚未进行真机 WebDAV / 生命周期场景验证。未重新构建 Release，外层已签名 APK 仍为 `db90e81`。

### WebDAV 播放与控制

- 进度条和横向滑动调整为拖动时只更新预览、松手时仅发送一次 `absolute+exact` seek，避免连续 Range 请求互相打断导致暂停或进度回跳。
- 进度条 seek 不再主动暂停/恢复播放；章节切换保留用户切换前的播放/暂停状态。
- 音量纵向手势按屏幕高度映射完整系统音量范围，单次滑动可快速完成大幅调整。

### 播放列表与网络日期

- 本地代理 URL 增加编码后的真实文件名作为稳定回退，播放列表首次打开即可显示文件名，不再依赖临时数字 stream id。
- 网络视频日期格式由 `MMM dd` 改为与本地视频一致的 `MMM dd, yyyy`。
- 网络排序保持原有标题/日期/大小/时长逻辑，其中 `Date` 继续按文件修改日期排序。
- “字段”区域保留可靠的文件修改日期开关；由于 WebDAV 服务端和视频容器无法稳定提供创建日期，已移除文件创建日期与视频创建日期字段及其探测、缓存和兼容代码。
- 文件修改日期卡片只显示 `MMM dd, yyyy`，不显示“修改”等前缀；关闭缩略图后立即隐藏缓存图片，重新开启时恢复探测。

## 版本

- 版本号：官方 `1.2.9` → 本分支 `1.2.9-rev0906`（基础 versionCode 130；universal 为 1300，arm64 拆分包为 1302）
- 当前 GitHub Release：`v1.2.9-rev0821`（含 universal / arm64-v8a / armeabi-v7a / x86_64 / x86 五个已签名 APK）

## 构建与验证

- `:app:testStandardDebugUnitTest`：84 项测试全部通过
- `:app:assembleStandardDebug` 与 `:app:assembleStandardRelease` 构建通过
- universal / arm64-v8a / armeabi-v7a / x86_64 / x86 五个 Release APK 均已完成 zipalign、v2/v3/v4 签名、版本号和 ABI 校验
- 当前五个已签名 APK 及对应 `.idsig` 位于 `/root/mpvex/release/`；Gradle `app/build` 临时产物已清理

## 数据库（Room，v8 → v11）

- `network_connections` 表新增字段：
  - v9：`displayInHome`（首页显示开关）
  - v10：`preloadCache`（启动预热开关）
  - v11：`preloadDepth`(默认10)、`preloadPerDir`(默认5)、`preloadTotal`(默认500)、`preloadThreads`(默认6)
- 新增迁移链 `MIGRATION_8_9` / `MIGRATION_9_10` / `MIGRATION_10_11`
- schema 导出目录新增 `8.json` / `9.json` / `10.json` / `11.json`

## 新增功能

### 网络元数据探测（缩略图 / 时长 / 分辨率）
- `NetworkMetadataProbe.kt`（新增）：三级缓存（内存 → 磁盘 → 网络探测），O(1) keyIndex
  - 缓存 key：`<connectionId>::<path>::<size>::<etag>::<lastModified>`
  - 磁盘缩略图按连接分目录：`network_thumbnails/<connId>/<md5>.webp`（160px，WebP q80）
  - 时长/分辨率存 SharedPreferences `network_metadata_cache`
  - 硬解优先，失败回退软解；黑帧检测采样 3x3 网格
  - 缩略图统一进入可取消、去重、按优先级的单消费者队列（native 串行）；时长/分辨率探测受 `durationSemaphore = Semaphore(6)` 限制
- `NetworkCacheWarmer.kt`（新增）：启动预热；时长/分辨率按 `preloadThreads` 并发，缩略图仍串行，只处理 `preloadCache=1` 的连接

### 网络浏览增强
- `HomeNetworkConnectionsSection.kt`（新增）：首页顶部显示网络连接（Display on Home）
- `NetworkVideoCard`：视频卡片显示缩略图 + 时长标签
- 每目录独立排序（Title/Date/Size/Duration + 文件名二次排序防闪烁），存 `network_folder_sort`
- 长按刷新缓存（`refreshCache`）：文件夹递归失效 + 重探
- 预取视野外缩略图（AHEAD=10）+ 快速滚动取消（`cancelThumbnailsExcept`）

### 网络连播与播放器集成
- `playVideo`：整个目录作为 playlist 传入，播放器自动连播
- `startNextPrefetch`：剩余 20s 预取下一视频的有界头/尾 Range 到代理缓存
- 播放列表文件名/元数据反查（`resolveProxyFileName`）+ 缺失项并发探测刷新
- 元数据探测成功后主动刷新播放列表；失败采用指数退避，避免持续网络重试

### 本地代理（NetworkStreamingProxy）
- WebDAV Range 完整性：所有 ranged 请求必须收到远端 206 且 Content-Range 对齐（禁止全量下载降级）
- 任意偏移 Range segment LRU（`RangeSegmentCache`）：2MB/段、16 条、16MB 总上限，identity 含 size/etag/lastModified
- `getStreamInfo`：支持 `bytes=start-end` / `bytes=start-` / 后缀 `bytes=-N`，越界返回 416

### 设置与清理
- 设置 → 高级 → Clear all preview images（`clearThumbnailCache()`）
- 连接卡片 DeleteSweep 按钮：按连接清理预览缓存（`clearConnectionCache(connId)`）

## i18n 国际化与汉化

- **语言切换**：`utils/LocaleManager.kt`（新增）+ 外观设置新增"语言"选项（跟随系统 / English / 简体中文）
  - 通过 AndroidX per-app locale 与生成的 `localeConfig` 应用语言，选择后自动重建界面；不覆盖系统 `fontScale`
  - 偏好存默认 SharedPreferences 的 `app_locale` 键（`AppearancePreferences.appLocale`），与 LocaleManager 共用
- **字符串外部化**：将约 300 条硬编码用户可见字符串迁移到 `values/strings.xml`（覆盖 63 个文件，含无障碍 contentDescription、Toast、错误信息、占位符格式串）
- **中文翻译**：新增 `values-zh/strings.xml`，覆盖全部可翻译字符串，并完成空值、缺失项和格式占位符一致性校验
- 特殊处理：
  - 非 composable lambda 内不能调用 `stringResource`，在 `remember` 前先解析（OnlineSubtitleSearchSheet / ControlLayoutEditorScreen）
  - `grid_columns`（带占位符）与 `grid_columns_label`（静态）分开命名，避免格式串冲突

## 修改文件

| 文件 | 说明 |
|---|---|
| `app/build.gradle.kts` | 版本号 → 1.2.9-rev0906 |
| `database/MpvExDatabase.kt` | version 11 |
| `database/dao/NetworkConnectionDao.kt` | 新字段查询 |
| `di/DatabaseModule.kt` | 迁移链 v8→v11 |
| `domain/network/NetworkConnection.kt` | displayInHome / preloadCache / preloadXxx |
| `domain/network/NetworkFile.kt` | duration、etag |
| `preferences/BrowserPreferences.kt` | 排序/缓存偏好 |
| `repository/NetworkRepository.kt` | 连接 CRUD 扩展 |
| `ui/browser/cards/NetworkConnectionCard.kt` | 复选框/Preload 折叠配置/清缓存 |
| `ui/browser/cards/NetworkVideoCard.kt` | 缩略图 + 时长 |
| `ui/browser/components/BrowserTopBar.kt` | 刷新按钮 |
| `ui/browser/filesystem/FileSystemBrowserScreen.kt` | 网络接入首页 |
| `ui/browser/folderlist/FolderListScreen.kt` | 网络目录列表 |
| `ui/browser/networkstreaming/*` | 浏览 VM/UI、预热、元数据、代理和首页网络区块 |
| `ui/browser/networkstreaming/clients/WebDavClient.kt` | PROPFIND 提供 etag |
| `ui/player/PlayerActivity.kt` | 网络目录连播队列和下一项文件头预取 |
| `ui/player/PlayerViewModel.kt` | 播放列表网络文件名与元数据反查 |
| `ui/player/controls/PlayerSheets.kt` | 网络元数据探测完成后刷新播放列表展示 |
| `ui/player/controls/components/sheets/PlaylistSheet.kt` | 预览图/时长/分辨率展示 |
| `ui/preferences/AdvancedPreferencesScreen.kt` | Clear all preview images |
| `utils/sort/SortUtils.kt` | 网络文件按标题、日期、大小和时长排序 |
| `utils/LocaleManager.kt` | AndroidX per-app locale 语言切换 |
| `App.kt` | 启动时应用保存的 per-app locale |
| `MainActivity.kt` / `PlayerActivity.kt` / `CrashActivity.kt` / `MediaInfoActivity.kt` | AppCompat locale 生命周期支持；保留系统字体缩放 |
| `preferences/AppearancePreferences.kt` | 新增 `appLocale` 偏好 |
| `preferences/AppearancePreferencesScreen.kt` | 语言选择器 UI |
| `res/values/strings.xml` | 新增约 197 条资源（网络功能 + 全量外部化字符串） |
| `res/values-zh/strings.xml` | 简体中文全量翻译 |
| `gradlew` | 可执行权限修正 |

## 与官方版的功能取舍

- 本分支移除了官方版的 Ambient Mode 与 Lua 脚本功能
- APK 使用本分支的本地发布密钥签名；设备上已安装不同签名的官方版或其他构建时，Android 可能不允许直接覆盖安装

## 限制处理结果

1. 缩略图仍受 `libplayer.so` 全局锁约束；统一的可取消、去重、按优先级单消费者队列保证 native 调用串行。
2. 播放列表会主动探测当前项及相邻项，缓存命中按完整文件版本区分；失败结果不写入永久缩略图缓存，完成后响应式刷新。
3. 代理使用严格内存上限的任意 Range segment LRU，按实际请求区间缓存并支持包含区间复用；WebDAV 不接受被忽略或错位的 Range 响应，预取头尾均有大小上限。
4. `preloadThreads` 只控制时长/分辨率探测并发，并限制在实际探测并发上限内；缩略图始终交给上述串行队列。
5. 语言切换使用 AndroidX per-app locale 与生成的 `localeConfig`；不覆盖系统 `fontScale`，保留无障碍字体设置。
6. 指定用户界面的可见文案已迁移到资源；项目校验任务会阻止这些界面新增明显的 Kotlin 硬编码文本。技术标签、URL 示例、路径、系统 `android.R.string.*` 和动态数值格式除外。
