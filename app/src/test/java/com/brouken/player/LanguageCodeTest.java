package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LanguageCodeTest {

    @Test
    public void bareCodesAreRead() {
        assertEquals("eng", LanguageCode.bare("eng"));
        assertEquals("rus", LanguageCode.bare(" RUS "));
        assertEquals("de", LanguageCode.bare("de"));
        assertEquals("ukr", LanguageCode.bare("Ukr"));
    }

    @Test
    public void bibliographicFormsBecomeOnesLocaleCanName() {
        assertEquals("de", LanguageCode.bare("ger"));
        assertEquals("fr", LanguageCode.bare("FRE"));
    }

    @Test
    public void namesStayNames() {
        assertNull(LanguageCode.bare("SDH"));
        assertNull(LanguageCode.bare("AC3"));
        assertNull(LanguageCode.bare("Forced"));
        assertNull(LanguageCode.bare("English"));
        assertNull(LanguageCode.bare("Дубляж"));
        assertNull(LanguageCode.bare("e"));
        assertNull(LanguageCode.bare(null));
    }
}
