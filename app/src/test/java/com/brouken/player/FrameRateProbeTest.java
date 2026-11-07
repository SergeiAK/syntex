package com.brouken.player;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.Format;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rate the probe measures from sample times. The 25i cases are the first packets of library
 * recordings (18.10.2026); the average used before read the dropped-fields ones as 49.25–49.57,
 * which no window matched and the display mode rule sent to 60 Hz.
 */
public class FrameRateProbeTest {

    private static final int IGNORE_TAIL = 30;

    private static List<Long> load(final String name) throws Exception {
        final List<Long> times = new ArrayList<>();
        try (InputStream in = FrameRateProbeTest.class.getResourceAsStream("/frame-times/" + name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    times.add(Long.parseLong(line.trim()));
                }
            }
        }
        return times;
    }

    /** Evenly spaced times at this rate, rounded to µs the way an extractor reports them. */
    private static List<Long> even(final double fps, final int count) {
        final List<Long> times = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            times.add(Math.round(i * 1_000_000d / fps));
        }
        return times;
    }

    private static float measure(final List<Long> times) {
        return Utils.frameRateFromSampleTimes(times, IGNORE_TAIL);
    }

    @Test
    public void interlacedRecordingWithDroppedFieldsIsFiftyFields() throws Exception {
        assertEquals(50f, measure(load("ts-1080i-dropped-fields-a.txt")), 0f);
        assertEquals(50f, measure(load("ts-1080i-dropped-fields-b.txt")), 0f);
    }

    @Test
    public void evenInterlacedRecordingIsFiftyFields() throws Exception {
        assertEquals(50f, measure(load("ts-576i-even.txt")), 0f);
    }

    @Test
    public void standardRatesSnapFromMicrosecondSteps() {
        assertEquals(24000f / 1001f, measure(even(24000d / 1001d, 380)), 0f);
        assertEquals(24f, measure(even(24d, 380)), 0f);
        assertEquals(25f, measure(even(25d, 380)), 0f);
        assertEquals(30000f / 1001f, measure(even(30000d / 1001d, 380)), 0f);
        assertEquals(60000f / 1001f, measure(even(60000d / 1001d, 380)), 0f);
    }

    @Test
    public void presentationOrderDoesNotMatter() {
        // Decode order of an IBBP stream: each P before the two B frames shown ahead of it.
        final List<Long> shown = even(24000d / 1001d, 381);
        final List<Long> decoded = new ArrayList<>();
        decoded.add(shown.get(0));
        for (int i = 1; i + 2 < shown.size(); i += 3) {
            decoded.add(shown.get(i + 2));
            decoded.add(shown.get(i));
            decoded.add(shown.get(i + 1));
        }
        assertEquals(24000f / 1001f, measure(decoded), 0f);
    }

    @Test
    public void aFewDroppedFramesDoNotMoveTheRate() {
        final List<Long> times = even(25d, 380);
        for (int i = 300; i > 0; i -= 50) {
            times.remove(i);
        }
        Collections.shuffle(times, new java.util.Random(1));
        assertEquals(25f, measure(times), 0f);
    }

    @Test
    public void tooFewTimesTellNothing() {
        assertEquals(Format.NO_VALUE, measure(new ArrayList<>()), 0f);
        final List<Long> one = new ArrayList<>();
        one.add(0L);
        assertEquals(Format.NO_VALUE, measure(one), 0f);
    }

    /** Steps repeating the given pattern (µs), from 0, as many times as asked. */
    private static List<Long> pattern(final int steps, final long... cadence) {
        final List<Long> times = new ArrayList<>();
        long t = 0;
        times.add(t);
        for (int i = 0; i < steps; i++) {
            t += cadence[i % cadence.length];
            times.add(t);
        }
        return times;
    }

    /** 2:3 pulldown left in the times: 33.4 and 50 ms in turn is film at 23.976, not 30 or 20. */
    @Test
    public void pulldownCadenceIsReadByItsAverage() {
        assertEquals(24000f / 1001f, measure(pattern(400, 33367, 50050)), 0f);
        // 2:3:2:3 as fields of 59.94 kept per frame, the same film.
        assertEquals(24000f / 1001f, measure(pattern(400, 33367, 50050, 33367, 50050)), 0f);
    }

    /** Half the run at 24, half at 30: no one rate to put the display at. */
    @Test
    public void variableRateGivesNone() {
        final List<Long> times = pattern(200, 41708, 33367);
        // A stretch of 30 fps then one of 24, the way an edit of two sources runs.
        final List<Long> edit = pattern(200, 33367);
        final long last = edit.get(edit.size() - 1);
        for (Long t : pattern(200, 41708).subList(1, 201)) {
            edit.add(last + t);
        }
        assertEquals(Format.NO_VALUE, measure(edit), 0f);
        // An irregular phone clip: steps wander between 25 and 45 ms.
        final List<Long> phone = new ArrayList<>();
        long t = 0;
        final java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 400; i++) {
            phone.add(t);
            t += 25_000 + random.nextInt(20_000) + (i / 100) * 5_000;
        }
        assertEquals(Format.NO_VALUE, measure(phone), 0f);
        // An alternation that does hold its average is a cadence, not VFR.
        assertEquals(1_000_000f / 37537.5f, measure(times), 0.01f);
    }
}
