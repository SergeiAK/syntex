package com.brouken.player;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.extractor.Extractor;
import androidx.annotation.Nullable;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.mkv.MatroskaExtractor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Makes a Matroska file whose index is reached through a <em>chain</em> of SeekHeads seekable.
 *
 * <p>A SeekHead is Matroska's table of contents: one {@code Seek} entry per top-level element, each
 * naming what it points at ({@code SeekID}) and where it lives ({@code SeekPosition}). Nothing stops
 * one of those entries from pointing at <em>another</em> SeekHead, and mkvmerge writes exactly that
 * whenever the real index cannot live at the front of the file — the head holds a 17-byte stub
 * pointing at the end of the file, and the SeekHead there lists Info, Tracks, Tags and the Cues.
 *
 * <p>{@link MatroskaExtractor} does not follow that link. It remembers a Cues position only from an
 * entry whose {@code SeekID} is {@code Cues} itself, so on such a file it reaches the first Cluster
 * having found no index and settles for {@code SeekMap.Unseekable}. Everything downstream follows
 * from that single verdict: Media3 disables the time bar (a disabled view cannot take D-pad focus,
 * so on TV the bar is unreachable and DOWN dismisses the controls instead), every seek is dropped,
 * playback always starts at zero, and {@code savePlayer()} declines to remember a position. The
 * picture and the duration are fine throughout, which is what makes it look like a control bug
 * rather than a container one. VLC and mpv follow the link, so the same file seeks there.
 *
 * <p>This reads the chain itself — two peeks, no parsing of its own beyond the table of contents —
 * and hands the answer to the extractor, whose own seek-for-cues machinery then does the rest. The
 * hand-over is a write into a private field, because Media3 offers no way to say it: the extractor
 * is created by a factory, {@code cuesContentPosition} has no setter, and the Cues position is not
 * knowable without a read from the far end of the file that no {@code Extractor} API can express.
 *
 * <p>Both peeks happen before the extractor has read a single byte, and that is load-bearing rather
 * than tidy: {@code DefaultEbmlReader} pops its stack of open master elements only when the input
 * crosses an element's end going <em>forward</em>. Jump to the far end of the file after it has
 * opened Segment and jump back, and Segment never closes — every element after that is nested a
 * level too deep. Media3's own seek-for-cues survives only because it returns to a position past the
 * Cluster it popped. So: resolve the chain first, hand over, never reposition the delegate. The state
 * machine only ever moves forwards, so a reopen that lands somewhere unexpected gives up rather than
 * chasing.
 *
 * <p>ponytail: reflection into Media3 internals. Contained — the field is looked up once, and if a
 * Media3 release renames it the chain is not followed and files like this stay exactly as unseekable as
 * they are today. The reflection-free alternative is to wrap {@link ExtractorInput} and rewrite the
 * eight bytes of the stub entry in flight ({@code SeekID} → Cues, position → the real one, same
 * length, zero-padded), which costs seventeen forwarding methods to save one field write.
 *
 * <p>Second job, since every Matroska file passes through here anyway: a stretch of bytes that are not
 * Matroska no longer ends the file. A torrent backend hands over a piece it does not have as zeros, and
 * a zero byte is no valid element ID — {@code VarintReader} throws an {@link IllegalStateException},
 * which the loader takes for a bug, and three retries of the same bytes later playback stops on a black
 * screen (measured on the JVM: six seconds of a 4K remux with one MiB zeroed halfway, 20.10.2026). The
 * stock reader resyncs only on an ID longer than four bytes. Here a malformed read once samples are
 * flowing skips to the next Cluster and plays on from it; see {@link #resync}. Just+ 2.4.2 does the same
 * inside its copy of the EBML reader.
 *
 * <p>Third job: a file with no Cues at all — no index anywhere, only Clusters — seeks too, the way ffmpeg
 * and Kodi seek one. The {@code Unseekable} verdict is swapped for a map that guesses a byte offset from
 * the average bitrate, and the guess is then narrowed by the Clusters themselves: each one opens with its
 * own timestamp, so a few short peeks — find the next Cluster, read its time, interpolate again —
 * bracket the target and the read resumes from the last Cluster at or before it. Measured on Ducks Take
 * Off 1080p (CtrlHD, 21.10.2026): no SeekHead, no Cues, Segment of unknown size, a Cluster every
 * ~1.5 MB. See {@link #search}. What it cannot do is find a keyframe: playback resumes at a Cluster, and
 * the picture waits for the next keyframe after it, so a file with an eight-second GOP shows its picture
 * up to eight seconds late (Ducks has two keyframes in 16 seconds).
 */
final class MkvCuesLink extends ForwardingExtractor {

    private static final int ID_EBML_HEADER = 0x1A45DFA3;
    private static final int ID_SEGMENT = 0x18538067;
    private static final int ID_SEEK_HEAD = 0x114D9B74;
    private static final int ID_SEEK = 0x4DBB;
    private static final int ID_SEEK_ID = 0x53AB;
    private static final int ID_SEEK_POSITION = 0x53AC;
    private static final int ID_CUES = 0x1C53BB6B;
    private static final int ID_CLUSTER = 0x1F43B675;
    private static final int ID_TIMECODE = 0xE7;
    private static final int ID_CRC32 = 0xBF;

    /**
     * How far a search reads looking for the next Cluster before deciding the one it is inside is too
     * big to step past. A mkvmerge Cluster holds up to five seconds: 1.5 MB at Ducks' 110 Mbit/s, ~60 at
     * a 100 Mbit/s remux with long clusters — which is then bracketed from below rather than walked.
     */
    private static final long SEARCH_SCAN_BYTES = 8L * 1024 * 1024;
    /** Close enough: the search stops at a Cluster at most this far before the target. */
    private static final long SEARCH_CLOSE_US = 1_000_000;
    /** Each step is a reopen of the stream at a new offset; past this many the best so far will do. */
    private static final int SEARCH_MAX_STEPS = 10;

    /** How much a resync looks at per peek: Just+'s figure, and a few round trips per torrent piece. */
    private static final int RESYNC_WINDOW_BYTES = 64 * 1024;

    /**
     * How far to look for a SeekHead. It is the first child of Segment in every file that has one,
     * and a table of contents is a few dozen bytes; this is room for a header, a Void and an index
     * far larger than any of them. Deliberately not more: at the far end of a torrent-backed stream
     * these bytes are pieces somebody has to fetch before the picture starts.
     */
    private static final int PEEK_BYTES = 16 * 1024;

    /** Where the {@code Cues} position lands. Null if a Media3 release moved it — see the class doc. */
    private static final Field CUES_CONTENT_POSITION = matroskaField("cuesContentPosition");
    /**
     * Nanoseconds per Cluster timestamp tick, from Info. Read, not written; a Media3 release without it
     * leaves the search on Matroska's default of a millisecond, which is what nearly every file uses.
     */
    private static final Field TIMECODE_SCALE = matroskaField("timecodeScale");

    private static final int STATE_READ_HEAD = 0;
    private static final int STATE_READ_CHAINED_SEEK_HEAD = 1;
    private static final int STATE_DONE = 2;

    private final MatroskaExtractor matroska;

    private int state = STATE_READ_HEAD;
    /** Absolute offset of Segment's first content byte; SeekPositions are relative to it. */
    private long segmentContentPosition = C.POSITION_UNSET;
    /**
     * A sample has come out of this extractor: the tracks are declared and the reader is inside the
     * Clusters, so damage from here on is in the media rather than in the header. Damage before it is
     * left to fail as it did — skipping a broken Tracks element would play a file with no tracks.
     */
    private boolean sampling;

    /** The input of the read in progress — what the seek map callback, which gets none, measures. */
    @Nullable private ExtractorInput currentInput;
    /** Set once this file has got the estimating map instead of {@code Unseekable}. */
    private boolean estimating;
    /** Where the first Cluster's content starts: the estimate's zero. */
    private long dataStart;
    private long dataLength;
    private long durationUs;

    // The search in progress (see #search): a Cluster known at or before the target, one known after it.
    private boolean searching;
    private long targetUs;
    private long loPosition;
    private long loUs;
    private long hiPosition;
    private long hiUs;
    private int steps;

    private MkvCuesLink(Extractor delegate, MatroskaExtractor matroska) {
        super(delegate);
        this.matroska = matroska;
    }

    @Override
    public void init(ExtractorOutput output) {
        super.init(new ForwardingExtractorOutput(output) {
            @Override
            public void seekMap(SeekMap seekMap) {
                super.seekMap(estimate(seekMap));
            }
        });
    }

    /**
     * Matroska's verdict, or the estimating map in place of an {@code Unseekable} one. Only that one:
     * a map built from Cues is better than any guess. Needs what a guess is made of — the file's
     * length and duration — and is called while the reader stands at the first Cluster's content.
     */
    private SeekMap estimate(SeekMap seekMap) {
        final ExtractorInput input = currentInput;
        if (seekMap.isSeekable() || input == null || input.getLength() == C.LENGTH_UNSET
                || seekMap.getDurationUs() == C.TIME_UNSET || seekMap.getDurationUs() <= 0) {
            return seekMap;
        }
        // The content of the first Cluster, less its header: at most a four-byte ID and an eight-byte
        // size. The search finds the exact start from there.
        dataStart = Math.max(0, input.getPosition() - 12);
        dataLength = input.getLength();
        durationUs = seekMap.getDurationUs();
        estimating = true;
        Utils.log("mkv no index: seeking by estimate, " + dataLength + " B, " + durationUs + " us");
        return new SeekMap() {
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
                // Early for the same reason as the search's own guesses; see search().
                return new SeekPoints(new SeekPoint(timeUs, guess(timeUs - SEARCH_CLOSE_US / 2,
                        dataStart, 0, dataLength, durationUs)));
            }
        };
    }

    /** The offset where {@code timeUs} would be if the bitrate between the two known points were even. */
    private static long guess(long timeUs, long fromPosition, long fromUs, long toPosition, long toUs) {
        if (timeUs <= fromUs || toUs <= fromUs) {
            return fromPosition;
        }
        final double share = Math.min(1, (double) (timeUs - fromUs) / (toUs - fromUs));
        return fromPosition + (long) (share * (toPosition - fromPosition));
    }

    @Override
    public void seek(long position, long timeUs) {
        super.seek(position, timeUs);
        searching = estimating;
        if (searching) {
            targetUs = timeUs;
            loPosition = dataStart;
            loUs = 0;
            hiPosition = dataLength;
            hiUs = durationUs;
            steps = 0;
        }
    }

    /**
     * Two extra reads at most, and only on a file that actually chains: the head peek returns bytes
     * the extractor is about to read anyway, and a file whose SeekHead names the Cues outright — or
     * has no SeekHead at all — is left alone on the spot.
     */
    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        switch (state) {
            case STATE_READ_HEAD: {
                // A source that does not state its length cannot be seeked into anyway, and an index
                // at an offset nobody can address is not worth a round trip to read. Not settled for
                // good, though: a load whose open() failed shows up here with no length once, and the
                // retry has one.
                if (input.getLength() == C.LENGTH_UNSET || CUES_CONTENT_POSITION == null) {
                    break;
                }
                // The state moves only once the peek has returned. A peek that throws — the network
                // going away mid-read — leaves the work undone, and the retry of the same load comes
                // back through here to do it; settled beforehand, it would have been skipped for good.
                final long chained = chainedSeekHeadPosition(peek(input));
                state = STATE_DONE;
                if (chained != C.POSITION_UNSET) {
                    state = STATE_READ_CHAINED_SEEK_HEAD;
                    seekPosition.position = chained;
                    return RESULT_SEEK;
                }
                break;
            }
            case STATE_READ_CHAINED_SEEK_HEAD: {
                final long cues = cuesPosition(peek(input));
                state = STATE_DONE;
                if (cues != C.POSITION_UNSET) {
                    try {
                        CUES_CONTENT_POSITION.setLong(matroska, cues);
                    } catch (IllegalAccessException e) {
                        Utils.log("mkv cues link: " + e);
                    }
                }
                // Back to the start: the extractor has read nothing yet and expects to begin at the
                // EBML header. It finds the Cues itself from here, by the same seek it would have
                // made had the stub named them.
                seekPosition.position = 0;
                return RESULT_SEEK;
            }
            default:
                break;
        }
        if (searching) {
            final int searched = search(input, seekPosition);
            if (searched != RESULT_CONTINUE) {
                return searched;
            }
        }
        final int result;
        currentInput = input;
        try {
            result = super.read(input, seekPosition);
        } catch (IllegalStateException | ParserException e) {
            if (!sampling) {
                throw e;
            }
            return resync(input, e);
        } finally {
            currentInput = null;
        }
        // Matroska returns CONTINUE only with a sample out (its read loops until one is).
        if (result == RESULT_CONTINUE) {
            sampling = true;
        }
        return result;
    }

    /**
     * Skips from where the read broke to the next Cluster and resets the extractor onto it, the way a
     * seek through the Cues would land there. Only a Cluster: four exact bytes, which zeros or a cut
     * through a frame do not fake the way the shorter IDs of other elements can. The bytes in between
     * are peeked and dropped on the same connection — no reopen, no re-request of the range. Nothing
     * before the end of the file means nothing to play: the read ends there, as at the end of a file.
     */
    private int resync(ExtractorInput input, Exception cause) throws IOException {
        final long from = input.getPosition();
        final byte[] window = new byte[RESYNC_WINDOW_BYTES];
        while (true) {
            input.resetPeekPosition();
            int filled = 0;
            while (filled < window.length) {
                final int count = input.peek(window, filled, window.length - filled);
                if (count == C.RESULT_END_OF_INPUT) {
                    break;
                }
                filled += count;
            }
            input.resetPeekPosition();
            for (int i = 0; i + 4 <= filled; i++) {
                if ((window[i] & 0xFF) == 0x1F && (window[i + 1] & 0xFF) == 0x43
                        && (window[i + 2] & 0xFF) == 0xB6 && (window[i + 3] & 0xFF) == 0x75) {
                    input.skipFully(i);
                    final long cluster = input.getPosition();
                    Utils.log("mkv resync: skipped " + (cluster - from) + " B at " + from + ", " + cause);
                    // Matroska's own seek reset: the reader's element stack, the block in progress,
                    // the half-written sample. The time is not used by it.
                    super.seek(cluster, C.TIME_UNSET);
                    return RESULT_CONTINUE;
                }
            }
            if (filled < window.length) {
                Utils.log("mkv resync: nothing after " + from + " to the end, " + cause);
                return RESULT_END_OF_INPUT;
            }
            // Searched; drop it all but the last three bytes, so an ID across the seam is still seen.
            input.skipFully(filled - 3);
        }
    }

    /**
     * One step of the search for {@link #targetUs}, from wherever the input stands: the next Cluster
     * after it, by its timestamp, narrows the bracket. Returns {@code RESULT_SEEK} to try a new offset,
     * or {@code RESULT_CONTINUE} once the input stands on the Cluster to play from and the extractor
     * has been reset onto it. A file that stops making sense mid-search plays from the best Cluster
     * found so far — at worst its first one — rather than failing.
     */
    private int search(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        final long from = input.getPosition();
        if (from == loPosition && steps > 0) {
            // Back on the Cluster the search settled on; see the end of this method. Found again
            // rather than trusted: the lower bound starts out as the estimate's zero, a few bytes
            // before the first Cluster, and stays there when nothing earlier than the target turned up.
            final long[] cluster = nextCluster(input);
            if (cluster != null) {
                input.skipFully((int) (cluster[0] - input.getPosition()));
            }
            return land(input, input.getPosition());
        }
        steps++;
        final long[] cluster = nextCluster(input);
        if (cluster == null) {
            // No Cluster starts in what was scanned: the one around `from` began before it.
            hiPosition = Math.min(hiPosition, from);
        } else if (cluster[1] <= targetUs) {
            if (cluster[0] >= loPosition) {
                loPosition = cluster[0];
                loUs = cluster[1];
            }
        } else if (cluster[0] < hiPosition) {
            hiPosition = cluster[0];
            hiUs = cluster[1];
        } else {
            // Past the upper bound already known: nothing starts between `from` and it.
            hiPosition = Math.min(hiPosition, from);
        }
        final boolean close = targetUs - loUs <= SEARCH_CLOSE_US;
        // Aimed half the tolerance early: aimed at the target itself, a guess lands just past it as
        // often as just before, and a bracket closed only from above creeps down a Cluster per step.
        final long next = guess(targetUs - SEARCH_CLOSE_US / 2, loPosition, loUs, hiPosition, hiUs);
        if (close || steps >= SEARCH_MAX_STEPS || hiPosition - loPosition <= RESYNC_WINDOW_BYTES
                || next <= loPosition) {
            Utils.log("mkv no index: " + targetUs + " us -> Cluster at " + loPosition + " (" + loUs
                    + " us), " + steps + " steps");
            if (cluster != null && cluster[0] == loPosition) {
                input.skipFully((int) (loPosition - input.getPosition()));
                return land(input, loPosition);
            }
            seekPosition.position = loPosition;
            return RESULT_SEEK;
        }
        seekPosition.position = next;
        return RESULT_SEEK;
    }

    /** Ends the search on the Cluster the input now stands on. */
    private int land(ExtractorInput input, long position) {
        searching = false;
        super.seek(position, C.TIME_UNSET);
        return RESULT_CONTINUE;
    }

    /**
     * The first Cluster at or after the input's position, as {@code {position, timeUs}}, or null if
     * none starts within {@link #SEARCH_SCAN_BYTES}. The input is left somewhere at or before the
     * Cluster found: the bytes before it are read and dropped. A Cluster is an exact four-byte ID followed by a size and a Timecode — an ID
     * alone turns up by chance in compressed video every few megabytes, the Timecode right behind it
     * does not.
     */
    @Nullable
    private long[] nextCluster(ExtractorInput input) throws IOException {
        final byte[] window = new byte[RESYNC_WINDOW_BYTES];
        // Room behind a candidate for its size, a CRC-32 and the Timecode, all in the same window.
        final int tail = 4 + 8 + 6 + 1 + 8 + 8;
        final long start = input.getPosition();
        input.resetPeekPosition();
        try {
            while (input.getPosition() - start < SEARCH_SCAN_BYTES) {
                int filled = 0;
                while (filled < window.length) {
                    final int count = input.peek(window, filled, window.length - filled);
                    if (count == C.RESULT_END_OF_INPUT) {
                        break;
                    }
                    filled += count;
                }
                final boolean last = filled < window.length;
                final int end = last ? filled : filled - tail;
                for (int i = 0; i + 4 <= end; i++) {
                    if ((window[i] & 0xFF) == 0x1F && (window[i + 1] & 0xFF) == 0x43
                            && (window[i + 2] & 0xFF) == 0xB6 && (window[i + 3] & 0xFF) == 0x75) {
                        final long ticks = clusterTimecode(window, i + 4, filled);
                        if (ticks >= 0) {
                            return new long[] {input.getPosition() + i, ticks * timecodeScale() / 1000};
                        }
                    }
                }
                if (last) {
                    return null;
                }
                // Drop what was searched and peek on from where the search stopped, so a candidate
                // across the seam is seen whole in the next window. Skipped rather than peeked past:
                // a peek keeps every byte in memory, and this may cover megabytes.
                input.resetPeekPosition();
                input.skipFully(end);
            }
            return null;
        } finally {
            input.resetPeekPosition();
        }
    }

    /**
     * The Timecode of the Cluster whose size starts at {@code at}, in ticks, or -1 if what follows
     * the ID is not a Cluster's size and Timecode (a CRC-32 between them is allowed, as the spec does).
     */
    private static long clusterTimecode(byte[] b, int at, int limit) {
        int p = at;
        final int sizeLength = vintLength(b, p, limit);
        if (sizeLength == 0) {
            return -1;
        }
        p += sizeLength;
        if (p + 6 <= limit && (b[p] & 0xFF) == ID_CRC32 && (b[p + 1] & 0xFF) == 0x84) {
            p += 6;
        }
        if (p + 2 > limit || (b[p] & 0xFF) != ID_TIMECODE) {
            return -1;
        }
        final int lengthByte = b[p + 1] & 0xFF;
        // A Timecode is an unsigned integer of up to eight bytes, its size one byte of the 0x8n form.
        if (lengthByte < 0x81 || lengthByte > 0x88) {
            return -1;
        }
        final int n = lengthByte & 0x0F;
        p += 2;
        if (p + n > limit) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < n; i++) {
            value = (value << 8) | (b[p + i] & 0xFF);
        }
        return value;
    }

    /** The length of the EBML varint at {@code p}, or 0 if there is none (a zero first byte). */
    private static int vintLength(byte[] b, int p, int limit) {
        if (p >= limit || b[p] == 0) {
            return 0;
        }
        final int length = Integer.numberOfLeadingZeros(b[p] & 0xFF) - 23;
        return p + length <= limit ? length : 0;
    }

    private long timecodeScale() {
        if (TIMECODE_SCALE != null) {
            try {
                final long scale = TIMECODE_SCALE.getLong(matroska);
                if (scale > 0) {
                    return scale;
                }
            } catch (IllegalAccessException e) {
                // Default below.
            }
        }
        return 1_000_000;
    }

    /** Up to {@link #PEEK_BYTES} from the input's current position, leaving the read position alone. */
    private static byte[] peek(ExtractorInput input) throws IOException {
        final byte[] buffer = new byte[PEEK_BYTES];
        int read = 0;
        // peekFully() throws at the end of input rather than returning short, and the chained
        // SeekHead sits a few hundred bytes from the end of the file — so read what is there.
        while (read < buffer.length) {
            final int count = input.peek(buffer, read, buffer.length - read);
            if (count == C.RESULT_END_OF_INPUT) {
                break;
            }
            read += count;
        }
        input.resetPeekPosition();
        return read == buffer.length ? buffer : Arrays.copyOf(buffer, read);
    }

    /**
     * The absolute position of the SeekHead this file's first SeekHead points at, or
     * {@link C#POSITION_UNSET} if there is no chain to follow — no SeekHead, one that names the Cues
     * itself (the ordinary file, which needs nothing from here), or bytes that are not Matroska.
     */
    private long chainedSeekHeadPosition(byte[] head) {
        try {
            final MatroskaMetadataReader.EbmlReader reader =
                    new MatroskaMetadataReader.EbmlReader(new ByteArrayInputStream(head));
            if (reader.readId() != ID_EBML_HEADER) {
                return C.POSITION_UNSET;
            }
            reader.skip(reader.readSize());
            while (true) {
                final long id = reader.readId();
                final long size = reader.readSize();
                if (id == ID_SEGMENT) {
                    break;
                }
                reader.skip(size);
            }
            segmentContentPosition = reader.totalBytesRead;
            while (true) {
                final long id = reader.readId();
                final long size = reader.readSize();
                if (id == ID_SEEK_HEAD) {
                    final long[] entries = readSeekHead(reader, size);
                    // A SeekHead that names the Cues needs nothing from here, even if it also links
                    // to another one — the extractor already has what it looks for.
                    return entries[0] != C.POSITION_UNSET || entries[1] == C.POSITION_UNSET
                            ? C.POSITION_UNSET : segmentContentPosition + entries[1];
                }
                reader.skip(size);
            }
        } catch (Exception e) {
            // Out of peeked bytes, or a file shaped in a way this does not follow. Playback is not
            // this class's to break: the extractor reads the same stream it always did.
            return C.POSITION_UNSET;
        }
    }

    /** The absolute Cues position named by the SeekHead these bytes start with. */
    private long cuesPosition(byte[] chainedSeekHead) {
        try {
            final MatroskaMetadataReader.EbmlReader reader =
                    new MatroskaMetadataReader.EbmlReader(new ByteArrayInputStream(chainedSeekHead));
            if (reader.readId() != ID_SEEK_HEAD) {
                return C.POSITION_UNSET;
            }
            final long[] entries = readSeekHead(reader, reader.readSize());
            return entries[0] == C.POSITION_UNSET
                    ? C.POSITION_UNSET : segmentContentPosition + entries[0];
        } catch (Exception e) {
            return C.POSITION_UNSET;
        }
    }

    /**
     * Walks one SeekHead's entries, positioned at its first content byte.
     *
     * @return {@code {cues, chainedSeekHead}}, each a Segment-relative position or
     *     {@link C#POSITION_UNSET}.
     */
    private static long[] readSeekHead(MatroskaMetadataReader.EbmlReader reader, long size)
            throws IOException {
        final long[] found = {C.POSITION_UNSET, C.POSITION_UNSET};
        final long end = reader.totalBytesRead + size;
        while (reader.totalBytesRead < end) {
            final long id = reader.readId();
            final long entrySize = reader.readSize();
            if (id != ID_SEEK) {
                reader.skip(entrySize);
                continue;
            }
            final long entryEnd = reader.totalBytesRead + entrySize;
            long seekId = C.POSITION_UNSET;
            long seekPosition = C.POSITION_UNSET;
            while (reader.totalBytesRead < entryEnd) {
                final long childId = reader.readId();
                final long childSize = reader.readSize();
                // SeekID holds the target's element ID exactly as it is written on disk, marker bits
                // and all — the same shape as the constants above.
                if (childId == ID_SEEK_ID) {
                    seekId = reader.readUInt(childSize);
                } else if (childId == ID_SEEK_POSITION) {
                    seekPosition = reader.readUInt(childSize);
                } else {
                    reader.skip(childSize);
                }
            }
            if (seekPosition == C.POSITION_UNSET) {
                continue;
            }
            if (seekId == ID_CUES) {
                found[0] = seekPosition;
            } else if (seekId == ID_SEEK_HEAD) {
                found[1] = seekPosition;
            }
        }
        return found;
    }

    @Nullable
    private static Field matroskaField(String name) {
        try {
            final Field field = MatroskaExtractor.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            Utils.log("mkv cues link: no " + name + ", " + t);
            return null;
        }
    }

    /**
     * Wraps Matroska — and only Matroska — wherever it comes from. Goes <em>outside</em>
     * {@link Dv7Converter}, which tests the extractor array for {@code instanceof MatroskaExtractor}
     * and would find a wrapper instead; from out here the Matroska instance is still reachable
     * through {@link Extractor#getUnderlyingImplementation()}.
     */
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

        /** A wrapper per extractor: Media3 keeps a set alive per media period, and state is per file. */
        private static Extractor[] wrap(Extractor[] extractors) {
            for (int i = 0; i < extractors.length; i++) {
                final Extractor underlying = extractors[i].getUnderlyingImplementation();
                if (underlying instanceof MatroskaExtractor) {
                    extractors[i] = new MkvCuesLink(extractors[i], (MatroskaExtractor) underlying);
                }
            }
            return extractors;
        }
    }
}
