package com.cn.media.playback;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises only public SDK commands. Files V01 and V11 are staged by the device runner. */
final class PlayerChecks implements PlayerListener {
    private final Instrumentation instrumentation;
    private final Context context;
    private final Bundle evidence;
    private final Map<Long,CommandResult> results = new HashMap<>();
    private final AtomicInteger received = new AtomicInteger();
    private Player player;
    private String violation;
    private int audioStarts, videoStarts;
    private int endedEvents;
    private PlayerSnapshot last;
    private String firstBackend = "";
    private Map<Long,PlayerError> diagnosticCallbacks;
    private java.util.ArrayList<Long> timelinePositions;

    PlayerChecks(Instrumentation instrumentation, Bundle evidence) {
        this.instrumentation = instrumentation;
        this.context = instrumentation.getTargetContext();
        this.evidence = evidence;
    }

    @Override public synchronized void onCommandResult(CommandResult result) {
        if (results.put(result.requestId, result) != null) violation = "Duplicate result " + result.requestId;
        if (diagnosticCallbacks != null && result.status == CommandResult.Status.FAILED &&
                diagnosticCallbacks.get(result.requestId) != result.error) violation = "Missing prior error callback";
        if (Looper.myLooper() != Looper.getMainLooper()) violation = "Unexpected callback thread";
        notifyAll();
    }
    @Override public synchronized void onError(PlayerError error, PlayerSnapshot snapshot) {
        if (diagnosticCallbacks == null) return;
        if (snapshot.mediaId != error.mediaId || snapshot.timelineId != error.timelineId ||
                diagnosticCallbacks.put(error.requestId, error) != null) violation = "Error identity mismatch";
    }
    @Override public synchronized void onSnapshot(PlayerSnapshot next) {
        if (next.state == PlayerSnapshot.State.ENDED && (last == null || last.state != next.state ||
                last.mediaId != next.mediaId || last.timelineId != next.timelineId)) endedEvents++;
        if (timelinePositions != null && next.state == PlayerSnapshot.State.PLAYING)
            timelinePositions.add(next.positionMs);
        if (last != null && last.revision >= next.revision) violation = "Reordered snapshot";
        last = next;
        notifyAll();
    }
    @Override public synchronized void onFirstAudioConsumption(PlayerSnapshot snapshot) { audioStarts++; notifyAll(); }
    @Override public synchronized void onFirstVideoSubmission(PlayerSnapshot snapshot) {
        firstBackend = snapshot.videoBackend;
        videoStarts++;
        notifyAll();
    }

    private static void check(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }

    private synchronized CommandResult await(long id, CommandResult.Status expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (!results.containsKey(id)) {
            long left = deadline - SystemClock.elapsedRealtime();
            check(left > 0, "Request timed out " + id + " last=" + (last == null ? null : last.state));
            wait(left);
        }
        check(violation == null, violation);
        CommandResult result = results.get(id);
        check(result.status == expected, "Request " + id + " status=" + result.status +
                " error=" + (result.error == null ? null : result.error.code + "/" + result.error.nativeStatus));
        return result;
    }
    private CommandResult ok(long id) throws Exception { return await(id, CommandResult.Status.SUCCESS); }

    private synchronized void awaitState(PlayerSnapshot.State state, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (last == null || last.state != state) {
            long left = end - SystemClock.elapsedRealtime();
            check(left > 0 && (last == null || last.error == null),
                    "Expected " + state + ", got " + (last == null ? null :
                            last.state + " pos=" + last.positionMs + " err=" +
                            (last.error != null ? last.error.code + "/" + last.error.nativeStatus : "none")));
            wait(left);
        }
    }

