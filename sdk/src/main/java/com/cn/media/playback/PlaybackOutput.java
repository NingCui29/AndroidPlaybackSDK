package com.cn.media.playback;

import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;

/**
 * 渲染输出纪元（Epoch）。
 * <p>
 * 代表单次播放或单次定位（Seek）生成的渲染生命周期实例。
 * 负责音视频时钟同步（A/V Sync）、音频 PCM 路由管理、视频 Surface 动态绑定与切换、
 * 硬件解码器（{@link HardwareVideo}）调度及向 FFmpeg 软解的无缝故障回退（Fallback）。
 * </p>
 * <p>
 * <b>线程模型：</b>
 * <ul>
 *     <li>底层 Native 解码线程通过 JNI 进入并调用 {@link #acquireVideo}、{@link #finishVideo}、{@link #write} 等方法；</li>
 *     <li>主控制队列（Control Queue）拥有用户的操作意图，调用 {@link #bind}、{@link #stop}、{@link #setPlaying} 等方法；</li>
 *     <li>内部采用对象锁与细粒度条件变量（{@link Object#wait} / {@link Object#notifyAll}）协调多线程竞态。</li>
 * </ul>
 * </p>
 */
final class PlaybackOutput implements AutoCloseable {

    /**
     * 渲染输出事件观察者接口。
     * <p>
     * 接收由 {@link PlaybackOutput} 抛出的首帧、后端变更、Surface 失效及轨道状态等关键生命周期事件。
     * </p>
     */
    interface Observer {
        /**
         * 首帧就绪通知（已定位至指定位置并完成首帧解码准备）。
         *
         * @param output 当前渲染纪元实例
         * @param actualMs 实际就绪的媒体时间戳（毫秒）
         */
        void ready(PlaybackOutput output, long actualMs);

        /**
         * 首次实际向系统音频输出设备提交音频样本通知。
         *
         * @param output 当前渲染纪元实例
         */
        void firstAudio(PlaybackOutput output);

        /**
         * 首帧视频画面提交至 Surface 渲染通知。
         *
         * @param output 当前渲染纪元实例
         */
        void firstVideo(PlaybackOutput output);

        /**
         * 当前视频解码后端名称变更通知（例如从硬件解码器切换为 "ffmpeg"）。
         *
         * @param output 当前渲染纪元实例
         * @param name 解码后端组件名称
         */
        void backend(PlaybackOutput output, String name);

        /**
         * 硬件解码组件发生故障通知。
         *
         * @param output 当前渲染纪元实例
         * @param failure 故障详情模型
         */
        void backendFault(PlaybackOutput output, HardwareVideo.Fault failure);

        /**
         * 绑定的 Surface 失效通知（如底层 BufferQueue 释放或 SurfaceView 销毁）。
         *
         * @param output 当前渲染纪元实例
         * @param invalid 已失效的 Surface 实例
         */
        void surfaceLost(PlaybackOutput output, Surface invalid);

        /**
         * 轨道不可用或解码丢失通知。
         *
         * @param output 当前渲染纪元实例
         * @param isVideo 是否为视频轨道（{@code true} 为视频，{@code false} 为音频）
         * @param reason 轨道丢失原因代码
         */
        void trackLost(PlaybackOutput output, boolean isVideo, int reason);

        /**
         * 动态音频流切换结果通知。
         *
         * @param output 当前渲染纪元实例
         * @param request 请求序号标识
         * @param raw 底层原生状态码（0 表示成功）
         * @param actualMs 切换生效时的实际媒体时间戳（毫秒）
         */
        void audioSwitchResult(PlaybackOutput output, long request, int raw, long actualMs);
    }

