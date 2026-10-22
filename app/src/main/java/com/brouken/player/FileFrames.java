package com.brouken.player;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.datasource.DataSource;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.inspector.MediaExtractorCompat;

import java.nio.ByteBuffer;

/**
 * Seek preview frames cut out of the file itself, for a file with no trickplay set beside it.
 *
 * <p>One extractor of its own over the same file, opened once, so the index is read once and every
 * frame after it costs a seek to the preceding keyframe, that one sample and its decode. A decoder of
 * its own too: software where the device has one for the format, so the film's hardware decoder is left
 * alone, and a second hardware instance only where the software one refuses (10-bit HEVC on a Shield).
 *
 * <p>Measured 22.10.2026 over SMB (fork-roadmap.md): Shield 0.12-0.2 s a frame at 1080p, 0.2-0.5 s at
 * 4K, 10-bit HEVC included; Dune 0.45-0.75 s at 1080p H.264, and no HEVC at all - its Amlogic decoder
 * allows two instances and its software HEVC fails on 4K 10-bit. Where nothing opens, {@link #open}
 * answers null and the card goes without a frame. Every frame is timed into the log under this TAG.
 */
final class FileFrames {

    private static final String TAG = "FileFrames";
    static final int WIDTH = 320;

    private final MediaExtractorCompat extractor;
    private final MediaCodec codec;
    private final String codecName;
    private final ByteBuffer sample;

    private FileFrames(final MediaExtractorCompat extractor, final MediaCodec codec, final String codecName,
                       final int maxInput) {
        this.extractor = extractor;
        this.codec = codec;
        this.codecName = codecName;
        this.sample = ByteBuffer.allocateDirect(maxInput);
    }

