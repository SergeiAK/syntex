package com.brouken.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A Jellyfin trickplay set lying next to the video being played (see {@link TrickplayIndex}), and the
 * frames out of it.
 *
 * <p>Found once per file, by one listing of the video's folder and two of the set's. Nothing is
 * downloaded then: the sheets are read when a seek first asks for a frame in them, a megabyte or so
 * each, and kept compressed — a decoded 3200×1320 sheet is 17 MB, a frame cut out of it 170 KB.
 *
 * <p>Places it is looked for: SMB and WebDAV (through the browser's own listings and reads) and files
 * on the device. A stream from elsewhere has no folder beside it.
 */
final class Trickplay {

    private static final String TAG = "Trickplay";
    /** Kept sheets: the one being sought in and its neighbours either way. */
    private static final int SHEETS_KEPT = 4;
    private static final long MAX_SHEET = 16L << 20;

    private final Context context;
    final TrickplayIndex index;
    private final Uri[] sheets;
    private final LruCache<Integer, BitmapRegionDecoder> decoders =
            new LruCache<Integer, BitmapRegionDecoder>(SHEETS_KEPT) {
                @Override
                protected void entryRemoved(final boolean evicted, final Integer key,
                                            final BitmapRegionDecoder old, final BitmapRegionDecoder now) {
                    old.recycle();
                }
            };

    private Trickplay(final Context context, final TrickplayIndex index, final Uri[] sheets) {
        this.context = context.getApplicationContext();
        this.index = index;
        this.sheets = sheets;
    }

    /** True for the addresses a set can be looked for beside. */
    static boolean speaks(@Nullable final Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        final String scheme = uri.getScheme();
        return SmbSessions.SCHEME.equals(scheme) || DavFiles.speaks(uri) || "file".equals(scheme);
    }

    /** The set beside this video, or null: none there, or none that fits its length. */
    @WorkerThread
    @Nullable
    static Trickplay find(final Context context, final Uri video, final long durationMs) {
        if (!speaks(video) || durationMs <= 0) {
            return null;
        }
        try {
            final String name = video.getLastPathSegment();
            if (name == null) {
                return null;
            }
            final DocumentFile parent = "file".equals(video.getScheme())
                    ? localParent(video) : NetworkFiles.parent(context, video);
            if (parent == null) {
                return null;
            }
            final DocumentFile set = parent.findFile(TrickplayIndex.folderFor(name));
            if (set == null || !set.isDirectory()) {
                return null;
            }
            final DocumentFile[] widths = set.listFiles();
            final String[] names = new String[widths.length];
            for (int i = 0; i < widths.length; i++) {
                names[i] = widths[i].isDirectory() ? widths[i].getName() : null;
            }
            final int picked = TrickplayIndex.pick(names);
            if (picked < 0) {
                return null;
            }
            final Uri[] numbered = sheets(widths[picked].listFiles());
            final TrickplayIndex index = TrickplayIndex.of(names[picked], numbered.length, durationMs);
            if (index == null) {
                Log.d(TAG, "set does not fit " + durationMs + " ms: " + names[picked] + ", "
                        + numbered.length + " sheets");
                return null;
            }
            Log.d(TAG, "found " + names[picked] + ", " + numbered.length + " sheets, "
                    + index.intervalMs + " ms: " + name);
            return new Trickplay(context, index, numbered);
        } catch (final RuntimeException e) {
            // A listing that fails is a set that is not there, as far as a seek is concerned.
            Log.d(TAG, "no set for " + video + ": " + e);
            return null;
        }
    }

    @Nullable
    private static DocumentFile localParent(final Uri video) {
        final String path = video.getPath();
        final File dir = path == null ? null : new File(path).getParentFile();
        return dir == null ? null : DocumentFile.fromFile(dir);
    }

    /** The sheets in order, as far as they run on from 0 without a gap. */
    private static Uri[] sheets(final DocumentFile[] files) {
        final Uri[] byNumber = new Uri[files.length];
        for (final DocumentFile file : files) {
            final int n = TrickplayIndex.sheetNumber(file.getName());
            if (n >= 0 && n < byNumber.length) {
                byNumber[n] = file.getUri();
            }
        }
        int count = 0;
        while (count < byNumber.length && byNumber[count] != null) {
            count++;
        }
        final Uri[] sheets = new Uri[count];
        System.arraycopy(byNumber, 0, sheets, 0, count);
        return sheets;
    }

    /**
     * The frame for a position, cut out of its sheet; null when the sheet cannot be read. The first ask
     * in a sheet reads it, so this is not for the main thread.
     */
    @WorkerThread
    @Nullable
    synchronized Bitmap frame(final long positionMs) {
        final int[] at = index.locate(positionMs);
        final BitmapRegionDecoder decoder = decoder(at[0]);
        if (decoder == null) {
            return null;
        }
        // The sheet's own size gives the cell's: the width is the set's, the height follows the film's shape.
        final int cellWidth = decoder.getWidth() / index.columns;
        final int cellHeight = decoder.getHeight() / index.rows;
        final Rect cell = new Rect(at[1] * cellWidth, at[2] * cellHeight,
                (at[1] + 1) * cellWidth, (at[2] + 1) * cellHeight);
        try {
            return decoder.decodeRegion(cell, new BitmapFactory.Options());
        } catch (final RuntimeException e) {
            Log.d(TAG, "cell " + cell + " of sheet " + at[0] + ": " + e);
            return null;
        }
    }

    /** Reads a sheet ahead of the seek reaching it. */
    @WorkerThread
    synchronized void prefetch(final long positionMs) {
        decoder(index.locate(positionMs)[0]);
    }

    synchronized void release() {
        decoders.evictAll();
    }

    @Nullable
    private BitmapRegionDecoder decoder(final int sheet) {
        if (sheet < 0 || sheet >= sheets.length) {
            return null;
        }
        final BitmapRegionDecoder kept = decoders.get(sheet);
        if (kept != null) {
            return kept;
        }
        try {
            final byte[] bytes = read(sheets[sheet]);
            final BitmapRegionDecoder decoder = Build.VERSION.SDK_INT >= 31
                    ? BitmapRegionDecoder.newInstance(bytes, 0, bytes.length)
                    : newInstanceLegacy(bytes);
            if (decoder != null) {
                decoders.put(sheet, decoder);
            }
            return decoder;
        } catch (final IOException | RuntimeException e) {
            Log.d(TAG, "sheet " + sheet + " (" + sheets[sheet] + "): " + e);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static BitmapRegionDecoder newInstanceLegacy(final byte[] bytes) throws IOException {
        return BitmapRegionDecoder.newInstance(bytes, 0, bytes.length, false);
    }

    private byte[] read(final Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) {
            try (InputStream in = new FileInputStream(uri.getPath())) {
                return drain(in);
            }
        }
        final DataSource source = NetworkThumbs.sideFiles(context).createDataSource();
        try {
            source.open(new DataSpec(uri));
            final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = source.read(buffer, 0, buffer.length)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_SHEET) {
                    throw new IOException("sheet over " + (MAX_SHEET >> 20) + " MiB");
                }
            }
            return out.toByteArray();
        } finally {
            source.close();
        }
    }

    private static byte[] drain(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        final byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_SHEET) {
                throw new IOException("sheet over " + (MAX_SHEET >> 20) + " MiB");
            }
        }
        return out.toByteArray();
    }
}
