package com.brouken.player.skip;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SkipSource} backed by the raw segments JSON delivered through the launch Intent.
 *
 * <p>The JSON mirrors Lampa's {@code data.segments}:
 * <pre>{ "duration_ms": &lt;ms&gt;, "skip": [{"start": &lt;sec&gt;, "end": &lt;sec&gt;}], "ad": [ ... ] }</pre>
 * {@code start}/{@code end} are in seconds. Timings are remapped against the actual file duration
 * via {@link SegmentAdjuster}. Any parse failure yields an empty list — playback is never blocked.
 *
 * <p>A {@code skip} segment may say what it is: {@code "type"} one of {@code intro}, {@code recap},
 * {@code outro} (or {@code credits}), {@code preview}, {@code commercial}. A commercial goes with the
 * {@code ad} ones; the others keep their category, which is what lets the player weigh them against
 * its own lookup instead of taking them whole ({@link #isTyped()}, {@link SegmentFinder#mergeLauncher}).
 * An unknown word reads as no type. Lampa sends none, and for it nothing changes.
 */
public class IntentSegmentsSource implements SkipSource {

    private final List<SegmentAdjuster.Seg> skip = new ArrayList<>();
    private final List<SegmentAdjuster.Seg> ad = new ArrayList<>();
    private double refDurationSec;
    private boolean typed;
    private final boolean valid;

    public IntentSegmentsSource(String json) {
        this.valid = parse(json);
    }

    /** @return true if the JSON held at least one usable segment. */
    public boolean hasSegments() {
        return valid && !(skip.isEmpty() && ad.isEmpty());
    }

    /**
     * Whether the sender named what its segments are. Then they are one voice among the player's own
     * sources rather than the whole answer: a launcher that knows the type usually takes it from a
     * server's analysis of the file (Jellyfin's Intro Skipper), right as often as not.
     */
    public boolean isTyped() {
        return valid && typed;
    }

    /** The category a sender's word names; null for a commercial, UNKNOWN for anything unrecognised. */
    static SkipSegment.Category category(String type) {
        if (type == null) {
            return SkipSegment.Category.UNKNOWN;
        }
        switch (type.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "intro":
                return SkipSegment.Category.INTRO;
            case "recap":
                return SkipSegment.Category.RECAP;
            case "outro":
            case "credits":
                return SkipSegment.Category.CREDITS;
            case "preview":
                return SkipSegment.Category.PREVIEW;
            case "commercial":
                return null;
            default:
                return SkipSegment.Category.UNKNOWN;
        }
    }

    private boolean parse(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(json);
            refDurationSec = parseDurationMs(root.opt("duration_ms"));
            parseArray(root.optJSONArray("skip"), skip, ad);
            parseArray(root.optJSONArray("ad"), ad, ad);
            return true;
        } catch (Exception e) {
            skip.clear();
            ad.clear();
            typed = false;
            return false;
        }
    }

    private void parseArray(JSONArray array, List<SegmentAdjuster.Seg> out, List<SegmentAdjuster.Seg> ads) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject seg = array.optJSONObject(i);
            if (seg == null) {
                continue;
            }
            double start = seg.optDouble("start", 0);
            double end = seg.optDouble("end", 0);
            if (Double.isNaN(start) || Double.isNaN(end) || end <= start) {
                continue;
            }
            final SkipSegment.Category category = out == ads ? SkipSegment.Category.UNKNOWN
                    : category(seg.optString("type", null));
            if (category == null) {
                ads.add(new SegmentAdjuster.Seg(start, end));
                typed = true;
            } else {
                out.add(new SegmentAdjuster.Seg(start, end, category));
                typed |= category != SkipSegment.Category.UNKNOWN;
            }
        }
    }

    /** Mirrors {@code parseDurationMs} in segments.js: ms → sec, guarding NaN/non-positive. */
    private static double parseDurationMs(Object value) {
        if (value == null) {
            return 0;
        }
        double ms;
        try {
            ms = Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
        if (Double.isNaN(ms) || ms <= 0) {
            return 0;
        }
        return ms / 1000.0;
    }

    /** The length the timings were measured against ({@code duration_ms}); 0 when not given. */
    public long referenceDurationMs() {
        return Math.round(refDurationSec * 1000);
    }

    @NonNull
    @Override
    public List<SkipSegment> getSegments(double durationSec) {
        SegmentAdjuster.Result result = SegmentAdjuster.adjust(skip, ad, refDurationSec, durationSec);

        List<SkipSegment> out = new ArrayList<>();
        for (SegmentAdjuster.Seg seg : result.skip) {
            // Typed: timed by whoever analysed this very file, then remapped to its length - as file-
            // accurate as the player's sources get, so chapter-grade trust. Untyped keeps its old shape.
            out.add(seg.category == SkipSegment.Category.UNKNOWN
                    ? new SkipSegment(seg.start, seg.end, SkipSegment.Type.SKIP)
                    : new SkipSegment(seg.start, seg.end, SkipSegment.Type.SKIP, seg.category,
                            SkipSegment.CoordBase.DURATION_AWARE, SkipSegment.TIME_TRUST_CHAPTER));
        }
        for (SegmentAdjuster.Seg seg : result.ad) {
            out.add(new SkipSegment(seg.start, seg.end, SkipSegment.Type.AD));
        }
        return out;
    }
}
