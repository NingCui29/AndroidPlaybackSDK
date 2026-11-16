package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;

/**
 * 单条媒体流（音频轨、视频轨或字幕轨）的元数据描述类（不可变对象）。
 */
public final class TrackInfo {
    /**
     * 媒体轨道类型。
     */
    public enum Type {
        /** 音频轨道。 */
        AUDIO,
        /** 视频轨道。 */
        VIDEO,
        /** 字幕轨道。 */
        SUBTITLE,
        /** 附件、封面或其它数据流。 */
        OTHER
    }

    /** 归属的媒体会话编号。 */
    public final long mediaId;
    /** 容器内流的索引序号（从 0 起算）。 */
    public final int index;
    /** 轨道媒体类型。 */
    public final @NotNull Type type;
    /** 编码格式名称（如 "h264"、"hevc"、"aac"、"flac"、"mp3" 等）。 */
    public final @NotNull String codec;
    /** 音频或字幕语言标识代码（ISO 639-1 / ISO 639-2）。 */
    public final @NotNull String language;
    /** 已过滤控制字符的 UTF-8 轨道名称/标题，无则为空字符串。 */
    public final @NotNull String title;
    /** 原生流时间基分子（每 tick 对应的秒数分子）。 */
    public final int timeBaseNumerator;
    /** 原生流时间基分母（每 tick 对应的秒数分母）。 */
    public final int timeBaseDenominator;
    /** 像素宽高比分子（PAR），为 0 时表示未指定特殊宽高比（使用 1:1）。 */
    public final int pixelAspectNumerator;
    /** 像素宽高比分母（PAR）。 */
    public final int pixelAspectDenominator;
    /** 编解码器初始化参数头（extradata/SPS/PPS）字节大小。 */
    public final int codecInitializationBytes;
    /** 容器标记是否为默认播放轨道。 */
    public final boolean defaultTrack;
    /** 是否为专辑静态封面图流。 */
    public final boolean coverArt;
    /** 当前库构建是否包含并支持该轨道的软件解码器。 */
    public final boolean softwareDecodable;
    /** 视频原始像素宽度（对于非视频轨为 0）。 */
    public final int width;
    /** 视频原始像素高度（对于非视频轨为 0）。 */
    public final int height;
    /** 音频采样率（Hz，对于非音频轨为 0）。 */
    public final int sampleRate;
    /** 音频声道数量（对于非音频轨为 0）。 */
    public final int channels;
    /** 视频画面需要顺时针旋转的角度（0, 90, 180, 270）。 */
    public final int rotationDegrees;

    TrackInfo(long mediaId, int index, int type, String codec, String language, boolean defaultTrack,
              boolean coverArt, boolean softwareDecodable, int width, int height, int sampleRate, int channels, int rotation,
              byte[] titleUtf8, int timeNum, int timeDen, int aspectNum, int aspectDen, int initBytes) {
        this.mediaId = mediaId;
        this.index = index;
        this.type = Type.values()[type];
        this.codec = codec;
        this.language = language;
        this.title = new String(titleUtf8, java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\p{Cntrl}", "");
        timeBaseNumerator = timeNum;
        timeBaseDenominator = timeDen;
        pixelAspectNumerator = aspectNum;
        pixelAspectDenominator = aspectDen;
        codecInitializationBytes = initBytes;
        this.defaultTrack = defaultTrack;
        this.coverArt = coverArt;
        this.softwareDecodable = softwareDecodable;
        this.width = width;
        this.height = height;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.rotationDegrees = rotation;
    }
}
