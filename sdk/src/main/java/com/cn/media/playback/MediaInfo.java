package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 媒体文件探测解析完成后的容器与音视频流元数据信息类（不可变对象）。
 */
public final class MediaInfo {
    /** 媒体会话编号。 */
    public final long mediaId;
    /** 媒体总时长（毫秒），对于直播流、顺序管道或未知时长的流为 null。 */
    public final @Nullable Long durationMs;
    /** 该媒体容器中包含的所有音视频轨道信息列表。 */
    public final @NotNull List<TrackInfo> tracks;
    /** 当前媒体是否支持拖动定位（Seek）。 */
    public final boolean seekable;
    /** 在探测阶段从输入源实际读取的字节数（不包含前缀回放复用）。 */
    public final long probeBytes;

    /**
     * 探测预算触顶维度枚举。
     */
    public enum ProbeLimit {
        /** 未触及任何预算，自然探测完成。 */
        NONE,
        /** 达到最大允许的探测字节数上限（5 MiB）。 */
        BYTES,
        /** 达到最大允许的墙钟时间上限（5 秒）。 */
        WALL_TIME
    }

    /** 探测终止时的预算触顶状态。 */
    public final @NotNull ProbeLimit probeLimit;
    MediaInfo(long mediaId, long durationMs, boolean seekable, TrackInfo[] tracks, long probeBytes, int limit) {
        this.probeBytes = probeBytes;
        this.probeLimit = ProbeLimit.values()[limit];
        this.mediaId = mediaId;
        this.seekable = seekable;
        this.durationMs = durationMs < 0 ? null : durationMs;
        this.tracks = Collections.unmodifiableList(Arrays.asList(tracks.clone()));
    }
}
