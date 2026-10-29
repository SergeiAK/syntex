package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QualityIconTest {

    @Test
    public void commonSizes() {
        assertEquals(R.drawable.ic_quality_sd_24dp, QualityIcon.of(720, 576));
        assertEquals(R.drawable.ic_high_quality_24dp, QualityIcon.of(1280, 720));
        assertEquals(R.drawable.ic_quality_fhd_24dp, QualityIcon.of(1920, 1080));
        assertEquals(R.drawable.ic_quality_4k_24dp, QualityIcon.of(3840, 2160));
    }

    /** Scope films lose height, not width. */
    @Test
    public void wideFilmsKeepTheirClass() {
        assertEquals(R.drawable.ic_quality_4k_24dp, QualityIcon.of(3840, 1600));
        assertEquals(R.drawable.ic_quality_fhd_24dp, QualityIcon.of(1920, 800));
        assertEquals(R.drawable.ic_high_quality_24dp, QualityIcon.of(1280, 536));
    }

    /** 4:3 and anamorphic storage lose width, not height. */
    @Test
    public void narrowPicturesKeepTheirClass() {
        assertEquals(R.drawable.ic_quality_fhd_24dp, QualityIcon.of(1440, 1080));
        assertEquals(R.drawable.ic_high_quality_24dp, QualityIcon.of(960, 720));
    }

    @Test
    public void unknownSizeIsPlainHd() {
        assertEquals(R.drawable.ic_high_quality_24dp, QualityIcon.of(NO_VALUE, NO_VALUE));
        assertEquals(R.drawable.ic_high_quality_24dp, QualityIcon.of(0, 0));
    }

    /** Format.NO_VALUE, as a renderer reports an unknown size. */
    private static final int NO_VALUE = -1;
}
