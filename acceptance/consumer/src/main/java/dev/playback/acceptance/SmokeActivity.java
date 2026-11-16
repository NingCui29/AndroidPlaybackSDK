package dev.playback.acceptance;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import com.cn.media.playback.*;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent, release-minified application. Its result file is written after release completes. */
public final class SmokeActivity extends Activity {
    private final ConcurrentHashMap<Long, CommandResult> results = new ConcurrentHashMap<>();
    private final AtomicBoolean audio = new AtomicBoolean(), video = new AtomicBoolean();
    private HostBinding host;
    private TextureView display;
    private Surface surface;
    private SurfaceTexture retainedTexture;
    private volatile boolean destroyed;
    private final PlayerListener listener = new PlayerListener() {
        @Override public void onCommandResult(CommandResult value) {
            if (value.snapshot.state == PlayerSnapshot.State.RELEASED) releaseDisplay();
            results.put(value.requestId, value);
        }
        @Override public void onFirstAudioConsumption(PlayerSnapshot value) { audio.set(true); }
        @Override public void onFirstVideoSubmission(PlayerSnapshot value) { video.set(true); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String sample = getIntent().getStringExtra("sample");
        if (!"V01".equals(sample) && !"A01".equals(sample) && !"V05".equals(sample)) {
            TextView message = new TextView(this);
            message.setText("Launch with the V01, V05 or A01 acceptance fixture.");
            setContentView(message);
            getExternalFilesDir(null);
            return;
        }
        host = new HostBinding(this, listener);
        if ("A01".equals(sample)) {
            setContentView(new TextView(this));
            new Thread(() -> exercise(sample), "audio-acceptance").start();
            return;
        }
        display = new TextureView(this);
        display.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
                if (surface != null) return;
                surface = new Surface(texture);
                new Thread(() -> exercise(sample), "video-acceptance").start();
            }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                if (host.snapshot().state == PlayerSnapshot.State.RELEASED) return true;
                retainedTexture = texture;
                host.release();
                return false;
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {}
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        setContentView(display);
    }

    private CommandResult await(long request) throws Exception {
        long end = SystemClock.elapsedRealtime() + 12000;
        CommandResult response;
        while ((response = results.remove(request)) == null) {
            if (destroyed || SystemClock.elapsedRealtime() >= end) throw new Exception("Command timeout " + request);
            Thread.sleep(10);
        }
        if (response.status != CommandResult.Status.SUCCESS) throw new Exception("Command "
                + request + " " + response.status + " " + (response.error == null ? "" : response.error.code));
        return response;
    }

    private void check(boolean value, String message) throws Exception {
        if (!value) throw new Exception(message);
    }

    private void waitForOutput(boolean picture, long afterMs) throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        do {
            PlayerSnapshot at = host.snapshot();
            if (audio.get() && at.stats.audioFramesConsumed > 0 && at.positionMs >= afterMs &&
                    (!picture || (video.get() && at.stats.videoFramesSubmitted > 0))) return;
            check(!destroyed && at.state != PlayerSnapshot.State.ERROR, "Playback failed");
            Thread.sleep(20);
        } while (SystemClock.elapsedRealtime() < end);
        throw new Exception("No progressing output at " + afterMs + " ms");
    }

    private boolean capturePicture() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean changed = new AtomicBoolean();
        runOnUiThread(() -> {
            try {
                Bitmap frame = display.getBitmap(96, 54);
                if (frame != null) {
                    int first = frame.getPixel(0, 0);
                    for (int y = 0; y < frame.getHeight(); y += 2)
                        for (int x = 0; x < frame.getWidth(); x += 2)
                            if (frame.getPixel(x, y) != first) changed.set(true);
                    frame.recycle();
                }
            } finally { done.countDown(); }
        });
        return done.await(3, TimeUnit.SECONDS) && changed.get();
    }

    private void exercise(String name) {
        JSONObject record = new JSONObject();
        boolean picture = !"A01".equals(name);
        File dir = getExternalFilesDir(null);
        long start = SystemClock.elapsedRealtime();
        try {
            record.put("sample", name).put("application", getPackageName());
            File input = new File(dir, "V05".equals(name) ? "V05.mkv" : picture ? "V01.mp4" : "A01.mp3");
            if (picture) await(host.surface(surface));
            CommandResult opened = await(host.open(input));
            check(opened.snapshot.audioTrack != null && (opened.snapshot.videoTrack != null) == picture,
                    "Unexpected selected tracks");
            record.put("audioCodec", opened.snapshot.audioTrack.codec);
            if (picture) record.put("videoCodec", opened.snapshot.videoTrack.codec);
            await(host.play());
            waitForOutput(picture, 300);
            PlayerSnapshot at = host.snapshot();
            if ("V05".equals(name)) {
                TrackInfo alternate = null;
                for (TrackInfo track : opened.snapshot.media.tracks) {
                    if (track.type == TrackInfo.Type.AUDIO && track.index != at.audioTrack.index) alternate = track;
                }
                check(alternate != null, "Missing alternate audio stream");
                PlayerSnapshot changed = await(host.selectAudioTrack(alternate)).snapshot;
                check(changed.audioTrack != null && changed.audioTrack.index == alternate.index, "Audio selection failed");
                record.put("oldTrack", at.audioTrack.index).put("newTrack", changed.audioTrack.index);
            }
            record.put("audioFramesConsumed", at.stats.audioFramesConsumed)
                    .put("videoFramesSubmitted", at.stats.videoFramesSubmitted)
                    .put("backend", at.videoBackend);
            if (picture) {
                boolean pixels = capturePicture();
                record.put("visibleNonuniformFrame", pixels);
                check(pixels, "No visible test pattern");
            }
            check(await(host.pause()).snapshot.state == PlayerSnapshot.State.PAUSED, "Expected PAUSED");
            CommandResult seek = await(host.seek(1500));
            check(seek.actualPositionMs != null && Math.abs(seek.actualPositionMs - 1500) <= 100,
                    "Seek did not land near target: " + seek.actualPositionMs);
            record.put("seekActualMs", seek.actualPositionMs);
            await(host.play());
            waitForOutput(picture, 1800);
            record.put("resumedPositionMs", host.snapshot().positionMs);
            check(await(host.stop()).snapshot.state == PlayerSnapshot.State.IDLE, "Expected IDLE after stop");
            check(await(host.release()).snapshot.state == PlayerSnapshot.State.RELEASED, "Expected RELEASED");
            record.put("outcome", "PASS");
        } catch (Throwable error) {
            try {
                record.put("outcome", "FAIL").put("failure", error.toString());
                host.release();
            } catch (Exception ignored) {}
        }
        try {
            record.put("elapsedMs", SystemClock.elapsedRealtime() - start);
            File pending = new File(dir, name + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(pending)) {
                stream.write(record.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!pending.renameTo(new File(dir, name + ".json"))) throw new java.io.IOException("result rename failed");
        } catch (Exception error) { android.util.Log.e("PlaybackAcceptance", "Result file", error); }
        runOnUiThread(this::finish);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (host != null) host.release();
        super.onDestroy();
    }

    private void releaseDisplay() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (retainedTexture != null) {
            retainedTexture.release();
            retainedTexture = null;
        }
    }
}
