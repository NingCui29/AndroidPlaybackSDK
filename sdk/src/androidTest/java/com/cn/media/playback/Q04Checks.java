package com.cn.media.playback;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.view.Surface;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

final class Q04Checks {
    private final Instrumentation test;
    private final Context context;
    private final Bundle results;
    private Q04Trace trace;
    private Surface surface;
    private DisplayActivity activity;
    private PlayerConfig.VideoMode mode;

    Q04Checks(Instrumentation test, Bundle results) {
        this.test = test;
        this.context = test.getTargetContext();
        this.results = results;
    }

    private static void require(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    void run(String suite, int rounds, String policy) throws Exception {
        require(rounds >= 1 && rounds <= 50, "Invalid round count");
        mode = PlayerConfig.VideoMode.valueOf(policy);
        try (Q04Trace capture = new Q04Trace(context)) {
            trace = capture;
            results.putString("trace", trace.dir.getName());
            // Report the raw path immediately. It can be pulled after a crash or cancellation.
            Bundle location = new Bundle();
            location.putString("trace", trace.dir.getName());
            test.sendStatus(1, location);
            try {
                boolean audioOnly = suite.equals("q04A03") || suite.equals("q04Pilot");
                if (!audioOnly) {
                    Intent intent = new Intent(context, DisplayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity = (DisplayActivity)test.startActivitySync(intent);
                    surface = activity.awaitSurface();
                }
                if (suite.equals("q04Release")) {
                    releaseGroups(rounds);
                } else {
                    int cycles;
                    switch (suite) {
                        case "q04Realtime": case "q04Pilot": cycles = 1; break;
                        case "q04V01": cycles = 12; break;
                        case "q04A03": cycles = 900; break;
                        default: throw new AssertionError("Unknown Q04 suite " + suite);
                    }
                    for (int r = 1; r <= rounds; r++) playRound(suite, r, audioOnly, cycles);
                }
                trace.event("suiteFinished", rounds, 0, suite);
            } catch (Throwable t) {
                trace.event("failure", 0, 0, t.toString());
                throw t;
            } finally {
                if (activity != null) test.runOnMainSync(activity::finish);
            }
        }
    }

    private static final class Reply {
        final CommandResult result;
        final long callbackNs;
        Reply(CommandResult result) {
            callbackNs = SystemClock.elapsedRealtimeNanos();
            this.result = result;
        }
    }

    private final class Session implements PlayerListener, AutoCloseable {
        final Map<Long,Reply> replies = new HashMap<>();
        final Player player;
        int ended, audioStarts, videoStarts;
        boolean released, observesDisplay;
        PlayerSnapshot endedAt;
        long endedNs;
        String failure;

        Session() {
            PlayerConfig.Builder options = new PlayerConfig.Builder().setVideoMode(mode);
            options.codecCalls = trace.observeCodecs();
            player = new Player(context, options.build(), this);
            trace.player = player;
        }

        @Override public synchronized void onCommandResult(CommandResult result) {
            if (replies.put(result.requestId, new Reply(result)) != null) failure = "Duplicate command completion";
            notifyAll();
        }
        @Override public synchronized void onFirstAudioConsumption(PlayerSnapshot snapshot) { audioStarts++; notifyAll(); }
        @Override public synchronized void onFirstVideoSubmission(PlayerSnapshot snapshot) { videoStarts++; notifyAll(); }
        @Override public synchronized void onError(PlayerError error, PlayerSnapshot snapshot) {
            try {
                trace.event("playerError", error.code.number, error.nativeStatus,
                        error.scope + "/" + error.stage + "/" + error.backend +
                        " media=" + error.mediaId + " timeline=" + error.timelineId +
                        " request=" + error.requestId + " positionMs=" + snapshot.positionMs +
                        " surface=" + (surface != null && surface.isValid()));
            } catch (java.io.IOException e) { failure = "Cannot record player error " + e.getClass().getSimpleName(); }
            notifyAll();
        }
        @Override public synchronized void onSnapshot(PlayerSnapshot shot) {
            if (shot.state == PlayerSnapshot.State.ENDED && (endedAt == null || shot.timelineId != endedAt.timelineId)) {
                endedAt = shot;
                endedNs = SystemClock.elapsedRealtimeNanos();
                ended++;
            }
            if (shot.state == PlayerSnapshot.State.ERROR) failure = "Unexpected player error " + shot.error.code;
            notifyAll();
        }
        synchronized Reply await(long id, CommandResult.Status status) throws Exception {
            long until = SystemClock.elapsedRealtime() + 7000;
            while (!replies.containsKey(id)) {
                long left = until - SystemClock.elapsedRealtime();
                require(left > 0, "Command timeout id=" + id);
                wait(left);
            }
            Reply reply = replies.remove(id);
            require(reply.result.status == status, "id=" + id + " got " + reply.result.status + ", expected " + status);
            return reply;
        }
        Reply ok(long id) throws Exception { return await(id, CommandResult.Status.SUCCESS); }

        void attachDisplay() throws Exception {
            observesDisplay = true;
            test.runOnMainSync(() -> activity.observeSurface(player));
            surface = activity.awaitSurface();
            ok(player.setVideoSurface(surface));
        }

        synchronized void awaitOutput(boolean video) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 6500;
            while (audioStarts == 0 || (video && videoStarts == 0)) {
                require(failure == null && deadline > SystemClock.elapsedRealtime(), "Missing outputs: " + failure);
                wait(100);
            }
        }

        synchronized void awaitEnd(int count, long duration) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + duration + Math.max(8000, duration / 10);
            while (ended < count) {
                trace.healthy();
                require(failure == null && deadline > SystemClock.elapsedRealtime(), "Unexpected interruption: " + failure);
                wait(500);
            }
        }

        @Override public void close() throws Exception {
            if (observesDisplay) {
                test.runOnMainSync(() -> activity.observeSurface(null));
                observesDisplay = false;
            }
            if (!released) {
                ok(player.release());
                released = true;
            }
            trace.player = null;
        }
    }

