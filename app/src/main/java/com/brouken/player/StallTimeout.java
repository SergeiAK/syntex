package com.brouken.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/**
 * A response that has started arriving and then stops is dropped after {@link #STALL_SEC} seconds of
 * silence, not the client's two minutes. The read fails with a socket timeout, which NetworkRetryPolicy
 * takes for a dropped connection: the load is asked again half a second later from where it stopped, and
 * what is buffered keeps playing in the meantime. Before, a CDN that kept the connection open and sent
 * nothing cost up to two minutes of spinner after a buffer of fifty seconds (Just+ 2.6.2 found the same).
 *
 * <p>Only once the body has started. Silence before the first byte is a server still preparing what was
 * asked for — a torrent backend fetching the piece behind a seek, a transcoder cutting a segment — and is
 * waited for as before, under the client's own read timeout (see PlayerActivity.MEDIA_HTTP_CLIENT).
 *
 * <p>The timeout is the socket's, reached through the body: OkHttp sets it again for the next exchange on
 * a connection, so nothing outlives the response it was set for.
 */
final class StallTimeout implements Interceptor {

    static final long STALL_SEC = 30;

    private final long stallMs;

    StallTimeout() {
        this(TimeUnit.SECONDS.toMillis(STALL_SEC));
    }

    StallTimeout(final long stallMs) {
        this.stallMs = stallMs;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull final Chain chain) throws IOException {
        final Response response = chain.proceed(chain.request());
        if (!response.isSuccessful()) {
            return response;
        }
        return response.newBuilder().body(new Body(response.body(), stallMs)).build();
    }

    private static final class Body extends ResponseBody {

        private final ResponseBody delegate;
        private final BufferedSource source;

        Body(final ResponseBody delegate, final long stallMs) {
            this.delegate = delegate;
            final BufferedSource raw = delegate.source();
            source = Okio.buffer(new ForwardingSource(raw) {
                private boolean armed;

                @Override
                public long read(@NonNull final Buffer sink, final long byteCount) throws IOException {
                    final long read = super.read(sink, byteCount);
                    if (!armed && read > 0) {
                        armed = true;
                        raw.timeout().timeout(stallMs, TimeUnit.MILLISECONDS);
                    }
                    return read;
                }
            });
        }

        @Nullable
        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @NonNull
        @Override
        public BufferedSource source() {
            return source;
        }
    }
}
