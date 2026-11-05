package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The remembered maps on disk (see {@link MapFile}): whole or not at all, and a torn one not covered. */
public class MapFileTest {

    @Rule
    public TemporaryFolder dir = new TemporaryFolder();

    private static LinkedHashMap<String, Long> map(final Object... pairs) {
        final LinkedHashMap<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void noFileIsEmpty() {
        assertTrue(MapFile.read(new File(dir.getRoot(), "positions")).isEmpty());
    }

    @Test
    public void writtenReadsBackInOrder() {
        final File file = new File(dir.getRoot(), "positions");
        final LinkedHashMap<String, Long> written = map("smb:///b.mkv", 2L, "smb:///a.mkv", 1L);
        assertTrue(MapFile.write(file, written));
        final Map<String, Long> read = MapFile.read(file);
        assertEquals(written, read);
        assertEquals(new ArrayList<>(written.keySet()), new ArrayList<>(read.keySet()));
        assertFalse("nothing left beside it", new File(file.getPath() + MapFile.TMP).exists());
    }

    /** What the old code wrote and what is on every box now: the same stream, so it reads unchanged. */
    @Test
    public void readsWhatTheOldWriterLeft() throws IOException {
        final File file = new File(dir.getRoot(), "positions");
        final LinkedHashMap<String, Long> old = map("smb:///film.mkv", 51_000L);
        try (FileOutputStream fos = new FileOutputStream(file);
             ObjectOutputStream os = new ObjectOutputStream(fos)) {
            os.writeObject(old);
        }
        assertEquals(old, MapFile.read(file));
    }

    /** A write cut off before the rename leaves a stray .tmp - and the last whole map where it was. */
    @Test
    public void cutOffWriteLeavesTheLastWholeOne() throws IOException {
        final File file = new File(dir.getRoot(), "positions");
        final LinkedHashMap<String, Long> whole = map("smb:///film.mkv", 51_000L);
        assertTrue(MapFile.write(file, whole));
        try (FileOutputStream torn = new FileOutputStream(file.getPath() + MapFile.TMP)) {
            torn.write(new byte[] {(byte) 0xAC, (byte) 0xED, 0, 5, 0x73});
        }
        assertEquals(whole, MapFile.read(file));
        // And the next write goes through over the stray.
        final LinkedHashMap<String, Long> next = map("smb:///film.mkv", 60_000L);
        assertTrue(MapFile.write(file, next));
        assertEquals(next, MapFile.read(file));
    }

    /** A torn file from before writes were atomic is moved aside, not covered by the next write. */
    @Test
    public void tornFileIsKeptAside() throws IOException {
        final File file = new File(dir.getRoot(), "positions");
        final byte[] torn = {(byte) 0xAC, (byte) 0xED, 0, 5, 0x73, 0x72};
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(torn);
        }
        assertTrue(MapFile.read(file).isEmpty());
        assertFalse(file.exists());
        final File aside = new File(file.getPath() + MapFile.BROKEN);
        assertEquals(torn.length, aside.length());

        assertTrue(MapFile.write(file, map("smb:///new.mkv", 1L)));
        assertEquals(torn.length, aside.length());
    }

    @Test
    public void writeIntoAMissingFolderFails() {
        final File file = new File(new File(dir.getRoot(), "gone"), "positions");
        assertFalse(MapFile.write(file, map("smb:///a.mkv", 1L)));
        assertFalse(file.exists());
    }

    /**
     * Read-modify-write under one lock, the way Prefs does it: many writers at once, every key lands.
     */
    @Test
    public void writersUnderOneLockLoseNothing() throws InterruptedException {
        final File file = new File(dir.getRoot(), "positions");
        final Object lock = new Object();
        final int threads = 8;
        final int each = 25;
        final List<Thread> all = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            all.add(new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    synchronized (lock) {
                        final LinkedHashMap<String, Long> known = new LinkedHashMap<>(MapFile.read(file));
                        known.put("smb:///" + id + "/" + i, (long) i);
                        MapFile.write(file, known);
                    }
                }
            }));
        }
        for (final Thread thread : all) {
            thread.start();
        }
        for (final Thread thread : all) {
            thread.join();
        }
        assertEquals(threads * each, MapFile.read(file).size());
    }
}
