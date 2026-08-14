# mpvEx 修改记录

> 本文件记录相对上游的分支修改（基于 commit 4151a45）。当前提交：`a320f02`（`v1.2.9-rev0814`）

## 版本

- 版本号：`1.2.9` → `1.2.9-rev1` → `1.2.9-rev0814`（versionCode 129 不变）
- 已发布 GitHub Release：`v1.2.9-rev0814`（含 universal / arm64-v8a / armeabi-v7a / x86_64 / x86 五个已签名 APK）

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
- 播放列表面板打开期间每 500ms 轮询刷新时长/分辨率

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
- **中文翻译**：新增 `values-zh/strings.xml`，覆盖全部可翻译字符串（默认 789 条中除 12 条 `translatable="false"` 外的全部）
- 特殊处理：
  - 非 composable lambda 内不能调用 `stringResource`，在 `remember` 前先解析（OnlineSubtitleSearchSheet / ControlLayoutEditorScreen）
  - `grid_columns`（带占位符）与 `grid_columns_label`（静态）分开命名，避免格式串冲突

## 修改文件

| 文件 | 说明 |
|---|---|
| `app/build.gradle.kts` | 版本号 → 1.2.9-rev0814 |
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
| `ui/browser/networkstreaming/*` | 浏览 VM/UI/预热/元数据/代理/首页区块 |
| `ui/browser/networkstreaming/clients/WebDavClient.kt` | PROPFIND 提供 etag |
| `ui/player/PlayerActivity.kt` | 连播队列/prefetchNext/resolveProxyFileName |
| `ui/player/PlayerViewModel.kt` | 播放列表元数据反查 + 并发探测 |
| `ui/player/controls/PlayerSheets.kt` | 播放列表轮询刷新 |
| `ui/player/controls/components/sheets/PlaylistSheet.kt` | 预览图/时长/分辨率展示 |
| `ui/preferences/AdvancedPreferencesScreen.kt` | Clear all preview images |
| `utils/sort/SortUtils.kt` | 网络文件排序 |
| `utils/LocaleManager.kt` | 语言切换（createConfigurationContext + attachBaseContext 包装） |
| `App.kt` | attachBaseContext 应用语言 |
| `MainActivity.kt` / `PlayerActivity.kt` / `CrashActivity.kt` / `MediaInfoActivity.kt` | attachBaseContext 应用语言 |
| `preferences/AppearancePreferences.kt` | 新增 `appLocale` 偏好 |
| `preferences/AppearancePreferencesScreen.kt` | 语言选择器 UI |
| `res/values/strings.xml` | 新增约 197 条资源（网络功能 + 全量外部化字符串） |
| `res/values-zh/strings.xml` | 简体中文全量翻译 |
| `gradlew` | 可执行权限修正 |

## 已知限制

1. 缩略图 native 串行（libplayer.so 全局锁），`thumbnailSemaphore` 保持 1 勿调大
2. 播放列表分辨率/预览图仅当文件被探测过才显示
3. HeaderCache 仅对 fast-start MP4（moov 在头部）有效
4. `preloadThreads` 配置保留但预热固定串行
5. 语言切换通过 Activity `recreate()` 生效；`PlayerActivity` 原有 fontScale=1f 处理已保留
6. 少数字符串（如剪贴板标签、`android.R.string.*`、纯数字/百分比/倍速格式）未外部化，保持动态或框架默认
