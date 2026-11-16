package com.cn.media.playback;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 当前播放时间轴（Timeline）下的实时性能、音视频吞吐量与质量统计指标类（不可变对象）。
 * <p>
 * 音频采样计数单位为交错采样帧（Interleaved Sample Frames），时间计量涵盖首帧/首声纳秒时刻与卡顿最大间隔。
 */
public final class PlaybackStats {
    /** 累计向 AudioTrack 写入的 PCM 采样帧总数。 */
    public final long audioFramesWritten;
    /** AudioTrack 硬件声卡实际已播放消费的 PCM 采样帧总数。 */
    public final long audioFramesConsumed;
    /** 成功提交给 Surface 进行渲染显示的视频帧总数。 */
    public final long videoFramesSubmitted;
    /** 因严重迟到而被丢弃的视频帧总数。 */
    public final long videoFramesDropped;
    /** 视频帧提交给渲染系统的最长单次间隔时间（毫秒），用于衡量卡顿。 */
    public final long maxSubmissionGapMs;
    /** 首个 PCM 采样帧被实际消费的纳秒时刻（基于 SystemClock.elapsedRealtimeNanos()），未出声前为 0。 */
    public final long firstAudioConsumptionNs;
    /** 首个视频帧成功提交给 Surface 的纳秒时刻（基于 SystemClock.elapsedRealtimeNanos()），未提交前为 0。 */
    public final long firstVideoSubmissionNs;
    /** 最近一次提交视频帧的时间戳相对内部媒体主时钟的偏差偏移（微秒）。 */
    public final long lastVideoOffsetUs;
    /** 当前激活的 AudioTrack 音频输出路由设备 ID，未知时为 0。 */
    public final int audioRouteId;
    /** 当前激活的音频输出设备类型常量（对应 android.media.AudioDeviceInfo.TYPE_*）。 */
    public final int audioRouteType;
    /** 音频物理输出设备切换变更次数（如扬声器转耳机）。 */
    public final int audioRouteChanges;
    /** AudioTrack 重建恢复次数。 */
    public final int audioOutputRebuilds;
    /** 软解最近一次渲染所选用的色彩空间矩阵（如 "BT.709"、"BT.601" 等）。 */
    public final @NotNull String softwareColorMatrix;
    /** 软解最近一次渲染所选用的色彩范围（"Limited" 或 "Full"）。 */
    public final @NotNull String softwareColorRange;
    /** 色彩矩阵是否由画面分辨率推导（未显式标记时）。 */
    public final boolean softwareMatrixInferred;
    /** 色彩范围是否由像素格式推导。 */
    public final boolean softwareRangeInferred;
    /** 当前渲染周期内软解持有排队帧与应用侧转换暂存的峰值字节数。 */
    public final long softwarePeakFrameBytes;
    /** 硬件解码直出画面的实际宽高与裁剪几何信息（MediaCodec 报告格式前为 null）。 */
    public final @Nullable HardwareOutputInfo hardwareOutput;
    /** 最近一次硬件解码器筛选时匹配到的候选组件列表及优先级。 */
    public final @NotNull List<String> hardwareSelection;
    /** 最近一次硬件故障平滑回退至软解的详细度量信息（未发生回退时为 null）。 */
    public final @Nullable VideoRecoveryInfo videoRecovery;
    /** 媒体主时钟捕获到的时间戳跳变（Discontinuity）次数。 */
    public final int audioTimestampJumps;

    PlaybackStats(long written, long consumed, long submitted, long dropped, long gap, long offset,
                  int color, long peakBytes, int routeId, int routeType, int routeChanges, int rebuilds,
                  HardwareOutputInfo hardwareOutput, List<String> hardwareSelection, long firstAudioNs, long firstVideoNs,
                  VideoRecoveryInfo videoRecovery, int audioTimestampJumps) {
        audioFramesWritten = written;
        audioFramesConsumed = consumed;
        videoFramesSubmitted = submitted;
        videoFramesDropped = dropped;
        maxSubmissionGapMs = gap;
        firstAudioConsumptionNs = firstAudioNs;
        firstVideoSubmissionNs = firstVideoNs;
        lastVideoOffsetUs = offset;
        audioRouteId = routeId;
        audioRouteType = routeType;
        audioRouteChanges = routeChanges;
        audioOutputRebuilds = rebuilds;
        switch (color & 255) {
            case 1: softwareColorMatrix = "BT.709"; break;
            case 4: softwareColorMatrix = "FCC"; break;
            case 5: softwareColorMatrix = "BT.601"; break;
            case 7: softwareColorMatrix = "SMPTE 240M"; break;
            case 9: softwareColorMatrix = "BT.2020 NCL"; break;
            default: softwareColorMatrix = "";
        }
        softwareColorRange = color == 0 ? "" : (color & 256) != 0 ? "full" : "limited";
        softwareMatrixInferred = (color & 1024) != 0;
        softwareRangeInferred = (color & 512) != 0;
        softwarePeakFrameBytes = peakBytes;
        this.hardwareOutput = hardwareOutput;
        this.hardwareSelection = hardwareSelection;
        this.videoRecovery = videoRecovery;
        this.audioTimestampJumps = audioTimestampJumps;
    }

    static final PlaybackStats EMPTY = new PlaybackStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null,
            java.util.Collections.emptyList(), 0, 0, null, 0);
}
