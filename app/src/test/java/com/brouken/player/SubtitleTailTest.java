package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Several sidecars in one language: {@code Film.eng1.srt}, {@code Film.eng2.srt}. */
public class SubtitleTailTest {

    @Test
    public void numberAfterLanguage() {
        assertEquals("1", SubtitleUtils.tailNumber("eng1"));
        assertEquals("12", SubtitleUtils.tailNumber("en12"));
    }

    @Test
    public void noNumberInPlainOrOtherTails() {
        assertNull(SubtitleUtils.tailNumber("eng"));
        assertNull(SubtitleUtils.tailNumber("forced"));
        assertNull(SubtitleUtils.tailNumber("1080p"));
        assertNull(SubtitleUtils.tailNumber("s01e02"));
        assertNull(SubtitleUtils.tailNumber(null));
    }
}
