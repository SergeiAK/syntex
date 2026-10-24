package com.brouken.player.skip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.Tracks;
import androidx.media3.extractor.metadata.Chapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The chapters of the file being played, as Media3 1.11 extracts them: Matroska {@code Chapters},
 * MP4 Nero {@code chpl} and the QuickTime chapter track all arrive as {@link Chapter} entries in a
 * track's {@link Format#metadata} — not through {@code onMetadata}, which is where ID3 chapters of an
 * audio file would come.
 *
 * <p>What Media3 hands over needs tidying before it is a list a viewer would recognise: Matroska
 * chapters are keyed by their UID, not their time, so they come out in UID order; {@code
 * ChapterTimeEnd} is optional there, so an end can be unknown. Here they are sorted by start and an
 * unknown end is taken from the next chapter's start (the last one's from the duration).
 *
 * <p>Times are in the period's coordinates. For a progressive file the window starts at the period
 * start, so they are seek positions as they are.
 */
public final class FileChapters {

    /** A chapter that starts this close to the position counts as the one we are at, not the next. */
    static final long SAME_POSITION_MS = 1000;

    /**
     * "Previous" within this many milliseconds of a chapter's start goes to the chapter before it;
     * later, it goes back to the start of the current one. The same 3 s as Media3's own
     * {@code seekToPrevious} uses for media items.
     */
    static final long RESTART_WINDOW_MS = 3000;

    public static final FileChapters NONE = new FileChapters(Collections.<Entry>emptyList());

    /** One chapter: start and end in milliseconds, the end possibly {@link C#TIME_UNSET}. */
    public static final class Entry {
        public final long startMs;
        public final long endMs;
        @Nullable public final String title;
        public final boolean hidden;

