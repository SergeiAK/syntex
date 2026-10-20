package com.brouken.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

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

    @Override
    public long getRetryDelayMsFor(@NonNull final LoadErrorInfo info) {
        if (NetworkFiles.needsPassword(info.exception)) {
            return C.TIME_UNSET;
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
