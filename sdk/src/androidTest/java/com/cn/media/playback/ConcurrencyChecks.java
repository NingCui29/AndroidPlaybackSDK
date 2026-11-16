package com.cn.media.playback;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import java.io.File;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Commands go through the public API. Each player has a separate callback ledger. */
final class ConcurrencyChecks {
    private final Instrumentation test;
    private final Context context;
    private final PrintWriter trace;
    private final Bundle result;

    ConcurrencyChecks(Instrumentation test, Bundle result) throws Exception {
        this.test = test;
        this.context = test.getTargetContext();
        this.result = result;
        trace = new PrintWriter(new File(context.getCacheDir(), "q03-events.tsv"), "UTF-8");
        trace.println("wallMs\tinstance\tevent\tid\tmedia\ttimeline\tstate-or-result\tbackend");
    }

    private synchronized void log(String who, String event, long id, PlayerSnapshot s, String value) {
        trace.println(SystemClock.elapsedRealtime() + "\t" + who + "\t" + event + "\t" + id + "\t" +
                s.mediaId + "\t" + s.timelineId + "\t" + value + "\t" + s.videoBackend);
    }

    private final class Host implements PlayerListener {
        final Player player;
        final String name;
        final Map<Long,CommandResult> done = new HashMap<>();
        final Map<Long,String> accepted = new HashMap<>();
        PlayerSnapshot last;
        String violation;

