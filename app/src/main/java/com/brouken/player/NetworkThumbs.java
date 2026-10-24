package com.brouken.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;

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
import java.util.Locale;
import java.util.Map;
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
    /** The extractors of a still taken without the retriever (see Fetcher.ownFrame). */
    private static final ExtractorsFactory EXTRACTORS = new MkvCuesLink.Factory(new DefaultExtractorsFactory());
    /** A still was refused or cut off while a player was up: the browser has rows to ask again. */
    private static volatile boolean missed;
    /** The browser's way to ask again, while it is started; see whenPlayerGone. */
    @Nullable
    private static Runnable onPlayerGone;
    /** The source of the still in progress, to be cut off when a player comes up. */
    @Nullable
    private static volatile Source current;
    private static boolean registered;
    /**
     * The stills the browser has had of files on a share, by address: the width, the height and the
     * written date it asked for them with. Glide keeps a still under exactly those, so the player's
     * playlist rail can find the same one again in the cache - a still is not taken over the network
     * while a player is up, and the browser's is the one the viewer saw (24.10.2026).
     */
    private static final Map<String, long[]> BROWSER_STILLS = new ConcurrentHashMap<>();

    private NetworkThumbs() {
    }

    static void browserStill(final Uri media, final int width, final int height, final long written) {
        BROWSER_STILLS.put(media.toString(), new long[]{width, height, written});
    }

    /** As browserStill put it: {width, height, written}; null when the browser has had none. */
    @Nullable
    static long[] browserStill(final Uri media) {
        return BROWSER_STILLS.get(media.toString());
    }

    static synchronized void register(final Context context) {
        if (registered) {
            return;
        }
        registered = true;
        Glide.get(context.getApplicationContext()).getRegistry()
                .prepend(Uri.class, Bitmap.class, new Factory(context.getApplicationContext()))
                .prepend(Uri.class, Bitmap.class, new LocalCovers.Factory());
    }

    /**
     * A picture's bytes as a bitmap no bigger than the still needs: halved while both sides stay at
     * or above it, so a 3000 px poster is decoded at a quarter of its size and not in full.
     */
    @Nullable
    static Bitmap decodePicture(final byte[] bytes, final int width, final int height) {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
            sample *= 2;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    /** Exactly {@code size} bytes from {@code offset}, or an IOException. */
    static byte[] readFully(final MkvCover.Input input, final long offset, final long size)
            throws IOException {
        final byte[] bytes = new byte[(int) size];
        int done = 0;
        while (done < bytes.length) {
            final int read = input.readAt(offset + done, bytes, done, bytes.length - done);
            if (read <= 0) {
                throw new IOException("picture cut short at " + (offset + done) + " of " + size);
            }
            done += read;
        }
        return bytes;
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
    /**
     * Reads the files of a browsed place - SMB, WebDAV (as http, with the place's password) or a file on
     * the device - one at a time, outside the player. The stills here and the trickplay sheets read
     * through it.
     */
    static androidx.media3.datasource.DataSource.Factory sideFiles(final Context context) {
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
        return new SmbDataSource.Factory(context, http);
    }

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
            // A Matroska file's key changed when its cover started to come first: under the old one,
            // the frame already kept on disk would have stood in front of the cover for good.
            // So did an MP4's, when its embedded picture did; and a Matroska or AVI file's frame is now
            // taken another way, at another place - a cached one of the old way would never be replaced.
            final String name = uri.getLastPathSegment();
            final String kind = MkvCover.speaks(name) ? "net+cover+own "
                    : ownFrames(name) ? "net+own " : embedsPicture(name) ? "net+art " : "net ";
            return new LoadData<>(new ObjectKey(kind + uri + " " + width + "x" + height),
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
            if (SideArt.isPicture(uri.getLastPathSegment())) {
                picture(callback);
                return;
            }
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
                    if (MkvCover.speaks(uri.getLastPathSegment())) {
                        final Bitmap cover = cover(source);
                        if (cover != null) {
                            Log.d("NetworkThumbs", "cover in " + (SystemClock.elapsedRealtime() - start)
                                    + " ms, read " + source.reads + " x / " + (source.bytes >> 10)
                                    + " KiB: " + uri.getLastPathSegment());
                            callback.onDataReady(cover);
                            return;
                        }
                    }
                    if (ownFrames(uri.getLastPathSegment())) {
                        final Bitmap own = ownFrame(source);
                        if (own != null) {
                            Log.d("NetworkThumbs", "own frame in " + (SystemClock.elapsedRealtime() - start)
                                    + " ms, read " + source.reads + " x / " + (source.bytes >> 10) + " KiB, jumps "
                                    + source.jumps + ": " + uri.getLastPathSegment());
                            callback.onDataReady(own);
                            return;
                        }
                        if (source.aborted || source.spent) {
                            // Sorted out below, as a retriever's failure would be.
                            throw new IOException("own frame cut off");
                        }
                        Log.d("NetworkThumbs", "no own frame after " + (SystemClock.elapsedRealtime() - start)
                                + " ms, " + (source.bytes >> 10) + " KiB, the retriever then: "
                                + uri.getLastPathSegment());
                    }
                    retriever.setDataSource(source);
                    final long opened = SystemClock.elapsedRealtime();
                    if (embedsPicture(uri.getLastPathSegment())) {
                        final byte[] art = retriever.getEmbeddedPicture();
                        final Bitmap picture = art == null ? null : decodePicture(art, width, height);
                        if (picture != null) {
                            Log.d("NetworkThumbs", "embedded picture in " + (SystemClock.elapsedRealtime() - start)
                                    + " ms, read " + (source.bytes >> 10) + " KiB: " + uri.getLastPathSegment());
                            callback.onDataReady(picture);
                            return;
                        }
                    }
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
         * The cover among a Matroska file's attachments, or null to take a frame instead. A file the
         * reader cannot make sense of is not a failure here - the retriever still gets its turn - but a
         * read refused for a player or over the limit is, and goes up as it would from the retriever.
         */
        @Nullable
        private Bitmap cover(final Source source) throws IOException {
            try {
                final MkvCover.Found found = MkvCover.find(source::readAt);
                if (found == null) {
                    return null;
                }
                return decodePicture(readFully(source::readAt, found.offset, found.size), width, height);
            } catch (final IOException e) {
                if (source.aborted || source.spent) {
                    throw e;
                }
                Log.d("NetworkThumbs", "no cover read: " + uri.getLastPathSegment() + ": " + e);
                return null;
            }
        }

        /**
         * A picture lying next to the video (see {@link SideArt}): read whole and decoded small. Not
         * one at a time with the frames and not turned away for a player - it takes no decoder, and a
         * poster of a few hundred kilobytes should not queue behind a frame of a 4K file.
         */
        private void picture(final DataCallback<? super Bitmap> callback) {
            if (cancelled) {
                callback.onLoadFailed(new IOException("cancelled"));
                return;
            }
            final Source source = SmbSessions.SCHEME.equals(uri.getScheme())
                    ? new SmbSource(context, uri) : new Source(context, uri);
            try {
                final long size = source.getSize();
                if (size <= 0 || size > MkvCover.MAX_PICTURE) {
                    callback.onLoadFailed(new IOException("picture of " + size + " B: " + uri));
                    return;
                }
                final Bitmap picture = decodePicture(readFully(source::readAt, 0, size), width, height);
                if (picture == null) {
                    callback.onLoadFailed(new IOException("not a picture: " + uri));
                } else {
                    callback.onDataReady(picture);
                }
            } catch (final Exception e) {
                callback.onLoadFailed(e);
            } finally {
                source.close();
            }
        }

        /**
         * The keyframe a tenth of the way in, found through the container's own index by Media3 and
         * decoded here (see FileFrames) - not by the retriever, which on a Matroska file walks the head
         * of it for twenty keyframes to pick the largest (findThumbnails in the platform's extractor):
         * 100-290 MB of a 720p cartoon, where this reads the head, the Cues and one cluster (measured on
         * Shield, 22.10.2026). Null when the file or the decoder will not have it; the retriever then.
         */
        @Nullable
        private Bitmap ownFrame(final Source source) {
            final Reads reads = new Reads(uri, source);
            final FileFrames frames = FileFrames.open(uri, () -> reads, EXTRACTORS, true);
            if (frames == null) {
                return null;
            }
            try {
                if (frames.durationUs <= 0) {
                    return frames.frame(1000, width);
                }
                // A fade or a title on black at a tenth in is a black tile (a cartoon of Christmas
                // Favorites, Shield 23.10.2026): a keyframe further in costs one more cluster here.
                final long durationMs = frames.durationUs / 1000;
                final long[] tries = {Math.max(1000, Math.min(durationMs / 10, 600_000)),
                        durationMs / 4, durationMs * 2 / 5};
                Bitmap black = null;
                for (final long at : tries) {
                    final Bitmap frame = frames.frame(at, width);
                    if (frame == null) {
                        continue;
                    }
                    if (black != null) {
                        black.recycle();
                    }
                    if (!dark(frame)) {
                        return frame;
                    }
                    black = frame;
                }
                // Black all through, or nothing further in: the black one is still the file's picture.
                return black;
            } finally {
                frames.release();
            }
        }

        /** Whether a still is all but black: its mean brightness on a coarse grid. */
        private static boolean dark(final Bitmap bitmap) {
            final int w = bitmap.getWidth();
            final int h = bitmap.getHeight();
            long sum = 0;
            int n = 0;
            for (int j = 0; j < 9; j++) {
                for (int i = 0; i < 16; i++) {
                    final int c = bitmap.getPixel(i * w / 16, j * h / 9);
                    sum += ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff);
                    n += 3;
                }
            }
            return sum / n < 24;
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

    /** Matroska and AVI: a still of the file's own, ahead of the retriever (see Fetcher.ownFrame). */
    static boolean ownFrames(@Nullable final String name) {
        return MkvCover.speaks(name) || name != null && name.toLowerCase(Locale.ROOT).endsWith(".avi");
    }

    /** The MP4 family, where the retriever finds a cover in the file's metadata before taking a frame. */
    static boolean embedsPicture(@Nullable final String name) {
        if (name == null) {
            return false;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov");
    }

    /**
     * A Source as a Media3 data source, for the extractor of a still of our own: every read is the
     * Source's positioned one, so its held-open file, its window, its count and its limit all apply,
     * and a player coming up cuts this off as it does the retriever.
     */
    private static final class Reads implements androidx.media3.datasource.DataSource {

        private final Uri uri;
        private final Source source;
        private long position;
        private long end;

        Reads(final Uri uri, final Source source) {
            this.uri = uri;
            this.source = source;
        }

        @Override
        public void addTransferListener(@NonNull final TransferListener listener) {
        }

        @Override
        public long open(@NonNull final DataSpec spec) throws IOException {
            position = spec.position;
            final long size = source.getSize();
            if (size < 0) {
                end = -1;
                return spec.length;
            }
            end = spec.length == C.LENGTH_UNSET ? size : Math.min(size, position + spec.length);
            return end - position;
        }

        @Override
        public int read(@NonNull final byte[] buffer, final int offset, final int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int ask = length;
            if (end >= 0) {
                if (position >= end) {
                    return C.RESULT_END_OF_INPUT;
                }
                ask = (int) Math.min(length, end - position);
            }
            final int read = source.readAt(position, buffer, offset, ask);
            if (read <= 0) {
                return C.RESULT_END_OF_INPUT;
            }
            position += read;
            return read;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public void close() {
            // The Source is the fetcher's, and closed by it.
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
            source = sideFiles(context).createDataSource();
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
