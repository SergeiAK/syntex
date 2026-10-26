package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeaderTitleTest {

    /** The one from the header on Dune and Shield, 25.10. */
    @Test
    public void tagsAndExtensionGo() {
        assertEquals("Вверх (2009)", HeaderTitle.clean("Вверх (2009) {tmdb-14160} - [1080p].mkv"));
    }

    /** The playlist question: "[1080p].ts" stayed in the name. */
    @Test
    public void transportStreamTag() {
        assertEquals("Серия 3", HeaderTitle.clean("Серия 3 [1080p].ts"));
    }

    @Test
    public void episodeNameKeepsItsDashes() {
        assertEquals("Аббатство Даунтон - S01E05 - Эпизод 5",
                HeaderTitle.clean("Аббатство Даунтон - S01E05 - Эпизод 5 [WEB-DL 1080p].mkv"));
    }

    /** A dot that is not a container is part of the name. */
    @Test
    public void dotInTitleStays() {
        assertEquals("Vol. 2", HeaderTitle.clean("Vol. 2"));
        assertEquals("Mr. Robot", HeaderTitle.clean("Mr. Robot"));
    }

    /** A title a launcher sent, not a file: square brackets there may be the film's own. */
    @Test
    public void callerTitleKeepsSquareBrackets() {
        assertEquals("[REC] 2", HeaderTitle.clean("[REC] 2"));
    }

    /** The player's file name arrives with the extension already cut (Dune, 26.10). */
    @Test
    public void fileNameWithoutExtension() {
        assertEquals("11.22.63 - S01E01 - Кроличья нора",
                HeaderTitle.clean("11.22.63 - S01E01 - Кроличья нора [1080p]", true));
        assertEquals("[REC] 2", HeaderTitle.clean("[REC] 2", false));
    }

    @Test
    public void nothingLeftKeepsTheName() {
        assertEquals("[1080p]", HeaderTitle.clean("[1080p].mkv"));
    }
}
