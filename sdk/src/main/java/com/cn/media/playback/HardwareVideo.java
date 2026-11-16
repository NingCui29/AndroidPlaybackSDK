package com.cn.media.playback;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台硬件视频解码驱动与调度器。
 * <p>
 * 基于 Android {@link MediaCodec} 同步模式实现，归属于后台视频解码工作线程独占拥有。
 * 支持以下企业级关键能力：
 * <ul>
 *     <li>平台硬件加速解码器智能发现与白名单筛选（过滤系统软解与别名，优先平台纯硬件实现）；</li>
 *     <li>无缝 Surface 动态热切换（{@link MediaCodec#setOutputSurface}）与无缝离屏占位 Surface 回退；</li>
 *     <li>SPS/PPS 动态重配与自适应分辨率调整（Adaptive Playback）；</li>
 *     <li>精确首帧输出超时监控与故障捕获，配合 {@link PlaybackOutput} 触发自动无缝降级回退到 FFmpeg 软解。</li>
 * </ul>
 * </p>
 */
final class HardwareVideo implements AutoCloseable {

    /**
     * 硬件视频解码器故障异常。
     * <p>
     * 封装发生硬件故障的具体执行阶段、组件名称、底层平台错误代码及媒体时间戳信息。
     * </p>
     */
    static final class Fault extends RuntimeException {
        /** 发生故障的执行阶段（如 "hardwareSetup"、"hardwareConfigure"、"hardwareDecode" 等）。 */
        final String stage;
        /** 发生故障的编解码器组件名称（如 "c2.android.avc.decoder"）。 */
        final String component;
        /** 底层系统 {@link MediaCodec.CodecException} 返回的原生错误代码，非系统异常时为 0。 */
        final int platformCode;
        /** 标识故障是否发生在已成功起播运行之后（{@code true} 为运行期故障，{@code false} 为初始化阶段故障）。 */
        boolean runtime;
        /** 故障发生的系统绝对纳秒时间戳。 */
        long failedAtNs;
        /** 故障发生时的播放媒体时间戳（微秒）。 */
        long positionUs;

        /**
         * 构建硬件解码故障异常。
         *
         * @param stage 故障阶段标识
         * @param component 组件名称
         * @param cause 异常原因
         */
        Fault(String stage, String component, Throwable cause) {
            super(stage, cause);
            this.stage = stage;
            this.component = component == null ? "" : component;
            this.platformCode = cause instanceof MediaCodec.CodecException ?
                    ((MediaCodec.CodecException)cause).getErrorCode() : 0;
        }

        /**
         * 将内部硬件故障映射转换为对外统一的播放器错误码 {@link PlayerError.Code}。
         *
         * @return 映射后的错误代码枚举
         */
        PlayerError.Code code() {
            if (platformCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE ||
                    platformCode == MediaCodec.CodecException.ERROR_RECLAIMED) return PlayerError.Code.RESOURCE_EXHAUSTED;
            if ("hardwareAccessUnit".equals(stage) || "hardwareTimestamp".equals(stage)) return PlayerError.Code.INVALID_MEDIA_DATA;
            if ("hardwareSurface".equals(stage)) return PlayerError.Code.OUTPUT_LOST;
            return runtime ? PlayerError.Code.DECODER_RUNTIME : PlayerError.Code.DECODER_INIT;
        }
    }

    private final PlaybackOutput owner;
    private final CodecCalls calls;
    private final String mime;
    private int width, height, configuredWidth, configuredHeight;
    private final int rotation;
    private int profile, level;
    private final PlayerConfig policy;
    private final float frameRate;
    private NalAdapter nal;
    private final ByteBuffer rawConfig;
    private List<String> names;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final Map<Long,Long> durations = new LinkedHashMap<>();
    private Surface desired, active, placeholder;
    private SurfaceTexture texture;
    private volatile MediaCodec codec;
    private String name = "";
    private volatile Fault bindingFault;
    private boolean closed;
    private boolean sawOutput, firstPacket = true, eos, everQueued;
    private long firstQueuedMs, idleSince;
    private long acquisitionMs, acquisitionBytes;
    private int configurationVersion = 1, formatChanges, adaptiveChanges, rebuilds;
    private boolean adaptive, pendingConfiguration, prependConfiguration;
    private Picture pendingPicture;

    /**
     * 解码就绪待呈现画面结构体。
     */
    private static final class Picture {
        final MediaCodec codec; final int index; final long pts, duration;
        Picture(MediaCodec codec, int index, long pts, long duration) {
            this.codec = codec; this.index = index; this.pts = pts; this.duration = duration;
        }
    }

    /**
     * 创建硬件视频解码驱动实例并探测匹配候选解码器。
     *
     * @param owner 所属渲染纪元
     * @param track 视频轨道元数据
     * @param surface 初始绑定的渲染目标 Surface
     * @param extra 编码额外配置头（SPS/PPS 等）
     * @param ffProfile FFmpeg 提取的编码 Profile
     * @param ffLevel FFmpeg 提取的编码 Level
     * @param bits 像素位深（如 8 或 10）
     * @param fps 视频帧率
     * @param policy 播放器配置策略
     */
    HardwareVideo(PlaybackOutput owner, TrackInfo track, Surface surface, ByteBuffer extra,
                  int ffProfile, int ffLevel, int bits, float fps, PlayerConfig policy) {
        this.owner = owner;
        this.policy = policy;
        calls = policy.codecCalls;
        width = track.width;
        height = track.height;
        rotation = track.rotationDegrees;
        frameRate = fps;
        desired = surface;
        switch (track.codec) {
            case "h264": mime = MediaFormat.MIMETYPE_VIDEO_AVC; break;
            case "hevc": mime = MediaFormat.MIMETYPE_VIDEO_HEVC; break;
            case "vp8": mime = MediaFormat.MIMETYPE_VIDEO_VP8; break;
            case "vp9": mime = MediaFormat.MIMETYPE_VIDEO_VP9; break;
            case "mpeg4": mime = MediaFormat.MIMETYPE_VIDEO_MPEG4; break;
            default: throw new Fault("hardwareMime", "", null);
        }
        boolean avc = mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC);
        boolean hevc = mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC);
        profile = androidProfile(avc, hevc, ffProfile, bits);
        level = androidLevel(avc, hevc, ffLevel);
        nal = avc || hevc ? new NalAdapter(hevc, extra) : null;
        if (nal == null && extra != null && extra.hasRemaining()) {
            rawConfig = ByteBuffer.allocate(extra.remaining());
            rawConfig.put(extra.duplicate()).flip();
        } else rawConfig = null;
        names = candidates(policy);
        if (names.isEmpty()) throw new Fault("hardwareCapabilities", "", null);
    }

    /** 将 FFmpeg profile 常量映射为 Android 平台的 MediaCodecInfo profile 常量。 */
    private static int androidProfile(boolean avc, boolean hevc, int profile, int bits) {
        if (hevc) {
            if (profile == 2 || bits == 10) return MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
            if (profile == 1) return MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
        }
        if (avc) switch (profile & 255) {
            case 66: return MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
            case 77: return MediaCodecInfo.CodecProfileLevel.AVCProfileMain;
            case 88: return MediaCodecInfo.CodecProfileLevel.AVCProfileExtended;
            case 100: return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
            case 110: return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10;
            case 122: return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422;
            case 244: return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444;
            default: break;
        }
        return 0;
    }

    /** 将 FFmpeg level 常量映射为 Android 平台的 MediaCodecInfo level 常量。 */
    private static int androidLevel(boolean avc, boolean hevc, int level) {
        int[] levels = avc ? new int[]{10,9,11,12,13,20,21,22,30,31,32,40,41,42,50,51,52,60,61,62} :
                new int[]{30,60,63,90,93,120,123,150,153,156,180,183,186};
        if (avc || hevc) for (int i = 0; i < levels.length; i++)
            if (levels[i] == level) return 1 << (avc ? i : 2*i);
        return 0;
    }

    /** 构建当前格式的 {@link MediaFormat}。 */
    private MediaFormat format() {
        MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
        if (profile != 0) f.setInteger(MediaFormat.KEY_PROFILE, profile);
        if (level != 0) f.setInteger(MediaFormat.KEY_LEVEL, level);
        if (frameRate > 0) f.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate);
        return f;
    }

    /**
     * 发现并筛选适合当前码流特征的可用硬件解码器候选列表。
     *
     * @param policy 播放器配置策略
     * @return 优选的硬件解码组件名称列表
     */
    private List<String> candidates(PlayerConfig policy) {
        List<String> selected = new ArrayList<>(2);
        List<String> trace = new ArrayList<>();
        trace.add(mime + " " + width + "x" + height + " fps=" + frameRate + " profile=" + profile + " level=" + level);
        for (MediaCodecInfo component : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            String id = component.getName();
            if (component.isEncoder() || !java.util.Arrays.asList(component.getSupportedTypes()).contains(mime)) continue;
            if (selected.contains(id)) { trace.add(id + " duplicate"); continue; }
            if (Build.VERSION.SDK_INT >= 29) {
                if (component.isAlias()) { trace.add(id + " alias of " + component.getCanonicalName()); continue; }
                if (!component.isHardwareAccelerated() || component.isSoftwareOnly()) { trace.add(id + " software"); continue; }
            } else if (!policy.verifiedLegacyCodecs.contains(id)) { trace.add(id + " unverified firmware"); continue; }
            try {
                MediaCodecInfo.CodecCapabilities cap = component.getCapabilitiesForType(mime);
                if (cap.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) ||
                        cap.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
                    trace.add(id + " requires secure/tunneled output"); continue;
                }
                MediaCodecInfo.VideoCapabilities vc = cap.getVideoCapabilities();
                if (vc == null || !vc.isSizeSupported(width, height)) { trace.add(id + " size unsupported"); continue; }
                if (frameRate > 0 && !vc.areSizeAndRateSupported(width,height,frameRate)) {
                    trace.add(id + " rate unsupported"); continue;
                }
                if (!cap.isFormatSupported(format())) { trace.add(id + " format unsupported"); continue; }
                if (selected.size() >= 2) { trace.add(id + " candidate limit"); continue; }
                selected.add(id);
                trace.add(id + (Build.VERSION.SDK_INT >= 29 ? " platform hardware" : " firmware-verified") +
                        " alignment=" + vc.getWidthAlignment() + "x" + vc.getHeightAlignment());
            } catch (IllegalArgumentException ignored) {
                trace.add(id + " capabilities unavailable");
            }
        }
        owner.hardwareSelection(trace);
        return selected;
    }

    /** 获取有效的渲染目标 Surface（若外部未指定或已失效，则自动创建并返回内部占位 Surface）。 */
    private Surface target() {
        if (desired != null && desired.isValid()) return desired;
        if (placeholder == null) {
            texture = Build.VERSION.SDK_INT >= 26 ? new SurfaceTexture(false) : new SurfaceTexture(0);
            texture.setDefaultBufferSize(width, height);
            placeholder = new Surface(texture);
        }
        return placeholder;
    }

    /**
     * 动态绑定新的输出渲染 Surface。
     * <p>
     * 在解码运行过程中调用 {@link MediaCodec#setOutputSurface} 实现无缝切换；
     * 若切换抛出异常则记录故障并释放解码器。
     * </p>
     *
     * @param newSurface 目标 Surface
     */
    synchronized void bind(Surface newSurface) {
        if (closed) return;
        desired = newSurface;
        if (codec == null) return;
        try {
            Surface replacement = target();
            if (active != replacement) {
                codec.setOutputSurface(replacement);
                active = replacement;
            }
        } catch (RuntimeException failed) {
            bindingFault = new Fault("hardwareSurface", name, failed);
            releaseCodec();
        }
    }

    /**
     * 初始化并启动硬件解码器组件。
     * <p>
     * 遍历候选列表，尝试创建、配置并启动解码器，若全部候选失败则抛出最后一次的故障异常。
     * </p>
     */
    private void start() {
        MediaFormat f = format();
        f.setInteger(MediaFormat.KEY_ROTATION, rotation);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, NalAdapter.LIMIT);
        if (nal != null) {
            f.setByteBuffer("csd-0", nal.configuration(0));
            if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) f.setByteBuffer("csd-1", nal.configuration(1));
        } else if (rawConfig != null) f.setByteBuffer("csd-0", rawConfig.duplicate());
        Fault last = null;
        for (String candidate : names) {
            String stage = "hardwareCreate";
            synchronized (this) {
                if (closed) throw new Fault("hardwareCancelled", name, null);
                try {
                    name = candidate;
                    codec = calls.create(name);
                    stage = "hardwareConfigure";
                    active = target();
                    adaptive = codec.getCodecInfo().getCapabilitiesForType(mime)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback);
                    if (adaptive) {
                        f.setInteger(MediaFormat.KEY_MAX_WIDTH, width);
                        f.setInteger(MediaFormat.KEY_MAX_HEIGHT, height);
                    }
                    configuredWidth = width;
                    configuredHeight = height;
                    calls.configure(codec, f, active);
                    stage = "hardwareStart";
                    calls.start(codec);
                } catch (IOException | RuntimeException failure) {
                    last = new Fault(stage, name, failure);
                    releaseCodec();
                }
            }
            if (bindingFault != null) throw bindingFault;
            if (codec != null) {
                owner.backendSelected(name);
                return;
            }
            owner.backendFault(last);
        }
        throw last;
    }

    /**
     * 投递已解封装的视频数据包至硬件解码管道。
     * <p>
     * <b>输入契约：</b>输入缓冲区归调用方所有直至方法返回，JNI 跨层后不会保留数据包引用。<br/>
     * 方法内部自动检测 SPS/PPS 等参数集变化，按需触发自适应配置更新或解码器重新构建。
     * </p>
     *
     * @param packet 原始视频数据包
     * @param pts 显示时间戳（微秒）
     * @param duration 持续时长（微秒）
     * @param key 是否为关键帧
     * @param extra 额外配置头
     */
    void submit(ByteBuffer packet, long pts, long duration, boolean key, ByteBuffer extra) {
        if (bindingFault != null) throw bindingFault;
        synchronized (this) {
            if (idleSince != 0) {
                if (!sawOutput && firstQueuedMs != 0) firstQueuedMs += SystemClock.elapsedRealtime() - idleSince;
                idleSince = 0;
            }
        }
        if (nal != null) {
            try {
                if (extra != null) {
                    NalAdapter updated = new NalAdapter(mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC), extra);
                    pendingConfiguration |= !updated.sameConfiguration(nal);
                    nal = updated;
                }
                int revision = nal.revision();
                nal.collect(packet);
                pendingConfiguration |= nal.revision() != revision;
            } catch (IllegalArgumentException badPacket) { throw new Fault("hardwareAccessUnit", name, badPacket); }
        }
        if (codec == null || pendingConfiguration) {
            if (acquisitionMs == 0) acquisitionMs = SystemClock.elapsedRealtime();
            acquisitionBytes += packet.remaining();
            if (acquisitionBytes > NalAdapter.LIMIT || SystemClock.elapsedRealtime() - acquisitionMs > 2000)
                throw new Fault("hardwareRandomAccess", name, null);
            if (!key || (nal != null && (!nal.randomAccess() || !nal.complete()))) return;
            if (nal != null) {
                NalParameters sps = nal.activeSps();
                width = sps.width; height = sps.height;
                boolean hevc = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime);
                profile = androidProfile(!hevc, hevc, sps.profile, sps.depth);
                level = androidLevel(!hevc, hevc, sps.level);
                if (hevc && sps.highTier) level <<= 1;
            }
            names = candidates(policy);
            if (codec != null) {
                configurationVersion++;
                if (adaptive && names.contains(name) && width <= configuredWidth && height <= configuredHeight) {
                    prependConfiguration = true;
                    adaptiveChanges++;
                } else {
                    // 在废除上一代解码器索引之前，必须先将上一代滞留的画面全部排空递送完毕。
                    finish();
                    synchronized (this) { releaseCodec(); }
                    durations.clear();
                    firstPacket = true; sawOutput = eos = false;
                    synchronized (this) { firstQueuedMs = 0; }
                    rebuilds++;
                }
            }
            if (codec == null) {
                if (names.isEmpty()) throw new Fault("hardwareCapabilities", name, null);
                start();
            }
            acquisitionMs = acquisitionBytes = 0;
            pendingConfiguration = false;
        }
        if (pts == Long.MIN_VALUE) throw new Fault("hardwareTimestamp", name, null);
        queue(packet, pts, key, false);
        if (duration > 0) {
            if (durations.size() >= 256) durations.remove(durations.keySet().iterator().next());
            durations.put(pts, duration);
        }
        firstPacket = false;
        prependConfiguration = false;
        while (drainOne(0)) { /* 排空所有当前立即可用的输出画面 */ }
    }

    /** 阻塞将数据包压入 MediaCodec 输入队列（带超时控制与重试循环）。 */
    private void queue(ByteBuffer packet, long pts, boolean key, boolean end) {
        long began = SystemClock.elapsedRealtime();
        while (!owner.stopped()) {
            if (bindingFault != null) throw bindingFault;
            synchronized (this) {
                if (codec == null) throw new Fault("hardwareLost", name, null);
                int index = codec.dequeueInputBuffer(2000);
                if (index >= 0) {
                    int bytes = 0;
                    if (!end) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        if (input == null) throw new Fault("hardwareInputBuffer", name, null);
                        input.clear();
                        if (nal != null) bytes = prependConfiguration ? nal.adaptiveCopy(packet, input)
                                : nal.copy(packet, input, firstPacket);
                        else {
                            bytes = packet.remaining();
                            if (bytes > NalAdapter.LIMIT || bytes > input.remaining())
                                throw new Fault("hardwareInputCapacity", name, null);
                            input.put(packet.duplicate());
                        }
                    }
                    calls.queue(codec, index, bytes, pts, end ? MediaCodec.BUFFER_FLAG_END_OF_STREAM :
                            key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    if (firstQueuedMs == 0) firstQueuedMs = SystemClock.elapsedRealtime();
                    if (!end) everQueued = true;
                    return;
                }
            }
            if (drainOne(0)) began = SystemClock.elapsedRealtime();
            else flushPicture();
            if (SystemClock.elapsedRealtime() - began > 2000) throw new Fault("hardwareInputTimeout", name, null);
        }
        throw new Fault("hardwareCancelled", name, null);
    }

    /** 从解码器拉取单个输出缓冲区并处理格式变更与音视频同步丢帧判定。 */
    private boolean drainOne(long timeoutUs) {
        int index;
        MediaCodec instance;
        synchronized (this) {
            if (bindingFault != null) throw bindingFault;
            instance = codec;
            if (instance == null) throw new Fault("hardwareLost", name, null);
            index = instance.dequeueOutputBuffer(info, timeoutUs);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = instance.getOutputFormat();
                int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                int left = integer(f,"crop-left",0), top = integer(f,"crop-top",0);
                int right = integer(f,"crop-right",w-1), bottom = integer(f,"crop-bottom",h-1);
                if (left < 0 || top < 0 || right < left || right >= w || bottom < top || bottom >= h)
                    throw new Fault("hardwareCrop", name, null);
                owner.hardwareFormat(new HardwareOutputInfo(name, w, h, left, top, right, bottom,
                        Math.max(1,integer(f,"sar-width",1)), Math.max(1,integer(f,"sar-height",1)),
                        rotation, configurationVersion, ++formatChanges, adaptiveChanges, rebuilds));
                instance.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                return true;
            }
        }
        if (index < 0) {
            if (!sawOutput && owner.expectsVideoProgress()) {
                synchronized (this) {
                    if (!sawOutput && firstQueuedMs != 0 && SystemClock.elapsedRealtime() - firstQueuedMs > 2000)
                        throw new Fault("hardwareFirstOutput", name, null);
                }
            }
            return index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED;
        }
        eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        boolean picture = (!eos || info.size > 0) && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
        long pts = info.presentationTimeUs;
        Long duration = durations.remove(pts);
        Picture current = new Picture(instance, index, pts, duration == null ? 0 : duration);
        Picture held = pendingPicture;
        if (held != null) {
            pendingPicture = null;
            if (picture && owner.lateVideo(held.pts, held.duration, pts) == 1) release(held, null);
            else present(held);
        }
        if (!picture) release(current, null);
        else {
            sawOutput = true;
            if (!eos && owner.lateVideo(pts, current.duration, Long.MIN_VALUE) == 1) pendingPicture = current;
            else present(current);
        }
        return true;
    }

    /** 从 MediaFormat 安全读取整型配置值。 */
    private static int integer(MediaFormat f, String key, int fallback) {
        return f.containsKey(key) ? f.getInteger(key) : fallback;
    }

    /** 将暂存的待呈现画面立即发布渲染。 */
    void flushPicture() {
        Picture held = pendingPicture;
        pendingPicture = null;
        if (held != null) present(held);
    }

    /** 在输入阻塞等待之前排空挂起画面并更新空闲时钟。 */
    void beforeInputWait() {
        flushPicture();
        if (configurationWaitMs() == 0) throw new Fault("hardwareRandomAccess", name, null);
        synchronized (this) {
            if (idleSince == 0) idleSince = SystemClock.elapsedRealtime();
        }
    }

    /** 获取等待配置就绪的剩余可用毫秒数（最多 2 秒超时）。 */
    int configurationWaitMs() {
        if (acquisitionMs == 0) return -1;
        return (int)Math.max(0, 2000 - (SystemClock.elapsedRealtime()-acquisitionMs));
    }

    /** 重置首帧入队与空闲等待时间锚点（通常在解除暂停或重新绑定 Surface 时触发）。 */
    synchronized void resetOutputWait() {
        long now = SystemClock.elapsedRealtime();
        if (firstQueuedMs != 0) firstQueuedMs = now;
        if (idleSince != 0) idleSince = now;
    }

    /** 请求渲染当前画面并释放缓冲区。 */
    private void present(Picture picture) { release(picture, owner.acquireVideo(picture.pts, picture.duration)); }

    /** 将输出缓冲区交还给解码器，并视绑定的 Surface 状态决定是否渲染上屏。 */
    private void release(Picture picture, Surface selected) {
        boolean submitted = false;
        try {
            synchronized (this) {
                if (bindingFault != null) throw bindingFault;
                if (codec != picture.codec) return;
                if (selected != null && selected == active && selected.isValid()) {
                    codec.releaseOutputBuffer(picture.index, System.nanoTime());
                    submitted = true;
                } else codec.releaseOutputBuffer(picture.index, false);
            }
        } finally {
            if (selected != null) owner.finishVideo(picture.pts, submitted);
        }
    }

    /**
     * 发送流结束标记（EOS）并阻塞排空硬件解码器中的所有积压画面。
     */
    void finish() {
        if (codec == null) throw new Fault("hardwareMissingConfiguration", name, null);
        queue(null, 0, false, true);
        long progress = SystemClock.elapsedRealtime();
        while (!eos && !owner.stopped()) {
            if (drainOne(5000)) progress = SystemClock.elapsedRealtime();
            else if (SystemClock.elapsedRealtime() - progress > 2000) throw new Fault("hardwareDrain", name, null);
        }
        flushPicture();
    }

    /** 检查是否已实际向硬件解码器入队过数据帧。 */
    boolean started() { return everQueued; }
    /** 获取当前活跃的硬件解码组件名称。 */
    String component() { return name; }

    /** 释放底层的 MediaCodec 实例并清理关联引用。 */
    private void releaseCodec() {
        if (codec != null) {
            try { calls.release(codec); }
            catch (RuntimeException releaseFailure) {
                if (bindingFault != null) bindingFault.addSuppressed(releaseFailure);
                else android.util.Log.w("AndroidPlaybackSDK", "Failed to release " + name);
            }
            finally { codec = null; active = null; }
        }
    }

    /**
     * 关闭硬件解码驱动，安全释放解码器与占位 Surface 资源。
     */
    @Override public synchronized void close() {
        closed = true;
        try { releaseCodec(); }
        finally {
            if (placeholder != null) { placeholder.release(); placeholder = null; }
            if (texture != null) { texture.release(); texture = null; }
            desired = null;
        }
    }
}
