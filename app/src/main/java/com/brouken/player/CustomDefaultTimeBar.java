package com.brouken.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.animation.PathInterpolator;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.TimeBar;

import java.lang.reflect.Field;

class CustomDefaultTimeBar extends DefaultTimeBar {

    Rect scrubberBar;
    private Rect progressBar;
    // The bar's own height, and the field Media3 keeps it in: on a remote the bar has to say it holds the
    // focus, and it has nothing else to say it with — no plate to draw a contour on, and a full-width line
    // in white would be the loudest thing on the screen. So the rail itself doubles, the way a television
    // player's does, and the scrubber is already at its dragged size while focused.
    private Field barHeightField;
    private int barHeightRest;
    private ValueAnimator barHeightAnimator;
    private static final long BAR_HEIGHT_DURATION_MS = 150;
    /**
     * The bar's own frame, drawn under Media3's rails: a dark rim around the whole bar and a light halo
     * around the rim. A light played run on a light frame used to have no edge at all — on a Shield with
     * the Monochrome accent, #E0E0E0 over a sunlit sky was a bar with no progress on it. The rim gives
     * every fill a dark edge, the halo gives the rim a light one on a dark frame, so one of the two always
     * reads. The same answer the poster bars in Lampa and Wholphin+ gave to the same problem.
     */
    private static final int RIM = 0xFF0A0A0C;
    private static final int HALO_REST = 0x59FFFFFF;
    /**
     * While the bar holds the focus the halo goes fully white: a contour around the bar, the mark a
     * television viewer reads as "here", where the lit grey track it replaces fought the played run.
     */
    private static final int HALO_FOCUSED = 0xFFFFFFFF;
    private final Paint framePaint = new Paint();
    /**
     * TV: a rounded rail on the bottom block's plate, with no contour. The plate gives the rail its ground,
     * so the white frame around the whole bar - the loudest line on the screen - goes, and the focus is the
     * ring round the dot plus the height the rail already grows by. The dark rim stays, as a hairline on the
     * rounded shape: with the controls down a remote seek shows the bar on its own, over the picture.
     */
    private boolean roundLook;
    private static final int FOCUS_RING = 0x47FFFFFF;
    private final Paint roundRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path barClip = new Path();
    private final RectF barRect = new RectF();
    private boolean seekLook;
    private long seekTargetMs = -1;
    private long seekTargetDurationMs;
    private final Paint seekTargetRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean scrubbing;
    private int scrubbingStartX;
    private boolean scrubbingNow;

    // The dot's own colour, kept because the band is painted over it and it has to be put back (see
    // onDraw). Media3 keeps its scrubber paint private and the app sets the colour itself anyway.
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Field scrubberScaleField;
    private Field durationField;
    private final Paint skipPaint = new Paint();
    private long[] skipStartsMs;
    private long[] skipEndsMs;
    private int[] skipColors;
    private int[] skipFillColors;
    private long skipDurationMs;
    /**
     * Where the file's chapters start, past the first (which is the bar's own left end). Shown as ticks
     * standing a little proud of the bar, or — the viewer's choice (Prefs.chapterMarks) — as cuts that
     * break the bar into one short bar per chapter, as Plezy draws it. Ticks are the default: a cut goes
     * through a skip band wherever a chapter boundary falls inside one.
     */
    private long[] chapterStartsMs;
    private long chapterDurationMs;
    private boolean chapterCuts;
    private final Paint chapterPaint = new Paint();

