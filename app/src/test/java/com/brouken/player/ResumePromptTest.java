package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ResumePromptTest {

    private static void assertSplit(final String over, final String title, final String whole) {
        final CharSequence[] names = ResumePrompt.split(whole);
        assertEquals(over, names[0] == null ? null : names[0].toString());
        assertEquals(title, names[1].toString());
    }

    @Test
    public void anEpisodeIsNamedUnderItsShow() {
        assertSplit("Американская семейка · S01E01", "Пилотный выпуск",
                "Американская семейка - S01E01 - Пилотный выпуск");
        assertSplit("Sherlock · S01E01", "A Study in Pink", "Sherlock.S01E01.A Study in Pink");
        assertSplit("Show · S02E05", "Name", "Show s2e5 Name");
    }

    @Test
    public void releaseTagsAtTheEndAreLeftOut() {
        assertSplit("Американская семейка · S01E01", "Пилотный выпуск",
                "Американская семейка - S01E01 - Пилотный выпуск [720p]");
        assertSplit(null, "Movie (2010)", "Movie (2010) [1080p] [WEB-DL]");
        assertSplit(null, "[720p]", "[720p]");
    }

    @Test
    public void aMarkerWithNothingAfterItLeavesTheShowAsTheTitle() {
        assertSplit("S01E01", "Sherlock", "Sherlock.S01E01");
        assertSplit(null, "S01E01", "S01E01");
        assertSplit("S01E03", "Третья серия", "S01E03 - Третья серия");
    }

    @Test
    public void aTitleWithoutAMarkerIsLeftWhole() {
        assertSplit(null, "Metallica - Nothing Else Matters", "Metallica - Nothing Else Matters");
        // Not an episode: a word that happens to hold the letters.
        assertSplit(null, "Mars1e2 demo", "Mars1e2 demo");
        assertNull(ResumePrompt.split(null)[0]);
    }
}
