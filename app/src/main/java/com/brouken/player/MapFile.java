package com.brouken.player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A remembered map - positions, lengths, their times, watched marks - on disk, written so that a
 * write cut short leaves the last whole one in place.
 *
 * <p>These used to be written straight over the file. A box pulled from the wall mid-write left a
 * torn file, which read back as nothing at all - and the next write then saved that nothing over a
 * household's history. Now the map goes to a {@code .tmp} beside it, is flushed to the disk, and is
 * renamed over the old file: a rename within one directory is atomic, so a reader sees either the old
 * map or the new one, never half of one.
 *
 * <p>Atomic is not the same as one writer: two threads that each read, change and write back still
 * lose one of the changes. That is the callers' lock to hold - {@code Prefs.class}, see
 * {@link Prefs}.
 *
 * <p>Plain {@link File}s rather than a {@code Context}, so the unit tests can run it off the device.
 */
final class MapFile {

    static final String TMP = ".tmp";
    static final String BROKEN = ".broken";

    private MapFile() {
    }

    /**
     * The map in {@code file}; empty when there is none. A file that is there and does not read is
     * moved aside to {@code .broken} first, so the write that follows does not save an empty map over
     * what may still be got back by hand.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Long> read(final File file) {
        if (!file.exists()) {
            return Collections.emptyMap();
        }
        try (FileInputStream fis = new FileInputStream(file);
             ObjectInputStream is = new ObjectInputStream(fis)) {
            return (LinkedHashMap<String, Long>) is.readObject();
        } catch (Exception e) {
            final File aside = new File(file.getPath() + BROKEN);
            //noinspection ResultOfMethodCallIgnored
            aside.delete();
            if (!file.renameTo(aside)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            return Collections.emptyMap();
        }
    }

    /** Writes {@code map} over {@code file} whole or not at all. False when it was not written. */
    static boolean write(final File file, final Map<String, Long> map) {
        final File tmp = new File(file.getPath() + TMP);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            final ObjectOutputStream os = new ObjectOutputStream(fos);
            os.writeObject(map instanceof LinkedHashMap ? map : new LinkedHashMap<>(map));
            os.flush();
            // Onto the disk before the rename: otherwise a power cut can leave the rename done and the
            // bytes it points at not.
            fos.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(file) && !replace(tmp, file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return true;
    }

    /**
     * The rename again, for a file system whose plain rename will not go over a file that is there -
     * Windows, where the unit tests run. Android's is rename(2), which does, so on a box this is only
     * reached when that failed for good reason, and fails the same. java.nio.file is API 26: on the
     * Android 7 boxes it is not there at all, which is a failed write and not a crash.
     */
    private static boolean replace(final File from, final File to) {
        try {
            java.nio.file.Files.move(from.toPath(), to.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
