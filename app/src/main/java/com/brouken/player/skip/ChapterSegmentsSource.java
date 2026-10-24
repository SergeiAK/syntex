package com.brouken.player.skip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * {@link SkipSource} from the file's own chapters named as intro, recap, credits or preview (see
 * {@link FileChapters#categoryOf}), filled in from another source where the chapters are silent.
 *
 * <p>Chapters are cut to this very file, so their timing needs no remap and outranks every other
 * source — the launcher's {@code segments} too, as the Lampa player contract 2.3.0 has it ("file
 * chapters win over segments"). Winning is per kind, though: a file whose only named chapter is
 * "Opening" still gets its credits from the launcher or the online lookup. A segment of the other
 * source is dropped when the chapters already name its kind, or when it overlaps a chapter segment
 * whatever its kind.
 */
public class ChapterSegmentsSource implements SkipSource {

    private final FileChapters chapters;
    @Nullable private final SkipSource other;

    public ChapterSegmentsSource(FileChapters chapters, @Nullable SkipSource other) {
        this.chapters = chapters;
        this.other = other;
    }

    @NonNull
    @Override
    public List<SkipSegment> getSegments(double durationSec) {
        // Built afresh each time: the last chapter's end waits for the duration, and the once-only
        // `skipped` flags must not carry across rebuilds.
        final List<SkipSegment> out = new ArrayList<>(chapters.skipSegments(durationSec));
        if (other == null) {
            return out;
        }
        final EnumSet<SkipSegment.Category> named = EnumSet.noneOf(SkipSegment.Category.class);
        for (SkipSegment s : out) {
            named.add(s.category);
        }
        final List<SkipSegment> fromChapters = new ArrayList<>(out);
        for (SkipSegment s : other.getSegments(durationSec)) {
            if (s.type == SkipSegment.Type.SKIP) {
                final SkipSegment.Category kind = kindOf(s, durationSec);
                if (kind != null && named.contains(kind)) {
                    continue;
                }
            }
            if (!overlapsAny(s, fromChapters)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The kind of a segment from the other source. The launcher's segments come without one, so it is
     * read off the position the way {@link SkipManager} tells the credits: ending in the closing
     * stretch is credits, anything earlier an intro. {@code null} while the duration that takes is
     * unknown — then only the overlap test applies, and the rebuild with the duration sorts it out.
     */
    @Nullable
    static SkipSegment.Category kindOf(SkipSegment s, double durationSec) {
        if (s.category != SkipSegment.Category.UNKNOWN) {
            return s.category;
        }
        if (!(durationSec > 0)) {
            return null;
        }
        return s.endSec >= durationSec * SkipManager.CREDITS_END_FRACTION
                ? SkipSegment.Category.CREDITS : SkipSegment.Category.INTRO;
    }

    private static boolean overlapsAny(SkipSegment s, List<SkipSegment> list) {
        for (SkipSegment c : list) {
            if (s.startSec < c.endSec && c.startSec < s.endSec) {
                return true;
            }
        }
        return false;
    }
}
