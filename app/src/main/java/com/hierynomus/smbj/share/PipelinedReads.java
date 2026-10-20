package com.hierynomus.smbj.share;

import androidx.annotation.Nullable;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.messages.SMB2ReadResponse;

import java.util.concurrent.Future;

/**
 * The read smbj sends without waiting for the answer, reached from outside the library.
 *
 * <p>{@code File.read} sends one request and blocks until it is answered, so a reader on top of it
 * has one request in flight and the link sits idle for a round trip on every one. smbj does have the
 * two halves apart — {@code File.readAsync} sends, {@code Share.receive} waits and checks the status —
 * but 0.14.0 keeps both package-private, hence this class living in their package. It does nothing of
 * its own: {@link #await} is {@code Share.read} with the waiting moved to a time of the caller's
 * choosing, the same timeout and the same view of end-of-file as {@code File.read}.
 */
public final class PipelinedReads {

    private static final StatusHandler SUCCESS_OR_EOF = status ->
            status == NtStatus.STATUS_SUCCESS.getValue() || status == NtStatus.STATUS_END_OF_FILE.getValue();

    private PipelinedReads() {
    }

    /** Puts a read of {@code length} bytes at {@code offset} on the wire and returns at once. */
    public static Future<SMB2ReadResponse> send(final File file, final long offset, final int length) {
        return file.readAsync(offset, length);
    }

    /** The bytes a {@link #send} brought, {@code null} at end of file; throws as {@code File.read} does. */
    @Nullable
    public static byte[] await(final File file, final Future<SMB2ReadResponse> pending) {
        final SMB2ReadResponse response = file.share.receive(
                pending, "Read", file.fileId, SUCCESS_OR_EOF, file.share.getReadTimeout());
        if (response.getHeader().getStatusCode() == NtStatus.STATUS_END_OF_FILE.getValue()) {
            return null;
        }
        return response.getData();
    }
}