    private final Observer observer;
    /** 当前活跃的 PCM 音频输出通道。 */
    private PcmOutput pcm;
    /** 在途帧写入绑定的 PCM 输出句柄，用于在音频流切换时防止旧帧写错通道。 */
    private PcmOutput framePcm;
    /** 当前选中的音频流索引编号，-1 表示未启用音频。 */
    private int audioIndex;
    /** 正在挂起的音频流切换请求 ID。 */
    private long switchRequest;
    /** 音频切换时的媒体时间戳下限（微秒），低于该时间戳的旧流样本被舍弃。 */
    private long switchFloor;
    /** 音频选择版本单调递增计数器。 */
    private long audioVersion;
    /** 历史纪元累计提交的 PCM 字节数。 */
    private long previousWritten;
    /** 历史纪元累计消费的 PCM 字节数。 */
    private long previousConsumed;
    /** 当前输出增益倍数（0.0f ~ 1.0f）。 */
    private float gain;
    /** 是否包含音频轨道。 */
    private boolean audio;
    /** 是否包含视频轨道。 */
    private boolean video;
    /** 播放暂停状态。 */
    private boolean paused;
    /** 渲染纪元已停止。 */
    private boolean stopped;
    /** 是否处于首帧预览（Seek 暂停态渲染首帧）模式。 */
    private boolean preview;
    /** 是否已触发首帧就绪通知。 */
    private boolean ready;
    /** 是否已完成首帧音频输出。 */
    private boolean firstAudio;
    /** 是否已完成首帧视频画面上屏。 */
    private boolean firstVideo;
    /** 标识当前是否有视频工作线程正在占用 Surface 进行锁屏或渲染绘制。 */
    private boolean videoBusy;
    /** 音频数据流是否已经到达流末尾并完成排空。 */
    private boolean audioEnded;
    /** 当前渲染绑定的目标 Surface。 */
    private Surface surface;
    /** 用于 CPU 软解渲染时的独立 Surface 代理，避免占用并污染 MediaCodec 生产通道。 */
    private Surface cpuSurface;
    /** 标识是否正在执行 Surface 的重绑定切换。 */
    private boolean switching;
    /** 目标定位时间戳（微秒）。 */
    private final long targetUs;
    /** 视频轨道元数据信息。 */
    private final TrackInfo videoTrack;
    /** 播放器全局配置策略。 */
    private final PlayerConfig policy;
    /** 是否强制使用软解模式。 */
    private final boolean forceSoftware;
    /** 活跃的硬件解码控制器。 */
    private volatile HardwareVideo hardware;
    /** 最近一次发生的硬件解码故障。 */
    private volatile HardwareVideo.Fault lastFault;
    /** 当前使用的解码后端名称（如 "c2.android.avc.decoder" 或 "ffmpeg"）。 */
    private volatile String backend;
    /** 音频输出采样率（Hz）。 */
    private int rate;
    /** 音频媒体时间基准锚点（微秒）。 */
    private long audioAnchor = -1;
    /** 媒体时钟基准锚点（微秒）。 */
    private long clockAnchor;
    /** 系统绝对单调时钟基准锚点（微秒）。 */
    private long wallAnchor;
    /** 上一帧呈现的视频时间戳（微秒）。 */
    private long lastVideo = -1;
    /** 视频帧显示间隔（微秒），默认 33000（约 30fps）。 */
    private long intervalUs = 33000;
    /** 预期呈现的下一帧视频时间戳（微秒）。 */
    private long nextVideo = -1;
    /** 暂停态或时钟冻结时的媒体时间戳（微秒）。 */
    private long frozenUs;
    /** PCM 时间轴追踪器，用于处理采样率重采样与 PTS 跳变补偿。 */
    private final PcmTimeline pcmTimeline = new PcmTimeline();
    /** 音频时间偏移量（微秒）。 */
    private long audioShiftUs;
    /** 视频时间偏移量（微秒）。 */
    private long videoShiftUs;
    /** 标记视频时间戳是否发生了不连续跳变。 */
    private boolean videoJumped;
    /** 已成功提交至 Surface 渲染的视频帧计数。 */
    private long submitted;
    /** 因超时落后而主动丢弃的视频帧计数。 */
    private long dropped;
    /** 上一次提交画面的系统纳秒时间戳。 */
    private long lastSubmissionNs;
    /** 连续提交画面之间的最大间隔纳秒数（卡顿监控）。 */
    private long maxGapNs;
    /** 最近一帧的音视频时钟偏差偏移量（微秒）。 */
    private long lastOffsetUs;
    /** 首帧音频实际输出的绝对纳秒时间戳。 */
    private long firstAudioNs;
    /** 首帧视频实际呈现的绝对纳秒时间戳。 */
    private long firstVideoNs;
    /** 软解像素颜色格式代码。 */
    private int softwareColor;
    /** 软解峰值缓冲区占用字节数。 */
    private long softwarePeakBytes;
    /** 硬件解码输出格式详情。 */
    private volatile HardwareOutputInfo hardwareFormat;
    /** 硬件故障回退恢复统计信息。 */
    private VideoRecoveryInfo recovery;
    /** 硬件解码器候选项探测轨迹链。 */
    private volatile java.util.List<String> hardwareSelection = java.util.Collections.emptyList();
    /** 纪元终结时生成的不可变统计快照。 */
    private PlaybackStats finalStats;
    /** 最近若干帧的间隔滑动窗口数组。 */
    private final long[] recentIntervals = new long[5];
    /** 滑动窗口内记录的有效间隔数。 */
    private int intervalCount;
    /** 滑动窗口当前写入索引。 */
    private int intervalIndex;

