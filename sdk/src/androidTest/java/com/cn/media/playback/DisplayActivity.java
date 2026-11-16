package com.cn.media.playback;

import android.app.Activity;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

public final class DisplayActivity extends Activity {
    private Surface available;
    private volatile SurfaceView view;
    private volatile Player boundPlayer;
    private volatile long destroyCount, detachNs;
    private volatile long bindNs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        replaceView();
    }

    void replaceView() {
        SurfaceView next = new SurfaceView(this);
        next.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                Player p = boundPlayer;
                if (p != null) {
                    bindNs = android.os.SystemClock.elapsedRealtimeNanos();
                    p.setVideoSurface(holder.getSurface());
                }
                synchronized (DisplayActivity.this) {
                    if (next == view) {
                        available = holder.getSurface();
                        DisplayActivity.this.notifyAll();
                    }
                }
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                synchronized (DisplayActivity.this) {
                    if (available == holder.getSurface()) available = null;
                }
                Player p = boundPlayer;
                if (p != null) {
                    long start = android.os.SystemClock.elapsedRealtimeNanos();
                    p.detachVideoSurface(holder.getSurface());
                    detachNs = android.os.SystemClock.elapsedRealtimeNanos() - start;
                    destroyCount++;
                }
            }
        });
        synchronized (this) { available = null; view = next; }
        setContentView(next);
    }

    synchronized Surface awaitSurface() throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + 4000;
        while (available == null || !available.isValid()) {
            long left = deadline - android.os.SystemClock.elapsedRealtime();
            if (left <= 0) throw new AssertionError("Display surface unavailable for 4 seconds");
            wait(left);
        }
        return available;
    }

    android.graphics.Rect surfaceBounds() { return new android.graphics.Rect(view.getHolder().getSurfaceFrame()); }

    // Instrumentation sets the owner on the UI thread for suites exercising system callbacks.
    void observeSurface(Player player) { boundPlayer = player; }
    long destroys() { return destroyCount; }
    long detachNs() { return detachNs; }
    long bindNs() { return bindNs; }
    void removeVideoView() { setContentView(new android.widget.FrameLayout(this)); }
}
