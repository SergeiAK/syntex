package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Saving a subtitle found online beside its video: the name it gets and the offset it keeps. */
public class SubtitleSaveTest {

    private static Set<String> taken(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    @Test
    public void firstNameHasNoNumber() {
        assertEquals("Film.ru.srt", SubtitleSave.freeName("Film", "ru", false, taken()));
    }

    @Test
    public void takenNameGetsTheNextNumberTheReaderTellsApart() {
        final String name = SubtitleSave.freeName("Film", "ru", false,
                taken("film.ru.srt", "film.ru2.srt"));
        assertEquals("Film.ru3.srt", name);
        // Read back as the third Russian file, not as a language called "ru3".
        assertEquals("3", SubtitleUtils.tailNumber("ru3"));
    }

    @Test
    public void takenIsComparedWithoutCase() {
        assertEquals("Film.ru2.srt", SubtitleSave.freeName("Film", "ru", false, taken("film.ru.srt")));
    }

    @Test
    public void machineTranslationKeepsItsMarker() {
        assertEquals("Film.auto.ru.srt", SubtitleSave.freeName("Film", "ru", true, taken()));
        assertEquals("Film.auto.ru2.srt",
                SubtitleSave.freeName("Film", "ru", true, taken("film.auto.ru.srt")));
    }

    @Test
    public void noNameWhenAllNumbersAreTaken() {
        final Set<String> all = new HashSet<>();
        for (int n = 1; n <= 99; n++) {
            all.add(SubtitleSave.nameFor("film", "ru", false, n));
        }
        assertNull(SubtitleSave.freeName("Film", "ru", false, all));
    }

    @Test
    public void zeroOffsetLeavesTextAlone() {
        final String srt = "1\n00:00:01,000 --> 00:00:02,500\nHi\n";
        assertEquals(srt, SubtitleSave.shifted(srt, 0));
    }

    @Test
    public void offsetMovesBothStampsOfACue() {
        assertEquals("1\n00:00:02,500 --> 00:00:04,000\nHi\n",
                SubtitleSave.shifted("1\n00:00:01,000 --> 00:00:02,500\nHi\n", 1500));
    }

    @Test
    public void offsetCarriesAcrossMinutesAndHours() {
        assertEquals("01:00:00,200 --> 01:00:01,000",
                SubtitleSave.shifted("00:59:59,700 --> 01:00:00,500", 500));
    }

    @Test
    public void negativeOffsetStopsAtZero() {
        assertEquals("00:00:00,000 --> 00:00:00,500",
                SubtitleSave.shifted("00:00:00,300 --> 00:00:01,500", -1000));
    }

    @Test
    public void webVttStampsKeepTheirDot() {
        assertEquals("00:00:03.250 --> 00:00:04.000",
                SubtitleSave.shifted("00:00:01.250 --> 00:00:02.000", 2000));
    }

    @Test
    public void numbersInTheTextAreNotStamps() {
        assertEquals("1\n00:00:02,000 --> 00:00:03,000\nAt 10:30 sharp, 2,000 people\n",
                SubtitleSave.shifted("1\n00:00:01,000 --> 00:00:02,000\nAt 10:30 sharp, 2,000 people\n",
                        1000));
    }
}
