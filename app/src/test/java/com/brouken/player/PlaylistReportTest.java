package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Section 7 of the playlist contract as {@link PlaylistReport} answers it. */
public class PlaylistReportTest {

    private static final long MIN = 60_000;

    @Test
    public void closedBeforeAnythingPlayedIsCancelled() {
        final PlaylistReport.Snapshot snapshot = new PlaylistReport(3, 1).snapshot();

        assertEquals("cancelled", snapshot.endBy);
        assertEquals(1, snapshot.index);
        assertArrayEquals(new long[]{-1, -1, -1}, snapshot.positionsMs);
        assertEquals(0, snapshot.durationMs);
        assertEquals(0, snapshot.history.size());
    }

    @Test
    public void autoNextReportsTheFirstWatchedAndTheSecondWhereLeft() {
        final PlaylistReport report = new PlaylistReport(3, 0);
        report.playing(0, "s1e1", 10 * MIN, 50 * MIN, 1000);
        report.playing(0, "s1e1", 49 * MIN, 50 * MIN, 1100);
        report.ended(0, 1200);
        report.playing(1, "s1e2", 5_000, 52 * MIN, 1210);
        report.playing(1, "s1e2", 7 * MIN, 52 * MIN, 1600);

        final PlaylistReport.Snapshot snapshot = report.snapshot();

        assertEquals("user", snapshot.endBy);
        assertEquals(1, snapshot.index);
        assertEquals("s1e2", snapshot.uri);
        assertEquals(7 * MIN, snapshot.positionMs);
        assertEquals(52 * MIN, snapshot.durationMs);
        assertArrayEquals(new long[]{50 * MIN, 7 * MIN, -1}, snapshot.positionsMs);
        assertEquals(2, snapshot.history.size());
        final PlaylistReport.Visit first = snapshot.history.get(0);
        assertEquals(0, first.index);
        assertEquals(1000, first.startedAtSec);
        assertEquals(1200, first.endedAtSec);
        assertEquals(50 * MIN, first.positionMs);
        assertEquals(1210, snapshot.history.get(1).startedAtSec);
        assertEquals(1600, snapshot.history.get(1).endedAtSec);
    }

    @Test
    public void aRebuildOfTheSameItemContinuesItsVisit() {
        final PlaylistReport report = new PlaylistReport(2, 0);
        report.playing(0, "720p", 3 * MIN, 40 * MIN, 100);
        // A quality switch: another uri, the same item.
        report.playing(0, "1080p", 3 * MIN, 40 * MIN, 130);

        final PlaylistReport.Snapshot snapshot = report.snapshot();

        assertEquals(1, snapshot.history.size());
        assertEquals("1080p", snapshot.uri);
        // The position did not move, so neither did ended_at.
        assertEquals(100, snapshot.history.get(0).endedAtSec);
    }

    @Test
    public void goingBackToAnItemOpensANewVisit() {
        final PlaylistReport report = new PlaylistReport(2, 0);
        report.playing(0, "a", MIN, 40 * MIN, 1);
        report.playing(1, "b", MIN, 40 * MIN, 2);
        report.playing(0, "a", 2 * MIN, 40 * MIN, 3);

        assertEquals(3, report.snapshot().history.size());
    }

    @Test
    public void unknownDurationIsZeroAtTheTopAndMinusOneInAVisit() {
        final PlaylistReport report = new PlaylistReport(1, 0);
        report.playing(0, "live", 30_000, -1, 1);
        report.ended(0, 2);

        final PlaylistReport.Snapshot snapshot = report.snapshot();

        assertEquals(0, snapshot.durationMs);
        assertEquals(-1, snapshot.history.get(0).durationMs);
        // Nothing to call "the end" by: the last position stands.
        assertEquals(30_000, snapshot.positionsMs[0]);
    }

    @Test
    public void theLastItemEndingCompletesThePlaylist() {
        final PlaylistReport report = new PlaylistReport(2, 1);
        report.playing(1, "b", 41 * MIN, 42 * MIN, 1);
        report.completed(1, 2);

        final PlaylistReport.Snapshot snapshot = report.snapshot();

        assertEquals("completion", snapshot.endBy);
        assertArrayEquals(new long[]{-1, 42 * MIN}, snapshot.positionsMs);
    }

    @Test
    public void anErrorClearsWhenSomethingPlaysAgain() {
        final PlaylistReport report = new PlaylistReport(2, 0);
        report.playing(0, "a", MIN, 40 * MIN, 1);
        report.failed(1, "b", "Cannot open the file");

        PlaylistReport.Snapshot snapshot = report.snapshot();
        assertEquals("error", snapshot.endBy);
        assertEquals("Cannot open the file", snapshot.errorMessage);
        assertEquals(1, snapshot.index);
        assertEquals("b", snapshot.uri);
        assertEquals(-1, snapshot.positionsMs[1]);

        report.playing(0, "a", 2 * MIN, 40 * MIN, 5);
        snapshot = report.snapshot();
        assertEquals("user", snapshot.endBy);
        assertNull(snapshot.errorMessage);
    }

    @Test
    public void theSameSnapshotIsNotSentTwice() {
        final PlaylistReport report = new PlaylistReport(1, 0);
        report.playing(0, "a", MIN, 40 * MIN, 1);

        assertNotNull(report.snapshotToSend());
        // Home, then the stop that follows it: nothing new.
        report.playing(0, "a", MIN, 40 * MIN, 9);
        assertNull(report.snapshotToSend());
        report.playing(0, "a", 2 * MIN, 40 * MIN, 70);
        assertNotNull(report.snapshotToSend());
    }

    @Test
    public void historyKeepsTheLastFiveHundredVisits() {
        final PlaylistReport report = new PlaylistReport(2, 0);
        for (int i = 0; i < PlaylistReport.HISTORY_CAP + 10; i++) {
            report.playing(i % 2, "x", 1000, 2000, i);
        }

        final PlaylistReport.Snapshot snapshot = report.snapshot();

        assertEquals(PlaylistReport.HISTORY_CAP, snapshot.history.size());
        assertEquals(10, snapshot.history.get(0).startedAtSec);
    }

    @Test
    public void survivesSaveAndRestore() {
        final PlaylistReport report = new PlaylistReport(3, 0);
        report.playing(0, "a", 49 * MIN, 50 * MIN, 1);
        report.ended(0, 2);
        report.playing(2, "c", 3 * MIN, 45 * MIN, 3);

        final PlaylistReport restored = PlaylistReport.restore(report.save(), 3, report.uri(),
                report.errorMessage());

        assertNotNull(restored);
        assertEquals(report.snapshot(), restored.snapshot());
        // The open visit goes on rather than a new one opening.
        restored.playing(2, "c", 4 * MIN, 45 * MIN, 9);
        assertEquals(2, restored.snapshot().history.size());
    }

    @Test
    public void restoreRefusesAnotherPlaylistsJournal() {
        final PlaylistReport report = new PlaylistReport(3, 2);

        assertNull(PlaylistReport.restore(report.save(), 2, null, null));
        assertNull(PlaylistReport.restore(null, 3, null, null));
    }
}
