package com.brouken.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.ViewCompat;
import androidx.media3.common.util.Util;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.util.Formatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The question a video opens with when it has a remembered place: what is waiting, how far in, and the two
 * answers — low on the leading side over a shade that rises from the bottom edge, where a streaming app
 * asks the same thing, and not as a dialog in the middle of the film.
 *
 * <p>It was a centred AlertDialog, and it lost to the player behind it (Shield, 23.10.2026): the controls
 * stayed up under the scrim, brighter than anything in the dialog, and the header's 26sp bold title
 * outweighed the dialog's own, so the eye went to a row of buttons that had nothing to do at that moment.
 * The controls are put away while this is up — the caller's part — and nothing here is larger than the
 * name of what is waiting.
 *
 * <p>A window of its own rather than a view in the player's: the D-pad and Back stay inside it, so a left
 * press moves between the answers instead of seeking a film that has not started.
 */
final class ResumePrompt {

    // S01E05 anywhere in the title, the shape every naming scheme the library uses writes (Kodi, Jellyfin,
    // tinyMediaManager). 1x05 is left alone here: in a title it is as often a resolution or a ratio.
    private static final Pattern EPISODE = Pattern.compile("(?i)(?<![a-z0-9])s(\\d{1,2})\\s*e(\\d{1,3})(?!\\d)");

    // Release tags at the end of a file name, "[720p]", "[WEB-DL]": the header keeps them, the name a
    // question is asked about does not need them. Square brackets only - "(2009)" is part of a show's name.
    private static final Pattern TAGS = Pattern.compile("(\\s*\\[[^\\]]*\\])+\\s*$");

    // What is trimmed from either side of the episode marker: "Show - S01E01 - Name", "Show.S01E01.Name".
    private static final String SEPARATORS = " \t-–—._";

    private ResumePrompt() {
    }

