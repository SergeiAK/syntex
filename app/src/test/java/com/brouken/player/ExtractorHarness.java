package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;

/**
 * Runs a Media3 extractor over a file on the JVM and records what comes out: tracks, formats, sample
 * counts. Sample files are cut from the library and passed in with -PmediaTestDir=...; a test whose
 * file is missing is skipped, not failed.
 */
final class ExtractorHarness {

    private ExtractorHarness() {
    }

    /**
     * ParsableByteArray reads Build.FINGERPRINT on first use, and the android.jar stub leaves it null;
     * Media3's Log formats through android.util.Log, a stub here. As in AviAudioReframerTest.
     */
    static void prepareJvm() {
        try {
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            final Object unsafe = theUnsafe.get(null);
            final java.lang.reflect.Field fingerprint = android.os.Build.class.getField("FINGERPRINT");
            final Object base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field.class)
                    .invoke(unsafe, fingerprint);
            final long offset = (long) unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field.class)
                    .invoke(unsafe, fingerprint);
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, base, offset, "jvm-unit-test");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
    }

    static File testFile(String name) {
        final String dir = System.getProperty("mediaTestDir", "");
        Assume.assumeTrue("mediaTestDir not set", !dir.isEmpty());
        final File file = new File(dir, name);
        Assume.assumeTrue(file + " missing", file.isFile());
        return file;
    }

    static boolean sniffs(Extractor extractor, File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return extractor.sniff(input(raf, 0));
        }
    }

    /** Reads until the extractor has declared its tracks and every one has a format, or to the end. */
    static Result readTracks(Extractor extractor, File file) throws IOException {
        return read(extractor, file, Integer.MAX_VALUE, true);
    }

    /**
     * Reads the whole file, or until {@code maxSamples} samples have come out of all tracks together.
     * A cut file ending inside a sample ends the read rather than the test.
     */
    static Result readSamples(Extractor extractor, File file, int maxSamples) throws IOException {
        return read(extractor, file, maxSamples, false);
    }

    private static Result read(Extractor extractor, File file, int maxSamples, boolean tracksOnly)
            throws IOException {
        final Result result = new Result();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            Assume.assumeTrue(file + " not recognised by sniff", extractor.sniff(input(raf, 0)));
            extractor.init(result);
            loop(extractor, raf, 0, result, maxSamples, tracksOnly);
        }
        return result;
    }

    /**
     * Seeks an extractor that has already been through {@link #readSamples} the way the player does —
     * {@code seek()} and a fresh input at the position — and reads {@code maxSamples} more into a
     * cleared {@code result}.
     */
    static void seekAndRead(Extractor extractor, File file, Result result, long position, long timeUs,
                            int maxSamples) throws IOException {
        for (Track t : result.tracks) t.clear();
        extractor.seek(position, timeUs);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            loop(extractor, raf, position, result, maxSamples, false);
        }
    }

    private static void loop(Extractor extractor, RandomAccessFile raf, long position, Result result,
                             int maxSamples, boolean tracksOnly) throws IOException {
        ExtractorInput in = input(raf, position);
        final PositionHolder holder = new PositionHolder();
        while (true) {
            if (tracksOnly && result.allFormatsKnown()) break;
            if (result.samples() >= maxSamples) break;
            final int code;
            try {
                code = extractor.read(in, holder);
            } catch (EOFException e) {
                break;
            }
            if (code == Extractor.RESULT_END_OF_INPUT) break;
            if (code == Extractor.RESULT_SEEK) {
                if (holder.position >= raf.length()) break;
                in = input(raf, holder.position);
            }
        }
    }

    private static ExtractorInput input(RandomAccessFile raf, long position) throws IOException {
        raf.seek(position);
        final DataReader reader = (buffer, offset, length) -> {
            final int n = raf.read(buffer, offset, length);
            return n < 0 ? C.RESULT_END_OF_INPUT : n;
        };
        return new DefaultExtractorInput(reader, position, raf.length());
    }

    static final class Result implements ExtractorOutput {
        final List<Track> tracks = new ArrayList<>();
        boolean ended;
        @Nullable SeekMap seekMap;

        @Override
        public TrackOutput track(int id, int type) {
            final Track t = new Track(type);
            tracks.add(t);
            return t;
        }

        @Override
        public void endTracks() {
            ended = true;
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            this.seekMap = seekMap;
        }

        boolean allFormatsKnown() {
            if (!ended) return false;
            for (Track t : tracks) {
                if (t.format == null) return false;
            }
            return true;
        }

        int samples() {
            int n = 0;
            for (Track t : tracks) n += t.samples;
            return n;
        }

        /** The sample mime type of the first track of this type, or null if there is none. */
        @Nullable
        String mime(int type) {
            final Track t = track(type);
            return t == null || t.format == null ? null : t.format.sampleMimeType;
        }

        @Nullable
        Track track(int type) {
            for (Track t : tracks) {
                if (t.type == type) return t;
            }
            return null;
        }
    }

    static final class Track implements TrackOutput {
        final int type;
        @Nullable Format format;
        int samples;
        /** Samples timed earlier than the one before them by more than a second. */
        int timeJumpsBack;
        long firstTimeUs = C.TIME_UNSET;
        long lastTimeUs = C.TIME_UNSET;

        Track(int type) {
            this.type = type;
        }

        void clear() {
            samples = 0;
            timeJumpsBack = 0;
            firstTimeUs = C.TIME_UNSET;
            lastTimeUs = C.TIME_UNSET;
        }

        @Override
        public void format(Format format) {
            this.format = format;
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            final byte[] b = new byte[length];
            int read = 0;
            while (read < length) {
                final int n = input.read(b, read, length - read);
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput && read == 0) return C.RESULT_END_OF_INPUT;
                    throw new EOFException();
                }
                read += n;
            }
            return length;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            data.skipBytes(length);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset,
                                   @Nullable CryptoData cryptoData) {
            if (firstTimeUs == C.TIME_UNSET) firstTimeUs = timeUs;
            if (lastTimeUs != C.TIME_UNSET && timeUs < lastTimeUs - 1_000_000) timeJumpsBack++;
            lastTimeUs = timeUs;
            samples++;
        }
    }
}
