package com.cn.media.playback.coexist;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import com.cn.media.playback.Player;
import com.cn.media.playback.PlayerConfig;
import com.cn.media.playback.PlayerListener;

public final class CoexistActivity extends Activity {
    private static native int marker();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        System.loadLibrary("playback_companion");
        if (marker() != 2301) throw new IllegalStateException("Unexpected companion");
        System.loadLibrary("playback");
        Player player = new Player(this, new PlayerConfig.Builder().build(), new PlayerListener() {});
        Log.i("PlaybackCoexist", "both JNI libraries loaded; marker=2301, state=" + player.getSnapshot().state);
        player.release();
        finish();
    }
}
