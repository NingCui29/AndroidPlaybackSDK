package com.cn.media.playback;

import android.media.AudioTrack;
import java.nio.ByteBuffer;

/**
 * AudioTrack PCM 数据写入调用包装类。
 * <p>
 * 提供包级私有拦截点，支持测试套件对平台音频写入注入短写（Short write）、部分写入及异常模拟。
 */
class PcmCalls {
    /**
     * 将 PCM 字节数据以非阻塞模式写入 AudioTrack。
     *
     * @param track 目标 AudioTrack 实例
     * @param data  包含 PCM 音频数据的直接缓冲区
     * @param bytes 待写入字节数
     * @return 实际成功写入的字节数，或写入错误码
     */
    int write(AudioTrack track, ByteBuffer data, int bytes) {
        return track.write(data, bytes, AudioTrack.WRITE_NON_BLOCKING);
    }
}
