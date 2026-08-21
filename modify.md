# mpvEx 修改记录

> 本文件记录相对上游官方 `v1.2.9` 的功能差异。当前版本：`v1.2.9-rev0821`
>
> 官方版本已经提供网络连接与 SMB / FTP / WebDAV 播放能力；本文仅记录本分支相对官方版本新增的功能。

## 版本

- 版本号：官方 `1.2.9` → 本分支 `1.2.9-rev0821`（versionCode 129 不变；arm64 拆分包 versionCode 1292）
- 当前 GitHub Release：`v1.2.9-rev0821`（含 universal / arm64-v8a / armeabi-v7a / x86_64 / x86 五个已签名 APK）

## 构建与验证

- `:app:testStandardDebugUnitTest`：21 项测试全部通过（原有 10 项 + 本轮新增 11 项）
- `:app:assembleStandardDebug` 与 `:app:assembleStandardRelease` 构建通过
- universal / arm64-v8a / armeabi-v7a / x86_64 / x86 五个 Release APK 均已完成 zipalign、v2/v3 签名、版本号和 ABI 校验

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
