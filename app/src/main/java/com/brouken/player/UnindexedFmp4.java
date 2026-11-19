package com.brouken.player;

import android.content.Context;
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
import androidx.media3.extractor.ForwardingSeekMap;
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

    private final Context context;
    private final Uri uri;
    private final DataSource.Factory source;
    private final long resumePositionUs;
    private final Object seekMapLock = new Object();

    private ExtractorOutput output;
    private volatile boolean released;
    private boolean wantsIndex;
    private boolean started;
    private long inputLength = C.LENGTH_UNSET;
    private boolean nativeIndex;
    private boolean earlyIndexPublished;
    private SeekMap unseekableMap;

    UnindexedFmp4(Extractor delegate, Context context, Uri uri, DataSource.Factory source,
                  long resumePositionUs) {
        super(delegate);
        this.context = context;
        this.uri = uri;
        this.source = source;
        this.resumePositionUs = resumePositionUs;
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
                synchronized (seekMapLock) {
                    if (seekMap.isSeekable()) {
                        nativeIndex = true;
                        wantsIndex = false;
                    } else {
                        wantsIndex = true;
                        unseekableMap = seekMap;
                        // Media3 prepares with the first map it sees. Do not hand it an unseekable
                        // map that would discard the nonzero start before our index is ready.
                        if (resumePositionUs > 0 && inputLength != C.LENGTH_UNSET && !nativeIndex) {
                            return;
                        }
                    }
                    output.seekMap(lengthened(seekMap));
                }
            }
        });
    }

    /**
     * A map whose index runs past the duration it states keeps the index's word for it. An mfra written
     * after the fact can outlive an mvhd written at the start: Chromium's
     * bbb-320x240-2video-2audio.mp4 states 4.2 s in mvhd, has no mehd, and its tfra runs to 24 s - the
     * player showed 4.2 s of a film it went on playing (D5, Dune and Shield, 25.10). The last point is
     * where the last fragment starts, so one more fragment of the length before it is added.
     */
    static SeekMap lengthened(final SeekMap seekMap) {
        final long statedUs = seekMap.getDurationUs();
        if (!seekMap.isSeekable() || statedUs == C.TIME_UNSET) {
            return seekMap;
        }
        final long lastUs = seekMap.getSeekPoints(Long.MAX_VALUE / 2).first.timeUs;
        if (lastUs <= statedUs) {
            return seekMap;
        }
        final long beforeUs = seekMap.getSeekPoints(lastUs - 1).first.timeUs;
        final long durationUs = lastUs + Math.max(0, lastUs - beforeUs);
        Utils.log("fmp4: index runs to " + lastUs / 1000 + " ms past the stated " + statedUs / 1000
                + " ms, duration " + durationUs / 1000 + " ms");
        return new ForwardingSeekMap(seekMap) {
            @Override
            public long getDurationUs() {
                return durationUs;
            }
        };
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        inputLength = input.getLength();
        final int result = super.read(input, seekPosition);
        if (wantsIndex && !started && inputLength != C.LENGTH_UNSET) {
            started = true;
            startScan(inputLength, unseekableMap);
        }
        return result;
    }

    @Override
    public void release() {
        released = true;
        super.release();
    }

    private void startScan(final long length, final SeekMap originalMap) {
        if (resumePositionUs > 0) {
            Utils.log("fmp4 index: waiting before starting at the saved position");
        }
        final Thread thread = new Thread(() -> {
            final long began = System.nanoTime();
            final FragmentIndex index;
            try {
                index = "smb".equalsIgnoreCase(uri.getScheme())
                        ? scanSmb(length, originalMap.getDurationUs(), began)
                        : FragmentIndex.scan(this::readAt, length, () -> released);
            } catch (IOException | RuntimeException e) {
                Utils.log("fmp4 index: scan failed, " + e);
                publishScan(null, originalMap, began);
                return;
            }
            publishScan(index, originalMap, began);
        }, "Fmp4Index");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void publishScan(FragmentIndex index, SeekMap originalMap, long began) {
        synchronized (seekMapLock) {
            if (released || nativeIndex) {
                return;
            }
            if (index == null) {
                Utils.log("fmp4 index: no fragments found");
            }
            if (resumePositionUs > 0 && (index == null || !index.complete)) {
                if (earlyIndexPublished) {
                    Utils.log("fmp4 index: incomplete, keeping the startup seek map");
                } else {
                    Utils.log("fmp4 index: incomplete, opening without the saved position");
                    output.seekMap(originalMap);
                }
            } else if (index != null) {
                Utils.log("fmp4 index: " + index.size() + " fragments in "
                        + (System.nanoTime() - began) / 1_000_000 + " ms, seekable");
                output.seekMap(new IndexSeekMap(index, originalMap.getDurationUs()));
            }
        }
    }

    private void publishResumeIndex(FragmentIndex index, long durationUs, long began) {
        synchronized (seekMapLock) {
            if (released || nativeIndex) {
                return;
            }
            earlyIndexPublished = true;
            Utils.log("fmp4 index: saved position " + resumePositionUs / 1000 + " ms covered by "
                    + index.size() + " fragments in " + (System.nanoTime() - began) / 1_000_000
                    + " ms, still scanning");
            output.seekMap(new IndexSeekMap(index, durationUs));
        }
    }

    /** Keep one SMB handle for the scan; each box needs only its own positioned read. */
    private FragmentIndex scanSmb(long length, long durationUs, long began) throws IOException {
        if (released) {
            return null;
        }
        final com.hierynomus.smbj.share.File file = SmbSessions.open(context, uri);
        // A partial map must not shorten the apparent duration: the resume policy uses it to
        // distinguish a real saved position from a film already watched to the end.
        final boolean early = resumePositionUs > 0 && resumePositionUs < durationUs;
        try {
            return FragmentIndex.scan((position, into, size) -> {
                try {
                    return readSmbFully(file::read, position, into, size);
                } catch (RuntimeException e) {
                    SmbSessions.readFailed(file, e);
                    throw e;
                }
            }, length, () -> released, resumePositionUs,
                    early ? index -> publishResumeIndex(index, durationUs, began) : null);
        } finally {
            try {
                file.close();
            } catch (Exception ignored) {
                // A failed connection may already have closed the handle.
            }
        }
    }

    @FunctionalInterface
    interface SmbRead {
        int read(byte[] buffer, long position, int offset, int length);
    }

    static int readSmbFully(SmbRead read, long position, byte[] buffer, int length) {
        int total = 0;
        while (total < length) {
            final int count = read.read(buffer, position + total, total, length - total);
            if (count <= 0) {
                break;
            }
            total += count;
        }
        return total;
    }

    /** One positioned read for other sources, through the same source the player reads from. */
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
            // On the startup snapshot a request beyond its coverage starts reading at the last
            // known moof. Media3 still passes the original target to the extractor and sample queues.
            final int i = index.indexFor(timeUs);
            return new SeekPoints(new SeekPoint(index.timesUs[i], index.offsets[i]));
        }
    }

    static final class Factory extends ForwardingExtractorsFactory {

        private final Context context;
        private final DataSource.Factory source;
        private final Uri resumeUri;
        private final long resumePositionUs;

        Factory(Context context, ExtractorsFactory factory, DataSource.Factory source, Uri resumeUri,
                long resumePositionUs) {
            super(factory);
            this.context = context.getApplicationContext();
            this.source = source;
            this.resumeUri = resumeUri;
            this.resumePositionUs = resumePositionUs;
        }

        @Override
        public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
            final Extractor[] extractors = super.createExtractors(uri, responseHeaders);
            for (int i = 0; i < extractors.length; i++) {
                if (extractors[i].getUnderlyingImplementation() instanceof FragmentedMp4Extractor) {
                    extractors[i] = new UnindexedFmp4(extractors[i], context, uri, source,
                            uri.equals(resumeUri) ? resumePositionUs : 0);
                }
            }
            return extractors;
        }
    }
}
