package com.brouken.player.avi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import com.brouken.player.Mpeg4VideoObjectLayer;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/**
 * The fork's AviExtractor holds an MPEG-4 track's format until it has looked at the first chunk, and
 * puts a GMC VOL on it. Files are cut from the library with {@code ffmpeg -t 3 -c copy} and passed in
 * with -PaviTestDir=...; without it the test is skipped:
 * {@code gmc.avi} from "A Disney Christmas Gift.avi" (XviD, GMC with 3 points), {@code plain.avi} from
 * a plain XviD episode.
 */
public class AviExtractorGmcTest {

    static {
        // As in AviAudioReframerTest: ParsableByteArray reads Build.FINGERPRINT on first use, and the
        // android.jar stub leaves it null.
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
        // The extractor logs what it finds; media3's Log formats through android.util.Log, a stub here.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
    }

    @Test
    public void gmcVolTravelsOnTheFormat() throws IOException {
        final Track video = extract(testFile("gmc.avi"));
        assertNotNull(video.format);
        assertTrue(Mpeg4VideoObjectLayer.usesGmc(video.format.initializationData));
        assertTrue("format before the first sample", video.formatBeforeSamples);
        assertTrue("samples were read", video.samples > 25);
    }

    @Test
    public void plainStreamIsUnchanged() throws IOException {
        final Track video = extract(testFile("plain.avi"));
        assertNotNull(video.format);
        assertEquals(0, video.format.initializationData.size());
        assertTrue("format before the first sample", video.formatBeforeSamples);
        assertTrue("samples were read", video.samples > 25);
    }

    private static File testFile(String name) {
        final String dir = System.getProperty("aviTestDir", "");
        Assume.assumeTrue("aviTestDir not set", !dir.isEmpty());
        final File file = new File(dir, name);
        Assume.assumeTrue(file + " missing", file.isFile());
        return file;
    }

    private static Track extract(File file) throws IOException {
        final AviExtractor extractor = new AviExtractor(0, new DefaultSubtitleParserFactory());
        final Capture capture = new Capture();
        extractor.init(capture);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            ExtractorInput in = input(raf, 0);
            final PositionHolder holder = new PositionHolder();
            while (true) {
                final int result = extractor.read(in, holder);
                if (result == Extractor.RESULT_END_OF_INPUT) break;
                if (result == Extractor.RESULT_SEEK) in = input(raf, holder.position);
            }
        }
        for (Track t : capture.tracks) {
            if (t.type == C.TRACK_TYPE_VIDEO) return t;
        }
        throw new AssertionError("no video track");
    }

    private static ExtractorInput input(RandomAccessFile raf, long position) throws IOException {
        raf.seek(position);
        final DataReader reader = (buffer, offset, length) -> {
            final int n = raf.read(buffer, offset, length);
            return n < 0 ? C.RESULT_END_OF_INPUT : n;
        };
        return new DefaultExtractorInput(reader, position, raf.length());
    }

    private static final class Capture implements ExtractorOutput {
        final List<Track> tracks = new ArrayList<>();

        @Override
        public TrackOutput track(int id, int type) {
            final Track t = new Track(type);
            tracks.add(t);
            return t;
        }

        @Override
        public void endTracks() {
        }

        @Override
        public void seekMap(SeekMap seekMap) {
        }
    }

    private static final class Track implements TrackOutput {
        final int type;
        @Nullable Format format;
        boolean formatBeforeSamples;
        int samples;

        Track(int type) {
            this.type = type;
        }

        @Override
        public void format(Format format) {
            this.format = format;
            formatBeforeSamples = samples == 0;
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
                    throw new java.io.EOFException();
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
            samples++;
        }
    }
}
