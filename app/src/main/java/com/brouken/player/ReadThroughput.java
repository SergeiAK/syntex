package com.brouken.player;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * How fast the media source actually delivers, for the statistics card: bytes read over the time spent
 * waiting inside {@code read()}, the last few seconds of it.
 *
 * <p>Media3's own estimate does not answer this for a file. DefaultBandwidthMeter adds a sample only when
 * a transfer ends, and a progressive file — SMB, a local http server — is one transfer from the start to
 * the next seek, so the card had nothing to show however long the file played. And the sample it does
 * take is bytes over wall-clock time, idle spells with a full buffer included, which reads as a slow link
 * exactly when the link is keeping up. Time inside read() is the time the loader was held by the source;
 * the time it was held by a full buffer is left out, so the figure is what the source can do.
 */
final class ReadThroughput {

    /** Long enough to ride over one slow request, short enough to follow a link that has just dropped. */
    static final long WINDOW_MS = 5_000;
    private static final long BUCKET_MS = 250;

    /** {start ms, bytes, ns spent reading}, one per quarter second with reads in it. */
    private final ArrayDeque<long[]> buckets = new ArrayDeque<>();
    private long lastBitrate;

    synchronized void reset() {
        buckets.clear();
        lastBitrate = 0;
    }

    /** One read: how many bytes it brought and how long the caller waited for them. */
    synchronized void add(long nowMs, int bytes, long busyNs) {
        if (bytes <= 0) {
            return;
        }
        final long[] last = buckets.peekLast();
        if (last != null && nowMs - last[0] < BUCKET_MS) {
            last[1] += bytes;
            last[2] += busyNs;
        } else {
            buckets.addLast(new long[]{nowMs, bytes, busyNs});
        }
        drop(nowMs);
        // Here and not on asking: the card may well be closed while the reads go on, and the figure it
        // shows on opening over a full buffer is the one these reads left.
        long windowBytes = 0;
        long windowBusyNs = 0;
        for (long[] bucket : buckets) {
            windowBytes += bucket[1];
            windowBusyNs += bucket[2];
        }
        // A window of reads served from memory alone (a refilled SMB window, a cache) has no wait in it
        // to divide by; it says nothing about the link either way.
        if (windowBusyNs >= 1_000_000) {
            lastBitrate = (long) (windowBytes * 8e9 / windowBusyNs);
        }
    }

    /**
     * Bits a second over the {@link #WINDOW_MS} of reads up to the latest. While nothing is being read —
     * the buffer is full and the loader waits — the figure from the last reads stands: the link did not get
     * slower for being left alone. 0 before the first read.
     */
    synchronized long bitrate() {
        return lastBitrate;
    }

    private void drop(long nowMs) {
        while (!buckets.isEmpty() && nowMs - buckets.peekFirst()[0] > WINDOW_MS) {
            buckets.removeFirst();
        }
    }

    /** Puts the meter on the media reads; subtitles and hashes go round it. */
    static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstream;
        private final ReadThroughput meter;

        Factory(DataSource.Factory upstream, ReadThroughput meter) {
            this.upstream = upstream;
            this.meter = meter;
        }

        @Override
        public DataSource createDataSource() {
            return new Metered(upstream.createDataSource(), meter);
        }
    }

    private static final class Metered implements DataSource {

        private final DataSource upstream;
        private final ReadThroughput meter;

        Metered(DataSource upstream, ReadThroughput meter) {
            this.upstream = upstream;
            this.meter = meter;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            return upstream.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            final long start = System.nanoTime();
            final int read = upstream.read(buffer, offset, length);
            meter.add(SystemClock.elapsedRealtime(), read, System.nanoTime() - start);
            return read;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return upstream.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return upstream.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }
    }
}
