package com.cn.media.playback;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * 监听回调串行派发队列类。
 * <p>
 * 保证任意时刻至多仅有一个排空任务（drain runnable）被提交给目标线程池执行，
 * 防止宿主自定义的并发 Executor 破坏监听回调事件的单实例串行有序性。
 * 同时对高频连续的进度通知进行节流合并（若队尾已存在未消费的进度通知，则丢弃旧的以保留最新进度）。
 */
final class CallbackQueue {
    private static final int MAX_RETRIES = 3;
    /** 队列项封装。 */
    private static final class Entry {
        /** 是否为周期性播放进度快照回调。 */
        final boolean progress;
        /** 具体要执行的回调任务。 */
        final Runnable callback;
        Entry(boolean progress, Runnable callback) { this.progress = progress; this.callback = callback; }
    }
    private final Executor executor;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private boolean draining;
    private int retries;

    /**
     * 构造回调队列。
     *
     * @param executor 目标回调调度执行器
     */
    CallbackQueue(Executor executor) { this.executor = executor; }

    /**
     * 将回调任务加入队列，并在必要时调度排空执行。
     *
     * @param progress 是否为周期性进度快照
     * @param callback 待执行的回调闭包
     */
    void add(boolean progress, Runnable callback) {
        synchronized (queue) {
            if (progress && !queue.isEmpty() && queue.getLast().progress) queue.removeLast();
            queue.addLast(new Entry(progress, callback));
            if (draining) return;
            draining = true;
            retries = 0;
        }
        dispatch();
    }

    private void dispatch() {
        try {
            executor.execute(this::drain);
        } catch (RuntimeException rejected) {
            android.util.Log.w("PlaybackSDK", "Callback executor rejected execution: " + rejected.getMessage());
            synchronized (queue) {
                if (retries < MAX_RETRIES &&
                        new Handler(Looper.getMainLooper()).postDelayed(this::dispatch, 50L << retries++)) {
                    return;
                }
                draining = false;
            }
        }
    }

    /** 串行循环排空队内所有待执行的回调任务。 */
    private void drain() {
        for (;;) {
            Entry entry;
            synchronized (queue) {
                entry = queue.pollFirst();
                if (entry == null) { draining = false; return; }
            }
            try {
                entry.callback.run();
            } catch (Throwable failure) {
                android.util.Log.e("PlaybackSDK", "Listener failed: " + failure.getClass().getSimpleName());
            }
        }
    }
}
