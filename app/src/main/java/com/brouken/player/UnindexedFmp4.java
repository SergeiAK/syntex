package com.brouken.player;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.ForwardingExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Makes a fragmented MP4 with no index — no {@code sidx}, no {@code mfra}, the shape of a recording or
 * a stream saved as it came — seekable.
 *
 * <p>Media3 can seek such a file only through an index, and reports it unseekable without one: no
 * focus on the time bar, no resume, a seek landing back at the start. Here the file is played as it
 * always was, and meanwhile a thread of its own walks the file's boxes ({@link FragmentIndex}) and,
 * when it has the fragments, declares the seek map again — the player takes the second one as the file
 * becoming seekable. The extractor itself takes up at any {@code moof}, so nothing else changes.
 *
 * <p>Only for a file that opens with {@code ftyp}, {@code styp} or {@code moov} and is reported
 * unseekable by a {@link FragmentedMp4Extractor}; anything else is left to the extractor it came with.
 */
final class UnindexedFmp4 extends ForwardingExtractor {

    private final Uri uri;
    private final DataSource.Factory source;

    private ExtractorOutput output;
    private volatile boolean released;
    private boolean wantsIndex;
    private long durationUs = C.TIME_UNSET;
    private boolean started;

    UnindexedFmp4(Extractor delegate, Uri uri, DataSource.Factory source) {
        super(delegate);
        this.uri = uri;
        this.source = source;
    }

    @Override
    public void init(ExtractorOutput output) {
        this.output = output;
        super.init(new ExtractorOutput() {
            @Override
            public TrackOutput track(int id, int type) {
                return output.track(id, type);
            }

            @Override
            public void endTracks() {
                output.endTracks();
            }

            @Override
            public void seekMap(SeekMap seekMap) {
                output.seekMap(seekMap);
                if (!seekMap.isSeekable()) {
                    wantsIndex = true;
                    durationUs = seekMap.getDurationUs();
                }
            }
        });
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        final int result = super.read(input, seekPosition);
        if (wantsIndex && !started && input.getLength() != C.LENGTH_UNSET) {
            started = true;
            startScan(input.getLength());
        }
        return result;
    }

    @Override
    public void release() {
        released = true;
        super.release();
    }

    private void startScan(final long length) {
        final Thread thread = new Thread(() -> {
            final long began = System.nanoTime();
            final FragmentIndex index;
            try {
                index = FragmentIndex.scan(this::readAt, length, () -> released);
            } catch (IOException | RuntimeException e) {
                Utils.log("fmp4 index: scan failed, " + e);
                return;
            }
            if (released) {
                return;
            }
            if (index == null) {
                Utils.log("fmp4 index: no fragments found");
                return;
            }
            Utils.log("fmp4 index: " + index.size() + " fragments in "
                    + (System.nanoTime() - began) / 1_000_000 + " ms, seekable");
            output.seekMap(new IndexSeekMap(index, durationUs));
        }, "Fmp4Index");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** One positioned read, through the same source the player reads from: credentials and all. */
    private int readAt(long position, byte[] into, int length) throws IOException {
        final DataSource dataSource = source.createDataSource();
        try {
            dataSource.open(new DataSpec.Builder().setUri(uri).setPosition(position).setLength(length)
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build());
            int read = 0;
            while (read < length) {
                final int n = dataSource.read(into, read, length - read);
                if (n == C.RESULT_END_OF_INPUT) {
                    break;
                }
                read += n;
            }
            return read;
        } finally {
            try {
                dataSource.close();
            } catch (IOException ignored) {
                // Nothing to do about it, and the read is what mattered.
            }
        }
    }

    static final class IndexSeekMap implements SeekMap {

        private final FragmentIndex index;
        private final long durationUs;

        IndexSeekMap(FragmentIndex index, long durationUs) {
            this.index = index;
            final int last = index.size() - 1;
            // Without a stated duration, the last fragment's start and one more of the average length.
            this.durationUs = durationUs != C.TIME_UNSET ? durationUs
                    : last > 0 ? index.timesUs[last] + index.timesUs[last] / last : index.timesUs[last];
        }

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public long getDurationUs() {
            return durationUs;
        }

        @Override
        public SeekPoints getSeekPoints(long timeUs) {
            final int i = index.indexFor(timeUs);
            return new SeekPoints(new SeekPoint(index.timesUs[i], index.offsets[i]));
        }
    }

    static final class Factory extends ForwardingExtractorsFactory {

        private final DataSource.Factory source;

        Factory(ExtractorsFactory factory, DataSource.Factory source) {
            super(factory);
            this.source = source;
        }

        @Override
        public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
            final Extractor[] extractors = super.createExtractors(uri, responseHeaders);
            for (int i = 0; i < extractors.length; i++) {
                if (extractors[i].getUnderlyingImplementation() instanceof FragmentedMp4Extractor) {
                    extractors[i] = new UnindexedFmp4(extractors[i], uri, source);
                }
            }
            return extractors;
        }
    }
}
