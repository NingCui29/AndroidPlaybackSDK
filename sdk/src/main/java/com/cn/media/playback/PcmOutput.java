package com.cn.media.playback;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import java.nio.ByteBuffer;

/**
 * 音频 PCM 数据输出与 AudioTrack 渲染管理类。
 * <p>
 * 核心职责：
 * <ul>
 *     <li>独占拥有底层 {@link AudioTrack} 实例，维护一个有界的 PCM 直接内存环形缓冲区（Ring Buffer）；</li>
 *     <li>以连续 10ms 的切片分块向平台 AudioTrack 写入交错 16 位 PCM 数据；</li>
 *     <li>基于环形缓冲区水位实施双向背压控制（高水位背压，低水位恢复读取）；</li>
 *     <li>监听 AudioTrack 路由设备切换与播放头推进，遇到停滞时支持有界自动重建恢复；</li>
 *     <li>在媒体自然结束时通过 {@link #drain()} 执行静音填充与排空。</li>
 * </ul>
 */
final class PcmOutput implements AutoCloseable {
    private final PcmCalls calls;
    private AudioTrack track;
    private ByteBuffer ring;
    private int rate, sourceRate, channels, frameBytes, highFrames, chunkFrames;
    private long queued, written, maxWritten, played, headBase, lastHead;
    private long lastHeadAt, lastWriteAt, nextRoutePoll;
    private int routeId, routeType, routeChanges, recoveries;
    private boolean cancelled, paused, invalidOutput;
    private float gain = 1f;

    PcmOutput() { this(new PcmCalls()); }
    PcmOutput(PcmCalls calls) { this.calls = calls; }

    /**
     * 配置音频输出参数并初始化底层 AudioTrack。
     *
     * @param inputRate 输入原始采样率（Hz）
     * @param channels  声道数（1 单声道，2 立体声）
     * @return 实际协商选定的输出采样率，失败返回 -1
     */
    synchronized int configure(int inputRate, int channels) {
        if (cancelled || (channels != 1 && channels != 2)) return -1;
        if (ring != null) return sourceRate == inputRate && this.channels == channels ? rate : -1;
        sourceRate = inputRate;
        this.channels = channels;
        frameBytes = channels * 2;
        int preferred = inputRate == 44100 ? 44100 : 48000;
        for (int choice : new int[]{preferred, preferred == 48000 ? 44100 : 48000}) {
            rate = choice;
            if (!openTrack()) continue;
            chunkFrames = rate / 100;
            highFrames = rate * 120 / 1000;
            ring = ByteBuffer.allocateDirect(highFrames * frameBytes);
            return rate;
        }
        return -1;
    }

