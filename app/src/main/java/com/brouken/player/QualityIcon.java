package com.brouken.player;

/** Icon of the quality button: the class of the picture playing now — SD, HD, FHD or 4K. */
final class QualityIcon {

    private QualityIcon() {
    }

    /**
     * By width, with the height brought to 16:9 for narrow pictures: a 4K film cut to 2.40:1 is 3840x1600
     * and must still read 4K, a 1440x1080 one still FHD. Unknown size keeps the plain HD icon.
     */
    static int of(int width, int height) {
        final int wide = Math.max(width, height * 16 / 9);
        if (wide <= 0) return R.drawable.ic_high_quality_24dp;
        if (wide >= 3200) return R.drawable.ic_quality_4k_24dp;
        if (wide >= 1800) return R.drawable.ic_quality_fhd_24dp;
        if (wide >= 1200) return R.drawable.ic_high_quality_24dp;
        return R.drawable.ic_quality_sd_24dp;
    }
}