    private File sample(String name) throws Exception {
        File file = new File(context.getCacheDir(), name).getCanonicalFile();
        require(file.isFile(), "Missing " + name);
        trace.watchFixture(file);
        return file;
    }

    private void playRound(String group, int round, boolean audioOnly, int cycles) throws Exception {
        long mediaMs = audioOnly ? 8000 : 600000;
        long pcmFrames = mediaMs * 48;
        trace.mark(group, round, 0, "baseline");
        trace.sample("before", true);
        File file = sample(audioOnly ? "A03.flac" : "V01.mp4");
        try (Session s = new Session()) {
            if (!audioOnly) {
                s.attachDisplay();
            }
            s.ok(s.player.open(MediaSource.fromFile(file)));
            long wallStart = SystemClock.elapsedRealtimeNanos();
            for (int i = 1; i <= cycles; i++) {
                trace.mark(group, round, i, "playing");
                trace.event("cycleStart", mediaMs, pcmFrames, file.getName());
                long start = SystemClock.elapsedRealtimeNanos();
                s.ok(s.player.play());
                s.awaitEnd(i, mediaMs);
                PlayerSnapshot end = s.endedAt;
                PlaybackStats stats = end.stats;
                long us = (s.endedNs - start) / 1000;
                trace.event("cycleEnd", mediaMs, us, end.videoBackend);
                trace.event("audioFrames", stats.audioFramesWritten, stats.audioFramesConsumed, "");
                trace.event("videoFrames", stats.videoFramesSubmitted, stats.videoFramesDropped, "");
                trace.event("videoMaxSubmitGapMs", stats.maxSubmissionGapMs, 0, "");
                require(stats.audioFramesWritten == pcmFrames && stats.audioFramesConsumed == pcmFrames,
                        "Incomplete PCM " + stats.audioFramesWritten + "/" + stats.audioFramesConsumed);
                require(audioOnly ? stats.videoFramesSubmitted == 0 : stats.videoFramesSubmitted > 0, "Unexpected video output");
                require(s.audioStarts >= i && (audioOnly || s.videoStarts >= i), "Missing first output on replay " + i);
                trace.mark(group, round, i, "boundary");
            }
            trace.event("roundEnd", mediaMs * cycles, (SystemClock.elapsedRealtimeNanos() - wallStart) / 1000, mode.name());
        }
        trace.mark(group, round, cycles, "idle");
        SystemClock.sleep(5000);
        trace.sample("postIdle", true);
    }