    public CustomDefaultTimeBar(Context context) {
        this(context, null);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, attrs);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr, @Nullable AttributeSet timebarAttrs) {
        this(context, attrs, defStyleAttr, timebarAttrs, 0);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr, @Nullable AttributeSet timebarAttrs, int defStyleRes) {
        super(context, attrs, defStyleAttr, timebarAttrs, defStyleRes);
        try {
            Field field = DefaultTimeBar.class.getDeclaredField("scrubberBar");
            field.setAccessible(true);
            scrubberBar = (Rect) field.get(this);
            Field progressField = DefaultTimeBar.class.getDeclaredField("progressBar");
            progressField.setAccessible(true);
            progressBar = (Rect) progressField.get(this);
            barHeightField = DefaultTimeBar.class.getDeclaredField("barHeight");
            barHeightField.setAccessible(true);
            barHeightRest = barHeightField.getInt(this);
            scrubberScaleField = DefaultTimeBar.class.getDeclaredField("scrubberScale");
            scrubberScaleField.setAccessible(true);
            durationField = DefaultTimeBar.class.getDeclaredField("duration");
            durationField.setAccessible(true);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            barHeightField = null;
            e.printStackTrace();
        }
        // The scrubber grows while the bar is being dragged (DefaultTimeBar.drawPlayhead), and the base
        // class keeps that flag private — this listener spans exactly the same window.
        addListener(new OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                scrubbingNow = true;
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                scrubbingNow = false;
            }
        });
    }

    /**
     * Highlight skip/ad segment ranges on the progress bar so the user sees them in advance.
     * Arrays are parallel: {@code edgeColors} paints the crisp boundary hairlines, {@code fillColors}
     * the soft band across the whole segment. Both carry per-segment (translucent) ARGB.
     */
    void setSkipHighlights(long[] startsMs, long[] endsMs, int[] edgeColors, int[] fillColors, long durationMs) {
        this.skipStartsMs = startsMs;
        this.skipEndsMs = endsMs;
        this.skipColors = edgeColors;
        this.skipFillColors = fillColors;
        this.skipDurationMs = durationMs;
        invalidate();
    }

    /** See {@link #chapterStartsMs}; {@code null} or an empty array clears them. */
    void setChapterMarks(@Nullable long[] startsMs, long durationMs, boolean cuts) {
        if (java.util.Arrays.equals(chapterStartsMs, startsMs) && chapterDurationMs == durationMs
                && chapterCuts == cuts) {
            return;
        }
        chapterStartsMs = startsMs != null && startsMs.length > 0 ? startsMs : null;
        chapterDurationMs = durationMs;
        chapterCuts = cuts;
        invalidate();
    }

    /** The x of each chapter boundary strictly inside the bar; empty when there is nothing to mark. */
    private int[] chapterXs() {
        if (chapterStartsMs == null || chapterDurationMs <= 0 || progressBar == null || progressBar.width() <= 0) {
            return new int[0];
        }
        final int[] xs = new int[chapterStartsMs.length];
        int n = 0;
        for (long start : chapterStartsMs) {
            if (start <= 0 || start >= chapterDurationMs) {
                continue;
            }
            xs[n++] = xOf(start, chapterDurationMs);
        }
        return java.util.Arrays.copyOf(xs, n);
    }

    /** ~3dp: wide enough to read as a break at three metres, narrow enough not to eat a short chapter. */
    private static int cutWidth() {
        return Math.max(4, Utils.dpToPx(3));
    }

    /**
     * Takes the cuts out of what is drawn next. {@code inset} widens each one: the frame is cut at the
     * plain width and the rails inside it wider, so every piece keeps a dark end of its own.
     */
    private void clipCuts(final Canvas canvas, final int[] xs, final int inset) {
        final int half = cutWidth() / 2 + inset;
        for (int x : xs) {
            canvas.clipRect(x - half, 0, x + half, getHeight(), android.graphics.Region.Op.DIFFERENCE);
        }
    }

    /** A light tick in a dark rim at each boundary, a few dp proud of the bar on both sides. */
    private void drawChapterTicks(final Canvas canvas, final int[] xs) {
        final int rim = rimWidth();
        final int half = Math.max(1, rimWidth() / 2 + 1);
        final int proud = Utils.dpToPx(3);
        final int top = progressBar.top - rim - proud;
        final int bottom = progressBar.bottom + rim + proud;
        for (int x : xs) {
            chapterPaint.setColor(RIM);
            canvas.drawRect(x - half - rim, top - rim, x + half + rim, bottom + rim, chapterPaint);
            chapterPaint.setColor(0xF0F0F0F0);
            canvas.drawRect(x - half, top, x + half, bottom, chapterPaint);
        }
    }

    @Override
    public void setScrubberColor(final int scrubberColor) {
        super.setScrubberColor(scrubberColor);
        playheadPaint.setColor(scrubberColor);
    }

    void clearSkipHighlights() {
        this.skipStartsMs = null;
        this.skipEndsMs = null;
        this.skipColors = null;
        this.skipFillColors = null;
        this.skipDurationMs = 0;
        invalidate();
    }

    /**
     * Where a remote seek is going, as a dot on the bar. Media3 hides its own scrubber while only the
     * progress is up, and in that window the bar itself stands at the playing position, not at the target —
     * so without this the card's pointer would be the only thing saying where the seek lands. -1 clears it.
     */
    void setSeekTarget(final long targetMs, final long durationMs) {
        if (seekTargetMs == targetMs && seekTargetDurationMs == durationMs) {
            return;
        }
        seekTargetMs = targetMs;
        seekTargetDurationMs = durationMs;
        invalidate();
    }

    /** The x of a position on the bar, in this view's coordinates; -1 before the first layout. */
    int xOf(final long positionMs, final long durationMs) {
        if (progressBar == null || progressBar.width() <= 0 || durationMs <= 0) {
            return -1;
        }
        return progressBar.left + Math.round(progressBar.width() * clamp((float) positionMs / durationMs));
    }

    /** The top of the drawn bar, in this view's coordinates. */
    int barTop() {
        return progressBar != null ? progressBar.top : 0;
    }

    private void drawSeekTarget(final Canvas canvas) {
        if (!seekLook || seekTargetMs < 0) {
            return;
        }
        final int x = xOf(seekTargetMs, seekTargetDurationMs);
        if (x < 0) {
            return;
        }
        final float radius = getResources().getDimensionPixelSize(R.dimen.exo_styled_progress_enabled_thumb_size) / 2f;
        final float cy = progressBar.exactCenterY();
        // A hairline of black around it, so a light dot still reads where it lands on the light played part.
        seekTargetRing.setColor(RIM);
        canvas.drawCircle(x, cy, radius + rimWidth(), seekTargetRing);
        canvas.drawCircle(x, cy, radius, playheadPaint);
    }

    /** ~1.5dp: the hairline width the segment edges already use, so the bar has one line weight. */
    private static int rimWidth() {
        return Math.max(2, Utils.dpToPx(3) / 2);
    }

    /** See {@link #roundLook}. */
    void setRoundLook(final boolean roundLook) {
        this.roundLook = roundLook;
        roundRimPaint.setColor(RIM);
        focusRingPaint.setColor(FOCUS_RING);
        invalidate();
    }

    /** Halo, then rim, under the rails Media3 is about to paint into {@code progressBar}. */
    private void drawFrame(final Canvas canvas) {
        if (progressBar == null || progressBar.width() <= 0) {
            return;
        }
        final int rim = rimWidth();
        if (roundLook) {
            barRect.set(progressBar.left - rim, progressBar.top - rim, progressBar.right + rim, progressBar.bottom + rim);
            final float r = barRect.height() / 2f;
            canvas.drawRoundRect(barRect, r, r, roundRimPaint);
            return;
        }
        final int halo = Math.max(1, Utils.dpToPx(1));
        framePaint.setColor(isFocused() && !seekLook ? HALO_FOCUSED : HALO_REST);
        canvas.drawRect(progressBar.left - rim - halo, progressBar.top - rim - halo,
                progressBar.right + rim + halo, progressBar.bottom + rim + halo, framePaint);
        framePaint.setColor(RIM);
        canvas.drawRect(progressBar.left - rim, progressBar.top - rim,
                progressBar.right + rim, progressBar.bottom + rim, framePaint);
    }

    /**
     * The dot, painted again over Media3's own with a dark ring, so a light dot on the light played run
     * keeps its edge. Inside a segment it takes the segment's colour, opaque: the viewer sees at the dot
     * itself that they are in the credits, not only by a band the dot sits on. Same size and visibility
     * rules as DefaultTimeBar.drawPlayhead, including the scale it hides the dot with.
     */
    private void drawPlayhead(final Canvas canvas, final int segmentColor) {
        if (scrubberBar == null || progressBar == null || scrubberScaleField == null) {
            return;
        }
        final float scale;
        try {
            if (durationField.getLong(this) <= 0) {
                return;
            }
            scale = scrubberScaleField.getFloat(this);
        } catch (IllegalAccessException e) {
            scrubberScaleField = null;
            return;
        }
        final int radius = (int) (playheadRadius() * scale);
        if (radius <= 0) {
            return;
        }
        final int x = Math.min(Math.max(scrubberBar.right, scrubberBar.left), progressBar.right);
        final int y = scrubberBar.centerY();
        if (roundLook && isFocused() && !seekLook) {
            canvas.drawCircle(x, y, radius + Utils.dpToPx(7), focusRingPaint);
        }
        playheadRing.setColor(RIM);
        canvas.drawCircle(x, y, radius + rimWidth(), playheadRing);
        if (segmentColor != 0) {
            final int dotColor = playheadPaint.getColor();
            playheadPaint.setColor(segmentColor | 0xFF000000);
            canvas.drawCircle(x, y, radius, playheadPaint);
            playheadPaint.setColor(dotColor);
        } else {
            canvas.drawCircle(x, y, radius, playheadPaint);
        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        final int[] chapterXs = chapterXs();
        final boolean cuts = chapterCuts && chapterXs.length > 0;
        if (cuts) {
            canvas.save();
            clipCuts(canvas, chapterXs, 0);
        }
        drawFrame(canvas);
        if (cuts) {
            clipCuts(canvas, chapterXs, rimWidth());
        }
        // The rails and the bands are rectangles in Media3; the round look rounds them by a clip. The dot
        // Media3 draws in there is clipped with them and is drawn whole again below, as it always was.
        final boolean clip = roundLook && progressBar != null && progressBar.width() > 0;
        if (clip) {
            canvas.save();
            barRect.set(progressBar);
            final float r = barRect.height() / 2f;
            barClip.reset();
            barClip.addRoundRect(barRect, r, r, Path.Direction.CW);
            canvas.clipPath(barClip);
        }
        super.onDraw(canvas);
        final boolean segments = skipStartsMs != null && skipDurationMs > 0 && progressBar != null;
        if (segments && progressBar.width() <= 0) {
            if (clip) {
                canvas.restore();
            }
            if (cuts) {
                canvas.restore();
            }
            return;
        }
        final int segmentColor = segments ? drawSegments(canvas) : 0;
        if (clip) {
            canvas.restore();
        }
        if (cuts) {
            canvas.restore();
        } else if (chapterXs.length > 0) {
            // Over the rails and the bands, under the dot: the dot stays the frontmost mark.
            drawChapterTicks(canvas, chapterXs);
        }
        drawPlayhead(canvas, segmentColor);
        drawSeekTarget(canvas);
    }

    /** The skip segments' bands; returns the fill of the one the dot is in, 0 when it is in none. */
    private int drawSegments(final Canvas canvas) {
        final int barLeft = progressBar.left;
        final int barWidth = progressBar.width();
        // Media3 paints the playhead inside super.onDraw above, so everything below lands on top of it and
        // the fill is opaque. The patch used to be kept clear for that reason, which hid the segment the
        // viewer is actually inside: the dot is 12dp wide, and on a 50-minute film 1dp is 8.5s, so every
        // segment shorter than about a hundred seconds disappeared under it at exactly the moment the
        // player was offering to skip it - measured on the owner's phone. So the band goes through and the
        // dot is painted back on top of it: it stays the frontmost mark, and the segment stays visible.
        final int playheadX = scrubberBar != null
                ? Math.min(Math.max(scrubberBar.right, scrubberBar.left), progressBar.right) : -1;
        // Each segment gets a soft fill band across its whole width, plus ~1.5dp crisp hairlines on the
        // boundaries (chapter-divider style). The band demarcates the region while the edges frame it,
        // both staying lighter in weight than the coral scrubber.
        final int hairWidth = Math.max(2, Utils.dpToPx(3) / 2);
        int segmentColor = 0;
        for (int i = 0; i < skipStartsMs.length; i++) {
            float startFraction = clamp((float) skipStartsMs[i] / skipDurationMs);
            float endFraction = clamp((float) skipEndsMs[i] / skipDurationMs);
            int left = barLeft + Math.round(barWidth * startFraction);
            int right = barLeft + Math.round(barWidth * endFraction);
            if (right < left) {
                right = left;
            }
            if (skipFillColors != null && right > left) {
                skipPaint.setColor(skipFillColors[i]);
                drawBand(canvas, left, right);
            }
            skipPaint.setColor(skipColors[i]);
            drawBand(canvas, left, left + hairWidth);
            // Second hairline at the segment end, only when there's room for it to read as a separate edge.
            if (right - left > hairWidth * 2) {
                drawBand(canvas, right - hairWidth, right);
            }
            if (skipFillColors != null && right > left && playheadX >= left && playheadX <= right) {
                segmentColor = skipFillColors[i];
            }
        }
        return segmentColor;
    }

    /** Paints a bar-height band. */
    private void drawBand(Canvas canvas, int left, int right) {
        if (right <= left) {
            return;
        }
        canvas.drawRect(left, progressBar.top, right, progressBar.bottom, skipPaint);
    }

    @Override
    protected void onFocusChanged(final boolean gainFocus, final int direction,
                                  @Nullable final Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        // The halo goes white (see drawFrame). Height alone was the whole mark and it was not enough:
        // measured on tv_720p, focus moved the rail from 3px to 6px of a 720px screen, which is nothing at
        // three metres. A contour is a colour change a viewer sees from the sofa, and unlike the lit grey
        // track it once was, it does not touch the contrast between played and unplayed.
        invalidate();
        if (barHeightField == null) {
            return;
        }
        if (barHeightAnimator != null) {
            barHeightAnimator.cancel();
        }
        // The one place in the player that animates a focus change, and for the reason the rest do not: the
        // mark here *is* a size, and a full-width line snapping from 2dp to 4dp reads as a flicker rather
        // than a state. Material's short duration with its standard easing, so the bar grows in the time a
        // remote step takes anyway.
        final int from = currentBarHeight();
        final int to = gainFocus ? barHeightRest * 3 : barHeightRest;
        if (from == to) {
            return;
        }
        barHeightAnimator = ValueAnimator.ofInt(from, to);
        barHeightAnimator.setDuration(BAR_HEIGHT_DURATION_MS);
        barHeightAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        barHeightAnimator.addUpdateListener(animation -> setBarHeight((int) animation.getAnimatedValue()));
        barHeightAnimator.start();
    }

    /**
     * The bar is up only to show where a remote seek is going, with the controls hidden. The focus it holds
     * then is left over from the controls, not something the viewer moved, so the focus contour would
     * announce a state nobody asked about. The height still follows the focus; the halo stays at rest.
     */
    void setSeekLook(final boolean seekLook) {
        if (this.seekLook == seekLook) {
            return;
        }
        this.seekLook = seekLook;
        if (!seekLook) {
            seekTargetMs = -1;
        }
        invalidate();
    }

    private int currentBarHeight() {
        try {
            return barHeightField.getInt(this);
        } catch (IllegalAccessException e) {
            barHeightField = null;
            return barHeightRest;
        }
    }

    /** Media3 lays the three bars out from this height, so the rects follow on the next pass. */
    private void setBarHeight(final int height) {
        if (barHeightField == null) {
            return;
        }
        try {
            barHeightField.setInt(this, height);
            requestLayout();
        } catch (IllegalAccessException e) {
            barHeightField = null;
        }
    }

    /** The radius Media3 is currently drawing the scrubber with — same rule as DefaultTimeBar.drawPlayhead. */
    private int playheadRadius() {
        if (scrubbingNow || isFocused()) {
            return getResources().getDimensionPixelSize(R.dimen.exo_styled_progress_dragged_thumb_size) / 2;
        }
        // Disabled (an unseekable stream): the base class draws no dot, so nothing has to be kept clear.
        return isEnabled()
                ? getResources().getDimensionPixelSize(R.dimen.exo_styled_progress_enabled_thumb_size) / 2
                : 0;
    }

    private static float clamp(float value) {
        return value < 0 ? 0 : (value > 1 ? 1 : value);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && scrubberBar != null) {
            scrubbing = false;
            scrubbingStartX = (int)event.getX();
            final int distanceFromScrubber = Math.abs(scrubberBar.right - scrubbingStartX);
            if (distanceFromScrubber > Utils.dpToPx(24))
                return true;
            else
                scrubbing = true;
        }
        // The DOWN was swallowed above, so the base class is not scrubbing. Start it now, either
        // because the finger has moved far enough to be a deliberate drag, or because the finger
        // was lifted without moving at all — a tap, which seeks to the touched point.
        if (!scrubbing && scrubberBar != null
                && (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_UP)) {
            final int distanceFromStart = Math.abs(((int)event.getX()) - scrubbingStartX);
            if (event.getAction() == MotionEvent.ACTION_MOVE && distanceFromStart <= Utils.dpToPx(6)) {
                return true;
            }
            scrubbing = true;
            startScrubbingAt(event);
        }
        return super.onTouchEvent(event);
    }

    /**
     * Hands the base class the ACTION_DOWN it never received, so it positions the scrubber itself.
     * The press is clamped onto the bar because the finger may already have left it — dragging off
     * the bar is how fine scrubbing is started — and a press outside the bar would be ignored.
     */
    private void startScrubbingAt(MotionEvent event) {
        final MotionEvent down = MotionEvent.obtainNoHistory(event);
        down.setAction(MotionEvent.ACTION_DOWN);
        if (progressBar != null) {
            final float x = Math.min(Math.max(event.getX(), progressBar.left), progressBar.right - 1);
            down.setLocation(x, progressBar.centerY());
        }
        super.onTouchEvent(down);
        down.recycle();
    }
}
