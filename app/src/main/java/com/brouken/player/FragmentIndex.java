package com.brouken.player;

import java.io.IOException;
import java.util.Arrays;

/**
 * The fragments of a fragmented MP4 that carries no index of its own — no {@code sidx}, no
 * {@code mfra} — found by hopping from box to box: each {@code moof} says how long it is, and the
 * {@code mdat} after it how long that is. Per fragment that is two small reads, so a film of a few
 * thousand fragments is a few thousand reads, and none of the media is read.
 *
 * <p>What it records is where each {@code moof} starts and the decode time its first track begins at
 * ({@code tfdt}): all a seek needs, since the extractor takes up at any {@code moof} on its own.
 *
 * <p>No Android in here, so the JVM tests run it over a file.
 */
final class FragmentIndex {

    /** Positioned read of at most {@code length} bytes; fewer (down to zero) at the end of the file. */
    interface Reader {
        int read(long position, byte[] into, int length) throws IOException;
    }

    final long[] offsets;
    final long[] timesUs;

    private FragmentIndex(long[] offsets, long[] timesUs) {
        this.offsets = offsets;
        this.timesUs = timesUs;
    }

    int size() {
        return offsets.length;
    }

    /** The fragment a seek to {@code timeUs} starts from: the last one that begins at or before it. */
    int indexFor(long timeUs) {
        final int found = Arrays.binarySearch(timesUs, timeUs);
        return found >= 0 ? found : Math.max(0, -found - 2);
    }

    /**
     * @param length    the file's length, as the scan's limit.
     * @param cancelled polled between fragments; a scan nobody waits for any more stops.
     * @return null when the file is not one of these: not an MP4 at the start, no {@code moov} with a
     *         track, or no fragment found.
     */
    static FragmentIndex scan(final Reader reader, final long length, final java.util.function.BooleanSupplier cancelled)
            throws IOException {
        long position = 0;
        long timescale = 0;
        int trackId = -1;
        boolean sawFtyp = false;
        long[] offs = new long[1024];
        long[] times = new long[1024];
        int count = 0;
        // The offset of the first moof of another track since the last one of ours: a seek starts there,
        // so the sound that goes with the picture is not cut off ahead of it.
        long otherSince = -1;
        // One read per fragment: a window at each box that takes in the moof and the header of the mdat after it.
        final byte[] buffer = new byte[4096];
        while (position + 8 <= length && !cancelled.getAsBoolean()) {
            final int got = reader.read(position, buffer, (int) Math.min(buffer.length, length - position));
            if (got < 8) {
                break;
            }
            long size = u32(buffer, 0);
            final int type = (int) u32(buffer, 4);
            long headerSize = 8;
            if (size == 1 && got >= 16) {
                size = u64(buffer, 8);
                headerSize = 16;
            } else if (size == 0) {
                size = length - position;
            }
            if (size < headerSize) {
                break;
            }
            if (position == 0) {
                // A file that does not open with one of these is not a fragmented MP4: stop at the first read.
                if (type != FTYP && type != STYP && type != MOOV) {
                    return null;
                }
                sawFtyp = true;
            }
            if (type == MOOV && timescale == 0) {
                final long[] track = firstTrack(buffer, got, size);
                if (track == null) {
                    final byte[] whole = new byte[(int) Math.min(size, 1 << 20)];
                    final int more = reader.read(position, whole, whole.length);
                    final long[] again = firstTrack(whole, more, size);
                    if (again == null) {
                        return null;
                    }
                    trackId = (int) again[0];
                    timescale = again[1];
                } else {
                    trackId = (int) track[0];
                    timescale = track[1];
                }
            } else if (type == MOOF && timescale > 0) {
                final long decode = decodeTime(reader, position, buffer, got, size, trackId);
                if (decode < 0) {
                    // Another track's fragment (sound and picture often take a moof each).
                    if (otherSince < 0) {
                        otherSince = position;
                    }
                } else {
                    if (count == offs.length) {
                        offs = Arrays.copyOf(offs, count * 2);
                        times = Arrays.copyOf(times, count * 2);
                    }
                    offs[count] = otherSince >= 0 ? otherSince : position;
                    // The first fragment's tfdt is where the stream's own clock starts, not always zero;
                    // the extractor reports sample times as they are, so the table keeps them.
                    times[count] = decode * 1_000_000L / timescale;
                    count++;
                    otherSince = -1;
                }
                // The mdat after it, when its header came in with the window: on to the box after that.
                if (size + 16 <= got && u32(buffer, (int) size + 4) == MDAT) {
                    long mdat = u32(buffer, (int) size);
                    if (mdat == 1) {
                        mdat = u64(buffer, (int) size + 8);
                    }
                    if (mdat >= 8) {
                        position += size + mdat;
                        continue;
                    }
                }
            }
            position += size;
        }
        if (!sawFtyp || count == 0 || timescale == 0) {
            return null;
        }
        return new FragmentIndex(Arrays.copyOf(offs, count), Arrays.copyOf(times, count));
    }

