package com.brouken.player;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * Volume boost applied to the PCM stream itself, used where the LoudnessEnhancer effect is not
 * available: some devices (Lenovo/MediaTek among them) gate third-party audio effects, handing out an
 * effect that never reaches AudioFlinger — it stays uninitialized and silently does nothing. See
 * Utils.applyBoost, which routes the boost to whichever of the two actually works.
 * <p>
 * Media3 ships a GainProcessor, but it casts the scaled sample straight to short: an overflowing peak
 * wraps around into noise instead of clipping, which is exactly what boosting produces.
 */
class BoostAudioProcessor extends BaseAudioProcessor {

    /**
     * Where the centre sits in an interleaved frame, or -1 when this many channels carry no discrete
     * centre at all. Media3 delivers samples in the ascending bit order of the channel mask, and
     * FRONT_CENTER has only FRONT_LEFT and FRONT_RIGHT below it — so wherever a centre exists it is
     * the third sample of the frame. Four channels is quad (FL, FR, BL, BR) and has none; so do mono
     * and stereo, where the speech is in every channel and lifting every channel is the volume control.
     *
     * <p>A mask can in principle carry six channels without a centre, and then the third sample is a
     * surround. Nothing this side of the sink is told the mask — AudioProcessor.AudioFormat carries
     * only the count — so that case is left to the clip counter and the log line below to expose.
     */
    static int centreChannelIndex(int channelCount) {
        switch (channelCount) {
            case 3:
            case 5:
            case 6:
            case 7:
            case 8:
            case 10:
            case 12:
                return 2;
            default:
                return -1;
        }
    }

    // Written on the app thread at every volume change, read on the playback thread.
    private volatile float gain = 1f;
    // Whether the viewer asked for the lift at all. The amount is not a setting: it is decided per
    // configuration, since it depends on what the route will do with the channels.
    private volatile boolean centreBoost;
    // What this configuration's route earns, whether or not it is asked for: the lift can then be
    // switched mid-film without waiting for a reconfiguration. Written by onConfigure.
    private volatile float armedGain = 1f;
    // The amount in force, read on the playback thread. 1 = nothing to do.
    private volatile float centreGain = 1f;
    // What the route out of this device will take, so the lift can tell a discrete centre from one the
    // platform is about to fold into stereo. 0 = not known, treated as discrete.
    private volatile int routeChannelCount;
    // Said once, when a lifted centre first runs out of headroom: the effect is inaudible in a log and
    // invisible in a screenshot, and clipped dialogue is exactly what this setting can cause.
    private boolean clipReported;

    /**
     * How much of the speech band of the middle is added back to a stereo or mono track: 1 is the band
     * doubled, six decibels at its centre. Twice the three a discrete centre gets, because here the band
     * is all that is lifted - the music in the middle below 300 Hz and above 4 kHz, and everything at the
     * sides, stays where it was. A starting value, to be set by ear on the boxes.
     */
    static final float SPEECH_LIFT = 1f;
    /** The band dialogue lives in: under it the bass of the score, over it sibilance and cymbals. */
    static final float SPEECH_LOW_HZ = 300f;
    static final float SPEECH_HIGH_HZ = 4000f;

    // What this configuration earns on stereo and mono, where there is no centre channel to lift and the
    // speech is lifted out of the middle instead. Written by onConfigure.
    private volatile boolean speechArmed;
    // The same, in force: armed and asked for.
    private volatile boolean speechOn;
    // The band, as a high-pass at its bottom and a low-pass at its top. Built for the sample rate in
    // onConfigure; their state is the playback thread's alone.
    private Biquad speechHigh;
    private Biquad speechLow;

    /**
     * One second-order section (the RBJ cookbook's), transposed direct form II: two floats of state,
     * five multiplies a sample.
     */
    static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float z1, z2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        /** Butterworth (Q = 1/sqrt 2) high- or low-pass at {@code hz}, held under Nyquist. */
        static Biquad pass(boolean high, double hz, int sampleRate) {
            final double w0 = 2 * Math.PI * Math.min(hz, sampleRate * 0.45) / sampleRate;
            final double cos = Math.cos(w0);
            final double alpha = Math.sin(w0) / (2 * Math.sqrt(0.5));
            final double edge = high ? (1 + cos) / 2 : (1 - cos) / 2;
            return new Biquad(edge, high ? -(1 + cos) : 1 - cos, edge, 1 + alpha, -2 * cos, 1 - alpha);
        }

