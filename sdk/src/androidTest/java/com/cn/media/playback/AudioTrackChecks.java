package com.cn.media.playback;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Public request, selection, PCM consumption and timeline evidence; physical output is measured separately. */
final class AudioTrackChecks implements PlayerListener {
    private final Instrumentation instrumentation;
    private final Bundle report;
    private final Map<Long, CommandResult> results = new HashMap<>();
    private final Set<Integer> consumedTracks = new HashSet<>();
    private boolean duplicate;
    private Player player;
    private Thread feeder;

    AudioTrackChecks(Instrumentation instrumentation, Bundle report) {
        this.instrumentation = instrumentation;
        this.report = report;
    }

    @Override public synchronized void onCommandResult(CommandResult result) {
        if (results.put(result.requestId, result) != null) duplicate = true;
        notifyAll();
    }
    @Override public synchronized void onFirstAudioConsumption(PlayerSnapshot value) {
        if (value.audioTrack != null) consumedTracks.add(value.audioTrack.index);
        notifyAll();
    }

    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }

    private synchronized CommandResult await(long id, CommandResult.Status status) throws Exception {
        long end = SystemClock.elapsedRealtime() + 6500;
        while (!results.containsKey(id) && SystemClock.elapsedRealtime() < end) wait(20);
        CommandResult result = results.get(id);
        check(!duplicate && result != null && result.status == status, "request=" + id + " expected=" + status +
                " actual=" + (result == null ? "timeout" : result.status + "/" +
                (result.error == null ? "" : result.error.stage + "/" + result.error.nativeStatus)));
        return result;
    }
    private CommandResult ok(long id) throws Exception { return await(id, CommandResult.Status.SUCCESS); }
    private synchronized void consumed(int track) throws Exception {
        long end = SystemClock.elapsedRealtime() + 3000;
        while (!consumedTracks.contains(track) && SystemClock.elapsedRealtime() < end) wait(30);
        check(consumedTracks.contains(track), "No consumption for selected track " + track);
    }

    private long open(File file, boolean pipe) throws Exception {
        if (!pipe) return player.open(MediaSource.fromFile(file));
        ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
        feeder = new Thread(() -> {
            try (OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(fds[1]);
                 FileInputStream source = new FileInputStream(file)) {
                byte[] block = new byte[8192];
                for (int n; (n = source.read(block)) != -1;) sink.write(block, 0, n);
            } catch (java.io.IOException readerClosed) { /* Released player closed the input. */ }
        }, "track-fixture");
        feeder.start();
        try (ParcelFileDescriptor read = fds[0]) {
            return player.open(MediaSource.fromDescriptor(read.getFileDescriptor(), 0, -1));
        }
    }

    void run(int rounds) throws Exception {
        for (int round = 0; round < rounds; round++) {
            for (String scenario : new String[]{"file", "pipe", "paused-file", "paused-pipe", "prepare-fault",
                    "rollback", "rollback-fault", "latest", "seek", "late-pipe"}) {
                String key = "track-" + round + "-" + scenario;
                try { report.putString(key, exercise(scenario)); }
                catch (Exception | AssertionError failure) { report.putString(key, "FAIL " + failure); throw failure; }
            }
        }
    }

    private String exercise(String scenario) throws Exception {
        synchronized (this) { results.clear(); consumedTracks.clear(); duplicate = false; }
        boolean pipe = scenario.endsWith("pipe");
        boolean pause = scenario.startsWith("paused");
        boolean late = scenario.equals("late-pipe");
        boolean hold = scenario.equals("latest") || scenario.equals("seek");
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        int[] alternate = {-1};
        PlayerConfig.Builder config = new PlayerConfig.Builder().setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY);
        config.audioSwitchCalls = new AudioSwitchCalls() {
            @Override boolean prepare(int stream, boolean rollback) {
                if (hold && !rollback) {
                    entered.countDown();
                    try { proceed.await(1, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { return false; }
                }
                return !scenario.equals("prepare-fault") && !(rollback && scenario.equals("rollback-fault"));
            }
            @Override boolean firstWrite(int stream) {
                return !(scenario.startsWith("rollback") && stream == alternate[0]);
            }
        };
        player = new Player(instrumentation.getTargetContext(), config.build(), this);
        File sample = new File(instrumentation.getTargetContext().getCacheDir(), late ? "L01-delayed.mkv" : "V05.mkv");
        try {
            PlayerSnapshot opened = ok(open(sample, pipe)).snapshot;
            TrackInfo original = opened.audioTrack, target = null;
            for (TrackInfo t : opened.media.tracks)
                if (t.type == TrackInfo.Type.AUDIO && t != original) target = t;
            check(original != null && target != null && opened.media.seekable != pipe, "Wrong fixture");
            alternate[0] = target.index;
            ok(player.play());
            consumed(original.index);
            if (pause) ok(player.pause());
            PlayerSnapshot initial = player.getSnapshot();
            long begin = SystemClock.elapsedRealtime(), request = player.selectAudioTrack(target);
            if (hold) {
                check(entered.await(1200, TimeUnit.MILLISECONDS), "Preparation did not reach gate");
                check(player.getSnapshot().pendingAudioTrack == target && player.getSnapshot().audioTrack == original,
                        "Pending and active tracks are indistinguishable");
                long next = scenario.equals("seek") ? player.seekTo(750) : player.selectAudioTrack(original);
                await(request, CommandResult.Status.SUPERSEDED);
                proceed.countDown();
                ok(next);
                check(player.getSnapshot().audioTrack == original, "Superseded worker overwrote latest selection");
                return "PASS superseded " + request + " winner " + next;
            }
            boolean failure = late || scenario.contains("fault") || scenario.equals("rollback");
            CommandResult result = await(request, failure ? CommandResult.Status.FAILED : CommandResult.Status.SUCCESS);
            long ms = SystemClock.elapsedRealtime() - begin;
            check(result.snapshot.timelineId == initial.timelineId, "Switch reset video timeline");
            check(result.snapshot.pendingAudioTrack == null, "Pending track still set");
            if (failure) {
                TrackInfo expected = scenario.equals("rollback-fault") ? null : original;
                check(result.snapshot.audioTrack == expected, "Wrong active track after " + scenario);
                if (late) check(result.error.code == PlayerError.Code.INPUT_TIMEOUT && ms < 2400,
                        "Late sequential input was not bounded");
                if (scenario.startsWith("rollback")) check(result.error.stage.equals("trackNewOutput"), "New output error lost");
                return "PASS " + result.error.stage + ", active=" + (expected == null ? -1 : expected.index) + ", ms=" + ms;
            }
            check(result.snapshot.audioTrack == target && result.actualPositionMs != null, "No committed target PCM");
            if (pause) {
                check(result.snapshot.state == PlayerSnapshot.State.PAUSED && !consumedTracks.contains(target.index),
                        "Paused switch consumed audio");
                ok(player.play());
            }
            consumed(target.index);
            check(ok(player.selectAudioTrack(original)).snapshot.audioTrack == original, "Reverse switch failed");
            return "PASS initial=" + initial.positionMs + " actual=" + result.actualPositionMs + " elapsedMs=" + ms;
        } finally {
            proceed.countDown();
            ok(player.release());
            if (feeder != null) { feeder.join(2000); check(!feeder.isAlive(), "Pipe writer leaked"); feeder = null; }
        }
    }
}
