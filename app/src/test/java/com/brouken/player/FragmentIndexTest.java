package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.junit.Test;

/**
 * A fragmented MP4 with no index at all: no {@code sidx}, no {@code mfra}. Two samples, both cut from
 * bbb_1080p_c.ts by ffmpeg ({@code -c copy -movflags frag_keyframe+empty_moov+default_base_moof+skip_trailer},
 * the second with {@code +separate_moof}, a moof per track as the sample the user's recording has):
 * {@code frag-noindex.mp4} and {@code frag-separate.mp4}, passed in with -PmediaTestDir=...
 */
public class FragmentIndexTest {

    static {
        ExtractorHarness.prepareJvm();
    }

    @Test
    public void fragmentsMuxedTogether() throws IOException {
        checkIndexAndSeek(ExtractorHarness.testFile("frag-noindex.mp4"));
    }

    @Test
    public void fragmentsOneTrackEach() throws IOException {
        checkIndexAndSeek(ExtractorHarness.testFile("frag-separate.mp4"));
    }

    @Test
    public void aFileThatIsNotAnMp4IsLeftAlone() throws IOException {
        final File file = File.createTempFile("not-mp4", ".bin");
        try {
            java.nio.file.Files.write(file.toPath(), new byte[4096]);
            assertEquals(null, scan(file));
        } finally {
            file.delete();
        }
    }

    private static void checkIndexAndSeek(File file) throws IOException {
        final FragmentIndex index = scan(file);
        assertNotNull(index);
        assertTrue("fragments: " + index.size(), index.size() > 5);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            final byte[] tag = new byte[4];
            for (int i = 0; i < index.size(); i++) {
                raf.seek(index.offsets[i] + 4);
                raf.readFully(tag);
                assertEquals("moof", new String(tag, "US-ASCII"));
                if (i > 0) {
                    assertTrue(index.offsets[i] > index.offsets[i - 1]);
                    assertTrue(index.timesUs[i] >= index.timesUs[i - 1]);
                }
            }
        }

        final FragmentedMp4Extractor extractor = new FragmentedMp4Extractor(new DefaultSubtitleParserFactory(), 0);
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(extractor, file, 1);
        assertNotNull(result.seekMap);
        assertTrue("the stock extractor has no index to go by", !result.seekMap.isSeekable());

        final SeekMap map = new UnindexedFmp4.IndexSeekMap(index, C.TIME_UNSET);
        assertTrue(map.isSeekable());
        assertTrue(map.getDurationUs() > index.timesUs[index.size() - 1]);
        final long targetUs = index.timesUs[index.size() / 2] + 300_000L;
        final SeekMap.SeekPoints points = map.getSeekPoints(targetUs);
        assertTrue(points.first.position > 0);
        assertTrue(points.first.timeUs <= targetUs);
        ExtractorHarness.seekAndRead(extractor, file, result, points.first.position, points.first.timeUs, 40);
        final ExtractorHarness.Track video = result.track(C.TRACK_TYPE_VIDEO);
        assertNotNull(video);
        assertTrue(video.samples > 0);
        assertTrue("first sample " + video.firstTimeUs, video.firstTimeUs <= targetUs);
        assertTrue("first sample " + video.firstTimeUs, video.firstTimeUs > targetUs - 6_000_000L);
        assertEquals(0, video.timeJumpsBack);
        final ExtractorHarness.Track audio = result.track(C.TRACK_TYPE_AUDIO);
        assertNotNull(audio);
        assertTrue("audio is there after the seek too", audio.samples > 0);
    }

    private static FragmentIndex scan(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return FragmentIndex.scan((position, into, length) -> {
                raf.seek(position);
                final int n = raf.read(into, 0, length);
                return Math.max(n, 0);
            }, raf.length(), () -> false);
        }
    }
}
