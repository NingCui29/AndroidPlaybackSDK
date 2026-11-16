package com.cn.media.playback;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import java.io.IOException;

/**
 * 平台级 {@link MediaCodec} 底层调用封装器。
 * <p>
 * 将系统 MediaCodec 的创建、配置、启动、入队及释放等关键平台操作解耦封装，
 * 为真机故障注入测试（如硬件崩溃、内存耗尽、权限失效及异常时序模拟）提供可插拔的观测与拦截边界。
 * </p>
 */
class CodecCalls {

    /**
     * 根据组件名称创建底层系统 {@link MediaCodec} 实例。
     *
     * @param name 解码组件名称（例如 {@code "c2.android.avc.decoder"}）
     * @return 创建成功的系统 MediaCodec 实例
     * @throws IOException 当平台找不到指定组件或实例化失败时抛出
     */
    MediaCodec create(String name) throws IOException {
        return MediaCodec.createByCodecName(name);
    }

    /**
     * 配置硬件解码器格式与渲染目标 Surface。
     *
     * @param codec 目标解码器实例
     * @param format 视频媒体格式规范
     * @param surface 绑定的渲染 Surface 目标
     */
    void configure(MediaCodec codec, MediaFormat format, Surface surface) {
        codec.configure(format, surface, null, 0);
    }

    /**
     * 启动硬件解码器工作流。
     *
     * @param codec 目标解码器实例
     */
    void start(MediaCodec codec) {
        codec.start();
    }

    /**
     * 释放硬件解码器系统资源。
     *
     * @param codec 待释放的解码器实例
     */
    void release(MediaCodec codec) {
        codec.release();
    }

    /**
     * 将编码视频数据提交至解码器输入队列。
     *
     * @param codec 目标解码器实例
     * @param index 输入缓冲区索引
     * @param bytes 提交数据的有效字节长度
     * @param pts 帧显示时间戳（微秒）
     * @param flags 缓冲区标记（如关键帧或流结束符）
     */
    void queue(MediaCodec codec, int index, int bytes, long pts, int flags) {
        codec.queueInputBuffer(index, 0, bytes, pts, flags);
    }
}
