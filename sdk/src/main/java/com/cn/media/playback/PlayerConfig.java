package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;

import android.os.Handler;
import android.os.Looper;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 播放器全局配置策略类（不可变对象）。
 * <p>
 * 通过 {@link Builder} 链式构建，用于配置视频解码策略、进度回调频率、多轨降级容忍度及回调线程等。
 */
public final class PlayerConfig {
    /**
     * 视频解码策略模式。
     */
    public enum VideoMode {
        /**
         * 自动模式（推荐）。优先尝试硬件解码直出，遇到不受支持的配置或运行时异常时自动平滑回退至软解。
         */
        AUTO,
        /**
         * 强制软件解码模式。全程使用 FFmpeg CPU 软解并输出 RGBA 渲染。
         */
        SOFTWARE_ONLY,
        /**
         * 仅硬件解码模式。仅尝试平台 MediaCodec 硬件解码器，若硬件不支持或异常直接报错，不隐式回退至软解。
         */
        HARDWARE_ONLY
    }

    /** 进度轮询通知间隔时间（毫秒），0 表示禁用周期性进度通知。 */
    public final int progressIntervalMs;
    /** 视频解码模式。 */
    public final @NotNull VideoMode videoMode;
    /** 是否对选中的所有轨道实施严格模式（true 时任一轨失败整片失败，false 时单轨失败允许单轨降级继续播放）。 */
    public final boolean requireAllSelectedTracks;
    /** 是否启用快速关键帧定位模式（Fast Seek，默认 true，Seek 时直接落地关键帧出图，实现毫秒级极速响应）。 */
    public final boolean fastSeek;
    /** 监听回调派发线程调度器。 */
    public final @NotNull Executor callbackExecutor;
    final Set<String> verifiedLegacyCodecs;
    final CodecCalls codecCalls;
    final PcmCalls pcmCalls;
    final AudioSwitchCalls audioSwitchCalls;

    private PlayerConfig(Builder builder) {
        progressIntervalMs = builder.progressIntervalMs;
        videoMode = builder.videoMode;
        requireAllSelectedTracks = builder.requireAllSelectedTracks;
        fastSeek = builder.fastSeek;
        verifiedLegacyCodecs = Collections.unmodifiableSet(new HashSet<>(builder.verifiedLegacyCodecs));
        codecCalls = builder.codecCalls;
        pcmCalls = builder.pcmCalls;
        audioSwitchCalls = builder.audioSwitchCalls;
        if (builder.callbackExecutor == null) {
            Handler main = new Handler(Looper.getMainLooper());
            callbackExecutor = command -> main.post(command);
        } else {
            callbackExecutor = builder.callbackExecutor;
        }
    }

    /**
     * 播放器配置构建器。
     */
    public static final class Builder {
        private int progressIntervalMs = 250;
        private VideoMode videoMode = VideoMode.AUTO;
        private boolean requireAllSelectedTracks;
        private boolean fastSeek = true;
        private Executor callbackExecutor;
        private final Set<String> verifiedLegacyCodecs = new HashSet<>();
        CodecCalls codecCalls = new CodecCalls();
        PcmCalls pcmCalls = new PcmCalls();
        AudioSwitchCalls audioSwitchCalls = new AudioSwitchCalls();

        /**
         * 注册在指定固件指纹上实测已验证的硬件解码器组件名称（适用于旧版本系统）。
         *
         * @param fingerprint   系统构建指纹（Build.FINGERPRINT）
         * @param componentName 硬件解码组件名（如 c2.android.avc.decoder）
         * @return 当前构建器实例
         */
        public @NotNull Builder addVerifiedHardwareDecoder(@NotNull String fingerprint, @NotNull String componentName) {
            Objects.requireNonNull(fingerprint);
            Objects.requireNonNull(componentName);
            if (fingerprint.equals(android.os.Build.FINGERPRINT)) verifiedLegacyCodecs.add(componentName);
            return this;
        }

        /**
         * 设置播放进度周期性通知的间隔时间。
         *
         * @param interval 毫秒数。可选 100～1000 毫秒，或者传入 0 彻底关闭周期通知（仅接收状态变迁快照）
         * @return 当前构建器实例
         */
        public @NotNull Builder setProgressIntervalMs(int interval) {
            if (interval != 0 && (interval < 100 || interval > 1000)) {
                throw new IllegalArgumentException("Expected zero or 100 to 1000 ms");
            }
            progressIntervalMs = interval;
            return this;
        }

        /**
         * 设置视频解码模式策略。
         *
         * @param mode 解码模式（AUTO, SOFTWARE_ONLY, HARDWARE_ONLY）
         * @return 当前构建器实例
         */
        public @NotNull Builder setVideoMode(@NotNull VideoMode mode) {
            videoMode = Objects.requireNonNull(mode);
            return this;
        }

        /**
         * 设置多音视频轨道部分失败时的容忍策略。
         *
         * @param strict true 表示只要选中的任意轨道无法播放立即报整会话 ERROR；false 表示允许部分降级继续播放有效轨道
         * @return 当前构建器实例
         */
        public @NotNull Builder setRequireAllSelectedTracks(boolean strict) {
            requireAllSelectedTracks = strict;
            return this;
        }

        /**
         * 设置是否启用快速关键帧定位模式（Fast Seek）。
         * <p>
         * 开启时（默认）使用目标处或之前的可解码关键帧。关闭时从可解码位置预滚至目标时间。
         * 单次请求可以通过 {@link Player#seekTo(long, boolean)} 指定模式。
         * </p>
         *
         * @param enable true 为快速关键帧模式（推荐），false 为精确逐帧模式
         * @return 当前构建器实例
         */
        public @NotNull Builder setFastSeek(boolean enable) {
            this.fastSeek = enable;
            return this;
        }

        /**
         * 设置所有 {@link PlayerListener} 事件回调的执行器。
         * 执行器拒绝提交时，SDK 会保留队列并延迟重试。释放结果交付前，执行器需要保持可用。
         *
         * @param executor 回调调度器，默认在 Android 主线程 Looper 交付
         * @return 当前构建器实例
         */
        public @NotNull Builder setCallbackExecutor(@NotNull Executor executor) {
            callbackExecutor = Objects.requireNonNull(executor);
            return this;
        }

        /**
         * 构建不可变的 {@link PlayerConfig} 配置实例。
         *
         * @return 配置实例
         */
        public @NotNull PlayerConfig build() {
            return new PlayerConfig(this);
        }
    }
}
