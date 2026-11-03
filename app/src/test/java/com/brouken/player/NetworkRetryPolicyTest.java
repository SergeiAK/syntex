package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetworkRetryPolicyTest {

    @Test
    public void noNetworkIsAnOutage() {
        // As logged on the Dune with the Wi-Fi off (0054): the reconnect, and the read before it.
        assertTrue(NetworkRetryPolicy.outage(new ConnectException(
                "failed to connect to /192.168.1.40 (port 445) after 5000ms: connect failed: ENETUNREACH")));
        assertTrue(NetworkRetryPolicy.outage(new IOException(
                new SMBRuntimeException("DiskShare has already been closed"))));
        assertTrue(NetworkRetryPolicy.outage(new UnknownHostException("nas")));
    }

    @Test
    public void httpSocketFailuresAreAnOutage() {
        // HttpDataSourceException carries the socket's failure as its cause.
        assertTrue(NetworkRetryPolicy.outage(new IOException(new SocketTimeoutException("timeout"))));
    }

    @Test
    public void refusalsAndInterruptsAreNot() {
        assertFalse(NetworkRetryPolicy.outage(new SMBApiException(
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue(), SMB2MessageCommandCode.SMB2_CREATE, null)));
        assertFalse(NetworkRetryPolicy.outage(new IOException(new SMBRuntimeException(
                new TransportException(new InterruptedException())))));
        assertFalse(NetworkRetryPolicy.outage(new IOException("No valid varint length mask found")));
        Thread.interrupted();
    }

    @Test
    public void aboutAMinuteThenStop() {
        assertEquals(NetworkRetryPolicy.FIRST_DELAY_MS, NetworkRetryPolicy.delayFor(1));
        long total = 0;
        for (int i = 1; i <= NetworkRetryPolicy.MAX_OUTAGE_RETRIES; i++) {
            total += NetworkRetryPolicy.delayFor(i);
        }
        assertTrue(total >= 55_000 && total <= 65_000);
        assertEquals(C.TIME_UNSET, NetworkRetryPolicy.delayFor(NetworkRetryPolicy.MAX_OUTAGE_RETRIES + 1));
    }

    private static Map<String, List<String>> retryAfter(final String value) {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Retry-After", Collections.singletonList(value));
        return headers;
    }

    @Test
    public void tooManyRequestsWaitsWhatTheServerSays() {
        assertEquals(NetworkRetryPolicy.BUSY_DELAY_MS, NetworkRetryPolicy.busyDelayMs(null));
        assertEquals(NetworkRetryPolicy.BUSY_DELAY_MS, NetworkRetryPolicy.busyDelayMs(new HashMap<>()));
        assertEquals(30_000, NetworkRetryPolicy.busyDelayMs(retryAfter("30")));
        // Held to its bounds: not hammering, not an evening's wait.
        assertEquals(NetworkRetryPolicy.BUSY_MIN_MS, NetworkRetryPolicy.busyDelayMs(retryAfter("0")));
        assertEquals(NetworkRetryPolicy.BUSY_MAX_MS, NetworkRetryPolicy.busyDelayMs(retryAfter("3600")));
        // A date instead of seconds: the default minute.
        assertEquals(NetworkRetryPolicy.BUSY_DELAY_MS,
                NetworkRetryPolicy.busyDelayMs(retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")));
    }
}
