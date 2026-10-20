package com.brouken.player;

import android.net.Uri;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
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
 */
final class M2tsExtractor implements Extractor {

    private static final int PACKET = 192;
    private static final int TS_PACKET = TsExtractor.TS_PACKET_SIZE;
    private static final int HEADER = PACKET - TS_PACKET;
    private static final int SNIFF_PACKETS = 5;
    private static final int TS_STREAM_TYPE_HDMV_TRUEHD = 0x83;
    /** Blu-ray LPCM; Media3 takes 0x80 for DigiCipher II video. */
    private static final int TS_STREAM_TYPE_HDMV_LPCM = 0x80;
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

    /** The stock readers, plus the AC-3 core of a Blu-ray TrueHD stream. */
    private static final class PayloadReaders implements TsPayloadReader.Factory {

        private final DefaultTsPayloadReaderFactory stock;

        PayloadReaders(DefaultTsPayloadReaderFactory stock) {
            this.stock = stock;
        }

        @Override
        public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
            return stock.createInitialPayloadReaders();
        }

        @Nullable
        @Override
        public TsPayloadReader createPayloadReader(int streamType, TsPayloadReader.EsInfo esInfo) {
            if (streamType == TS_STREAM_TYPE_HDMV_LPCM) {
                // Read as DigiCipher II H.262 the stream never yields a format, and the player, waiting
                // for every track to have one, loaded the whole file first - 50 s for "00019.m2ts"
                // (measured on Dune, 0041). No reader: the track is left out, the picture plays silent.
                return null;
            }
            if (streamType == TS_STREAM_TYPE_HDMV_TRUEHD) {
                return new PesReader(new Ac3CoreReader(
                        new Ac3Reader(esInfo.language, esInfo.getRoleFlags(), MimeTypes.VIDEO_MP2T)));
            }
            return stock.createPayloadReader(streamType, esInfo);
        }
    }

    /**
     * Collects each PES packet whole and hands it on only if it starts with an AC-3 sync word. The
     * TrueHD access units on the same PID never reach the AC-3 reader, which would otherwise take any
     * {@code 0B 77} inside them for a frame.
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
                ac3.packetStarted(timeUs, flags);
                try {
                    ac3.consume(new ParsableByteArray(packet, size));
                } catch (androidx.media3.common.ParserException e) {
                    // Ac3Reader declares it, never throws it; a broken frame is skipped like a lost one.
                    Utils.log("m2ts: AC-3 core frame skipped, " + e);
                }
                ac3.packetFinished();
            }
            size = 0;
        }

        @Override
        public void endOfInputReached() {
            packetFinished();
            ac3.endOfInputReached();
        }
    }

    /** Adds the M2TS extractor after the stock ones; a stock extractor refuses these files anyway. */
    static final class Factory extends ForwardingExtractorsFactory {

        private final SubtitleParser.Factory subtitleParserFactory;
        private final int payloadReaderFlags;
        private final int timestampSearchBytes;

        /**
         * @param payloadReaderFlags   the {@link DefaultTsPayloadReaderFactory} flags the stock TS
         *                             extractor is given;
         * @param timestampSearchBytes the stock one's, counted in 188-byte packets like it; raised to
         *                             {@link #MIN_TIMESTAMP_SEARCH_PACKETS} if smaller.
         */
        Factory(ExtractorsFactory delegate, SubtitleParser.Factory subtitleParserFactory,
                int payloadReaderFlags, int timestampSearchBytes) {
            super(delegate);
            this.subtitleParserFactory = subtitleParserFactory;
            this.payloadReaderFlags = payloadReaderFlags;
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
            all[extractors.length] = create();
            return all;
        }

        M2tsExtractor create() {
            return new M2tsExtractor(new TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    /* extractorFlags= */ 0,
                    subtitleParserFactory,
                    new TimestampAdjuster(0),
                    new PayloadReaders(new DefaultTsPayloadReaderFactory(payloadReaderFlags)),
                    timestampSearchBytes));
        }
    }
}
