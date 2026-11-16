# Android 多格式音视频播放 SDK 设计规划

设计基线 v0.1 · 2026-11-11 · 已进入实现与设备验收

播放器在独立工程 AndroidPlaybackSDK 实现，拥有自己的构建、测试宿主和发行流程。首版目标私库坐标为 com.cn.media:android-playback-sdk:1.0.0，开发演练使用 1.0.0-rc.1。当前实施、原始设备结果与剩余门槛见[实施与验证记录](21-implementation.md)，制品及使用方式见[工程首页](../README.md)。

目标是交付能在独立 Android 应用中嵌入的播放器。FFmpeg 负责探测、解封装、音频解码和视频软解，Android MediaCodec 提供视频设备解码路径。调用者可使用 Java 或 Kotlin，以本地 AAR、离线 Maven 仓库或远程 Maven 坐标集成同一发行物。

| 阅读顺序 | 用途 |
|---|---|
| [SDK 接入契约](01-sdk.md) | 确定宿主和库分别负责什么，核对状态和操作结果 |
| [播放内核详细设计](02-engine.md) | 指导输入、轨道、解码、同步、故障恢复与释放 |
| [构建与发行设计](03-distribution.md) | 确定 native 构建、AAR 内容及 Maven 发布流程 |
| [实施计划与验收矩阵](04-plan.md) | 确定先后依赖、样本、真机指标和完成条件 |
| [按模块执行的 TODO 清单](05-todo.md) | 领取开发任务、检查前置依赖、逐项登记验收结果 |

## 专题总索引

| 开工阶段 | 设计与验收文件 |
|---|---|
| 准备与决策 | [按开工顺序确认参数](19-decisions.md) · [B01 设备](06-devices.md) · [B02 样本](07-samples.md) · [B03 指标](08-metrics.md) |
| 接口与输入 | [A01～A03 接口评审](09-api-review.md) · [N01、I01～I03 构建与输入](10-native-input.md) |
| 软解闭环 | [S01～S05 内核](11-software-playback.md) · [Q01 真机步骤](12-softdecode-device.md) |
| 硬解与回退 | [H01～H04 设计](13-hardware-playback.md) · [硬解真机步骤](14-hardware-device.md) |
| 生命周期 | [L01～L02 设计](15-track-route-surface.md) · [切换与重建真机步骤](16-lifecycle-device.md) |
| 回归与发行 | [Q02～Q04 验收](17-qualification.md) · [D01～D05 发行手册](18-release-verification.md) |

TODO 编号标识任务。设备表的 D01～D07 表示设备角色；样本表中的 A01 等表示文件。跨表引用时注明任务、设备或样本。任务状态见 TODO，运行结果按候选版本登记。

## 开工准备

[已确认参数核对与开工缺项](20-readiness.md)按第一次需要的阶段列出设备、样本、工具链和发行环境。

## 设计范围

当前本地演练候选的制品摘要、各项验收证据及两个签核角色的状态见 [D04 候选审核记录](d04-e271c519.md)。

首版验收使用 RK3588 NEXBoard 真机，Android 6.0 已移出支持范围，正式制品仅 arm64-v8a；B02 的全部视频和音频组合必播，首个实时目标为 H.264 1080p30 单路。设计覆盖本地文件与 content URI、SDR 和 PCM 输出。D01 已确定为 RK3588 NEXBoard，固件号按测试轮次记录；HEVC 性能门槛及实测结果待补。

HTTP 点播、直播协议、字幕、倍速保调、DRM 和 HDR 输出分别列为后续能力。接入契约保留未知时长、不可定位输入和多轨信息，可用于后续扩展。媒体格式承诺按经验证的封装、轨道编码、规格和设备组合发布。

## VLC 设计参考

VLC 3.0.x 上游按模块提供 MediaCodec NDK/JNI 接入，处理 H.264/HEVC 初始化数据，并在特定失败后标记当前轨道以避免重复选择失败后端。本 SDK 借鉴按轨选择、码流适配和有界回退，自行定义公开 API。VLC 不进入 SDK 依赖。上述依据来自上游分支，不能用于判定特定 AAR 在特定设备上的运行路径。[VideoLAN 解码模块](https://github.com/videolan/vlc/blob/3.0.x/modules/codec/omxil/mediacodec.c)

本地制品的源码重建、符号核对和宿主接入见[发行演练记录](d01-local-delivery.md)。工作区后续修改须针对新摘要重新验收。