    /**
     * 构建渲染输出纪元。
     *
     * @param observer 事件监听器
     * @param audioIndex 初始选中的音频流索引，-1 表示禁用音频
     * @param videoTrack 视频轨道信息，为 {@code null} 表示纯音频播放
     * @param targetMs 起始定位的目标媒体时间（毫秒）
     * @param preview 是否处于首帧定位预览状态（暂停且仅渲染第一帧）
     * @param playing 是否初始处于播放状态
     * @param surface 视频渲染输出 Surface，可为 {@code null}
     * @param gain 初始音频输出增益（0.0f ~ 1.0f）
     * @param policy 播放器配置参数
     * @param forceSoftware 是否强制禁用硬件加速直出
     */
    PlaybackOutput(Observer observer, int audioIndex, TrackInfo videoTrack, long targetMs,
                   boolean preview, boolean playing, Surface surface, float gain, PlayerConfig policy,
                   boolean forceSoftware) {
        this.observer = observer;
        pcm = new PcmOutput(policy.pcmCalls);
        this.audioIndex = audioIndex;
        this.audio = audioIndex >= 0;
        this.gain = gain;
        switchFloor = targetMs * 1000;
        this.videoTrack = videoTrack;
        this.video = videoTrack != null;
        this.policy = policy;
        this.forceSoftware = forceSoftware;
        backend = video && (forceSoftware || policy.videoMode == PlayerConfig.VideoMode.SOFTWARE_ONLY) ? "ffmpeg" : "";
        this.targetUs = targetMs * 1000;
        this.preview = preview;
        this.surface = surface;
        paused = !playing;
        clockAnchor = frozenUs = targetUs;
        wallAnchor = nowUs();
        pcm.setGain(gain);
        pcm.setPaused(paused);
    }

    /** 获取当前系统单调递增时钟（微秒）。 */
    private static long nowUs() { return SystemClock.elapsedRealtimeNanos() / 1000; }

    /** 等待状态变更通知，捕获中断并安全退出。 */
    private void waitForChange(long ms) {
        try { wait(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); stop(); }
    }

    /**
     * 更新播放与暂停状态。
     *
     * @param playing {@code true} 为播放，{@code false} 为暂停
     */
    synchronized void setPlaying(boolean playing) {
        if (playing == !paused || stopped) return;
        frozenUs = clockUs();
        paused = !playing;
        lastSubmissionNs = 0;
        if (hardware != null) hardware.resetOutputWait();
        clockAnchor = frozenUs;
        wallAnchor = nowUs();
        pcm.setPaused(paused);
        notifyAll();
    }

    /**
     * 设置音频音量增益。
     *
     * @param gain 音量增益（0.0f ~ 1.0f）
     */
    synchronized void setGain(float gain) { this.gain = gain; pcm.setGain(gain); }

    /** 获取当前选中的音频流索引。 */
    synchronized int selectedAudioIndex() { return audioIndex; }
    /** 获取音频切换的时间戳下限（微秒）。 */
    synchronized long selectedAudioFloorUs() { return switchFloor; }
    /** 获取当前音频轨道版本号。 */
    synchronized long audioSelectionVersion() { return audioVersion; }

    /**
     * 无缝替换当前活跃的音频输出管道。
     *
     * @param index 新目标音频流索引
     * @param request 切换请求标识
     * @param replacement 预先初始化配置好的新 PCM 输出实例
     * @return {@code true} 表示替换成功，{@code false} 表示纪元已停止或音频已结束
     */
    synchronized boolean installAudio(int index, long request, PcmOutput replacement) {
        if (stopped || audioEnded) return false;
        switchFloor = clockUs();
        previousWritten += pcm.submitted();
        previousConsumed += pcm.consumed();
        // cancel() 持有旧 PCM 的内部监视器并在发布前执行刷新。
        // 已处于 JNI 内部的写入线程会保留 framePcm，绝不会向新输出误写。
        pcm.cancel();
        pcm.close();
        pcm = replacement;
        pcm.setGain(gain);
        pcm.setPaused(paused);
        audio = true;
        audioIndex = index;
        audioVersion++;
        switchRequest = request;
        audioAnchor = -1;
        firstAudio = false;
        clockAnchor = frozenUs = switchFloor;
        wallAnchor = nowUs();
        notifyAll();
        return true;
    }

    /** 撤销或忽略指定的音频流切换请求编号。 */
    synchronized void forgetAudioRequest(long request) {
        if (switchRequest == request) switchRequest = 0;
    }

    /**
     * 响应音频流切换失败并回退状态。
     *
     * @param index 发生失败的音频流索引
     * @param raw 底层原生错误码
     * @return 错误状态代码
     */
    synchronized int switchingAudioFailed(int index, int raw) {
        if (index != audioIndex) return 1;
        if (switchRequest == 0) return 0;
        long request = switchRequest;
        switchRequest = 0;
        abandonAudio(index);
        observer.audioSwitchResult(this, request, raw, frozenUs / 1000);
        notifyAll();
        return 1;
    }