        Host(String name, PlayerConfig.VideoMode mode) {
            this.name = name;
            player = new Player(context, new PlayerConfig.Builder().setVideoMode(mode).setProgressIntervalMs(100).build(), this);
        }
        @Override public synchronized void onSnapshot(PlayerSnapshot s) {
            if (last != null && last.revision >= s.revision) violation = "Reordered snapshot";
            if (last != null && last.state == PlayerSnapshot.State.RELEASED) violation = "Post-release snapshot";
            last = s;
            log(name, "snapshot", 0, s, s.state.name());
            notifyAll();
        }
        @Override public synchronized void onCommandResult(CommandResult r) {
            if (done.put(r.requestId, r) != null) violation = "Duplicate result " + r.requestId;
            if (Looper.myLooper() != Looper.getMainLooper()) violation = "Listener outside main executor";
            log(name, "result", r.requestId, r.snapshot, r.status + (r.error == null ? "" : "/" + r.error.code));
            notifyAll();
        }
        @Override public void onFirstAudioConsumption(PlayerSnapshot s) { first("pcm", s); }
        @Override public void onFirstVideoSubmission(PlayerSnapshot s) { first("picture", s); }
        private synchronized void first(String what, PlayerSnapshot s) {
            if (last == null || last.state == PlayerSnapshot.State.RELEASED ||
                    last.mediaId != s.mediaId || last.timelineId != s.timelineId)
                violation = "Output from stale session " + what;
            log(name, what, 0, s, s.state.name());
        }
        synchronized long add(String command, long id) {
            if (accepted.put(id, command) != null) throw new AssertionError("Duplicate request ID " + id);
            log(name, "request", id, player.getSnapshot(), command);
            return id;
        }
        // Queue the public requests together before the control thread can deliver worker completions.
        // This test-only fence also works with the classes inside the sealed release AAR.
        void enqueueBatch(java.util.concurrent.Callable<Void> commands) throws Exception {
            java.lang.reflect.Field field = Player.class.getDeclaredField("control");
            field.setAccessible(true);
            Future<Void> queued = ((ExecutorService)field.get(player)).submit(commands);
            queued.get(5, TimeUnit.SECONDS);
        }
        synchronized CommandResult await(long id) throws Exception {
            long end = SystemClock.elapsedRealtime() + 7000;
            while (!done.containsKey(id) && violation == null) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) throw new AssertionError(name + " timeout " + accepted.get(id) + "/" + id);
                wait(left);
            }
            if (violation != null) throw new AssertionError(name + " " + violation);
            return done.get(id);
        }
        long open(String file) throws Exception {
            return add("open " + file, player.open(MediaSource.fromFile(new File(context.getCacheDir(), file))));
        }
        void ok(long id) throws Exception {
            CommandResult r = await(id);
            if (r.status != CommandResult.Status.SUCCESS) throw new AssertionError("Failed " + name + "/" +
                    accepted.get(id) + "/" + r.status);
        }
        void release() throws Exception {
            long id = player.release();
            if (!accepted.containsKey(id)) add("release", id);
            ok(id);
            if (player.release() != id) throw new AssertionError("Non-idempotent release");
            test.waitForIdleSync();
            synchronized (this) {
                if (violation != null || last.state != PlayerSnapshot.State.RELEASED ||
                        !accepted.keySet().equals(done.keySet())) throw new AssertionError("Ledger " + name + " " + violation);
            }
        }
    }

    void run(int rounds) throws Exception {
        Intent launch = new Intent(context, DisplayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        DisplayActivity display = (DisplayActivity)test.startActivitySync(launch);
        try {
            Surface surface = display.awaitSurface();
            for (int i = 0; i < rounds; i++) {
                interruptedCommands(i);
                targeted(i, surface);
            }
            for (int seed : new int[]{87319, 55817, 140921}) random(seed, surface);
            result.putString("q03", "PASS targeted rounds=" + rounds + " mixed=3x100");
        } finally { trace.close(); test.runOnMainSync(display::finish); }
    }

    private void interruptedCommands(int round) throws Exception {
        Host h = new Host("interrupted-" + round, PlayerConfig.VideoMode.SOFTWARE_ONLY);
        try {
            long[] opening = new long[3];
            h.enqueueBatch(() -> {
                opening[0] = h.open("A04.wav");
                opening[1] = h.add("play while preparing", h.player.play());
                opening[2] = h.add("pause while preparing", h.player.pause());
                return null;
            });
            h.ok(opening[1]);
            h.ok(opening[2]);
            h.ok(opening[0]);
            PlayerSnapshot paused = h.await(opening[2]).snapshot;
            PlayerSnapshot ready = h.await(opening[0]).snapshot;
            if (paused.state != PlayerSnapshot.State.PREPARING || paused.playWhenReady ||
                    ready.state != PlayerSnapshot.State.READY || ready.playWhenReady ||
                    ready.stats.audioFramesConsumed != 0) throw new AssertionError("Preparing pause lost");
            h.ok(h.add("play", h.player.play()));
            long[] requests = new long[3];
            h.enqueueBatch(() -> {
                requests[0] = h.add("first seek", h.player.seekTo(2700));
                requests[1] = h.add("replace seek", h.player.seekTo(4900));
                requests[2] = h.add("stop during preroll", h.player.stop());
                return null;
            });
            CommandResult first = h.await(requests[0]), second = h.await(requests[1]);
            if (first.status != CommandResult.Status.SUPERSEDED ||
                    second.status != CommandResult.Status.CANCELLED ||
                    !first.snapshot.seeking || !second.snapshot.seeking ||
                    second.snapshot.timelineId <= first.snapshot.timelineId)
                throw new AssertionError("Pending seeks did not settle on their own timelines");
            h.ok(requests[2]);
            PlayerSnapshot idle = h.await(requests[2]).snapshot;
            if (idle.state != PlayerSnapshot.State.IDLE || idle.seeking || idle.media != null)
                throw new AssertionError("Stop retained media or pending seek");
            long reopened = h.open("A04.wav");
            h.ok(reopened);
            if (h.await(reopened).snapshot.mediaId != reopened) throw new AssertionError("Old media after stop");
        } finally { h.release(); }
    }

    private void targeted(int round, Surface surface) throws Exception {
        Host one = new Host("timing-" + round, round%2 == 0 ? PlayerConfig.VideoMode.AUTO : PlayerConfig.VideoMode.SOFTWARE_ONLY);
        Host two = new Host("peer-" + round, PlayerConfig.VideoMode.HARDWARE_ONLY);
        try {
            long old = one.add("late provider", one.player.open(MediaSource.fromUri(FixtureProvider.uri("late","A04.wav"))));
            SystemClock.sleep(40);
            long next = one.open("V05.mkv");
            if (one.await(old).status != CommandResult.Status.SUPERSEDED) throw new AssertionError("Late open not superseded");
            one.ok(next);
            one.ok(one.add("surface", one.player.setVideoSurface(surface)));
            one.ok(one.add("play", one.player.play()));
            TrackInfo second = null;
            for (TrackInfo t : one.player.getSnapshot().media.tracks) {
                if (t.type == TrackInfo.Type.AUDIO && t != one.player.getSnapshot().audioTrack) second = t;
            }
            if (second == null) throw new AssertionError("Missing second audio track");
            one.add("switch", one.player.selectAudioTrack(second));
            one.add("seek", one.player.seekTo(2400));
            one.ok(one.add("pause", one.player.pause()));
            two.ok(two.open("V01.mp4"));
            two.ok(two.add("play audio plus hardware", two.player.play()));
            SystemClock.sleep(180);
            one.player.detachVideoSurface(surface);
            one.add("rebind", one.player.setVideoSurface(surface));
            one.add("seek before release", one.player.seekTo(1100));
            one.release();
            if (two.player.getSnapshot().state != PlayerSnapshot.State.PLAYING ||
                    two.player.getSnapshot().stats.audioFramesConsumed == 0) throw new AssertionError("Peer interrupted");
        } finally { try { one.release(); } finally { two.release(); } }
    }

    private void random(int seed, Surface surface) throws Exception {
        Random rand = new Random(seed);
        Host host = new Host("seed-" + seed, PlayerConfig.VideoMode.AUTO);
        try {
            host.ok(host.open("V05.mkv"));
            for (int i = 0; i < 100; i++) {
                switch (rand.nextInt(9)) {
                    case 0: host.open(rand.nextBoolean() ? "V05.mkv" : "A04.wav"); break;
                    case 1: host.add("play", host.player.play()); break;
                    case 2: host.add("pause", host.player.pause()); break;
                    case 3: host.add("seek", host.player.seekTo(rand.nextInt(7500))); break;
                    case 4: host.add("volume", host.player.setVolume(rand.nextFloat())); break;
                    case 5: host.add("mute", host.player.setMuted(rand.nextBoolean())); break;
                    case 6: host.add("surface", host.player.setVideoSurface(rand.nextBoolean() ? surface : null)); break;
                    case 7: host.add("stop", host.player.stop()); break;
                    default:
                        MediaInfo info = host.player.getSnapshot().media;
                        if (info != null) {
                            TrackInfo t = info.tracks.get(rand.nextInt(info.tracks.size()));
                            host.add("select " + t.index, host.player.selectAudioTrack(t));
                        } else host.open("V05.mkv");
                }
                SystemClock.sleep(rand.nextInt(15));
            }
        } finally { host.release(); }
    }
}
