package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LetterBarTest {

    /** The bar as text, each letter with the row it jumps to: "#0 A2 Я5". */
    private static String bar(final List<String> names, final boolean descending) {
        final StringBuilder out = new StringBuilder();
        for (final int[] entry : LetterBar.index(names, descending)) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append((char) entry[0]).append(entry[1]);
        }
        return out.toString();
    }

    /** Sorted the way the browser sorts a folder, so the test sees the rows it will really get. */
    private static List<String> sorted(final String... names) {
        final List<String> rows = new ArrayList<>(Arrays.asList(names));
        rows.sort(Utils::compareNatural);
        return rows;
    }

    @Test
    public void onlyLettersThatArePresent() {
        // Latin M and Cyrillic М look alike and are two letters, as they are two runs of rows.
        assertEquals("#0 A2 M3 Б4 М5 Я6",
                bar(sorted("Матрица", "12 обезьян", "Avatar", "Me", "Брат", "Ящик", "8 женщин"), false));
    }

    @Test
    public void caseAndDigitsFileTogether() {
        assertEquals("#0 A1", bar(sorted("[2001] film", "avatar", "Alien"), false));
    }

    /** "Ё" is after "Я" in the rows (by code), so it is after "Я" on the bar - never between Е and Ж. */
    @Test
    public void yoWhereTheRowsPutIt() {
        final List<String> rows = sorted("Ёлки", "Елена", "Жмурки", "Ясно");
        assertEquals(Arrays.asList("Елена", "Жмурки", "Ясно", "Ёлки"), rows);
        assertEquals("Е0 Ж1 Я2 Ё3", bar(rows, false));
    }

    @Test
    public void descendingTurnsTheBarOver() {
        final List<String> rows = sorted("Avatar", "Брат", "1+1", "Ящик");
        Collections.reverse(rows);
        assertEquals("Я0 Б1 A2 #3", bar(rows, true));
    }

    /** Folders and then files, each sorted: a letter in both jumps to the folder. */
    @Test
    public void letterInFoldersAndFilesJumpsToTheFolder() {
        final List<String> rows = new ArrayList<>(sorted("Alien", "Matrix"));
        rows.addAll(sorted("Avatar.mkv", "Brazil.mkv"));
        assertEquals("A0 B3 M1", bar(rows, false));
    }
}
