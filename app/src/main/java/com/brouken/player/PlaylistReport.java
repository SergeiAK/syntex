package com.brouken.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What a {@code playlist} launch is answered with: section 7 of {@code docs/PLAYLIST_API.md} (2.3.0
 * edition), in the shell's scope — positions, durations, the visit journal and how the session ended.
 * The track keys of section 7.2 ({@code audio_*}, {@code subtitle_*}, {@code voice_label}) are not
 * reported, as the request does not read them either.
 *
 * <p>Plain Java, fed by the activity from its playback poll and listener, so the rules are tested
 * without a device; the activity turns a {@link Snapshot} into intent extras. The journal lives with the
 * activity: it does not survive a process kill.
 */
final class PlaylistReport {

    /** Section 7.4 keeps the last 500 visits. */
    static final int HISTORY_CAP = 500;

    static final class Visit {
        int index;
        long startedAtSec;
        long endedAtSec;
        long positionMs;
        /** -1 = not known. */
        long durationMs = -1;

        Visit copy() {
            final Visit visit = new Visit();
            visit.index = index;
            visit.startedAtSec = startedAtSec;
            visit.endedAtSec = endedAtSec;
            visit.positionMs = positionMs;
            visit.durationMs = durationMs;
            return visit;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Visit)) {
                return false;
            }
            final Visit v = (Visit) o;
            return index == v.index && startedAtSec == v.startedAtSec && endedAtSec == v.endedAtSec
                    && positionMs == v.positionMs && durationMs == v.durationMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, startedAtSec, endedAtSec, positionMs, durationMs);
        }
    }

    /** One report, everything section 7.2 asks for in the shell's scope. */
    static final class Snapshot {
        String uri;
        int index;
        long positionMs;
        /** 0 = not known (unlike a visit's -1). */
        long durationMs;
        /** -1 = never opened; the item's duration = watched to the end. */
        long[] positionsMs;
        String endBy;
        String errorMessage;
        List<Visit> history;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Snapshot)) {
                return false;
            }
            final Snapshot s = (Snapshot) o;
            return index == s.index && positionMs == s.positionMs && durationMs == s.durationMs
                    && Objects.equals(uri, s.uri) && Arrays.equals(positionsMs, s.positionsMs)
                    && Objects.equals(endBy, s.endBy) && Objects.equals(errorMessage, s.errorMessage)
                    && Objects.equals(history, s.history);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uri, index, positionMs, durationMs, Arrays.hashCode(positionsMs), endBy,
                    errorMessage, history);
        }
    }

    private final long[] positionsMs;
    private final long[] durationsMs;
    private final List<Visit> history = new ArrayList<>();
    private Visit open;
    private int index;
    private String uri;
    private boolean completed;
    private String errorMessage;
    private Snapshot sent;

    PlaylistReport(int size, int startIndex) {
        positionsMs = new long[size];
        durationsMs = new long[size];
        Arrays.fill(positionsMs, -1);
        Arrays.fill(durationsMs, -1);
        index = startIndex;
    }

    /**
     * The item {@code item} is playing at {@code positionMs}. The first call, and the first after a move
     * to another item, opens a visit; a rebuild of the same item (quality switch) continues it.
     */
    void playing(int item, String itemUri, long positionMs, long durationMs, long nowSec) {
        if (item < 0 || item >= positionsMs.length) {
            return;
        }
        // Something plays again: the error is over (section 7.2).
        errorMessage = null;
        completed = false;
        index = item;
        if (itemUri != null) {
            uri = itemUri;
        }
        if (durationMs > 0) {
            durationsMs[item] = durationMs;
        }
        final long position = Math.max(0, positionMs);
        if (open == null || open.index != item) {
            open = new Visit();
            open.index = item;
            open.startedAtSec = nowSec;
            open.endedAtSec = nowSec;
            open.positionMs = position;
            history.add(open);
            if (history.size() > HISTORY_CAP) {
                history.remove(0);
            }
        } else if (open.positionMs != position) {
            open.positionMs = position;
            open.endedAtSec = nowSec;
        }
        open.durationMs = durationsMs[item];
        positionsMs[item] = position;
    }

    /**
     * The item played to its end (auto-next, or the last one ending). Reported as its duration; with no
     * duration known the last position stands.
     */
    void ended(int item, long nowSec) {
        if (item < 0 || item >= positionsMs.length || durationsMs[item] <= 0) {
            return;
        }
        positionsMs[item] = durationsMs[item];
        if (open != null && open.index == item && open.positionMs != durationsMs[item]) {
            open.positionMs = durationsMs[item];
            open.endedAtSec = nowSec;
        }
    }

    /** The whole playlist played out. */
    void completed(int lastItem, long nowSec) {
        ended(lastItem, nowSec);
        completed = true;
    }

    /** Playback failed on {@code item}; {@code message} is what the viewer was shown. */
    void failed(int item, String itemUri, String message) {
        if (item >= 0 && item < positionsMs.length) {
            index = item;
        }
        if (itemUri != null) {
            uri = itemUri;
        }
        errorMessage = message != null ? message : "";
    }

    Snapshot snapshot() {
        final Snapshot snapshot = new Snapshot();
        snapshot.uri = uri;
        snapshot.index = index;
        snapshot.positionsMs = positionsMs.clone();
        snapshot.positionMs = Math.max(0, positionsMs[index]);
        snapshot.durationMs = Math.max(0, durationsMs[index]);
        snapshot.history = new ArrayList<>(history.size());
        for (Visit visit : history) {
            snapshot.history.add(visit.copy());
        }
        snapshot.errorMessage = errorMessage;
        if (errorMessage != null) {
            snapshot.endBy = "error";
        } else if (completed) {
            snapshot.endBy = "completion";
        } else if (history.isEmpty()) {
            snapshot.endBy = "cancelled";
        } else {
            snapshot.endBy = "user";
        }
        return snapshot;
    }

    /**
     * The journal as numbers, for the saved instance state: section 7.4 wants it to survive a recreate
     * and a process kill. The uri and the error go beside it as strings ({@link #uri()},
     * {@link #errorMessage()}).
     */
    long[] save() {
        final int size = positionsMs.length;
        final long[] data = new long[2 + 2 * size + 5 * history.size()];
        data[0] = index;
        data[1] = completed ? 1 : 0;
        System.arraycopy(positionsMs, 0, data, 2, size);
        System.arraycopy(durationsMs, 0, data, 2 + size, size);
        int at = 2 + 2 * size;
        for (Visit visit : history) {
            data[at++] = visit.index;
            data[at++] = visit.startedAtSec;
            data[at++] = visit.endedAtSec;
            data[at++] = visit.positionMs;
            data[at++] = visit.durationMs;
        }
        return data;
    }

    /** What {@link #save()} wrote, or null when it does not fit a playlist of {@code size}. */
    static PlaylistReport restore(long[] data, int size, String uri, String errorMessage) {
        if (data == null || data.length < 2 + 2 * size || (data.length - 2 - 2 * size) % 5 != 0
                || data[0] < 0 || data[0] >= size) {
            return null;
        }
        final PlaylistReport report = new PlaylistReport(size, (int) data[0]);
        report.completed = data[1] == 1;
        System.arraycopy(data, 2, report.positionsMs, 0, size);
        System.arraycopy(data, 2 + size, report.durationsMs, 0, size);
        for (int at = 2 + 2 * size; at < data.length; at += 5) {
            final Visit visit = new Visit();
            visit.index = (int) data[at];
            visit.startedAtSec = data[at + 1];
            visit.endedAtSec = data[at + 2];
            visit.positionMs = data[at + 3];
            visit.durationMs = data[at + 4];
            report.history.add(visit);
        }
        // The visit in progress goes on after a recreate (section 7.4: a rebuild continues it).
        report.open = report.history.isEmpty() ? null : report.history.get(report.history.size() - 1);
        report.uri = uri;
        report.errorMessage = errorMessage;
        return report;
    }

    String uri() {
        return uri;
    }

    String errorMessage() {
        return errorMessage;
    }

    /**
     * The snapshot for the callback, or null when it is the same as the one sent last (section 8: Home
     * used to deliver one report twice). Marks it sent.
     */
    Snapshot snapshotToSend() {
        final Snapshot snapshot = snapshot();
        if (snapshot.equals(sent)) {
            return null;
        }
        sent = snapshot;
        return snapshot;
    }
}
