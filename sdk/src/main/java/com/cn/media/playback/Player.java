package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import android.content.Context;
import android.os.OperationCanceledException;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.cn.media.playback.CommandResult.Status;
import static com.cn.media.playback.PlayerError.Code;
import static com.cn.media.playback.PlayerError.Scope;
import static com.cn.media.playback.PlayerSnapshot.State;

/**
 * 核心播放器类。
 * <p>
 * 支持单实例复用。对外暴露的所有时间单位均为毫秒；命令请求编号由实例自增维护。
 * 线程模型：
 * <ul>
 *     <li>公开 API 均为非阻塞调用，入队至单线程控制调度器串行执行；</li>
 *     <li>内部 I/O 操作收拢至专用媒体线程；</li>
 *     <li>监听回调默认在 Android 主线程（或配置注入的 Executor）串行有序交付。</li>
 * </ul>
 */
public final class Player {
    private final Context context;
    private final PlayerConfig config;
    private final PlayerListener listener;
    private final CallbackQueue callbacks;
    private volatile Thread controlThread;
    private final CountDownLatch releaseSurfaceBarrier = new CountDownLatch(1);
    private final ScheduledThreadPoolExecutor control = new ScheduledThreadPoolExecutor(1,
            r -> controlThread = new Thread(r, "playback-control"));
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "playback-media"));
    private final ExecutorService audioPreparation = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "playback-track-preparation"));
    private boolean usedTrackWorker;
    private AudioChange change;

    private static final class AudioChange {
        final long id;
        final TrackInfo original, requested;
        TrackInfo preparing;
        AudioTrackProbe probe;
        ScheduledFuture<?> timeout;
        ScheduledFuture<?> commitCheck;
        PcmOutput preparedOutput;
        PlayerError failure;
        boolean installed, restoring;
        AudioChange(long id, TrackInfo original, TrackInfo requested) {
            this.id = id; this.original = original; this.requested = requested;
        }
    }
    private final ArrayDeque<PlayerError> history = new ArrayDeque<>();
    private final ProviderOpener providers = new ProviderOpener();
    private long sequence, releaseId;

    // Accessed by the control queue only, except the volatile published snapshot.
    private PlaybackSession session;
    private PlaybackOutput output;
    private State state = State.IDLE;
    private MediaInfo media;
    private TrackInfo audio, video;
    private Surface surface;
    private long surfaceVersion;
    private boolean intent, muted;
    private float volume = 1;
    private long pendingOpen, pendingTimeline, timeline, revision, position;
    private ScheduledFuture<?> progress, seekDeadline;
    private PlayerError error;
    private List<PlayerError> trackFailures = Collections.emptyList();
    private PlaybackStats stats = PlaybackStats.EMPTY;
    private boolean forceSoftware;
    private boolean surfaceRebuilt;
    private String lastBackend = "";
    private volatile PlayerSnapshot latest;

    /**
     * 构造新的播放器实例。
     *
     * @param context  Android 上下文，内部自动转为 ApplicationContext 避免内存泄漏
     * @param config   播放器全局配置参数（缓冲、视频模式、回调调度器等）
     * @param listener 播放事件与命令完成结果监听器
     */
    public Player(@NotNull Context context, @NotNull PlayerConfig config, @NotNull PlayerListener listener) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.config = Objects.requireNonNull(config);
        this.listener = Objects.requireNonNull(listener);
        callbacks = new CallbackQueue(config.callbackExecutor);
        control.setRemoveOnCancelPolicy(true);
        control.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        latest = snapshot();
    }

    /**
     * 获取当前播放器状态的不可变快照。
     *
     * @return 当前最新状态快照
     */
    public @NotNull PlayerSnapshot getSnapshot() { return latest; }

    /**
     * 获取内存中保留的最近诊断与错误历史记录（最多 128 条，已自动脱敏私有路径与凭据）。
     *
     * @return 不可修改的错误与诊断列表
     */
    public @NotNull List<PlayerError> getDiagnostics() {
        synchronized (history) { return Collections.unmodifiableList(new ArrayList<>(history)); }
    }

    private interface Command { void run(long id); }

    private synchronized long accept(Command command) {
        if (releaseId != 0) throw new IllegalStateException("Player released");
        long id = ++sequence;
        control.execute(() -> command.run(id));
        return id;
    }

    /**
     * 打开媒体源。文件描述符的校验与系统级复制为同步完成，后续探测解析为异步处理。
     *
     * @param source 媒体源对象（文件、URI 或 FD 区间）
     * @return 本次操作的请求序列号，可通过 {@link PlayerListener#onCommandResult(CommandResult)} 追踪结果
     * @throws IOException 当文件描述符复制或基础 I/O 初始化失败时抛出
     */
    public synchronized long open(@NotNull MediaSource source) throws IOException {
        if (releaseId != 0) throw new IllegalStateException("Player released");
        InputRange admitted = Objects.requireNonNull(source).acquireDescriptor();
        return accept(id -> prepare(new PlaybackSession(id, source, admitted)));
    }

    /**
     * 请求开始或恢复播放。
     *
     * @return 本次命令的请求编号
     */
    public long play() { return accept(id -> playbackIntent(id, true)); }

    /**
     * 请求暂停播放。
     *
     * @return 本次命令的请求编号
     */
    public long pause() { return accept(id -> playbackIntent(id, false)); }

    /**
     * 请求定位到指定的媒体时间位置。
     *
     * @param positionMs 目标时间（毫秒），必须为非负数
     * @return 本次命令的请求编号
     */
    public long seekTo(long positionMs) { return accept(id -> seek(id, positionMs)); }

    /**
     * 选择并切换当前播放的音轨。
     *
     * @param track 目标音轨信息
     * @return 本次命令的请求编号
     */
    public long selectAudioTrack(@NotNull TrackInfo track) {
        return accept(id -> {
            if (media == null || track == null || track.type != TrackInfo.Type.AUDIO ||
                    !media.tracks.contains(track)) { reject(id, Code.TRACK_NOT_FOUND, "selectAudio"); return; }
            if (!track.softwareDecodable) { reject(id, Code.TRACK_UNSUPPORTED, "selectAudio"); return; }
            if (state == State.ERROR) { reject(id, Code.INVALID_STATE, "selectAudio"); return; }
            cancelChange(Status.SUPERSEDED);
            if (track == audio) { publish(false); success(id); return; }
            if (output == null && !media.seekable && state != State.ENDED) startRun(position, false, 0);
            change = new AudioChange(id, audio, track);
            prepareTrack(change, track);
        });
    }

    private void prepareTrack(AudioChange item, TrackInfo track) {
        item.preparing = track;
        item.installed = false;
        item.probe = session.audioProbe(track.index, currentPosition());
        if (item.probe == null) { trackFailure(item, Code.RESOURCE_EXHAUSTED, "trackPreparation", 0); return; }
        AudioTrackProbe probe = item.probe;
        boolean restoring = item.restoring;
        item.timeout = control.schedule(() -> {
            if (change == item && item.restoring == restoring)
                trackFailure(item, Code.INPUT_TIMEOUT, restoring ? "trackRollbackTimeout" : "trackSwitchTimeout", 0);
        }, restoring ? 1000 : 2000, TimeUnit.MILLISECONDS);
        publish(false);
        usedTrackWorker = true;
        audioPreparation.execute(() -> {
            long[] detail = new long[3];
            PcmOutput prepared = new PcmOutput(config.pcmCalls);
            prepared.setPaused(true);
            int raw;
            try (AudioTrackProbe ignored = probe) {
                raw = probe.inspect(detail);
                if (raw >= 0 && !config.audioSwitchCalls.prepare(track.index, restoring)) raw = -1;
                if (raw >= 0 && prepared.configure((int)detail[0], (int)detail[1]) <= 0) raw = -1;
            } catch (RuntimeException invalid) { raw = -1; }
            final int status = raw;
            control.execute(() -> {
                if (change != item || item.probe != probe) { prepared.close(); return; }
                item.probe = null;
                if (status < 0) {
                    prepared.close();
                    trackFailure(item, status == -1 ? Code.OUTPUT_LOST : Code.fromNumber(NativeInput.category(status, false)),
                            restoring ? "trackRollbackPrepare" : "trackPrepare", status);
                    return;
                }
                item.preparedOutput = prepared;
                commitAtAudioBoundary(item, track, detail[2] / 1000);
            });
        });
    }

    private void commitAtAudioBoundary(AudioChange item, TrackInfo track, long firstMs) {
        if (change != item || item.preparing != track || item.preparedOutput == null) return;
        // The sequential demuxer can see a later audio track several seconds ahead.
        // Keep the live output until the new PCM is within one output staging window.
        long ahead = firstMs - currentPosition();
        if (ahead > 120) {
            item.commitCheck = control.schedule(() -> commitAtAudioBoundary(item, track, firstMs),
                    Math.min(ahead - 120, 100), TimeUnit.MILLISECONDS);
            return;
        }
        PcmOutput prepared = item.preparedOutput;
        item.preparedOutput = null;
        if (output == null) {
            prepared.close();
            audio = track;
            finishChange(item, firstMs);
        } else if (output.installAudio(track.index, item.id, prepared)) {
            item.installed = true;
            audio = track;
            publish(false);
        } else {
            prepared.close();
            trackFailure(item, Code.INVALID_STATE, "trackStreamEnded", 0);
        }
    }

    private void forgetChangeWork(AudioChange item) {
        if (item.commitCheck != null) { item.commitCheck.cancel(false); item.commitCheck = null; }
        if (item.preparedOutput != null) { item.preparedOutput.close(); item.preparedOutput = null; }
        if (item.probe != null) { item.probe.cancel(); item.probe = null; }
        if (item.timeout != null) { item.timeout.cancel(false); item.timeout = null; }
        if (output != null) output.forgetAudioRequest(item.id);
    }

    private void cancelChange(Status status) {
        if (change == null) return;
        AudioChange item = change;
        forgetChangeWork(item);
        change = null;
        publish(false);
        complete(item.id, status, null);
    }

    private void finishChange(AudioChange item, long actualMs) {
        forgetChangeWork(item);
        change = null;
        publish(false);
        complete(item.id, item.failure == null ? Status.SUCCESS : Status.FAILED, item.failure, actualMs);
    }

    private void trackFailure(AudioChange item, Code code, String stage, int raw) {
        PlayerError issue = problem(item.id, code, Scope.TRACK, stage, raw, item.preparing);
        if (item.failure == null) item.failure = issue;
        if (item.installed && output != null) {
            output.abandonAudio(item.preparing.index);
            audio = null;
        }
        forgetChangeWork(item);
        publish(false);
        report(issue);
        if (item.installed && !item.restoring && item.original != null && output != null) {
            item.restoring = true;
            prepareTrack(item, item.original);
            return;
        }
        finishChange(item, currentPosition());
        if (audio == null && (video == null || config.requireAllSelectedTracks))
            fail(0, video == null ? Code.NO_PLAYABLE_TRACK : code, "trackRollbackFailed", raw);
    }

    /**
     * 绑定或解绑视频渲染输出目标 Surface。
     * <p>
     * 宿主持有 Surface 的生命周期。当传入 null 时解绑当前 Surface，且在确认最后一次正在写入的旧缓冲完全归还后才会完成解绑回调。
     *
     * @param replacement 目标 Surface，为 null 时表示解绑
     * @return 本次命令的请求编号
     */
    public long setVideoSurface(@Nullable Surface replacement) {
        return accept(id -> {
            if (replacement != null && !replacement.isValid()) { reject(id, Code.OUTPUT_LOST, "surface"); return; }
            if (surface != replacement) surfaceVersion++;
            surface = replacement;
            surfaceRebuilt = false;
            if (output != null) output.bind(replacement);
            if (video != null && audio == null && (state == State.PLAYING || state == State.PAUSED)) {
                state = intent && surface != null ? State.PLAYING : State.PAUSED;
            }
            publish(false);
            success(id);
        });
    }

    /**
     * 同步解绑已被系统销毁的 Surface。
     * <p>
     * 宿主应在 {@link android.view.SurfaceHolder.Callback#surfaceDestroyed(android.view.SurfaceHolder)}
     * 返回给 Android 系统之前调用本方法。本方法会同步阻塞等待所有在途硬件或软件帧绘制归还。
     * 对已过时的旧 Surface 销毁通知将保持当前绑定状态不变。
     *
     * @param destroyed 已被销毁的目标 Surface
     */
    public void detachVideoSurface(@NotNull Surface destroyed) {
        Objects.requireNonNull(destroyed);
        if (Thread.currentThread() == controlThread) { detachOnControl(destroyed); return; }
        long began = android.os.SystemClock.elapsedRealtimeNanos();
        CountDownLatch barrier;
        synchronized (this) {
            if (releaseId != 0) barrier = releaseSurfaceBarrier;
            else {
                barrier = new CountDownLatch(1);
                control.execute(() -> {
                    try { detachOnControl(destroyed); }
                    finally { barrier.countDown(); }
                });
            }
        }
        boolean interrupted = false;
        for (;;) {
            try { barrier.await(); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        long elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - began) / 1_000_000;
        if (elapsedMs > 100) android.util.Log.w("PlaybackSurface", "Synchronous detach took " + elapsedMs + " ms");
    }

    private void detachOnControl(Surface destroyed) {
        if (surface != destroyed) return;
        surfaceVersion++;
        surface = null;
        if (output != null) output.bind(null);
        if (audio == null && video != null && state == State.PLAYING) state = State.PAUSED;
        publish(false);
    }

    /**
     * 设置播放音量。
     *
     * @param gain 线性音量系数，取值范围为 [0.0, 1.0]
     * @return 本次命令的请求编号
     */
    public long setVolume(float gain) {
        return accept(id -> {
            if (!(gain >= 0 && gain <= 1)) { reject(id, Code.INVALID_ARGUMENT, "volume"); return; }
            volume = gain;
            if (output != null) output.setGain(muted ? 0 : volume);
            publish(false);
            success(id);
        });
    }

    /**
     * 设置静音状态。
     *
     * @param value true 表示静音，false 表示恢复原音量
     * @return 本次命令的请求编号
     */
    public long setMuted(boolean value) {
        return accept(id -> {
            muted = value;
            if (output != null) output.setGain(muted ? 0 : volume);
            publish(false);
            success(id);
        });
    }

    /**
     * 停止播放并将状态重置为 IDLE。
     * <p>
     * 实例可继续复用调用 {@link #open(MediaSource)}。
     *
     * @return 本次命令的请求编号
     */
    public long stop() { return accept(id -> cleanup(id, false)); }

    /**
     * 永久释放播放器实例及其占用的原生解码器、AudioTrack、线程池与文件描述符资源。
     * <p>
     * 多次重复调用将共享同一个请求编号并幂等完成。调用后不可再次复用本实例。
     *
     * @return 本次释放命令的请求编号
     */
    public synchronized long release() {
        if (releaseId != 0) return releaseId;
        releaseId = ++sequence;
        long id = releaseId;
        control.execute(() -> cleanup(id, true));
        return id;
    }

    private long currentPosition() {
        return output == null ? position : clamp(output.positionMs());
    }

    private long clamp(long ms) {
        long value = Math.max(0, ms);
        return media != null && media.durationMs != null ? Math.min(value, media.durationMs) : value;
    }

    private PlayerSnapshot snapshot() {
        return new PlayerSnapshot(++revision, session == null ? 0 : session.id, timeline, state,
                intent, pendingTimeline != 0, currentPosition(), media, audio, video, volume, muted,
                output == null ? lastBackend : output.backend(), error,
                output == null ? stats : output.statistics(), change == null ? null : change.preparing,
                surfaceVersion, output == null ? 0 : output.firstOnSurface(), trackFailures);
    }

    private void publish(boolean periodic) {
        PlayerSnapshot update = snapshot();
        latest = update;
        callbacks.add(periodic, () -> {
            if (!periodic || (latest.mediaId == update.mediaId && latest.timelineId == update.timelineId &&
                    latest.state == State.PLAYING)) listener.onSnapshot(update);
        });
        if (!periodic) updateTicker();
    }

    private void updateTicker() {
        boolean needed = state == State.PLAYING && config.progressIntervalMs != 0;
        if (!needed && progress != null) { progress.cancel(false); progress = null; }
        if (needed && progress == null) {
            progress = control.scheduleWithFixedDelay(() -> publish(true), config.progressIntervalMs,
                    config.progressIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void complete(long id, Status status, PlayerError failure) {
        complete(id, status, failure, null);
    }

    private void complete(long id, Status status, PlayerError failure, Long actualMs) {
        if (id == 0) return;
        CommandResult result = new CommandResult(id, status, failure, latest, actualMs);
        callbacks.add(false, () -> listener.onCommandResult(result));
    }

    private void success(long id) { complete(id, Status.SUCCESS, null); }

    private PlayerError problem(long request, Code code, Scope scope, String stage, int raw) {
        return problem(request, code, scope, stage, raw, null);
    }

    private PlayerError problem(long request, Code code, Scope scope, String stage, int raw, TrackInfo failedTrack) {
        PlayerError failure = new PlayerError(code, scope, session == null ? 0 : session.id, timeline,
                request, stage, output == null ? lastBackend : output.backend(), raw,
                failedTrack == null ? -1 : failedTrack.index);
        rememberFailure(failure);
        return failure;
    }

    private void rememberFailure(PlayerError failure) {
        if (failure.trackIndex >= 0) {
            ArrayList<PlayerError> current = new ArrayList<>(trackFailures);
            for (int i = current.size() - 1; i >= 0; --i)
                if (current.get(i).trackIndex == failure.trackIndex) current.remove(i);
            current.add(failure);
            trackFailures = Collections.unmodifiableList(current);
        }
        synchronized (history) {
            if (history.size() == 128) history.removeFirst();
            history.addLast(failure);
        }
    }

    private void report(PlayerError failure) {
        PlayerSnapshot at = latest;
        callbacks.add(false, () -> listener.onError(failure, at));
    }

    private void reject(long id, Code code, String stage) {
        PlayerError failure = problem(id, code, Scope.REQUEST, stage, 0);
        report(failure);
        complete(id, Status.FAILED, failure);
    }

    private void endPending(Status terminal) {
        cancelChange(terminal);
        complete(pendingOpen, terminal, null);
        complete(pendingTimeline, terminal, null);
        pendingOpen = pendingTimeline = 0;
        clearSeekDeadline();
    }

    private void clearSeekDeadline() {
        if (seekDeadline != null) { seekDeadline.cancel(false); seekDeadline = null; }
    }

    private void retire() {
        PlaybackSession old = session;
        if (old != null) {
            old.retire();
            io.execute(old::close);
        }
        session = null;
        output = null;
    }

    private void prepare(PlaybackSession next) {
        trackFailures = Collections.emptyList();
        endPending(Status.SUPERSEDED);
        retire();
        session = next;
        media = null;
        audio = video = null;
        position = 0;
        timeline = 0;
        forceSoftware = false;
        surfaceRebuilt = false;
        lastBackend = "";
        stats = PlaybackStats.EMPTY;
        intent = false;
        error = null;
        state = State.PREPARING;
        pendingOpen = next.id;
        publish(false);
        io.execute(() -> openInput(next));
    }

    private void openInput(PlaybackSession next) {
        ScheduledFuture<?> timeout = control.schedule(() -> {
            if (session == next && pendingOpen == next.id)
                fail(next.id, Code.INPUT_TIMEOUT, "open", 0);
        }, 5, TimeUnit.SECONDS);
        try {
            NativeInput acquired;
            try { acquired = next.acquire(context.getContentResolver(), providers); }
            finally { timeout.cancel(false); }
            if (acquired == null) return;
            int raw = acquired.probe();
            if (raw < 0) {
                Code code = Code.fromNumber(NativeInput.category(raw, true));
                control.execute(() -> openFailed(next, code, "probe", raw));
                return;
            }
            MediaInfo info = acquired.describe(next.id);
            control.execute(() -> prepared(next, info));
        } catch (SecurityException denied) {
            control.execute(() -> openFailed(next, Code.INPUT_PERMISSION, "open", 0));
        } catch (ProviderOpener.Busy exhausted) {
            control.execute(() -> openFailed(next, Code.RESOURCE_EXHAUSTED, "providerOpen", 0));
        } catch (IOException | OperationCanceledException unavailable) {
            control.execute(() -> openFailed(next, Code.INPUT_IO, "open", 0));
        } catch (IllegalArgumentException invalid) {
            control.execute(() -> openFailed(next, Code.INVALID_ARGUMENT, "inputRange", 0));
        }
    }

    private void openFailed(PlaybackSession next, Code code, String stage, int raw) {
        if (session == next && pendingOpen == next.id) fail(next.id, code, stage, raw);
    }

    private static TrackInfo preferred(MediaInfo info, TrackInfo.Type type) {
        TrackInfo selected = null;
        for (TrackInfo track : info.tracks) {
            if (track.type != type || track.coverArt || !track.softwareDecodable) continue;
            if (selected == null || track.defaultTrack) selected = track;
            if (track.defaultTrack) break;
        }
        return selected;
    }

    private void prepared(PlaybackSession next, MediaInfo info) {
        if (session != next || pendingOpen != next.id) return;
        media = info;
        audio = preferred(info, TrackInfo.Type.AUDIO);
        video = preferred(info, TrackInfo.Type.VIDEO);
        boolean strictFailure = false;
        for (TrackInfo track : info.tracks) {
            boolean missing = (track.type == TrackInfo.Type.AUDIO && audio == null) ||
                    (track.type == TrackInfo.Type.VIDEO && !track.coverArt && video == null);
            if (!missing) continue;
            problem(next.id, Code.TRACK_UNSUPPORTED, Scope.TRACK, "trackSelection", 0, track);
            if (config.requireAllSelectedTracks || (track.type == TrackInfo.Type.VIDEO &&
                    config.videoMode == PlayerConfig.VideoMode.HARDWARE_ONLY)) {
                strictFailure = true;
            }
        }
        if (audio == null && video == null) { fail(next.id, Code.NO_PLAYABLE_TRACK, "trackSelection", 0); return; }
        if (strictFailure) { fail(next.id, Code.TRACK_UNSUPPORTED, "trackSelection", 0); return; }
        long id = pendingOpen;
        pendingOpen = 0;
        state = State.READY;
        publish(false);
        if (!trackFailures.isEmpty()) report(trackFailures.get(0));
        success(id);
        if (intent) startRun(0, false, 0);
    }

    private void playbackIntent(long id, boolean playing) {
        if (session == null || state == State.ERROR) { reject(id, Code.INVALID_STATE, "playIntent"); return; }
        if (playing && state == State.ENDED && !media.seekable) {
            reject(id, Code.SEEK_UNAVAILABLE, "replay"); return;
        }
        intent = playing;
        if (state == State.PREPARING || (state == State.ENDED && !playing)) {
            publish(false); success(id); return;
        }
        if (playing && output == null) startRun(0, state == State.ENDED, 0);
        else {
            if (output != null) output.setPlaying(playing);
            state = runningState();
            publish(false);
        }
        success(id);
    }

    private State runningState() {
        return intent && (audio != null || surface != null) ? State.PLAYING : State.PAUSED;
    }

    private void seek(long id, long ms) {
        if (media == null || state == State.ERROR) { reject(id, Code.INVALID_STATE, "seek"); return; }
        if (!media.seekable) { reject(id, Code.SEEK_UNAVAILABLE, "seek"); return; }
        startRun(Math.min(clamp(ms), Long.MAX_VALUE / 1000), true, id);
    }

    private void startRun(long target, boolean seek, long request) {
        startRun(target, seek, request, null);
    }

    private void startRun(long target, boolean seek, long request, HardwareVideo.Fault recoverFrom) {
        cancelChange(Status.SUPERSEDED);
        complete(pendingTimeline, Status.SUPERSEDED, null);
        clearSeekDeadline();
        pendingTimeline = request;
        timeline++;
        position = target;
        PlaybackSession owner = session;
        int a = audio == null ? -1 : audio.index;
        int v = video == null ? -1 : video.index;
        PlaybackOutput run = new PlaybackOutput(observer, a, video, target, seek,
                intent, surface, muted ? 0 : volume, config, forceSoftware);
        run.recoveryFrom(recoverFrom, target * 1000);
        owner.replace(run);
        output = run;
        state = runningState();
        publish(false);
        if (request != 0) {
            seekDeadline = control.schedule(() -> {
                if (output == run && pendingTimeline == request)
                    fail(request, Code.INPUT_TIMEOUT, "preroll", 0);
            }, 3, TimeUnit.SECONDS);
        }
        io.execute(() -> {
            NativeInput input = owner.enter(run);
            if (input == null) { run.close(); return; }
            int raw;
            try {
                raw = seek ? input.seek(v >= 0 ? v : a, target) : 0;
                if (raw >= 0) raw = input.render(a, v, target, run);
            } finally {
                run.close();
            }
            final int status = raw;
            control.execute(() -> finished(owner, run, status));
        });
    }

    private final PlaybackOutput.Observer observer = new PlaybackOutput.Observer() {
        @Override public void audioSwitchResult(PlaybackOutput run, long id, int raw, long actualMs) {
            control.execute(() -> {
                if (output != run || change == null || change.id != id) return;
                if (raw < 0) trackFailure(change, Code.fromNumber(NativeInput.category(raw, false)),
                        change.restoring ? "trackRollbackOutput" : "trackNewOutput", raw);
                else finishChange(change, actualMs);
            });
        }
        @Override public void ready(PlaybackOutput run, long actualMs) {
            control.execute(() -> {
                if (output != run || pendingTimeline == 0) return;
                long id = pendingTimeline;
                pendingTimeline = 0;
                clearSeekDeadline();
                position = actualMs;
                publish(false);
                complete(id, Status.SUCCESS, null, actualMs);
            });
        }
        @Override public void firstAudio(PlaybackOutput run) { first(run, true); }
        @Override public void firstVideo(PlaybackOutput run) { first(run, false); }
        @Override public void surfaceLost(PlaybackOutput run, Surface invalid) {
            control.execute(() -> {
                if (output == run && surface == invalid && !invalid.isValid()) detachOnControl(invalid);
            });
        }
        @Override public void backend(PlaybackOutput run, String name) {
            control.execute(() -> {
                if (output != run) return;
                if ("ffmpeg".equals(name) && config.videoMode == PlayerConfig.VideoMode.AUTO)
                    forceSoftware = true;
                publish(false);
            });
        }
        @Override public void trackLost(PlaybackOutput run, boolean isVideo, int raw) {
            control.execute(() -> {
                if (output != run) return;
                boolean fatal = config.requireAllSelectedTracks ||
                        (isVideo && config.videoMode == PlayerConfig.VideoMode.HARDWARE_ONLY);
                PlayerError lost = problem(pendingTimeline, Code.fromNumber(NativeInput.category(raw, false)),
                        Scope.TRACK, isVideo ? (fatal ? "videoFailed" : "videoDegraded") :
                        (fatal ? "audioFailed" : "audioDegraded"), raw, isVideo ? video : audio);
                if (!fatal) {
                    if (isVideo) video = null;
                    else audio = null;
                    state = runningState();
                }
                publish(false);
                report(lost);
            });
        }
        @Override public void backendFault(PlaybackOutput run, HardwareVideo.Fault failure) {
            control.execute(() -> {
                if (output != run) return;
                Code code = failure.code();
                PlayerError item = new PlayerError(code, Scope.TRACK, session.id, timeline, pendingTimeline,
                        failure.stage, failure.component, failure.platformCode, video == null ? -1 : video.index);
                rememberFailure(item);
                publish(false);
                report(item);
            });
        }
        private void first(PlaybackOutput run, boolean isAudio) {
            control.execute(() -> {
                if (output != run) return;
                publish(false);
                PlayerSnapshot at = latest;
                callbacks.add(false, () -> {
                    if (isAudio) listener.onFirstAudioConsumption(at);
                    else listener.onFirstVideoSubmission(at);
                });
            });
        }
    };

    private void finished(PlaybackSession owner, PlaybackOutput run, int raw) {
        if (session != owner || output != run) return;
        if (change != null) {
            PlayerError ended = problem(change.id, Code.INVALID_STATE, Scope.TRACK, "trackStreamEnded", raw, change.preparing);
            if (change.failure == null) change.failure = ended;
            finishChange(change, currentPosition());
            report(ended);
        }
        long at = currentPosition();
        stats = run.statistics();
        lastBackend = run.backend();
        output = null;
        if (raw < 0) {
            Code category = Code.fromNumber(NativeInput.category(raw, false));
            boolean rebuild = run.surfaceSwitchFailed() && !surfaceRebuilt;
            if (NativeInput.recoverHardware(raw) && media.seekable && !forceSoftware &&
                    (rebuild || config.videoMode == PlayerConfig.VideoMode.AUTO)) {
                if (rebuild) surfaceRebuilt = true;
                else forceSoftware = true;
                long request = pendingTimeline;
                pendingTimeline = 0;
                HardwareVideo.Fault fault = rebuild ? null : run.hardwareFailure();
                long restore = request != 0 ? position : fault != null ? Math.max(0,fault.positionUs / 1000) : at;
                startRun(restore, true, request, fault);
                return;
            }
            position = at;
            fail(pendingTimeline, category, "render", raw);
            return;
        }
        state = State.ENDED;
        intent = false;
        position = media.durationMs != null ? media.durationMs : at;
        long id = pendingTimeline;
        pendingTimeline = 0;
        clearSeekDeadline();
        publish(false);
        success(id);
    }

    private void fail(long request, Code code, String stage, int raw) {
        cancelChange(Status.CANCELLED);
        position = currentPosition();
        error = problem(request, code, Scope.SESSION, stage, raw);
        if (session != null) {
            PlaybackSession failed = session;
            failed.retire();
            io.execute(failed::close);
        }
        output = null;
        state = State.ERROR;
        intent = false;
        long open = pendingOpen, seek = pendingTimeline;
        pendingTimeline = pendingOpen = 0;
        clearSeekDeadline();
        publish(false);
        report(error);
        complete(open, Status.FAILED, error);
        complete(seek, Status.FAILED, error);
        if (request != open && request != seek) complete(request, Status.FAILED, error);
    }

    private void cleanup(long id, boolean release) {
        trackFailures = Collections.emptyList();
        endPending(Status.CANCELLED);
        retire();
        if (release) {
            if (surface != null) surfaceVersion++;
            surface = null;
            releaseSurfaceBarrier.countDown();
        }
        long generation = ++timeline;
        media = null;
        audio = video = null;
        intent = false;
        position = 0;
        stats = PlaybackStats.EMPTY;
        lastBackend = "";
        error = null;
        state = release ? State.RELEASING : State.STOPPING;
        publish(false);
        Runnable fence = () -> io.execute(() -> providers.afterOutstanding(() -> control.execute(() -> {
            if (session == null && timeline == generation) {
                state = release ? State.RELEASED : State.IDLE;
                if (release) {
                    surface = null;
                    synchronized (history) { history.clear(); }
                }
                publish(false);
            }
            success(id);
            if (release) { io.shutdown(); control.shutdown(); }
        })));
        if (usedTrackWorker) audioPreparation.execute(fence);
        else fence.run();
        if (release) audioPreparation.shutdown();
    }
}
