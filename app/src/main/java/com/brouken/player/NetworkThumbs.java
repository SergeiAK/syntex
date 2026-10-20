package com.brouken.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A still of a video on an SMB share or a WebDAV server, for the browser's rows and tiles.
 *
 * <p>The platform's retriever takes a {@link MediaDataSource}, so the file is not downloaded to look
 * at it: the retriever reads the container's head, jumps to the index and then to the one keyframe it
 * needs, and every one of those reads goes out as a positioned read through the same data sources the
 * player uses - {@link SmbDataSource} with its 1 MiB window, and dav:// rewritten to http with the
 * saved place's credentials.
 *
 * <p>One at a time across the whole app. A television box has a handful of hardware decoders and a
 * list being scrolled would otherwise ask for a dozen frames at once - of 4K HEVC, over the network.
 * Glide keeps what comes back on disk, so each file costs this once.
 *
 * <p>Not while the player is open: the one in progress is cut off when a player screen comes up
 * (its next read fails, and the retriever lets go), and the player still waits for it to be gone
 * before it picks a video decoder. The retriever decodes in the media server with the same hardware decoders the player
 * uses, and on an Amlogic box a VC-1 decoder is exclusive: while a still of a VC-1 file was being
 * taken, the player's own decoder was refused ({@code NO_MEMORY}) and Media3 fell back to the
 * software one - a 4K H.264 slide show (measured on Dune, 0037). A file that gave no frame is not
 * tried again until the app restarts: Glide keeps no failure, and that VC-1 still was taken - and
 * failed - four times in four minutes.
 *
 * <p>Not for a torrent server (a frame there means the server fetching the torrent) and not for a
 * media server, which sends its own artwork.
 */
final class NetworkThumbs {

    private static final ReentrantLock ONE_AT_A_TIME = new ReentrantLock();
    /** Players started and not stopped: a stop of one screen may come after the start of the next. */
    private static final AtomicInteger PLAYERS_OPEN = new AtomicInteger();
    /** How long a player waits for a still in progress; a slow one took 6 s on the Dune. */
    private static final long PLAYER_WAIT_MS = 8000;
    /**
     * What one still may read. A file without an index is walked by the retriever to its end - a
     * 1080p MKV read 121 MB in 33 s, and every still behind it waited (measured on Dune, 0043) -
     * while the heaviest still that came back in 0037 read 40 MB, of a UHD TS.
     */
    static final long MAX_BYTES = 48L << 20;
    private static final Set<String> NO_FRAME = ConcurrentHashMap.newKeySet();
    /** A still was refused or cut off while a player was up: the browser has rows to ask again. */
    private static volatile boolean missed;
    /** The browser's way to ask again, while it is started; see whenPlayerGone. */
    @Nullable
    private static Runnable onPlayerGone;
    /** The source of the still in progress, to be cut off when a player comes up. */
    @Nullable
    private static volatile Source current;
    private static boolean registered;

    private NetworkThumbs() {
    }

    static synchronized void register(final Context context) {
        if (registered) {
            return;
        }
        registered = true;
        Glide.get(context.getApplicationContext()).getRegistry()
                .prepend(Uri.class, Bitmap.class, new Factory(context.getApplicationContext()));
    }

    /** From the player screen's onStart and onStop: no new stills in between. */
    static void playerVisible(final boolean visible) {
        if (visible) {
            PLAYERS_OPEN.incrementAndGet();
            // Measured on the Dune, 0041: the still of the very file being opened had read 121 MB in
            // 33 s, and the player waited 12 s of it. The retriever cannot be told to stop; its source
            // can stop answering.
            final Source source = current;
            if (source != null) {
                source.aborted = true;
            }
        } else {
            if (PLAYERS_OPEN.decrementAndGet() < 0) {
                PLAYERS_OPEN.set(0);
            }
            final Runnable again = onPlayerGone;
            Log.d("NetworkThumbs", "player gone, open " + PLAYERS_OPEN.get() + ", missed " + missed
                    + ", browser " + (again != null));
            if (PLAYERS_OPEN.get() == 0 && missed && again != null) {
                missed = false;
                new Handler(Looper.getMainLooper()).post(again);
            }
        }
    }

    /**
     * Set by the browser in onStart, cleared in onStop; run on the main thread once the last player
     * is gone if a still was refused meanwhile. Needed because of the order of a return: the
     * browser's onStart, where Glide begins its failed requests again, comes before the player's
     * onStop - so they were refused a second time and the rows stayed blank (measured on Dune, 0043).
     */
    static void whenPlayerGone(@Nullable final Runnable again) {
        onPlayerGone = again;
    }