    private synchronized void awaitOutput(int a, int v) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (audioStarts < a || videoStarts < v) {
            long left = deadline - SystemClock.elapsedRealtime();
            check(left > 0 && (last == null || last.error == null), "No first output: " + audioStarts + "/" + videoStarts);
            wait(left);
        }
    }

    void geometry() throws Exception {
        android.content.Intent intent = new android.content.Intent(context, DisplayActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        DisplayActivity activity = (DisplayActivity)instrumentation.startActivitySync(intent);
        player = new Player(context, new PlayerConfig.Builder()
                .setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY).build(), this);
        try {
            android.view.Surface surface = activity.awaitSurface();
            ok(player.setVideoSurface(surface));
            ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(), "V02.mov"))));
            ok(player.play());
            awaitOutput(0, 1);
            ok(player.pause());
            ok(player.seekTo(1500));
            check("ffmpeg".equals(player.getSnapshot().videoBackend), "Software backend expected");
            if (android.os.Build.VERSION.SDK_INT < 24)
                throw new AssertionError("Surface PixelCopy requires API 24+");
            android.graphics.Rect size = activity.surfaceBounds();
            // PixelCopy samples the actual consumer buffer, including the black bars.
            int w = size.width(), h = size.height();
            android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            try {
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                AtomicInteger status = new AtomicInteger(-1);
                android.view.PixelCopy.request(surface, image, code -> { status.set(code); done.countDown(); },
                        new Handler(Looper.getMainLooper()));
                check(done.await(2, java.util.concurrent.TimeUnit.SECONDS) && status.get() == 0, "PixelCopy " + status);
                int rw = Math.min(w, (int)Math.round(h * 360.0 / 640));
                int rh = Math.min(h, (int)Math.round(w * 640.0 / 360));
                int left = (w-rw)/2, top = (h-rh)/2;
                int red = image.getPixel(left+rw/16, top+rh-rh/32);
                int blue = image.getPixel(left+rw/16, top+rh/32);
                int bar = image.getPixel(left > 0 ? left/2 : w/2, left > 0 ? h/2 : top/2);
                check(left > 0 || top > 0, "Expected at least one letterbox bar for fixture");
                check(android.graphics.Color.red(red) > 180 && android.graphics.Color.blue(red) < 70 &&
                        android.graphics.Color.blue(blue) > 180 && android.graphics.Color.red(blue) < 70,
                        "V02 rotated ruler " + Integer.toHexString(red) + "/" + Integer.toHexString(blue));
                check((bar & 0xffffff) == 0, "V02 bar " + Integer.toHexString(bar));
                evidence.putString("V02_geometry", "surface=" + w + "x" + h + " fitted=" + rw + "x" + rh +
                        " red=" + Integer.toHexString(red) + " blue=" + Integer.toHexString(blue) +
                        " bar=" + Integer.toHexString(bar) + " rotation=" + player.getSnapshot().videoTrack.rotationDegrees);
            } finally { image.recycle(); }
        } finally { ok(player.release()); instrumentation.runOnMainSync(activity::finish); }
    }

    void dynamic(DisplayActivity display, String name) throws Exception {
        player = new Player(context, new PlayerConfig.Builder().setVideoMode(PlayerConfig.VideoMode.HARDWARE_ONLY)
                .setProgressIntervalMs(100).build(), this);
        android.view.Surface surface = display.awaitSurface();
        try {
            ok(player.setVideoSurface(surface));
            ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(),name))));
            if (name.startsWith("V11")) ok(player.seekTo(26000));
            ok(player.play());
            if (name.contains("bad-length")) {
                awaitState(PlayerSnapshot.State.ERROR, 5000);
                boolean accessUnit = false;
                for (PlayerError e : player.getDiagnostics()) if ("hardwareAccessUnit".equals(e.stage)) accessUnit = true;
                check(accessUnit, "Missing malformed access unit diagnostic");
                evidence.putString(name, "hardwareAccessUnit -> ERROR");
                return;
            }
            if (name.startsWith("V11")) {
                stallScheduler();
                awaitState(PlayerSnapshot.State.ENDED, 8500);
                PlaybackStats stats = player.getSnapshot().stats;
                check(stats.videoFramesDropped > 0 && stats.videoFramesSubmitted + stats.videoFramesDropped == 120,
                        "Hardware late frame accounting " + stats.videoFramesSubmitted + "/" + stats.videoFramesDropped);
                evidence.putString(name, "submitted=" + stats.videoFramesSubmitted + " late=" + stats.videoFramesDropped);
                return;
            }
            StringBuilder trace = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                long deadline = SystemClock.elapsedRealtime() + 6000;
                long target = 300 + i*1200L;
                PlayerSnapshot at;
                do {
                    at = player.getSnapshot();
                    check(at.error == null && SystemClock.elapsedRealtime() < deadline, "Missing segment " + i +
                            " pos=" + at.positionMs + " error=" + (at.error == null ? null : at.error.stage));
                    if (at.positionMs < target) SystemClock.sleep(10);
                } while (at.positionMs < target);
                HardwareOutputInfo info = at.stats.hardwareOutput;
                check(info != null, "Missing hardware geometry");
                int w = info.cropRight - info.cropLeft + 1, h = info.cropBottom - info.cropTop + 1;
                int[] widths = {640,320,800}, heights = {360,240,448};
                check(w == widths[i] && h == heights[i], "Segment " + i + " wrong crop " + w + "x" + h);
                android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888);
                int pixel;
                try {
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    AtomicInteger status = new AtomicInteger(-1);
                    android.view.PixelCopy.request(surface,image,code -> {status.set(code); done.countDown();},
                            new Handler(Looper.getMainLooper()));
                    check(done.await(2,java.util.concurrent.TimeUnit.SECONDS) && status.get()==0, "PixelCopy " + status);
                    pixel = image.getPixel(4,4);
                } finally { image.recycle(); }
                int[] rgb = {android.graphics.Color.red(pixel),android.graphics.Color.green(pixel),android.graphics.Color.blue(pixel)};
                check(rgb[i]>180 && rgb[(i+1)%3]<70 && rgb[(i+2)%3]<70, "Wrong segment pixel " + Integer.toHexString(pixel));
                trace.append(" ").append(w).append("x").append(h).append("/").append(Integer.toHexString(pixel));
            }
            awaitState(PlayerSnapshot.State.ENDED,6000);
            PlaybackStats stats = player.getSnapshot().stats;
            HardwareOutputInfo end = stats.hardwareOutput;
            check(end.configurationVersion == 3 && end.outputFormatChanges >= 3 && end.codecRebuilds >= 1 &&
                    stats.videoFramesSubmitted + stats.videoFramesDropped == 90, "Final configuration or frame accounting " +
                    end.configurationVersion + "/" + end.outputFormatChanges + "/" + end.codecRebuilds + "/" +
                    stats.videoFramesSubmitted + "/" + stats.videoFramesDropped);
            evidence.putString(name, end.component + trace + " versions=3 adaptive=" + end.adaptiveChanges +
                    " rebuild=" + end.codecRebuilds + " submitted=" + stats.videoFramesSubmitted +
                    " dropped=" + stats.videoFramesDropped);
        } finally { ok(player.release()); }
    }

    private void stallScheduler() throws ReflectiveOperationException {
        java.lang.reflect.Field epoch = Player.class.getDeclaredField("output");
        epoch.setAccessible(true);
        Object gate = epoch.get(player);
        check(gate != null, "Missing active epoch");
        synchronized (gate) { SystemClock.sleep(550); }
    }

    void lateVideo() throws Exception {
        player = new Player(context, new PlayerConfig.Builder()
                .setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY).build(), this);
        HandlerThread consumer = new HandlerThread("late-video-reader");
        consumer.start();
        Handler handler = new Handler(consumer.getLooper());
        try (ImageReader reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)) {
            Runnable consume = () -> {
                for (;;) {
                    try (Image image = reader.acquireNextImage()) {
                        if (image == null) break;
                        received.incrementAndGet();
                    }
                }
            };
            reader.setOnImageAvailableListener(r -> consume.run(), handler);
            try {
                ok(player.setVideoSurface(reader.getSurface()));
                ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(), "V11.mp4"))));
                ok(player.seekTo(26000));
                ok(player.play());
                // Some Surface producers replace unacquired buffers instead of
                // blocking. Stall the real scheduler at its normal entry lock;
                // keep the independent ImageReader draining throughout the test.
                stallScheduler();
                awaitState(PlayerSnapshot.State.ENDED, 8000);
                PlaybackStats stats = player.getSnapshot().stats;
                long end = SystemClock.elapsedRealtime() + 1000;
                while (received.get() < stats.videoFramesSubmitted && SystemClock.elapsedRealtime() < end)
                    SystemClock.sleep(10);
                check(stats.videoFramesDropped > 0 && stats.videoFramesSubmitted + stats.videoFramesDropped == 120 &&
                        stats.videoFramesSubmitted == received.get(), "V11 late frames " +
                        stats.videoFramesSubmitted + "/" + stats.videoFramesDropped + "/" + received.get());
                check(stats.softwarePeakFrameBytes > 0 && stats.softwarePeakFrameBytes <= 32L*1024*1024,
                        "Workspace budget " + stats.softwarePeakFrameBytes);
                evidence.putString("V11_late", "submitted=" + stats.videoFramesSubmitted + " received=" + received.get() +
                        " dropped=" + stats.videoFramesDropped + " frameBytes=" + stats.softwarePeakFrameBytes +
                        " matrix=" + stats.softwareColorMatrix + " range=" + stats.softwareColorRange);
            } finally {
                handler.post(consume);
                ok(player.release());
                reader.setOnImageAvailableListener(null, null);
                consumer.quitSafely();
                consumer.join();
            }
        }
    }

    void run() throws Exception {
        File root = context.getCacheDir();
        File audioFile = new File(root, "A02-test.m4a");
        try (InputStream source = instrumentation.getContext().getAssets().open("A02.m4a");
             FileOutputStream output = new FileOutputStream(audioFile)) {
            byte[] data = new byte[4096];
            int n;
            while ((n = source.read(data)) >= 0) output.write(data, 0, n);
        }
        player = new Player(context, new PlayerConfig.Builder()
                .setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY).setProgressIntervalMs(100).build(), this);
        HandlerThread thread = new HandlerThread("test-surface");
        thread.start();
        try (ImageReader reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 3)) {
            reader.setOnImageAvailableListener(r -> {
                try (Image image = r.acquireNextImage()) { if (image != null) received.incrementAndGet(); }
            }, new Handler(thread.getLooper()));
            try {
                ok(player.setVideoSurface(reader.getSurface()));
                MediaSource v01 = MediaSource.fromFile(new File(root, "V01.mp4"));
                long open = player.open(v01);
                long start = player.play(), pause = player.pause();
                ok(start);
                ok(pause);
                MediaInfo info = ok(open).snapshot.media;
                check(info != null && info.seekable && info.tracks.size() == 2, "V01 description");
                check(!player.getSnapshot().playWhenReady, "Preparation ignored pause intent");
                ok(player.play());
                awaitOutput(1, 1);
                SystemClock.sleep(450);
                long paused = ok(player.pause()).snapshot.positionMs;
                SystemClock.sleep(3000);
                check(paused > 0 && player.getSnapshot().positionMs == paused, "Paused position moved");
                ok(player.setVolume(0.4f));
                ok(player.setMuted(true));
                CommandResult invalid = await(player.setVolume(Float.NaN), CommandResult.Status.FAILED);
                check(invalid.error.code == PlayerError.Code.INVALID_ARGUMENT && player.getSnapshot().volume == 0.4f,
                        "Invalid gain changed state");
                int submittedBefore = received.get();
                long seekAt = SystemClock.elapsedRealtime();
                CommandResult landed = ok(player.seekTo(12345));
                evidence.putLong("pausedSeekMs", SystemClock.elapsedRealtime() - seekAt);
                check(Math.abs(landed.snapshot.positionMs - 12345) <= 34 && !landed.snapshot.playWhenReady,
                        "Unexpected preview position " + landed.snapshot.positionMs);
                SystemClock.sleep(150);
                check(received.get() == submittedBefore + 1, "Paused preview count=" + (received.get()-submittedBefore));
                ok(player.setMuted(false));
                ok(player.play());
                ok(player.seekTo(598500));
                awaitState(PlayerSnapshot.State.ENDED, 8000);
                ok(player.stop());
                check(player.getSnapshot().state == PlayerSnapshot.State.IDLE, "stop");
                ok(player.setVideoSurface(null));
                try (ParcelFileDescriptor host = ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    open = player.open(MediaSource.fromDescriptor(host.getFileDescriptor(), 0, audioFile.length()));
                }
                info = ok(open).snapshot.media;
                check(info.tracks.size() == 1 && info.tracks.get(0).channels == 1, "Audio description");
                ok(player.play());
                ok(player.seekTo(11200));
                awaitState(PlayerSnapshot.State.ENDED, 5000);
                evidence.putInt("surfaceFramesReceived", received.get());
                evidence.putLong("volumeAfterUnmuteMillis", Math.round(player.getSnapshot().volume * 1000));
                checkBlockedPipe();
            } finally {
                long release = player.release();
                check(release == player.release(), "Release returned a second request");
                ok(release);
                check(player.getSnapshot().state == PlayerSnapshot.State.RELEASED && player.getDiagnostics().isEmpty(),
                        "Release incomplete");
            }
        } finally {
            thread.quitSafely();
            thread.join();
            audioFile.delete();
        }
        evidence.putString("publicApi", "V01/A02 pause, seek, EOS, reuse, cancel, release passed");
    }

    void fullV01() throws Exception {
        HandlerThread thread = new HandlerThread("test-full-surface");
        thread.start();
        player = new Player(context, new PlayerConfig.Builder()
                .setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY).build(), this);
        try (ImageReader reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 3)) {
            reader.setOnImageAvailableListener(r -> {
                try (Image image = r.acquireNextImage()) { if (image != null) received.incrementAndGet(); }
            }, new Handler(thread.getLooper()));
            ok(player.setVideoSurface(reader.getSurface()));
            long began = SystemClock.elapsedRealtime();
            ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(), "V01.mp4"))));
            ok(player.play());
            awaitState(PlayerSnapshot.State.ENDED, 635000);
            PlaybackStats stats = player.getSnapshot().stats;
            evidence.putLong("playbackElapsedMs", SystemClock.elapsedRealtime() - began);
            evidence.putLong("audioFramesWritten", stats.audioFramesWritten);
            evidence.putLong("audioFramesConsumed", stats.audioFramesConsumed);
            evidence.putLong("videoFramesSubmitted", stats.videoFramesSubmitted);
            evidence.putLong("maxSubmissionGapMs", stats.maxSubmissionGapMs);
            evidence.putInt("imagesReceived", received.get());
            check(stats.audioFramesConsumed == 28800000L && stats.audioFramesWritten == 28800000L,
                    "PCM consumption incomplete");
            evidence.putLong("lateDropped", stats.videoFramesDropped);
            check(stats.videoFramesSubmitted + stats.videoFramesDropped == 18000 &&
                    received.get() == stats.videoFramesSubmitted, "Video accounting incomplete");
        } finally {
            ok(player.release());
            thread.quitSafely();
            thread.join();
        }
    }

    void hardware() throws Exception {
        android.content.Intent intent = new android.content.Intent(context, DisplayActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        DisplayActivity activity = (DisplayActivity)instrumentation.startActivitySync(intent);
        player = new Player(context, new PlayerConfig.Builder()
                .setVideoMode(PlayerConfig.VideoMode.HARDWARE_ONLY).build(), this);
        try {
            android.view.Surface surface = activity.awaitSurface();
            ok(player.setVideoSurface(surface));
            for (String name : new String[]{"V01.mp4","V03.mp4","V10.ts","V11.mp4","H02-hevc.ts"}) {
                int before;
                synchronized (this) { before = videoStarts; }
                MediaInfo info = ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(), name)))).snapshot.media;
                ok(player.play());
                awaitOutput(0, before + 1);
                SystemClock.sleep(250);
                String component = player.getSnapshot().videoBackend;
                check(!component.isEmpty() && !component.equals("ffmpeg"), "No hardware component");
                evidence.putString(name, component);
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    android.graphics.Bitmap captured = android.graphics.Bitmap.createBitmap(64, 36,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    AtomicInteger status = new AtomicInteger(-1);
                    android.view.PixelCopy.request(surface, captured, code -> { status.set(code); done.countDown(); },
                            new Handler(Looper.getMainLooper()));
                    check(done.await(2, java.util.concurrent.TimeUnit.SECONDS) &&
                            status.get() == android.view.PixelCopy.SUCCESS, "PixelCopy failed " + status);
                    int maximum = 0, minimum = 255;
                    for (int y = 0; y < 36; ++y) for (int x = 0; x < 64; ++x) {
                        int red = android.graphics.Color.red(captured.getPixel(x, y));
                        maximum = Math.max(maximum, red);
                        minimum = Math.min(minimum, red);
                    }
                    check(maximum - minimum > 32, "Uniform captured hardware frame");
                    captured.recycle();
                }
                ok(player.pause());
                ok(player.seekTo(3200));
                ok(player.setVideoSurface(null));
                instrumentation.runOnMainSync(activity::replaceView);
                surface = activity.awaitSurface();
                ok(player.setVideoSurface(surface));
                ok(player.play());
                ok(player.seekTo(info.durationMs - 700));
                awaitState(PlayerSnapshot.State.ENDED, 7000);
            }
        } finally {
            int i = 0;
            for (PlayerError e : player.getDiagnostics()) evidence.putString("diagnostic" + i++,
                    e.stage + "/" + e.code + "/" + e.backend + "/" + e.nativeStatus);
            ok(player.release());
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    void fault(DisplayActivity display, String stage, PlayerConfig.VideoMode mode, int round) throws Exception {
        java.util.concurrent.atomic.AtomicLong injected = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong audioAtFault = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.CountDownLatch pipeResume = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        PlayerConfig.Builder policy = new PlayerConfig.Builder().setVideoMode(mode).setProgressIntervalMs(100);
        boolean sequential = stage.startsWith("pipe");
        boolean runtime = sequential || stage.equals("runtime");
        boolean expectVideoLoss = stage.equals("pipeNoEntry") && mode == PlayerConfig.VideoMode.AUTO;
        // V01 random access pictures occur every 250 frames (8.333 s).
        // Use a retained GOP, an evicted GOP shortly before the next key, or an evicted GOP far from it.
        long faultPts = stage.equals("pipeNextEntry") ? 7700000L : stage.equals("pipeNoEntry") ? 3000000L :
                sequential ? 1100000L : 9100000L;
        policy.codecCalls = new CodecCalls() {
            private void trip() {
                if (injected.compareAndSet(0, SystemClock.elapsedRealtime()))
                    audioAtFault.set(player.getSnapshot().stats.audioFramesConsumed);
                throw new IllegalStateException("Injected " + stage);
            }
            @Override android.media.MediaCodec create(String name) throws java.io.IOException {
                attempts.incrementAndGet();
                if (stage.equals("create")) trip();
                return super.create(name);
            }
            @Override void configure(android.media.MediaCodec codec, android.media.MediaFormat format,
                                     android.view.Surface surface) {
                if (stage.equals("configure")) trip();
                super.configure(codec, format, surface);
            }
            @Override void start(android.media.MediaCodec codec) {
                if (stage.equals("start")) trip();
                super.start(codec);
            }
            @Override void queue(android.media.MediaCodec codec, int index, int bytes, long pts, int flags) {
                if (runtime && pts >= faultPts) trip();
                super.queue(codec, index, bytes, pts, flags);
            }
        };
        Thread writer = null;
        player = new Player(context, policy.build(), this);
        try {
            ok(player.setVideoSurface(display.awaitSurface()));
            File file = new File(context.getCacheDir(), "V01.mp4");
            long open;
            if (sequential) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                writer = new Thread(() -> {
                    try (InputStream source = new java.io.FileInputStream(file);
                         java.io.OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        byte[] block = new byte[32768];
                        int n;
                        long sent = 0;
                        while ((n = source.read(block)) >= 0) {
                            // The pinned V01's packet at 5.0 s DTS starts at byte 5789476.
                            // Withhold the next keyframe (8.333 s) until degradation is observable.
                            if (stage.equals("pipeNoEntry") && sent >= 5789476) pipeResume.await();
                            sink.write(block, 0, n);
                            sent += n;
                        }
                    } catch (java.io.IOException closedByPlayer) { /* stop closes the read end */ }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }, "test-stream");
                writer.start();
                try (ParcelFileDescriptor host = pipe[0]) {
                    open = player.open(MediaSource.fromDescriptor(host.getFileDescriptor(), 0, -1));
                }
            } else open = player.open(MediaSource.fromFile(file));
            check(ok(open).snapshot.media.seekable != sequential, "Seek capability mismatch");
            ok(player.play());
            long deadline = SystemClock.elapsedRealtime() + (runtime ? faultPts / 1000 : 0) + 9000;
            String key = round + "-" + mode + "-" + stage;
            if (mode == PlayerConfig.VideoMode.HARDWARE_ONLY) {
                awaitState(PlayerSnapshot.State.ERROR, deadline - SystemClock.elapsedRealtime());
                check(player.getSnapshot().error.code == (runtime ? PlayerError.Code.DECODER_RUNTIME :
                        PlayerError.Code.DECODER_INIT), "Hardware failure category");
            } else if (expectVideoLoss) {
                synchronized (this) {
                    while (last == null || last.videoTrack != null || injected.get() == 0 ||
                            last.audioTrack == null || last.stats.audioFramesConsumed <= audioAtFault.get()) {
                        long left = deadline - SystemClock.elapsedRealtime();
                        check(left > 0 && (last == null || last.error == null), "Missing sequential audio continuation");
                        wait(left);
                    }
                    boolean loss = false;
                    for (PlayerError e : last.trackFailures)
                        if ("videoDegraded".equals(e.stage) && e.code == PlayerError.Code.DECODER_RUNTIME &&
                                e.trackIndex >= 0 && e.scope == PlayerError.Scope.TRACK) loss = true;
                    check(loss, "Missing unavailable-video diagnostic");
                    boolean cause = false;
                    for (PlayerError e : player.getDiagnostics())
                        if (e.code == PlayerError.Code.DECODER_RUNTIME && e.scope == PlayerError.Scope.TRACK &&
                                !e.backend.isEmpty() && e.stage.startsWith("hardware") && e.trackIndex >= 0) cause = true;
                    check(cause, "Missing original component failure");
                    evidence.putLong(key+"AudioAdvance", last.stats.audioFramesConsumed - audioAtFault.get());
                }
                long beforeResume = player.getSnapshot().stats.audioFramesConsumed;
                pipeResume.countDown();
                synchronized (this) {
                    long resumedDeadline = SystemClock.elapsedRealtime() + 3000;
                    while (last == null || last.stats.audioFramesConsumed < beforeResume + 4800) {
                        long left = resumedDeadline - SystemClock.elapsedRealtime();
                        check(left > 0 && (last == null || last.error == null), "Audio did not continue after pipe resumed");
                        wait(left);
                    }
                    check(last.videoTrack == null && last.audioTrack != null, "Unexpected tracks after degraded continuation");
                }
            } else {
                synchronized (this) {
                    while (!(injected.get() > 0 && last != null && last.stats.videoFramesSubmitted > 0 &&
                            last.videoBackend.equals("ffmpeg") && last.stats.videoRecovery != null &&
                            last.stats.videoRecovery.firstSoftwareSubmissionNanos > 0)) {
                        long left = deadline - SystemClock.elapsedRealtime();
                        check(left > 0 && (last == null || last.error == null), "No software recovery: " + stage +
                                " injected=" + injected.get() + " attempts=" + attempts.get() +
                                " backend=" + (last == null ? "" : last.videoBackend));
                        wait(left);
                    }
                    VideoRecoveryInfo info = last.stats.videoRecovery;
                    check(info.failureNanos > 0 && info.firstSoftwareSubmissionNanos >= info.failureNanos &&
                            !info.failureStage.isEmpty(), "Missing first software submission or fault metadata");
                    long latencyUs = (info.firstSoftwareSubmissionNanos - info.failureNanos) / 1000;
                    long deltaUs = info.firstSoftwarePtsUs - info.targetUs;
                    evidence.putLong(key+"RecoveryUs", latencyUs);
                    evidence.putLong(key+"PositionErrorUs", deltaUs);
                    if (stage.equals("runtime"))
                        check(latencyUs <= 4000000 && Math.abs(deltaUs) <= 33334, "V01 seekable recovery limit");
                    if (stage.equals("pipeNextEntry"))
                        check(info.firstSoftwarePtsUs >= 8333333 && info.firstSoftwarePtsUs < 16666666,
                                "Sequential recovery missed next random access picture");
                }
                check(!player.getDiagnostics().isEmpty(), "Fault cause missing");
            }
            check(injected.get() != 0 && attempts.get() <= 2 && attempts.get() > 0, "Hardware attempt count");
            evidence.putLong(key + "Ms", SystemClock.elapsedRealtime() - injected.get());
        } finally {
            pipeResume.countDown();
            ok(player.release());
            if (writer != null) { writer.join(1000); check(!writer.isAlive(), "Pipe writer retained"); }
        }
    }

    private void checkBlockedPipe() throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor read = pipe[0]; ParcelFileDescriptor write = pipe[1]) {
            long open = player.open(MediaSource.fromDescriptor(read.getFileDescriptor(), 0, -1));
            SystemClock.sleep(100);
            long began = SystemClock.elapsedRealtime();
            long stop = player.stop();
            await(open, CommandResult.Status.CANCELLED);
            ok(stop);
            long elapsed = SystemClock.elapsedRealtime() - began;
            check(elapsed <= 1000, "Blocked stop took " + elapsed);
            evidence.putLong("blockedStopMs", elapsed);
        }
    }

    static final String[] FORMATS = {"V01.mp4","V02.mov","V03.mp4","V04.mkv","V05.mkv","V06.webm",
            "V07.webm","V08.avi","V09a.wmv","V09b.wmv","V10.ts","V11.mp4",
            "A01.mp3","A02.m4a","A03.flac","A04.wav","A05a.ogg","A05b.ogg","A06.wma"};

    void degrade(DisplayActivity display, String name, boolean strict, int round) throws Exception {
        player = new Player(context, new PlayerConfig.Builder().setRequireAllSelectedTracks(strict)
                .setProgressIntervalMs(100).setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY).build(), this);
        try {
            ok(player.setVideoSurface(display.awaitSurface()));
            MediaInfo info = ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(), name)))).snapshot.media;
            check(info.tracks.size() == 2, "Fixture track count");
            ok(player.play());
            boolean both = name.equals("bad-both.mov");
            if (both || strict) {
                awaitState(PlayerSnapshot.State.ERROR, 5000);
                if (both && !strict) check(player.getSnapshot().error.code == PlayerError.Code.NO_PLAYABLE_TRACK,
                        "Both tracks must report NO_PLAYABLE_TRACK");
            } else {
                boolean sound = name.equals("bad-video.mov");
                awaitOutput(sound ? 1 : 0, sound ? 0 : 1);
                long limit = SystemClock.elapsedRealtime() + 3000;
                PlayerSnapshot next;
                do {
                    next = player.getSnapshot();
                    if ((next.audioTrack != null) == sound && (next.videoTrack != null) != sound &&
                            next.positionMs >= 200) break;
                    check(SystemClock.elapsedRealtime() < limit, "Remaining track or clock did not progress");
                    SystemClock.sleep(20);
                } while (true);
            }
            int losses = 0;
            for (PlayerError e : player.getDiagnostics())
                if (e.stage.equals("audioDegraded") || e.stage.equals("videoDegraded")) losses++;
            check(losses == (strict ? 0 : both ? 2 : 1), "Unexpected track failure count " + losses);
            PlayerSnapshot failed = player.getSnapshot();
            java.util.HashSet<Integer> seen = new java.util.HashSet<>();
            for (PlayerError cause : failed.trackFailures) {
                check(cause.scope == PlayerError.Scope.TRACK && cause.mediaId == failed.mediaId &&
                        cause.nativeStatus != 0 && seen.add(cause.trackIndex), "Missing native track cause");
                boolean matched = false;
                for (TrackInfo track : info.tracks) if (track.index == cause.trackIndex)
                    matched = both || track.type == (name.equals("bad-audio.mov") ? TrackInfo.Type.AUDIO : TrackInfo.Type.VIDEO);
                check(matched, "Fault attributed to the wrong stream");
            }
            check(seen.size() >= (both && !strict ? 2 : 1), "Missing causes from the final snapshot");
            verifyRecovered(new File(context.getCacheDir(), "A02.m4a"));
            check(player.getSnapshot().trackFailures.isEmpty(), "Old track failures leaked across open");
            evidence.putString(round+"-"+name+"-"+strict, "PASS track errors=" + losses + ", reopened audio");
        } finally { ok(player.release()); }
    }

    void pcmWrites(String name) throws Exception {
        boolean wav = name.equals("A04.wav");
        java.io.ByteArrayOutputStream accepted = new java.io.ByteArrayOutputStream();
        AtomicInteger shortWrites = new AtomicInteger(), calls = new AtomicInteger();
        PlayerConfig.Builder policy = new PlayerConfig.Builder();
        policy.pcmCalls = new PcmCalls() {
            @Override synchronized int write(android.media.AudioTrack track, java.nio.ByteBuffer data, int size) {
                int frameBytes = track.getChannelCount()*2;
                int count = calls.incrementAndGet();
                if (count%19 == 0) return 0;
                // Vary the accepted fraction, including a one-frame write. The real AudioTrack
                // still receives the bytes. Collect only the prefix the platform actually accepted.
                int limit = Math.min(size, frameBytes*(count%3==0 ? 1 : 197));
                java.nio.ByteBuffer copy = data.duplicate();
                int actual = super.write(track, data, limit);
                if (actual > 0) {
                    if (actual < size) shortWrites.incrementAndGet();
                    byte[] chunk = new byte[actual];
                    copy.get(chunk);
                    accepted.write(chunk,0,chunk.length);
                }
                return actual;
            }
        };
        player = new Player(context,policy.build(),this);
        PlaybackStats stats;
        try {
            ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(),name))));
            ok(player.play());
            awaitState(PlayerSnapshot.State.ENDED,20000);
            stats = player.getSnapshot().stats;
        } finally { ok(player.release()); }
        int channels = wav ? 2 : 1;
        byte[] raw = accepted.toByteArray();
        int mediaBytes = Math.toIntExact(stats.audioFramesConsumed*channels*2);
        check(shortWrites.get() > 0 && calls.get() >= 19 && stats.audioFramesWritten == stats.audioFramesConsumed &&
                mediaBytes <= raw.length && raw.length % (2*channels) == 0, "Short-write accounting");
        for (int i=mediaBytes;i<raw.length;i++) check(raw[i]==0,"Nonzero AudioTrack drain padding");
        if (wav) {
            check(stats.audioFramesConsumed==384000,"A04 consumption");
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            sha.update(raw,0,mediaBytes);
            StringBuilder hex = new StringBuilder();
            for (byte b:sha.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            check(hex.toString().equals("4c0c474c8a7ebdc3c023d250f82adcfa030f2b5b564ec788ad594e5b2d39f7a7"),
                    "A04 platform PCM differs from source WAV payload");
        } else {
            check(stats.audioFramesConsumed>=576000 && stats.audioFramesConsumed<=577024,"AAC consumption");
            java.nio.ShortBuffer pcm = java.nio.ByteBuffer.wrap(raw,0,mediaBytes)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int[] start = {115680,288480,461280,575040}, frequencies = {440,660,880,1000};
            for (int i=0;i<start.length;i++) {
                double wanted = toneAmplitude(pcm,start[i],frequencies[i]);
                check(wanted>500 && wanted>10*toneAmplitude(pcm,start[i],frequencies[i]+500),
                        "Missing A02 PCM marker at "+start[i]);
            }
        }
        evidence.putString(name,"PASS consumed="+stats.audioFramesConsumed+" partialWrites="+shortWrites.get()+
                " injectedZeroWrites="+calls.get()/19+" paddingBytes="+(raw.length-mediaBytes));
    }

    private static double toneAmplitude(java.nio.ShortBuffer pcm,int start,int hz) {
        double real=0,imag=0;
        for (int i=0;i<960;i++) {
            double phase=2*Math.PI*hz*i/48000.0;
            real+=pcm.get(start+i)*Math.cos(phase);
            imag+=pcm.get(start+i)*Math.sin(phase);
        }
        return Math.hypot(real,imag)/480.0;
    }

    void audioRecovery(int round) throws Exception {
        PlayerConfig.Builder policy = new PlayerConfig.Builder().setProgressIntervalMs(100);
        AtomicInteger writes = new AtomicInteger();
        policy.pcmCalls = new PcmCalls() {
            @Override int write(android.media.AudioTrack track, java.nio.ByteBuffer data, int bytes) {
                if (writes.incrementAndGet() == 30) return -6; // AudioTrack.ERROR_DEAD_OBJECT
                return super.write(track, data, bytes);
            }
        };
        player = new Player(context, policy.build(), this);
        try {
            ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(), "A04.wav"))));
            ok(player.setVolume(0.4f));
            ok(player.play());
            awaitState(PlayerSnapshot.State.ENDED, 11000);
            PlaybackStats stats = player.getSnapshot().stats;
            check(stats.audioOutputRebuilds == 1 && stats.audioFramesWritten == 384000 &&
                    stats.audioFramesConsumed == 384000 && stats.audioRouteId > 0 &&
                    player.getSnapshot().volume == 0.4f, "Rebuilt AudioTrack lost PCM, gain or route: " +
                            stats.audioFramesConsumed + "/" + stats.audioFramesWritten + " route=" + stats.audioRouteId);
            evidence.putString("audio-" + round, "PASS injected write error; rebuilt=" + stats.audioOutputRebuilds +
                    " uniqueConsumed=" + stats.audioFramesConsumed + " deviceId=" + stats.audioRouteId +
                    " deviceType=" + stats.audioRouteType);
        } finally { ok(player.release()); }
    }

    void discontinuity(DisplayActivity display, String name, boolean pipe, PlayerConfig.VideoMode mode)
            throws Exception {
        player = new Player(context, new PlayerConfig.Builder().setVideoMode(mode).setRequireAllSelectedTracks(true)
                .setProgressIntervalMs(100).build(), this);
        Thread writer = null;
        boolean forward = name.contains("forward");
        try {
            ok(player.setVideoSurface(display.awaitSurface()));
            File file = new File(context.getCacheDir(), name);
            long open;
            if (pipe) {
                ParcelFileDescriptor[] pair = ParcelFileDescriptor.createPipe();
                writer = new Thread(() -> {
                    try (InputStream src = new java.io.FileInputStream(file);
                         java.io.OutputStream dst = new ParcelFileDescriptor.AutoCloseOutputStream(pair[1])) {
                        byte[] bytes = new byte[32768];
                        int n;
                        while ((n = src.read(bytes)) >= 0) dst.write(bytes, 0, n);
                    } catch (java.io.IOException closed) { /* release can close the pipe */ }
                });
                writer.start();
                try (ParcelFileDescriptor read = pair[0]) {
                    open = player.open(MediaSource.fromDescriptor(read.getFileDescriptor(), 0, -1));
                }
            } else open = player.open(MediaSource.fromFile(file));
            check(ok(open).snapshot.media.seekable != pipe, "Unexpected TS input seekability");
            if (pipe) check(await(player.seekTo(1000), CommandResult.Status.FAILED).error.code ==
                    PlayerError.Code.SEEK_UNAVAILABLE, "Sequential seek error");
            synchronized (this) { timelinePositions = new java.util.ArrayList<>(); }
            long start = SystemClock.elapsedRealtime();
            ok(player.play());
            awaitState(PlayerSnapshot.State.ENDED, 14000);
            long wall = SystemClock.elapsedRealtime() - start;
            PlaybackStats stats = player.getSnapshot().stats;
            check(stats.audioTimestampJumps == 1 && stats.audioFramesConsumed == 376L * 1024 &&
                    stats.videoFramesSubmitted + stats.videoFramesDropped == 200 &&
                    stats.videoFramesSubmitted >= 190, "Wrong boundary or lost media " + stats.audioTimestampJumps +
                    "/" + stats.audioFramesConsumed + "/" + stats.videoFramesSubmitted + "/" + stats.videoFramesDropped);
            check(wall >= 7200 && wall <= 9400 && stats.maxSubmissionGapMs < 700,
                    "Timestamp jump stretched playback " + wall + "/" + stats.maxSubmissionGapMs);
            boolean jumped = false;
            synchronized (this) {
                for (int i = 1; i < timelinePositions.size(); ++i) {
                    long delta = timelinePositions.get(i) - timelinePositions.get(i-1);
                    if (forward ? delta > 2500 : delta < -1000) jumped = true;
                }
                timelinePositions = null;
            }
            check(jumped, "Public position did not follow PCM jump");
            if (!pipe) {
                long target = forward ? 9000 : 5500;
                CommandResult fast = ok(player.seekTo(target, false));
                check(fast.actualPositionMs != null && fast.actualPositionMs <= target,
                        "Fast TS seek passed target: " + fast.actualPositionMs);
                CommandResult seek = ok(player.seekTo(target, true));
                check(seek.actualPositionMs != null && Math.abs(seek.actualPositionMs - target) <= 40,
                        "TS seek after discontinuity " + seek.actualPositionMs);
            }
            evidence.putString(name + "/" + (pipe ? "pipe" : "file") + "/" + mode,
                    "PASS PCM=" + stats.audioFramesConsumed + " frames=" + stats.videoFramesSubmitted +
                    " dropped=" + stats.videoFramesDropped + " jumps=" + stats.audioTimestampJumps +
                    " elapsedMs=" + wall + " maxGapMs=" + stats.maxSubmissionGapMs);
        } finally {
            ok(player.release());
            if (writer != null) { writer.join(2000); check(!writer.isAlive(), "TS writer still alive"); }
        }
    }

    void timings(DisplayActivity display, String name, String operation, int rounds) throws Exception {
        boolean wantsVideo = name.startsWith("V") || name.endsWith(".wmv");
        player = new Player(context, new PlayerConfig.Builder().setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY)
                .setFastSeek(!name.equals("V09a.wmv")).setProgressIntervalMs(100).build(), this);
        try {
            MediaSource source = MediaSource.fromFile(new File(context.getCacheDir(), name));
            if ("seekTimeout".equals(operation)) {
                PlayerSnapshot opened = ok(player.open(source)).snapshot;
                check(opened.videoTrack != null && opened.audioTrack == null && opened.media.durationMs != null,
                        "Expected a video-only fixture of known duration, such as V11.mp4");
                long target = Math.min(1000, opened.media.durationMs / 2);
                // Without a Surface the video-only output cannot present the first frame.
                CommandResult timeout = await(player.seekTo(target, true), CommandResult.Status.FAILED);
                check(timeout.error.code == PlayerError.Code.INPUT_TIMEOUT && "preroll".equals(timeout.error.stage) &&
                                timeout.error.scope == PlayerError.Scope.REQUEST &&
                                timeout.snapshot.mediaId == opened.mediaId && !timeout.snapshot.seeking &&
                                timeout.snapshot.state == PlayerSnapshot.State.PAUSED && timeout.snapshot.error == null &&
                                timeout.snapshot.positionMs == opened.positionMs && !timeout.snapshot.playWhenReady,
                        "Preroll timeout must preserve the paused session and its position");
                long retry = player.seekTo(target, true);
                ok(player.setVideoSurface(display.awaitSurface()));
                CommandResult restored = ok(retry);
                check(restored.actualPositionMs != null && restored.snapshot.mediaId == opened.mediaId &&
                                restored.snapshot.state == PlayerSnapshot.State.PAUSED,
                        "Binding the Surface must preserve the paused seek request");
                evidence.putString("seekTimeout", "PASS retry remains active through Surface binding after preroll timeout");
                return;
            }
            if (wantsVideo) ok(player.setVideoSurface(display.awaitSurface()));
            if ("seek".equals(operation)) {
                long duration = ok(player.open(source)).snapshot.media.durationMs;
                ok(player.play());
                awaitOutput(wantsVideo ? 0 : 1, wantsVideo ? 1 : 0);
                for (int i = 0; i < rounds; ++i) {
                    boolean forward = (i & 1) == 0;
                    long target = duration * (forward ? 60 + (i/2)%20 : 20 + (i/2)%15) / 100;
                    if (name.equals("V11.mp4") && i == 0) target = 12350;
                    long start = SystemClock.elapsedRealtimeNanos();
                    CommandResult landed = ok(player.seekTo(target, true));
                    check(landed.actualPositionMs != null, "Missing actual seek landing");
                    long firstNs = wantsVideo ? landed.snapshot.stats.firstVideoSubmissionNs :
                            landed.snapshot.stats.firstAudioConsumptionNs;
                    check(firstNs >= start, "Missing output timestamp for seek " + target);
                    long interval = name.equals("3.wmv") ? 40 :
                            name.equals("V11.mp4") && target >= 10000 && target < 20000 ? 67 : 34;
                    long error = landed.actualPositionMs - target;
                    evidence.putString("sample-" + i, (forward ? "forward" : "backward") + "," +
                            (firstNs - start) / 1000000.0 + "," + target + "," + landed.actualPositionMs + "," + error);
                    check(Math.abs(error) <= (wantsVideo ? interval : 20), "Seek error " + error);
                }
                if (name.equals("3.wmv")) {
                    long target = duration * 60 / 100;
                    CommandResult fast = ok(player.seekTo(target, false));
                    check(fast.actualPositionMs != null && fast.actualPositionMs <= target,
                            "Fast WMV seek passed target: " + fast.actualPositionMs);
                    evidence.putString("fast", target + "," + fast.actualPositionMs);
                    for (int i = 0; i < 2; i++) {
                        verifySeekAtEnd(duration);
                        int starts = videoStarts;
                        ok(player.play());
                        awaitOutput(0, starts + 1);
                        ok(player.seekTo(target, true));
                    }
                    evidence.putString("wmvEndpoint", "PASS exact seek to end, replay and seek twice");
                } else if (name.equals("V09a.wmv") || name.equals("V10.ts")) {
                    // First video PTS relative to the audio-led format origin: 43 ms / 21.333 ms.
                    long firstUs = name.equals("V09a.wmv") ? 43000 : 21333;
                    long atOrAfterKey = (firstUs + 999) / 1000;
                    long beforeKey = atOrAfterKey - 1;
                    for (long target : new long[]{0, atOrAfterKey, beforeKey}) {
                        CommandResult first = ok(player.seekTo(target, target == beforeKey));
                        check(Long.valueOf(firstUs / 1000).equals(first.actualPositionMs), "Wrong first frame");
                    }
                    PlayerSnapshot beforeMiss = player.getSnapshot();
                    CommandResult early = await(player.seekTo(beforeKey, false), CommandResult.Status.FAILED);
                    check(early.actualPositionMs == null && early.error.code == PlayerError.Code.SEEK_UNAVAILABLE &&
                                    early.error.scope == PlayerError.Scope.REQUEST && "seekLanding".equals(early.error.stage) &&
                                    early.snapshot.mediaId == beforeMiss.mediaId && !early.snapshot.seeking &&
                                    early.snapshot.state == beforeMiss.state && early.snapshot.error == null &&
                                    early.snapshot.playWhenReady == beforeMiss.playWhenReady,
                            "Missed fast seek must preserve the current session and play intent");
                    ok(player.seekTo(atOrAfterKey, true));
                    evidence.putString("firstKey", "firstUs=" + firstUs + " fastBefore=SEEK_UNAVAILABLE");
                } else if (name.equals("V01.mp4")) {
                    verifySeekAtEnd(duration);
                    evidence.putString("seekAtEnd", "PASS no frame or PCM at the requested end position");
                }
            } else {
                if ("warm".equals(operation)) {
                    long prepare = player.open(source);
                    long start = player.play();
                    ok(prepare);
                    ok(start);
                    awaitOutput(wantsVideo ? 0 : 1, wantsVideo ? 1 : 0);
                    ok(player.stop());
                }
                for (int i = 0; i < rounds; ++i) {
                    int a = audioStarts, v = videoStarts;
                    long start = SystemClock.elapsedRealtimeNanos();
                    long open = player.open(source), play = player.play();
                    ok(open);
                    ok(play);
                    awaitOutput(wantsVideo ? 0 : a+1, wantsVideo ? v+1 : 0);
                    PlaybackStats stats = player.getSnapshot().stats;
                    long firstNs = wantsVideo ? stats.firstVideoSubmissionNs : stats.firstAudioConsumptionNs;
                    check(firstNs >= start, "Missing startup output clock");
                    evidence.putString("sample-" + i, operation + "," + (firstNs - start) / 1000000.0 + ",0,0,0");
                    ok(player.stop());
                }
            }
        } finally { ok(player.release()); }
    }

    private void verifySeekAtEnd(long duration) throws Exception {
        long mediaId = player.getSnapshot().mediaId;
        long start = SystemClock.elapsedRealtime();
        CommandResult end = await(player.seekTo(duration, true), CommandResult.Status.FAILED);
        check(end.actualPositionMs == null && end.error.code == PlayerError.Code.SEEK_UNAVAILABLE &&
                        end.error.scope == PlayerError.Scope.REQUEST && "seekEndOfStream".equals(end.error.stage) &&
                        end.snapshot.state == PlayerSnapshot.State.ENDED && end.snapshot.positionMs == duration &&
                        end.snapshot.mediaId == mediaId && !end.snapshot.seeking && !end.snapshot.playWhenReady &&
                        SystemClock.elapsedRealtime() - start < 2500,
                "Exact seek to the endpoint must reach ENDED before the preroll deadline");
    }

    void surfaceLifecycle(DisplayActivity display, String name, PlayerConfig.VideoMode mode, int rounds, boolean pauseDetached) throws Exception {
        boolean hasAudio = name.equals("V01.mp4");
        double[] detach = new double[rounds], submit = new double[rounds];
        String label = name + "-" + mode + (pauseDetached ? "-resume" : "-bind");
        player = new Player(context, new PlayerConfig.Builder().setVideoMode(mode).setRequireAllSelectedTracks(true)
                .setProgressIntervalMs(100).build(), this);
        try {
            instrumentation.runOnMainSync(() -> display.observeSurface(player));
            ok(player.setVideoSurface(display.awaitSurface()));
            ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(), name))));
            ok(player.play());
            awaitOutput(hasAudio ? 1 : 0, 1);
            for (int i = 0; i < rounds; i++) {
                long oldVersion = player.getSnapshot().surfaceVersion;
                long destroyed = display.destroys();
                instrumentation.runOnMainSync(display::removeVideoView);
                PlayerSnapshot gone = player.getSnapshot();
                check(display.destroys() == destroyed + 1 && gone.surfaceVersion == oldVersion + 1 &&
                        gone.firstSurfaceSubmissionNs == 0, "Missing system destruction barrier");
                detach[i] = display.detachNs() / 1e6;
                long v = gone.stats.videoFramesSubmitted;
                SystemClock.sleep(350);
                PlayerSnapshot during = player.getSnapshot();
                check(during.stats.videoFramesSubmitted == v, "Picture submitted to old Surface");
                if (hasAudio) check(during.positionMs > gone.positionMs && during.stats.audioFramesConsumed >
                        gone.stats.audioFramesConsumed, "Audio did not continue without Surface");
                else check(during.positionMs == gone.positionMs && during.state == PlayerSnapshot.State.PAUSED,
                        "Pure-video timeline moved without Surface");
                if (pauseDetached) ok(player.pause());
                instrumentation.runOnMainSync(display::replaceView);
                android.view.Surface next = display.awaitSurface();
                long start = display.bindNs();
                if (pauseDetached) {
                    ok(player.setVideoSurface(next));
                    SystemClock.sleep(150);
                    check(!player.getSnapshot().playWhenReady && player.getSnapshot().firstSurfaceSubmissionNs == 0,
                            "Replacement resumed a paused player");
                    start = SystemClock.elapsedRealtimeNanos();
                    ok(player.play());
                }
                long deadline = SystemClock.elapsedRealtime() + 4500;
                PlayerSnapshot after;
                for (;;) {
                    after = player.getSnapshot();
                    if (after.surfaceVersion == oldVersion + 2 && after.firstSurfaceSubmissionNs >= start) break;
                    check(after.error == null && SystemClock.elapsedRealtime() < deadline, "New surface output timeout");
                    SystemClock.sleep(10);
                }
                submit[i] = (after.firstSurfaceSubmissionNs - start) / 1e6;
                boolean software = after.videoBackend.equals("ffmpeg");
                check((mode == PlayerConfig.VideoMode.SOFTWARE_ONLY) == software && !after.videoBackend.isEmpty(), "Unexpected backend");
                evidence.putString(label + "-" + i, "detachMs=" + detach[i] + " firstSubmitMs=" + submit[i] +
                        " version=" + after.surfaceVersion + " from=" + (pauseDetached ? "resume" : "bind") +
                        " backend=" + after.videoBackend);
            }
            java.util.Arrays.sort(detach);
            java.util.Arrays.sort(submit);
            int index = (int)Math.ceil(0.95 * rounds) - 1;
            evidence.putString(label + "-summary", "detachP95=" + detach[index] + " submitP95=" + submit[index]);
            check(detach[index] <= 50 && submit[index] <= 500, "Direct bind or detach P95 missed");
        } finally {
            instrumentation.runOnMainSync(() -> display.observeSurface(null));
            ok(player.release());
        }
    }

    void softwareLoop(DisplayActivity display, String name, int round) throws Exception {
        boolean picture = name.startsWith("V"), sound = !name.equals("V11.mp4");
        player = new Player(context, new PlayerConfig.Builder().setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY)
                .setRequireAllSelectedTracks(true).setProgressIntervalMs(100).build(), this);
        MediaSource source = MediaSource.fromFile(new File(context.getCacheDir(), name));
        String key = name + "-" + round;
        try {
            if (picture) ok(player.setVideoSurface(display.awaitSurface()));
            MediaInfo info = ok(player.open(source)).snapshot.media;
            long duration = name.equals("V01.mp4") ? 600000 : name.equals("V11.mp4") ? 30000 : 12000;
            check(info.seekable && info.durationMs != null && Math.abs(duration-info.durationMs) <= 35, "Duration mismatch");
            ok(player.play());
            awaitOutput(sound ? 1 : 0, picture ? 1 : 0);
            PlayerSnapshot pause = ok(player.pause()).snapshot;
            SystemClock.sleep(3000);
            check(pause.positionMs == player.getSnapshot().positionMs, "Pause position changed");
            ok(player.play());
            long[] points = name.equals("V11.mp4") ? new long[]{duration/5, duration*4/5, duration/2, 12350} :
                    new long[]{duration/5, duration*4/5, duration/2};
            for (long point : points) {
                CommandResult landed = ok(player.seekTo(point));
                check(landed.actualPositionMs != null && Math.abs(landed.actualPositionMs - point) <=
                        (picture ? (name.equals("V11.mp4") && point >= 10000 && point < 20000 ? 67 : 34) : 20),
                        "Seek landing " + point + " got " + landed.actualPositionMs);
            }
            // Restart at the origin so the EOS test covers every frame, including the tail.
            ok(player.seekTo(0));
            awaitState(PlayerSnapshot.State.ENDED, duration + 8000);
            PlaybackStats stats = player.getSnapshot().stats;
            long frames = name.equals("V01.mp4") ? 18000 : picture ? 750 : 0;
            check(stats.videoFramesSubmitted + stats.videoFramesDropped == frames, "Incomplete video timeline");
            long pcm = stats.audioFramesConsumed;
            if (name.equals("A02.m4a")) check(pcm >= 576000 && pcm <= 577024, "AAC content length " + pcm);
            else check(pcm == (sound ? 28800000 : 0), "Invalid PCM tail count " + pcm);
            check(stats.audioFramesWritten == pcm && endedEvents == 1, "Drain or terminal count mismatch");
            int a = audioStarts, v = videoStarts;
            ok(player.open(source));
            ok(player.play());
            awaitOutput(sound ? a+1 : 0, picture ? v+1 : 0);
            ok(player.stop());
            evidence.putString(key, "PASS frames=" + stats.videoFramesSubmitted + " dropped=" + stats.videoFramesDropped +
                    " pcm=" + pcm + " endEvents=" + endedEvents + " seekCount=" + (points.length+1));
        } finally { ok(player.release()); }
    }

    void progressAfterStall() throws Exception {
        java.util.concurrent.CountDownLatch sampling = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger burst = new AtomicInteger();
        PlayerListener listener = new PlayerListener() {
            @Override public void onSnapshot(PlayerSnapshot snapshot) {
                if (sampling.getCount() == 0 && observed.getCount() != 0 &&
                        snapshot.state == PlayerSnapshot.State.PLAYING) burst.incrementAndGet();
            }
            @Override public void onCommandResult(CommandResult result) {
                // This suite requests synchronous callbacks to observe every publication.
                synchronized (PlayerChecks.this) {
                    results.put(result.requestId, result);
                    PlayerChecks.this.notifyAll();
                }
            }
        };
        player = new Player(context, new PlayerConfig.Builder().setProgressIntervalMs(100)
                .setCallbackExecutor(Runnable::run).build(), listener);
        try {
            ok(player.open(MediaSource.fromFile(new File(context.getCacheDir(), "A04.wav"))));
            ok(player.play());
            long deadline = SystemClock.elapsedRealtime() + 3000;
            while (player.getSnapshot().positionMs < 300) {
                check(SystemClock.elapsedRealtime() < deadline, "Missing steady progress");
                SystemClock.sleep(10);
            }
            java.lang.reflect.Field field = Player.class.getDeclaredField("control");
            field.setAccessible(true);
            java.util.concurrent.ScheduledThreadPoolExecutor scheduler =
                    (java.util.concurrent.ScheduledThreadPoolExecutor)field.get(player);
            scheduler.execute(() -> {
                // Put the observer between the overdue ticks and the next regularly spaced tick.
                SystemClock.sleep(800);
                sampling.countDown();
                scheduler.schedule(observed::countDown, 25, java.util.concurrent.TimeUnit.MILLISECONDS);
            });
            check(observed.await(3, java.util.concurrent.TimeUnit.SECONDS), "No post-stall observation");
            check(burst.get() == 1, "Post-stall progress publications=" + burst.get());
            evidence.putString("progress", "interval=100ms stall=800ms post-stall publications=" + burst.get());
        } finally { ok(player.release()); }
    }

    void diagnostics() throws Exception {
        String run = java.util.UUID.randomUUID().toString();
        String secretPath = "Vprivate" + run;
        String secretUser = "user" + run;
        String secretPassword = "pw" + run;
        String secretToken = "token" + run;
        String begin = "diagnostics begin " + run, end = "diagnostics end " + run;
        File privateFile = new File(context.getCacheDir(), secretPath);
        android.net.Uri privateUri = FixtureProvider.uri("private", secretPath).buildUpon()
                .appendQueryParameter("credential", "https://" + secretUser + ":" + secretPassword + "@invalid.example")
                .appendQueryParameter("token", secretToken).build();
        diagnosticCallbacks = new HashMap<>();
        android.util.Log.i("PlaybackDiagnosticsCheck", begin);
        player = new Player(context, new PlayerConfig.Builder().build(), this);
        try {
            check(!privateFile.exists(), "Missing-file fixture collision");
            verifyDiagnostic(player.open(MediaSource.fromFile(privateFile)), PlayerError.Code.INPUT_IO, "open", 0);
            verifyDiagnostic(player.open(MediaSource.fromUri(privateUri)), PlayerError.Code.INPUT_PERMISSION, "open", 0);
            try (FileOutputStream out = new FileOutputStream(privateFile)) {
                out.write(new byte[]{'R','I','F','F',36,0,0,0});
            }
            verifyDiagnostic(player.open(MediaSource.fromFile(privateFile)),
                    PlayerError.Code.FORMAT_UNSUPPORTED, "probe", -1094995529);
            long old = player.open(MediaSource.fromUri(FixtureProvider.uri("stall", "A04.wav")));
            long stop = player.stop();
            check(await(old, CommandResult.Status.CANCELLED).error == null, "Cancellation carried an error");
            ok(stop);
            check(player.getDiagnostics().size() == 3, "Cancellation created a failure diagnostic");
            long[] ids = new long[130];
            for (int i = 0; i < ids.length; ++i) {
                ids[i] = player.setVolume(Float.NaN);
                CommandResult rejection = await(ids[i], CommandResult.Status.FAILED);
                check(rejection.error.code == PlayerError.Code.INVALID_ARGUMENT &&
                        rejection.error.scope == PlayerError.Scope.REQUEST, "Volume rejection scope");
            }
            java.util.List<PlayerError> ring = player.getDiagnostics();
            check(ring.size() == 128, "Diagnostic capacity");
            for (int i = 0; i < ring.size(); ++i)
                check(ring.get(i).requestId == ids[i+2], "Diagnostic eviction order");
        } finally {
            try { ok(player.release()); }
            finally { if (privateFile.exists() && !privateFile.delete()) throw new AssertionError("Fixture cleanup"); }
        }
        check(player.getDiagnostics().isEmpty(), "Release retained diagnostic history");
        android.util.Log.i("PlaybackDiagnosticsCheck", end);
        Process logs = new ProcessBuilder("logcat", "-b", "main", "-d", "--pid=" + android.os.Process.myPid(),
                "-v", "raw", "*:V").redirectErrorStream(true).start();
        boolean seenBegin = false, seenEnd = false;
        int lines = 0;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                logs.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.equals(begin)) seenBegin = true;
                if (seenBegin && !seenEnd) {
                    ++lines;
                    check(!line.contains(secretPath) && !line.contains(secretUser) &&
                            !line.contains(secretPassword) && !line.contains(secretToken), "Sensitive value in process log");
                }
                if (line.equals(end) && seenBegin) seenEnd = true;
            }
        } finally { logs.destroy(); }
        check(seenBegin && seenEnd, "Missing process log boundaries");
        evidence.putString("A03", "open/2002, open/2001, probe/3001 raw=-1094995529, CANCELLED; " +
                "128/130 retained in order, release cleared; bounded process log lines=" + lines + " leaks=0");
    }

    private void verifyDiagnostic(long id, PlayerError.Code code, String stage, int raw) throws Exception {
        CommandResult result = await(id, CommandResult.Status.FAILED);
        PlayerError e = result.error;
        check(e != null && e.code == code && e.scope == PlayerError.Scope.SESSION && e.requestId == id &&
                e.mediaId == id && e.timelineId == result.snapshot.timelineId && e.trackIndex == -1 &&
                e.stage.equals(stage) && e.backend.isEmpty() && e.nativeStatus == raw &&
                player.getDiagnostics().contains(e), "Session error metadata");
    }

    void content(int round) throws Exception {
        File root = context.getCacheDir();
        File good = new File(root, "A04.wav");
        File ranged = new File(root, "A04-range.wav");
        try (InputStream input = new java.io.FileInputStream(good);
             FileOutputStream file = new FileOutputStream(ranged)) {
            file.write(new byte[]{-1,-1,-1,-1});
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) >= 0;) file.write(buffer, 0, n);
            file.write(new byte[]{-1,-1,-1,-1});
        }
        File truncated = new File(root, "A04-cut.wav");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(truncated, "rw")) {
            f.setLength(0);
            f.write(new byte[]{'R','I','F','F',36,0,0,0});
        }
        File unknown = new File(root, "A04-unknown.wav");
        // A bounded PCM-shaped RIFF with a deliberately unassigned format tag.
        byte[] wave = java.nio.ByteBuffer.allocate(60).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46464952).putInt(52).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short)0xffff).putShort((short)1).putInt(48000).putInt(96000)
                .putShort((short)2).putShort((short)16).putInt(0x61746164).putInt(16).array();
        try (FileOutputStream f = new FileOutputStream(unknown)) { f.write(wave); }
        player = new Player(context, new PlayerConfig.Builder().build(), this);
        try {
            long duration = ok(player.open(MediaSource.fromFile(good))).snapshot.media.durationMs;
            expectOpenError(player.open(MediaSource.fromUri(FixtureProvider.uri("deny", good.getName()))),
                    PlayerError.Code.INPUT_PERMISSION);
            verifyRecovered(good);
            expectOpenError(player.open(MediaSource.fromUri(FixtureProvider.uri("runtime", good.getName()))),
                    PlayerError.Code.INPUT_IO);
            verifyRecovered(good);
            expectOpenError(player.open(MediaSource.fromFile(truncated)), PlayerError.Code.FORMAT_UNSUPPORTED);
            verifyRecovered(good);
            expectOpenError(player.open(MediaSource.fromFile(unknown)), PlayerError.Code.NO_PLAYABLE_TRACK);
            verifyRecovered(good);
            long before = SystemClock.elapsedRealtime();
            expectOpenError(player.open(MediaSource.fromUri(FixtureProvider.uri("stall", good.getName()))),
                    PlayerError.Code.INPUT_TIMEOUT);
            long timedOut = SystemClock.elapsedRealtime() - before;
            check(timedOut >= 4800 && timedOut <= 6500, "Provider timeout " + timedOut);
            verifyRecovered(good);
            long old = player.open(MediaSource.fromUri(FixtureProvider.uri("late", good.getName())));
            SystemClock.sleep(100);
            long switched = SystemClock.elapsedRealtime();
            long replacement = player.open(MediaSource.fromUri(FixtureProvider.uri("range", ranged.getName())));
            await(old, CommandResult.Status.SUPERSEDED);
            PlayerSnapshot resolved = ok(replacement).snapshot;
            long switchMs = SystemClock.elapsedRealtime() - switched;
            check(switchMs < 1000, "Replacement waited for obsolete provider: " + switchMs);
            check(resolved.mediaId == replacement && resolved.media.seekable &&
                    resolved.media.durationMs == duration, "Range or session mismatch");
            int starts = audioStarts;
            ok(player.play());
            awaitOutput(starts+1, 0);
            ok(player.seekTo(duration-300));
            awaitState(PlayerSnapshot.State.ENDED, 3500);
            checkBlockedPipe();
            evidence.putString("content-"+round, "PASS deny, provider runtime error, cut@8, unknown WAV tag, timeout=" +
                    timedOut + "ms, superseded provider switch=" + switchMs + "ms, sentinel range, blocked FD cancel");
        } finally { ok(player.release()); }
    }

    private void expectOpenError(long id, PlayerError.Code code) throws Exception {
        CommandResult result = await(id, CommandResult.Status.FAILED);
        check(result.error != null && result.error.code == code &&
                result.snapshot.state == PlayerSnapshot.State.ERROR,
                "Expected " + code + " got " + (result.error == null ? null : result.error.code));
    }

    private void verifyRecovered(File good) throws Exception {
        int starts = audioStarts;
        ok(player.open(MediaSource.fromFile(good)));
        ok(player.play());
        awaitOutput(starts+1, 0);
    }

    void format(String name, DisplayActivity display, String kind, PlayerConfig.VideoMode mode) throws Exception {
        boolean hasVideo = name.startsWith("V"), hasAudio = !name.equals("V11.mp4");
        player = new Player(context, new PlayerConfig.Builder().setVideoMode(mode)
                .setRequireAllSelectedTracks(true).setProgressIntervalMs(100).build(), this);
        try {
            if (hasVideo) ok(player.setVideoSurface(display.awaitSurface()));
            File file = new File(context.getCacheDir(), name);
            long open;
            switch (kind) {
                case "fd":
                    try (ParcelFileDescriptor host = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                        open = player.open(MediaSource.fromDescriptor(host.getFileDescriptor(), 0, file.length()));
                    }
                    break;
                case "uri": open = player.open(MediaSource.fromUri(FixtureProvider.uri("file", name))); break;
                case "file": open = player.open(MediaSource.fromFile(file)); break;
                default: throw new AssertionError("Unknown input " + kind);
            }
            PlayerSnapshot ready = ok(open).snapshot;
            check((ready.audioTrack != null) == hasAudio && (ready.videoTrack != null) == hasVideo &&
                    ready.media.seekable && ready.media.durationMs != null, "Track selection or seek capability");
            ok(player.play());
            awaitOutput(hasAudio ? 1 : 0, hasVideo ? 1 : 0);
            String backend = player.getSnapshot().videoBackend;
            ok(player.pause());
            long duration = ready.media.durationMs;
            ok(player.seekTo(duration * 3 / 5));
            if (name.equals("V05.mkv")) {
                TrackInfo other = null;
                for (TrackInfo track : ready.media.tracks)
                    if (track.type == TrackInfo.Type.AUDIO && track != ready.audioTrack) other = track;
                check(other != null && ok(player.selectAudioTrack(other)).snapshot.audioTrack == other,
                        "Audio selection did not switch");
            }
            ok(player.play());
            ok(player.seekTo(duration / 4));
            ok(player.seekTo(Math.max(0, duration - 800)));
            awaitState(PlayerSnapshot.State.ENDED, 7000);
            PlaybackStats stats = player.getSnapshot().stats;
            check((!hasAudio || stats.audioFramesConsumed > 0) && (!hasVideo || stats.videoFramesSubmitted > 0),
                    "No selected output in the final interval");
            evidence.putString(name, "PASS " + kind + " " + mode + " video=" + backend +
                    " finalVideo=" + stats.videoFramesSubmitted + " finalPCM=" + stats.audioFramesConsumed);
        } finally { ok(player.release()); }
    }

    void surfaceBarrier(DisplayActivity display) throws Exception {
        TrackInfo video = new TrackInfo(1, 0, TrackInfo.Type.VIDEO.ordinal(), "h264", "", true,
                false, true, 1920, 1080, 0, 0, 0, new byte[0], 1, 1000, 1, 1, 0);
        PlayerConfig config = new PlayerConfig.Builder()
                .setVideoMode(PlayerConfig.VideoMode.SOFTWARE_ONLY).build();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        try (PlaybackOutput output = new PlaybackOutput(null, -1, video, 0,
                false, -1, false, true, display.awaitSurface(), 1, config, true, null, null)) {
            check(output.acquireVideo(0, 33000) != null, "No frame ownership to retire");
            java.util.concurrent.FutureTask<Void> detach = new java.util.concurrent.FutureTask<>(() -> {
                entered.countDown();
                output.bind(null);
                return null;
            });
            new Thread(detach, "surface-barrier-test").start();
            try {
                check(entered.await(1, java.util.concurrent.TimeUnit.SECONDS), "No detach attempt");
                try {
                    detach.get(1300, java.util.concurrent.TimeUnit.MILLISECONDS);
                    throw new AssertionError("Detach completed before in-flight frame was returned");
                } catch (java.util.concurrent.TimeoutException waitingForFrame) {
                    // Still waiting beyond the former one-second forced completion.
                }
            } finally {
                output.finishVideo(0, false);
            }
            detach.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        evidence.putString("surfaceBarrier", "PASS detach waited for the held frame");
    }

    void seekTransferFault(DisplayActivity display) throws Exception {
        final AtomicBoolean injectReleaseFault = new AtomicBoolean(false);
        final AtomicInteger releaseCodecCount = new AtomicInteger();
        final AtomicInteger discardCount = new AtomicInteger();
        final AtomicBoolean holdNextRender = new AtomicBoolean(false);
        final CountDownLatch frameInFlight = new CountDownLatch(1);
        final CountDownLatch returnFrame = new CountDownLatch(1);

        CodecCalls customCalls = new CodecCalls() {
            @Override
            void releaseOutputBuffer(android.media.MediaCodec codec, int index, boolean render) {
                if (!render) {
                    discardCount.incrementAndGet();
                    if (injectReleaseFault.get()) {
                        throw new IllegalStateException("Injected releaseOutputBuffer fault during discard");
                    }
                } else if (holdNextRender.getAndSet(false)) {
                    frameInFlight.countDown();
                    try {
                        check(returnFrame.await(7, TimeUnit.SECONDS), "Held frame was never released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
                super.releaseOutputBuffer(codec, index, render);
            }

            @Override
            void release(android.media.MediaCodec codec) {
                releaseCodecCount.incrementAndGet();
                super.release(codec);
            }
        };

        PlayerConfig.Builder builder = new PlayerConfig.Builder()
                .setVideoMode(PlayerConfig.VideoMode.HARDWARE_ONLY)
                .setProgressIntervalMs(100);
        builder.codecCalls = customCalls;
        player = new Player(context, builder.build(), this);

        android.view.Surface surface = display.awaitSurface();
        File videoFile = new File(context.getCacheDir(), "V11.mp4");
        if (!videoFile.exists()) {
            videoFile = new File(context.getCacheDir(), "V01.mp4");
        }

        try {
            ok(player.setVideoSurface(surface));
            ok(player.open(MediaSource.fromFile(videoFile)));
            ok(player.play());
            awaitOutput(0, 1);
            Thread.sleep(400);
            ok(player.pause());
            Thread.sleep(100);

            // Stage a pending picture to guarantee non-null pendingPicture during transfer
            instrumentation.runOnMainSync(() -> {
                if (player.activeOutput() != null && player.activeOutput().hardware() != null) {
                    player.activeOutput().hardware().stagePendingPictureForTest();
                }
            });

            // 1. Enable fault injection: during seek transfer, returning held pendingPicture throws
            injectReleaseFault.set(true);
            int releaseBefore = releaseCodecCount.get();

            long seekId = player.seekTo(5000);
            CommandResult result = await(seekId, CommandResult.Status.FAILED);
            check(result.error != null, "Missing error in failed seek result");
            check(result.error.code == PlayerError.Code.DECODER_RUNTIME, "Expected DECODER_RUNTIME, got " + result.error.code);
            check("hardwareTransfer".equals(result.error.stage), "Expected hardwareTransfer stage, got " + result.error.stage);

            // 2. Check that the old hardware decoder was recycled via calls.release(codec)
            check(releaseCodecCount.get() > releaseBefore, "Old hardware decoder was not released/recycled");

            // 3. Check that player state transitioned to ERROR
            check(player.getSnapshot().state == PlayerSnapshot.State.ERROR, "Player should be in ERROR state");

            // 4. Verify player can subsequently stop() and return to IDLE
            ok(player.stop());
            check(player.getSnapshot().state == PlayerSnapshot.State.IDLE, "Player should be in IDLE state after stop");

            // 5. Disable fault injection and verify player can open new media and play
            injectReleaseFault.set(false);
            PlayerSnapshot reopened = ok(player.open(MediaSource.fromFile(videoFile))).snapshot;
            check(reopened.state == PlayerSnapshot.State.READY, "Player should be in READY state after open");
            holdNextRender.set(true);
            long seekStart = SystemClock.elapsedRealtime();
            long heldSeek = player.seekTo(1000, true);
            try {
                check(frameInFlight.await(3, TimeUnit.SECONDS), "Seek did not reach the held hardware frame");
                CommandResult timeout = await(heldSeek, CommandResult.Status.FAILED);
                check(timeout.error.code == PlayerError.Code.INPUT_TIMEOUT &&
                                timeout.error.scope == PlayerError.Scope.REQUEST &&
                                SystemClock.elapsedRealtime() - seekStart < 5000,
                        "JNI cancellation waited for the held hardware frame");
                verifySeekAtEnd(reopened.media.durationMs);
            } finally {
                returnFrame.countDown();
            }
            int starts = videoStarts;
            ok(player.play());
            awaitOutput(0, starts + 1);
            ok(player.stop());
        } finally {
            try { ok(player.release()); } catch (Exception ignored) {}
        }

        // The final callback must survive four transient rejections without another event.
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch delivered = new CountDownLatch(1);
        CallbackQueue cbQueue = new CallbackQueue(runnable -> {
            if (attempts.incrementAndGet() <= 4) {
                throw new java.util.concurrent.RejectedExecutionException("Simulated queue saturation");
            }
            runnable.run();
        });
        cbQueue.add(false, delivered::countDown);
        check(delivered.await(3, TimeUnit.SECONDS),
                "Last event should be delivered after executor recovery without another enqueue");

        evidence.putString("seekTransferFault", "PASS transfer failure, in-flight JNI cancellation, replay and callback retry");
    }
}
