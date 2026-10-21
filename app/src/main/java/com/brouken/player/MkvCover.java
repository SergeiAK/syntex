package com.brouken.player;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The cover a Matroska file carries among its attachments, found by its place and size alone: the
 * picture is read only once it has been chosen, and nothing of the film is touched to find it.
 *
 * <p>Where the attachments are is in the SeekHead at the head of the segment; mkvmerge also writes them
 * there, ahead of the first Cluster, so a file with no SeekHead entry for them is walked only as far as
 * its first Cluster. A cover is what the Matroska spec names one - {@code cover}, {@code small_cover},
 * {@code cover_land}, {@code small_cover_land} - and the landscape ones first, since a row's still is
 * 16:9. Any other picture among the attachments comes last: fonts are what most files attach, and a
 * picture that is not called a cover is still a picture of the film.
 */
final class MkvCover {

    /** The reads this needs, positioned: a MediaDataSource has exactly this shape. */
    interface Input {
        int readAt(long position, byte[] buffer, int offset, int size) throws IOException;
    }

    /** Where the chosen picture's bytes are. */
    static final class Found {
        final long offset;
        final long size;
        final String name;

        Found(final long offset, final long size, final String name) {
            this.offset = offset;
            this.size = size;
            this.name = name;
        }
    }

    private static final long EBML = 0x1A45DFA3L;
    private static final long SEGMENT = 0x18538067L;
    private static final long SEEK_HEAD = 0x114D9B74L;
    private static final long SEEK = 0x4DBBL;
    private static final long SEEK_ID = 0x53ABL;
    private static final long SEEK_POSITION = 0x53ACL;
    private static final long ATTACHMENTS = 0x1941A469L;
    private static final long ATTACHED_FILE = 0x61A7L;
    private static final long FILE_NAME = 0x466EL;
    private static final long FILE_MEDIA_TYPE = 0x4660L;
    private static final long FILE_DATA = 0x465CL;
    private static final long CLUSTER = 0x1F43B675L;
    private static final long UNKNOWN = -1;
    /** A picture bigger than this is not a cover anybody made for a list. */
    static final long MAX_PICTURE = 16L << 20;
    /** Top-level elements walked before giving up: a file is a handful of them ahead of its Clusters. */
    private static final int MAX_TOP = 32;

    private final Input input;
    private final byte[] scratch = new byte[8];
    private long position;

    private MkvCover(final Input input) {
        this.input = input;
    }

