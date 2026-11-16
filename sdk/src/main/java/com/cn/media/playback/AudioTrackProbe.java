package com.cn.media.playback;

/**
 * 异步音轨切换探测句柄类。
 * <p>
 * 独占持有一个底层预读游标（对于可定位文件源）或对顺序源包历史的引用，
 * 供音轨准备工作线程在后台独立探测新音轨的解码格式（采样率、声道数）与实际起始时间，
 * 避免阻塞当前正在播放的音频输出主线程。
 */
final class AudioTrackProbe implements AutoCloseable {
    private long handle;

    /**
     * 包装原生探测器句柄。
     *
     * @param handle 底层 C 指针
     */
    AudioTrackProbe(long handle) { this.handle = handle; }
    static native long create(long source, int stream, long positionMs);
    private static native int inspect(long handle, long[] result);
    private static native void signal(long handle);
    private static native void destroy(long handle);

    /**
     * 检查并提取新音轨的采样率、声道数及首帧时间戳。
     *
     * @param result 长度至少为 3 的 long 数组，依次接收 [0]=采样率, [1]=声道数, [2]=首帧时间(微秒)
     * @return 原生探测执行状态码，>= 0 表示成功
     */
    int inspect(long[] result) { return inspect(handle, result); }

    /** 唤醒并取消可能阻塞在底层 I/O 中的探测操作。 */
    synchronized void cancel() { if (handle != 0) signal(handle); }

    /** 释放并销毁底层探测器句柄。 */
    @Override public synchronized void close() {
        if (handle != 0) { destroy(handle); handle = 0; }
    }
}