    /**
     * A still not taken because a player was up: not an answer about the file, so a row that got it
     * is bound again in full rather than kept as it is (see Utils.bindPreview).
     */
    static final class Refused extends IOException {
        Refused(final String message, @Nullable final Throwable cause) {
            super(message, cause);
        }
    }

    /** Whether a failed request was turned away for a player rather than for the file. */
    static boolean refused(@Nullable final GlideException e) {
        if (e == null) {
            return false;
        }
        for (final Throwable cause : e.getRootCauses()) {
            for (Throwable t = cause; t != null; t = t.getCause()) {
                if (t instanceof Refused) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Blocks until the still in progress, if any, has released its decoder - on the player's own
     * thread, before it picks a video decoder; never on the main thread. A still that outlasts the
     * wait is left to finish, and the player takes what decoder it is given, as before.
     */
    static void awaitIdle() {
        if (!ONE_AT_A_TIME.isLocked() || ONE_AT_A_TIME.isHeldByCurrentThread()) {
            return;
        }
        final long start = SystemClock.elapsedRealtime();
        boolean idle = false;
        try {
            idle = ONE_AT_A_TIME.tryLock(PLAYER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (idle) {
                ONE_AT_A_TIME.unlock();
            }
        }
        Utils.log("thumbs: player waited " + (SystemClock.elapsedRealtime() - start) + " ms for a still"
                + (idle ? "" : ", still busy"));
    }

    /** Whether a frame of this address is taken here. */
    static boolean speaks(@Nullable final Uri uri) {
        return uri != null && (SmbSessions.SCHEME.equals(uri.getScheme()) || DavFiles.speaks(uri));
    }

    private static final class Factory implements ModelLoaderFactory<Uri, Bitmap> {

        private final Context context;

        Factory(final Context context) {
            this.context = context;
        }

        @NonNull
        @Override
        public ModelLoader<Uri, Bitmap> build(@NonNull final MultiModelLoaderFactory multi) {
            return new Loader(context);
        }

        @Override
        public void teardown() {
        }
    }

    private static final class Loader implements ModelLoader<Uri, Bitmap> {

        private final Context context;

        Loader(final Context context) {
            this.context = context;
        }

        @Nullable
        @Override
        public LoadData<Bitmap> buildLoadData(@NonNull final Uri uri, final int width,
                                              final int height, @NonNull final Options options) {
            return new LoadData<>(new ObjectKey("net " + uri + " " + width + "x" + height),
                    new Fetcher(context, uri, width, height));
        }

        @Override
        public boolean handles(@NonNull final Uri uri) {
            return speaks(uri);
        }
    }

    private static final class Fetcher implements DataFetcher<Bitmap> {

        private final Context context;
        private final Uri uri;
        private final int width;
        private final int height;
        private volatile boolean cancelled;

        Fetcher(final Context context, final Uri uri, final int width, final int height) {
            this.context = context;
            this.uri = uri;
            this.width = Math.max(1, Math.min(width, 640));
            this.height = Math.max(1, Math.min(height, 360));
        }

        @Override
        public void loadData(@NonNull final Priority priority,
                             @NonNull final DataCallback<? super Bitmap> callback) {
            ONE_AT_A_TIME.lock();
            try {
                if (cancelled) {
                    // Scrolled past while it waited its turn: nothing has been read, nothing is owed.
                    callback.onLoadFailed(new IOException("cancelled"));
                    return;
                }
                if (PLAYERS_OPEN.get() > 0) {
                    missed = true;
                    // Not cached as a failure: the browser binds its rows again when the player is
                    // gone (whenPlayerGone), and Glide begins a failed request again on a rebind.
                    Log.d("NetworkThumbs", "refused, player open: " + uri.getLastPathSegment());
                    callback.onLoadFailed(new Refused("player open", null));
                    return;
                }
                if (NO_FRAME.contains(uri.toString())) {
                    callback.onLoadFailed(new IOException("no frame in " + uri + " (earlier)"));
                    return;
                }
                final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                final Source source = SmbSessions.SCHEME.equals(uri.getScheme())
                        ? new SmbSource(context, uri) : new Source(context, uri);
                final long start = SystemClock.elapsedRealtime();
                current = source;
                try {
                    retriever.setDataSource(source);
                    final long opened = SystemClock.elapsedRealtime();
                    final Bitmap frame = frame(retriever);
                    // What a still costs, by phase: worth having in the log while the cost is the
                    // question - a folder of fifty is fifty of these, one after another.
                    Log.d("NetworkThumbs", "open " + (opened - start) + " ms, frame "
                            + (SystemClock.elapsedRealtime() - opened) + " ms, read "
                            + source.reads + " x / " + (source.bytes >> 10) + " KiB, jumps "
                            + source.jumps + ": " + uri.getLastPathSegment());
                    if (source.aborted) {
                        missed = true;
                        callback.onLoadFailed(new Refused("player open, cut off", null));
                    } else if (frame == null) {
                        NO_FRAME.add(uri.toString());
                        callback.onLoadFailed(new IOException("no frame in " + uri
                                + (source.spent ? " within " + (MAX_BYTES >> 20) + " MiB" : "")));
                    } else {
                        callback.onDataReady(frame);
                    }
                } catch (final Exception e) {
                    // The retriever may give up on a refused read by throwing rather than by
                    // returning no frame: the same two cases, then.
                    if (source.aborted) {
                        missed = true;
                    } else if (source.spent) {
                        NO_FRAME.add(uri.toString());
                    }
                    Log.d("NetworkThumbs", "failed after " + (source.bytes >> 10) + " KiB"
                            + (source.aborted ? ", cut off" : source.spent ? ", over the limit" : "")
                            + ": " + uri.getLastPathSegment() + ": " + e);
                    callback.onLoadFailed(source.aborted ? new Refused("player open, cut off", e) : e);
                } finally {
                    current = null;
                    try {
                        retriever.release();
                    } catch (final Exception ignored) {
                        // Nothing to do about a retriever that will not close.
                    }
                    source.close();
                }
            } finally {
                ONE_AT_A_TIME.unlock();
            }
        }

        /**
         * A tenth of the way in, between a second and ten minutes: far enough past the logos and the
         * fade-in that it is a picture of the film, and close enough to the head of the file that a
         * container without an index does not have to be walked for it.
         */
        private Bitmap frame(final MediaMetadataRetriever retriever) {
            long at = 1_000_000L;
            try {
                final String ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (ms != null) {
                    at = Math.max(1_000_000L, Math.min(Long.parseLong(ms) * 100L, 600_000_000L));
                }
            } catch (final NumberFormatException ignored) {
                // No duration: a second in, as for a file on the device.
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                return retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        width, height);
            }
            final Bitmap full = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null || full.getWidth() <= width) {
                return full;
            }
            final Bitmap scaled = Bitmap.createScaledBitmap(full, width,
                    Math.max(1, full.getHeight() * width / full.getWidth()), true);
            full.recycle();
            return scaled;
        }

        @Override
        public void cleanup() {
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @NonNull
        @Override
        public Class<Bitmap> getDataClass() {
            return Bitmap.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            // LOCAL so Glide keeps the decoded still on disk rather than trying to keep the "source",
            // which here is a whole film.
            return DataSource.LOCAL;
        }
    }

    /**
     * An SMB file held open for the whole retrieval and read where the retriever asks.
     *
     * <p>Not through {@link SmbDataSource}: that opens the file afresh on every {@code open()} - a
     * create, a stat, and its 1 MiB window thrown away - which is right for a player that opens once
     * and streams, and ruinous for a retriever that jumps. Measured on the Dune over Wi-Fi: a 1080p MKV
     * took 20.7 s to open for 1.4 MiB read, in 70 jumps - about 300 ms a jump and nearly nothing a byte.
     * Here a jump is only a positioned read, and a 1 MiB window under it turns the retriever's
     * few-kilobyte reads into one request each.
     */
    private static final class SmbSource extends Source {

        // 1 MiB, SMB 3's largest read: on the Dune's Wi-Fi a request cost about as much as its bytes at
        // 256 KiB (an MP4's index came to 12 MiB in 45 requests and 3 s), so fewer, larger ones.
        private static final int WINDOW = 1024 * 1024;

        private final Context context;
        private final Uri uri;
        @Nullable
        private com.hierynomus.smbj.share.File file;
        private final byte[] window = new byte[WINDOW];
        private long windowStart = -1;
        private int windowLength;
        private long length = -1;

        SmbSource(final Context context, final Uri uri) {
            super(context, uri);
            this.context = context;
            this.uri = uri;
        }

        private com.hierynomus.smbj.share.File file() throws IOException {
            if (file == null) {
                try {
                    file = SmbSessions.open(context, uri);
                    length = file.getFileInformation().getStandardInformation().getEndOfFile();
                } catch (final IOException e) {
                    throw e;
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }
            return file;
        }

        @Override
        public synchronized int readAt(final long at, final byte[] buffer, final int offset,
                                       final int size) throws IOException {
            if (size == 0) {
                return 0;
            }
            checkNotAborted();
            final com.hierynomus.smbj.share.File open = file();
            if (at >= length) {
                return -1;
            }
            reads++;
            if (at < windowStart || at >= windowStart + windowLength) {
                jumps++;
                final int fill;
                try {
                    fill = open.read(window, at, 0, (int) Math.min(WINDOW, length - at));
                } catch (final Exception e) {
                    SmbSessions.readFailed(open, e);
                    throw new IOException(e);
                }
                if (fill <= 0) {
                    return -1;
                }
                windowStart = at;
                windowLength = fill;
                bytes += fill;
            }
            final int from = (int) (at - windowStart);
            final int served = Math.min(size, windowLength - from);
            System.arraycopy(window, from, buffer, offset, served);
            return served;
        }

        @Override
        public synchronized long getSize() throws IOException {
            file();
            return length;
        }

        @Override
        public synchronized void close() {
            if (file != null) {
                try {
                    file.close();
                } catch (final Exception ignored) {
                    // A closed handle on a connection that has already gone is not news.
                }
                file = null;
            }
        }
    }

    /**
     * The retriever's reads, served by a media3 data source. Reads that follow on from the last one
     * carry on with the open source; a jump closes it and opens again where the retriever wants -
     * which over SMB is the cost of one request, since the read there is positioned anyway.
     */
    private static class Source extends MediaDataSource {

        private final Uri uri;
        private final androidx.media3.datasource.DataSource source;
        private long size = -2;
        int reads;
        int jumps;
        long bytes;
        /** Set from another thread when a player comes up: every read after it fails. */
        volatile boolean aborted;
        /** MAX_BYTES read: every read after it fails, and the file is taken for one without a frame. */
        boolean spent;
        private long position = -1;
        private boolean open;

        Source(final Context context, final Uri uri) {
            this.uri = uri;
            final androidx.media3.datasource.DataSource.Factory http =
                    new ResolvingDataSource.Factory(new DefaultHttpDataSource.Factory(), spec -> {
                        if (!DavFiles.speaks(spec.uri)) {
                            return spec;
                        }
                        final DataSpec asHttp = spec.buildUpon().setUri(DavFiles.http(spec.uri)).build();
                        final String authorization = NetworkFiles.authorization(context, spec.uri);
                        return authorization == null ? asHttp : asHttp.withAdditionalHeaders(
                                Collections.singletonMap("Authorization", authorization));
                    });
            source = new SmbDataSource.Factory(context, http).createDataSource();
        }

        final void checkNotAborted() throws IOException {
            if (aborted) {
                throw new IOException("still cut off: player open");
            }
            if (bytes >= MAX_BYTES) {
                if (!spent) {
                    spent = true;
                    Utils.log("thumbs: " + (bytes >> 20) + " MiB read, " + jumps + " jumps, no more: "
                            + uri.getLastPathSegment());
                }
                throw new IOException("still over " + (MAX_BYTES >> 20) + " MiB");
            }
        }

        @Override
        public synchronized int readAt(final long at, final byte[] buffer, final int offset,
                                       final int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            checkNotAborted();
            final long total = getSize();
            if (total >= 0 && at >= total) {
                return -1;
            }
            reads++;
            if (!open || at != position) {
                jumps++;
                close();
                source.open(new DataSpec.Builder().setUri(uri).setPosition(at).build());
                open = true;
                position = at;
            }
            int done = 0;
            while (done < length) {
                final int read = source.read(buffer, offset + done, length - done);
                if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) {
                    break;
                }
                done += read;
            }
            position += done;
            bytes += done;
            return done == 0 ? -1 : done;
        }

        @Override
        public synchronized long getSize() throws IOException {
            if (size == -2) {
                // Asked of the source once, by opening it at the head: the length comes back from
                // the open, and the retriever's first read is at the head anyway.
                close();
                final long length = source.open(new DataSpec.Builder().setUri(uri).build());
                open = true;
                position = 0;
                size = length == androidx.media3.common.C.LENGTH_UNSET ? -1 : length;
            }
            return size;
        }

        @Override
        public synchronized void close() {
            if (open) {
                open = false;
                try {
                    source.close();
                } catch (final IOException ignored) {
                    // A source that will not close has nothing more to give either way.
                }
            }
        }
    }
}
