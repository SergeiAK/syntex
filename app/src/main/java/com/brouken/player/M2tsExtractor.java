package com.brouken.player;

import android.net.Uri;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractorInput;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorsFactory;
import androidx.media3.extractor.ForwardingSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.Ac3Reader;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.PesReader;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.extractor.ts.TsPayloadReader;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Plays BDAV MPEG-TS — the {@code .m2ts} of Blu-ray discs and AVCHD cameras.
 *
 * <p>It is MPEG-TS with a 4-byte timestamp ({@code TP_extra_header}) in front of every 188-byte
 * packet: 192 bytes a packet, sync byte at offsets 4, 196, 388… {@link TsExtractor} knows only 188.
 * Its sniff wants five sync bytes 188 apart and refuses the file outright (measured on Dune, 0037:
 * {@code UnrecognizedInputFormatException}); the duration and seek searches count packets in 188s.
 *
 * <p>This runs a stock {@link TsExtractor} over a view of the file with the four bytes cut out of
 * every packet. Positions convert linearly — packet n starts at n·192 in the file and at n·188 in the
 * view — on the way in (reads, {@link #seek}) and on the way out (seeks the extractor asks for, the
 * {@link SeekMap} it builds), so its own duration reader and binary-search seeking work unchanged.
 *
 * <p>Blu-ray carries Dolby TrueHD as stream type 0x83, which Media3 has no reader for: the track would
 * be dropped. On the same PID the disc also carries the AC-3 core every player must be able to fall
 * back to, each frame in a PES packet of its own (the TrueHD access units in the others). Type 0x83
 * therefore gets the AC-3 reader behind a filter that passes only the PES packets that begin with an
 * AC-3 sync word — sound in 5.1 rather than none. Blu-ray LPCM (0x80) is left out: Media3 has no
 * reader for it either, and takes the number for a video stream.
 *
 * <p>The disc's other audio numbers are its own too, and Media3 knows none of them: Dolby Digital Plus
 * 0x84 and DTS-HD 0x85 (High Resolution) / 0x86 (Master Audio; to Media3 an SCTE-35 cue stream) — the
 * tracks were dropped. DTS-HD goes to the stock DTS reader, which takes the core and its extension
 * substream. Dolby Digital Plus on a disc is an AC-3 frame for 5.1 followed by an E-AC-3 dependent
 * frame with the channels past it; fed both, the AC-3 reader would switch format at every frame, so
 * the dependent frames are dropped and the track plays in 5.1.
 *
 * <p>A disc copied to a plain {@code .ts} keeps these numbers with 188-byte packets, where the stock
 * extractor reads it. {@link Factory} gives that one the same readers, switched on only once the
 * file shows itself a disc: the Blu-ray registration descriptor ("HDMV") in its program map. In a
 * broadcast stream 0x80 and 0x86 mean what Media3 takes them for.
 */
final class M2tsExtractor implements Extractor {

    private static final int PACKET = 192;
    private static final int TS_PACKET = TsExtractor.TS_PACKET_SIZE;
    private static final int HEADER = PACKET - TS_PACKET;
    private static final int SNIFF_PACKETS = 5;
    private static final int TS_STREAM_TYPE_HDMV_TRUEHD = 0x83;
    /** Blu-ray LPCM; Media3 takes 0x80 for DigiCipher II video. */
    private static final int TS_STREAM_TYPE_HDMV_LPCM = 0x80;
    private static final int TS_STREAM_TYPE_HDMV_EAC3 = 0x84;
    private static final int TS_STREAM_TYPE_HDMV_DTS_HD = 0x85;
    private static final int TS_STREAM_TYPE_HDMV_DTS_HD_MA = 0x86;
    /** Registration descriptor, tag 5, length 4, "HDMV": a Blu-ray program map has it. */
    private static final byte[] HDMV_REGISTRATION = {0x05, 0x04, 'H', 'D', 'M', 'V'};
    /**
     * The least window, in 188-byte packets, searched for a PCR at either end of the file and at every
     * step of a seek. Blu-ray allows 100 ms between PCRs and carries them on a PID of their own; at
     * 37 Mbit/s that is ~2400 packets apart (measured: "H264_TrueHD.m2ts"), past the app's 1500 for
     * plain TS, and the file then has no duration and no seeking. 6000 packets — 1.1 MB — span 100 ms
     * up to ~90 Mbit/s, the disc maximum is 48.
     */
    private static final int MIN_TIMESTAMP_SEARCH_PACKETS = 6000;

    private final TsExtractor ts;
    @Nullable
    private ExtractorInput lastInput;
    @Nullable
    private PacketInput view;
    private final PositionHolder innerPosition = new PositionHolder();

    M2tsExtractor(TsExtractor ts) {
        this.ts = ts;
    }

    /** A 188-byte position in the view to the file position of the same byte. */
    static long toFile(long viewPosition) {
        final long packet = viewPosition / TS_PACKET;
        final int inPacket = (int) (viewPosition % TS_PACKET);
        // The start of a packet maps to the start of its 192 bytes, timestamp included: that is where
        // a reader positioned between packets in the file is.
        return packet * PACKET + (inPacket == 0 ? 0 : HEADER + inPacket);
    }

    /** A file position to the view; inside a timestamp counts as the start of the packet after it. */
    static long toView(long filePosition) {
        final long packet = filePosition / PACKET;
        final int inPacket = (int) (filePosition % PACKET);
        return packet * TS_PACKET + Math.max(0, inPacket - HEADER);
    }

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        final byte[] head = new byte[PACKET * SNIFF_PACKETS];
        if (!input.peekFully(head, 0, head.length, /* allowEndOfInput= */ true)) {
            return false;
        }
        for (int i = 0; i < SNIFF_PACKETS; i++) {
            if (head[HEADER + i * PACKET] != 0x47) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void init(ExtractorOutput output) {
        ts.init(new ForwardingExtractorOutput(output) {
            @Override
            public void seekMap(SeekMap seekMap) {
                super.seekMap(new ForwardingSeekMap(seekMap) {
                    @Override
                    public SeekPoints getSeekPoints(long timeUs) {
                        final SeekPoints points = super.getSeekPoints(timeUs);
                        final SeekPoint first = inFile(points.first);
                        return points.first == points.second
                                ? new SeekPoints(first)
                                : new SeekPoints(first, inFile(points.second));
                    }
                });
            }
        });
    }

    private static SeekPoint inFile(SeekPoint point) {
        return new SeekPoint(point.timeUs, toFile(point.position));
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        if (input != lastInput) {
            lastInput = input;
            view = new PacketInput(input);
        }
        final int result = ts.read(view, innerPosition);
        if (result == RESULT_SEEK) {
            seekPosition.position = toFile(innerPosition.position);
        }
        return result;
    }

    @Override
    public void seek(long position, long timeUs) {
        ts.seek(toView(position), timeUs);
    }

    @Override
    public void release() {
        ts.release();
    }

    /**
     * The file seen as plain MPEG-TS: every call stops at the end of a packet's 188 bytes, and the next
     * one first steps over the timestamp in front of the following packet.
     */
    private static final class PacketInput implements ExtractorInput {

        private final ExtractorInput file;

        PacketInput(ExtractorInput file) {
            this.file = file;
        }

        /** Steps the read position over a timestamp; the bytes left in this packet, or -1 at the end. */
        private int alignRead() throws IOException {
            final int inPacket = (int) (file.getPosition() % PACKET);
            if (inPacket < HEADER && !file.skipFully(HEADER - inPacket, /* allowEndOfInput= */ true)) {
                return -1;
            }
            return PACKET - (int) (file.getPosition() % PACKET);
        }

        private int alignPeek() throws IOException {
            final int inPacket = (int) (file.getPeekPosition() % PACKET);
            if (inPacket < HEADER
                    && !file.advancePeekPosition(HEADER - inPacket, /* allowEndOfInput= */ true)) {
                return -1;
            }
            return PACKET - (int) (file.getPeekPosition() % PACKET);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            final int left = alignRead();
            return left < 0 ? C.RESULT_END_OF_INPUT : file.read(buffer, offset, Math.min(length, left));
        }

        @Override
        public boolean readFully(byte[] target, int offset, int length, boolean allowEndOfInput)
                throws IOException {
            int done = 0;
            while (done < length) {
                final int n = read(target, offset + done, length - done);
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput && done == 0) return false;
                    throw new EOFException();
                }
                done += n;
            }
            return true;
        }

        @Override
        public void readFully(byte[] target, int offset, int length) throws IOException {
            readFully(target, offset, length, false);
        }

        @Override
        public int skip(int length) throws IOException {
            if (length == 0) return 0;
            final int left = alignRead();
            return left < 0 ? C.RESULT_END_OF_INPUT : file.skip(Math.min(length, left));
        }

        @Override
        public boolean skipFully(int length, boolean allowEndOfInput) throws IOException {
            int done = 0;
            while (done < length) {
                final int n = skip(length - done);
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput && done == 0) return false;
                    throw new EOFException();
                }
                done += n;
            }
            return true;
        }

        @Override
        public void skipFully(int length) throws IOException {
            skipFully(length, false);
        }

        @Override
        public int peek(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            final int left = alignPeek();
            return left < 0 ? C.RESULT_END_OF_INPUT : file.peek(target, offset, Math.min(length, left));
        }

        @Override
        public boolean peekFully(byte[] target, int offset, int length, boolean allowEndOfInput)
                throws IOException {
            int done = 0;
            while (done < length) {
                final int n = peek(target, offset + done, length - done);
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput && done == 0) return false;
                    throw new EOFException();
                }
                done += n;
            }
            return true;
        }

        @Override
        public void peekFully(byte[] target, int offset, int length) throws IOException {
            peekFully(target, offset, length, false);
        }

        @Override
        public boolean advancePeekPosition(int length, boolean allowEndOfInput) throws IOException {
            int done = 0;
            while (done < length) {
                final int left = alignPeek();
                final int step = Math.min(length - done, Math.max(left, 0));
                if (left < 0 || !file.advancePeekPosition(step, /* allowEndOfInput= */ true)) {
                    if (allowEndOfInput && done == 0) return false;
                    throw new EOFException();
                }
                done += step;
            }
            return true;
        }

        @Override
        public void advancePeekPosition(int length) throws IOException {
            advancePeekPosition(length, false);
        }

        @Override
        public void resetPeekPosition() {
            file.resetPeekPosition();
        }

        @Override
        public long getPeekPosition() {
            return toView(file.getPeekPosition());
        }

        @Override
        public long getPosition() {
            return toView(file.getPosition());
        }

        @Override
        public long getLength() {
            final long length = file.getLength();
            return length == C.LENGTH_UNSET ? C.LENGTH_UNSET : toView(length);
        }

        @Override
        public <E extends Throwable> void setRetryPosition(long position, E e) throws E {
            file.setRetryPosition(toFile(position), e);
        }
    }

    /** The stock readers, plus those for the Blu-ray stream types while {@link #bluray} is set. */
    private static final class PayloadReaders implements TsPayloadReader.Factory {

        private final DefaultTsPayloadReaderFactory stock;
        /** Set before the program map is read: always for M2TS, for plain TS by {@link HdmvWatch}. */
        boolean bluray;

        PayloadReaders(DefaultTsPayloadReaderFactory stock, boolean bluray) {
            this.stock = stock;
            this.bluray = bluray;
        }

        @Override
        public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
            return stock.createInitialPayloadReaders();
        }

        @Nullable
        @Override
        public TsPayloadReader createPayloadReader(int streamType, TsPayloadReader.EsInfo esInfo) {
            if (!bluray) {
                return stock.createPayloadReader(streamType, esInfo);
            }
            if (streamType == TS_STREAM_TYPE_HDMV_LPCM) {
                // Read as DigiCipher II H.262 the stream never yields a format, and the player, waiting
                // for every track to have one, loaded the whole file first - 50 s for "00019.m2ts"
                // (measured on Dune, 0041). No reader: the track is left out, the picture plays silent.
                return null;
            }
            if (streamType == TS_STREAM_TYPE_HDMV_TRUEHD || streamType == TS_STREAM_TYPE_HDMV_EAC3) {
                return new PesReader(new Ac3CoreReader(
                        new Ac3Reader(esInfo.language, esInfo.getRoleFlags(), MimeTypes.VIDEO_MP2T)));
            }
            if (streamType == TS_STREAM_TYPE_HDMV_DTS_HD
                    || streamType == TS_STREAM_TYPE_HDMV_DTS_HD_MA) {
                return stock.createPayloadReader(TsExtractor.TS_STREAM_TYPE_DTS_HD, esInfo);
            }
            return stock.createPayloadReader(streamType, esInfo);
        }
    }

    /**
     * Collects each PES packet whole and hands on, from those that start with an AC-3 sync word, every
     * frame but the E-AC-3 dependent ones. The TrueHD access units on the same PID never reach the
     * AC-3 reader, which would otherwise take any {@code 0B 77} inside them for a frame; a Dolby
     * Digital Plus track loses the dependent frame after each AC-3 one, and with it the channels past
     * 5.1.
     */
    static final class Ac3CoreReader implements ElementaryStreamReader {

        private final ElementaryStreamReader ac3;
        private byte[] packet = new byte[4096];
        private int size;
        private long timeUs;
        private int flags;
        private boolean started;

        Ac3CoreReader(ElementaryStreamReader ac3) {
            this.ac3 = ac3;
        }

        @Override
        public void seek() {
            started = false;
            size = 0;
            ac3.seek();
        }

        @Override
        public void createTracks(ExtractorOutput extractorOutput, PesReader.TrackIdGenerator idGenerator) {
            ac3.createTracks(extractorOutput, idGenerator);
        }

        @Override
        public void packetStarted(long pesTimeUs, int flags) {
            this.timeUs = pesTimeUs;
            this.flags = flags;
            size = 0;
            started = true;
        }

        @Override
        public void consume(ParsableByteArray data) {
            if (!started) {
                data.skipBytes(data.bytesLeft());
                return;
            }
            final int length = data.bytesLeft();
            if (size + length > packet.length) {
                packet = Arrays.copyOf(packet, Math.max(packet.length * 2, size + length));
            }
            data.readBytes(packet, size, length);
            size += length;
        }

        @Override
        public void packetFinished() {
            if (!started) return;
            started = false;
            if (size >= 2 && packet[0] == 0x0B && packet[1] == 0x77) {
                final int kept = dropDependentFrames(packet, size);
                ac3.packetStarted(timeUs, flags);
                try {
                    ac3.consume(new ParsableByteArray(packet, kept));
                } catch (androidx.media3.common.ParserException e) {
                    // Ac3Reader declares it, never throws it; a broken frame is skipped like a lost one.
                    Utils.log("m2ts: AC-3 core frame skipped, " + e);
                }
                ac3.packetFinished();
            }
            size = 0;
        }

        /**
         * Moves the frames to keep to the front of {@code data}, in order, and returns their length.
         * Frames are walked from the start by their own sizes; past the last whole frame, or at bytes
         * that are not one, the rest is kept as it is — the AC-3 reader resyncs on its own.
         */
        static int dropDependentFrames(byte[] data, int size) {
            int read = 0;
            int write = 0;
            while (size - read >= 6 && data[read] == 0x0B && data[read + 1] == 0x77) {
                final int frame = Ac3Util.parseAc3SyncframeSize(Arrays.copyOfRange(data, read, read + 6));
                if (frame <= 0 || frame > size - read) break;
                final boolean eac3 = ((data[read + 5] & 0xF8) >> 3) > 10;
                final boolean dependent = eac3 && ((data[read + 2] & 0xC0) >> 6) == 1;
                if (!dependent) {
                    System.arraycopy(data, read, data, write, frame);
                    write += frame;
                }
                read += frame;
            }
            System.arraycopy(data, read, data, write, size - read);
            return write + size - read;
        }

        @Override
        public void endOfInputReached() {
            packetFinished();
            ac3.endOfInputReached();
        }
    }

    /**
     * A stock TS extractor whose readers learn the stream is a Blu-ray one from the program map's
     * registration descriptor. {@link TsExtractor} reads the input in chunks before it parses the
     * packets in them, so a scan of each chunk as it comes in sets {@link PayloadReaders#bluray} before
     * the map it carries is read.
     */
    static final class PlainTs implements Extractor {

        private final TsExtractor ts;
        private final PayloadReaders readers;
        @Nullable
        private ExtractorInput lastInput;
        @Nullable
        private HdmvWatch watch;

        PlainTs(TsExtractor ts, PayloadReaders readers) {
            this.ts = ts;
            this.readers = readers;
        }

        @Override
        public boolean sniff(ExtractorInput input) throws IOException {
            return ts.sniff(input);
        }

        @Override
        public void init(ExtractorOutput output) {
            ts.init(output);
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            if (readers.bluray) {
                return ts.read(input, seekPosition);
            }
            if (input != lastInput) {
                lastInput = input;
                watch = new HdmvWatch(input, readers);
            }
            return ts.read(watch, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            ts.seek(position, timeUs);
        }

        @Override
        public void release() {
            ts.release();
        }
    }

    /** Passes reads through, looking in what they bring for {@link #HDMV_REGISTRATION}. */
    private static final class HdmvWatch extends ForwardingExtractorInput {

        private final PayloadReaders readers;
        /** The last bytes of the previous read, for a descriptor cut in two. */
        private final byte[] tail = new byte[HDMV_REGISTRATION.length - 1];
        private int tailSize;

        HdmvWatch(ExtractorInput input, PayloadReaders readers) {
            super(input);
            this.readers = readers;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            final int n = super.read(buffer, offset, length);
            if (n > 0 && !readers.bluray) look(buffer, offset, n);
            return n;
        }

        private void look(byte[] buffer, int offset, int n) {
            final byte[] joined = new byte[tailSize + n];
            System.arraycopy(tail, 0, joined, 0, tailSize);
            System.arraycopy(buffer, offset, joined, tailSize, n);
            if (indexOf(joined, HDMV_REGISTRATION) >= 0) {
                readers.bluray = true;
                Utils.log("ts: Blu-ray program map (HDMV), disc stream types on");
                return;
            }
            tailSize = Math.min(tail.length, joined.length);
            System.arraycopy(joined, joined.length - tailSize, tail, 0, tailSize);
        }

        static int indexOf(byte[] data, byte[] pattern) {
            outer:
            for (int i = 0; i + pattern.length <= data.length; i++) {
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) continue outer;
                }
                return i;
            }
            return -1;
        }
    }

    /**
     * Adds the M2TS extractor after the stock ones, and gives the stock TS extractor the Blu-ray
     * readers ({@link PlainTs}); a stock extractor refuses M2TS files anyway.
     */
    static final class Factory extends ForwardingExtractorsFactory {

        private final SubtitleParser.Factory subtitleParserFactory;
        private final int payloadReaderFlags;
        private final int stockTimestampSearchBytes;
        private final int timestampSearchBytes;

        /**
         * @param payloadReaderFlags   the {@link DefaultTsPayloadReaderFactory} flags the stock TS
         *                             extractor is given;
         * @param timestampSearchBytes the stock one's, counted in 188-byte packets like it; raised to
         *                             {@link #MIN_TIMESTAMP_SEARCH_PACKETS} if smaller for M2TS.
         */
        Factory(ExtractorsFactory delegate, SubtitleParser.Factory subtitleParserFactory,
                int payloadReaderFlags, int timestampSearchBytes) {
            super(delegate);
            this.subtitleParserFactory = subtitleParserFactory;
            this.payloadReaderFlags = payloadReaderFlags;
            this.stockTimestampSearchBytes = timestampSearchBytes;
            this.timestampSearchBytes =
                    Math.max(timestampSearchBytes, MIN_TIMESTAMP_SEARCH_PACKETS * TS_PACKET);
        }

        @Override
        public Extractor[] createExtractors() {
            return append(super.createExtractors());
        }

        @Override
        public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
            return append(super.createExtractors(uri, responseHeaders));
        }

        private Extractor[] append(Extractor[] extractors) {
            final Extractor[] all = Arrays.copyOf(extractors, extractors.length + 1);
            for (int i = 0; i < extractors.length; i++) {
                if (all[i] instanceof TsExtractor) all[i] = createPlain();
            }
            all[extractors.length] = create();
            return all;
        }

        /**
         * Built as {@code DefaultExtractorsFactory} builds its TS extractor for the app (single
         * program map, subtitles transcoded, no closed-caption formats), with the Blu-ray readers.
         */
        PlainTs createPlain() {
            final PayloadReaders readers = new PayloadReaders(
                    new DefaultTsPayloadReaderFactory(payloadReaderFlags), /* bluray= */ false);
            return new PlainTs(new TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    /* extractorFlags= */ 0,
                    subtitleParserFactory,
                    new TimestampAdjuster(0),
                    readers,
                    stockTimestampSearchBytes), readers);
        }

        M2tsExtractor create() {
            return new M2tsExtractor(new TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    /* extractorFlags= */ 0,
                    subtitleParserFactory,
                    new TimestampAdjuster(0),
                    new PayloadReaders(new DefaultTsPayloadReaderFactory(payloadReaderFlags),
                            /* bluray= */ true),
                    timestampSearchBytes));
        }
    }
}
