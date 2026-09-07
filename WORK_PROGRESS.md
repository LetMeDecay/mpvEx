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
| 2 | 大请求复用已缓存区间 | 已完成 | 25e5d7e；66 项测试及字符串检查通过，step2.log（1m11s） |
| 3 | 播放优先，限制后台探测与预取 | 已完成 | e583a72；69 项测试及字符串检查通过，step3.log（37s） |
| 4 | 小区间边读取边返回 | 已完成 | 5ff7d55；72 项测试及字符串检查通过，step4.log（32s） |
| 5 | 旧请求取消与有界重复区间合并 | 已完成 | a4a195c；79 项测试、字符串检查、Debug 通过，final-validation.log（31s） |
| 交付 | 综合验证、Release 签名、文档和清理 | 已完成 | Release 3m7s；五种签名 APK 为 a4a195c；构建目录已清理 |

## 当前落点（本次审查修复进行中）

- 最新授权：仅本次使用 gpt-6-astra medium，按 URL → 探测取消 → EOF 门控 → 图片保存交接 → 重叠 GET 风险顺序修复，不访问私有 WebDAV。
- 第 1 项已统一 WebDAV 客户端与代理路径拼接，WebDavPathTest 通过（1 项）；后续四项待处理。
- 第 1 项已提交 fb02b69。第 2 项改为退休独立探测请求入口，客户端仍由原 finally 清理；ProxyRequestScopeTest 4 项通过（32s）。第 3 项待处理。
- 以下为上一轮交付证据：

- 方案 2、3、4 已分别提交 25e5d7e、e583a72、5ff7d55。方案 5 已提交 a4a195c。
- 方案 5 的 ProxyRequestScope/Registry 关联响应与 Call；SharedRangeReads 提供有界渐进共享、独立游标、最后消费者取消和延迟释放资源许可。CancellableHttpRead 覆盖预取整个响应体，native 探测 watcher 将取消传给代理。
- 同文件定位只有新 GET 真正到达活动播放地址时才替换旧响应；不在手势开始/缓存内 seek 时盲目取消。元数据独立流不触发播放替换规则。
- 最终 79 项测试、字符串检查及 Standard Debug 通过（31s），日志 `/tmp/mpvex-webdav-final-validation.log`。此前各阶段结果已充分验证，不重复。
- Standard Release 通过（3m7s），日志 `/tmp/mpvex-webdav-final-release.log`。五种 APK 和 idsig 已通过 zipalign、v2/v3/v4 签名、版本、ABI 及包内 Git 校验，移入 `/root/mpvex/release/`。
- 当前 APK：代码 a4a195c，版本 1.2.9-rev0906，基础 versionCode 130；签名生成时间为北京时间 2026-09-07 01:08:34–01:08:39。签名日志与清单：`/tmp/mpvex-webdav-final-signing.log`、`/tmp/mpvex-webdav-final-artifacts.json`。
- app/build 和 .kotlin 已清理。本批没有 push；原固定推送任务范围保持不变，上传结果须另查成功凭证。
- 当前任务已全部完成，无阻塞。下次启动读取本文件后等待用户新任务，不重复实施 2–5 或重复已通过的验证。

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
