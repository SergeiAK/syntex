package com.brouken.player;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * TV header: one of the main facts of a file - the resolution, the codec, the HDR, the audio format -
 * drawn as a tag, a light fill under brighter, heavier text, with the rest of the line left as plain
 * dimmed text beside it. A capsule round each field, or one round the whole line, gave the container and
 * the running time the same weight as the facts a viewer actually picks a file by (23.10.2026).
 *
 * <p>The fill is as tall as the font's ascent to descent, which a single-line TextView already holds, so
 * the tag needs no extra line height; the gap after it is part of the span, so a run of tags needs no
 * spaces between them.
 */
final class HeaderTagSpan extends ReplacementSpan {

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final int fill;
    private final int ink;
    private final int padH;
    private final int gapAfter;
    private final float radius;
    private final RectF rect = new RectF();

    HeaderTagSpan(final int fill, final int ink, final int padH, final int gapAfter, final float radius) {
        this.fill = fill;
        this.ink = ink;
        this.padH = padH;
        this.gapAfter = gapAfter;
        this.radius = radius;
    }

    @Override
    public int getSize(@NonNull final Paint paint, final CharSequence text, final int start, final int end,
                       @Nullable final Paint.FontMetricsInt fm) {
        if (fm != null) {
            paint.getFontMetricsInt(fm);
        }
        return Math.round(measure(paint, text, start, end)) + 2 * padH + gapAfter;
    }

    @Override
    public void draw(@NonNull final Canvas canvas, final CharSequence text, final int start, final int end,
                     final float x, final int top, final int y, final int bottom, @NonNull final Paint paint) {
        final Typeface typeface = paint.getTypeface();
        final int color = paint.getColor();
        final float width = measure(paint, text, start, end);
        final Paint.FontMetrics metrics = paint.getFontMetrics();
        rect.set(x, y + metrics.ascent, x + width + 2 * padH, y + metrics.descent);
        paint.setColor(fill);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setTypeface(MEDIUM);
        paint.setColor(ink);
        canvas.drawText(text, start, end, x + padH, y, paint);
        paint.setTypeface(typeface);
        paint.setColor(color);
    }

    private static float measure(final Paint paint, final CharSequence text, final int start, final int end) {
        final Typeface typeface = paint.getTypeface();
        paint.setTypeface(MEDIUM);
        final float width = paint.measureText(text, start, end);
        paint.setTypeface(typeface);
        return width;
    }
}