        public Entry(long startMs, long endMs, @Nullable String title, boolean hidden) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.title = title;
            this.hidden = hidden;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Entry)) {
                return false;
            }
            final Entry e = (Entry) o;
            return startMs == e.startMs && endMs == e.endMs && hidden == e.hidden
                    && (title == null ? e.title == null : title.equals(e.title));
        }

        @Override
        public int hashCode() {
            return (int) (startMs * 31 + endMs) * 31 + (title == null ? 0 : title.hashCode());
        }

        @NonNull
        @Override
        public String toString() {
            return startMs + "-" + endMs + (hidden ? " hidden" : "") + " " + title;
        }
    }

    /** Sorted by start; ends as delivered (see {@link #endMs}). */
    private final List<Entry> entries;

    FileChapters(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * The chapters carried by the current tracks. Matroska copies the same list into every track's
     * format and MP4 attaches it to one, so the first track that has any is the answer.
     */
    @NonNull
    public static FileChapters from(@Nullable Tracks tracks) {
        if (tracks == null) {
            return NONE;
        }
        for (Tracks.Group group : tracks.getGroups()) {
            for (int i = 0; i < group.length; i++) {
                final FileChapters found = from(group.getTrackFormat(i).metadata);
                if (!found.entries.isEmpty()) {
                    return found;
                }
            }
        }
        return NONE;
    }

    @NonNull
    static FileChapters from(@Nullable Metadata metadata) {
        if (metadata == null) {
            return NONE;
        }
        final List<Entry> list = new ArrayList<>();
        for (int i = 0; i < metadata.length(); i++) {
            final Metadata.Entry entry = metadata.get(i);
            if (!(entry instanceof Chapter)) {
                continue;
            }
            final Chapter chapter = (Chapter) entry;
            if (chapter.getStartTimeMs() == C.TIME_UNSET || chapter.getStartTimeMs() < 0) {
                continue; // nothing to seek to
            }
            list.add(new Entry(chapter.getStartTimeMs(), chapter.getEndTimeMs(),
                    chapter.getTitle() != null ? chapter.getTitle().value : null, chapter.isHidden()));
        }
        return of(list);
    }

    @NonNull
    static FileChapters of(List<Entry> list) {
        if (list.isEmpty()) {
            return NONE;
        }
        final List<Entry> sorted = new ArrayList<>(list);
        Collections.sort(sorted, (a, b) -> Long.compare(a.startMs, b.startMs));
        return new FileChapters(Collections.unmodifiableList(sorted));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * The end of chapter {@code index}: its own end when it has one past its start, else the next
     * chapter's start, else the duration ({@link C#TIME_UNSET} while that is unknown).
     */
    public long endMs(int index, long durationMs) {
        final Entry e = entries.get(index);
        if (e.endMs != C.TIME_UNSET && e.endMs > e.startMs) {
            return e.endMs;
        }
        for (int j = index + 1; j < entries.size(); j++) {
            if (entries.get(j).startMs > e.startMs) {
                return entries.get(j).startMs;
            }
        }
        return durationMs > e.startMs ? durationMs : C.TIME_UNSET;
    }

    // --- Skip by chapter name ---

    /** What follows the name proper: "Opening - Kaikai Kitan", "Intro (song)", "Credits: cast". */
    // A dash only splits off a tail with space before it: "end-credits" is one name.
    private static final Pattern TAIL = Pattern.compile("(\\s+[-–—]\\s.*|\\s*[:(\\[|/].*)$");
    /** "OP", "OP1", "ED 2", "NCOP" — the anime shorthand, which only ever stands alone. */
    private static final Pattern SHORT_OP = Pattern.compile("^(nc)?op\\s*\\d*$");
    private static final Pattern SHORT_ED = Pattern.compile("^(nc)?ed\\s*\\d*$");
    /** "Opening 2", "Intro 1": a number after the name numbers the song, it does not change the kind. */
    private static final Pattern TRAILING_NUMBER = Pattern.compile("\\s+\\d+$");

    private static final String[] INTRO = {
            "intro", "opening", "opening credits", "opening titles", "opening theme", "opening song",
            "theme song", "title sequence", "вступление", "заставка", "интро", "опенинг",
            "начальные титры",
    };
    private static final String[] RECAP = {
            "recap", "previously", "previously on", "ранее", "ранее в сериале", "в предыдущих сериях",
            "в предыдущей серии", "краткое содержание",
    };
    private static final String[] CREDITS = {
            "credits", "end credits", "ending credits", "closing credits", "ending", "ending theme",
            "ending song", "outro", "титры", "финальные титры", "конечные титры", "эндинг",
    };
    private static final String[] PREVIEW = {
            "preview", "next episode", "next episode preview", "next time", "анонс",
            "анонс следующей серии", "в следующей серии", "превью",
    };

    /**
     * The skip category a chapter's name says it is, or {@code null} for a story chapter. The whole
     * name has to be the marker — "Intro", "Opening - song title", "OP2" — never a word somewhere
     * in it: "Introduction to the case" is a story chapter, and "Mid-credits scene" is content a
     * viewer skipping the credits wants to see.
     */
    @Nullable
    public static SkipSegment.Category categoryOf(@Nullable String title) {
        if (title == null) {
            return null;
        }
        String name = title.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        name = TAIL.matcher(name).replaceFirst("");
        name = name.replace('_', ' ').replace('.', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        if (SHORT_OP.matcher(name).matches()) {
            return SkipSegment.Category.INTRO;
        }
        if (SHORT_ED.matcher(name).matches()) {
            return SkipSegment.Category.CREDITS;
        }
        name = TRAILING_NUMBER.matcher(name).replaceFirst("");
        if (contains(INTRO, name)) {
            return SkipSegment.Category.INTRO;
        }
        if (contains(RECAP, name)) {
            return SkipSegment.Category.RECAP;
        }
        if (contains(CREDITS, name)) {
            return SkipSegment.Category.CREDITS;
        }
        if (contains(PREVIEW, name)) {
            return SkipSegment.Category.PREVIEW;
        }
        return null;
    }

    private static boolean contains(String[] names, String name) {
        for (String n : names) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any chapter is named as something to skip. */
    public boolean hasSkipChapters() {
        for (Entry e : entries) {
            if (categoryOf(e.title) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Skip segments for the chapters named as intro, recap, credits or preview, in file coordinates.
     * Hidden chapters count: hidden means "not in a table of contents", not "not there". Adjacent
     * chapters of one kind (an "Opening" split in two) become one segment. A chapter whose end cannot
     * be known yet — the last one, before the duration is — is left for the rebuild that has it.
     */
    @NonNull
    List<SkipSegment> skipSegments(double durationSec) {
        final long durationMs = durationSec > 0 ? Math.round(durationSec * 1000) : C.TIME_UNSET;
        final List<SkipSegment> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            final SkipSegment.Category category = categoryOf(entries.get(i).title);
            if (category == null) {
                continue;
            }
            final long start = entries.get(i).startMs;
            long end = endMs(i, durationMs);
            while (i + 1 < entries.size() && categoryOf(entries.get(i + 1).title) == category
                    && end != C.TIME_UNSET && entries.get(i + 1).startMs <= end) {
                i++;
                end = Math.max(end, endMs(i, durationMs));
            }
            if (end == C.TIME_UNSET || end <= start) {
                continue;
            }
            out.add(new SkipSegment(start / 1000.0, end / 1000.0, SkipSegment.Type.SKIP, category,
                    SkipSegment.CoordBase.CHAPTER, SkipSegment.TIME_TRUST_CHAPTER));
        }
        return out;
    }

    // --- Previous / next chapter ---

    /** Chapters a viewer can step through: not hidden, one per start time. */
    private List<Long> visibleStarts() {
        final List<Long> starts = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (!e.hidden && (starts.isEmpty() || starts.get(starts.size() - 1) != e.startMs)) {
                starts.add(e.startMs);
            }
        }
        return starts;
    }

    /** Stepping makes sense from two chapters up: a single one is the whole file. */
    public boolean canStep() {
        return visibleStarts().size() >= 2;
    }

    /**
     * Where "next chapter" goes from {@code positionMs}: the first chapter starting past it, or
     * {@link C#TIME_UNSET} in the last chapter, where the caller moves on to the next file.
     */
    public long nextStartMs(long positionMs) {
        if (!canStep()) {
            return C.TIME_UNSET;
        }
        for (long start : visibleStarts()) {
            if (start > positionMs + SAME_POSITION_MS) {
                return start;
            }
        }
        return C.TIME_UNSET;
    }

    /**
     * Where "previous chapter" goes: the start of the current chapter when well into it, else the
     * start of the one before. {@link C#TIME_UNSET} at the start of the first chapter, where the
     * caller goes to the previous file — and likewise before the first chapter, in a file whose
     * chapters start late.
     */
    public long previousStartMs(long positionMs) {
        if (!canStep()) {
            return C.TIME_UNSET;
        }
        final List<Long> starts = visibleStarts();
        int current = -1;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) <= positionMs + SAME_POSITION_MS) {
                current = i;
            }
        }
        if (current < 0) {
            return C.TIME_UNSET;
        }
        if (positionMs - starts.get(current) > RESTART_WINDOW_MS) {
            return starts.get(current);
        }
        return current > 0 ? starts.get(current - 1) : C.TIME_UNSET;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileChapters && entries.equals(((FileChapters) o).entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return entries.toString();
    }
}
