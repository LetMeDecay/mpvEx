# 当前状态

更新：2026-10-13。主仓库 `/root/mpvex/mpvEx`；Android/Kotlin/Compose/Room/libmpv。

- 当前授权：按 REFACTOR_PLAN.md 分阶段优化并交付原包名/签名的 Standard Release；不推送。全局 Codex 配置不修改。
- P0/P1a 完成；P1b 已统一 native 网关、加载/定位队列、条目身份和退出快照。当前批次将进度、时长、暂停、缓冲汇聚为原子 PlaybackUiState，隔离迟到失败事件；其他控件及 Service/PiP 尚未统一控制。
- P2 当前批次：Intent 只解析地址；当前文件名称/身份和资源打开移到 IO；目录/数据库队列在当前文件就绪后准备，取消旧代次工作。content 描述符由 native 条目租约管理，覆盖回调早于命令返回、跳过条目及退出；附加音轨/字幕资源保留至引擎销毁。R2 重复打开/泄漏已修复。
- 本批：通知/PiP 暂停与定位接入同一控制通道，通知读取核心状态；暂停队列有界且校验代次。配置/字体/脚本后台生成按内容寻址的完整快照，失败回退上一版本，活跃引擎持有租约；先绘制准备界面再初始化 native。配置编辑器移除临时文件并可靠关闭输入流。
- 尚未完成：其他控件意图及元数据汇聚、native 初始化线程/实例生命周期调整、真正分页队列及 P3–P6。没有设备启动耗时，不能宣称 WebDAV 1–2 秒等待已解决。
- 本批验证：33 项针对性单元测试与最终 Standard Debug 编译通过；日志 `.tmp/playback-source-20261013/`。Standard Release/R8/lintVital 构建通过（2m35s）；签名、原证书一致、版本递增、16KB ZIP 对齐及交付哈希全部通过。ADB 无设备，未执行安装、启动健康或功能/性能验收。
- 最新交付代码 `cb99287`：Release 1.2.9-rev1013.1，基础 versionCode 133（ABI 1330–1334），`../release/` 五种 APK + idsig 已覆盖旧包；构建/检查日志和 delivery-manifest.json 保留在本批 scratch 中。
- 本批检查：7 项命令队列测试、4 项配置快照测试及最终 Standard Debug 编译通过；日志 `.tmp/remaining-20261013/`。Release 1.2.9-rev1013.2 / 基础 versionCode 134 待交付。
- 下一步：继续按需队列和稳定历史身份，然后推进后续阶段。未解决问题仅见 PROJECT_ISSUES.md，reex 静态证据见 REEX_STARTUP_REVIEW.md；历史查 Git，不恢复已删除的重复流水。
