package com.cn.media.playback;

import java.io.Closeable;
import java.io.IOException;

/**
 * 底层原生媒体输入桥接类（JNI 封装）。
 * <p>
 * 负责与底层 C/C++ AVIO 及解封装器交互，包括文件描述符绑定、流探测、时长与轨道解析、
 * 时间戳定位 Seek、以及跨线程取消控制。
 */
final class NativeInput implements Closeable {
    static { System.loadLibrary("playback"); }
    private long handle;
    private boolean probed, prepared;

    /**
     * 基于输入描述符区间构造底层输入句柄。
     *
     * @param range 输入描述符与字节范围
     * @throws IOException 原生输入分配失败时抛出
     */
    NativeInput(InputRange range) throws IOException {
        handle = create(range.fd.getFd(), range.start, range.length, range.seekable);
        if (handle == 0) throw new IOException("Cannot allocate native input");
    }

    /**
     * 执行流探测并解析文件头部与流元数据（耗时 I/O 操作，运行于独立媒体线程）。
     *
     * @return 状态码，>= 0 表示成功，负数表示底层 FFmpeg 错误码
     */
    int probe() {
        if (handle == 0 || probed) throw new IllegalStateException("Invalid probe state");
        probed = true;
        int status = probe(handle);
        prepared = status >= 0;
        return status;
    }

    /**
     * 获取解析完成后的完整媒体与轨道描述信息。
     *
     * @param mediaId 分配的媒体会话编号
     * @return 媒体元数据对象
     */
    MediaInfo describe(long mediaId) {
        if (!prepared || handle == 0) throw new IllegalStateException("No prepared input");
        return describe(handle, mediaId);
    }

    private static native MediaInfo describe(long handle, long mediaId);

    /** 重新激活输入中断信号量，为下一次解码周期做准备。 */
    void rearm() { rearm(handle); }

    /**
     * 在指定音视频轨道上定位至指定时间（毫秒）。
     *
     * @param index 流索引
     * @param ms    目标毫秒时间戳
     * @return 原生定位执行结果状态码
     */
    int seek(int index, long ms) { return seek(handle, index, ms); }

    /**
     * 创建用于异步音轨切换准备的轻量原生探测器。
     *
     * @param index    音轨索引
     * @param position 目标起算时间位置
     * @return 音轨探测器，失败返回 null
     */
    synchronized AudioTrackProbe audioProbe(int index, long position) {
        long probe = handle == 0 ? 0 : AudioTrackProbe.create(handle, index, position);
        return probe == 0 ? null : new AudioTrackProbe(probe);
    }

    /**
     * 进入解复用与音视频解码分发主循环。
     *
     * @param audio    选中的音频流索引序号，-1 表示无音频
     * @param video    选中的视频流索引序号，-1 表示无视频
     * @param targetMs 解码起点时间（毫秒）
     * @param output   渲染输出 epoch 回调接口
     * @return 原生渲染循环退出状态码
     */
    int render(int audio, int video, long targetMs, PlaybackOutput output) {
        return render(handle, audio, video, targetMs, output);
    }
    private static native void rearm(long handle);
    private static native int seek(long handle, int index, long ms);
    private static native int render(long handle, int audio, int video, long targetMs, PlaybackOutput output);
    static native int category(int status, boolean probing);
    static native boolean recoverHardware(int status);

    /** 触发中断标志，唤醒阻塞在阻塞式 I/O 读取中的原生线程。 */
    synchronized void cancel() {
        if (handle != 0) cancel(handle);
    }

    /** 关闭并销毁底层输入句柄，释放关联的 AVIO 与 AVFormatContext 资源。 */
    @Override public synchronized void close() {
        if (handle != 0) {
            destroy(handle);
            handle = 0;
        }
    }

    int playBoth(PcmOutput pcm, android.view.Surface surface) { return playBoth(handle, pcm, surface); }
    private static native int playBoth(long handle, PcmOutput pcm, android.view.Surface surface);
    int playVideo(android.view.Surface surface) { return playVideo(handle, surface); }
    private static native int playVideo(long handle, android.view.Surface surface);
    int playAudio(PcmOutput output) { return playAudio(handle, output); }
    private static native int playAudio(long handle, PcmOutput output);
    private static native long create(int fd, long start, long length, boolean seekable);
    private static native int probe(long handle);
    private static native void cancel(long handle);
    private static native void destroy(long handle);
    static native int liveInputFds();
    static native int waitingSequential();
}
