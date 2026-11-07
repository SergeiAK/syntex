package com.brouken.player;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A track name that is nothing but a language code — "eng", "RUS", "de", as muxers write them into the
 * title — is not a name: shown as it is, the menu reads "eng" where every other row says "English".
 * Only the codes of ISO 639-1 and their ISO 639-2 forms count, so a label such as "SDH" or "AC3", or a
 * rare 639-3 code that happens to look like a word, is left as the name it is.
 */
final class LanguageCode {

    private static final Set<String> CODES = new HashSet<>();

    static {
        for (final String two : Locale.getISOLanguages()) {
            CODES.add(two);
            try {
                CODES.add(new Locale(two).getISO3Language());
            } catch (java.util.MissingResourceException ignored) {
                // A code with no three-letter form: the two-letter one stands alone.
            }
        }
    }

    /** The bibliographic 639-2 forms muxers still write, which Locale does not know, to 639-1. */
    private static final String[][] BIBLIOGRAPHIC = {
            {"ger", "de"}, {"fre", "fr"}, {"dut", "nl"}, {"chi", "zh"}, {"cze", "cs"}, {"gre", "el"},
            {"per", "fa"}, {"rum", "ro"}, {"slo", "sk"}, {"alb", "sq"}, {"arm", "hy"}, {"baq", "eu"},
            {"bur", "my"}, {"geo", "ka"}, {"ice", "is"}, {"mac", "mk"}, {"mao", "mi"}, {"may", "ms"},
            {"tib", "bo"}, {"wel", "cy"}};

    private LanguageCode() {
    }

    /**
     * The code, lowercase, when the whole name is one — a bibliographic one as its 639-1 form, which
     * Locale can name; null when it is a name.
     */
    @Nullable
    static String bare(@Nullable final String name) {
        if (name == null) {
            return null;
        }
        final String code = name.trim().toLowerCase(Locale.ROOT);
        if (code.length() < 2 || code.length() > 3) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c < 'a' || c > 'z') {
                return null;
            }
        }
        for (final String[] pair : BIBLIOGRAPHIC) {
            if (pair[0].equals(code)) {
                return pair[1];
            }
        }
        return CODES.contains(code) ? code : null;
    }
}
