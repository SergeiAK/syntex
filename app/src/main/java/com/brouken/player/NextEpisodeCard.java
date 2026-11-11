package com.brouken.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

/**
 * The card that asks before the next episode: low on the trailing side, over the credits that keep
 * playing, with Watch counting down and Cancel. A window of its own, as {@link ResumePrompt} is, so the
 * D-pad moves between the two answers and Back cancels instead of seeking the film behind it.
 */
final class NextEpisodeCard {

    interface Listener {
        /** Watch pressed, or the countdown ran out. */
        void onWatch();

        /** Cancel or Back. */
        void onCancel();
    }

    private final Dialog dialog;
    private final MaterialButton watch;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context ctx;
    private int remaining;
    private boolean done;
    private final Listener listener;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (done) {
                return;
            }
            remaining--;
            if (remaining <= 0) {
                finish(true);
                return;
            }
            watch.setText(ctx.getString(R.string.next_episode_watch_in, remaining));
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * @param name         what comes next: "Season 1 · Episode 3 · The name", or its title
     * @param countdownSec seconds before it moves on by itself; 0 waits for a press
     */
    static NextEpisodeCard show(final Activity activity, final UiMetrics ui, @Nullable final CharSequence name,
                                final int countdownSec, final Listener listener) {
        return new NextEpisodeCard(activity, ui, name, countdownSec, listener);
    }

    private NextEpisodeCard(final Activity activity, final UiMetrics ui, @Nullable final CharSequence name,
                            final int countdownSec, final Listener listener) {
        this.listener = listener;
        // Dark whatever the appearance choice: the ground is the film, never the app's.
        ctx = Dialogs.dialogContext(activity, false);
        final int ink = MaterialColors.getColor(ctx, R.attr.colorOnSurface, Color.WHITE);
        final int quiet = MaterialColors.getColor(ctx, R.attr.colorOnSurfaceVariant, Color.LTGRAY);

        final FrameLayout root = new FrameLayout(ctx);
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable plate = new GradientDrawable();
        plate.setColor(0xE0101010);
        plate.setCornerRadius(ui.dp(16));
        card.setBackground(plate);
        card.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20));

        final TextView over = new TextView(ctx);
        over.setText(R.string.next_episode_title);
        over.setTextColor(quiet);
        over.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textPanelRow());
        card.addView(over);

        if (name != null && name.length() > 0) {
            final TextView title = new TextView(ctx);
            title.setText(name);
            title.setTextColor(ink);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textPanelRow() + 4);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            final LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleLp.topMargin = ui.dp(4);
            card.addView(title, titleLp);
        }

        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        remaining = countdownSec;
        watch = answer(ctx, ui, countdownSec > 0
                ? ctx.getString(R.string.next_episode_watch_in, countdownSec)
                : ctx.getString(R.string.next_episode_watch), true, () -> finish(true));
        watch.setIconResource(R.drawable.ic_play_arrow_24dp);
        watch.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        watch.setIconTint(watch.getTextColors());
        final MaterialButton cancel = answer(ctx, ui, ctx.getString(R.string.next_episode_cancel), false,
                () -> finish(false));
        final LinearLayout answers = new LinearLayout(ctx);
        answers.setOrientation(LinearLayout.HORIZONTAL);
        answers.addView(watch);
        final LinearLayout.LayoutParams cancelLp = (LinearLayout.LayoutParams) cancel.getLayoutParams();
        cancelLp.setMarginStart(ui.dp(12));
        answers.addView(cancel, cancelLp);
        final LinearLayout.LayoutParams answersLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        answersLp.topMargin = ui.dp(20);
        card.addView(answers, answersLp);

        final FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        cardLp.setMarginEnd(Math.max(ui.dp(24), ui.overscanH()));
        cardLp.bottomMargin = Math.max(ui.dp(40), ui.overscanV() + ui.dp(24));
        root.addView(card, cardLp);

        dialog.setContentView(root);
        dialog.setTitle(ctx.getString(R.string.next_episode_title));
        dialog.setOnCancelListener(d -> finish(false));
        final Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Nothing over the film but the card: the credits are still playing.
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
        // One press of OK watches; see ResumePrompt for why both are set.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            watch.setFocusedByDefault(true);
        }
        dialog.setOnShowListener(d -> watch.requestFocus());
        dialog.show();
        if (countdownSec > 0) {
            handler.postDelayed(tick, 1000);
        }
    }

    boolean isShowing() {
        return !done && dialog.isShowing();
    }

    /** Takes the card away without an answer: the episode changed under it, or the player is closing. */
    void dismiss() {
        done = true;
        handler.removeCallbacks(tick);
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void finish(final boolean watchNext) {
        if (done) {
            return;
        }
        dismiss();
        if (watchNext) {
            listener.onWatch();
        } else {
            listener.onCancel();
        }
    }

    private static MaterialButton answer(final Context ctx, final UiMetrics ui, final CharSequence label,
                                         final boolean filled, final Runnable onClick) {
        final MaterialButton button = Dialogs.answer(ctx, ui, label, filled, onClick);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textPanelRow());
        button.setMinHeight(ui.dp(48));
        button.setCornerRadius(ui.dp(24));
        button.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }
}
