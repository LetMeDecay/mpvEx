# mpvEx 修改记录

> 本文件记录相对上游的分支修改（基于 commit 4151a45）。当前版本：`v1.2.9-rev0821`

## 版本

- 版本号：`1.2.9` → `1.2.9-rev1` → `1.2.9-rev0814` → `1.2.9-rev0821`（versionCode 129 不变；arm64 拆分包 versionCode 1292）
- 当前 GitHub Release：`v1.2.9-rev0821`（含 universal / arm64-v8a / armeabi-v7a / x86_64 / x86 五个已签名 APK）
- `v1.2.9-rev0814` Release 因说明中错误声称官方版仅支持本地播放、且未包含后续纠错，已撤回；旧 tag 保留用于历史追溯

## rev0821 修复与验证

### WebDAV / 网络连播
- 修复通过播放列表切换 WebDAV 视频时，新视频继承上一视频播放时间的问题：
  - 初始 Intent 的 `position` 只应用一次，后续 playlist 项无历史状态时立即从 0 开始
  - 每个代理视频使用当前 `connectionId + filePath` 生成稳定媒体标识，避免临时 stream ID 破坏进度恢复
  - 播放状态异步查询增加加载代次校验，旧文件的查询结果不能写入新文件
  - 保留外部 Intent 显式指定的首次起播位置；存在有效历史状态时仍按设置恢复
- 修复网络连播切换后字幕仍按最初点击文件搜索的问题，改为从当前代理 URI 的 `StreamInfo` 获取真实路径和连接 ID
- 下一项文件头预取改为在 `FILE_LOADED` 后启动：切换时取消旧 watcher，按实际 shuffle 顺序选择下一项，仅对正在进行的请求去重，并正确处理失败和协程取消

### 元数据、排序与国际化
- 移除播放列表面板每 500ms 的无条件轮询；网络元数据失败增加指数退避和并发去重，成功后只刷新展示，避免离线或不兼容服务器被持续请求
- 修复按时长降序排列时未知时长项目跑到列表顶部的问题，未知时长现在始终位于末尾
- 补齐简体中文资源中 159 个空字符串；校验结果为 0 空值、0 缺失、0 格式占位符不匹配

### 测试
- 新增网络媒体稳定标识、播放位置策略、预取顺序/并发去重、元数据退避和网络时长排序单元测试
- `:app:testStandardDebugUnitTest`：10 项测试全部通过
- `:app:assembleStandardDebug` 与 `:app:assembleStandardRelease` 构建通过
- arm64-v8a Release APK 已完成 zipalign、v2/v3 签名和 ABI 校验

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
  - `thumbnailSemaphore = Semaphore(1)`（native 串行）、`durationSemaphore = Semaphore(6)`
- `NetworkCacheWarmer.kt`（新增）：启动预热，严格按列表顺序串行，只处理 `preloadCache=1` 的连接

### 网络浏览增强
- `HomeNetworkConnectionsSection.kt`（新增）：首页顶部显示网络连接（Display on Home）
- `NetworkVideoCard`：视频卡片显示缩略图 + 时长标签
- 每目录独立排序（Title/Date/Size/Duration + 文件名二次排序防闪烁），存 `network_folder_sort`
- 长按刷新缓存（`refreshCache`）：文件夹递归失效 + 重探
- 预取视野外缩略图（AHEAD=10）+ 快速滚动取消（`cancelThumbnailsExcept`）

### 网络连播与播放器集成
- `playVideo`：整个目录作为 playlist 传入，播放器自动连播
- `startNextPrefetch`：剩余 20s 预取下一视频 moov 头部到代理 HeaderCache
- 播放列表文件名/元数据反查（`resolveProxyFileName`）+ 缺失项并发探测刷新
- 元数据探测成功后主动刷新播放列表；失败采用指数退避，避免持续网络重试

### 本地代理（NetworkStreamingProxy）
- WebDAV Range 完整性：ranged 请求必须收到远端 206；offset>0 收到 200 拒绝（禁止全量下载）
- 文件头 LRU 缓存（`HeaderCache`）：内存 2MB/条、8 条、16MB 上限
- `getStreamInfo`：支持 `bytes=start-end` / `bytes=start-` / 后缀 `bytes=-N`，越界返回 416

### 设置与清理
- 设置 → 高级 → Clear all preview images（`clearThumbnailCache()`）
- 连接卡片 DeleteSweep 按钮：按连接清理预览缓存（`clearConnectionCache(connId)`）

## i18n 国际化与汉化（本次新增）

