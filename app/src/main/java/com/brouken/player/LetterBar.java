package com.brouken.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * The column of first letters beside a long folder, each a jump to the first row that starts with it
 * - the same bar as ours in Wholphin (PR #1975), and for the same reason: a folder of five hundred
 * films is walked one row at a time on a remote, and the letter is what the viewer is looking for.
 *
 * <p>Only the letters the folder actually has. A full alphabet is 27 entries in English and 60 in
 * Russian, which does not fit the height of a television, and two thirds of it would lead nowhere.
 *
 * <p>The letters come from the rows as they stand, and their order is the rows' own order
 * ({@link Utils#compareNatural} lowercases and compares by code), so the bar cannot disagree with the
 * list it jumps in: "Ё" is after "Я" here because that is where the rows put it. Wholphin learnt this
 * the hard way - a bar ordered by the language's alphabet and a list ordered by the server's collation
 * came apart at "Ё".
 */
public final class LetterBar extends ScrollView {

    /**
     * What a letter does to the list: shows its first row, or puts the remote on it. A row of -1
     * puts the remote back on the row it came from - the bar was entered and left without a step.
     */
    interface Listener {
        void onLetter(int row, boolean land);
    }

    /** The letter every name that does not start with a letter files under. */
    static final char OTHER = '#';

    private final LinearLayout column;
    private final List<Character> letters = new ArrayList<>();
    private final List<Integer> targets = new ArrayList<>();
    private Listener listener;
    private boolean tv;
    /** The letter of the row the list is standing on, or -1. */
    private int current = -1;
    /**
     * Whether the focus is moving along the column rather than arriving in it. Arriving lands on
     * the lit letter, the one the list is already standing in, and must not throw the list back to
     * that letter's first row; only a step to another letter shows another place.
     */
    private boolean walking;
    /** Whether this visit to the bar has moved the list at all. */
    private boolean shifted;

    public LetterBar(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipToPadding(false);
        setFocusable(false);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /**
     * The gaps above the first letter and below the last, matching the list's. On the column and
     * not on this view, so the column has the room to scroll into: a ScrollView's own padding is
     * not part of what it scrolls. Measured on Shield - padded here, the last letter still ended in
     * the band a television may cut.
     */
    void setEdges(final int top, final int bottom) {
        column.setPadding(0, top, 0, bottom);
    }

    /**
     * And the same gaps kept when the focus walks the column: left to itself a ScrollView brings the
     * focused letter flush with its edge, padding or no padding.
     */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(final Rect rect) {
        final Rect room = new Rect(rect);
        room.top -= column.getPaddingTop();
        room.bottom += column.getPaddingBottom();
        return super.computeScrollDeltaToGetChildRectOnScreen(room);
    }

    void setListener(final Listener listener, final boolean tv) {
        this.listener = listener;
        this.tv = tv;
    }

    /** What a name files under: its first letter in capitals, or {@link #OTHER}. */
    static char initialOf(final String name) {
        if (name == null || name.isEmpty()) {
            return OTHER;
        }
        final char first = name.charAt(0);
        return Character.isLetter(first) ? Character.toUpperCase(first) : OTHER;
    }

    /**
     * The letters of {@code names} in the order the rows have them, each with the first row under
     * it. {@link #OTHER} goes where digits and signs sort - first, or last in a descending folder.
     *
     * <p>The rows hold folders and then files, each sorted on its own, so one letter can start two
     * runs: the jump is to the first, which is the folder.
     */
    static List<int[]> index(final List<String> names, final boolean descending) {
        final List<int[]> found = new ArrayList<>();
        final List<Character> seen = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            final char letter = initialOf(names.get(i));
            if (!seen.contains(letter)) {
                seen.add(letter);
                found.add(new int[]{ letter, i });
            }
        }
        found.sort((a, b) -> {
            final int order = Integer.compare(rank((char) a[0]), rank((char) b[0]));
            return descending ? -order : order;
        });
        return found;
    }

    private static int rank(final char letter) {
        return letter == OTHER ? -1 : Character.toLowerCase(letter);
    }

    /**
     * Builds the bar for these rows. Answers whether it is worth showing at all: a folder whose names
     * start with one or two letters has nothing to jump between.
     */
    boolean show(final List<String> names, final boolean descending) {
        final List<int[]> found = index(names, descending);
        final List<Character> wanted = new ArrayList<>();
        for (final int[] entry : found) {
            wanted.add((char) entry[0]);
        }
        targets.clear();
        for (final int[] entry : found) {
            targets.add(entry[1]);
        }
        if (wanted.size() < 3) {
            letters.clear();
            column.removeAllViews();
            current = -1;
            return false;
        }
        if (!wanted.equals(letters)) {
            letters.clear();
            letters.addAll(wanted);
            current = -1;
            build();
        }
        return true;
    }

    private void build() {
        column.removeAllViews();
        final Context ctx = getContext();
        final int size = Utils.dpToPx(tv ? 30 : 24);
        for (int i = 0; i < letters.size(); i++) {
            final int at = i;
            final TextView key = new TextView(ctx);
            key.setText(String.valueOf(letters.get(i)));
            key.setGravity(Gravity.CENTER);
            key.setIncludeFontPadding(false);
            key.setTextSize(TypedValue.COMPLEX_UNIT_SP, tv ? 16 : 13);
            key.setFocusable(true);
            key.setClickable(true);
            key.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onLetter(targets.get(at), tv);
                }
            });
            // Walking the column shows each letter's first row as the remote passes it, without
            // taking the remote off the bar; Left, OK and Back are what put it down in the list.
            key.setOnFocusChangeListener((v, focused) -> {
                // Only on the way in: the letter being left hears of it first, and clearing the
                // step there would forget it before the next letter arrives.
                if (focused) {
                    mark(at);
                    if (walking && listener != null) {
                        shifted = true;
                        listener.onLetter(targets.get(at), false);
                    } else if (!walking) {
                        shifted = false;
                    }
                    walking = false;
                }
            });
            key.setOnKeyListener((v, code, event) -> onKey(at, code, event));
            column.addView(key, new LinearLayout.LayoutParams(size, size));
            paint(key, false);
        }
    }

    private boolean onKey(final int at, final int code, final KeyEvent event) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                // Both halves: the activity steps up a folder on Back's UP, and only when nothing
                // took its DOWN - so taking one and not the other leaves the press half-handled.
                if (event.getAction() == KeyEvent.ACTION_UP && listener != null) {
                    listener.onLetter(shifted ? targets.get(at) : -1, true);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // The ends of the column are ends: up from the first letter is the toolbar, and a
                // remote that falls into it has lost its place in a long folder.
                final boolean end = at == (code == KeyEvent.KEYCODE_DPAD_UP ? 0 : letters.size() - 1);
                if (!end && event.getAction() == KeyEvent.ACTION_DOWN) {
                    walking = true;
                }
                return end;
            default:
                return false;
        }
    }

    /** Lights the letter of the row the list is standing on. */
    void mark(final char letter) {
        mark(letters.indexOf(letter));
    }

    private void mark(final int at) {
        if (at == current) {
            return;
        }
        final int was = current;
        current = at;
        if (was >= 0 && was < column.getChildCount()) {
            paint((TextView) column.getChildAt(was), false);
        }
        if (at >= 0 && at < column.getChildCount()) {
            final View key = column.getChildAt(at);
            paint((TextView) key, true);
            if (!hasFocus()) {
                // Kept in sight while the list moves under it: a column taller than the window
                // scrolls, and a lit letter off the edge says nothing.
                post(() -> smoothScrollTo(0, Math.max(0, key.getTop() - (getHeight() - key.getHeight()) / 2)));
            }
        }
    }

    /**
     * The lit letter wears the accent as a disc, the rest are ink on the ground; the focus contour is
     * the one every control in this app takes ({@link Dialogs#pickerRow}).
     */
    private void paint(final TextView key, final boolean lit) {
        final Context ctx = key.getContext();
        final int fill = lit ? MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
                : Color.TRANSPARENT;
        key.setBackground(Dialogs.pickerRow(ctx, fill, false, Utils.dpToPx(tv ? 15 : 12)));
        key.setTextColor(MaterialColors.getColor(ctx, lit ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant, Color.WHITE));
    }

    /**
     * From the list the remote lands on the lit letter, not on whichever letter happens to sit level
     * with the row it left: the bar is offered to the focus search as that one letter alone. Inside
     * the bar every letter is there, so Up and Down walk it.
     *
     * <p>And only to a step to the right. Down from the buttons at the end of the toolbar found the
     * letter straight below them before any row (measured on Shield, tiles) - the bar is the list's
     * margin, not a stop on the way into it.
     */
    @Override
    public void addFocusables(final ArrayList<View> views, final int direction, final int mode) {
        if (hasFocus() || column.getChildCount() == 0) {
            super.addFocusables(views, direction, mode);
            return;
        }
        if (direction != FOCUS_RIGHT) {
            return;
        }
        final View key = column.getChildAt(Math.max(current, 0));
        if (key.isFocusable() && getVisibility() == VISIBLE) {
            views.add(key);
        }
    }
}
