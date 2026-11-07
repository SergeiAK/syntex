package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class SubtitleOffsetMemoryTest {

    private final Map<String, String> map = new HashMap<>();
    private final SubtitleOffsetMemory memory = new SubtitleOffsetMemory(new SubtitleOffsetMemory.Store() {
        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, String value) {
            if (value == null) map.remove(key);
            else map.put(key, value);
        }
    });

    private static final int MAIN = SubtitleOffsetMemory.MAIN;

    @Test
    public void nothingRemembered() {
        assertNull(memory.find("e1", "dir:/s1", MAIN));
    }

    @Test
    public void fileComesBackWithItsOwn() {
        memory.remember("e1", "dir:/s1", MAIN, 1.25);
        memory.remember("e2", "dir:/s1", MAIN, -0.5);
        final SubtitleOffsetMemory.Found found = memory.find("e1", "dir:/s1", MAIN);
        assertEquals(1.25, found.sec, 1e-9);
        assertFalse(found.fromSeries);
    }

    @Test
    public void newEpisodeTakesTheSeriesLast() {
        memory.remember("e1", "dir:/s1", MAIN, 1.25);
        memory.remember("e2", "dir:/s1", MAIN, 2.0);
        final SubtitleOffsetMemory.Found found = memory.find("e3", "dir:/s1", MAIN);
        assertEquals(2.0, found.sec, 1e-9);
        assertTrue(found.fromSeries);
        assertNull(memory.find("e3", "dir:/other", MAIN));
    }

    @Test
    public void zeroClearsTheFileAndSetsTheSeriesInSync() {
        memory.remember("e1", "dir:/s1", MAIN, 1.25);
        memory.remember("e1", "dir:/s1", MAIN, 0);
        assertFalse(map.containsKey("file:1:e1"));
        final SubtitleOffsetMemory.Found found = memory.find("e1", "dir:/s1", MAIN);
        assertEquals(0, found.sec, 1e-9);
        assertTrue(found.fromSeries);
    }

    @Test
    public void linesAreApart() {
        memory.remember("e1", "dir:/s1", SubtitleOffsetMemory.SECONDARY, 3.0);
        assertNull(memory.find("e1", "dir:/s1", MAIN));
        assertEquals(3.0, memory.find("e1", "dir:/s1", SubtitleOffsetMemory.SECONDARY).sec, 1e-9);
    }

    @Test
    public void noSeriesKeepsTheFileOnly() {
        memory.remember("e1", null, MAIN, 0.75);
        assertEquals(0.75, memory.find("e1", null, MAIN).sec, 1e-9);
        assertNull(memory.find("e2", null, MAIN));
    }
}
