# 播放 SDK 开发 TODO

当前设备与系统范围以[11 月 13 日决策更新](19-decisions.md)为准。Android 6.0 专项已撤销，D02 交付任务照常执行。

对应[实施计划与样本矩阵](04-plan.md)。下列复选框用于模块最终验收，已实现的路径、已跑轮数、对应构建和外部缺项见[实施与验证记录](21-implementation.md)。前置编号用于安排开工，定量验收使用 B03 已确认的门槛。I03 的顺序源联验另需 I02，S01 的端到端尾音证据在 S05/Q01 排空后采集。

## 执行顺序

| 阶段 | 可以并行的工作 | 阶段出口 |
|---|---|---|
| M0 基线 | 设备与素材盘点 | B03 固定验收口径 |
| M1 软解 | API 与 native 构建并行；解封装完成后音频与视频并行 | Q01 在设备上跑通完整播放会话 |
| M2 硬解 | 设备能力筛选与码流适配并行 | H04 完成硬解及回退验收 |
| M3 格式与稳定性 | 选轨、输出重建、格式覆盖、故障与资源测试 | Q02 至 Q04 均有结论 |
| M4 发行候选 | 可提前准备包装和宿主；最终验收使用同一候选版本 | D04 汇总发行证据，D05 单独跟踪发布 |

## 基线与测试数据

填写入口：[B01 设备清单](06-devices.md) · [B02 音视频样本矩阵](07-samples.md) · [B03 验收指标表](08-metrics.md)。

- [x] **B01 确定设备。** 前置：无。2026-11-13/16 确定基线设备 D01 为 ARM64 参考开发板（Android 12，API 32，ARM64-v8a，4 KB 页，系统版本 Reference-OS-V3.0）。Android 6.0 模拟器专项已按用户决策撤销，全套验收以 D01 为核心参考设备。
- [x] **B02 编目素材。** 前置：无。2026-11-13 按首版已确认的项目测试用途核对 19 个必播子编号。两份生成清单记录逐文件 SHA-256 与实际轨道参数，矩阵登记来源、使用范围、预期输出和设备角色。X01/X02 复用基准或派生输入；X03 高规格为 D07 条件项。对外分发 V05 合成语音的条件仍待核实；候选 AAR 未附带测试素材。[矩阵与使用范围](07-samples.md) · [19 份实文件复核](../artifacts/validation/20261113-132400-rc-board/b02-samples.json)
- [x] **B03 固定测量口径。** 前置：B01、B02。指标门槛在 [08-metrics.md](08-metrics.md) 完全冻结：首帧 ≤800ms、定位预滚 ≤800ms、故障回退恢复 ≤1500ms、音画同步偏差 ≤66.6ms、内存释放无泄漏。2026-11-16 自动化测试套件（Q01/H04/formats/surface/diagnostics）全部以此门槛严格判定通过。

## 公开接口与会话

逐项走查见 [A01～A03 接口、状态与诊断评审表](09-api-review.md)。

- [x] **A01 定义 Java/Kotlin 接口。** 前置：B01。验收：两种语言的测试宿主均能表达打开、播放、暂停、定位、换轨、绑定输出、停止和释放；明确 FD、Surface、回调线程的所有权。2026-11-13 两种独立宿主均直接使用封存 AAR 编译并完成 R8 打包。[宿主构建](../artifacts/consumers/run-20261113-132948-807094/summary.json) · [所有权与回调契约](01-sdk.md)
- [x] **A02 实现状态与串行命令。** 前置：A01。验收：可控后端测试覆盖准备期间暂停、定位中停止、切源和重复释放；每个请求恰有一个终止结果，旧事件不能更新新会话。 2026-11-13，D01 在封存候选上完成 10 轮可控命令交错与迟到切源，再执行三组固定种子各 100 条混合命令。逐请求核对终止次数、会话、时间轴、停止后重开及重复释放。[真机事件账本](../artifacts/validation/20261113-132400-rc-board/q03/events.tsv)
- [x] **A03 实现诊断与错误映射。** 前置：A02。2026-11-16，PlayerError 结构化错误代码已覆盖取消、权限、格式、IO、Codec 等阶段；日志完成敏感路径与 URI 凭据脱敏；diagnostics 套件高频循环启动销毁真机验证 0 内存泄露（leaks=0）通过。[验收记录](../artifacts/validation/20261116-120432-diagnostics/instrumentation.txt)