    @WorkerThread
    @Nullable
    static FileFrames open(final Uri uri, final DataSource.Factory source, final ExtractorsFactory extractors) {
        final long t0 = SystemClock.elapsedRealtime();
        final MediaExtractorCompat extractor = new MediaExtractorCompat(extractors, source);
        try {
            extractor.setDataSource(uri, 0);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (format == null) {
                Log.d(TAG, "open: no video track");
                extractor.release();
                return null;
            }
            extractor.selectTrack(track);
            final long t1 = SystemClock.elapsedRealtime();
            final String mime = format.getString(MediaFormat.KEY_MIME);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            // The software one first, then every decoder for the type in the device's order: a second
            // hardware instance is what a 10-bit HEVC is left with where the software one refuses it.
            final java.util.List<String> names = new java.util.ArrayList<>();
            final String soft = pickDecoder(format);
            if (soft != null) {
                names.add(soft);
            }
            for (final MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || names.contains(info.getName())) {
                    continue;
                }
                for (final String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) {
                        names.add(info.getName());
                        break;
                    }
                }
            }
            MediaCodec codec = null;
            for (final String name : names) {
                MediaCodec c = null;
                try {
                    c = MediaCodec.createByCodecName(name);
                    c.configure(format, null, null, 0);
                    c.start();
                    codec = c;
                    break;
                } catch (final Exception e) {
                    Log.d(TAG, "decoder " + name + " refused: " + e);
                    if (c != null) {
                        c.release();
                    }
                }
            }
            if (codec == null) {
                extractor.release();
                return null;
            }
            int maxInput = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            maxInput = Math.max(maxInput, 8 << 20);
            Log.d(TAG, "open " + mime + " " + format.getInteger(MediaFormat.KEY_WIDTH) + "x"
                    + format.getInteger(MediaFormat.KEY_HEIGHT) + " via " + codec.getName()
                    + ": extractor " + (t1 - t0) + " ms, decoder " + (SystemClock.elapsedRealtime() - t1) + " ms");
            return new FileFrames(extractor, codec, codec.getName(), maxInput);
        } catch (final Exception | OutOfMemoryError e) {
            Log.d(TAG, "open failed after " + (SystemClock.elapsedRealtime() - t0) + " ms: " + e);
            extractor.release();
            return null;
        }
    }

    /** A software decoder for the format if the device has one, else null for the default. */
    @Nullable
    private static String pickDecoder(final MediaFormat format) {
        final String mime = format.getString(MediaFormat.KEY_MIME);
        for (final MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            final String n = info.getName();
            if (!n.startsWith("OMX.google.") && !n.startsWith("c2.android.")) {
                continue;
            }
            for (final String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    try {
                        if (info.getCapabilitiesForType(type).isFormatSupported(withoutRate(format))) {
                            return n;
                        }
                    } catch (final RuntimeException ignored) {
                        // The check itself is unreliable on some boxes; the default decoder then.
                    }
                }
            }
        }
        return null;
    }

    private static MediaFormat withoutRate(final MediaFormat f) {
        final MediaFormat c = MediaFormat.createVideoFormat(f.getString(MediaFormat.KEY_MIME),
                f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT));
        return c;
    }

    /** The keyframe at or before the position, scaled to {@link #WIDTH}; null when it cannot be had. */
    @WorkerThread
    @Nullable
    synchronized Bitmap frame(final long positionMs) {
        final long t0 = SystemClock.elapsedRealtime();
        try {
            codec.flush();
            extractor.seekTo(positionMs * 1000, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC);
            sample.clear();
            final int size = extractor.readSampleData(sample, 0);
            final long sampleUs = extractor.getSampleTime();
            final long t1 = SystemClock.elapsedRealtime();
            if (size < 0) {
                Log.d(TAG, "frame " + positionMs + ": no sample");
                return null;
            }
            final int in = codec.dequeueInputBuffer(500_000);
            if (in < 0) {
                Log.d(TAG, "frame " + positionMs + ": no input buffer");
                return null;
            }
            final ByteBuffer buffer = codec.getInputBuffer(in);
            sample.limit(size);
            sample.position(0);
            buffer.put(sample);
            codec.queueInputBuffer(in, 0, size, sampleUs, MediaCodec.BUFFER_FLAG_KEY_FRAME);
            // End of stream at once, so a decoder that holds frames for reordering lets this one go.
            final int eos = codec.dequeueInputBuffer(500_000);
            if (eos >= 0) {
                codec.queueInputBuffer(eos, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final long deadline = SystemClock.elapsedRealtime() + 3000;
            while (SystemClock.elapsedRealtime() < deadline) {
                final int out = codec.dequeueOutputBuffer(info, 100_000);
                if (out < 0) {
                    continue;
                }
                if (info.size == 0 && (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    codec.releaseOutputBuffer(out, false);
                    Log.d(TAG, "frame " + positionMs + ": end of stream without a picture");
                    return null;
                }
                final long t2 = SystemClock.elapsedRealtime();
                final Image image = codec.getOutputImage(out);
                final long t3 = SystemClock.elapsedRealtime();
                final Bitmap bitmap = image != null ? scaled(image) : null;
                if (image != null) {
                    image.close();
                }
                codec.releaseOutputBuffer(out, false);
                final long t4 = SystemClock.elapsedRealtime();
                Log.d(TAG, "frame " + positionMs + " -> " + sampleUs / 1000 + " (" + size / 1024 + " KiB): seek+read "
                        + (t1 - t0) + " ms, decode " + (t2 - t1) + " ms, image " + (t3 - t2) + " ms, scale "
                        + (t4 - t3) + " ms, total " + (t4 - t0) + " ms, " + codecName);
                return bitmap;
            }
            Log.d(TAG, "frame " + positionMs + ": no picture in 3 s");
            return null;
        } catch (final Exception | OutOfMemoryError e) {
            Log.d(TAG, "frame " + positionMs + " failed after " + (SystemClock.elapsedRealtime() - t0) + " ms: " + e);
            return null;
        }
    }

    /**
     * Nearest-neighbour from the luma and chroma planes straight to ARGB, at preview size. Whole rows are
     * copied out of the planes first: an indexed get per pixel on a 4K plane cost 2-3 s a frame on Dune.
     * A 10-bit picture (P010) keeps its high byte. BT.709 matrix at HD and above, BT.601 below.
     */
    private static Bitmap scaled(final Image image) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int outW = WIDTH;
        final int outH = Math.max(1, Math.round((float) h * outW / w));
        // ImageFormat.YCBCR_P010, by number: the constant is API 29 and this class runs from 23.
        final int high = image.getFormat() == 0x36 ? 1 : 0;
        final boolean hd = h >= 720;
        final int cr = hd ? 1836 : 1634;
        final int cgU = hd ? 218 : 400;
        final int cgV = hd ? 546 : 833;
        final int cb = hd ? 2163 : 2066;
        final Image.Plane[] p = image.getPlanes();
        final ByteBuffer y = p[0].getBuffer();
        final ByteBuffer u = p[1].getBuffer();
        final ByteBuffer v = p[2].getBuffer();
        final int yRow = p[0].getRowStride();
        final int yPix = p[0].getPixelStride();
        final int cRow = p[1].getRowStride();
        final int cPix = p[1].getPixelStride();
        final byte[] yLine = new byte[yRow];
        final byte[] uLine = new byte[cRow];
        final byte[] vLine = new byte[cRow];
        final int[] xs = new int[outW];
        for (int i = 0; i < outW; i++) {
            xs[i] = i * w / outW;
        }
        final int[] argb = new int[outW * outH];
        for (int j = 0; j < outH; j++) {
            final int sy = j * h / outH;
            row(y, sy * yRow, yLine);
            row(u, (sy >> 1) * cRow, uLine);
            row(v, (sy >> 1) * cRow, vLine);
            for (int i = 0; i < outW; i++) {
                final int sx = xs[i];
                final int Y = (yLine[sx * yPix + high] & 0xff) - 16;
                final int ci = (sx >> 1) * cPix + high;
                final int U = (uLine[ci] & 0xff) - 128;
                final int V = (vLine[ci] & 0xff) - 128;
                final int yy = 1192 * Math.max(0, Y);
                final int r = clamp((yy + cr * V) >> 10);
                final int g = clamp((yy - cgV * V - cgU * U) >> 10);
                final int b = clamp((yy + cb * U) >> 10);
                argb[j * outW + i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888);
    }

    /** One row of a plane into the array, as much of it as the plane holds (its last row may be short). */
    private static void row(final ByteBuffer plane, final int offset, final byte[] into) {
        final ByteBuffer view = plane.duplicate();
        view.position(Math.min(offset, view.limit()));
        view.get(into, 0, Math.min(into.length, view.remaining()));
    }

    private static int clamp(final int c) {
        return c < 0 ? 0 : Math.min(255, c);
    }

    synchronized void release() {
        try {
            codec.stop();
        } catch (final RuntimeException ignored) {
        }
        codec.release();
        extractor.release();
    }
}
