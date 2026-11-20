package com.cn.media.playback.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.cn.media.playback.CommandResult;
import com.cn.media.playback.MediaSource;
import com.cn.media.playback.PlaybackStats;
import com.cn.media.playback.Player;
import com.cn.media.playback.PlayerConfig;
import com.cn.media.playback.PlayerError;
import com.cn.media.playback.PlayerListener;
import com.cn.media.playback.PlayerSnapshot;
import com.cn.media.playback.TrackInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Android Playback SDK 全功能真机演示播放器 Demo。
 * <p>
 * 具备以下核心能力：
 * <ul>
 *     <li>全生命周期 Surface 绑定与同步解绑（零帧泄漏、零死锁）；</li>
 *     <li>极速关键帧定位（FastSeek，毫秒级响应）与精准逐帧定位（ExactSeek）动态切换；</li>
 *     <li>解码模式实时切换（AUTO 硬件优先自动回退 / 纯硬件 / 纯软件 FFmpeg）；</li>
 *     <li>实时高精度诊断 HUD（显示状态、分辨率、解码后端、渲染帧数、丢帧率、Seek延时、物理内存 PSS）；</li>
 *     <li>完整播放控制：播放/暂停、停止、重播、前进/后退 10s、音量调节与静音控制；</li>
 *     <li>交互式控制栏淡入淡出及自动隐藏。</li>
 * </ul>
 */
public class DemoActivity extends Activity implements SurfaceHolder.Callback, PlayerListener {

    private static final String DEFAULT_SAMPLE_PATH = "/sdcard/Download/benchmark/V01.mp4";
    private static final long CONTROLS_TIMEOUT_MS = 10000;

    // UI 组件
    private SurfaceView surfaceView;
    private ProgressBar loadingProgress;
    private View topBar;
    private View bottomBar;
    private LinearLayout hudContainer;
    private TextView hudTitle;
    private TextView hudText;
    private EditText editPath;
    private Button btnPickFile;
    private Button btnOpen;
    private Button btnToggleHud;
    private Button btnPlayPause;
    private Button btnStop;
    private Button btnStepBack;
    private Button btnStepForward;
    private Button btnFastSeek;
    private Button btnDecodeMode;
    private Button btnAspectRatio;
    private Button btnMute;
    private SeekBar seekBar;
    private SeekBar seekVolume;
    private TextView tvCurrentTime;
    private TextView tvDuration;

    // 画幅比例模式
    public enum AspectRatioMode {
        FIT("比例:自适应"),
        STRETCH("比例:拉伸"),
        CROP("比例:裁剪"),
        RATIO_16_9("比例:16:9"),
        RATIO_4_3("比例:4:3"),
        RATIO_21_9("比例:21:9"),
        RATIO_9_16("比例:9:16 (短视频)"),
        RATIO_3_4("比例:3:4 (小红书)"),
        RATIO_1_1("比例:1:1 (正方形)"),
        ORIGINAL("比例:原始");

        final String label;
        AspectRatioMode(String label) { this.label = label; }
    }
    private AspectRatioMode currentAspectRatio = AspectRatioMode.FIT;
    private int currentVideoWidth = 0;
    private int currentVideoHeight = 0;
    private double currentVideoSar = 1.0;
    private int currentVideoRotation = 0;

    // 播放器核心
    private Player player;
    private Surface currentSurface;
    private boolean fastSeekEnabled = true;
    private PlayerConfig.VideoMode videoMode = PlayerConfig.VideoMode.AUTO;
    private String currentFilePath = DEFAULT_SAMPLE_PATH;
    private Uri currentContentUri = null;
    private static final int REQUEST_CODE_PICK_FILE = 2001;
    private boolean autoOpenPending = true;

    // 状态与指标
    private volatile int playerGeneration = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean activityStarted;
    private boolean isDraggingSeek = false;
    private boolean wasPlayingBeforeDrag = false;
    private boolean isSeekInFlight = false;
    private long lastDragSeekTimeMs = 0;
    private long pendingDragSeekMs = -1;
    private static final long DRAG_SEEK_INTERVAL_MS = 30; // 30ms 节流，实现拖拽跟手画面实时刷新
    private boolean controlsVisible = true;
    private boolean isMuted = false;
    private float currentVolume = 1.0f;
    private long currentDurationMs = 0;
    private long lastOpenCommandId = -1;
    private long pendingSeekPositionMs = -1;
    private long lastSeekCommandId = -1;
    private long lastSeekStartTimeNs = 0;
    private long lastSeekLatencyMs = -1;
    private boolean seekRequestExact;
    private boolean lastSuccessfulSeekExact;
    private int pssRefreshCounter = 0;
    private double lastProcessPssMb = 0.0;

    private final Runnable hideControlsTask = () -> setControlsVisibility(false);

