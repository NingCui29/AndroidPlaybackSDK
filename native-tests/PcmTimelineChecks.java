package com.cn.media.playback;

/** Host-side playback-head tests with both sides of each discontinuity in the same PCM ring. */
public final class PcmTimelineChecks {
    private static void expect(long actual, long expected) {
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        PcmTimeline timeline = new PcmTimeline();
        int rate = 48000;
        timeline.start(0, 0);
        timeline.append(192000, 7000000);
        timeline.append(194400, 5550000);
        expect(timeline.mediaUs(191976, rate), 3999500);
        expect(timeline.mediaUs(192000, rate), 4000000);
        expect(timeline.jumps, 0);
        expect(timeline.mediaUs(192048, rate), 7001000);
        expect(timeline.jumps, 1);
        expect(timeline.shiftUs(rate), 3000000);
        expect(timeline.mediaUs(194448, rate), 5551000);
        expect(timeline.jumps, 2);
        expect(timeline.shiftUs(rate), 1500000);
        // The replacement track starts at the current content position with a fresh hardware head.
        timeline.start(5551000, timeline.shiftUs(rate));
        expect(timeline.mediaUs(441, 44100), 5561000);
        expect(timeline.shiftUs(44100), 1500000);
        PcmTimeline longRun = new PcmTimeline();
        long boundary = 1L << 32;
        longRun.start(0, 0);
        longRun.append(boundary, 123000000);
        longRun.append(boundary, 456000000);
        expect(longRun.mediaUs(boundary, rate), boundary * 1000000L / rate);
        expect(longRun.mediaUs(boundary + 48, rate), 456001000);
        expect(longRun.jumps, 1);
        longRun.append(boundary + 96, 700000);
        longRun.start(900000, 0);
        expect(longRun.mediaUs(480, rate), 910000);
        System.out.println("PASS extended frame count across 2^32, replaced boundary and old pending segment cleared");
        System.out.println("PASS forward/backward PCM boundaries, queued jumps, 48/44.1 kHz track handoff");
    }
}
