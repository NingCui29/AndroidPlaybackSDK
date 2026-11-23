package com.cn.media.playback;

import android.content.ContentResolver;
import android.os.CancellationSignal;
import java.io.IOException;

/**
 * 播放会话协调器。
 * <p>
 * 负责协调单一 I/O 拥有线程上的媒体打开、文件描述符（FD）传递、跨世代渲染纪元（{@link PlaybackOutput}）
 * 的轮替调度，以及取消信号的传播与底层解封装输入流（{@link NativeInput}）的生命周期管理。
 * </p>
 * <p>
 * 设计保证：
 * <ul>
 *     <li>即使在取消信号触发后才返回的文件描述符，也会在当前会话的流转契约中被安全关闭，杜绝句柄泄漏；</li>
 *     <li>对多代渲染周期的切换提供原子级的停机与准入拦截，防止旧任务与新会话交叉运行。</li>
 * </ul>
 * </p>
 */
final class PlaybackSession {
    /** 会话唯一单调递增标识符。 */
    final long id;
    /** 当前会话绑定的媒体输入源规范。 */
    private final MediaSource source;
    /** 预准入检查阶段已经打开的文件范围（若存在）。 */
    private final InputRange admitted;
    /** 用于中断耗时 Provider/FD 打开过程的系统取消信号量。 */
    private final CancellationSignal opening = new CancellationSignal();
    /** 标识会话是否已进入作废/淘汰状态。 */
    private boolean retiring;
    /** 底层 C/C++ 解封装输入通道实例。 */
    private NativeInput input;
    /** 异步 ContentProvider 打开请求的句柄代理。 */
    private ProviderOpener.Request provider;
    /** 当前处于激活状态的渲染输出世代实例。 */
    private PlaybackOutput current;
    /** 已由 I/O 线程准入、尚未完成清理的输出。 */
    private PlaybackOutput rendering;

    /**
     * 创建播放会话实例。
     *
     * @param id 会话递增标识符
     * @param source 媒体输入源规范
     * @param admitted 预准入校验已打开的输入范围描述符，如无则为 {@code null}
     */
    PlaybackSession(long id, MediaSource source, InputRange admitted) {
        this.id = id;
        this.source = source;
        this.admitted = admitted;
    }

    /**
     * 获取并打开底层媒体输入通道。
     * <p>
     * <b>线程约束：</b>仅限 I/O 工作线程调用。<br/>
     * 如果输入源属于 {@code content://} 提供者且配置了异步打开，则委托 {@link ProviderOpener} 执行；
     * 否则直接延迟打开或复用预准入的输入范围。如果在打开过程中会话已被废弃（retiring），将立即关闭资源并返回 {@code null}。
     * </p>
     *
     * @param resolver Android 系统内容解析器，用于跨进程读取媒体数据
     * @param opener ContentProvider 异步开启器管理器
     * @return 初始化完成的底层输入通道实例，若会话已作废则返回 {@code null}
     * @throws IOException 当底层描述符获取或 native 解封装通道构建失败时抛出
     */
    NativeInput acquire(ContentResolver resolver, ProviderOpener opener) throws IOException {
        ProviderOpener.Request pending = null;
        if (source.usesProvider()) {
            synchronized (this) {
                if (retiring) return null;
                pending = provider = opener.start(source, resolver, opening);
            }
        }
        try (InputRange range = pending != null ? pending.take() :
                admitted != null ? admitted : source.openDeferred(resolver, opening)) {
            synchronized (this) {
                if (retiring) return null;
                input = new NativeInput(range);
                return input;
            }
        }
    }

    /**
     * 替换当前激活的渲染输出纪元（Epoch）。
     * <p>
     * 请求旧输出停止，并中断底层 I/O。读写线程退出并清理旧输出后，I/O 线程才能准入新输出。
     * </p>
     *
     * @param output 即将生效的渲染输出纪元，为 {@code null} 时只停止当前输出
     */
    synchronized void replace(PlaybackOutput output) {
        if (current != null) {
            current.requestStop();
            if (input != null) input.cancel();
        }
        current = output;
    }

    /** 外部 Surface 将解绑或更换时，等待已被替换的输出归还在途画面。 */
    synchronized void awaitReplacedOutput() {
        if (rendering != null && rendering != current) rendering.stop();
    }

    /**
     * 探测指定音频流的底层格式与参数。
     *
     * @param stream 音频流索引编号
     * @param position 媒体起始探测位置偏移（微秒）
     * @return 探测结果探针实例；若会话已作废或输入未初始化完成则返回 {@code null}
     */
    synchronized AudioTrackProbe audioProbe(int stream, long position) {
        return retiring || input == null ? null : input.audioProbe(stream, position);
    }

    /**
     * 准入指定的渲染输出纪元进入执行流程。
     * <p>
     * <b>线程约束：</b>由 I/O 所有者在前一个纪元及其所有后台渲染工作线程完全退出后调用。<br/>
     * 重置底层解封装流的取消标记（rearm），使新纪元可以继续读取流数据。
     * </p>
     *
     * @param output 请求准入的渲染输出纪元
     * @return 解除取消拦截的底层输入通道；若会话已废弃或输出不是当前活跃代则返回 {@code null}
     */
    synchronized NativeInput enter(PlaybackOutput output) {
        if (retiring || current != output) return null;
        input.rearm();
        rendering = output;
        return input;
    }

    synchronized void leave(PlaybackOutput output) {
        if (rendering == output) rendering = null;
    }

    /**
     * 作废并退休当前会话。
     * <p>
     * 标记会话状态为 {@code retiring}，放弃挂起的 Provider 打开请求，停止当前渲染纪元，
     * 取消底层 Native 读取任务，并向正在阻塞的系统打开调用发送取消信号。
     * </p>
     */
    void retire() {
        synchronized (this) {
            retiring = true;
            if (provider != null) provider.abandon();
            if (current == null && input != null) input.cancel();
            replace(null);
            awaitReplacedOutput();
        }
        opening.cancel();
    }

    /**
     * 关闭并终结底层输入资源。
     * <p>
     * <b>线程约束：</b>仅限 I/O 线程在完成准备或渲染彻底结束后调用，确保资源无并发竞态释放。
     * </p>
     */
    synchronized void close() {
        if (input != null) { input.close(); input = null; }
        current = null;
        rendering = null;
    }
}
