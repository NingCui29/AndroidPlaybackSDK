package com.cn.media.playback;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.Closeable;
import java.io.IOException;

/**
 * 输入文件描述符及其有效字节区间封装类。
 * <p>
 * 独占持有一个 {@link ParcelFileDescriptor} 的生命周期。偏移量与区间长度以字节为单位，
 * 负责在 Native AVIO 读取层之前实施严格的越界拦截与顺序流非法非零偏移校验。
 */
final class InputRange implements Closeable {
    final ParcelFileDescriptor fd;
    final long start;
    final long length;
    final boolean seekable;

    private InputRange(ParcelFileDescriptor fd, long start, long length, boolean seekable) {
        this.fd = fd;
        this.start = start;
        this.length = length;
        this.seekable = seekable;
    }

    static void validate(long start, long length) {
        if (start < 0 || length < -1 || (length >= 0 && start > Long.MAX_VALUE - length)) {
            throw new IllegalArgumentException("Invalid input range");
        }
    }

    static InputRange take(ParcelFileDescriptor fd, long start, long length) throws IOException {
        boolean transferred = false;
        try {
            validate(start, length);
            StructStat stat = Os.fstat(fd.getFileDescriptor());
            boolean seekable;
            try {
                Os.lseek(fd.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
                seekable = true;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.ESPIPE) throw e;
                seekable = false;
            }
            if (!seekable && start != 0) throw new IllegalArgumentException("Sequential input requires zero offset");
            if (OsConstants.S_ISREG(stat.st_mode)) {
                if (start > stat.st_size || length > stat.st_size - start) {
                    throw new IllegalArgumentException("Input range exceeds file size");
                }
                if (length == -1) length = stat.st_size - start;
            }
            InputRange input = new InputRange(fd, start, length, seekable);
            transferred = true;
            return input;
        } catch (ErrnoException e) {
            throw new IOException("Input descriptor inspection failed", e);
        } finally {
            if (!transferred) fd.close();
        }
    }

    @Override public void close() throws IOException {
        fd.close();
    }
}