    /** Whether a file's name says it is Matroska, the one container here that carries attachments. */
    static boolean speaks(@Nullable final String name) {
        if (name == null) {
            return false;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mkv") || lower.endsWith(".mk3d") || lower.endsWith(".webm");
    }

    /** The cover's place, or null when the file has none or is not Matroska. */
    @Nullable
    static Found find(final Input input) throws IOException {
        return new MkvCover(input).find();
    }

    @Nullable
    private Found find() throws IOException {
        try {
            if (readId() != EBML) {
                return null;
            }
        } catch (final IOException notMatroska) {
            return null;
        }
        // Not "position += readSize()": that adds to the position as it was before the size was read.
        final long header = readSize();
        position += header;
        if (readId() != SEGMENT) {
            return null;
        }
        readSize();
        final long segment = position;
        long attachments = -1;
        for (int i = 0; i < MAX_TOP; i++) {
            final long at = position;
            final long id;
            final long size;
            try {
                id = readId();
                size = readSize();
            } catch (final IOException end) {
                break;
            }
            if (id == ATTACHMENTS) {
                attachments = at;
                break;
            }
            final long data = position;
            if (id == SEEK_HEAD && size != UNKNOWN) {
                final long pointed = seekHead(data, size, segment);
                if (pointed >= 0) {
                    attachments = pointed;
                    break;
                }
            }
            if (id == CLUSTER || size == UNKNOWN) {
                break;
            }
            position = data + size;
        }
        if (attachments < 0) {
            return null;
        }
        position = attachments;
        if (readId() != ATTACHMENTS) {
            return null;
        }
        final long size = readSize();
        return attachments(position, size == UNKNOWN ? Long.MAX_VALUE : position + size);
    }

    /** The Attachments element's place out of a SeekHead, or -1 when it names none. */
    private long seekHead(final long from, final long size, final long segment) throws IOException {
        final long end = from + size;
        position = from;
        while (position < end) {
            final long id = readId();
            final long length = readSize();
            if (id != SEEK) {
                position += length;
                continue;
            }
            final long seekEnd = position + length;
            long target = -1;
            long where = -1;
            while (position < seekEnd) {
                final long child = readId();
                final long childLength = readSize();
                if (child == SEEK_ID) {
                    target = readUnsigned(childLength);
                } else if (child == SEEK_POSITION) {
                    where = readUnsigned(childLength);
                } else {
                    position += childLength;
                }
            }
            position = seekEnd;
            if (target == ATTACHMENTS && where >= 0) {
                return segment + where;
            }
        }
        return -1;
    }

    @Nullable
    private Found attachments(final long from, final long end) throws IOException {
        Found best = null;
        int bestRank = Integer.MAX_VALUE;
        position = from;
        while (position < end) {
            final long id;
            final long length;
            try {
                id = readId();
                length = readSize();
            } catch (final IOException cut) {
                break;
            }
            if (id != ATTACHED_FILE || length == UNKNOWN) {
                if (length == UNKNOWN) {
                    break;
                }
                position += length;
                continue;
            }
            final long fileEnd = position + length;
            String name = null;
            String type = null;
            long dataAt = -1;
            long dataSize = 0;
            while (position < fileEnd) {
                final long child = readId();
                final long childLength = readSize();
                if (child == FILE_NAME) {
                    name = readString(childLength);
                } else if (child == FILE_MEDIA_TYPE) {
                    type = readString(childLength);
                } else {
                    if (child == FILE_DATA) {
                        dataAt = position;
                        dataSize = childLength;
                    }
                    position += childLength;
                }
            }
            position = fileEnd;
            final int rank = rank(name, type);
            if (dataAt >= 0 && dataSize > 0 && dataSize <= MAX_PICTURE && rank < bestRank) {
                best = new Found(dataAt, dataSize, name);
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * How good a cover the attachment is: lower is better, MAX_VALUE is none at all. A name without
     * its extension, since the spec allows .jpg and .png alike.
     */
    static int rank(@Nullable final String name, @Nullable final String type) {
        final String lowerType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        final String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        final boolean picture = lowerType.startsWith("image/")
                || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".webp");
        if (!picture) {
            return Integer.MAX_VALUE;
        }
        final int dot = lowerName.lastIndexOf('.');
        switch (dot < 0 ? lowerName : lowerName.substring(0, dot)) {
            case "cover_land":
                return 0;
            case "small_cover_land":
                return 1;
            case "cover":
                return 2;
            case "small_cover":
                return 3;
            default:
                return 4;
        }
    }

    private int read(final int count) throws IOException {
        int done = 0;
        while (done < count) {
            final int read = input.readAt(position + done, scratch, done, count - done);
            if (read <= 0) {
                throw new IOException("Matroska cut short at " + (position + done));
            }
            done += read;
        }
        return done;
    }

    /** An element ID with its length marker kept, as the spec writes them. */
    private long readId() throws IOException {
        read(1);
        final int first = scratch[0] & 0xFF;
        final int length = Integer.numberOfLeadingZeros(first) - 23;
        if (length < 1 || length > 4) {
            throw new IOException("no element ID at " + position);
        }
        if (length > 1) {
            final long at = position;
            position++;
            read(length - 1);
            position = at;
        }
        long id = first;
        for (int i = 1; i < length; i++) {
            id = (id << 8) | (scratch[i - 1] & 0xFF);
        }
        position += length;
        return id;
    }

    /** An element size with its marker taken off; UNKNOWN for the all-ones value. */
    private long readSize() throws IOException {
        read(1);
        final int first = scratch[0] & 0xFF;
        final int length = Integer.numberOfLeadingZeros(first) - 23;
        if (length < 1 || length > 8) {
            throw new IOException("no element size at " + position);
        }
        long size = first & (0xFF >> length);
        boolean allOnes = size == (0xFF >> length);
        if (length > 1) {
            final long at = position;
            position++;
            read(length - 1);
            position = at;
            for (int i = 0; i < length - 1; i++) {
                final int b = scratch[i] & 0xFF;
                allOnes &= b == 0xFF;
                size = (size << 8) | b;
            }
        }
        position += length;
        return allOnes ? UNKNOWN : size;
    }

    private long readUnsigned(final long length) throws IOException {
        if (length < 1 || length > 8) {
            position += Math.max(0, length);
            return -1;
        }
        read((int) length);
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (scratch[i] & 0xFF);
        }
        position += length;
        return value;
    }

    private String readString(final long length) throws IOException {
        if (length < 0 || length > 4096) {
            position += Math.max(0, length);
            return null;
        }
        final byte[] bytes = new byte[(int) length];
        int done = 0;
        while (done < bytes.length) {
            final int read = input.readAt(position + done, bytes, done, bytes.length - done);
            if (read <= 0) {
                throw new IOException("Matroska cut short at " + (position + done));
            }
            done += read;
        }
        position += length;
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
