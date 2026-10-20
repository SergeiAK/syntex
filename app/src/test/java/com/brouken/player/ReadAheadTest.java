package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.annotation.Nullable;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ReadAheadTest {

    /** A file on the far side: answers by offset, records what was sent and whether it was waited on. */
    private static final class FakeRemote implements ReadAhead.Remote<long[]> {

        final byte[] file;
        final List<long[]> sent = new ArrayList<>();
        /** Offsets whose answer comes back this many bytes short. */
        long shortAt = -1;
        int shortBy;

        FakeRemote(final int size) {
            file = new byte[size];
            for (int i = 0; i < size; i++) {
                file[i] = (byte) (i * 31 + 7);
            }
        }

        @Override
        public long[] send(final long offset, final int length) {
            final long[] request = {offset, length};
            sent.add(request);
            return request;
        }

        @Nullable
        @Override
        public byte[] await(final long[] pending) {
            final int from = (int) pending[0];
            if (from >= file.length) {
                return null;
            }
            int to = (int) Math.min(file.length, from + pending[1]);
            if (from == shortAt) {
                to -= shortBy;
            }
            return Arrays.copyOfRange(file, from, to);
        }
    }

    private static byte[] readAll(final ReadAhead<long[]> ahead, final long start) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        long position = start;
        byte[] chunk;
        while ((chunk = ahead.next(position)) != null) {
            out.write(chunk, 0, chunk.length);
            position += chunk.length;
        }
        return out.toByteArray();
    }

    @Test
    public void handsOutTheFileInOrder() throws IOException {
        final FakeRemote remote = new FakeRemote(10 * 100 + 37);
        assertArrayEquals(remote.file, readAll(new ReadAhead<>(remote, 100, 0, remote.file.length), 0));
    }

    @Test
    public void growsFromOneRequestToTheLimit() throws IOException {
        final FakeRemote remote = new FakeRemote(100 * 100);
        final ReadAhead<long[]> ahead = new ReadAhead<>(remote, 100, 0, remote.file.length);
        assertEquals(100, ahead.next(0).length);
        // A probe that reads one chunk and closes has left only two more on the wire.
        assertEquals(3, remote.sent.size());
        assertEquals(2, ahead.inFlight());
        ahead.next(100);
        assertEquals(ReadAhead.MAX_AHEAD, ahead.inFlight());
        ahead.next(200);
        ahead.next(300);
        assertEquals(ReadAhead.MAX_AHEAD, ahead.inFlight());
    }

    @Test
    public void neverAsksPastTheEnd() throws IOException {
        final FakeRemote remote = new FakeRemote(10_000);
        // A bounded open: 250 bytes from 1000.
        final ReadAhead<long[]> ahead = new ReadAhead<>(remote, 100, 1000, 1250);
        assertArrayEquals(Arrays.copyOfRange(remote.file, 1000, 1250), readAll(ahead, 1000));
        long asked = 0;
        for (long[] request : remote.sent) {
            asked += request[1];
        }
        assertEquals(250, asked);
    }

    @Test
    public void aShortAnswerStartsOverFromWhereItStopped() throws IOException {
        final FakeRemote remote = new FakeRemote(1000);
        remote.shortAt = 100;
        remote.shortBy = 30;
        final ReadAhead<long[]> ahead = new ReadAhead<>(remote, 100, 0, remote.file.length);
        // The requests already sent for 200, 300 answer for the wrong offsets once 100 came back with 70.
        assertArrayEquals(remote.file, readAll(ahead, 0));
        boolean resent = false;
        for (long[] request : remote.sent) {
            resent |= request[0] == 170;
        }
        assertEquals(true, resent);
    }

    @Test
    public void endOfFileIsNull() throws IOException {
        final FakeRemote remote = new FakeRemote(50);
        // The open believed the file longer than it is now.
        final ReadAhead<long[]> ahead = new ReadAhead<>(remote, 100, 0, 200);
        assertEquals(50, ahead.next(0).length);
        assertNull(ahead.next(50));
    }
}