        float process(float x) {
            final float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void reset() {
            z1 = 0;
            z2 = 0;
        }
    }

    /** The speech band of one sample of the middle. */
    private float speechBand(float middle) {
        return speechLow.process(speechHigh.process(middle));
    }

    void setGain(float gain) {
        this.gain = gain;
    }

    /**
     * Arms the lift, or turns it off with 1. The amount is decided per configuration from the route:
     * see {@link #centreGainFor}.
     */
    void setCentreBoost(boolean on, int routeChannelCount) {
        this.centreBoost = on;
        this.routeChannelCount = routeChannelCount;
        centreGain = on ? armedGain : 1f;
        speechOn = on && speechArmed;
    }

    /** The same, on the route already known: the session's switch, flipped while the film plays. */
    void setCentreBoost(boolean on) {
        setCentreBoost(on, routeChannelCount);
    }

    /**
     * How much to lift the centre of a track this wide on a route that wide.
     *
     * <p>Two cases, because they have different headroom. When the route carries the channels, the
     * centre reaches the speakers as itself and a lift is spent entirely on it: three decibels, which is
     * audible on a buried line and still leaves room over an ordinary peak.
     *
     * <p>When the route is narrower, the platform folds the track into stereo and the centre arrives
     * attenuated by the downmix — about three decibels of it — mixed under everything else. Six
     * decibels here is three after the fold, the same distance as the other case. The reference player
     * goes much further, setting its downmix centre coefficient to 3.0 against a default of 0.707, but
     * that value lives inside a matrix ffmpeg normalises afterwards, so it buys emphasis rather than
     * level. Ours is applied to the samples themselves, before a fold this player does not control, and
     * copying the number literally would clip the dialogue instead of clarifying it.
     */
    static float centreGainFor(int channelCount, int routeChannelCount) {
        if (centreChannelIndex(channelCount) < 0) {
            return 1f;
        }
        return routeChannelCount > 0 && routeChannelCount < channelCount ? 2f : 1.413f;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        // Said once per configuration, because the effect itself cannot be seen from a log or a
        // screenshot: this is the line that shows the lift was actually armed, on how many channels, and
        // which of its two amounts the route earned.
        final float armed = centreGainFor(inputAudioFormat.channelCount, routeChannelCount);
        armedGain = armed;
        centreGain = centreBoost ? armed : 1f;
        // Stereo and mono have no centre channel: their speech sits in the middle of the image, and it
        // is the middle's speech band that is lifted (see queueInput). Quad has neither - no centre,
        // and a middle that is two pairs - and is left alone.
        final boolean speech = inputAudioFormat.channelCount <= 2 && inputAudioFormat.sampleRate > 0;
        if (speech) {
            speechHigh = Biquad.pass(true, SPEECH_LOW_HZ, inputAudioFormat.sampleRate);
            speechLow = Biquad.pass(false, SPEECH_HIGH_HZ, inputAudioFormat.sampleRate);
        }
        speechArmed = speech;
        speechOn = centreBoost && speech;
        clipReported = false;
        if (centreBoost) {
            Utils.log("centre boost: " + inputAudioFormat.channelCount + " channels, route "
                    + (routeChannelCount > 0 ? String.valueOf(routeChannelCount) : "unknown ")
                    + (speech ? ", lifting the middle " + Math.round(SPEECH_LOW_HZ) + "-"
                            + Math.round(SPEECH_HIGH_HZ) + " Hz by "
                            + Math.round(20 * Math.log10(1 + SPEECH_LIFT)) + " dB"
                            : armed == 1f ? ", no centre to lift"
                            : ", lifting by " + Math.round(20 * Math.log10(armed)) + " dB"));
        }
        // NOT_SET keeps this processor out of the chain: passthrough bitstreams and any other encoding
        // are passed on untouched.
        return inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
                || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                ? inputAudioFormat : AudioFormat.NOT_SET;
    }

