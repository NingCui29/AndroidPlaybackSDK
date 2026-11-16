package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;

/**
 * 播放器事件与命令结果监听接口。
 * <p>
 * 契约说明：
 * <ul>
 *     <li>回调在同一播放器实例中保证串行有序交付，默认运行在 Android 主线程；</li>
 *     <li>允许在回调方法内安全调用播放器的任何命令（如 {@code play()}、{@code pause()}、{@code release()} 等）；</li>
 *     <li>所有方法均提供默认空实现，宿主仅需覆写关心的事件。</li>
 * </ul>
 */
public interface PlayerListener {
    /**
     * 播放器状态或周期性播放进度快照更新通知。
     *
     * @param snapshot 当前最新的不可变状态快照
     */
    default void onSnapshot(@NotNull PlayerSnapshot snapshot) {}

    /**
     * 用户发起的异步命令（open、play、seekTo、setVideoSurface 等）执行完成结果通知。
     *
     * @param result 包含命令请求序列号、执行状态（SUCCESS/FAILED/CANCELLED/SUPERSEDED）及对应快照的结果对象
     */
    default void onCommandResult(@NotNull CommandResult result) {}

    /**
     * 播放过程中发生错误时的主动通知。
     *
     * @param error    包含错误分类码、所属阶段、发生后端及原生状态的错误对象
     * @param snapshot 发生错误时的播放器上下文快照
     */
    default void onError(@NotNull PlayerError error, @NotNull PlayerSnapshot snapshot) {}

    /**
     * 首次音频采样帧被硬件/平台 AudioTrack 实际消费输出时的关键时间点通知。
     *
     * @param snapshot 首次出声时刻的播放器状态快照
     */
    default void onFirstAudioConsumption(@NotNull PlayerSnapshot snapshot) {}

    /**
     * 首次解码完成的视频帧成功提交给 Surface 进行渲染显示时的关键时间点通知。
     *
     * @param snapshot 首帧提交时刻的播放器状态快照
     */
    default void onFirstVideoSubmission(@NotNull PlayerSnapshot snapshot) {}
}