    /** 放弃当前选中的音频流通道。 */
    synchronized void abandonAudio(int index) {
        if (audioIndex != index) return;
        frozenUs = clockAnchor = clockUs();
        wallAnchor = nowUs();
        pcm.cancel();
        audioIndex = -1;
        audio = false;
        notifyAll();
    }

    /** 确认并发布已切换成功的音频通知。 */
    private void acceptSwitchedAudio() {
        if (switchRequest != 0 && framePcm == pcm) {
            long request = switchRequest;
            switchRequest = 0;
            observer.audioSwitchResult(this, request, 0, audioAnchor / 1000);
        }
    }

    /**
     * 动态绑定或替换视频渲染输出 Surface。
     * <p>
     * 支持在播放进行中热切换显示窗口，通过 {@link #awaitSurfaceIdle()} 阻塞直到旧 Surface
     * 上没有任何在途的 CPU 或硬件渲染写入，安全完成通道交接。
     * </p>
     *
     * @param replacement 新的目标 Surface，传入 {@code null} 表示解除绑定（后台播放）
     */
    synchronized void bind(Surface replacement) {
        if (stopped || surface == replacement) return;
        if (!audio && replacement == null) frozenUs = clockUs();
        switching = true;
        awaitSurfaceIdle();
        if (stopped) {
            switching = false;
            notifyAll();
            return;
        }
        releaseCpuSurface();
        firstOnSurface = 0;
        lastSubmissionNs = 0;
        surface = replacement;
        if (hardware != null) { hardware.bind(replacement); hardware.resetOutputWait(); }
        if (!audio && replacement != null) {
            clockAnchor = frozenUs;
            wallAnchor = nowUs();
        }
        switching = false;
        notifyAll();
    }

    /**
     * 停止渲染纪元。
     * <p>
     * 冻结时钟、取消 PCM 输出并等待在途的视频绘制任务结束，最后释放硬件解码器与 CPU 渲染表面。
     * </p>
     */
    synchronized void stop() {
        if (!stopped) {
            frozenUs = clockUs();
            stopped = true;
            pcm.cancel();
            notifyAll();
        }
        // 新一代渲染纪元可能在上一代完成回收前就变成活跃代。
        // 绝不允许旧 Surface 的写入操作跨越此交接点。
        awaitSurfaceIdle();
        // 处于组件发现和 codec.configure 之间的解码工作线程可能仍然持有解码器。
        // 即便当前纪元已注销，close() 也会阻断其配置执行。
        if (hardware != null) hardware.close();
        releaseCpuSurface();
        surface = null;
    }

