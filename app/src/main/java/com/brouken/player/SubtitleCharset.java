package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.Subtitle;
import androidx.media3.extractor.text.SubtitleParser;

import com.sigpwned.chardet4j.Chardet;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Text subtitles in whatever encoding they came in, put right before Media3 parses them. Media3's SubRip,
 * SSA and WebVTT parsers read anything without a byte order mark as UTF-8 (SubripParser.detectUtfCharset,
 * 1.11.0), so a cp1251 .srt — still the usual thing beside a Russian rip — comes out as a row of
 * replacement marks.
 * <p>
 * The files the player copies itself were already re-encoded (Utils.convertInputStreamToUTF), but that
 * was only those: a subtitle a launcher hands over by URL, a playlist's subtitles and the text tracks
 * inside an MKV went to the parser as they were. Here, around the parser factory, it is every one of them.
 * <p>
 * Two faults are mended:
 * <ul>
 *   <li><b>Not UTF-8 at all</b> — guessed as Cyrillic (cp1251 or KOI8-R) or Western. An embedded track
 *       arrives one line at a time, too short for a statistical detector, so the guess is a count of
 *       bytes that holds on a single line, and is kept for the rest of the track once made.
 *   <li><b>UTF-8 that was once read as cp1251 or cp1252 and saved again</b> — "РџСЂРёРІРµС‚" or
 *       "ÐŸÑ€Ð¸Ð²ÐµÑ‚" for "Привет". Valid UTF-8, so nothing else would touch it. Mended only when the
 *       whole text maps back to bytes of that code page and those bytes are strict UTF-8: real Russian
 *       or French text practically never does.
 * </ul>
 */
final class SubtitleCharset {

    static final Charset CP1251 = Charset.forName("windows-1251");
    static final Charset CP1252 = Charset.forName("windows-1252");
    static final Charset KOI8_R = Charset.forName("KOI8-R");

    /** At or above this share of non-ASCII bytes among the letters, a text is Cyrillic in an 8-bit page. */
    private static final double CYRILLIC_SHARE = 0.3;
    /** Below this a statistical detector guesses at random; the code page of the West is the safer bet. */
    private static final int DETECTOR_MIN_BYTES = 256;

    private static final char[][] REVERSE = {reverseTable(CP1251), reverseTable(CP1252)};
    private static final Charset[] REVERSE_PAGES = {CP1251, CP1252};

    private SubtitleCharset() {
    }

    /** The formats whose Media3 parsers decode bytes as UTF-8 by themselves. TTML declares its own. */
    static boolean isPlainText(@Nullable final String mimeType) {
        return MimeTypes.APPLICATION_SUBRIP.equals(mimeType) || MimeTypes.TEXT_SSA.equals(mimeType)
                || MimeTypes.TEXT_VTT.equals(mimeType);
    }

    /** One track's worth of decoding: the code page, once guessed, holds for every later block. */
    static final class Decoder {

        @Nullable
        private Charset settled;
        /** What the last block that needed mending was taken for, for the log. */
        @Nullable
        String lastFix;

        /** The block as UTF-8, or null when it already is good UTF-8 and is to be parsed as it is. */
        @Nullable
        byte[] toUtf8(final byte[] data, final int offset, final int length) {
            if (hasBom(data, offset, length)) {
                return null;
            }
            final String text = strictUtf8(data, offset, length);
            if (text != null) {
                final String repaired = repairMojibake(text);
                if (repaired == null) {
                    return null;
                }
                lastFix = "mojibake";
                return repaired.getBytes(StandardCharsets.UTF_8);
            }
            if (settled == null) {
                settled = guess(data, offset, length);
            }
            lastFix = settled.name();
            return new String(data, offset, length, settled).getBytes(StandardCharsets.UTF_8);
        }
    }

    static boolean hasBom(final byte[] data, final int offset, final int length) {
        if (length >= 3 && (data[offset] & 0xFF) == 0xEF && (data[offset + 1] & 0xFF) == 0xBB
                && (data[offset + 2] & 0xFF) == 0xBF) {
            return true;
        }
        if (length >= 2) {
            final int a = data[offset] & 0xFF;
            final int b = data[offset + 1] & 0xFF;
            return (a == 0xFE && b == 0xFF) || (a == 0xFF && b == 0xFE);
        }
        return false;
    }