    private boolean openTrack() {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) return false;
        int frames = Math.max((minimum + frameBytes - 1) / frameBytes, rate * 80 / 1000);
        try {
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(frames * frameBytes).setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = builder.build();
            track.setVolume(gain);
            if (!paused) {
                try { track.play(); } catch (IllegalStateException ignored) {}
            }
            lastHeadAt = lastWriteAt = SystemClock.elapsedRealtime();
            nextRoutePoll = 0;
            return true;
        } catch (RuntimeException e) {
            releaseTrack();
            return false;
        }
    }

    private void releaseTrack() {
        if (track != null) { track.release(); track = null; }
    }

    /** 冲刷音频输出硬件队列并复位写入与已消费帧计数。 */
    synchronized void flush() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
                if (!paused) track.play();
            } catch (IllegalStateException ignored) {}
        }
        queued = written = maxWritten = played = headBase = lastHead = 0;
        lastHeadAt = lastWriteAt = SystemClock.elapsedRealtime();
        if (ring != null) ring.clear();
        cancelled = false;
        invalidOutput = false;
    }

    /** 立即取消音频输出，暂停并冲刷 AudioTrack 并唤醒所有阻塞等待。 */
    synchronized void cancel() {
        cancelled = true;
        if (track != null) {
            try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { }
        }
        notifyAll();
    }

    /**
     * 设置音频播放/暂停状态。
     *
     * @param pause true 表示暂停，false 表示恢复
     */
    synchronized void setPaused(boolean pause) {
        if (cancelled) return;
        if (paused == pause && track != null && (pause || track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)) return;
        paused = pause;
        if (track != null) {
            try {
                if (pause) track.pause();
                else track.play();
            }
            catch (IllegalStateException broken) { invalidOutput = true; }
        }
        lastHeadAt = lastWriteAt = SystemClock.elapsedRealtime();
        notifyAll();
    }

    /**
     * 设置 AudioTrack 硬件增益音量。
     *
     * @param value 线性音量 [0.0, 1.0]
     */
    synchronized void setGain(float value) {
        gain = value;
        if (track != null && !cancelled) {
            try { track.setVolume(value); } catch (IllegalStateException broken) { invalidOutput = true; }
        }
    }

    private void await(long ms) {
        try { wait(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); cancelled = true; }
    }

    /**
     * 轮询并获取硬件声卡已实际播放消费的 PCM 采样帧总数（处理 32 位回绕），同时更新音频路由信息。
     *
     * @return 实际已消费采样帧数
     */
    synchronized long consumed() {
        if (track == null || cancelled || invalidOutput) return played;
        try {
            long raw = track.getPlaybackHeadPosition() & 0xffffffffL;
            if (raw < lastHead) {
                if (lastHead - raw > 0x80000000L) headBase += 0x100000000L;
                else { invalidOutput = true; return played; }
            }
            lastHead = raw;
            long now = SystemClock.elapsedRealtime();
            long progress = Math.min(written, headBase + raw);
            if (progress > played) { played = progress; lastHeadAt = now; }
            if (!paused && now >= nextRoutePoll) {
                AudioDeviceInfo device = track.getRoutedDevice();
                int id = device == null ? 0 : device.getId();
                if (id != routeId && routeId != 0 && id != 0) routeChanges++;
                routeId = id;
                routeType = device == null ? 0 : device.getType();
                nextRoutePoll = now + 100;
            }
        } catch (IllegalStateException broken) { invalidOutput = true; }
        return played;
    }

    synchronized long submitted() { return maxWritten; }
    synchronized long queuedFrames() { return queued; }
    synchronized int routeId() { return routeId; }
    synchronized int routeType() { return routeType; }
    synchronized int routeChanges() { return routeChanges; }
    synchronized int recoveries() { return recoveries; }

    private boolean recover(String cause) {
        android.util.Log.w("PlaybackPCM", "track=" + routeId + " rebuilds=" + recoveries + " reason=" + cause +
                " frames=" + played + "/" + written + "/" + queued);
        if (recoveries != 0 || cancelled) return false;
        recoveries++;
        releaseTrack();
        headBase = written = played;
        lastHead = 0;
        invalidOutput = false;
        return openTrack();
    }

    private int platformWrite(ByteBuffer buffer, int size) {
        try { return calls.write(track, buffer, size); }
        catch (IllegalStateException broken) { return AudioTrack.ERROR_INVALID_OPERATION; }
    }

    // Submit contiguous 10 ms slices. Ring bytes are retained until the playback head consumes them.
    private boolean pump() {
        consumed();
        long now = SystemClock.elapsedRealtime();
        if (invalidOutput) return recover("invalid head or route");
        if (written > played && now - lastHeadAt >= 1000) return recover("head stalled");
        while (written < queued) {
            int offset = (int)(written % highFrames);
            int frames = (int)Math.min(queued - written, Math.min(chunkFrames, highFrames - offset));
            ByteBuffer slice = ring.duplicate();
            slice.position(offset * frameBytes);
            int bytes = platformWrite(slice, frames * frameBytes);
            if (bytes < 0 || bytes % frameBytes != 0) return recover("write " + bytes);
            if (bytes == 0) return now - lastWriteAt < 500 || recover("write stalled");
            if (written == played) lastHeadAt = now;
            written += bytes / frameBytes;
            maxWritten = Math.max(maxWritten, written);
            lastWriteAt = now;
        }
        return true;
    }

    synchronized int write(ByteBuffer data) {
        if (ring == null || data.remaining() % frameBytes != 0) return -1;
        boolean throttled = false;
        while (data.hasRemaining() && !cancelled) {
            if (paused) { await(0); continue; }
            if (!pump()) return -1;
            long pending = queued - played;
            if (pending == highFrames) throttled = true;
            // A replacement mixer can require a full startup burst. Do not starve
            // it at the low-water mark when it accepted everything we offered.
            if (throttled && pending > highFrames / 2 &&
                    (pending >= highFrames || written < queued || SystemClock.elapsedRealtime() - lastHeadAt < 40)) {
                await(2);
                continue;
            }
            throttled = false;
            int offset = (int)(queued % highFrames);
            int frames = (int)Math.min(highFrames - pending,
                    Math.min(highFrames - offset, data.remaining() / frameBytes));
            ByteBuffer target = ring.duplicate();
            target.position(offset * frameBytes);
            int limit = data.limit();
            data.limit(data.position() + frames * frameBytes);
            target.put(data);
            data.limit(limit);
            if (written == queued) lastWriteAt = SystemClock.elapsedRealtime();
            queued += frames;
        }
        return !cancelled && pump() ? 0 : -1;
    }

    synchronized long drain() {
        if (ring == null) return 0;
        ByteBuffer tail = null;
        int padding = chunkFrames * frameBytes * 8;
        long progress = SystemClock.elapsedRealtime(), previous = consumed();
        while (played < queued && !cancelled) {
            if (paused) { await(0); progress = SystemClock.elapsedRealtime(); continue; }
            if (!pump()) return -1;
            long elapsed = SystemClock.elapsedRealtime();
            if (played != previous) { previous = played; progress = elapsed; }
            // Fill a hardware burst left below its mixer threshold, without increasing the media count.
            if (written == queued && padding > 0 && elapsed - progress >= 80) {
                if (tail == null) tail = ByteBuffer.allocateDirect(chunkFrames * frameBytes);
                tail.clear();
                int n = platformWrite(tail, Math.min(tail.capacity(), padding));
                if (n < 0) { if (!recover("tail write " + n)) return -1; }
                else padding -= n;
            }
            if (elapsed - progress >= 2000) return -1;
            if (played < queued) await(2);
        }
        return cancelled ? -1 : played;
    }

    @Override public synchronized void close() {
        releaseTrack();
        ring = null;
    }
}
