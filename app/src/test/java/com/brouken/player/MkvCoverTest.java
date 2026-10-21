package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * The samples in resources/mkv-cover were made with ffmpeg 21.10.2026 from a 1 s 64x36 MPEG-4 clip:
 * covers.mkv carries cover.png (36x54) and cover_land.jpg (64x36), font-only.mkv a font, none.mkv
 * nothing, other-picture.mkv a still.jpg and a font. ffmpeg writes the attachments ahead of the first
 * Cluster and names them in the SeekHead.
 */
public class MkvCoverTest {

    private static byte[] sample(final String name) throws IOException {
        try (InputStream in = MkvCoverTest.class.getResourceAsStream("/mkv-cover/" + name)) {
            assertNotNull(name, in);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            for (int read; (read = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static MkvCover.Input of(final byte[] bytes) {
        return (position, buffer, offset, size) -> {
            if (position >= bytes.length) {
                return -1;
            }
            final int served = (int) Math.min(size, bytes.length - position);
            System.arraycopy(bytes, (int) position, buffer, offset, served);
            return served;
        };
    }

    private static boolean startsWith(final byte[] bytes, final long at, final byte... magic) {
        return Arrays.equals(Arrays.copyOfRange(bytes, (int) at, (int) at + magic.length), magic);
    }

    @Test
    public void landscapeCoverBeatsPortrait() throws IOException {
        final byte[] file = sample("covers.mkv");
        final MkvCover.Found found = MkvCover.find(of(file));
        assertNotNull(found);
        assertEquals("cover_land.jpg", found.name);
        assertTrue(startsWith(file, found.offset, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF));
        // The picture's last two bytes are the JPEG end marker: the size is the attachment's own.
        assertTrue(startsWith(file, found.offset + found.size - 2, (byte) 0xFF, (byte) 0xD9));
    }

    @Test
    public void readFullyGivesThePictureBytes() throws IOException {
        final byte[] file = sample("covers.mkv");
        final MkvCover.Found found = MkvCover.find(of(file));
        assertNotNull(found);
        final byte[] picture = NetworkThumbs.readFully(of(file), found.offset, found.size);
        assertArrayEquals(Arrays.copyOfRange(file, (int) found.offset,
                (int) (found.offset + found.size)), picture);
    }

    @Test
    public void foundByWalkingWhenTheSeekHeadSaysNothing() throws IOException {
        final byte[] file = sample("covers.mkv");
        // The SeekHead's entry for the Attachments element, renamed into an ID nothing has: the
        // walk to the first Cluster is all that is left to find them by.
        boolean patched = false;
        for (int i = 0; i + 6 < file.length; i++) {
            if ((file[i] & 0xFF) == 0x53 && (file[i + 1] & 0xFF) == 0xAB
                    && (file[i + 3] & 0xFF) == 0x19 && (file[i + 4] & 0xFF) == 0x41
                    && (file[i + 5] & 0xFF) == 0xA4 && (file[i + 6] & 0xFF) == 0x69) {
                file[i + 6] = 0x00;
                patched = true;
                break;
            }
        }
        assertTrue("SeekID for Attachments in the sample", patched);
        final MkvCover.Found found = MkvCover.find(of(file));
        assertNotNull(found);
        assertEquals("cover_land.jpg", found.name);
    }

    @Test
    public void fontIsNotACover() throws IOException {
        assertNull(MkvCover.find(of(sample("font-only.mkv"))));
    }

    @Test
    public void noAttachmentsNoCover() throws IOException {
        assertNull(MkvCover.find(of(sample("none.mkv"))));
    }

    @Test
    public void anyPictureWhenNoneIsCalledACover() throws IOException {
        final MkvCover.Found found = MkvCover.find(of(sample("other-picture.mkv")));
        assertNotNull(found);
        assertEquals("still.jpg", found.name);
    }

    @Test
    public void notMatroskaIsNull() throws IOException {
        assertNull(MkvCover.find(of(new byte[]{0, 0, 1, (byte) 0xBA, 0, 0, 0, 0})));
    }

    @Test
    public void cutShortIsNoAnswerRatherThanAWrongOne() throws IOException {
        final byte[] file = Arrays.copyOf(sample("covers.mkv"), 520);
        try {
            final MkvCover.Found found = MkvCover.find(of(file));
            // Whatever it found must lie inside what there is.
            if (found != null) {
                assertTrue(found.offset + found.size <= file.length);
            }
        } catch (final IOException expected) {
            // Cut short: the frame gets its turn.
        }
    }

    /**
     * A real file: "Sample DV7.mkv" (878 MB, K:/testVideo) carries cover.jpg, folder.jpg and logo.png.
     * Its cover is found in a few reads, without the film being read.
     */
    @Test
    public void realFileCoverInAFewReads() throws IOException {
        final java.io.File file = ExtractorHarness.testFile("Sample DV7.mkv");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            final long[] read = new long[2];
            final MkvCover.Input input = (position, buffer, offset, size) -> {
                read[0]++;
                raf.seek(position);
                final int got = raf.read(buffer, offset, size);
                read[1] += Math.max(0, got);
                return got;
            };
            final MkvCover.Found found = MkvCover.find(input);
            assertNotNull(found);
            assertEquals("cover.jpg", found.name);
            final byte[] picture = NetworkThumbs.readFully(input, found.offset, found.size);
            assertTrue(startsWith(picture, 0, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF));
            System.out.println("Sample DV7.mkv: cover " + found.size + " B at " + found.offset + ", "
                    + read[0] + " reads, " + read[1] + " B including the picture");
            assertTrue(read[1] - picture.length < 64 * 1024);
        }
    }

    @Test
    public void rankByName() {
        assertTrue(MkvCover.rank("cover_land.jpg", "image/jpeg") < MkvCover.rank("cover.jpg", "image/jpeg"));
        assertTrue(MkvCover.rank("cover.png", "image/png") < MkvCover.rank("small_cover.png", "image/png"));
        assertTrue(MkvCover.rank("small_cover.jpg", "image/jpeg") < MkvCover.rank("still.jpg", "image/jpeg"));
        assertEquals(Integer.MAX_VALUE, MkvCover.rank("font.ttf", "application/x-truetype-font"));
        assertEquals(2, MkvCover.rank("Cover.JPG", null));
    }

    @Test
    public void speaksMatroskaByName() {
        assertTrue(MkvCover.speaks("Film.MKV"));
        assertTrue(MkvCover.speaks("clip.webm"));
        assertTrue(!MkvCover.speaks("film.mp4"));
        assertTrue(!MkvCover.speaks(null));
    }
}