    /** Rounds to a sample and holds it inside the range, reporting the first time it has to. */
    private short clip(float scaled) {
        final int sample = Math.round(scaled);
        if (sample > Short.MAX_VALUE || sample < Short.MIN_VALUE) {
            if (!clipReported) {
                clipReported = true;
                Utils.log("centre boost: clipping on this mix, the lift has no headroom here");
            }
            return sample > 0 ? Short.MAX_VALUE : Short.MIN_VALUE;
        }
        return (short) sample;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining()) {
            // The pipeline drains itself with the shared empty buffer, which replaceOutputBuffer(0)
            // would hand straight back — copying a buffer onto itself throws.
            return;
        }
        // Read once: the gesture can change it while a buffer is being scaled.
        final float gain = this.gain;
        final int channelCount = inputAudioFormat.channelCount;
        final float centreGain = this.centreGain;
        final int centre = centreGain != 1f ? centreChannelIndex(channelCount) : -1;
        final ByteBuffer outputBuffer = replaceOutputBuffer(inputBuffer.remaining());
        // On the format in force, not only the flag: onConfigure arms for the next format while buffers
        // of the one before may still be coming through.
        if (speechOn && channelCount <= 2 && speechHigh != null) {
            liftSpeech(inputBuffer, outputBuffer, gain, channelCount);
        } else if (gain == 1f && centre < 0) {
            outputBuffer.put(inputBuffer);
        } else if (gain == 1f && inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            // The usual case with the lift on: the volume is where the viewer left it and five channels
            // out of six need no arithmetic at all. Copy the block, then rewrite every centre sample in
            // place — cheaper than a per-sample loop on a box that has to keep up in software.
            final int start = outputBuffer.position();
            final int frames = inputBuffer.remaining() / (2 * channelCount);
            outputBuffer.put(inputBuffer);
            for (int frame = 0; frame < frames; frame++) {
                final int at = start + (frame * channelCount + centre) * 2;
                outputBuffer.putShort(at, clip(outputBuffer.getShort(at) * centreGain));
            }
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            // Media3 hands whole frames, so the channel counter starts at the first channel of a frame
            // every time and needs nothing carried between buffers.
            int channel = 0;
            while (inputBuffer.hasRemaining()) {
                final float scale = channel == centre ? gain * centreGain : gain;
                outputBuffer.putShort(clip(inputBuffer.getShort() * scale));
                if (++channel == channelCount) {
                    channel = 0;
                }
            }
        } else {
            // Float in, float out. Unreachable while the sink puts its own conversion to 16-bit ahead of
            // this processor, which it has always done; kept so that a build where it does not is quiet
            // rather than wrong.
            int channel = 0;
            while (inputBuffer.hasRemaining()) {
                final float scale = channel == centre ? gain * centreGain : gain;
                outputBuffer.putFloat(Math.max(-1f, Math.min(1f, inputBuffer.getFloat() * scale)));
                if (++channel == channelCount) {
                    channel = 0;
                }
            }
        }
        outputBuffer.flip();
    }

    /**
     * Stereo and mono: the middle, M = (L + R) / 2, through the speech band and added back to both
     * sides. What is only on one side, L - R, is untouched - so a score spread wide and effects panned
     * off the centre keep their level, and the voice, which a stereo mix puts in the middle, comes up.
     * Mono is all middle, and gets the band lift on its one channel.
     */
    private void liftSpeech(ByteBuffer in, ByteBuffer out, float gain, int channelCount) {
        final float lift = SPEECH_LIFT;
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            if (channelCount == 2) {
                while (in.hasRemaining()) {
                    final float left = in.getShort();
                    final float right = in.getShort();
                    final float band = lift * speechBand((left + right) * 0.5f);
                    out.putShort(clip((left + band) * gain));
                    out.putShort(clip((right + band) * gain));
                }
            } else {
                while (in.hasRemaining()) {
                    final float x = in.getShort();
                    out.putShort(clip((x + lift * speechBand(x)) * gain));
                }
            }
        } else if (channelCount == 2) {
            while (in.hasRemaining()) {
                final float left = in.getFloat();
                final float right = in.getFloat();
                final float band = lift * speechBand((left + right) * 0.5f);
                out.putFloat(Math.max(-1f, Math.min(1f, (left + band) * gain)));
                out.putFloat(Math.max(-1f, Math.min(1f, (right + band) * gain)));
            }
        } else {
            while (in.hasRemaining()) {
                final float x = in.getFloat();
                out.putFloat(Math.max(-1f, Math.min(1f, (x + lift * speechBand(x)) * gain)));
            }
        }
    }

    @Override
    protected void onFlush() {
        // A seek lands somewhere else: the filters' memory of the last place would ring into the first
        // milliseconds of the new one.
        if (speechHigh != null) {
            speechHigh.reset();
            speechLow.reset();
        }
    }

    @Override
    protected void onReset() {
        speechHigh = null;
        speechLow = null;
        speechArmed = false;
        speechOn = false;
    }
}
