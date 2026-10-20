package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/** Which failures {@link SmbSessions} takes for a lost connection, as smbj 0.14 throws them. */
public class SmbFailureTest {

    @After
    public void clearFlag() {
        Thread.interrupted();
    }

    private static SMBApiException status(final long code) {
        return new SMBApiException(code, SMB2MessageCommandCode.SMB2_READ, null);
    }

    @Test
    public void transportFailuresAreLost() {
        // Share.receive: a 20 s timeout or a dead socket, wrapped twice, then once more by us.
        assertTrue(SmbSessions.lost(new IOException(new SMBRuntimeException(
                new TransportException(new TimeoutException())))));
        assertTrue(SmbSessions.lost(new IOException(new SMBRuntimeException(
                new TransportException(new SocketException("Connection reset"))))));
        assertTrue(SmbSessions.lost(new java.net.ConnectException("refused")));
        assertTrue(SmbSessions.lost(new IOException(new SocketTimeoutException("connect"))));
    }

    @Test
    public void closedShareAndNoCreditsAreLost() {
        assertTrue(SmbSessions.lost(new IOException(
                new SMBRuntimeException("DiskShare has already been closed"))));
        assertTrue(SmbSessions.lost(new SMBRuntimeException(
                "Not enough credits (0 available) to hand out 16 sequence numbers")));
    }

    @Test
    public void sessionEndedByTheHostIsLost() {
        assertTrue(SmbSessions.lost(new IOException(status(0xC0000203L)))); // USER_SESSION_DELETED
        assertTrue(SmbSessions.lost(status(0xC00000C9L))); // NETWORK_NAME_DELETED
        assertTrue(SmbSessions.lost(status(0xC000035CL))); // NETWORK_SESSION_EXPIRED
    }

    @Test
    public void refusalsAreNotLost() {
        assertFalse(SmbSessions.lost(status(NtStatus.STATUS_ACCESS_DENIED.getValue())));
        assertFalse(SmbSessions.lost(new IOException(
                status(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue()))));
        assertFalse(SmbSessions.lost(status(NtStatus.STATUS_SHARING_VIOLATION.getValue())));
        // Ours, not the connection's.
        assertFalse(SmbSessions.lost(new IOException("still cut off: player open")));
        assertFalse(SmbSessions.lost(null));
    }

    @Test
    public void interruptedIsNotLost() {
        // A wait the loader broke off: smbj wraps the InterruptedException and sets the flag again.
        final IOException wrapped = new IOException(new SMBRuntimeException(
                new TransportException(new InterruptedException())));
        assertTrue(SmbSessions.interrupted(wrapped));
        assertFalse(SmbSessions.lost(wrapped));

        // Waiting for credits, it says so in words, and the flag is set.
        final SMBRuntimeException credits = new SMBRuntimeException(
                "Got interrupted waiting for 1 to be available. Credits available at this moment: 575");
        assertTrue(SmbSessions.interrupted(credits));
        assertFalse(SmbSessions.lost(credits));

        Thread.currentThread().interrupt();
        assertFalse(SmbSessions.lost(new SMBRuntimeException(new TransportException(
                new SocketException("Socket closed")))));
    }

    @Test
    public void aSocketTimeoutIsNotAnInterrupt() {
        assertFalse(SmbSessions.interrupted(new SocketTimeoutException("read timed out")));
    }
}
