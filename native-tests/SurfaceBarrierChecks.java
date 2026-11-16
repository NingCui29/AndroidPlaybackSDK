package com.cn.media.playback;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.util.concurrent.TimeUnit;

/** Runs on the JVM with Gradle's mockable Android API jar. The barrier is real Java code. */
public final class SurfaceBarrierChecks implements PlaybackOutput.Observer {
    private static final class HostSurface extends Surface {
        boolean valid = true;
        HostSurface() { super((SurfaceTexture)null); }
        @Override public boolean isValid() { return valid; }
        @Override public void release() { valid = false; }
    }

    private Surface lost;
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public void ready(PlaybackOutput o, long at) {}
    public void firstAudio(PlaybackOutput o) {}
    public void firstVideo(PlaybackOutput o) {}
    public void backend(PlaybackOutput o, String name) {}
    public void backendFault(PlaybackOutput o, HardwareVideo.Fault fault) {}
    public void trackLost(PlaybackOutput o, boolean video, int reason) {}
    public void audioSwitchResult(PlaybackOutput o, long id, int raw, long at) {}
    public void surfaceLost(PlaybackOutput o, Surface invalid) { lost = invalid; }

    private PlaybackOutput output(Surface surface) {
        PlayerConfig config = new PlayerConfig.Builder().setCallbackExecutor(Runnable::run).build();
        return new PlaybackOutput(this, 0, null, 0, true, true, surface, 1f, config, false);
    }

    private static Thread call(Runnable task) {
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void waitUntilBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
            Thread.yield();
        check(thread.getState() == Thread.State.WAITING, "Surface fence returned before finishVideo");
    }

    private void repeatedStop() throws Exception {
        HostSurface surface = new HostSurface();
        try (PlaybackOutput out = output(surface)) {
            check(out.acquireVideo(0, 33333) == surface, "Didn't hold the old Surface");
            Thread first = call(out::stop);
            Thread second = null;
            try {
                waitUntilBlocked(first);
                second = call(out::stop);
                waitUntilBlocked(second);
            } finally {
                out.finishVideo(0, false);
                first.join(2000);
                if (second != null) second.join(2000);
            }
            check(!first.isAlive() && !second.isAlive(), "Stop did not finish");
            out.bind(new HostSurface());
            check(out.acquireVideo(33333, 33333) == null, "Old epoch submitted after replacement");
            check(out.initHardware(0, 0, 8, 30, null) == -3, "Old epoch tried to initialize a decoder");
        }
    }

    private void invalidSurface() {
        HostSurface surface = new HostSurface(), replacement = new HostSurface();
        try (PlaybackOutput out = output(surface)) {
            surface.release();
            check(out.acquireVideo(0, 33333) == null && lost == surface, "Invalid host Surface was granted");
            out.bind(replacement);
            check(out.acquireVideo(0, 33333) == replacement, "Valid replacement never acquired");
            out.finishVideo(0, true);
        }
    }

    public static void main(String[] args) throws Exception {
        SurfaceBarrierChecks test = new SurfaceBarrierChecks();
        test.repeatedStop();
        test.invalidSurface();
        System.out.println("PASS concurrent stop waits for lease, late init cancelled, invalid binding revoked and replaced");
    }
}
