# 当前任务恢复记录

更新时间：2026-09-07（Asia/Shanghai）

## 用户授权与边界

- 按顺序实施 WebDAV 草稿方案 **2 → 3 → 4 → 5**，每批验证、更新记录并本地提交；中断后继续未完成项。
- 不实施方案 6 的并行下载或其他候选方案。已交付的预览图 T1＋T2 和播放时间修复必须保留。
- 不保存或输出用户提供的内网地址、凭据、文件名；如需远端验证，只读且有读取量上限。
- 不推送本批代码、tag 或 APK。原低峰任务只推固定 f8d0fdf，不扩大其范围。
- 不操作相邻 mpvEx_New。Release 编译后签名、校验五种 APK＋idsig，放入 /root/mpvex/release/，再清理 app/build 和 .kotlin。

## 恢复入口

1. 读取本文件、`/root/mpvex/CONTEXT.md`、`modify.md`，执行 `git status --short --branch` 和 `git log -5 --oneline`。
2. 若有未提交变更，先按本文件“当前落点”检查，不覆盖或重新实现已有工作。
3. 检查所列构建日志及进程；已有通过证据的检查不重复执行。只有代码变更、失败或缺失证据时再验证。
4. 每个代码节点、测试完成或阻塞出现时更新本文件。临时日志可能被清理，因此通过数量、提交和关键决策需写入此文件。

## 批次进度

| 顺序 | 内容 | 状态 | 验证 / 提交 |
|---|---|---|---|
| 2 | 大请求复用已缓存区间 | 已完成 | 66 项测试及字符串检查通过，step2.log（1m11s）；提交见 Git 日志 |
| 3 | 播放优先，限制后台探测与预取 | 进行中：准备实现 | — |
| 4 | 小区间边读取边返回 | 待开始 | — |
| 5 | 旧请求取消与有界重复区间合并 | 待开始 | — |
| 交付 | 综合验证、Release 签名、文档和清理 | 待开始 | 当前 release 仍为 586edf0 |

## 当前落点

- 方案 2 已通过验证，正在提交：新增 WebDavRangeSource（HTTP 区间/版本校验）和 CachedRangeInputStream（缓存与缺口顺序输出），RangeSegmentCache 增加 next 切片查找。强 ETag 时以 If-Match 保护缺口；无强标识保留单流。新增 6 项范围测试。
- 下一步：实施方案 3。网络播放期间暂停新的缩略图/时长网络探测（缓存命中仍直接返回）；下一集头尾预取仅在当前播放缓存充足时启动，开始/停止与实际播放器生命周期绑定。当前无 Gradle 构建在运行。
- 代码入口：`NetworkStreamingProxy.kt` 的 `getStreamWithOffsetWebDAV`（单个同步 OkHttp Call）；`RangeSegmentCache.kt`（2 MiB/段、16 MiB LRU）。
- 方案 2：在同一文件版本内按偏移输出缓存与缺口，缺口仍单流读取；避免把整个大文件加载到内存。拼接需要可靠的远端版本条件，缺少可靠标识时保留兼容单流。
- 方案 3：在实际网络播放期间给当前播放优先权，缓存命中不阻塞；后台探测/预取按统一门控降载，播放器退出恢复，注意 Activity 重建。
- 方案 4：小区间流式返回，只有完整读取且校验通过才入缓存；下一项预取需真正消费有界响应，不能只读响应头后关闭。
- 方案 5：请求所有权关联显式 Call.cancel/close；只合并有界相同文件版本/区间，共享请求最后一个消费者离开后才取消。不要在手势开始或缓存内 seek 时盲目中断仍有用的播放流。

## 验证命令与记录

不要并行运行 Gradle：

```bash
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:testStandardDebugUnitTest :app:checkUserVisibleStrings
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:assembleStandardDebug
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:assembleStandardRelease
```

- 本批日志使用 `/tmp/mpvex-webdav-step2.log`、`step3.log`、`step4.log`、`step5.log`（同前缀）及最终验证日志。
- 无 Android 设备；JVM 测试可验证区间字节、版本校验、取消、调度及资源边界，但不能承诺真机吞吐和功耗。
- 无法读取实时 Codex 配额；不等待配额耗尽才保存进度，也不假设会话能自动重启。
