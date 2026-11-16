package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;

/**
 * 硬件解码故障向软件解码（FFmpeg）回退恢复过程的时间戳与性能统计模型。
 * <p>
 * 记录从硬件解码器抛出异常中断、触发软解降级，直至软解首帧画面成功提交上屏的全链路关键时间锚点。
 * 所有系统纳秒时间戳均基于 {@link android.os.SystemClock#elapsedRealtimeNanos()}。
 * </p>
 */
public final class VideoRecoveryInfo {

    /** 触发故障降级的硬件执行阶段标识（如 {@code "hardwareDecode"}、{@code "hardwareConfigure"} 等）。 */
    public final @NotNull String failureStage;

    /** 硬件解码故障发生的系统绝对纳秒时间戳。 */
    public final long failureNanos;

    /** 硬件解码故障发生时的播放媒体时间戳（微秒）。 */
    public final long failurePositionUs;

    /** 回退至软解恢复重试的目标定位时间戳（微秒）。 */
    public final long targetUs;

    /** 软解成功输出首帧画面的媒体显示时间戳（微秒）。 */
    public final long firstSoftwarePtsUs;

    /** 软解首帧画面实际完成提交渲染的系统绝对纳秒时间戳。 */
    public final long firstSoftwareSubmissionNanos;

    /**
     * 构造硬件故障回退恢复信息模型。
     *
     * @param stage 发生故障的硬件阶段
     * @param failureNs 故障发生的绝对纳秒时间戳
     * @param at 故障发生时的媒体位置（微秒）
     * @param target 软解恢复的目标定位位置（微秒）
     * @param firstPts 软解输出首帧的显示时间戳（微秒）
     * @param firstNs 软解首帧提交渲染的绝对纳秒时间戳
     */
    VideoRecoveryInfo(String stage, long failureNs, long at, long target, long firstPts, long firstNs) {
        failureStage = stage;
        failureNanos = failureNs; failurePositionUs = at; targetUs = target;
        firstSoftwarePtsUs = firstPts; firstSoftwareSubmissionNanos = firstNs;
    }
}
