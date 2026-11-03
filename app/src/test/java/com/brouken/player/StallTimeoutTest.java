package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class StallTimeoutTest {

    /** A server that answers at once, sends {@code before} bytes after {@code firstByteMs}, then goes quiet. */
    private static ServerSocket server(final long firstByteMs, final int before) throws Exception {
        final ServerSocket server = new ServerSocket(0);
        final Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                final InputStream in = socket.getInputStream();
                // The request head, up to the blank line.
                int matched = 0;
                while (matched < 4) {
                    final int b = in.read();
                    if (b < 0) {
                        return;
                    }
                    matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : 0;
                }
                final OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(firstByteMs);
                out.write(new byte[before]);
                out.flush();
                Thread.sleep(10_000);
            } catch (final Exception ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static OkHttpClient client() {
        return new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .addNetworkInterceptor(new StallTimeout(500))
                .build();
    }

    @Test
    public void silenceAfterTheFirstBytesIsCut() throws Exception {
        try (ServerSocket server = server(0, 4096);
             Response response = client().newCall(new Request.Builder()
                     .url("http://127.0.0.1:" + server.getLocalPort() + "/").build()).execute()) {
            final InputStream body = response.body().byteStream();
            final byte[] buffer = new byte[8192];
            int got = 0;
            final long start = System.nanoTime();
            try {
                int n;
                while ((n = body.read(buffer)) > 0) {
                    got += n;
                }
                fail("the stream ended instead of timing out");
            } catch (final SocketTimeoutException expected) {
                final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertEquals(4096, got);
                // The stall's half second, not the client's five.
                assertTrue("took " + ms + " ms", ms < 3_000);
            }
        }
    }

    @Test
    public void silenceBeforeTheFirstByteIsWaitedFor() throws Exception {
        // Two seconds before anything, four times the stall: still read, under the client's own timeout.
        try (ServerSocket server = server(2_000, 100);
             Response response = client().newCall(new Request.Builder()
                     .url("http://127.0.0.1:" + server.getLocalPort() + "/").build()).execute()) {
            final byte[] buffer = new byte[100];
            final int n = response.body().byteStream().read(buffer);
            assertTrue(n > 0);
        }
    }
}
