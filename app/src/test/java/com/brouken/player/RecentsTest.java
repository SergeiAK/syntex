package com.brouken.player;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class RecentsTest {

    @Test
    public void newestFirstAndNoTwice() {
        String stored = "";
        stored = Recents.push(stored, "a.mkv", "smb://nas/a.mkv");
        stored = Recents.push(stored, "b.mkv", "file:///b.mkv");
        stored = Recents.push(stored, "a.mkv", "smb://nas/a.mkv");
        final List<Recents.Recent> list = Recents.parse(stored);
        assertEquals(2, list.size());
        assertEquals("smb://nas/a.mkv", list.get(0).uri);
        assertEquals("file:///b.mkv", list.get(1).uri);
    }

    @Test
    public void cutToMax() {
        String stored = "";
        for (int i = 0; i < Recents.MAX + 5; i++) {
            stored = Recents.push(stored, "f" + i, "file:///f" + i);
        }
        final List<Recents.Recent> list = Recents.parse(stored);
        assertEquals(Recents.MAX, list.size());
        assertEquals("file:///f" + (Recents.MAX + 4), list.get(0).uri);
    }

    @Test
    public void tabsAndNewlinesInANameDoNotBreakTheList() {
        final List<Recents.Recent> list = Recents.parse(Recents.push("", "a\tb\nc", "file:///x"));
        assertEquals(1, list.size());
        assertEquals("a b c", list.get(0).name);
    }
}
