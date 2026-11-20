package com.cn.media.playback;

import android.content.Context;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.StructStat;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local measurements. Device monotonic time; recorded separately from external optical/acoustic evidence. */
final class Q04Trace implements Closeable {
    private static final String[] PSS_GROUPS = {
            "java-heap", "native-heap", "code", "stack", "graphics", "private-other", "system"
    };
    private static final String[] NO_ENTRIES = new String[0];
    final File dir;
    final AtomicInteger codecs = new AtomicInteger();
    volatile Player player;
    volatile String label = "startup,0,0,setup";
    volatile String pipeTarget = "";
    private volatile File fixture;
    private volatile StructStat fixtureStat;
    private final BufferedWriter samples, events, frames, inventory;
    private final Thread sampler;
    private final HandlerThread renderThread = new HandlerThread("q04-render");
    private final Handler render;
    private final PowerManager power;
    private final boolean releaseOnly;
    private final Debug.MemoryInfo memory = new Debug.MemoryInfo();
    private volatile boolean stopped;
    private volatile Throwable failure;
    private long lastCpu, lastWall;
    private int frameLines;

    private static BufferedWriter writer(File dir, String name, String header) throws IOException {
        BufferedWriter out = new BufferedWriter(new FileWriter(new File(dir, name)));
        out.write(header);
        out.newLine();
        return out;
    }

    Q04Trace(Context context, boolean releaseOnly) throws IOException {
        this.releaseOnly = releaseOnly;
        dir = new File(context.getCacheDir(), "q04-" + System.currentTimeMillis());
        if (!dir.mkdir()) throw new IOException("Cannot create evidence directory");
        power = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        String tag = "group,round,cycle,phase,monoMs,";
        StringBuilder memoryColumns = new StringBuilder(",javaUsedBytes");
        for (String group : PSS_GROUPS) memoryColumns.append(",pss-").append(group).append("-KiB");
        samples = writer(dir, "samples.csv", tag + "kind,cpuPct,pssKiB,nativeBytes,processFd,fixtureOrPipeFd,nativeInputFd,processThreads,sdkNamedThreads,liveCodecs,thermal,cpu0KHz,cpu4KHz,cpu6KHz,state,mediaId,timeline,positionMs,audioConsumed,videoSubmitted,videoDropped,maxSubmitGapMs,backend,routeId,routeType" + memoryColumns);
        frames = writer(dir, "rendered.csv", tag + "ptsUs,renderNs,callbackNs");
        events = writer(dir, "events.csv", tag + "event,number,value,detail");
        inventory = writer(dir, "inventory.tsv", "mark\tmonoMs\tkind\tid\ttargetOrName");
        renderThread.start();
        render = new Handler(renderThread.getLooper());
        sampler = new Thread(this::sampleLoop, "q04-sampler");
        sampler.start();
    }

    void mark(String group, int round, int cycle, String phase) {
        label = group + "," + round + "," + cycle + "," + phase;
    }

    void watchFixture(File file) throws android.system.ErrnoException {
        fixtureStat = Os.stat(file.getPath());
        fixture = file;
    }

    synchronized void event(String name, long number, long value, String detail) throws IOException {
        events.write(label + "," + SystemClock.elapsedRealtime() + "," + name + "," + number + "," + value + "," + safe(detail));
        events.newLine();
        events.flush();
    }

