package com.brouken.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * The seek readout, standing on the time bar over the place the seek lands: "+06:06 ▶▶ 43:35 (33%)"
 * going forward, the whole line mirrored going back — "(21%) 31:23 ◀◀ −06:06" — so it reads from the
 * delta outwards to the target either way, and the arrows point the way the seek is moving.
 *
 * <p>The way it is moving, not the sign of the delta: the delta is counted from where the film is, so after
 * an overshoot it stays "+" while the seek comes back towards it. Arrows that kept pointing right there
 * read as the remote not doing what was pressed.
 *
 * <p>It used to be the top line every passing message shares. The eye is on the bar while seeking, and
 * the bar is up for every remote seek, so the readout moved down to it, onto a pointer that says which
 * point of the bar it is talking about. The plate is the readout's own (sheet surface, ink_high), one
 * line instead of two: the delta and the target used to be stacked, and a stacked pair is read twice.
 *
 * <p>Positioned by hand, not by layout: it rides a point of a view that lives in another subtree (the
 * controller's bar), and that point moves with every step.
 */
final class SeekCard extends LinearLayout {

    /** The trickplay frame over the line, during a long seek; GONE otherwise. */
    private final ImageView still;
    private final LinearLayout line;
    private final TextView left;
    private final ImageView icon;
    private final TextView right;
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pointer = new Path();
    private final RectF plate = new RectF();
    private final float radius;
    private final int pointerHeight;
    private final int edgeMargin;
    private final int barGap;
    private float pointerX;
    private boolean anchored;
    // Which way the seek is moving: from the last target it showed, or for a card just shown, the delta's sign.
    private boolean back;
    private long shownTargetMs = -1;
    // What the card was last placed by, so a frame arriving later (and growing the card) can place it again.
    @Nullable private CustomDefaultTimeBar placedBar;
    private long placedTargetMs;
    private long placedDurationMs;

    SeekCard(final Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        setVisibility(GONE);
        radius = Utils.dpToPx(28);
        pointerHeight = Utils.dpToPx(8);
        edgeMargin = Utils.dpToPx(16);
        barGap = Utils.dpToPx(6);
        platePaint.setColor(ContextCompat.getColor(context, R.color.sheet_surface));
        setPadding(Utils.dpToPx(16), Utils.dpToPx(10), Utils.dpToPx(16), Utils.dpToPx(10) + pointerHeight);

        left = text(context);
        right = text(context);
        icon = new ImageView(context);
        icon.setImageTintList(ContextCompat.getColorStateList(context, R.color.ink_high));
        final int iconSize = Utils.dpToPx(20);
        final LayoutParams iconLp = new LayoutParams(iconSize, iconSize);
        iconLp.leftMargin = Utils.dpToPx(10);
        iconLp.rightMargin = Utils.dpToPx(10);
        line = new LinearLayout(context);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(left);
        line.addView(icon, iconLp);
        line.addView(right);

        still = new ImageView(context);
        still.setScaleType(ImageView.ScaleType.FIT_XY);
        still.setVisibility(GONE);
        final LayoutParams stillLp = new LayoutParams(0, 0);
        stillLp.bottomMargin = Utils.dpToPx(8);
        addView(still, stillLp);
        // Its own width, not the card's: a MATCH_PARENT child of a wrapping column is measured to the width of
        // the others, and the line is a little wider than the frame, so it came out cut at the end.
        addView(line, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static TextView text(final Context context) {
        final TextView view = new TextView(context);
        view.setTextColor(ContextCompat.getColor(context, R.color.ink_high));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.exo_error_message_text_size));
        view.setSingleLine(true);
        view.setIncludeFontPadding(false);
        return view;
    }

    /** Adds the card to the player's root, above the controller, so it can stand on the bar. */
    static SeekCard attach(final FrameLayout root) {
        final SeekCard card = new SeekCard(root.getContext());
        root.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        return card;
    }

    /**
     * @param deltaMs   signed distance from where the seek began
     * @param targetMs  where it lands
     * @param durationMs the media's length; the percentage needs it, and so does the place on the bar
     */
    void show(final CustomDefaultTimeBar bar, final long deltaMs, final long targetMs, final long durationMs) {
        final String time = Utils.formatMilis(targetMs);
        final String share = "(" + Math.round(targetMs * 100f / durationMs) + "%)";
        setLine(deltaMs, targetMs, back -> back ? share + " " + time : time + " " + share);
        anchored = true;
        bar.setSeekTarget(targetMs, durationMs);
        setShown(true);
        place(bar, targetMs, durationMs);
        // The bar may still be sliding in (the controller animates its progress-only state), so the place
        // measured now is where it is this frame. Once more after the next layout pass settles it.
        post(() -> {
            if (getVisibility() == VISIBLE && anchored) {
                place(bar, targetMs, durationMs);
            }
        });
    }

    /**
     * The same line where there is no length to put a point on the bar by: a stream whose duration is not
     * known (yet), or a broadcast. Same height over the bar, in the middle, and with no pointer, because
     * there is no point it could be pointing at.
     *
     * @param targetMs where it lands, or -1 for a broadcast — an offset into its sliding window is not a
     *                 time the viewer has anywhere to put, so only the delta is said
     */
    void showCentered(final CustomDefaultTimeBar bar, final long deltaMs, final long targetMs) {
        setLine(deltaMs, targetMs, back -> targetMs >= 0 ? Utils.formatMilis(targetMs) : null);
        anchored = false;
        setShown(true);
        place(bar, -1, 0);
        post(() -> {
            if (getVisibility() == VISIBLE && !anchored) {
                place(bar, -1, 0);
            }
        });
    }

    private interface TargetText {
        @Nullable
        CharSequence of(boolean back);
    }

    /** Forward reads delta, arrows, target; backward the mirror of it, so the arrows point at the target. */
    private void setLine(final long deltaMs, final long targetMs, final TargetText targetText) {
        if (getVisibility() == VISIBLE && shownTargetMs >= 0 && targetMs >= 0) {
            if (targetMs != shownTargetMs) {
                back = targetMs < shownTargetMs;
            }
        } else {
            back = deltaMs < 0;
        }
        shownTargetMs = targetMs;
        final CharSequence delta = rate == null ? Utils.formatMilisSign(deltaMs)
                : Utils.formatMilisSign(deltaMs) + " " + rate;
        final CharSequence target = targetText.of(back);
        final CharSequence first = back ? target : delta;
        final CharSequence second = back ? delta : target;
        left.setText(first);
        left.setVisibility(first != null ? VISIBLE : GONE);
        right.setText(second);
        right.setVisibility(second != null ? VISIBLE : GONE);
        icon.setImageResource(back ? R.drawable.ic_rewind_24dp : R.drawable.ic_fast_forward_24dp);
    }

    /** The speed a gliding mark moves at, shown after the offset; null for none (see PlayerActivity.glideKey). */
    @Nullable
    private CharSequence rate;

    void setRate(@Nullable final CharSequence rate) {
        this.rate = rate;
    }

    void hide() {
        setStill(null);
        setShown(false);
    }

    /**
     * The frame for where the seek lands, over the line; null takes it away. 320 px on a 1080-line screen —
     * a trickplay frame at its own size — and the same share of any other.
     */
    void setStill(@Nullable final Bitmap frame) {
        if (frame == null) {
            if (still.getVisibility() != GONE) {
                still.setImageDrawable(null);
                still.setVisibility(GONE);
                replace();
            }
            return;
        }
        final View root = (View) getParent();
        final int screen = root != null && root.getHeight() > 0 ? root.getHeight() : 1080;
        final int width = Math.round(screen * 320f / 1080f);
        final int height = Math.round(width * (float) frame.getHeight() / frame.getWidth());
        final LayoutParams lp = (LayoutParams) still.getLayoutParams();
        final boolean resized = lp.width != width || lp.height != height || still.getVisibility() != VISIBLE;
        lp.width = width;
        lp.height = height;
        still.setImageBitmap(frame);
        still.setVisibility(VISIBLE);
        if (resized) {
            still.setLayoutParams(lp);
            replace();
        }
    }

    private void replace() {
        if (placedBar != null && getVisibility() == VISIBLE) {
            place(placedBar, placedTargetMs, placedDurationMs);
        }
    }

    private final int[] barAt = new int[2];
    private final int[] rootAt = new int[2];
    @Nullable private Runnable onShownChanged;

    /** Told when the card comes and goes, for chrome that should step aside while a seek is on screen. */
    void setOnShownChanged(@Nullable final Runnable listener) {
        onShownChanged = listener;
    }

    private void setShown(final boolean shown) {
        final boolean was = getVisibility() == VISIBLE;
        setVisibility(shown ? VISIBLE : GONE);
        if (was != shown && onShownChanged != null) {
            onShownChanged.run();
        }
    }

    /** @param durationMs 0 to stand in the middle, with no point on the bar to stand over */
    private void place(final CustomDefaultTimeBar bar, final long targetMs, final long durationMs) {
        placedBar = bar;
        placedTargetMs = targetMs;
        placedDurationMs = durationMs;
        final View root = (View) getParent();
        final int barX = durationMs > 0 ? bar.xOf(targetMs, durationMs) : 0;
        if (root == null || barX < 0) {
            return;
        }
        bar.getLocationInWindow(barAt);
        root.getLocationInWindow(rootAt);
        final int pointAtX = durationMs > 0 ? barAt[0] - rootAt[0] + barX : root.getWidth() / 2;
        final int barTop = barAt[1] - rootAt[1] + bar.barTop();
        measure(MeasureSpec.makeMeasureSpec(root.getWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();
        // Centred on the point, and stopped by the screen's edge rather than cut by it: at the start or the
        // end of a film the card stays whole and only its pointer keeps going.
        final int x = Math.max(edgeMargin, Math.min(root.getWidth() - edgeMargin - width, pointAtX - width / 2));
        setTranslationX(x);
        setTranslationY(barTop - barGap - height);
        // Clear of the rounded corner, whatever the corner turns out to be at this height.
        final float minPointer = Math.min(radius, (height - pointerHeight) / 2f) + pointerHeight;
        pointerX = Math.max(minPointer, Math.min(width - minPointer, pointAtX - x));
        invalidate();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final float bottom = getHeight() - pointerHeight;
        plate.set(0, 0, getWidth(), bottom);
        final float r = Math.min(radius, bottom / 2f);
        canvas.drawRoundRect(plate, r, r, platePaint);
        if (!anchored) {
            // The pointer's band stays empty, so the plate stands as high as an anchored one does.
            super.onDraw(canvas);
            return;
        }
        pointer.reset();
        pointer.moveTo(pointerX - pointerHeight, bottom);
        pointer.lineTo(pointerX + pointerHeight, bottom);
        pointer.lineTo(pointerX, getHeight());
        pointer.close();
        canvas.drawPath(pointer, platePaint);
        super.onDraw(canvas);
    }
}
