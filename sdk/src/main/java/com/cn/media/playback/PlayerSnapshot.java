package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 某一版本修订点（Revision）下的播放器不可变状态快照。
 * <p>
 * 包含播放生命周期状态、当前进度位置、媒体与轨道信息、音量及实时统计数据。所有时间字段单位均为毫秒。
 */
public final class PlayerSnapshot {
    /**
     * 播放生命周期状态枚举。
     */
    public enum State {
        /** 空闲状态（初始或 stop 完成后）。 */
        IDLE,
        /** 正在异步打开媒体源并探测音视频流信息。 */
        PREPARING,
        /** 媒体已解析就绪，处于等待播放状态。 */
        READY,
        /** 正在正常播放。 */
        PLAYING,
        /** 处于暂停状态。 */
        PAUSED,
        /** 媒体已自然播放到末尾结束。 */
        ENDED,
        /** 发生不可恢复错误导致播放终止。 */
        ERROR,
        /** 正在异步执行停止操作。 */
        STOPPING,
        /** 正在异步执行资源释放操作。 */
        RELEASING,
        /** 资源已完全回收释放，实例不可再用。 */
        RELEASED
    }

    /** 状态快照修订版本号，每次状态变更自增。 */
    public final long revision;
    /** 媒体会话编号。 */
    public final long mediaId;
    /** 当前时间轴代际编号（每次重新打开、Seek 或故障回退时自增）。 */
    public final long timelineId;
    /** 当前播放进度时间（毫秒）。 */
    public final long positionMs;
    /** Surface 绑定版本代数（宿主每绑定或解绑一次 Surface 自增）。 */
    public final long surfaceVersion;
    /** 在当前 Surface 绑定与时间轴下首次提交视频渲染的纳秒时刻（基于 SystemClock.elapsedRealtimeNanos()），未提交前为 0。 */
    public final long firstSurfaceSubmissionNs;
    /** 当前生命周期状态。 */
    public final @NotNull State state;
    /** 用户播放意图（true 表示意图处于播放，false 表示意图处于暂停）。 */
    public final boolean playWhenReady;
    /** 当前是否正在执行拖动定位预滚。 */
    public final boolean seeking;
    /** 当前是否处于静音状态。 */
    public final boolean muted;
    /** 当前线性音量（0.0 至 1.0）。 */
    public final float volume;
    /** 媒体信息元数据（准备完成前或释放后为 null）。 */
    public final @Nullable MediaInfo media;
    /** 当前激活的音轨信息（若无音频或未选轨则为 null）。 */
    public final @Nullable TrackInfo audioTrack;
    /** 当前激活的视频轨信息（若无视频或未选轨则为 null）。 */
    public final @Nullable TrackInfo videoTrack;
    /** 正在准备切换中的目标音轨（切换完成前有效音轨仍由 audioTrack 保持）。 */
    public final @Nullable TrackInfo pendingAudioTrack;
    /** 当前视频解码后端名称（如 "c2.rk.avc.decoder"、"c2.rk.hevc.decoder"、"ffmpeg" 等）。 */
    public final @NotNull String videoBackend;
    /** 当前会话终止或发生的错误（无错误时为 null）。 */
    public final @Nullable PlayerError error;
    /** 当前媒体会话中记录的所有轨道级降级或错误列表。 */
    public final @NotNull java.util.List<PlayerError> trackFailures;
    /** 累计播放性能与统计指标（已渲染帧数、丢帧数、已消费 PCM 帧数等）。 */
    public final @NotNull PlaybackStats stats;

    PlayerSnapshot(long revision, long mediaId, long timelineId, State state, boolean playWhenReady,
                   boolean seeking, long positionMs, MediaInfo media, TrackInfo audio, TrackInfo video,
                   float volume, boolean muted, String videoBackend, PlayerError error, PlaybackStats stats,
                   TrackInfo pendingAudioTrack, long surfaceVersion, long firstSurfaceSubmissionNs,
                   java.util.List<PlayerError> trackFailures) {
        this.surfaceVersion = surfaceVersion;
        this.trackFailures = trackFailures;
        this.firstSurfaceSubmissionNs = firstSurfaceSubmissionNs;
        this.revision = revision;
        this.mediaId = mediaId;
        this.timelineId = timelineId;
        this.state = state;
        this.playWhenReady = playWhenReady;
        this.seeking = seeking;
        this.positionMs = positionMs;
        this.media = media;
        this.audioTrack = audio;
        this.pendingAudioTrack = pendingAudioTrack;
        this.videoTrack = video;
        this.volume = volume;
        this.muted = muted;
        this.videoBackend = videoBackend;
        this.error = error;
        this.stats = stats;
    }
}
