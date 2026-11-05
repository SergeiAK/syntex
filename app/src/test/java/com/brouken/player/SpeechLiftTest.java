package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * "Dialogue louder" on stereo and mono (see {@link BoostAudioProcessor#liftSpeech}): the middle's speech
 * band comes up, the sides and the middle's bass do not, and a discrete centre is still what a wide
 * track lifts.
 */
public class SpeechLiftTest {

    private static final int RATE = 48_000;
    private static final int AMPLITUDE = 8_000;

    /** Peak of each channel over the last half of a second of a sine fed through the processor. */
    private static float[] peaks(final boolean on, final int channels, final double hz,
                                 final boolean antiphase) throws Exception {
        final BoostAudioProcessor processor = new BoostAudioProcessor();
        processor.setCentreBoost(on, channels);
        processor.configure(new AudioProcessor.AudioFormat(RATE, channels, C.ENCODING_PCM_16BIT));
        processor.flush();
        final int frames = RATE;
        final ByteBuffer in = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) {
            final short s = (short) Math.round(AMPLITUDE * Math.sin(2 * Math.PI * hz * i / RATE));
            for (int c = 0; c < channels; c++) {
                in.putShort(antiphase && c == 1 ? (short) -s : s);
            }
        }
        in.flip();
        processor.queueInput(in);
        final ByteBuffer out = processor.getOutput();
        final float[] peak = new float[channels];
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                final short s = out.getShort();
                if (i >= frames / 2) {
                    peak[c] = Math.max(peak[c], Math.abs(s));
                }
            }
        }
        return peak;
    }

    private static double db(final float peak) {
        return 20 * Math.log10(peak / AMPLITUDE);
    }

    @Test
    public void voiceInTheMiddleComesUpSixDecibels() throws Exception {
        final float[] peak = peaks(true, 2, 1000, false);
        assertEquals(6.0, db(peak[0]), 0.5);
        assertEquals(6.0, db(peak[1]), 0.5);
    }

    @Test
    public void whatIsOnlyOnTheSidesStaysWhereItWas() throws Exception {
        final float[] peak = peaks(true, 2, 1000, true);
        assertEquals(0.0, db(peak[0]), 0.1);
        assertEquals(0.0, db(peak[1]), 0.1);
    }

    @Test
    public void bassAndTrebleInTheMiddleBarelyMove() throws Exception {
        assertTrue(db(peaks(true, 2, 50, false)[0]) < 0.5);
        assertTrue(db(peaks(true, 2, 15_000, false)[0]) < 0.5);
    }

    @Test
    public void monoIsAllMiddle() throws Exception {
        assertEquals(6.0, db(peaks(true, 1, 1000, false)[0]), 0.5);
    }

    @Test
    public void offIsUntouched() throws Exception {
        final float[] peak = peaks(false, 2, 1000, false);
        assertEquals(0.0, db(peak[0]), 0.01);
        assertEquals(0.0, db(peak[1]), 0.01);
    }

    /** A wide track still lifts its discrete centre (3 dB on a route that carries it), and only it. */
    @Test
    public void wideTrackLiftsItsCentreAsBefore() throws Exception {
        final float[] peak = peaks(true, 6, 1000, false);
        assertEquals(0.0, db(peak[0]), 0.01);
        assertEquals(3.0, db(peak[2]), 0.1);
        assertEquals(0.0, db(peak[3]), 0.01);
    }
}