    /** The bytes as UTF-8, or null when they are not valid UTF-8. */
    @Nullable
    static String strictUtf8(final byte[] data, final int offset, final int length) {
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            final CharBuffer chars = decoder.decode(ByteBuffer.wrap(data, offset, length));
            return chars.toString();
        } catch (final CharacterCodingException e) {
            return null;
        }
    }

    /**
     * The code page of bytes that are not UTF-8. In an 8-bit Cyrillic page nearly every letter is a byte
     * of 0x80 and up; in a Western one they are the odd accented letter. Between the two Cyrillic pages,
     * lower case decides — most of any text is lower case, and cp1251 keeps it at 0xE0–0xFF where KOI8-R
     * keeps its capitals.
     */
    static Charset guess(final byte[] data, final int offset, final int length) {
        int high = 0;
        int ascii = 0;
        int upperRow = 0; // 0xE0–0xFF: lower case in cp1251, capitals in KOI8-R
        int lowerRow = 0; // 0xC0–0xDF: the other way round
        for (int i = offset; i < offset + length; i++) {
            final int b = data[i] & 0xFF;
            if (b >= 0x80) {
                high++;
                if (b >= 0xE0) {
                    upperRow++;
                } else if (b >= 0xC0) {
                    lowerRow++;
                }
            } else if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')) {
                ascii++;
            }
        }
        if (high > 0 && high >= CYRILLIC_SHARE * (high + ascii)) {
            return lowerRow > upperRow ? KOI8_R : CP1251;
        }
        if (length >= DETECTOR_MIN_BYTES) {
            final byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            final Charset detected = Chardet.detectCharset(copy).orElse(null);
            if (detected != null && !StandardCharsets.UTF_8.equals(detected)) {
                return detected;
            }
        }
        return CP1252;
    }

    /**
     * The text UTF-8 was before someone read it as cp1251 or cp1252, or null when it is not that. All of
     * it has to map back to the page and the bytes have to be strict UTF-8 that says something else.
     */
    @Nullable
    static String repairMojibake(final String text) {
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x80) {
                nonAscii++;
            }
        }
        // One stray character is a letter; a mangled one is always two or more.
        if (nonAscii < 2) {
            return null;
        }
        for (int page = 0; page < REVERSE.length; page++) {
            final byte[] bytes = encodeBack(text, REVERSE[page]);
            if (bytes == null) {
                continue;
            }
            final String repaired = strictUtf8(bytes, 0, bytes.length);
            if (repaired != null && !repaired.equals(text)) {
                return repaired;
            }
        }
        return null;
    }

    /** The page's bytes for the text, or null when a character is not on the page. */
    @Nullable
    private static byte[] encodeBack(final String text, final char[] table) {
        final byte[] bytes = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < 0x80) {
                bytes[i] = (byte) c;
                continue;
            }
            int found = -1;
            for (int b = 0; b < 0x80; b++) {
                if (table[b] == c) {
                    found = b;
                    break;
                }
            }
            if (found < 0) {
                return null;
            }
            bytes[i] = (byte) (0x80 + found);
        }
        return bytes;
    }

    /**
     * The characters of bytes 0x80–0xFF on the page. A byte the page leaves undefined (0x98 in cp1251,
     * 0x81 in cp1252) is whatever the program that mangled the text made of it, usually the C1 control
     * of the same number, so that is what it maps back from.
     */
    private static char[] reverseTable(final Charset page) {
        final char[] table = new char[0x80];
        for (int b = 0x80; b <= 0xFF; b++) {
            final String s = new String(new byte[]{(byte) b}, page);
            final char c = s.length() == 1 ? s.charAt(0) : '�';
            table[b - 0x80] = c == '�' ? (char) b : c;
        }
        return table;
    }

    /** Media3's parsers, with every plain-text one fed UTF-8. */
    static final class ParserFactory implements SubtitleParser.Factory {

        private final SubtitleParser.Factory delegate;

        ParserFactory(final SubtitleParser.Factory delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supportsFormat(final Format format) {
            return delegate.supportsFormat(format);
        }

        @Override
        public int getCueReplacementBehavior(final Format format) {
            return delegate.getCueReplacementBehavior(format);
        }

        @Override
        public SubtitleParser create(final Format format) {
            final SubtitleParser parser = delegate.create(format);
            return isPlainText(format.sampleMimeType) ? new Parser(parser, format.sampleMimeType) : parser;
        }
    }

    private static final class Parser implements SubtitleParser {

        private final SubtitleParser delegate;
        private final String mimeType;
        private final Decoder decoder = new Decoder();
        private boolean logged;

        Parser(final SubtitleParser delegate, final String mimeType) {
            this.delegate = delegate;
            this.mimeType = mimeType;
        }

        @Override
        public void parse(final byte[] data, final int offset, final int length, final OutputOptions outputOptions,
                          final Consumer<CuesWithTiming> output) {
            final byte[] utf8 = mend(data, offset, length);
            if (utf8 == null) {
                delegate.parse(data, offset, length, outputOptions, output);
            } else {
                delegate.parse(utf8, 0, utf8.length, outputOptions, output);
            }
        }

        @Override
        public Subtitle parseToLegacySubtitle(final byte[] data, final int offset, final int length) {
            final byte[] utf8 = mend(data, offset, length);
            return utf8 == null ? delegate.parseToLegacySubtitle(data, offset, length)
                    : delegate.parseToLegacySubtitle(utf8, 0, utf8.length);
        }

        @Nullable
        private byte[] mend(final byte[] data, final int offset, final int length) {
            final byte[] utf8 = decoder.toUtf8(data, offset, length);
            if (utf8 != null && !logged) {
                // Once per track: an embedded track passes through here on every line.
                logged = true;
                Utils.log("subtitles: " + mimeType + " read as " + decoder.lastFix + ", not UTF-8");
            }
            return utf8;
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        @Override
        public int getCueReplacementBehavior() {
            return delegate.getCueReplacementBehavior();
        }
    }
}
