package com.cn.media.playback;

import android.content.Context;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Arrays;

/** I01: compare bytes through the three production admission paths, including FD ownership. */
final class InputRangeChecks {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static byte[] digest(InputRange range) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[256 * 1024];
        for (long at = 0; at < range.length;) {
            int n = Os.pread(range.fd.getFileDescriptor(), block, 0,
                    (int)Math.min(block.length, range.length - at), range.start + at);
            check(n > 0, "Premature EOF at " + at);
            sha.update(block, 0, n);
            at += n;
        }
        return sha.digest();
    }

    static void run(Context context, Bundle report) throws Exception {
        File sample = new File(context.getCacheDir(), "V01.mp4");
        byte[] expected;
        CancellationSignal cancel = new CancellationSignal();
        try (InputRange file = MediaSource.fromFile(sample).openDeferred(context.getContentResolver(), cancel)) {
            check(file.seekable && file.start == 0 && file.length == sample.length(), "File bounds");
            expected = digest(file);
        }
        File wrapper = File.createTempFile("V01-range", ".mp4", context.getCacheDir());
        try {
            try (FileInputStream src = new FileInputStream(sample); RandomAccessFile dest = new RandomAccessFile(wrapper, "rw")) {
                dest.writeInt(0x115599aa);
                byte[] buf = new byte[256 * 1024];
                for (int n; (n = src.read(buf)) >= 0;) dest.write(buf, 0, n);
                dest.writeInt(0x77553311);
            }
            try (InputRange uri = MediaSource.fromUri(FixtureProvider.uri("range", wrapper.getName()))
                    .openDeferred(context.getContentResolver(), cancel)) {
                check(uri.start == 4 && uri.length == sample.length() && Arrays.equals(digest(uri), expected), "URI digest");
            }
            InputRange copied;
            FileDescriptor closed;
            try (ParcelFileDescriptor host = ParcelFileDescriptor.open(wrapper, ParcelFileDescriptor.MODE_READ_ONLY)) {
                closed = host.getFileDescriptor();
                Os.lseek(closed, 7, OsConstants.SEEK_SET);
                try (InputRange unknown = MediaSource.fromDescriptor(closed, 4, -1).acquireDescriptor();
                     InputRange empty = MediaSource.fromDescriptor(closed, wrapper.length(), 0).acquireDescriptor()) {
                    check(unknown.length == wrapper.length()-4 && empty.length == 0, "Unknown or empty length");
                }
                for (long[] bad : new long[][]{{-1,0},{0,-2},{Long.MAX_VALUE,1},{wrapper.length(),1}}) {
                    try (InputRange ignored = MediaSource.fromDescriptor(closed,bad[0],bad[1]).acquireDescriptor()) {
                        throw new AssertionError("Invalid range accepted");
                    } catch (IllegalArgumentException expectedFailure) { /* rejected synchronously */ }
                }
                copied = MediaSource.fromDescriptor(closed, 4, sample.length()).acquireDescriptor();
                check(Os.lseek(closed, 0, OsConstants.SEEK_CUR) == 7, "Host cursor moved");
            }
            try (InputRange input = copied) {
                check(Arrays.equals(digest(input), expected), "Closed host FD affected the SDK copy");
            }
            try (InputRange ignored = MediaSource.fromDescriptor(closed,0,-1).acquireDescriptor()) {
                throw new AssertionError("Closed descriptor accepted");
            } catch (IOException expectedFailure) { /* no SDK descriptor acquired */ }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : expected) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            check(hex.toString().equals("6036a8ea2dbc5767e31b430009313c34213a88e48e5b6ee42957e80ad2276a30"), "V01 manifest mismatch");
            report.putString("range", "PASS file/uri/fd bytes=" + sample.length() + " sha256=" + hex);
        } finally { check(wrapper.delete(), "Fixture cleanup failed"); }
    }
}
