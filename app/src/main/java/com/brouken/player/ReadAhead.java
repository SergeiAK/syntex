package com.brouken.player;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Keeps a few reads ahead of a sequential reader on the wire, so the next chunk is already on its way
 * while the current one is being handed out.
 *
 * <p>One request at a time leaves the link idle between an answer and the next request: measured on
 * the Amlogic box over Wi-Fi, 25 MB/s against 36 MB/s of plain TCP on the same link, with no thread of
 * the player near a full core. samba's own client reads the same way this does ({@code cli_pull}:
 * requests of the server's maximum, several in flight, handed out by offset).
 *
 * <p>How far ahead grows from one request to {@link #MAX_AHEAD} as the reader keeps reading. A source is
 * opened afresh for every seek and the extractor's probes read a few kilobytes and close: answers
 * already asked for cannot be called back, and they sit on the connection in front of the next open's
 * first request. Starting at one keeps a short read from paying for a long one.
 *
 * @param <P> what a sent request is waited on by
 */
final class ReadAhead<P> {

    /** Sends a read and waits for it, apart. */
    interface Remote<P> {

        P send(long offset, int length) throws IOException;

        /** The bytes, as many as came, or {@code null} at end of file. */
        @Nullable
        byte[] await(P pending) throws IOException;
    }

    /**
     * Four mebibytes: enough to cover a round trip and the server's turn with room to spare, little
     * enough that what a closed source leaves on the connection costs the next open ~0.1 s on Wi-Fi.
     */
    static final int MAX_AHEAD = 4;

    private static final class Sent<P> {
        final long offset;
        final P pending;

        Sent(final long offset, final P pending) {
            this.offset = offset;
            this.pending = pending;
        }
    }

    private final Remote<P> remote;
    private final int chunk;
    private final long end;
    private final ArrayDeque<Sent<P>> ahead = new ArrayDeque<>();
    private long nextOffset;
    private int depth = 1;

    /** Reads up to {@code end}, a byte past the last wanted, in requests of {@code chunk}. */
    ReadAhead(final Remote<P> remote, final int chunk, final long start, final long end) {
        this.remote = remote;
        this.chunk = chunk;
        this.end = end;
        this.nextOffset = start;
    }

    /**
     * The bytes from {@code position} on, as one answer brought them, or {@code null} at end of file.
     * Called with the position just past the previous chunk, it is served from what is already on the
     * way; anything else — an answer that came short — starts over from there.
     */
    @Nullable
    byte[] next(final long position) throws IOException {
        final Sent<P> head = ahead.peekFirst();
        if (head == null || head.offset != position) {
            // What is in flight answers for the wrong offsets; it is left to arrive and be dropped.
            ahead.clear();
            nextOffset = position;
        }
        topUp();
        if (ahead.isEmpty()) {
            return null;
        }
        final byte[] data = remote.await(ahead.removeFirst().pending);
        if (data != null && data.length > 0) {
            depth = Math.min(depth * 2, MAX_AHEAD);
            topUp();
        }
        return data;
    }

    /** Requests sent and not yet handed out. */
    int inFlight() {
        return ahead.size();
    }

    private void topUp() throws IOException {
        while (ahead.size() < depth && nextOffset < end) {
            final int length = (int) Math.min(chunk, end - nextOffset);
            ahead.addLast(new Sent<>(nextOffset, remote.send(nextOffset, length)));
            nextOffset += length;
        }
    }
}
