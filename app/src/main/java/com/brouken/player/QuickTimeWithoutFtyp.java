package com.brouken.player;

import android.net.Uri;

import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.ForwardingExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.mp4.Mp4Extractor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Tells {@link Mp4Extractor} that a file starting without an {@code ftyp} atom is QuickTime.
 *
 * <p>The original QuickTime format has no {@code ftyp}; a file from a camera or an old editor starts
 * straight with {@code moov} (or {@code wide}, {@code free}, {@code mdat}). Media3 decides whether a
 * file is QuickTime only from the {@code qt  } brand in {@code ftyp}, with one fallback: an
 * {@code mdat} met before any {@code ftyp}. A file whose {@code moov} comes first never reaches that
 * fallback, and is parsed as ISO MP4.
 *
 * <p>Most of it parses the same either way, but not the sound of such files: AAC in a version-1
 * {@code mp4a} entry keeps its {@code esds} inside a {@code wave} atom, and {@code BoxParser} looks into
 * {@code wave} only for QuickTime. The track is left without a codec and dropped — the picture plays in
 * silence (measured on Dune, 0037: "Сплин - Остаемся зимовать.mov", {@code audio=none}).
 *
 * <p>This peeks at the first atom before the extractor reads anything and, if it is not {@code ftyp},
 * sets the extractor's file type. A seek does not reset that field, so once per extractor is enough.
 *
 * <p>ponytail: a write into a private field, as in {@link MkvCuesLink}. If a Media3 release renames
 * {@code fileType}, nothing is wrapped and such files stay silent as before;
 * {@code QuickTimeWithoutFtypTest} fails on the rename. The alternative is a copy of the 1500-line
 * extractor for a two-line change.
 */
final class QuickTimeWithoutFtyp extends ForwardingExtractor {

    private static final int TYPE_FTYP = 0x66747970;
    /** {@code Mp4Extractor.FILE_TYPE_QUICKTIME}. */
    private static final int FILE_TYPE_QUICKTIME = 1;

    /** Null if a Media3 release moved it — see the class doc. */
    private static final Field FILE_TYPE = fileTypeField();

    private final Mp4Extractor mp4;
    private boolean checked;

    QuickTimeWithoutFtyp(Extractor delegate, Mp4Extractor mp4) {
        super(delegate);
        this.mp4 = mp4;
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        // Settled only once the peek has returned: a peek that throws leaves the retry to do it.
        if (!checked && input.getPosition() == 0) {
            final byte[] header = new byte[8];
            final boolean full = input.peekFully(header, 0, header.length, /* allowEndOfInput= */ true);
            input.resetPeekPosition();
            checked = true;
            if (full && atomType(header) != TYPE_FTYP) {
                markQuickTime(mp4);
            }
        }
        return super.read(input, seekPosition);
    }

    private static int atomType(byte[] header) {
        return (header[4] & 0xFF) << 24 | (header[5] & 0xFF) << 16
                | (header[6] & 0xFF) << 8 | header[7] & 0xFF;
    }

    private static void markQuickTime(Mp4Extractor mp4) {
        try {
            FILE_TYPE.setInt(mp4, FILE_TYPE_QUICKTIME);
            Utils.log("mp4: no ftyp, read as QuickTime");
        } catch (IllegalAccessException e) {
            Utils.log("mp4: no ftyp, QuickTime flag failed: " + e);
        }
    }

    private static Field fileTypeField() {
        try {
            final Field field = Mp4Extractor.class.getDeclaredField("fileType");
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            Utils.log("mp4 QuickTime flag: unavailable, " + t);
            return null;
        }
    }

    /** True when the field is there to write; the test holds Media3 updates to it. */
    static boolean available() {
        return FILE_TYPE != null;
    }

    static final class Factory extends ForwardingExtractorsFactory {

        Factory(ExtractorsFactory factory) {
            super(factory);
        }

        @Override
        public Extractor[] createExtractors() {
            return wrap(super.createExtractors());
        }

        @Override
        public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
            return wrap(super.createExtractors(uri, responseHeaders));
        }

        /** A wrapper per extractor: the flag is per file. */
        static Extractor[] wrap(Extractor[] extractors) {
            if (FILE_TYPE == null) {
                return extractors;
            }
            for (int i = 0; i < extractors.length; i++) {
                final Extractor underlying = extractors[i].getUnderlyingImplementation();
                if (underlying instanceof Mp4Extractor) {
                    extractors[i] = new QuickTimeWithoutFtyp(extractors[i], (Mp4Extractor) underlying);
                }
            }
            return extractors;
        }
    }
}