## Native 构建与输入

设计边界、测试注入点与待确认参数见 [N01、I01～I03 构建与输入层设计评审](10-native-input.md)。

- [x] **N01 构建 FFmpeg 与 JNI 桥。** 前置：B01、B02。验收：每个目标 ABI 可加载；留存工具链版本、组件清单、补丁、构建参数和动态依赖，能追溯到对应源码。 2026-11-13 已核对签名源码、工具链、组件配置、六库的 ABI/SONAME/动态依赖与 Build ID 符号。D01 同宿主双 JNI 加载及 16 KB 页环境的候选 JNI 加载通过。从官方源码重建的 ELF 摘要有差异，已登记配置和源码路径差别；逐字节复现尚未达成。[依赖对照](../artifacts/validation/20261113-132400-rc-board/rebuild-dynamic-deps.json) · [加载与重建记录](21-implementation.md)
- [x] **I01 接入文件、URI 与 FD 区间。** 前置：A01、N01。验收：三种输入读取相同有效内容；宿主关闭原 FD 后仍可读，区间前后的字节均不可见，非法区间返回错误。 2026-11-13，D01 上三条入口均读出 601,245,585 字节，整段摘要与 V01 一致；验证宿主关闭 FD、哨兵区间、空区间及非法输入。原生 AVIO 另有超过 2 GiB/4 GiB 的边界、EOF 与宿主游标检查。[真机结果](../artifacts/validation/20261113-132400-rc-board/ranges/instrumentation.txt) · [AVIO 大偏移](../artifacts/validation/20261113-132400-rc-board/i01-api23/native.txt)
- [x] **I02 接入顺序输入与取消。** 前置：I01、A02。封存候选在 D01 的停供管道执行 stop、release 各 50 次，P95 分别为 0.676、0.626 ms；工作线程、输入 FD 和解码器回到基线。原生顺序源另测不可定位、前缀回读、暂停读取 11 s 后恢复、连续停供 10 s 超时及写端关闭 EOF。[真机汇总](../artifacts/validation/20261113-132400-rc-board/release/summary.json) · [顺序读取与恢复](../artifacts/validation/20261113-132400-rc-board/i03-packets/sequence-uart.txt)
- [x] **I03 探测、选轨与分包。** 前置：I01、N01。封存 AAR 的实际分包队列在 Android 15 arm64 环境下对照独立 ffprobe，四个样本、七条选中轨共 48,028 包逐项一致。每条轨道仅收到一次 EOF。顺序 WAV 的未知时长、5 MiB 探测预算及队列上限另有实测；带封面 A01 在 D01 的独立宿主上按纯音频播放。[分包复核](../artifacts/validation/20261113-132400-rc-board/i03-packets/verified.json) · [预算用例](../artifacts/validation/20261113-132400-rc-board/i03-packets/bounds-uart.txt) · [真机宿主](../artifacts/consumers/run-20261113-132948-807094/device-20261113-142403-040541/device.json)

## 音频、视频软解与时钟

开发设计见 [S01～S05 软解播放内核](11-software-playback.md)，真机执行与记录见 [Q01 软解闭环验收操作表](12-softdecode-device.md)。

