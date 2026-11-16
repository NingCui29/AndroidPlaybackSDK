package dev.playback.acceptance;

import android.view.Surface;
import java.io.File;
import java.io.IOException;
import com.cn.media.playback.PlayerSnapshot;
import com.cn.media.playback.TrackInfo;

interface HostCommands {
    long surface(Surface value);
    long open(File file) throws IOException;
    long play();
    long pause();
    long seek(long targetMs);
    long selectAudioTrack(TrackInfo track);
    long stop();
    long release();
    PlayerSnapshot snapshot();
}
