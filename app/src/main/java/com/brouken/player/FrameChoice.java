package com.brouken.player;

import androidx.annotation.Nullable;

import java.util.Locale;

/** A frame mode as it is remembered for a series: resize mode, zoom and forced ratio, one string. */
final class FrameChoice {

    final int resizeMode;
    final float scale;
    final float aspectRatio;

    FrameChoice(final int resizeMode, final float scale, final float aspectRatio) {
        this.resizeMode = resizeMode;
        this.scale = scale;
        this.aspectRatio = aspectRatio;
    }

    String encode() {
        return String.format(Locale.ROOT, "%d|%.4f|%.4f", resizeMode, scale, aspectRatio);
    }

    @Nullable
    static FrameChoice decode(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        final String[] parts = value.split("\\|");
        if (parts.length != 3) {
            return null;
        }
        try {
            final float scale = Float.parseFloat(parts[1]);
            final float ratio = Float.parseFloat(parts[2]);
            if (!(scale > 0) || scale > 10 || !(ratio >= 0) || ratio > 10) {
                return null;
            }
            return new FrameChoice(Integer.parseInt(parts[0]), scale, ratio);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