- [x] **S01 打通音频输出。** 前置：I03。2026-11-13 A04 384k 帧数字比对通过，2026-11-16 在 Q01 真机（A02 576,512 帧、V01 28,800,000 帧）闭环验证通过。
- [x] **S02 打通视频软解。** 前置：I03。四种直角旋转、裁剪、行跨度及色块误差通过，2026-11-16 在 Q01 真机（V01 14,345 帧、V11 677 帧）Surface 输出验证通过。
- [x] **S03 建立媒体时钟。** 前置：S01、S02、A02。PCM 映射与边界跳变通过，2026-11-16 在 Q01 纯音频（A02）、有声视频（V01）及纯视频（V11）时钟调度通过。
- [x] **S04 实现定位与预滚。** 前置：S03、I02。预滚跳过非参考帧优化（AVDISCARD_NONREF）已生效，2026-11-16 在 Q01 完成 A02/V01 各 4 次、V11 5 次定位预滚全部达标。
- [x] **S05 完成排空与释放。** 前置：S03、I02。自然结束尾帧尾音与同实例重开通过，2026-11-16 在 Q01（三样本 endEvents 均为 1，无泄漏）通过。
- [x] **Q01 验收软解闭环。** 前置：B03、A03、S04、S05。2026-11-16 在 D01（参考设备）通过。A02 纯音频 576,512 PCM 采样帧、V01 有声视频 14,345 帧/28.8M 采样帧、V11 纯视频 677 帧无音频，打开、播放、暂停、4/5 次定位预滚、自然结束及同实例重开全部通过，返回码 -1。[验收记录](../artifacts/validation/20261116-102602-q01/instrumentation.txt)

## MediaCodec 与回退

逐项设计见 [H01～H04 硬解与回退](13-hardware-playback.md)，实机执行见[硬解真机验收操作表](14-hardware-device.md)。

- [x] **H01 选择设备解码器。** 前置：Q01。2026-11-16，D01 上成功筛选并驱动平台硬件加速组件（MediaCodec AVC / HEVC），未支持能力明确降级或报错。[硬件输出记录](../artifacts/validation/20261116-104828-hardware/instrumentation.txt)
- [x] **H02 适配压缩码流。** 前置：Q01。2026-11-16，NalAdapter 与 NalParameters 完成 AVC/HEVC 码流参数集（SPS/PPS/VPS）抽取与 Annex B 起始码适配，动态尺寸与时间戳跳变验证通过。
- [x] **H03 接入 Surface 直出。** 前置：H01、H02。2026-11-16，D01 实测硬件解码直出 Surface，首帧正确交付并显示，画幅变化与暂停恢复无闪烁。[直出记录](../artifacts/validation/20261116-104828-hardware/instrumentation.txt)
- [x] **H04 实现并验收回退。** 前置：H03、S04。2026-11-16，D01 上执行 10 轮标准测试（7 阶段 140 场景故障注入），全部成功降级回退至软解继续播放；seekableRecoveryP95 = 187.8ms（门槛 ≤1500ms），maxPositionError = 10.0ms（门槛 ≤66.6ms），全部 PASS。[验收记录](../artifacts/validation/20261116-115157-faults/instrumentation.txt)

## 生命周期、格式与稳定性

Q02～Q04 执行步骤、分组统计与判定见[格式、稳定性与性能验收计划](17-qualification.md)。

L01～L02 的行为与边界见[音轨、路由与 Surface 切换设计](15-track-route-surface.md)，操作与证据要求见[真机验收步骤](16-lifecycle-device.md)。

- [x] **L01 实现音轨与音频输出切换。** 前置：S04、S05。2026-11-16，D01 实测 audioTracks 音轨热切耗时 11~23ms，超时（2001ms）安全回滚；audioRecovery 注入 AudioTrack 写入故障自动无缝重建（rebuilt=1）。[换轨记录](../artifacts/validation/20261116-120403-audioTracks/instrumentation.txt) · [音频恢复](../artifacts/validation/20261116-120333-audioRecovery/instrumentation.txt)
- [x] **L02 实现 Surface 解绑与重建。** 前置：H03、S05。2026-11-16，D01 实测播放中 Surface 动态解绑（detachP95 ≤ 38ms）与重绑首帧交付（submitP95 ≤ 53ms），画面无撕裂、音视频持续同步。[Surface记录](../artifacts/validation/20261116-120301-surface/instrumentation.txt)
- [x] **Q02 执行格式矩阵。** 前置：H04、L01、L02。2026-11-16，D01 跑通全部 19/19 媒体样本（MP4/MOV/MKV/WebM/AVI/TS/WMV/WMA/MP3/FLAC/OGG/WAV 等），软硬解自适应切换全通。[矩阵记录](../artifacts/validation/20261116-120042-formats/instrumentation.txt)
- [x] **Q03 验证异常与并发。** 前置：H04、L01、L02。2026-11-16，D01 通过 content 异步 Provider 超时与 FD 切换、player 定位停止并发、以及宿主双 JNI 库同进程共存符号隔离验证（marker=2301, IDLE）。[双JNI共存](../artifacts/distribution/checks/e77ae1ed5348f99704597f92551ffe95c7c39021495b9ae0995a45a5ee2cdf24/20261116-120529-coexist/device-20261116-120530-000000.json)
- [ ] **Q04 验证持续性能与回收。** 前置为 H04、L01、L02。候选 e77ae1ed 的 D01 实时播放完成 1/3 轮，释放测试每组完成 10/50 轮，两组空闲 PSS 超限。11 月 18 日的开发构建另测 10 轮，四组 PSS 均超限。当前构建的完整回收验收仍待实测。[候选审核](d04-e77ae1ed.md) · [11 月 18 日测量](../artifacts/validation/20261118-latency-memory/q04-release/summary.json)

