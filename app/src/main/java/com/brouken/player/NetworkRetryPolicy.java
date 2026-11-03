package com.brouken.player;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

import java.util.List;
import java.util.Map;

/**
 * Media3's retry budget, with two exceptions at either end.
 *
 * <p>A share that refuses the password refuses it again, and the default policy spends three retries
 * and about three and a half seconds finding that out before anything is said - so that one is not
 * retried at all.
 *
 * <p>A network that has gone away is given a minute to come back. On the defaults a load fails for
 * good after a handful of retries with 0-4 s between them, and with no network each of those fails
 * at once: on the Dune over Wi-Fi switched off for 30 s, the player gave up 17 s in, with the rest
 * of the buffer unplayed, and its own three re-prepares ran out two seconds before the Wi-Fi was back
 * (0054, 20.10.2026). Here the load keeps trying every two seconds, the buffer plays on, and a spinner
 * covers whatever the buffer did not. A load that gets bytes again starts its count afresh.
 *
 * <p>A server that answers 429 (too many requests) is asked again three times, a minute apart or after
 * whatever its Retry-After says, while the buffer plays. On the defaults it was asked three times within
 * three seconds — the very thing it had just refused — and the video stopped on an error. Meanwhile the
 * loading screen says the server asked to wait, not 0.0 MB/s ({@link #busyUntilMs}).
 *
 * <p>Everything else keeps the default budget: a server hiccup is worth a second read, and a
 * refusal that is not about the password fails as it did.
 */
final class NetworkRetryPolicy extends DefaultLoadErrorHandlingPolicy {

    /** The first retry soon, for a connection that only needed making again. */
    static final long FIRST_DELAY_MS = 500;
    /** Then every two seconds: a Wi-Fi rejoins in five to ten. */
    static final long DELAY_MS = 2_000;
    /** About a minute in all; after that the network is not coming back on its own. */
    static final int MAX_OUTAGE_RETRIES = 30;
    /** HTTP 429: how many times it is asked again, and the wait when the server names none. */
    static final int MAX_BUSY_RETRIES = 3;
    static final long BUSY_DELAY_MS = 60_000;
    /** The bounds a Retry-After is held to: not hammering, not an evening's wait either. */
    static final long BUSY_MIN_MS = 5_000;
    static final long BUSY_MAX_MS = 120_000;

    /** Until when (elapsedRealtime) a load waits on a server that asked it to; 0 when none does. */
    static volatile long busyUntilMs;

    @Override
    public long getRetryDelayMsFor(@NonNull final LoadErrorInfo info) {
        if (NetworkFiles.needsPassword(info.exception)) {
            return C.TIME_UNSET;
        }
        final HttpDataSource.InvalidResponseCodeException busy = busy(info.exception);
        if (busy != null) {
            final long delay = info.errorCount > MAX_BUSY_RETRIES ? C.TIME_UNSET
                    : busyDelayMs(busy.headerFields);
            busyUntilMs = delay == C.TIME_UNSET ? 0 : SystemClock.elapsedRealtime() + delay;
            Utils.log("network: 429, retry " + info.errorCount + "/" + MAX_BUSY_RETRIES
                    + (delay == C.TIME_UNSET ? ", giving up" : " in " + delay + " ms"));
            return delay;
        }
        if (outage(info.exception)) {
            final long delay = delayFor(info.errorCount);
            Utils.log("network: retry " + info.errorCount + "/" + MAX_OUTAGE_RETRIES
                    + (delay == C.TIME_UNSET ? ", giving up" : " in " + delay + " ms") + ", "
                    + root(info.exception));
            return delay;
        }
        // The minimum below is raised for the outage; anything else fails where it used to.
        if (info.errorCount > super.getMinimumLoadableRetryCount(info.mediaLoadData.dataType)) {
            return C.TIME_UNSET;
        }
        return super.getRetryDelayMsFor(info);
    }

    /**
     * Media3 holds a failed load against the player once it has failed more than this many times,
     * even with the buffer still playing. The limit is kept in {@link #getRetryDelayMsFor} instead,
     * where the failure is known.
     */
    @Override
    public int getMinimumLoadableRetryCount(final int dataType) {
        return Integer.MAX_VALUE;
    }

    @Nullable
    private static HttpDataSource.InvalidResponseCodeException busy(@Nullable final Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException
                    && ((HttpDataSource.InvalidResponseCodeException) t).responseCode == 429) {
                return (HttpDataSource.InvalidResponseCodeException) t;
            }
        }
        return null;
    }

    /** Retry-After in seconds, held to its bounds; a minute when there is none or it is a date. */
    static long busyDelayMs(@Nullable final Map<String, List<String>> headers) {
        if (headers != null) {
            for (final Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey() == null || !"retry-after".equalsIgnoreCase(header.getKey())
                        || header.getValue() == null || header.getValue().isEmpty()) {
                    continue;
                }
                try {
                    final long ms = Long.parseLong(header.getValue().get(0).trim()) * 1000;
                    return Math.max(BUSY_MIN_MS, Math.min(BUSY_MAX_MS, ms));
                } catch (final NumberFormatException e) {
                    break;
                }
            }
        }
        return BUSY_DELAY_MS;
    }

    /** The wait before retry number {@code errorCount}, or {@link C#TIME_UNSET} to stop. */
    static long delayFor(final int errorCount) {
        if (errorCount > MAX_OUTAGE_RETRIES) {
            return C.TIME_UNSET;
        }
        return errorCount <= 1 ? FIRST_DELAY_MS : DELAY_MS;
    }

    /**
     * True when the failure is the network rather than the server: no route, no address, a connection
     * that dropped or stopped answering. Over SMB that is {@link SmbSessions#lost}; over http the same
     * socket failures inside Media3's own exception. A load the player itself broke off is not one.
     */
    static boolean outage(@Nullable final Throwable failure) {
        if (failure == null || SmbSessions.interrupted(failure)) {
            return false;
        }
        if (SmbSessions.lost(failure)) {
            return true;
        }
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.SocketException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private static String root(final Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