- **语言切换**：`utils/LocaleManager.kt`（新增）+ 外观设置新增"语言"选项（跟随系统 / English / 简体中文）
  - 通过 `createConfigurationContext` + `attachBaseContext` 应用到 Application 与全部 4 个 Activity（MainActivity / PlayerActivity / CrashActivity / MediaInfoActivity），选择后自动重建界面
  - 偏好存默认 SharedPreferences 的 `app_locale` 键（`AppearancePreferences.appLocale`），与 LocaleManager 共用
- **字符串外部化**：将约 300 条硬编码用户可见字符串迁移到 `values/strings.xml`（覆盖 63 个文件，含无障碍 contentDescription、Toast、错误信息、占位符格式串）
- **中文翻译**：新增 `values-zh/strings.xml`，覆盖全部可翻译字符串；`rev0821` 补齐遗漏的 159 个空资源，并完成空值/缺失/占位符一致性校验
- 特殊处理：
  - 非 composable lambda 内不能调用 `stringResource`，在 `remember` 前先解析（OnlineSubtitleSearchSheet / ControlLayoutEditorScreen）
  - `grid_columns`（带占位符）与 `grid_columns_label`（静态）分开命名，避免格式串冲突

## 修改文件

| 文件 | 说明 |
|---|---|
| `app/build.gradle.kts` | 版本号 → 1.2.9-rev0821 |
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
| `ui/browser/networkstreaming/*` | 浏览 VM/UI/预热/元数据/代理/首页区块；元数据失败退避与预取结果处理 |
| `ui/browser/networkstreaming/clients/WebDavClient.kt` | PROPFIND 提供 etag |
| `ui/player/PlayerActivity.kt` | 连播队列、稳定网络媒体标识、WebDAV 切换进度隔离、当前文件字幕路径、下一项预取生命周期 |
| `ui/player/PlayerViewModel.kt` | 播放列表元数据反查 + 并发探测 + 失败指数退避 |
| `ui/player/controls/PlayerSheets.kt` | 移除 500ms 轮询，改由探测完成主动刷新 |
| `ui/player/controls/components/sheets/PlaylistSheet.kt` | 预览图/时长/分辨率展示 |
| `ui/preferences/AdvancedPreferencesScreen.kt` | Clear all preview images |
| `utils/sort/SortUtils.kt` | 网络文件排序；未知时长在升/降序中始终置底 |
| `utils/media/NetworkMediaIdentity.kt` | 网络代理文件稳定媒体标识 |
| `utils/media/PlaybackPositionPolicy.kt` | 初始显式位置、历史位置和后续列表项归零策略 |
| `utils/media/NetworkPrefetchSupport.kt` | shuffle/repeat 预取顺序和 in-flight 去重 |
| `ui/browser/networkstreaming/NetworkMetadataRetryPolicy.kt` | 元数据失败指数退避策略 |
| `utils/LocaleManager.kt` | 语言切换（createConfigurationContext + attachBaseContext 包装） |
| `App.kt` | attachBaseContext 应用语言 |
| `MainActivity.kt` / `PlayerActivity.kt` / `CrashActivity.kt` / `MediaInfoActivity.kt` | attachBaseContext 应用语言 |
| `preferences/AppearancePreferences.kt` | 新增 `appLocale` 偏好 |
| `preferences/AppearancePreferencesScreen.kt` | 语言选择器 UI |
| `res/values/strings.xml` | 新增约 197 条资源（网络功能 + 全量外部化字符串） |
| `res/values-zh/strings.xml` | 简体中文全量翻译；补齐 159 个空字符串 |
| `app/src/test/` | rev0821 网络媒体标识、播放位置、预取、重试和排序回归测试 |
| `gradlew` | 可执行权限修正 |

## 已知限制

1. 缩略图 native 串行（libplayer.so 全局锁），`thumbnailSemaphore` 保持 1 勿调大
2. 播放列表分辨率/预览图仅当文件被探测过才显示
3. HeaderCache 仅对 fast-start MP4（moov 在头部）有效
4. `preloadThreads` 配置保留但预热固定串行
5. 语言切换通过 Activity `recreate()` 生效；`PlayerActivity` 原有 fontScale=1f 处理已保留
6. 少数字符串（如剪贴板标签、`android.R.string.*`、纯数字/百分比/倍速格式）未外部化，保持动态或框架默认
7. WebDAV/SMB/FTP 连播修复已通过单元测试和本地构建，仍需在真实服务器上进行端到端切换验证
8. 网络媒体稳定标识当前包含文件路径的 Kotlin/Java `hashCode`，理论上存在极低概率碰撞
