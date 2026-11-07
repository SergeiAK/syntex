package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StartPositionTest {

    @Test
    public void integerAndLongAreMillis() {
        assertEquals(90_000L, StartPosition.millis(90_000));
        assertEquals(90_000L, StartPosition.millis(90_000L));
        assertEquals(0L, StartPosition.millis(0));
    }

    @Test
    public void anythingElseIsNone() {
        assertEquals(-1L, StartPosition.millis(null));
        assertEquals(-1L, StartPosition.millis("90000"));
        assertEquals(-1L, StartPosition.millis(-5));
        assertEquals(-1L, StartPosition.millis((long) Integer.MAX_VALUE + 1));
    }
}
