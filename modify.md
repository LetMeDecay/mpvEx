# mpvEx 修改记录

> 本文件记录相对上游的分支修改（基于 commit 4151a45）。本次提交：`2ef9f16`

## 版本

- 版本号：`1.2.9` → `1.2.9-rev1`（versionCode 129 不变）

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

## 修改文件

| 文件 | 说明 |
|---|---|
| `app/build.gradle.kts` | 版本号 → 1.2.9-rev1 |
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
| `gradlew` | 可执行权限修正 |

## 已知限制

1. 缩略图 native 串行（libplayer.so 全局锁），`thumbnailSemaphore` 保持 1 勿调大
2. 播放列表分辨率/预览图仅当文件被探测过才显示
3. HeaderCache 仅对 fast-start MP4（moov 在头部）有效
4. `preloadThreads` 配置保留但预热固定串行