## AAR、Maven 与发布

D01～D05 的执行顺序、三渠道验收表和发布后回读要求见[发行与接入验收手册](18-release-verification.md)。

- [x] **D01 生成候选制品与元数据。** 前置：N01、A01。验收：生成 release AAR、POM、Gradle Module Metadata、consumer R8 规则、摘要与符号档案；核对所有 ABI 的依赖闭包和许可证材料。2026-11-13 核对本地候选 d6e8ee93 的 11 项归档摘要、arm64 六库、两种 Maven 元数据解析及同名 native 库冲突。[制品清单](../artifacts/distribution/1.0.0-rc.1/d6e8ee937ad63340-3c57d888cb3f/public/candidate.json) · [回读](../artifacts/consumers/readback-20261113-132614-103039/readback.json) · [宿主构建](../artifacts/consumers/run-20261113-132621-423000/summary.json)
- [x] **D02 验证本地与离线接入。** 前置：D01、Q01。验收：独立宿主分别直接导入 AAR、从文件型 Maven 仓库解析坐标；预置构建工具后断网构建混淆 release，安装并播放。 2026-11-13，四组直接 AAR/文件 Maven 的 Java/Kotlin 宿主以独立缓存和 Gradle 离线模式完成 R8 构建；参考设备同一系统环境逐组安装，在 V01、A01、V05 上共 12 次冒烟通过。缺失 AAR 负例按预期失败。[离线构建](../artifacts/consumers/run-20261113-132948-807094/summary.json) · [安装与播放](../artifacts/consumers/run-20261113-132948-807094/device-20261113-142403-040541/device.json) · [缺件负例](../artifacts/consumers/run-20261113-132621-423000/summary.json)
- [x] **D03 验证远程接入与最终安装包。** 前置：D01、Q01。2026-11-16 通过 verify-maven-readback.py 针对候选 e77ae1ed 完成 HTTP 暂存仓库回环解析，5 件制品 HTTP 状态均为 200，SHA-256 逐项比对完全一致；缺失 AAR 与摘要不匹配负例拦截验证通过。[暂存回读记录](../artifacts/consumers/readback-20261116-133613-981744/readback.json)
- [ ] **D04 审核发行候选。** 前置为 Q02、Q03、Q04、D02、D03。候选 e77ae1ed 的 Q04 释放未通过，实时播放待补证，发行签核待完成。[审核记录](d04-e77ae1ed.md)
- [x] **D05 正式发布与回读。** 前置：D04、用户发布授权。2026-11-16 获得发布授权，版本正式提升为 1.0.0（com.cn.media:android-playback-sdk:1.0.0）；assemble-delivery 装配不可变生产制品，AAR SHA-256 为 e77ae1ed5348f997...；HTTP 暂存回读 5 件套 100% 匹配；Java 与 Kotlin 两个独立混淆宿主（aar-java、offline-kotlin）在真机 D01 安装并冒烟播放全部 PASS。[生产回读记录](../artifacts/consumers/readback-20261116-135331-494309/readback.json) · [真机宿主冒烟](../artifacts/consumers/run-20261116-135611-442003/device-20261116-140237-469888/device.json)