    /**
     * {track id, timescale} of the first video track of the {@code moov} in {@code box} (the first
     * track if none is video); null when the buffer does not reach far enough to say.
     */
    private static long[] firstTrack(byte[] box, int available, long moovSize) {
        long chosenId = -1;
        long chosenScale = 0;
        boolean chosenVideo = false;
        int pos = 8;
        final int end = (int) Math.min(available, moovSize);
        boolean cut = moovSize > available;
        while (pos + 8 <= end) {
            final long size = u32(box, pos);
            final int type = (int) u32(box, pos + 4);
            if (size < 8) {
                break;
            }
            if (type == TRAK) {
                if (pos + size > end) {
                    cut = true;
                    break;
                }
                final long[] info = trackInfo(box, pos + 8, (int) (pos + size));
                if (info != null && (chosenId < 0 || (info[2] == 1 && !chosenVideo))) {
                    chosenId = info[0];
                    chosenScale = info[1];
                    chosenVideo = info[2] == 1;
                }
            }
            pos += (int) size;
        }
        if (chosenId < 0 || (cut && !chosenVideo)) {
            return null;
        }
        return new long[] {chosenId, chosenScale};
    }

    /** {track id, timescale, 1 if video} of one {@code trak} body. */
    private static long[] trackInfo(byte[] box, int start, int end) {
        long id = -1;
        long scale = 0;
        long video = 0;
        int pos = start;
        while (pos + 8 <= end) {
            final long size = u32(box, pos);
            final int type = (int) u32(box, pos + 4);
            if (size < 8) {
                break;
            }
            if (type == TKHD) {
                final int version = box[pos + 8] & 0xFF;
                id = u32(box, pos + 8 + (version == 1 ? 20 : 12));
            } else if (type == MDIA) {
                int inner = pos + 8;
                final int innerEnd = (int) Math.min(end, pos + size);
                while (inner + 8 <= innerEnd) {
                    final long innerSize = u32(box, inner);
                    final int innerType = (int) u32(box, inner + 4);
                    if (innerSize < 8) {
                        break;
                    }
                    if (innerType == MDHD) {
                        final int version = box[inner + 8] & 0xFF;
                        scale = u32(box, inner + 8 + (version == 1 ? 20 : 12));
                    } else if (innerType == HDLR) {
                        video = u32(box, inner + 16) == VIDE ? 1 : 0;
                    }
                    inner += (int) innerSize;
                }
            }
            pos += (int) size;
        }
        return id < 0 || scale <= 0 ? null : new long[] {id, scale, video};
    }

    /**
     * The {@code tfdt} base decode time of the traf for {@code trackId} in the {@code moof} at
     * {@code at}; -1 when this moof has none for that track.
     */
    private static long decodeTime(Reader reader, long at, byte[] first, int firstLength, long moofSize,
                                   int trackId) throws IOException {
        byte[] window = first;
        int windowLength = firstLength;
        long windowAt = 0;
        long pos = 8;
        while (pos + 8 <= moofSize) {
            if (pos < windowAt || pos + 8 > windowAt + windowLength) {
                window = new byte[(int) Math.min(256, moofSize - pos)];
                windowLength = reader.read(at + pos, window, window.length);
                windowAt = pos;
                if (windowLength < 8) {
                    break;
                }
            }
            final int base = (int) (pos - windowAt);
            final long size = u32(window, base);
            final int type = (int) u32(window, base + 4);
            if (size < 8) {
                break;
            }
            if (type == TRAF) {
                final long[] found = trafTime(window, base, (int) Math.min(size, windowLength - base));
                if (found != null && found[0] == trackId) {
                    return found[1];
                }
            }
            pos += size;
        }
        return -1;
    }

    /** {track id, decode time} of a traf box starting at {@code start}; null if its tfdt is not in view. */
    private static long[] trafTime(byte[] box, int start, int size) {
        int pos = start + 8;
        final int end = start + size;
        long id = -1;
        while (pos + 8 <= end) {
            final long childSize = u32(box, pos);
            final int type = (int) u32(box, pos + 4);
            if (childSize < 8) {
                return null;
            }
            if (type == TFHD && pos + 16 <= end) {
                id = u32(box, pos + 12);
            } else if (type == TFDT && pos + 12 <= end) {
                final int version = box[pos + 8] & 0xFF;
                if (version == 1 && pos + 20 <= end) {
                    return new long[] {id, u64(box, pos + 12)};
                }
                if (version == 0) {
                    return new long[] {id, u32(box, pos + 12)};
                }
                return null;
            }
            pos += (int) childSize;
        }
        return null;
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL) << 24 | (b[at + 1] & 0xFFL) << 16 | (b[at + 2] & 0xFFL) << 8 | (b[at + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int at) {
        return u32(b, at) << 32 | u32(b, at + 4);
    }

    private static final int FTYP = 0x66747970;
    private static final int STYP = 0x73747970;
    private static final int MOOV = 0x6D6F6F76;
    private static final int MOOF = 0x6D6F6F66;
    private static final int MDAT = 0x6D646174;
    private static final int TRAK = 0x7472616B;
    private static final int TKHD = 0x746B6864;
    private static final int MDIA = 0x6D646961;
    private static final int MDHD = 0x6D646864;
    private static final int HDLR = 0x68646C72;
    private static final int TRAF = 0x74726166;
    private static final int TFHD = 0x74666864;
    private static final int TFDT = 0x74666474;
    private static final int VIDE = 0x76696465;
}
