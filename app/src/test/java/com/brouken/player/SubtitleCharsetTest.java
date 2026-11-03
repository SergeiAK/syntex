package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class SubtitleCharsetTest {

    private static final String SRT_RU = "1\n00:00:01,000 --> 00:00:03,000\nПривет, как дела?\n\n"
            + "2\n00:00:04,000 --> 00:00:06,000\nВсё хорошо — «спасибо».\n";

    private static String mend(final String text, final Charset as) {
        final byte[] bytes = text.getBytes(as);
        final byte[] utf8 = new SubtitleCharset.Decoder().toUtf8(bytes, 0, bytes.length);
        return utf8 == null ? null : new String(utf8, StandardCharsets.UTF_8);
    }

    /**
     * UTF-8 read as the page, the way the programs that do it read it: a byte the page leaves undefined
     * becomes the C1 control of the same number. Java's own decoder makes it U+FFFD, which loses it.
     */
    private static String mangle(final String text, final Charset page) {
        final StringBuilder out = new StringBuilder();
        for (final byte b : text.getBytes(StandardCharsets.UTF_8)) {
            final String c = new String(new byte[]{b}, page);
            out.append("�".equals(c) ? (char) (b & 0xFF) : c.charAt(0));
        }
        return out.toString();
    }

    @Test
    public void goodUtf8IsLeftAlone() {
        assertNull(mend(SRT_RU, StandardCharsets.UTF_8));
        assertNull(mend("Plain ASCII line", StandardCharsets.UTF_8));
        assertNull(mend("Déjà vu, garçon — été", StandardCharsets.UTF_8));
        assertNull(mend("Ну всё…", StandardCharsets.UTF_8));
    }

    @Test
    public void byteOrderMarkIsTheParsersBusiness() {
        final byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'};
        assertNull(new SubtitleCharset.Decoder().toUtf8(bom, 0, bom.length));
    }

    @Test
    public void cp1251WholeFile() {
        assertEquals(SRT_RU, mend(SRT_RU, SubtitleCharset.CP1251));
    }

    @Test
    public void cp1251OneShortLine() {
        // An embedded track: one line at a time.
        assertEquals("Да.", mend("Да.", SubtitleCharset.CP1251));
        assertEquals("Ёлки, Ї і Ґ", mend("Ёлки, Ї і Ґ", SubtitleCharset.CP1251));
    }

    @Test
    public void koi8r() {
        assertEquals(SRT_RU.replace("«", "\"").replace("»", "\"").replace("—", "-"),
                mend(SRT_RU.replace("«", "\"").replace("»", "\"").replace("—", "-"), SubtitleCharset.KOI8_R));
    }

    @Test
    public void westernShortLineIsCp1252() {
        assertEquals("Déjà vu, garçon", mend("Déjà vu, garçon", SubtitleCharset.CP1252));
    }

    @Test
    public void theGuessHoldsForTheRestOfTheTrack() {
        final SubtitleCharset.Decoder decoder = new SubtitleCharset.Decoder();
        final byte[] first = "Привет".getBytes(SubtitleCharset.CP1251);
        decoder.toUtf8(first, 0, first.length);
        // On its own this line would read as Western: one high byte among ASCII letters.
        final byte[] second = "Okay Mister Ян".getBytes(SubtitleCharset.CP1251);
        assertArrayEquals("Okay Mister Ян".getBytes(StandardCharsets.UTF_8), decoder.toUtf8(second, 0, second.length));
    }

    @Test
    public void mojibakeThroughCp1251() {
        final String mangled = mangle(SRT_RU, SubtitleCharset.CP1251);
        assertEquals(SRT_RU, mend(mangled, StandardCharsets.UTF_8));
    }

    @Test
    public void mojibakeThroughCp1252() {
        final String mangled = mangle(SRT_RU, SubtitleCharset.CP1252);
        assertEquals(SRT_RU, mend(mangled, StandardCharsets.UTF_8));
        final String french = mangle("Déjà vu", SubtitleCharset.CP1252);
        assertEquals("Déjà vu", mend(french, StandardCharsets.UTF_8));
    }

    @Test
    public void mojibakeWithTheUndefinedByte() {
        // "И" is D0 98 in UTF-8, and 0x98 is the one byte cp1251 leaves undefined.
        assertEquals("Иван", SubtitleCharset.repairMojibake(mangle("Иван", SubtitleCharset.CP1251)));
        assertNull(SubtitleCharset.repairMojibake("Иван"));
        // Mangled only halfway: not strict UTF-8 underneath, so not touched.
        assertNull(SubtitleCharset.repairMojibake("\u0420\u0098" + "ван"));
    }
}
