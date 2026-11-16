package com.cn.media.playback;

import java.util.ArrayDeque;

/**
 * 音频 PCM 帧数到媒体时间戳（PTS）连续映射时间轴类。
 * <p>
 * 解决由于流时间戳跳变（Discontinuity）、换轨或 AudioTrack 重建导致的播放头与媒体时间不连续问题。
 * 支持维护段落队列（Segments），当声卡消费推进越过跳变边界时才触发媒体时间的跳变更新。
 */
final class PcmTimeline {
    /** 映射时间段。 */
    private static final class Segment {
        final long startFrame, ptsUs;
        Segment(long startFrame, long ptsUs) { this.startFrame = startFrame; this.ptsUs = ptsUs; }
    }
    private final ArrayDeque<Segment> pending = new ArrayDeque<>();
    private long currentFrame, currentPts, firstPts, inheritedShift;
    /** 捕获并应用的时间戳跳变累计次数。 */
    int jumps;

    /**
     * 重置并启动时间轴映射。
     *
     * @param ptsUs         起始微秒时间戳
     * @param previousShift 继承的上一代际偏移量
     */
    void start(long ptsUs, long previousShift) {
        pending.clear();
        currentFrame = 0;
        currentPts = firstPts = ptsUs;
        inheritedShift = previousShift;
    }

    /**
     * 追加待生效的时间戳跳变边界段。
     *
     * @param queuedFrames 该段生效对应的排队总帧数
     * @param ptsUs        对应的媒体微秒时间戳
     */
    void append(long queuedFrames, long ptsUs) {
        if (!pending.isEmpty() && pending.getLast().startFrame == queuedFrames) pending.removeLast();
        pending.add(new Segment(queuedFrames, ptsUs));
    }

    /**
     * 根据当前硬件声卡实际已消费的 PCM 采样帧数，换算当前精准的媒体微秒时间。
     *
     * @param consumed 已消费采样帧数
     * @param rate     当前音频输出采样率（Hz）
     * @return 当前媒体呈现时间戳（微秒）
     */
    long mediaUs(long consumed, int rate) {
        while (!pending.isEmpty() && consumed > pending.getFirst().startFrame) {
            Segment segment = pending.removeFirst();
            currentFrame = segment.startFrame;
            currentPts = segment.ptsUs;
            jumps++;
        }
        return currentPts + timeUs(consumed - currentFrame, rate);
    }

    /**
     * 获得当前已消费连续时间相对基准的偏移校正量（微秒），用于音画同步调度。
     *
     * @param rate 采样率
     * @return 偏移微秒数
     */
    long shiftUs(int rate) {
        return inheritedShift + currentPts - firstPts - timeUs(currentFrame, rate);
    }

    private static long timeUs(long frames, int rate) {
        return frames / rate * 1000000L + (frames % rate) * 1000000L / rate;
    }
}