    private void releaseGroups(int rounds) throws Exception {
        File file = sample("V01.mp4");
        trace.mark("warmup", 0, 0, "playing");
        try (Session s = new Session()) {
            s.attachDisplay();
            s.ok(s.player.open(MediaSource.fromFile(file)));
            s.ok(s.player.play());
            s.awaitOutput(true);
        }
        trace.mark("warmup", 0, 0, "settle");
        SystemClock.sleep(5000);
        trace.mark("warmup", 0, 0, "baseline");
        for (int i = 0; i < 10; i++) {
            trace.sample("baseline", i == 9);
            SystemClock.sleep(1000);
        }
        for (boolean stall : new boolean[]{false, true}) {
            for (boolean stop : new boolean[]{true, false}) {
                String group = (stall ? "stalled" : "normal") + (stop ? "Stop" : "Release");
                for (int n = 1; n <= rounds; n++) releaseCase(group, n, file, stall, stop);
            }
        }
    }

    private void releaseCase(String group, int round, File file, boolean stall, boolean stop) throws Exception {
        trace.mark(group, round, 0, "before");
        trace.sample("before", false);
        ParcelFileDescriptor writer = null;
        long openId = 0;
        try (Session s = new Session()) {
            trace.mark(group, round, 0, "active");
            if (stall) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                writer = pipe[1];
                try (ParcelFileDescriptor source = pipe[0]) {
                    trace.pipeTarget = Os.readlink("/proc/self/fd/" + source.getFd());
                    openId = s.player.open(MediaSource.fromDescriptor(source.getFileDescriptor(), 0, -1));
                }
                long until = SystemClock.elapsedRealtime() + 1500;
                while (NativeInput.waitingSequential() == 0 && SystemClock.elapsedRealtime() < until) SystemClock.sleep(2);
                require(NativeInput.waitingSequential() == 1, "Empty pipe never entered cancellable poll");
            } else {
                s.attachDisplay();
                s.ok(s.player.open(MediaSource.fromFile(file)));
                s.ok(s.player.play());
                s.awaitOutput(true);
            }
            trace.sample("active", false);
            if (!stop && s.observesDisplay) {
                test.runOnMainSync(() -> activity.observeSurface(null));
                s.observesDisplay = false;
            }
            long start = SystemClock.elapsedRealtimeNanos();
            Reply reply = s.ok(stop ? s.player.stop() : s.player.release());
            s.released = !stop;
            long durationUs = (reply.callbackNs - start) / 1000;
            PlayerSnapshot.State expected = stop ? PlayerSnapshot.State.IDLE : PlayerSnapshot.State.RELEASED;
            require(reply.result.snapshot.state == expected, "Wrong terminal state");
            trace.event("operationUs", durationUs, reply.result.requestId, expected.name());
            if (stall) s.await(openId, CommandResult.Status.CANCELLED);
            trace.mark(group, round, 0, "completed");
            trace.sample("completed", false);
        } finally {
            if (writer != null) writer.close();
        }
        trace.mark(group, round, 0, "idle");
        SystemClock.sleep(5000);
        trace.sample("postIdle", true);
        trace.pipeTarget = "";
    }
}