    /** 阻塞等待直至没有任何后台工作线程正在占用 Surface 绘制。 */
    private void awaitSurfaceIdle() {
        boolean interrupted = false;
        long start = videoBusy ? SystemClock.elapsedRealtimeNanos() : 0;
        while (videoBusy) {
            try { wait(); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (start != 0) {
            long waitedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000;
            if (waitedMs > 100) android.util.Log.w("PlaybackSurface", "In-flight " + backend + " access took " + waitedMs + " ms");
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** 检查纪元是否已停止。 */
    synchronized boolean stopped() { return stopped; }
    /** 检查当前状态是否预期视频应有画面推进。 */
    synchronized boolean expectsVideoProgress() { return !stopped && (!paused || preview) && surface != null; }

    /** 获取当前活跃的解码后端名称。 */
    String backend() { return backend; }
    /** 设置并广播当前选中的解码后端。 */
    void backendSelected(String name) {
        backend = name;
        observer.backend(this, name);
    }
    /** 记录并广播硬件解码故障。 */
    synchronized void backendFault(HardwareVideo.Fault fault) {
        if (lastFault == fault) return;
        fault.failedAtNs = SystemClock.elapsedRealtimeNanos();
        fault.positionUs = clockUs();
        lastFault = fault;
        observer.backendFault(this, fault);
    }
    /** 检查是否发生过 Surface 切换阶段的硬件故障。 */
    boolean surfaceSwitchFailed() { return lastFault != null && "hardwareSurface".equals(lastFault.stage); }
    /** 获取最近一次硬件故障详情。 */
    HardwareVideo.Fault hardwareFailure() { return lastFault; }

    /**
     * 初始化 MediaCodec 硬件视频解码器。
     *
     * @param profile 编码 Profile
     * @param level 编码 Level
     * @param depth 颜色位深
     * @param fps 帧率
     * @param extra 额外配置字节缓冲区（如 SPS/PPS）
     * @return 1 表示硬解创建成功，0 表示已自动退回软解，负数表示严重失败或已停止
     */
    synchronized int initHardware(int profile, int level, int depth, float fps, ByteBuffer extra) {
        if (stopped) return -3;
        detachInvalidSurface();
        if (forceSoftware || policy.videoMode == PlayerConfig.VideoMode.SOFTWARE_ONLY) return 0;
        try {
            hardware = new HardwareVideo(this, videoTrack, surface, extra, profile, level, depth, fps, policy);
            return 1;
        } catch (RuntimeException failure) {
            int outcome = hardwareError(failure, "hardwareSetup");
            if (outcome < 0) return outcome;
            softwareFallback();
            return 0;
        }
    }

    /**
     * 投递待解包的编码视频帧至硬件解码器。
     *
     * @param packet 原始视频数据包，为 {@code null} 表示流结束
     * @param ptsUs 显示时间戳（微秒）
     * @param durationUs 帧持续时长（微秒）
     * @param key 是否为关键帧（IDR/I 帧）
     * @param extra 额外头信息参数
     * @return 0 表示接收成功，1 表示硬解接收前退回软解，2 表示从当前位置恢复，负数表示终结错误或取消
     */
    int hardwarePacket(ByteBuffer packet, long ptsUs, long durationUs, boolean key, ByteBuffer extra) {
        try {
            if (packet == null) hardware.finish();
            else hardware.submit(packet, ptsUs, durationUs, key, extra);
            return stopped() ? -3 : 0;
        } catch (RuntimeException failure) {
            return hardwareError(failure, "hardwareDecode");
        }
    }

    /**
     * 触发硬件解码器排空输出画面。
     *
     * @return 0 表示成功，负数表示发生故障
     */
    int flushHardwarePicture() {
        try {
            if (hardware != null) hardware.beforeInputWait();
            return 0;
        } catch (RuntimeException failure) { return hardwareError(failure, "hardwareOutput"); }
    }

    /** 获取硬件解码配置阻塞等待毫秒数。 */
    int configurationWaitMs() { return hardware == null ? -1 : hardware.configurationWaitMs(); }

    /** 记录硬件解码实际输出格式更新。 */
    void hardwareFormat(HardwareOutputInfo changed) { hardwareFormat = changed; }

    /** 记录硬件解码器候选匹配链路。 */
    void hardwareSelection(java.util.List<String> trace) {
        hardwareSelection = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(trace));
    }

    /**
     * 统一处理硬件解码异常。
     *
     * @param failure 异常对象
     * @param stage 发生故障的执行阶段标识
     * @return 控制决策码（负数终止，正数表示可降级重试模式）
     */
    private synchronized int hardwareError(RuntimeException failure, String stage) {
        if (stopped) return -3;
        HardwareVideo decoder = hardware;
        boolean runtime = decoder != null && decoder.started();
        HardwareVideo.Fault fault = failure instanceof HardwareVideo.Fault ?
                (HardwareVideo.Fault)failure : new HardwareVideo.Fault(stage, decoder == null ? "" : decoder.component(), failure);
        fault.runtime = runtime;
        backendFault(fault);
        if (decoder != null) decoder.close();
        hardware = null;
        if (policy.videoMode == PlayerConfig.VideoMode.HARDWARE_ONLY) {
            if (fault.code() == PlayerError.Code.RESOURCE_EXHAUSTED) return -4;
            if (fault.code() == PlayerError.Code.INVALID_MEDIA_DATA) return -5;
            return runtime ? -2 : -1;
        }
        return runtime ? 2 : 1;
    }

    /** 记录硬解故障并启动回退恢复时间戳。 */
    synchronized void recoveryFrom(HardwareVideo.Fault fault, long target) {
        if (fault != null) recovery = new VideoRecoveryInfo(fault.stage, fault.failedAtNs, fault.positionUs, target, -1, 0);
    }

    /** 触发降级回退至 FFmpeg 软解后端。 */
    synchronized void softwareFallback() {
        recoveryFrom(lastFault, clockUs());
        firstVideo = false;
        backendSelected("ffmpeg");
    }

    /**
     * 响应轨道不可用状态。
     *
     * @param isVideo 是否为视频轨
     * @param reason 不可用原因
     * @return 1 表示单轨仍可继续运行，2 表示全部轨道失效，0 表示无需继续处理
     */
    synchronized int trackUnavailable(boolean isVideo, int reason) {
        if (stopped) return 0;
        if (policy.requireAllSelectedTracks || (isVideo && policy.videoMode == PlayerConfig.VideoMode.HARDWARE_ONLY)) {
            observer.trackLost(this, isVideo, reason);
            return 0;
        }
        if (isVideo) {
            video = false;
            backend = "";
        } else {
            frozenUs = clockAnchor = clockUs();
            wallAnchor = nowUs();
            audio = false;
            pcm.cancel();
        }
        observer.trackLost(this, isVideo, reason);
        if (audio && audioAnchor >= 0) signalReady(audioAnchor);
        notifyAll();
        return audio || video ? 1 : 2;
    }

    /**
     * 计算当前渲染纪元的权威媒体时钟时间戳（微秒）。
     * <p>
     * 音视频同步算法核心：有音频时以音频 PCM 实际已消费的时间轴为主时钟基准；
     * 纯视频或音频未起播时以系统 Wall Clock 单调时钟推进。
     * </p>
     *
     * @return 当前播放进度时间戳（微秒）
     */
    private long clockUs() {
        if (stopped || paused || (!audio && surface == null)) return frozenUs;
        if (audio && !audioEnded && audioAnchor >= 0 && rate > 0) {
            long consumed = pcm.consumed();
            if (consumed > 0 && !firstAudio) {
                firstAudio = true;
                if (firstAudioNs == 0) firstAudioNs = SystemClock.elapsedRealtimeNanos();
                if (!video || surface == null) signalReady(audioAnchor);
                observer.firstAudio(this);
            }
            long position = pcmTimeline.mediaUs(consumed, rate);
            audioShiftUs = pcmTimeline.shiftUs(rate);
            return position;
        }
        if (lastVideo < 0 && !audioEnded) return frozenUs;
        return clockAnchor + Math.max(0, nowUs() - wallAnchor);
    }

    /** 获取当前播放位置毫秒数。 */
    synchronized long positionMs() { return Math.max(0, clockUs() / 1000); }

    /** 计算视频帧相对于当前权威时钟的提前量（微秒，正数代表视频超前需等待，负数代表滞后）。 */
    private long videoAheadUs(long pts) {
        long clock = clockUs();
        return audio ? (pts - videoShiftUs) - (clock - audioShiftUs) : pts - clock;
    }

    /** 获取当前播放会话的统计指标快照。 */
    synchronized PlaybackStats statistics() {
        if (finalStats != null) return finalStats;
        long written = pcm.submitted();
        return new PlaybackStats(previousWritten + written, previousConsumed + Math.min(written, pcm.consumed()), submitted, dropped,
                maxGapNs / 1000000, lastOffsetUs, softwareColor, softwarePeakBytes,
                pcm.routeId(), pcm.routeType(), pcm.routeChanges(), pcm.recoveries(), hardwareFormat, hardwareSelection,
                firstAudioNs, firstVideoNs, recovery, pcmTimeline.jumps);
    }

    /** 记录软解色彩格式与内存峰值。 */
    synchronized void softwareVideoInfo(int color, long peakBytes) {
        softwareColor = color;
        softwarePeakBytes = peakBytes;
    }

    /** 触发首帧渲染就绪信号。 */
    private void signalReady(long ptsUs) {
        if (ready) return;
        ready = true;
        observer.ready(this, Math.max(0, ptsUs / 1000));
    }

    /**
     * 音频帧前置准入处理。
     * <p>
     * <b>线程约束：</b>仅针对越过 preroll 剪裁区间的有效音频帧调用。
     * </p>
     *
     * @param stream 音频流编号
     * @param ptsUs 显示时间戳（微秒）
     * @param discontinuity 是否存在时间戳跳变不连续
     * @return 0 表示准入，1 表示流不匹配，-1 表示纪元已停止
     */
    synchronized int beginAudio(int stream, long ptsUs, boolean discontinuity) {
        if (audioIndex != stream) return 1;
        framePcm = pcm;
        if (audioAnchor < 0) {
            audioAnchor = ptsUs >= 0 ? ptsUs : targetUs;
            pcmTimeline.start(audioAnchor, audioShiftUs);
        } else if (discontinuity) {
            pcmTimeline.append(pcm.queuedFrames(), ptsUs);
        }
        if (paused && (!video || surface == null)) signalReady(audioAnchor);
        notifyAll();
        return stopped ? -1 : 0;
    }

    /**
     * 配置音频输出参数。
     *
     * @param inputRate 输入采样率（Hz）
     * @param channels 声道数量
     * @return 实际匹配生效的输出采样率，-1 表示纪元已停止
     */
    synchronized int configure(int inputRate, int channels) {
        if (stopped) return -1;
        if (framePcm != pcm) return inputRate;
        rate = pcm.configure(inputRate, channels);
        notifyAll();
        return rate;
    }

    /**
     * 写入音频 PCM 采样数据块。
     *
     * @param samples 存放 PCM 数据的字节缓冲区
     * @return 成功写入的字节数，负数表示写入失败
     */
    int write(ByteBuffer samples) {
        PcmOutput writer;
        synchronized (this) {
            writer = framePcm;
            if (writer != pcm) return 0;
            if (switchRequest != 0 && !policy.audioSwitchCalls.firstWrite(audioIndex)) return -1;
            // 暂停态切换在首次可听写入前建立已校验的 PCM 缓冲区。
            if (paused) acceptSwitchedAudio();
        }
        int status = writer.write(samples);
        synchronized (this) {
            if (writer != pcm) return 0;
            if (status >= 0 && !stopped) {
                acceptSwitchedAudio();
                clockUs();
            }
            notifyAll();
        }
        return status;
    }

    /**
     * 结束当前音频流并排空硬件缓冲区。
     *
     * @return 0 表示成功排空，-1 表示排空失败或纪元已停止
     */
    int endAudio() {
        long consumed = pcm.drain();
        synchronized (this) {
            if (consumed < 0 || stopped) return -1;
            clockAnchor = frozenUs = clockUs();
            wallAnchor = nowUs();
            audioEnded = true;
            notifyAll();
            return 0;
        }
    }

    /** 更新视频帧显示间隔统计。 */
    private void updateInterval(long pts, long durationUs) {
        if (lastVideo >= 0 && pts > lastVideo && pts - lastVideo <= 500000) {
            recentIntervals[intervalIndex] = pts - lastVideo;
            intervalIndex = (intervalIndex + 1) % recentIntervals.length;
            intervalCount = Math.min(intervalCount + 1, recentIntervals.length);
        }
        if (durationUs > 0) intervalUs = durationUs;
        else if (intervalCount > 0) {
            long[] ordered = java.util.Arrays.copyOf(recentIntervals, intervalCount);
            java.util.Arrays.sort(ordered);
            intervalUs = ordered[ordered.length / 2];
        }
    }

    /**
     * 判断并执行迟到视频帧丢弃决策。
     * <p>
     * 采用两段式校验协议：首次调用（{@code newer == Long.MIN_VALUE}）仅做潜在落后分类；
     * 二次确认时存在已解码的更新帧，再次比对时钟后确定丢帧，避免单帧突发卡顿导致误丢。
     * </p>
     *
     * @param pts 当前候选帧时间戳（微秒）
     * @param durationUs 帧持续时间（微秒）
     * @param newer 后续已就绪的更新帧时间戳，或 {@link Long#MIN_VALUE}
     * @return 1 表示应丢弃该帧，0 表示不丢弃正常呈现
     */
    synchronized int lateVideo(long pts, long durationUs, long newer) {
        if (!firstVideo || paused || preview || switching || stopped || surface == null ||
                pts < 0 || nextVideo < 0 || Math.abs(pts - nextVideo) > 500000) return 0;
        long duration = durationUs > 0 ? durationUs : intervalUs;
        long threshold = Math.max(50000L, Math.min(Long.MAX_VALUE / 2, duration) * 2);
        if (videoAheadUs(pts) >= -threshold) return 0;
        if (newer == Long.MIN_VALUE) return 1;
        if (newer <= pts || Math.abs(newer - pts - duration) > 500000) return 0;
        updateInterval(pts, durationUs);
        lastVideo = pts;
        nextVideo = pts + intervalUs;
        dropped++;
        return 1;
    }

    /**
     * 获取视频渲染 Surface 并精确等待呈现时序。
     * <p>
     * <b>契约：</b>Native 工作线程每次获取到非空的 Surface 后，必须在释放渲染窗口后配对调用 {@link #finishVideo}。
     * </p>
     *
     * @param ptsUs 当前帧预期显示时间戳（微秒）
     * @param durationUs 当前帧展示持续时长（微秒）
     * @return 可供渲染的目标 Surface，或 {@code null} 表示当前帧应丢弃或跳过
     */
    synchronized Surface acquireVideo(long ptsUs, long durationUs) {
        long pts = ptsUs >= 0 ? ptsUs : nextVideo >= 0 ? nextVideo : targetUs;
        updateInterval(pts, durationUs);
        if (lastVideo < 0 && pts + intervalUs <= targetUs) return null;
        boolean reset = lastVideo < 0 || (nextVideo >= 0 && Math.abs(pts - nextVideo) > 500000);
        if (reset && lastVideo >= 0) {
            videoShiftUs += pts - nextVideo;
            videoJumped = true;
        }
        lastVideo = pts;
        nextVideo = pts + intervalUs;
        if (reset && (audioAnchor < 0 || !audio)) {
            clockAnchor = firstVideo ? pts : Math.max(targetUs, pts);
            wallAnchor = nowUs();
        }
        for (;;) {
            if (stopped) return null;
            if (switching) { waitForChange(0); continue; }
            detachInvalidSurface();
            if (surface == null) {
                if (audio) {
                    // Surface 脱离时将解码进度限制在音频播放头附近。
                    // 避免在 Surface 重新绑定前解码超前数秒，导致无可用画面。
                    if (paused || audioEnded) return null;
                    long ahead = videoAheadUs(pts);
                    if (ahead <= 0) return null;
                    waitForChange(Math.min(20, (ahead + 999) / 1000));
                    continue;
                }
                waitForChange(0);
                continue;
            }
            if (!paused || preview) {
                long ahead = videoAheadUs(pts);
                if (ahead <= 0 || preview) break;
                waitForChange(Math.min(20, (ahead + 999) / 1000));
            } else {
                waitForChange(0);
            }
        }
        preview = false;
        Surface target = surface;
        if ("ffmpeg".equals(backend)) {
            // 对宿主原生 Surface 执行 CPU 锁定会保持其 Producer 处于连接状态。
            // 使用独立的 Surface 代理客户端，使得释放/重新挂载能够把同一个 BufferQueue 归还给 MediaCodec。
            if (cpuSurface == null) {
                android.os.Parcel parcel = android.os.Parcel.obtain();
                try {
                    surface.writeToParcel(parcel, 0);
                    parcel.setDataPosition(0);
                    cpuSurface = Surface.CREATOR.createFromParcel(parcel);
                } finally { parcel.recycle(); }
            }
            target = cpuSurface;
        }
        videoBusy = true;
        return target;
    }

    /** 检查 Surface 有效性并在失效时自动剥离并通知观察者。 */
    private void detachInvalidSurface() {
        if (surface != null && !surface.isValid()) {
            Surface invalid = surface;
            bind(null);
            observer.surfaceLost(this, invalid);
        }
    }

    private long firstOnSurface;
    /** 获取首帧视频画面绘制到当前 Surface 上的系统绝对纳秒时间戳。 */
    synchronized long firstOnSurface() { return firstOnSurface; }

    /**
     * 结束单帧视频渲染流程，更新性能统计指标并解除忙碌互斥锁。
     *
     * @param ptsUs 实际提交帧的时间戳（微秒）
     * @param didSubmit 该帧是否实际成功提交绘制
     */
    synchronized void finishVideo(long ptsUs, boolean didSubmit) {
        videoBusy = false;
        notifyAll();
        if (!didSubmit || stopped) return;
        long actual = ptsUs < 0 ? lastVideo : ptsUs;
        long now = SystemClock.elapsedRealtimeNanos();
        if (firstOnSurface == 0) firstOnSurface = now;
        if (lastSubmissionNs != 0) maxGapNs = Math.max(maxGapNs, now - lastSubmissionNs);
        lastSubmissionNs = paused ? 0 : now;
        lastOffsetUs = actual - clockUs();
        submitted++;
        if (paused) frozenUs = videoJumped ? actual : Math.max(targetUs, actual);
        signalReady(actual);
        if (!firstVideo) {
            firstVideo = true;
            if (firstVideoNs == 0) firstVideoNs = now;
            if (recovery != null && "ffmpeg".equals(backend) && recovery.firstSoftwareSubmissionNanos == 0) {
                recovery = new VideoRecoveryInfo(recovery.failureStage, recovery.failureNanos,
                        recovery.failurePositionUs, recovery.targetUs, actual, now);
            }
            observer.firstVideo(this);
        }
    }

    /**
     * 等待所有在途的视频缓冲完全排空呈现。
     *
     * @return 0 表示成功排空，-1 表示被停止中断
     */
    synchronized int endVideo() {
        while (!stopped && surface != null && nextVideo >= 0 && videoAheadUs(nextVideo) > 0) {
            waitForChange(paused ? 0 : Math.min(20, Math.max(1, videoAheadUs(nextVideo) / 1000)));
        }
        return stopped ? -1 : 0;
    }

    /** 释放 CPU 软解渲染用的 Surface 代理对象。 */
    private void releaseCpuSurface() {
        if (cpuSurface != null) { cpuSurface.release(); cpuSurface = null; }
    }

    /**
     * 关闭并终结当前渲染纪元，释放所有音视频底层输出与编解码器句柄。
     */
    @Override public synchronized void close() {
        if (!stopped) frozenUs = clockUs();
        finalStats = statistics();
        stopped = true;
        pcm.close();
        if (hardware != null) { hardware.close(); hardware = null; }
        releaseCpuSurface();
        surface = null;
        notifyAll();
    }
}
