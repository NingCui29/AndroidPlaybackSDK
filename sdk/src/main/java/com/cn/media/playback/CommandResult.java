package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 异步命令终态执行结果类。
 * <p>
 * 契约保证：每一个被播放器接收的有效命令请求序列号（requestId），在生命周期内恰好仅交付一次终止结果。
 */
public final class CommandResult {
    /**
     * 命令终态状态枚举。
     */
    public enum Status {
        /** 命令已成功执行完毕。 */
        SUCCESS,
        /** 命令在执行过程中遭遇错误失败。 */
        FAILED,
        /** 命令因主动调用 stop、release 或组件注销被显式取消。 */
        CANCELLED,
        /** 命令在排队或执行预滚期间被随后到达的更新命令（如连续 Seek）直接取代。 */
        SUPERSEDED
    }

    /** 关联的用户请求命令序列号。 */
    public final long requestId;
    /** 命令终态完成状态。 */
    public final @NotNull Status status;
    /** 命令失败时的具体错误信息（成功时为 null）。 */
    public final @Nullable PlayerError error;
    /** 命令完成瞬间捕获的播放器上下文状态快照。 */
    public final @NotNull PlayerSnapshot snapshot;
    /** 定位（Seek）或换轨完成后的实际媒体时间落点（毫秒），非定位操作时为 null。 */
    public final @Nullable Long actualPositionMs;

    CommandResult(long id, Status status, PlayerError error, PlayerSnapshot snapshot, Long actualPositionMs) {
        this.requestId = id;
        this.status = status;
        this.error = error;
        this.snapshot = snapshot;
        this.actualPositionMs = actualPositionMs;
    }
}