    private void resetSeekAndDragState() {
        mainHandler.removeCallbacks(dragSeekTask);
        mainHandler.removeCallbacks(seekWatchdogTask);
        isDraggingSeek = false;
        isSeekInFlight = false;
        pendingDragSeekMs = -1;
        lastSeekCommandId = -1;
        wasPlayingBeforeDrag = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        checkAndRequestStoragePermissions();
        initViews();
        setupListeners();
        initPlayer();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String path = intent.getStringExtra("path");
        if (path == null || path.trim().isEmpty()) return;
        selectMediaPath(path.trim());
        editPath.setText(currentFilePath);
        autoOpenPending = false;
        openCurrentMedia(0, true);
    }

    private void checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 1001);
        }
    }

    private void initViews() {
        surfaceView = findViewById(R.id.surface_view);
        loadingProgress = findViewById(R.id.loading_progress);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        hudContainer = findViewById(R.id.hud_container);
        hudTitle = findViewById(R.id.hud_title);
        hudText = findViewById(R.id.hud_text);
        editPath = findViewById(R.id.edit_path);
        btnPickFile = findViewById(R.id.btn_pick_file);
        btnOpen = findViewById(R.id.btn_open);
        btnToggleHud = findViewById(R.id.btn_toggle_hud);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnStop = findViewById(R.id.btn_stop);
        btnStepBack = findViewById(R.id.btn_step_back);
        btnStepForward = findViewById(R.id.btn_step_forward);
        btnFastSeek = findViewById(R.id.btn_fast_seek);
        btnDecodeMode = findViewById(R.id.btn_decode_mode);
        btnAspectRatio = findViewById(R.id.btn_aspect_ratio);
        btnMute = findViewById(R.id.btn_mute);
        seekBar = findViewById(R.id.seek_bar);
        seekVolume = findViewById(R.id.seek_volume);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvDuration = findViewById(R.id.tv_duration);

        surfaceView.getHolder().addCallback(this);
        View touchOverlay = findViewById(R.id.touch_overlay);
        if (touchOverlay != null) {
            touchOverlay.setOnClickListener(v -> toggleControlsVisibility());
        }
        String customPath = getIntent() != null ? getIntent().getStringExtra("path") : null;
        if (customPath != null && !customPath.trim().isEmpty()) {
            selectMediaPath(customPath.trim());
            editPath.setText(currentFilePath);
        } else {
            editPath.setText(DEFAULT_SAMPLE_PATH);
        }
        updateFastSeekButtonState();
        updateDecodeModeButtonState();
    }

    private void setupListeners() {
        // 全屏点击唤起/隐藏控制栏
        surfaceView.setOnClickListener(v -> toggleControlsVisibility());
        findViewById(R.id.root_container).setOnClickListener(v -> toggleControlsVisibility());

        // 本机文件选择
        btnPickFile.setOnClickListener(v -> showFilePickerMenu());

        // 打开源
        btnOpen.setOnClickListener(v -> {
            String path = editPath.getText().toString().trim();
            if (path.isEmpty()) {
                Toast.makeText(this, "请输入有效的媒体路径或点击选择文件", Toast.LENGTH_SHORT).show();
                return;
            }
            selectMediaPath(path);
            openCurrentMedia(0, true);
        });

        // HUD 开关
        btnToggleHud.setOnClickListener(v -> {
            boolean isVisible = hudContainer.getVisibility() == View.VISIBLE;
            hudContainer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            btnToggleHud.setText(isVisible ? "HUD:关" : "HUD:开");
            btnToggleHud.setTextColor(isVisible ? 0xFFB0BEC5 : 0xFF80D8FF);
            resetControlsTimeout();
        });

        // 播放 / 暂停
        btnPlayPause.setOnClickListener(v -> {
            resetControlsTimeout();
            if (player == null) return;
            wasPlayingBeforeDrag = false;
            PlayerSnapshot snapshot = player.getSnapshot();
            if (snapshot.state == PlayerSnapshot.State.PLAYING) {
                player.pause();
            } else if (snapshot.state == PlayerSnapshot.State.ENDED || snapshot.state == PlayerSnapshot.State.IDLE || snapshot.state == PlayerSnapshot.State.ERROR) {
                openCurrentMedia(0, true);
            } else {
                player.play();
            }
        });

        // 停止
        btnStop.setOnClickListener(v -> {
            resetControlsTimeout();
            resetSeekAndDragState();
            pendingSeekPositionMs = -1;
            if (player != null) player.stop();
        });

        // 步退 10 秒
        btnStepBack.setOnClickListener(v -> {
            resetControlsTimeout();
            if (player == null) return;
            long targetMs = Math.max(0, player.getSnapshot().positionMs - 10000);
            android.util.Log.i("DemoActivity", "btnStepBack clicked, target=" + targetMs);
            executeSeek(targetMs, true);
        });

        // 步进 10 秒
        btnStepForward.setOnClickListener(v -> {
            resetControlsTimeout();
            if (player == null) return;
            long targetMs = player.getSnapshot().positionMs + 10000;
            if (currentDurationMs > 0) targetMs = Math.min(currentDurationMs, targetMs);
            android.util.Log.i("DemoActivity", "btnStepForward clicked, current=" + player.getSnapshot().positionMs + " target=" + targetMs);
            executeSeek(targetMs, true);
        });

        // FastSeek 切换
        btnFastSeek.setOnClickListener(v -> {
            resetControlsTimeout();
            fastSeekEnabled = !fastSeekEnabled;
            updateFastSeekButtonState();
            rebuildPlayerWithCurrentPosition();
        });

        // 解码模式切换
        btnDecodeMode.setOnClickListener(v -> {
            resetControlsTimeout();
            if (videoMode == PlayerConfig.VideoMode.AUTO) {
                videoMode = PlayerConfig.VideoMode.SOFTWARE_ONLY;
            } else if (videoMode == PlayerConfig.VideoMode.SOFTWARE_ONLY) {
                videoMode = PlayerConfig.VideoMode.HARDWARE_ONLY;
            } else {
                videoMode = PlayerConfig.VideoMode.AUTO;
            }
            updateDecodeModeButtonState();
            rebuildPlayerWithCurrentPosition();
        });

        // 画幅比例切换
        btnAspectRatio.setOnClickListener(v -> cycleAspectRatio());

        // 音量调节
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    resetControlsTimeout();
                    currentVolume = progress / 100.0f;
                    player.setVolume(currentVolume);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { resetControlsTimeout(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { resetControlsTimeout(); }
        });

        // 静音开关
        btnMute.setOnClickListener(v -> {
            resetControlsTimeout();
            if (player == null) return;
            isMuted = !isMuted;
            player.setMuted(isMuted);
            btnMute.setText(isMuted ? "静音:开" : "静音:关");
            btnMute.setTextColor(isMuted ? 0xFFFF5252 : 0xFFFFFFFF);
        });

        // 进度拖动（支持滑动过程中画面实时跟手刷新）
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && canDragSeek()) {
                    resetControlsTimeout();
                    long targetMs = (long) (currentDurationMs * (progress / 1000.0));
                    tvCurrentTime.setText(formatTime(targetMs));
                    if (isDraggingSeek) {
                        scheduleDragSeek(targetMs);
                    } else {
                        executeSeek(targetMs, true);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (!canDragSeek()) return;
                isDraggingSeek = true;
                pendingDragSeekMs = -1;
                mainHandler.removeCallbacks(hideControlsTask);
                mainHandler.removeCallbacks(dragSeekTask);
                if (!wasPlayingBeforeDrag) {
                    wasPlayingBeforeDrag = (player != null && player.getSnapshot().state == PlayerSnapshot.State.PLAYING);
                }
                android.util.Log.i("DemoActivity", "onStartTrackingTouch wasPlayingBeforeDrag=" + wasPlayingBeforeDrag + " state=" + (player != null ? player.getSnapshot().state : "null"));
                if (wasPlayingBeforeDrag && player != null && player.getSnapshot().state == PlayerSnapshot.State.PLAYING) {
                    player.pause();
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!isDraggingSeek) return;
                isDraggingSeek = false;
                mainHandler.removeCallbacks(dragSeekTask);
                resetControlsTimeout();
                long targetMs = canDragSeek()
                        ? (long) (currentDurationMs * (seekBar.getProgress() / 1000.0)) : -1;
                android.util.Log.i("DemoActivity", "seekBar onStopTrackingTouch target=" + targetMs + " inFlight=" + isSeekInFlight + " wasPlaying=" + wasPlayingBeforeDrag);
                if (targetMs >= 0) {
                    if (isSeekInFlight) {
                        pendingDragSeekMs = targetMs;
                    } else {
                        pendingDragSeekMs = -1;
                        executeSeek(targetMs, true);
                    }
                } else if (!isSeekInFlight) {
                    resumeAfterDrag();
                }
            }
        });
    }

    private final Runnable dragSeekTask = this::executeDragSeek;
    private final Runnable seekWatchdogTask = () -> {
        if (isSeekInFlight) {
            android.util.Log.w("DemoActivity", "Seek watchdog triggered, resetting isSeekInFlight");
            isSeekInFlight = false;
            lastSeekCommandId = -1;
            finishDragSeek();
        }
    };

    private void scheduleDragSeek(long targetMs) {
        if (player == null) return;
        pendingDragSeekMs = targetMs;
        // 关键机制：如果当前正在解码一帧画面，坚决不中途打断前一次 Seek，保证当前帧顺利解码渲染上屏。
        // 上屏后在 onCommandResult 中无缝衔接执行最新的 pendingDragSeekMs！
        if (isSeekInFlight) {
            return;
        }
        mainHandler.removeCallbacks(dragSeekTask);
        executeDragSeek();
    }

    private void executeDragSeek() {
        if (player == null || pendingDragSeekMs < 0) return;
        long targetMs = pendingDragSeekMs;
        pendingDragSeekMs = -1;
        executeSeek(targetMs, !isDraggingSeek);
    }

    private void executeSeek(long targetMs) {
        executeSeek(targetMs, true);
    }

    private boolean canSeekCurrentMedia() {
        if (player == null || lastOpenCommandId >= 0) return false;
        PlayerSnapshot snapshot = player.getSnapshot();
        return snapshot.media != null && snapshot.media.seekable && snapshot.state != PlayerSnapshot.State.ERROR;
    }

    private boolean canDragSeek() {
        return activityStarted && currentDurationMs > 0 && canSeekCurrentMedia();
    }

    private void updateSeekBarEnabled() {
        seekBar.setEnabled(canDragSeek());
    }

    private void finishDragSeek() {
        if (pendingDragSeekMs >= 0 && canDragSeek()) {
            executeDragSeek();
            return;
        }
        pendingDragSeekMs = -1;
        if (!isDraggingSeek && player != null) {
            onSnapshot(player.getSnapshot());
            resumeAfterDrag();
        }
    }

    private void resumeAfterDrag() {
        boolean resume = wasPlayingBeforeDrag;
        wasPlayingBeforeDrag = false;
        android.util.Log.i("DemoActivity", "resumeAfterDrag resume=" + resume + " player=" + (player != null));
        if (!resume || !activityStarted || player == null) return;
        PlayerSnapshot.State state = player.getSnapshot().state;
        if (state == PlayerSnapshot.State.PAUSED || state == PlayerSnapshot.State.READY) player.play();
    }

    private void executeSeek(long targetMs, boolean exact) {
        if (!canSeekCurrentMedia()) return;
        isSeekInFlight = true;
        mainHandler.removeCallbacks(seekWatchdogTask);
        // 让 SDK 的定位预滚超时先返回命令终态。
        mainHandler.postDelayed(seekWatchdogTask, 3500);
        lastSeekStartTimeNs = SystemClock.elapsedRealtimeNanos();
        seekRequestExact = exact || !fastSeekEnabled;
        lastSeekCommandId = player.seekTo(targetMs, exact);
        android.util.Log.i("DemoActivity", "executeSeek target=" + targetMs + " exact=" + exact + " commandId=" + lastSeekCommandId);
    }

    private void updateFastSeekButtonState() {
        btnFastSeek.setText(fastSeekEnabled ? "FastSeek:开" : "FastSeek:关");
        btnFastSeek.setTextColor(fastSeekEnabled ? 0xFF00E676 : 0xFFFFB74D);
    }

    private void updateDecodeModeButtonState() {
        switch (videoMode) {
            case AUTO:
                btnDecodeMode.setText("解码:AUTO");
                btnDecodeMode.setTextColor(0xFF80D8FF);
                break;
            case SOFTWARE_ONLY:
                btnDecodeMode.setText("解码:纯软解");
                btnDecodeMode.setTextColor(0xFFFFAB40);
                break;
            case HARDWARE_ONLY:
                btnDecodeMode.setText("解码:纯硬解");
                btnDecodeMode.setTextColor(0xFFB388FF);
                break;
        }
    }

    private void cycleAspectRatio() {
        resetControlsTimeout();
        AspectRatioMode[] modes = AspectRatioMode.values();
        int nextIndex = (currentAspectRatio.ordinal() + 1) % modes.length;
        currentAspectRatio = modes[nextIndex];
        btnAspectRatio.setText(currentAspectRatio.label);
        applyAspectRatio();
    }

    private void applyAspectRatio() {
        if (surfaceView == null) return;
        View parent = findViewById(R.id.root_container);
        int parentW = parent != null ? parent.getWidth() : 0;
        int parentH = parent != null ? parent.getHeight() : 0;
        if (parentW <= 0 || parentH <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            parentW = dm.widthPixels;
            parentH = dm.heightPixels;
        }
        if (parentW <= 0 || parentH <= 0) return;

        double sar = currentVideoSar > 0 ? currentVideoSar : 1.0;
        double rawW = (currentVideoWidth > 0 ? currentVideoWidth : parentW) * sar;
        double rawH = (currentVideoHeight > 0 ? currentVideoHeight : parentH);
        double vidW = (currentVideoRotation == 90 || currentVideoRotation == 270) ? rawH : rawW;
        double vidH = (currentVideoRotation == 90 || currentVideoRotation == 270) ? rawW : rawH;
        double videoRatio = vidW / vidH;

        int targetW = parentW;
        int targetH = parentH;

        switch (currentAspectRatio) {
            case FIT: {
                if (parentW / (double) parentH > videoRatio) {
                    targetH = parentH;
                    targetW = (int) Math.round(parentH * videoRatio);
                } else {
                    targetW = parentW;
                    targetH = (int) Math.round(parentW / videoRatio);
                }
                break;
            }
            case STRETCH: {
                targetW = parentW;
                targetH = parentH;
                break;
            }
            case CROP: {
                if (parentW / (double) parentH > videoRatio) {
                    targetW = parentW;
                    targetH = (int) Math.round(parentW / videoRatio);
                } else {
                    targetH = parentH;
                    targetW = (int) Math.round(parentH * videoRatio);
                }
                break;
            }
            case RATIO_16_9: {
                double r = 16.0 / 9.0;
                if (parentW / (double) parentH > r) {
                    targetH = parentH;
                    targetW = (int) Math.round(parentH * r);
                } else {
                    targetW = parentW;
                    targetH = (int) Math.round(parentW / r);
                }
                break;
            }
            case RATIO_4_3: {
                double r = 4.0 / 3.0;
                if (parentW / (double) parentH > r) {
                    targetH = parentH;
                    targetW = (int) Math.round(parentH * r);
                } else {
                    targetW = parentW;
                    targetH = (int) Math.round(parentW / r);
                }
                break;
            }
            case RATIO_21_9: {
                double r = 21.0 / 9.0;
                if (parentW / (double) parentH > r) {
                    targetH = parentH;
                    targetW = (int) Math.round(parentH * r);
                } else {
                    targetW = parentW;
                    targetH = (int) Math.round(parentW / r);
                }
                break;
            }
            case RATIO_9_16: {
                double r = 9.0 / 16.0;
                if (parentW / (double) parentH > r) {
                    targetH = parentH;
                    targetW = (int) Math.round(parentH * r);
                } else {
                    targetW = parentW;
                    targetH = (int) Math.round(parentW / r);
                }
                break;
            }
            case RATIO_3_4: {
                double r = 3.0 / 4.0;
                if (parentW / (double) parentH > r) {
                    targetH = parentH;
                    targetW = (int) Math.round(parentH * r);
                } else {
                    targetW = parentW;
                    targetH = (int) Math.round(parentW / r);
                }
                break;
            }
            case RATIO_1_1: {
                double r = 1.0;
                if (parentW / (double) parentH > r) {
                    targetH = parentH;
                    targetW = (int) Math.round(parentH * r);
                } else {
                    targetW = parentW;
                    targetH = (int) Math.round(parentW / r);
                }
                break;
            }
            case ORIGINAL: {
                targetW = (int) Math.round(vidW);
                targetH = (int) Math.round(vidH);
                break;
            }
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER);
        surfaceView.setLayoutParams(lp);
        android.util.Log.i("DemoActivity", "applyAspectRatio mode=" + currentAspectRatio +
                " target=" + targetW + "x" + targetH + " parent=" + parentW + "x" + parentH);
    }

    private synchronized void initPlayer() {
        playerGeneration++;
        resetSeekAndDragState();
        lastOpenCommandId = -1;
        pendingSeekPositionMs = -1;

        if (player != null) {
            player.release();
            player = null;
        }

        PlayerConfig config = new PlayerConfig.Builder()
                .setProgressIntervalMs(200)
                .setVideoMode(videoMode)
                .setFastSeek(fastSeekEnabled)
                .build();

        player = new Player(this, config, new GenerationListener(playerGeneration));
        lastSeekLatencyMs = -1;
        updateSeekBarEnabled();
        if (currentSurface != null && currentSurface.isValid()) {
            player.setVideoSurface(currentSurface);
        }
    }

    private void rebuildPlayerWithCurrentPosition() {
        long resumePos = 0;
        boolean resumePlay = true;
        if (player != null) {
            PlayerSnapshot snapshot = player.getSnapshot();
            resumePos = snapshot.positionMs;
            resumePlay = snapshot.state == PlayerSnapshot.State.PLAYING;
            player.stop();
        }
        initPlayer();
        openCurrentMedia(resumePos, resumePlay);
    }

    private void selectMediaPath(String path) {
        currentFilePath = path;
        currentContentUri = path.startsWith("content://") ? Uri.parse(path) : null;
    }

    private void openCurrentMedia(long startPositionMs, boolean playWhenReady) {
        if (currentContentUri != null) {
            openMediaUri(currentContentUri, "已选视频", startPositionMs, playWhenReady);
        } else {
            openMedia(currentFilePath, startPositionMs, playWhenReady);
        }
    }

    private void openMedia(String filePath, long startPositionMs, boolean playWhenReady) {
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(this, "文件不存在或无读取权限: " + file.getName(), Toast.LENGTH_LONG).show();
            return;
        }

        try {
            resetSeekAndDragState();
            loadingProgress.setVisibility(View.VISIBLE);
            pendingSeekPositionMs = startPositionMs;
            lastOpenCommandId = player.open(MediaSource.fromFile(file));
            lastSeekLatencyMs = -1;
            updateSeekBarEnabled();
            if (playWhenReady) {
                player.play();
            }
        } catch (IOException e) {
            loadingProgress.setVisibility(View.GONE);
            pendingSeekPositionMs = -1;
            Toast.makeText(this, "打开媒体失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openMediaUri(Uri uri, String title, long startPositionMs, boolean playWhenReady) {
        try {
            resetSeekAndDragState();
            loadingProgress.setVisibility(View.VISIBLE);
            pendingSeekPositionMs = startPositionMs;
            lastOpenCommandId = player.open(MediaSource.fromUri(uri));
            lastSeekLatencyMs = -1;
            updateSeekBarEnabled();
            if (playWhenReady) {
                player.play();
            }
            Toast.makeText(this, "正在播放: " + title, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            loadingProgress.setVisibility(View.GONE);
            pendingSeekPositionMs = -1;
            Toast.makeText(this, "打开媒体URI失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openLocalFile(File file, long startPositionMs, boolean playWhenReady) {
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(this, "文件不可读: " + file.getName(), Toast.LENGTH_SHORT).show();
            return;
        }
        currentContentUri = null;
        currentFilePath = file.getAbsolutePath();
        editPath.setText(currentFilePath);
        openMedia(currentFilePath, startPositionMs, playWhenReady);
    }

    // --- 本机文件选择菜单与内置浏览器 ---

    private void showFilePickerMenu() {
        resetControlsTimeout();
        String[] options = {
                "系统文件管理器 (SAF / 选择任意视频)",
                "浏览内置存储 (/sdcard/)",
                "常见下载目录 (/sdcard/Download/)",
                "系统影视目录 (/sdcard/Movies/)",
                "相机拍摄目录 (/sdcard/DCIM/)",
                "预置基准样本 (V01.mp4)"
        };
        new AlertDialog.Builder(this)
                .setTitle("选择本机媒体文件")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            launchSystemFilePicker();
                            break;
                        case 1:
                            showStorageBrowserDialog(Environment.getExternalStorageDirectory());
                            break;
                        case 2:
                            showStorageBrowserDialog(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
                            break;
                        case 3:
                            showStorageBrowserDialog(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
                            break;
                        case 4:
                            showStorageBrowserDialog(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
                            break;
                        case 5:
                            openLocalFile(new File(DEFAULT_SAMPLE_PATH), 0, true);
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchSystemFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择视频文件"), REQUEST_CODE_PICK_FILE);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("*/*");
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(fallback, REQUEST_CODE_PICK_FILE);
            } catch (Exception ex) {
                Toast.makeText(this, "未找到系统文件管理器，正在打开内置文件浏览器", Toast.LENGTH_SHORT).show();
                showStorageBrowserDialog(Environment.getExternalStorageDirectory());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                handleSelectedUri(uri);
            }
        }
    }

    private void handleSelectedUri(Uri uri) {
        String displayName = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {}

        editPath.setText(uri.toString());
        currentContentUri = uri;
        currentFilePath = uri.toString();
        openMediaUri(uri, displayName != null ? displayName : "已选视频", 0, true);
    }

    private void showStorageBrowserDialog(File dir) {
        if (dir == null || !dir.exists()) {
            dir = Environment.getExternalStorageDirectory();
        }
        final File currentDir = dir;
        File[] files = currentDir.listFiles();
        List<File> fileList = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    fileList.add(f);
                } else if (isMediaFile(f.getName())) {
                    fileList.add(f);
                }
            }
            Collections.sort(fileList, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            });
        }

        List<String> displayNames = new ArrayList<>();
        if (currentDir.getParentFile() != null && !currentDir.equals(Environment.getExternalStorageDirectory())) {
            displayNames.add("📁 .. (返回上一级)");
        }
        for (File f : fileList) {
            displayNames.add((f.isDirectory() ? "📁 " : "🎬 ") + f.getName());
        }

        if (displayNames.isEmpty()) {
            displayNames.add("(此目录下没有媒体文件)");
        }

        new AlertDialog.Builder(this)
                .setTitle("浏览: " + currentDir.getName())
                .setItems(displayNames.toArray(new String[0]), (dialog, which) -> {
                    String selected = displayNames.get(which);
                    if (selected.contains(".. (返回上一级)")) {
                        showStorageBrowserDialog(currentDir.getParentFile());
                    } else if (!selected.startsWith("(")) {
                        int fileIndex = which - (displayNames.get(0).contains(".. (返回上一级)") ? 1 : 0);
                        if (fileIndex >= 0 && fileIndex < fileList.size()) {
                            File target = fileList.get(fileIndex);
                            if (target.isDirectory()) {
                                showStorageBrowserDialog(target);
                            } else {
                                openLocalFile(target, 0, true);
                            }
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static boolean isMediaFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".flv") ||
                lower.endsWith(".ts") || lower.endsWith(".mov") || lower.endsWith(".avi") ||
                lower.endsWith(".webm") || lower.endsWith(".m4v") || lower.endsWith(".wmv") ||
                lower.endsWith(".asf") || lower.endsWith(".mp3") || lower.endsWith(".aac") ||
                lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                lower.endsWith(".m4a") || lower.endsWith(".wma") || lower.endsWith(".3gp") ||
                lower.endsWith(".mpg") || lower.endsWith(".mpeg");
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
            toggleControlsVisibility();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void toggleControlsVisibility() {
        setControlsVisibility(!controlsVisible);
    }

    private void setControlsVisibility(boolean visible) {
        controlsVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(visibility);
        bottomBar.setVisibility(visibility);

        mainHandler.removeCallbacks(hideControlsTask);
        if (visible && player != null && player.getSnapshot().state == PlayerSnapshot.State.PLAYING) {
            mainHandler.postDelayed(hideControlsTask, CONTROLS_TIMEOUT_MS);
        }
    }

    private void resetControlsTimeout() {
        mainHandler.removeCallbacks(hideControlsTask);
        if (controlsVisible && player != null && player.getSnapshot().state == PlayerSnapshot.State.PLAYING) {
            mainHandler.postDelayed(hideControlsTask, CONTROLS_TIMEOUT_MS);
        }
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long sec = totalSec % 60;
        long min = (totalSec / 60) % 60;
        long hour = totalSec / 3600;
        if (hour > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", hour, min, sec);
        } else {
            return String.format(Locale.US, "%02d:%02d", min, sec);
        }
    }

    private double getProcessPssMb() {
        if (++pssRefreshCounter % 5 == 0 || lastProcessPssMb == 0.0) {
            try {
                Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
                Debug.getMemoryInfo(memoryInfo);
                lastProcessPssMb = memoryInfo.getTotalPss() / 1024.0;
            } catch (Exception e) {
                lastProcessPssMb = 0.0;
            }
        }
        return lastProcessPssMb;
    }

    // --- SurfaceHolder.Callback 实现 ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        currentSurface = holder.getSurface();
        if (player != null && currentSurface != null && currentSurface.isValid()) {
            player.setVideoSurface(currentSurface);
        }
        if (autoOpenPending) {
            autoOpenPending = false;
            File defaultFile = new File(currentFilePath);
            if (currentContentUri != null || (defaultFile.exists() && defaultFile.canRead())) {
                openCurrentMedia(0, true);
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Surface 尺寸变更由底层 MediaCodec / EGL 自动适配
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // SDK 契约：必须在 surfaceDestroyed 返回前同步解绑并等待旧帧归还
        if (player != null && holder.getSurface() != null) {
            player.detachVideoSurface(holder.getSurface());
        }
        currentSurface = null;
    }

    // --- PlayerListener 实现 ---

    @Override
    public void onSnapshot(PlayerSnapshot snapshot) {
        // 1. 状态与加载指示
        switch (snapshot.state) {
            case PREPARING:
                loadingProgress.setVisibility(View.VISIBLE);
                btnPlayPause.setText("准备中");
                break;
            case PLAYING:
                loadingProgress.setVisibility(View.GONE);
                btnPlayPause.setText("暂停");
                break;
            case PAUSED:
            case READY:
            case IDLE:
                loadingProgress.setVisibility(View.GONE);
                btnPlayPause.setText("播放");
                break;
            case ENDED:
                loadingProgress.setVisibility(View.GONE);
                btnPlayPause.setText("重播");
                break;
            case ERROR:
                loadingProgress.setVisibility(View.GONE);
                btnPlayPause.setText("错误");
                break;
            default:
                break;
        }

        // 2. 进度与总时长
        if (snapshot.media != null && snapshot.media.durationMs != null) {
            currentDurationMs = snapshot.media.durationMs;
            tvDuration.setText(formatTime(currentDurationMs));
        } else {
            currentDurationMs = 0;
            tvDuration.setText("00:00");
        }
        updateSeekBarEnabled();

        if (!isDraggingSeek && !isSeekInFlight && pendingDragSeekMs < 0) {
            tvCurrentTime.setText(formatTime(snapshot.positionMs));
            if (currentDurationMs > 0) {
                int progress = (int) Math.round(snapshot.positionMs * 1000.0 / currentDurationMs);
                seekBar.setProgress(Math.min(1000, Math.max(0, progress)));
            } else {
                seekBar.setProgress(0);
            }
        }

        // 3. 视频轨道与画幅尺寸监测
        if (snapshot.videoTrack != null) {
            TrackInfo v = snapshot.videoTrack;
            if (v.width != currentVideoWidth || v.height != currentVideoHeight ||
                    v.rotationDegrees != currentVideoRotation) {
                currentVideoWidth = v.width;
                currentVideoHeight = v.height;
                currentVideoRotation = v.rotationDegrees;
                currentVideoSar = (v.pixelAspectNumerator > 0 && v.pixelAspectDenominator > 0)
                        ? (double) v.pixelAspectNumerator / v.pixelAspectDenominator : 1.0;
                applyAspectRatio();
            }
        }

        // 4. 实时诊断 HUD 刷新
        updateDiagnosticsHud(snapshot);
    }

    private void updateDiagnosticsHud(PlayerSnapshot snapshot) {
        if (hudContainer.getVisibility() != View.VISIBLE) return;

        TrackInfo video = snapshot.videoTrack;
        String resStr = video != null ? (video.width + "x" + video.height + " (" + video.codec + ") | " + currentAspectRatio.label) : "-";
        PlaybackStats stats = snapshot.stats;

        long submitted = stats.videoFramesSubmitted;
        long dropped = stats.videoFramesDropped;
        long totalFrames = submitted + dropped;
        double dropRate = totalFrames > 0 ? (dropped * 100.0 / totalFrames) : 0.0;

        String seekStr = lastSeekLatencyMs >= 0 ? (lastSeekLatencyMs + " ms (" + (lastSuccessfulSeekExact ? "ExactSeek" : "FastSeek") + ")") : "-";
        double pssMb = getProcessPssMb();

        String backendDisplay;
        if (snapshot.videoBackend == null || snapshot.videoBackend.isEmpty()) {
            backendDisplay = "未激活";
        } else if (snapshot.videoBackend.contains("ffmpeg")) {
            backendDisplay = "FFmpeg 软件解码";
        } else {
            backendDisplay = "平台硬件解码 (MediaCodec Direct)";
        }

        String info = String.format(Locale.US,
                "状态: %s | 进度: %s / %s\n" +
                "分辨率: %s\n" +
                "解码后端: %s\n" +
                "视频渲染: %d 帧 | 丢帧: %d (%.2f%%)\n" +
                "音频消费: %d 帧\n" +
                "上次Seek耗时: %s\n" +
                "进程物理内存 (PSS): %.2f MB",
                snapshot.state.name(),
                formatTime(snapshot.positionMs),
                formatTime(currentDurationMs),
                resStr,
                backendDisplay,
                submitted,
                dropped,
                dropRate,
                stats.audioFramesConsumed,
                seekStr,
                pssMb
        );

        hudText.setText(info);
    }

    @Override
    public void onCommandResult(CommandResult result) {
        android.util.Log.i("DemoActivity", "onCommandResult id=" + result.requestId + " status=" + result.status + " actualPos=" + result.actualPositionMs + " lastSeekId=" + lastSeekCommandId);
        if (result.requestId == lastOpenCommandId) {
            lastOpenCommandId = -1;
            updateSeekBarEnabled();
            if (result.status == CommandResult.Status.SUCCESS && pendingSeekPositionMs > 0) {
                long target = pendingSeekPositionMs;
                pendingSeekPositionMs = -1;
                executeSeek(target);
            } else if (result.status != CommandResult.Status.SUCCESS) {
                pendingSeekPositionMs = -1;
                loadingProgress.setVisibility(View.GONE);
            }
        } else if (result.requestId == lastSeekCommandId) {
            mainHandler.removeCallbacks(seekWatchdogTask);
            isSeekInFlight = false;
            lastSeekCommandId = -1;
            if (result.status == CommandResult.Status.SUCCESS && result.actualPositionMs != null) {
                lastSeekLatencyMs = (SystemClock.elapsedRealtimeNanos() - lastSeekStartTimeNs) / 1_000_000;
                lastSuccessfulSeekExact = seekRequestExact;
                if (player != null) updateDiagnosticsHud(player.getSnapshot());
            }
            finishDragSeek();
        }
    }

    @Override
    public void onError(PlayerError error, PlayerSnapshot snapshot) {
        if (error.scope == PlayerError.Scope.SESSION) {
            pendingSeekPositionMs = -1;
        }
        String hint = "";
        if ("hardwareMime".equals(error.stage) || (error.code == PlayerError.Code.DECODER_INIT && videoMode == PlayerConfig.VideoMode.HARDWARE_ONLY)) {
            hint = "\n（当前为纯硬解模式，该媒体无硬件解码器，请切为AUTO或纯软解）";
        }
        Toast.makeText(this, "播放错误: [" + error.code + "] " + error.stage + hint, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onFirstVideoSubmission(PlayerSnapshot snapshot) {
        loadingProgress.setVisibility(View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        updateSeekBarEnabled();
        if (player != null && currentSurface != null && currentSurface.isValid()) {
            player.setVideoSurface(currentSurface);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityStarted = false;
        resetSeekAndDragState();
        updateSeekBarEnabled();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        playerGeneration++;
        resetSeekAndDragState();
        mainHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private final class GenerationListener implements PlayerListener {
        private final int generation;

        GenerationListener(int generation) {
            this.generation = generation;
        }

        private boolean isStale() {
            return generation != playerGeneration;
        }

        @Override
        public void onSnapshot(PlayerSnapshot snapshot) {
            if (isStale()) return;
            DemoActivity.this.onSnapshot(snapshot);
        }

        @Override
        public void onCommandResult(CommandResult result) {
            if (isStale()) return;
            DemoActivity.this.onCommandResult(result);
        }

        @Override
        public void onError(PlayerError error, PlayerSnapshot snapshot) {
            if (isStale()) return;
            DemoActivity.this.onError(error, snapshot);
        }

        @Override
        public void onFirstAudioConsumption(PlayerSnapshot snapshot) {
            if (isStale()) return;
            DemoActivity.this.onFirstAudioConsumption(snapshot);
        }

        @Override
        public void onFirstVideoSubmission(PlayerSnapshot snapshot) {
            if (isStale()) return;
            DemoActivity.this.onFirstVideoSubmission(snapshot);
        }
    }
}
