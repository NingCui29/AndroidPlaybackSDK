package com.cn.media.playback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class FixtureProvider extends ContentProvider {
    private static volatile CleanupGate cleanupGate;

    static final class CleanupGate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch allowReturn = new CountDownLatch(1);
        final ParcelFileDescriptor read, write;

        CleanupGate() throws IOException {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            read = pipe[0];
            write = pipe[1];
            cleanupGate = this;
        }

        AssetFileDescriptor open() throws FileNotFoundException {
            entered.countDown();
            try {
                if (allowReturn.await(10, TimeUnit.SECONDS))
                    return new AssetFileDescriptor(read, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new FileNotFoundException("Cleanup fixture was not released");
        }

        @Override public void close() throws IOException {
            cleanupGate = null;
            allowReturn.countDown();
            try { read.close(); } finally { write.close(); }
        }
    }

    static Uri uri(String operation, String file) {
        return new Uri.Builder().scheme("content").authority("com.cn.media.playback.test.fixture")
                .appendPath(operation).appendPath(file).build();
    }
    @Override public boolean onCreate() { return true; }

    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String type, android.os.Bundle options,
            CancellationSignal signal) throws FileNotFoundException {
        return openAssetFile(uri, "r", signal);
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2) throw new FileNotFoundException();
        String operation = uri.getPathSegments().get(0), name = uri.getLastPathSegment();
        if (!name.matches("[VAH][0-9A-Za-z.-]+")) throw new FileNotFoundException();
        if ("cleanup".equals(operation)) {
            CleanupGate gate = cleanupGate;
            if (gate == null) throw new FileNotFoundException("No cleanup fixture");
            return gate.open();
        }
        if ("private".equals(operation))
            throw new SecurityException("Denied " + uri + " " + uri.getQueryParameter("credential"));
        if ("deny".equals(operation)) throw new SecurityException("Fixture denied");
        if ("runtime".equals(operation)) throw new IllegalStateException("Fixture open failed");
        if ("late".equals(operation)) android.os.SystemClock.sleep(1200);
        if ("stall".equals(operation)) {
            CountDownLatch cancelled = new CountDownLatch(1);
            if (signal != null) signal.setOnCancelListener(cancelled::countDown);
            try { cancelled.await(9, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (signal != null) signal.throwIfCanceled();
        }
        File f = new File(getContext().getCacheDir(), name);
        long offset = "range".equals(operation) ? 4 : 0;
        return new AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY),
                offset, f.length() - offset * 2);
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return openAssetFile(uri, mode, null);
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
