package dev.playback.acceptance;

import android.content.Context;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import com.cn.media.playback.*;

final class HostBinding implements HostCommands {
    private final Player player;
    HostBinding(Context context, PlayerListener listener) {
        player = new Player(context, new PlayerConfig.Builder().setRequireAllSelectedTracks(true).build(), listener);
    }
    public long surface(Surface value) { return player.setVideoSurface(value); }
    public long open(File file) throws IOException { return player.open(MediaSource.fromFile(file)); }
    public long play() { return player.play(); }
    public long pause() { return player.pause(); }
    public long seek(long targetMs) { return player.seekTo(targetMs); }
    public long selectAudioTrack(TrackInfo track) { return player.selectAudioTrack(track); }
    public long stop() { return player.stop(); }
    public long release() { return player.release(); }
    public PlayerSnapshot snapshot() { return player.getSnapshot(); }
}
