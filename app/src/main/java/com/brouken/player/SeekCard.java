package com.brouken.player;

import android.content.Context;
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
 * forward, mirrored backward — "31:23 (21%) ◀◀ −06:06" — so the arrows point at the target either way.
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

    SeekCard(final Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
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
        addView(left);
        addView(icon, iconLp);
        addView(right);
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
        final StringBuilder target = new StringBuilder(Utils.formatMilis(targetMs));
        target.append(" (").append(Math.round(targetMs * 100f / durationMs)).append("%)");
        setLine(deltaMs, target);
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
        setLine(deltaMs, targetMs >= 0 ? Utils.formatMilis(targetMs) : null);
        anchored = false;
        setShown(true);
        place(bar, -1, 0);
        post(() -> {
            if (getVisibility() == VISIBLE && !anchored) {
                place(bar, -1, 0);
            }
        });
    }

    /** Forward reads delta, arrows, target; backward the mirror of it, so the arrows point at the target. */
    private void setLine(final long deltaMs, final CharSequence target) {
        final String delta = Utils.formatMilisSign(deltaMs);
        final boolean back = deltaMs < 0;
        final CharSequence first = back ? target : delta;
        final CharSequence second = back ? delta : target;
        left.setText(first);
        left.setVisibility(first != null ? VISIBLE : GONE);
        right.setText(second);
        right.setVisibility(second != null ? VISIBLE : GONE);
        icon.setImageResource(back ? R.drawable.ic_rewind_24dp : R.drawable.ic_fast_forward_24dp);
    }

    void hide() {
        setShown(false);
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
