# Changelog (更新日志)

本项目遵循 [Semantic Versioning 2.0.0](https://semver.org/lang/zh-CN/) 语义化版本规范。

---

## [v1.2.0] - 2026-11-20

### 🛡️ 核心并发与生命周期加固 (Core Concurrency & Lifecycle Hardening)
- **硬解移交并发竞争保护与死锁消除 (P1)**：
  - 修复 `HardwareVideo.transferOwner` 与解码线程 `drainOne`/`flushPicture` 并发竞争导致的暂存帧二次释放（Double-Free）隐患，引入原子取出与释放幂等守卫。
  - 增加硬解移交异常时的兜底回收路径，防止 MediaCodec 实例挂起泄漏。
  - 将 `PlaybackOutput.paused` 访问改造为 `volatile` 无锁读写，消除持有硬件锁调用输出锁的循环等待死锁。
  - 引入 Surface 空闲等待看门狗超时机制（1 秒超时保护），防止旧任务异常未完成渲染导致控制线程无限期阻塞。
- **JNI 符号与平台错误码规范化对齐**：
  - 补齐导出 JNI 符号 `Java_com_cn_media_playback_NativeInput_category`，彻底消除 `UnsatisfiedLinkError`。
  - 统一跨平台与 JNI 错误码契约：超时正确映射为 `INPUT_TIMEOUT (2003)`，内存/缓冲区耗尽映射为 `RESOURCE_EXHAUSTED (5002)`。
- **高可用控制与队列健壮性**：
  - `CallbackQueue` 健壮性增强：捕获线程池拒绝异常并安全复位 draining 状态，保护在途回调不被整队丢弃。
  - 故障自动恢复模式强制传入 `exact=true`，避免不可定位媒体或自动重试误走 FastSeek。

### 📱 播放演示器 (Demo) 交互鲁棒性重构
- **快速连续拖拽防卡死**：增加 `wasPlayingBeforeDrag` 周期保护守卫，防止定位在途期间被 `PAUSED` 状态快照错误冲刷，松手后稳健恢复播放。
- **切文件上下文安全复位**：新增 `resetSeekAndDragState()`，切换源时清理待排队任务并丢弃废弃的在途 Seek 目标，防止旧进度窜入新文件。
- **播放器多代重建防串扰**：引入 `playerGeneration` 世代代数与 `GenerationListener` 拦截器，彻底杜绝跨实例请求序号（`requestId`）冒领碰撞。
- **交互与错误引导优化**：纯硬解模式打开无硬件解码器的格式（如 WMV）时提供明确的中文指引；错误状态下支持播放按钮直接重试恢复。

### ⚙️ CI/CD 发布流水线加固
- `.github/workflows/publish-package.yml` 引入严苛错误退出模式（`set -euo pipefail`），移除版本回退逻辑，严格校验 Maven 产物一致性与 GitHub Release 创建流程。

---

## [v1.1.0] - 2026-11-17

### 🚀 新特性 (New Features)
- **画面多比例自适应与画幅控制**：
  - 新增 `AspectRatioMode` 模式体系，支持 `自适应 (FIT)`、`铺满拉伸 (STRETCH)`、`居中裁剪 (CROP)`。
  - 覆盖经典横屏 `16:9`、`4:3`、`21:9` 以及主流竖屏短视频 `9:16`、`3:4`、方形构图 `1:1` 与 `原始点对点 (ORIGINAL)` 等 10 种呈现模式。
  - 动态跟踪并响应媒体 SAR 像素宽高比与旋转角度矩阵（0°/90°/180°/270°）变化。
- **本机文件双通道选择与播放**：
  - **系统 SAF 选择器**：基于 `Intent.ACTION_GET_CONTENT` 调起 Android DocumentsUI，SDK 通过 `ContentResolver` 与底层 Native 文件描述符流式直通，零文件复制开销。
  - **内置极简存储浏览器**：专为大屏遥控器与快速调试设计，一键直达 `/sdcard/`、`/sdcard/Download/`、`/sdcard/Movies/`、`/sdcard/DCIM/` 等常用多媒体目录，支持目录树无级下钻与视频格式自动过滤。
- **实时拖拽跟手出图 (FastSeek Scrubbing)**：
  - SeekBar 拖动期间引入 30ms 节流调度（~33fps），拖动过程画面实时跟手变换，音频平滑静音，抬手瞬间接续播放。

### ⚡ 性能与底层引擎优化 (Performance & Engine)
- **纯软解 4K 零拷贝 HWC 缩放**：
  - 重构 `video_output.c`，引入 `compute_buffer_geometry` 动态计算最佳 Native 图形缓冲尺寸，将 1080P $\to$ 4K 画面放大彻底卸载至 Android SurfaceFlinger / 硬件合成器（DPU/HWC），消除 CPU 内存带宽瓶颈。
  - 纯软解单帧色彩转换与渲染耗时由 45ms 骤降至 **2.5 ~ 3.5ms**，纯软解丢帧率由 22.64% **彻底降为 0.00%**。
- **1:1 直通 NEON SIMD 极速转换**：
  - 启用 libswscale 无缩放直通内核与 `SWS_FAST_BILINEAR` 高速双线性插值，利用 ARM64 NEON 向量指令实现极速 YUV420P $\to$ RGBA 色彩空间映射。
- **多核心解码拓扑优化**：
  - 启用 `FF_THREAD_FRAME | FF_THREAD_SLICE` 混合多线程并发解码，并发线程数动态匹配物理核心（`av_clip(av_cpu_count(), 1, 6)`）。

### 🛡️ 混淆与安全构建 (ProGuard & R8)
- **完善消费者混淆守护规则**：
  - 增强 `sdk/consumer-rules.pro`，严密保护 JNI 静态绑定、C/C++ 动态反射构造、Direct ByteBuffer 内存映射及基于 Ordinal 寻址的枚举常量（`TrackInfo.Type`、`MediaInfo.ProbeLimit`）。
  - Release 混淆模式下 Demo 进程常驻物理内存（PSS）优化至 **44.71 ~ 50.72 MB**（降低约 20%）。

---

## [v1.0.0] - 2026-11-16

### 初始正式版本发布 (Initial Release)
- **双解内核引擎**：平台 MediaCodec 硬件加速解码直出与内嵌 FFmpeg 纯软件解码深度融合。
- **跨格式支持**：支持 MP4, MKV, FLV, TS, M2TS, AVI, WebM 等主流封装容器及 H.264, HEVC, AAC, MP3, FLAC 等编码。
- **高精度同步管道**：微秒级主从音画同步时钟（Audio Master Clock）。
- **极速 Seek 架构**：基于 `MediaCodec.flush()` + `AudioTrack.flush()` 低延迟管道复用，关键帧就近定位低至 5ms。
- **轻量模块化**：SDK AAR 仅约 3.08MB，开箱即用。
