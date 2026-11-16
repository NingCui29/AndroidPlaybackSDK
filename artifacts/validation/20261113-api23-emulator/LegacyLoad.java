package com.cn.media.playback;

import android.os.CancellationSignal;
import android.os.Looper;
import java.io.File;

public final class LegacyLoad {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        try (InputRange range = MediaSource.fromFile(new File(args[0])).openDeferred(null, new CancellationSignal());
             NativeInput input = new NativeInput(range)) {
            int status = input.probe();
            if (status != 0) throw new AssertionError("probe " + status);
            MediaInfo info = input.describe(7L);
            if (info.mediaId != 7 || info.tracks.size() != 1 || !"aac".equals(info.tracks.get(0).codec))
                throw new AssertionError("wrong metadata");
            System.out.println("PASS API23 AAR JNI load, FFmpeg probe, MediaInfo and TrackInfo JNI construction; codec=" + info.tracks.get(0).codec);
        }
    }
}