    /**
     * @param title      the name the header shows for this video
     * @param durationMs 0 when the length is not known; the line and the time left are then left out
     * @param onResume   carry on from {@code positionMs}; also what Back does, as the old dialog did:
     *                   it is what the player would have done without asking
     */
    static Dialog show(final Activity activity, final UiMetrics ui, final CharSequence title,
                       final long positionMs, final long durationMs,
                       final Runnable onResume, final Runnable onStartOver) {
        // Dark whatever the appearance choice: the ground is the film under a shade, never the app's.
        final Context ctx = Dialogs.dialogContext(activity, false);
        final int ink = MaterialColors.getColor(ctx, R.attr.colorOnSurface, Color.WHITE);
        final int quiet = MaterialColors.getColor(ctx, R.attr.colorOnSurfaceVariant, Color.LTGRAY);
        final StringBuilder timeBuilder = new StringBuilder();
        final Formatter timeFormatter = new Formatter(timeBuilder, Locale.getDefault());
        final String at = Util.getStringForTime(timeBuilder, timeFormatter, positionMs);

        final FrameLayout root = new FrameLayout(ctx);
        // A breath of shade over the whole picture and a deep one along the bottom: the picture stays
        // the picture, and the words under it stay readable over a white door as well as a dark room.
        root.setBackgroundColor(0x26000000);
        final View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x8C000000, 0xE0000000}));
        final int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        final int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(screenH * 0.6f), Gravity.BOTTOM));

        // The header's clock goes with the controls, and the time is still worth having while the
        // question waits: same size, same corner.
        final TextClock clock = new TextClock(ctx);
        clock.setTextColor(ink);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textClock());
        final FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clockLp.topMargin = Math.max(ui.dp(24), ui.overscanV() + ui.dp(12));
        clockLp.setMarginEnd(Math.max(ui.dp(24), ui.overscanH() + ui.dp(18)));
        root.addView(clock, clockLp);

        final int side = Math.max(ui.dp(24), ui.overscanH());
        final int blockW = Math.min(ui.dp(520), screenW - 2 * side);
        final LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);

        final CharSequence[] names = split(title);
        if (names[0] != null) {
            final TextView over = new TextView(ctx);
            over.setText(names[0]);
            over.setTextColor(quiet);
            over.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textPanelRow());
            over.setSingleLine(true);
            over.setEllipsize(TextUtils.TruncateAt.END);
            block.addView(over);
        }
        final TextView name = new TextView(ctx);
        name.setText(names[1]);
        name.setTextColor(ink);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textPromptTitle());
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        ViewCompat.setAccessibilityHeading(name, true);
        block.addView(name);

        if (durationMs > 0) {
            final Line line = new Line(ctx, ink, 0x59FFFFFF,
                    Math.min(1f, Math.max(0f, positionMs / (float) durationMs)), ui.dp(4));
            final LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4));
            lineLp.topMargin = ui.dp(28);
            block.addView(line, lineLp);

            final FrameLayout times = new FrameLayout(ctx);
            final TextView from = new TextView(ctx);
            from.setText(at);
            from.setTextColor(quiet);
            from.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textInfo());
            times.addView(from, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
            final TextView left = new TextView(ctx);
            left.setText(ctx.getString(R.string.resume_ask_left, Util.getStringForTime(timeBuilder,
                    timeFormatter, Math.max(0, durationMs - positionMs))));
            left.setTextColor(quiet);
            left.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textInfo());
            times.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
            final LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timesLp.topMargin = ui.dp(6);
            block.addView(times, timesLp);
        }

        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        final MaterialButton resume = answer(ctx, ui,
                ctx.getString(R.string.resume_ask_continue_from, at), true, () -> {
                    dialog.dismiss();
                    onResume.run();
                });
        resume.setIconResource(R.drawable.ic_play_arrow_24dp);
        resume.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        resume.setIconTint(resume.getTextColors());
        final MaterialButton over = answer(ctx, ui, ctx.getString(R.string.resume_ask_start_over),
                false, () -> {
                    dialog.dismiss();
                    onStartOver.run();
                });
        final LinearLayout answers = new LinearLayout(ctx);
        answers.setOrientation(LinearLayout.HORIZONTAL);
        answers.addView(resume);
        final LinearLayout.LayoutParams overLp = (LinearLayout.LayoutParams) over.getLayoutParams();
        overLp.setMarginStart(ui.dp(16));
        answers.addView(over, overLp);
        final LinearLayout.LayoutParams answersLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Room for the focus ring around the first answer, which is drawn outside the pill.
        answersLp.topMargin = ui.dp(32);
        answersLp.setMarginStart(ui.dp(4));
        block.addView(answers, answersLp);

        final FrameLayout.LayoutParams blockLp = new FrameLayout.LayoutParams(blockW,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        blockLp.setMarginStart(side);
        final int bottom = Math.max(ui.dp(40), ui.overscanV() + ui.dp(24));
        blockLp.bottomMargin = bottom;
        root.addView(block, blockLp);
        // A phone's navigation bar is under the window now (edge to edge, below); the block stands clear
        // of it, the shade does not.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            final androidx.core.graphics.Insets bars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            blockLp.bottomMargin = bottom + bars.bottom;
            block.setLayoutParams(blockLp);
            return insets;
        });

        dialog.setContentView(root);
        dialog.setTitle(ctx.getString(R.string.resume_ask_title));
        // Back carries on, as it did in the dialog this replaced.
        dialog.setOnCancelListener(d -> onResume.run());
        final Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // The shade is ours; the platform's dim would darken the film a second time.
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            WindowCompat.setDecorFitsSystemWindows(window, false);
            if (Build.VERSION.SDK_INT >= 28) {
                window.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        // One press of OK carries on. Default focus as well as a request, for the reason askResume gave
        // the old dialog: in touch mode the request alone does nothing, and the key that leaves touch
        // mode lands on the default — the first answer in the row, which is this one here anyway.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            resume.setFocusedByDefault(true);
        }
        dialog.setOnShowListener(d -> resume.requestFocus());
        dialog.show();
        return dialog;
    }

    /** An answer pill at the panels' size, wrapping its label rather than sharing the row. */
    private static MaterialButton answer(final Context ctx, final UiMetrics ui, final CharSequence label,
                                         final boolean filled, final Runnable onClick) {
        final MaterialButton button = Dialogs.answer(ctx, ui, label, filled, onClick);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textPanelRow());
        button.setMinHeight(ui.dp(48));
        button.setCornerRadius(ui.dp(24));
        button.setPadding(ui.dp(24), 0, ui.dp(24), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    /**
     * {overline, title}: "Show · S01E01" over the episode's own name when the title carries an episode
     * marker, else no overline and the title whole. A name with nothing after the marker keeps the
     * show as the title and the marker alone above it.
     */
    static CharSequence[] split(final CharSequence title) {
        final String full = title == null ? "" : title.toString();
        final String bare = TAGS.matcher(full).replaceFirst("");
        final String text = bare.isEmpty() ? full : bare;
        final Matcher m = EPISODE.matcher(text);
        if (!m.find()) {
            return new CharSequence[] {null, text};
        }
        final String marker;
        try {
            marker = String.format(Locale.ROOT, "S%02dE%02d",
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (NumberFormatException e) {
            return new CharSequence[] {null, text};
        }
        final String show = trim(text.substring(0, m.start()));
        final String episode = trim(text.substring(m.end()));
        if (episode.isEmpty()) {
            return show.isEmpty() ? new CharSequence[] {null, marker}
                    : new CharSequence[] {marker, show};
        }
        return new CharSequence[] {show.isEmpty() ? marker : show + " · " + marker, episode};
    }

    private static String trim(final String s) {
        int from = 0;
        int to = s.length();
        while (from < to && SEPARATORS.indexOf(s.charAt(from)) >= 0) {
            from++;
        }
        while (to > from && SEPARATORS.indexOf(s.charAt(to - 1)) >= 0) {
            to--;
        }
        return s.substring(from, to);
    }

    /** How far in: a played share over the rest of the track, both rounded, no thumb — it is not a control. */
    private static final class Line extends View {
        private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float share;
        private final float radius;
        private final RectF box = new RectF();

        Line(final Context ctx, final int playedColor, final int restColor, final float share,
             final int thickness) {
            super(ctx);
            played.setColor(playedColor);
            rest.setColor(restColor);
            this.share = share;
            this.radius = thickness / 2f;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            box.set(0, 0, w, h);
            canvas.drawRoundRect(box, radius, radius, rest);
            if (share > 0) {
                final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                box.set(rtl ? w - w * share : 0, 0, rtl ? w : w * share, h);
                canvas.drawRoundRect(box, radius, radius, played);
            }
        }
    }
}
