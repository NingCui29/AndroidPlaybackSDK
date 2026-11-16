package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;

/**
 * 播放器错误与异常诊断信息类。
 * <p>
 * 提供稳定的公开错误分类码（1xxx～5xxx），以及脱敏后的内部实现阶段与原生状态，保障排查诊断能力同时不泄露用户私有数据。
 */
public final class PlayerError {
    /**
     * 稳定的对外公开错误码定义。
     */
    public enum Code {
        /** 参数不合法（如音量超出 0.0~1.0 等）。 (1001) */
        INVALID_ARGUMENT(1001),
        /** 播放器处于当前操作不允许的非法状态。 (1002) */
        INVALID_STATE(1002),
        /** 当前媒体不支持 Seek 操作（如顺序输入流）。 (1003) */
        SEEK_UNAVAILABLE(1003),
        /** 指定的音视频轨道未在媒体中找到。 (1004) */
        TRACK_NOT_FOUND(1004),

        /** 输入源访问权限不足（如 Content URI 权限失效）。 (2001) */
        INPUT_PERMISSION(2001),
        /** 读取输入源时发生 I/O 异常或文件截断。 (2002) */
        INPUT_IO(2002),
        /** 输入流在规定时间内未送达有效数据引发超时。 (2003) */
        INPUT_TIMEOUT(2003),

        /** 容器封装格式或编码不支持。 (3001) */
        FORMAT_UNSUPPORTED(3001),
        /** 单特定轨道的编码参数无法被当前软硬件支持。 (3002) */
        TRACK_UNSUPPORTED(3002),
        /** 媒体数据损坏或头部解析失败。 (3003) */
        INVALID_MEDIA_DATA(3003),
        /** 媒体内所有轨道均无法被解码播放。 (3004) */
        NO_PLAYABLE_TRACK(3004),

        /** 解码器创建或配置初始化失败。 (4001) */
        DECODER_INIT(4001),
        /** 解码器在送入码流解码运行过程中发生致命错误。 (4002) */
        DECODER_RUNTIME(4002),

        /** 渲染输出目标丢失或失效（如 Surface 被系统销毁或 AudioTrack 写入被拒）。 (5001) */
        OUTPUT_LOST(5001),
        /** 内存、文件描述符或硬件编解码器实例耗尽。 (5002) */
        RESOURCE_EXHAUSTED(5002);

        /** 数字错误码。 */
        public final int number;
        Code(int number) { this.number = number; }

        static Code fromNumber(int number) {
            for (Code value : values()) if (value.number == number) return value;
            throw new IllegalArgumentException("Unknown internal error category");
        }
    }

    /**
     * 错误影响的作用域。
     */
    public enum Scope {
        /** 请求级：仅当前发起的特定命令失败，不影响播放会话继续。 */
        REQUEST,
        /** 轨道级：某一单轨解码或切换失败，会话可降级继续播放其它有效轨道。 */
        TRACK,
        /** 会话级：严重错误导致整个播放会话终止，播放器转为 ERROR 状态。 */
        SESSION
    }

    /** 稳定的错误分类代码。 */
    public final @NotNull Code code;
    /** 错误影响的作用域。 */
    public final @NotNull Scope scope;
    /** 发生错误时的媒体会话 ID。 */
    public final long mediaId;
    /** 发生错误时的时间轴版本代号。 */
    public final long timelineId;
    /** 关联的用户请求命令序列号（若非特定命令触发则为 0）。 */
    public final long requestId;
    /** 发生错误的内部处理阶段标识（如 "surface"、"render"、"demux" 等）。 */
    public final @NotNull String stage;
    /** 发生错误的软硬件后端名称（如 "c2.android.avc.decoder"、"ffmpeg" 等）。 */
    public final @NotNull String backend;
    /** 底层 FFmpeg 返回的负数错误码或平台 MediaCodec 错误码，无则为 0。 */
    public final int nativeStatus;
    /** 发生故障的媒体流索引序号，与特定流无关时为 -1。 */
    public final int trackIndex;

    PlayerError(Code code, Scope scope, long mediaId, long timelineId, long requestId,
                String stage, String backend, int nativeStatus) {
        this(code, scope, mediaId, timelineId, requestId, stage, backend, nativeStatus, -1);
    }

    PlayerError(Code code, Scope scope, long mediaId, long timelineId, long requestId,
                String stage, String backend, int nativeStatus, int trackIndex) {
        this.code = code;
        this.scope = scope;
        this.mediaId = mediaId;
        this.timelineId = timelineId;
        this.requestId = requestId;
        this.stage = stage;
        this.backend = backend;
        this.nativeStatus = nativeStatus;
        this.trackIndex = trackIndex;
    }
}
