package com.cn.media.playback;

import android.content.ContentResolver;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Content Provider 异步打开管理与超时隔离协调器。
 * <p>
 * 解决恶意或无响应的第三方 Content Provider 在进程间 Binder 调用取消后依然长期卡死调用方线程的问题。
 * 限制同一时间段内最多发起 2 个并发打开请求，超出的新请求立即抛出 {@link Busy}；
 * 并在实例关闭或重置时通过栅栏（Barrier）确保迟到返回的文件描述符能被即时回收。
 */
final class ProviderOpener {
    /** 并发打开请求超限异常。 */
    static final class Busy extends IOException {}
    private final Set<Request> active = new HashSet<>();

    /**
     * 在专用提供方线程上启动异步打开任务。
     *
     * @param source   目标媒体源
     * @param resolver Android 内容解析器
     * @param signal   取消信号量
     * @return 请求句柄对象
     * @throws Busy 当正在执行的 Provider 打开请求已达上限时抛出
     */
    synchronized Request start(MediaSource source, ContentResolver resolver, CancellationSignal signal) throws Busy {
        if (active.size() >= 2) throw new Busy();
        Request request = new Request();
        active.add(request);
        new Thread(() -> {
            InputRange range = null;
            Exception failure = null;
            try {
                range = source.openDeferred(resolver, signal);
            } catch (IOException | RuntimeException e) {
                failure = e;
            } finally {
                request.finish(range, failure);
                finished(request);
            }
        }, "playback-provider").start();
        return request;
    }

    /** 放弃当前所有未决的打开请求。 */
    synchronized void abandonAll() {
        for (Request request : new ArrayList<>(active)) {
            request.abandon();
        }
    }

    /**
     * 设置同步屏障，等待在此调用前已发起的全部未决打开请求完全退出后执行操作。
     *
     * @param timeoutMs 通知等待超时的毫秒数
     * @param scheduler 超时调度器
     * @param onTimeout 等待超时后的通知
     * @param onComplete 全部请求退出后的清理任务，即使此前已经超时也会执行
     */
    synchronized void afterOutstanding(long timeoutMs, ScheduledExecutorService scheduler,
                                       Runnable onTimeout, Runnable onComplete) {
        if (active.isEmpty()) { onComplete.run(); return; }
        AtomicInteger remaining = new AtomicInteger(active.size());
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            if (remaining.get() != 0) onTimeout.run();
        }, timeoutMs, TimeUnit.MILLISECONDS);
        for (Request request : active) request.barriers.add(() -> {
            if (remaining.decrementAndGet() == 0) {
                timeout.cancel(false);
                onComplete.run();
            }
        });
    }

    private synchronized void finished(Request request) {
        active.remove(request);
        for (Runnable action : request.barriers) action.run();
    }

    /**
     * 单个异步打开请求句柄类。
     */
    static final class Request {
        final ArrayList<Runnable> barriers = new ArrayList<>();
        private boolean done, abandoned;
        private InputRange result;
        private Exception error;

        /** 放弃当前请求结果，若后续迟到返回则就地销毁 FD。 */
        synchronized void abandon() {
            abandoned = true;
            closeResult();
            notifyAll();
        }

        /** 完成当前请求，记录结果或异常。 */
        synchronized void finish(InputRange range, Exception failure) {
            result = range;
            error = failure;
            done = true;
            if (abandoned) closeResult();
            notifyAll();
        }

        private void closeResult() {
            if (result == null) return;
            try { result.close(); } catch (IOException ignored) { }
            result = null;
        }

        /**
         * 同步等待并获取解析出的输入区间描述符。
         *
         * @return 输入区间对象
         * @throws IOException 当打开失败或被取消时抛出
         */
        synchronized InputRange take() throws IOException {
            while (!abandoned && !done) {
                try { wait(); }
                catch (InterruptedException e) {
                    abandon();
                    Thread.currentThread().interrupt();
                    throw new OperationCanceledException();
                }
            }
            if (abandoned) throw new OperationCanceledException();
            if (error instanceof IOException) throw (IOException)error;
            if (error instanceof RuntimeException) throw (RuntimeException)error;
            InputRange range = result;
            result = null;
            return range;
        }
    }
}
