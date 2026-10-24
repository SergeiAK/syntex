package com.brouken.player;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A data source that gives up after a number of bytes, counted across every open of one factory. A
 * still taken while a film plays reads over the same network the film does, and a file without an
 * index is walked from its start to the frame wanted: the cap ends that walk, as NetworkThumbs.MAX_BYTES
 * ends it for the browser's stills.
 */
final class CappedDataSource implements DataSource {

    /** One budget per factory: a still is one factory, whatever number of opens it takes. */
    static DataSource.Factory factory(final DataSource.Factory upstream, final long capBytes) {
        final AtomicLong spent = new AtomicLong();
        return () -> new CappedDataSource(upstream.createDataSource(), spent, capBytes);
    }

    private final DataSource upstream;
    private final AtomicLong spent;
    private final long capBytes;

    private CappedDataSource(final DataSource upstream, final AtomicLong spent, final long capBytes) {
        this.upstream = upstream;
        this.spent = spent;
        this.capBytes = capBytes;
    }

    @Override
    public void addTransferListener(@NonNull final TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull final DataSpec dataSpec) throws IOException {
        if (spent.get() >= capBytes) {
            throw new IOException("read cap of " + (capBytes >> 20) + " MiB reached");
        }
        return upstream.open(dataSpec);
    }

    @Override
    public int read(@NonNull final byte[] buffer, final int offset, final int length) throws IOException {
        if (spent.get() >= capBytes) {
            throw new IOException("read cap of " + (capBytes >> 20) + " MiB reached");
        }
        final int read = upstream.read(buffer, offset, length);
        if (read > 0) {
            spent.addAndGet(read);
        }
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }
}
