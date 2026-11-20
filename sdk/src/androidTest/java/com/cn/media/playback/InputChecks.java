package com.cn.media.playback;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class InputChecks extends Instrumentation {
    private boolean dual;
    private String suite;
    private int rounds;
    private String inputKind, policy, samples;
    private String timingOperation;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        dual = arguments != null && "V01".equals(arguments.getString("sample"));
        suite = arguments == null ? null : arguments.getString("suite");
        rounds = arguments == null ? 1 : Integer.parseInt(arguments.getString("rounds", "1"));
        inputKind = arguments == null ? "file" : arguments.getString("input", "file");
        policy = arguments == null ? "AUTO" : arguments.getString("policy", "AUTO");
        samples = arguments == null ? "" : arguments.getString("samples", "");
        timingOperation = arguments == null ? "" : arguments.getString("operation", "");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("providerCleanup".equals(suite)) {
                ProviderCleanupChecks.run(getTargetContext(), result);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("transferFault".equals(suite) || "surfaceBarrier".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                try {
                    PlayerChecks checks = new PlayerChecks(this, result);
                    if ("surfaceBarrier".equals(suite)) checks.surfaceBarrier(display);
                    else checks.seekTransferFault(display);
                }
                finally { runOnMainSync(display::finish); }
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("diagnostics".equals(suite)) {
                new PlayerChecks(this, result).diagnostics();
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("progress".equals(suite)) {
                new PlayerChecks(this, result).progressAfterStall();
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("ranges".equals(suite)) {
                InputRangeChecks.run(getTargetContext(), result);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("timestamps".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                try {
                    for (String name : new String[]{"V10-forward.ts","V10-backward.ts"})
                        for (boolean pipe : new boolean[]{false,true})
                            new PlayerChecks(this, result).discontinuity(display, name, pipe,
                                    PlayerConfig.VideoMode.valueOf(policy));
                } finally { runOnMainSync(display::finish); }
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("audioTracks".equals(suite)) {
                new AudioTrackChecks(this, result).run(rounds);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("timing".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                try { new PlayerChecks(this, result).timings(display, samples, timingOperation, rounds); }
                finally { runOnMainSync(display::finish); }
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("dynamic".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                try {
                    int failures = 0;
                    for (String name : new String[]{"H02-h264-inband.ts","H02-hevc-inband.ts","H02-h264-stsd.mp4",
                            "H02-hevc-stsd.mp4","H02-bad-length.mp4","V11.mp4"}) {
                        try { new PlayerChecks(this, result).dynamic(display, name); }
                        catch (Exception | AssertionError failure) {
                            result.putString(name, "FAILED " + failure); failures++;
                        }
                    }
                    if (failures > 0) throw new AssertionError("Failed dynamic cases: " + failures);
                } finally { runOnMainSync(display::finish); }
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("q03".equals(suite)) {
                new ConcurrencyChecks(this, result).run(rounds);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("q01".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                try {
                    for (String name : new String[]{"A02.m4a", "V11.mp4", "V01.mp4"}) {
                        if (!samples.isEmpty() && !java.util.Arrays.asList(samples.split(",")).contains(name)) continue;
                        for (int i = 0; i < rounds; i++) new PlayerChecks(this, result).softwareLoop(display, name, i);
                    }
                } finally { runOnMainSync(display::finish); }
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("surface".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                try {
                    int failures = 0;
                    for (String name : new String[]{"V01.mp4", "V11.mp4"})
                        for (PlayerConfig.VideoMode mode : new PlayerConfig.VideoMode[]{
                                PlayerConfig.VideoMode.SOFTWARE_ONLY, PlayerConfig.VideoMode.HARDWARE_ONLY})
                            for (boolean pause : new boolean[]{false,true}) {
                                try { new PlayerChecks(this, result).surfaceLifecycle(display, name, mode, rounds, pause); }
                                catch (Exception | AssertionError ex) {
                                    result.putString(name + "-" + mode + "-" + pause + "-failure", ex.toString());
                                    failures++;
                                }
                            }
                    if (failures > 0) throw new AssertionError("Surface groups failed: " + failures);
                } finally { runOnMainSync(display::finish); }
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (suite != null && suite.startsWith("q04")) {
                new Q04Checks(this, result).run(suite, rounds, policy);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("geometry".equals(suite)) {
                new PlayerChecks(this, result).geometry();
                new PlayerChecks(this, result).lateVideo();
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("player".equals(suite)) {
                new PlayerChecks(this, result).run();
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("full".equals(suite)) {
                new PlayerChecks(this, result).fullV01();
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("hardware".equals(suite)) {
                new PlayerChecks(this, result).hardware();
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("faults".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                try {
                    for (int round = 0; round < rounds; round++)
                        for (String stage : new String[]{"create","configure","start","runtime","pipeRuntime",
                                "pipeNextEntry","pipeNoEntry"})
                            for (PlayerConfig.VideoMode mode : new PlayerConfig.VideoMode[]{
                                    PlayerConfig.VideoMode.AUTO, PlayerConfig.VideoMode.HARDWARE_ONLY})
                                new PlayerChecks(this, result).fault(display, stage, mode, round);
                } finally {
                    runOnMainSync(display::finish);
                }
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("degrade".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                try {
                    for (int i = 0; i < rounds; i++)
                        for (String name : new String[]{"bad-audio.mov","bad-video.mov","bad-both.mov"})
                            for (boolean strict : new boolean[]{false, true})
                                new PlayerChecks(this, result).degrade(display, name, strict, i);
                } finally { runOnMainSync(display::finish); }
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("formats".equals(suite)) {
                android.content.Intent intent = new android.content.Intent(getTargetContext(), DisplayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                DisplayActivity display = (DisplayActivity)startActivitySync(intent);
                int failed = 0;
                try {
                    for (String name : PlayerChecks.FORMATS) {
                        if (!samples.isEmpty() && !java.util.Arrays.asList(samples.split(",")).contains(name)) continue;
                        try {
                            new PlayerChecks(this, result).format(name, display, inputKind,
                                    PlayerConfig.VideoMode.valueOf(policy));
                        } catch (Exception | AssertionError failure) {
                            result.putString(name, "FAILED " + failure);
                            failed++;
                        }
                    }
                } finally { runOnMainSync(display::finish); }
                if (failed != 0) throw new AssertionError("Failed format cases: " + failed);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("audioRecovery".equals(suite)) {
                for (int i = 0; i < rounds; ++i) new PlayerChecks(this, result).audioRecovery(i);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("pcmWrites".equals(suite)) {
                for (String name : new String[]{"A04.wav","A02.m4a"})
                    new PlayerChecks(this,result).pcmWrites(name);
                finish(Activity.RESULT_OK,result);
                return;
            }
            if ("content".equals(suite)) {
                for (int i = 0; i < rounds; ++i) new PlayerChecks(this, result).content(i);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (suite != null && !"input".equals(suite)) throw new AssertionError("Unknown suite " + suite);
            if (!dual) result.putLong("pcmFramesConsumed", checkFile());
            result.putLong("pipeCancelMs", checkCancellation());
            result.putInt("surfaceFramesReceived", checkVideo(result));
            result.putString("fileProbe", "passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private int checkVideo(Bundle result) throws Exception {
        int width = dual ? 1920 : 1280;
        int height = dual ? 1080 : 720;
        int expected = dual ? 18000 : 750;
        File file = new File(getTargetContext().getCacheDir(), dual ? "V01.mp4" : "V11.mp4");
        android.os.HandlerThread thread = new android.os.HandlerThread("video-consumer");
        thread.start();
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch all = new CountDownLatch(expected);
        try (android.media.ImageReader reader = android.media.ImageReader.newInstance(
                width, height, android.graphics.PixelFormat.RGBA_8888, 3);
             InputRange range = MediaSource.fromFile(file).openDeferred(
                     getTargetContext().getContentResolver(), new android.os.CancellationSignal());
             NativeInput input = new NativeInput(range)) {
            reader.setOnImageAvailableListener(source -> {
                try (android.media.Image image = source.acquireNextImage()) {
                    if (image != null && image.getWidth() == width && image.getHeight() == height &&
                            image.getPlanes()[0].getBuffer().remaining() >= width * height * 4) {
                        received.incrementAndGet();
                        all.countDown();
                    }
                }
            }, new android.os.Handler(thread.getLooper()));
            if (input.probe() != 0) throw new AssertionError("Video probe failed");
            MediaInfo media = input.describe(42L);
            if (media.mediaId != 42 || media.tracks.size() != (dual ? 2 : 1) ||
                    media.durationMs == null || (dual ? media.durationMs < 600000L || media.durationMs > 600022L : media.durationMs != 30000L))
                throw new AssertionError("Unexpected video media description: " + media.durationMs);
            int videos = 0;
            for (TrackInfo track : media.tracks) {
                if (track.mediaId != media.mediaId) throw new AssertionError("Stale track");
                if (track.type == TrackInfo.Type.VIDEO && !track.coverArt && "h264".equals(track.codec)
                        && track.width == width && track.height == height) videos++;
            }
            if (videos != 1) throw new AssertionError("Missing expected H.264 track");
            result.putLong("videoDurationMs", media.durationMs);
            int posted;
            if (dual) {
                try (PcmOutput pcm = new PcmOutput()) {
                    posted = input.playBoth(pcm, reader.getSurface());
                    if (posted < 0) throw new AssertionError("Dual playback failed: " + posted);
                    long consumed = pcm.drain();
                    result.putLong("pcmFramesSubmitted", pcm.submitted());
                    result.putLong("lastPlaybackHead", pcm.consumed());
                    result.putInt("framesPostedBeforeAudioDrain", posted);
                    if (consumed != 28800000L) throw new AssertionError("Audio frames: " + consumed);
                    result.putLong("pcmFramesConsumed", consumed);
                }
            } else {
                long began = SystemClock.elapsedRealtime();
                posted = input.playVideo(reader.getSurface());
                long elapsed = SystemClock.elapsedRealtime() - began;
                result.putLong("videoElapsedMs", elapsed);
                if (elapsed < 29500 || elapsed > 31500)
                    throw new AssertionError("Unexpected V11 presentation time: " + elapsed);
            }
            result.putInt("surfaceFramesPosted", posted);
            if (posted != expected || !all.await(2, TimeUnit.SECONDS))
                throw new AssertionError("Video posted=" + posted + " received=" + received.get());
            return received.get();
        } finally {
            thread.quitSafely();
            thread.join();
        }
    }
    private long checkFile() throws Exception {
        File file = File.createTempFile("input", ".bin", getTargetContext().getCacheDir());
        try {
            try (InputStream media = getContext().getAssets().open("A02.m4a");
                 FileOutputStream output = new FileOutputStream(file)) {
                output.write(1);
                byte[] block = new byte[4096];
                int count;
                while ((count = media.read(block)) != -1) output.write(block, 0, count);
                output.write(2);
            }
            NativeInput acquired;
            try (ParcelFileDescriptor host = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                 InputRange range = MediaSource.fromDescriptor(host.getFileDescriptor(), 1,
                         file.length() - 2).acquireDescriptor()) {
                acquired = new NativeInput(range);
            }
            try (NativeInput input = acquired; PcmOutput pcm = new PcmOutput()) {
                if (input.probe() != 0) throw new AssertionError("AAC probe failed");
                MediaInfo media = input.describe(43L);
                if (media.durationMs == null || (media.durationMs < 12000L || media.durationMs > 12022L) || media.tracks.size() != 1)
                    throw new AssertionError("Unexpected AAC description: " + media.durationMs);
                TrackInfo audio = media.tracks.get(0);
                if (audio.index != 0 || audio.mediaId != 43L || audio.channels != 1 ||
                        audio.sampleRate != 48000 || !"aac".equals(audio.codec) || audio.type != TrackInfo.Type.AUDIO)
                    throw new AssertionError("Unexpected AAC track");
                if (input.playAudio(pcm) != 0) throw new AssertionError("AAC output failed");
                long frames = pcm.drain();
                if (frames <= 0) throw new AssertionError("No consumed frames");
                return frames;
            }
        } finally {
            file.delete();
        }
    }

    private long checkCancellation() throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor writer = pipe[1];
             InputRange range = InputRange.take(pipe[0], 0, -1);
             NativeInput input = new NativeInput(range)) {
            CountDownLatch entered = new CountDownLatch(1);
            FutureTask<Integer> task = new FutureTask<>(() -> {
                entered.countDown();
                return input.probe();
            });
            Thread worker = new Thread(task, "input-probe");
            worker.start();
            try {
                if (!entered.await(1, TimeUnit.SECONDS)) throw new AssertionError("Worker unavailable");
                SystemClock.sleep(100);
                if (task.isDone()) throw new AssertionError("Empty open pipe returned early");
                long began = SystemClock.elapsedRealtime();
                input.cancel();
                int status = task.get(1, TimeUnit.SECONDS);
                if (status >= 0) throw new AssertionError("Cancelled probe succeeded");
                return SystemClock.elapsedRealtime() - began;
            } finally {
                input.cancel();
                writer.close();
                worker.join();
            }
        }
    }
}