    private static String safe(String label) {
        return label == null ? "" : label.replace(',', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    CodecCalls observeCodecs() {
        return new CodecCalls() {
            @Override MediaCodec create(String name) throws IOException {
                MediaCodec codec = super.create(name);
                codecs.incrementAndGet();
                return codec;
            }
            @Override void start(MediaCodec codec) {
                final String epoch = label;
                codec.setOnFrameRenderedListener((ignored, ptsUs, nanoTime) -> {
                    if (ptsUs == Long.MAX_VALUE) return;
                    synchronized (Q04Trace.this) {
                        try {
                            frames.write(epoch + "," + SystemClock.elapsedRealtime() + "," + ptsUs + "," + nanoTime + "," + System.nanoTime());
                            frames.newLine();
                            if ((++frameLines & 127) == 0) frames.flush();
                        } catch (IOException e) { failure = e; }
                    }
                }, render);
                super.start(codec);
            }
            @Override void release(MediaCodec codec) {
                super.release(codec);
                codecs.decrementAndGet();
            }
        };
    }

    private static String read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[96];
            int count = in.read(b);
            return count > 0 ? new String(b, 0, count, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException | SecurityException ignored) { return ""; }
    }

    private static String freq(int cpu) {
        String value = read(new File("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_cur_freq"));
        if (value.isEmpty()) return "-1";
        for (int i = 0; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') return "-1";
        }
        return value;
    }

    private void sampleLoop() {
        long next = SystemClock.elapsedRealtime();
        try {
            while (!stopped) {
                sample("tick", false);
                next += 1000;
                long remaining = next - SystemClock.elapsedRealtime();
                if (remaining > 0) Thread.sleep(remaining);
                else next = SystemClock.elapsedRealtime();
            }
        } catch (InterruptedException ignored) {
            if (!stopped) failure = ignored;
        } catch (Throwable t) { failure = t; }
    }

    void healthy() {
        if (failure != null) throw new AssertionError("Q04 evidence collection failed", failure);
    }

    synchronized void sample(String kind, boolean keepInventory) throws IOException {
        healthy();
        String mark = label;
        long wall = SystemClock.elapsedRealtime(), cpu = Process.getElapsedCpuTime();
        double pct = -1;
        if (kind.equals("tick")) {
            if (lastWall != 0 && wall > lastWall) pct = 100.0 * (cpu-lastCpu) / (wall-lastWall);
            lastWall = wall;
            lastCpu = cpu;
        }
        // Release gates use the explicit baseline, active, and post-idle resource samples.
        // Keep the release run's periodic memory trace free of file and thread inventory allocations.
        boolean collectResources = !releaseOnly || !"tick".equals(kind);
        String[] fds = collectResources ? new File("/proc/self/fd").list() : NO_ENTRIES;
        String[] tids = collectResources ? new File("/proc/self/task").list() : NO_ENTRIES;
        if (fds == null || tids == null) throw new IOException("Cannot enumerate process resources");
        int open = 0, mediaFds = 0, sdkThreads = 0;
        for (String fd : fds) {
            String link;
            try { link = Os.readlink("/proc/self/fd/" + fd); }
            catch (android.system.ErrnoException gone) { continue; }
            open++;
            File watched = fixture;
            if (link.equals(pipeTarget)) mediaFds++;
            else if (watched != null && link.endsWith("/" + watched.getName())) {
                // /data/data and /data/user/0 can give the same inode two different path strings.
                try {
                    StructStat actual = Os.stat("/proc/self/fd/" + fd);
                    if (actual.st_dev == fixtureStat.st_dev && actual.st_ino == fixtureStat.st_ino) mediaFds++;
                } catch (android.system.ErrnoException gone) { /* Concurrent close. */ }
            }
            if (keepInventory) inventory.write(mark + "\t" + wall + "\tfd\t" + fd + "\t" + link + "\n");
        }
        for (String tid : tids) {
            String name = read(new File("/proc/self/task/" + tid + "/comm"));
            if (name.startsWith("playback-")) sdkThreads++;
            if (keepInventory) inventory.write(mark + "\t" + wall + "\tthread\t" + tid + "\t" + name + "\n");
        }
        Debug.getMemoryInfo(memory);
        Runtime runtime = Runtime.getRuntime();
        long javaUsed = runtime.totalMemory() - runtime.freeMemory();
        Player current = player;
        PlayerSnapshot shot = current == null ? null : current.getSnapshot();
        String activity = "idle,0,0,0,0,0,0,0,,0,0";
        if (shot != null) {
            PlaybackStats s = shot.stats;
            activity = shot.state + "," + shot.mediaId + "," + shot.timelineId + "," + shot.positionMs + "," +
                    s.audioFramesConsumed + "," + s.videoFramesSubmitted + "," + s.videoFramesDropped + "," +
                    s.maxSubmissionGapMs + "," + safe(shot.videoBackend) + "," + s.audioRouteId + "," + s.audioRouteType;
        }
        int thermal = Build.VERSION.SDK_INT >= 29 ? power.getCurrentThermalStatus() : -1;
        samples.write(mark + "," + wall + "," + kind + "," + pct + "," + memory.getTotalPss() + "," +
                Debug.getNativeHeapAllocatedSize() + ",");
        if (collectResources) {
            samples.write(open + "," + mediaFds + "," + NativeInput.liveInputFds() + "," +
                    tids.length + "," + sdkThreads + "," + codecs.get() + "," + thermal + "," +
                    freq(0) + "," + freq(4) + "," + freq(6) + ",");
        } else {
            samples.write(",," + NativeInput.liveInputFds() + ",,," + codecs.get() + "," + thermal + ",,,,");
        }
        samples.write(activity + "," + javaUsed);
        Map<String,String> categories = Build.VERSION.SDK_INT >= 23 && !"tick".equals(kind) ?
                memory.getMemoryStats() : java.util.Collections.emptyMap();
        for (String group : PSS_GROUPS) {
            samples.write(",");
            String value = categories.get("summary." + group);
            if (value != null) samples.write(value);
        }
        samples.newLine();
        samples.flush();
        if (keepInventory) inventory.flush();
    }

    @Override public void close() throws IOException {
        stopped = true;
        sampler.interrupt();
        CountDownLatch barrier = new CountDownLatch(1);
        render.post(barrier::countDown);
        try {
            sampler.join(4000);
            if (!barrier.await(4, TimeUnit.SECONDS) || sampler.isAlive()) throw new IOException("Trace drain timeout");
            renderThread.quitSafely();
            renderThread.join(4000);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        for (BufferedWriter out : Arrays.asList(samples, events, frames, inventory)) out.close();
        healthy();
    }
}
