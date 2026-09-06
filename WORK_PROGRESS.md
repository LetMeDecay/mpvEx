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
| 5 | 旧请求取消与有界重复区间合并 | 已完成 | 79 项测试、字符串检查、Debug 通过，final-validation.log（31s）；提交见 Git 日志 |
| 交付 | 综合验证、Release 签名、文档和清理 | 进行中：准备 Release | 当前 release 仍为 586edf0 |

## 当前落点

- 方案 2、3、4 已分别提交 25e5d7e、e583a72、5ff7d55。方案 5 已通过最终验证，准备提交。
- 方案 5 的 ProxyRequestScope/Registry 关联响应与 Call；SharedRangeReads 提供有界渐进共享、独立游标、最后消费者取消和延迟释放资源许可。CancellableHttpRead 覆盖预取整个响应体，native 探测 watcher 将取消传给代理。
- 同文件定位只有新 GET 真正到达活动播放地址时才替换旧响应；不在手势开始/缓存内 seek 时盲目取消。元数据独立流不触发播放替换规则。
- 最终 79 项测试、字符串检查及 Standard Debug 通过（31s），日志 `/tmp/mpvex-webdav-final-validation.log`。此前各阶段结果已充分验证，不重复。
- 下一步：提交方案 5 后运行 Standard Release，日志 `/tmp/mpvex-webdav-final-release.log`；使用 `/tmp/mpvex-webdav-final-sign.py` 签名校验全部五种产物后替换 release，再清理 app/build/.kotlin、更新记录和交付。若该临时签名脚本丢失，按 CONTEXT.md 流程重建，并检查 APK 包内 Git 与本次代码提交一致。
- 当前 APK 仍为 586edf0，不能声称本批 APK 已交付。没有本批 push。原固定推送任务暂无成功凭证，本任务未改其范围。

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
