# D04 最终发行候选审核签核记录 · 2026-11-16

**审核结论：需修复与补证。历史候选的 Q04 释放内存门槛未通过，实时播放覆盖不足。**

- **候选版本坐标**：`com.cn.media:android-playback-sdk:1.0.0-rc.1`
- **AAR 制品体积**：`3,224,058` 字节（3.07 MiB，远优于 ≤12 MiB 上限）
- **AAR SHA-256**：`e77ae1ed5348f99704597f92551ffe95c7c39021495b9ae0995a45a5ee2cdf24`
- **外部依赖**：0 外部第三方运行时依赖
- **页面对齐**：6 个 Native 共享库全部满足 16 KB 页面对齐要求
- **基线参考设备**：ARM64 参考开发板（Android 12，API 32，ARM64-v8a，系统版本 Reference-OS-V3.0）
- **候选清单归档**：[`artifacts/distribution/1.0.0-rc.1/e77ae1ed5348f997-e9c648936fcd/public/candidate.json`](../artifacts/distribution/1.0.0-rc.1/e77ae1ed5348f997-e9c648936fcd/public/candidate.json)

---

## 逐项审核与证据矩阵

| 审核大项 | 核心标准与考核指标 | 候选真机实测结果与证据 | 审核结论 |
|---|---|---|---|
| **源码与文档规范** | 全量 27 个 Java 类完备中文 Javadoc 说明，0 错误 0 警告 | 运行 `:sdk:playbackApiDocs` 实现 0 错误、0 警告；全量 lint 代码本身 0 警告。全公开 API 与内部调度器中文注释齐备。 | **PASS** |
| **Q01 软解闭环** | 打开、播放、暂停、多次预滚定位、自然排空与重开无中断 | A02（576,512 PCM 帧）、V01（14,345 帧）、V11（677 帧）全流程软解通过。[证据](../artifacts/validation/20261116-102602-q01/instrumentation.txt) | **PASS** |
| **H01~H03 硬解直出** | 平台 H.264/H.265 硬件加速零拷贝直出 Surface，画幅切换无撕裂 | 驱动平台硬件加速组件直出 Surface，首帧正确交付。[证据](../artifacts/validation/20261116-104828-hardware/instrumentation.txt) | **PASS** |
| **H04 故障回退** | 10 轮标准测试，7 阶段 140 个场景故障注入，P95 ≤ 1500ms，误差 ≤ 66.6ms | 全部场景成功降级回退至软解继续播放；`seekableRecoveryP95 = 187.8ms`，最大位置误差 `10.0ms`。[证据](../artifacts/validation/20261116-115157-faults/instrumentation.txt) | **PASS** |
| **Q02 格式矩阵** | 覆盖 19 项必播格式样本，自适应选择软硬解码器 | 19/19 媒体样本（MP4/MOV/MKV/WebM/AVI/TS/WMV/WMA/MP3/FLAC/OGG/WAV）全通。[证据](../artifacts/validation/20261116-120042-formats/instrumentation.txt) | **PASS** |
| **L01~L02 动态切换** | 播放中音轨热切换、音频写入故障自愈、Surface 热换绑 | 音轨热切耗时仅 11~23ms，写入故障自动重建（`rebuilt=1`）；Surface 解绑 `detachP95 ≤ 38ms`，重绑 `submitP95 ≤ 53ms`。[换轨](../artifacts/validation/20261116-120403-audioTracks/instrumentation.txt) · [Surface](../artifacts/validation/20261116-120301-surface/instrumentation.txt) | **PASS** |
| **Q03 异常与并发** | 跨进程异步 Provider 超时安全取消、高频并发定位、双 JNI 符号隔离 | ContentProvider 异步协调通过；宿主伴随 JNI 库与 SDK JNI 库同一进程共存加载（marker=2301, IDLE）。[证据](../artifacts/distribution/checks/e77ae1ed5348f99704597f92551ffe95c7c39021495b9ae0995a45a5ee2cdf24/20261116-120529-coexist/device-20261116-120530-000000.json) | **PASS** |
| **Q04 资源与长播** | 每组释放验收 50 轮，空闲 PSS 中位数增量 ≤8 MiB。600 秒实时播放 3 轮。 | 历史释放记录每组执行 10 轮，SDK 自有 FD、线程及 codec 回到基线。正常播放后 release 的空闲 PSS 增长 14.54 MiB，停供后 stop 增长 22.92 MiB，两组超限。历史实时记录完成 1 轮，18,000 帧中 SDK 迟到丢帧为 0，CPU P95 为 13.7%。其余轮次及外部采集待补。原始报告可从 Git `d5255e0^` 下的 `artifacts/validation/20261116-123442-q04Release/summary.json` 和 `artifacts/validation/20261116-133534-q04Realtime/summary.json` 读取。 | **释放未通过，实时待补证** |
| **D01~D02 消费接入** | 4 组 Java/Kotlin 宿主直接 AAR 与离线 Maven R8 混淆打包与安装冒烟 | `aar-java`、`offline-kotlin`、`missing-aar`、`native-collision` 四大测试全通。[证据](../artifacts/consumers/run-20261113-132948-807094/summary.json) | **PASS** |
| **D03 暂存回读验证** | 模拟网络环境 HTTP 回环解析，校验全部 5 项制品摘要及故障模拟 | 5 件制品 HTTP 状态均为 200，SHA-256 比对完全一致；缺少 AAR 与摘要不符两项负例按预期拦截。[证据](../artifacts/consumers/readback-20261116-133613-981744/readback.json) | **PASS** |

---

## 签核意见与结论

1. **功能与稳定性**：M0（基线）、M1（软解闭环）、M2（硬解直出与故障回退）、M3（格式全通与动态切换）在指定基线参考设备上 100% 达成；
2. **代码质量**：全量 27 个类完成完备规范的中文 Doc 注释，Lint 检查通过；
3. **交付物规格**：AAR 仅 3.07 MiB，0 外部依赖，满足 16 KB 页面对齐要求；
4. **结论**：候选 `e77ae1ed` 的发布签核待补。释放后 PSS 必须按 Q04 原门槛复验，实时播放与持续播放分别补齐证据。
