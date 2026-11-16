package com.cn.media.playback;

import org.jetbrains.annotations.NotNull;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;

/**
 * 媒体输入源描述类。
 * <p>
 * 支持三种接入形式：
 * <ul>
 *     <li>本地标准文件：通过 {@link #fromFile(File)} 构建；</li>
 *     <li>Content Provider URI：通过 {@link #fromUri(Uri)} 构建（支持 content:// 协议）；</li>
 *     <li>带偏移与长度的文件描述符区间：通过 {@link #fromDescriptor(FileDescriptor, long, long)} 构建。</li>
 * </ul>
 * <b>所有权契约：</b>若使用文件描述符形式，宿主应保持传入的描述符在 {@link Player#open(MediaSource)} 调用期间处于有效打开状态，
 * SDK 在 open 命令准入阶段会立即通过系统级 {@code dup} 复制出独立私有副本，随后宿主可根据自身需要随时关闭原描述符。
 */
public final class MediaSource {
    /** 标识长度未知（受实际物理文件大小或流结束符 EOF 约束）。 */
    public static final long UNKNOWN_LENGTH = -1;
    private final File file;
    private final Uri uri;
    private final FileDescriptor descriptor;
    private final long offset;
    private final long length;

    private MediaSource(File file, Uri uri, FileDescriptor descriptor, long offset, long length) {
        this.file = file;
        this.uri = uri;
        this.descriptor = descriptor;
        this.offset = offset;
        this.length = length;
    }

    /**
     * 通过本地文件创建媒体源。
     *
     * @param file 目标本地可读文件
     * @return 媒体源实例
     */
    public static @NotNull MediaSource fromFile(@NotNull File file) {
        return new MediaSource(Objects.requireNonNull(file), null, null, 0, UNKNOWN_LENGTH);
    }

    /**
     * 通过 Android Content Provider URI 创建媒体源。
     *
     * @param uri content:// 协议 URI，需确保宿主已获取只读权限
     * @return 媒体源实例
     * @throws IllegalArgumentException 当 URI 非 content 协议时抛出
     */
    public static @NotNull MediaSource fromUri(@NotNull Uri uri) {
        if (!ContentResolver.SCHEME_CONTENT.equals(Objects.requireNonNull(uri).getScheme())) {
            throw new IllegalArgumentException("Expected a content URI");
        }
        return new MediaSource(null, uri, null, 0, UNKNOWN_LENGTH);
    }

    /**
     * 通过文件描述符及指定的有效字节区间创建媒体源。
     *
     * @param descriptor 原始文件描述符（如从 APK assets、压缩包或套接字管道取得）
     * @param offset     媒体数据在该描述符中的逻辑起始字节偏移（从 0 起算）
     * @param length     媒体数据的有效字节长度，若读取至流结尾则传入 {@link #UNKNOWN_LENGTH}
     * @return 媒体源实例
     */
    public static @NotNull MediaSource fromDescriptor(@NotNull FileDescriptor descriptor, long offset, long length) {
        InputRange.validate(offset, length);
        return new MediaSource(null, null, Objects.requireNonNull(descriptor), offset, length);
    }

    // Run during synchronous command admission, before superseding the old session.
    boolean usesProvider() { return uri != null; }

    InputRange acquireDescriptor() throws IOException {
        return descriptor == null ? null :
                InputRange.take(ParcelFileDescriptor.dup(descriptor), offset, length);
    }

    // File/provider access is deferred to the preparation worker.
    InputRange openDeferred(ContentResolver resolver, CancellationSignal cancel) throws IOException {
        cancel.throwIfCanceled();
        if (file != null) {
            return InputRange.take(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                    0, UNKNOWN_LENGTH);
        }
        if (uri == null) throw new IllegalStateException("Descriptor must be acquired on admission");
        try (AssetFileDescriptor asset = resolver.openAssetFileDescriptor(uri, "r", cancel)) {
            cancel.throwIfCanceled();
            if (asset == null) throw new IOException("Provider returned no descriptor");
            return InputRange.take(ParcelFileDescriptor.dup(asset.getFileDescriptor()),
                    asset.getStartOffset(), asset.getDeclaredLength());
        }
    }
}
