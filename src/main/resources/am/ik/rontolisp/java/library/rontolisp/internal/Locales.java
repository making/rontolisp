// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What the library answers for a locale other than the five every program may have
// (java.util.Locale): a locale whose words, patterns and numbers a JDK writes as the
// root's -- an unknown language -- is the root's; a known one, or one naming a script, a
// variant or a Unicode keyword, is refused by name where the program would need data it
// does not carry. A week is the region's, the letters' cases the Unicode Standard's
// special casing of Turkish, Azerbaijani and Lithuanian. The library reaches this class
// only where foreign() is true: a program that makes no other locale holds none of it.
// Third-party: the languages a JDK has words for and the weeks of the regions are Unicode
// CLDR data (Unicode-3.0), as a running JDK 25 answers them, Copyright (c) 1991-2026
// Unicode, Inc. Notice: THIRD-PARTY-NOTICES.txt, "Unicode".
package rontolisp.internal;

import java.util.Locale;

public final class Locales {

    private Locales() {
    }

    /** Whether the program can make a locale other than the five. */
    public static native boolean foreign();

    /**
     * The languages a JDK 25 writes a word, a pattern, a number or a letter's case of
     * otherwise than the root, or lists as available: by a space.
     */
    private static String known() {
        return " adp af agq ak am ann ar as asa ast az bal bas be bem bez bg bgc bho blo bm bn bo br brx bs ca ccp ce"
                + " ceb cgg chr ckb cnr cs csw cv cy da dav de dje doi drh drw dsb dua dyo dz ebu ee el en eo es et eu"
                + " ewo fa ff fi fil fo fr frr fur fy ga gaa gd gl gsw gu guz gv ha haw he hi hr hsb ht hu hy ia id ie"
                + " ig ii is it ja jgo jmc jv jw ka kab kam kde kea kgp khq ki kk kkj kl kln km kn ko kok ks ksb ksf ksh"
                + " ku kw kxv ky lag lb lg lij lkt lmo ln lo lrc lt lu luo luy lv mai mas mdf mer mfe mg mgh mgo mi mk"
                + " ml mn mni mo mr ms mt mua my mzn naq nb nd nds ne nl nmg nn nnh no nqo nso nus nyn oc om or os pa"
                + " pcm pis pl prp ps pt qu raj rm rn ro rof ru rw rwk sa sah saq sat sbp sc scc scr sd se seh ses sg sh"
                + " shi si sk sl smn sms sn so sq sr st su sv sw syr szl ta te teo tg th ti tk tl tn tnf to tok tr tt"
                + " twq tzm ug uk und ur uz vai vec vi vmw vun wae wo xh xnr xog yav yi yo yrl yue za zgh zh zu ";
    }

    /**
     * Which words, patterns and numbers a locale has: 0 the root's (the root's, an
     * unknown language's), 1 English, 2 British, 3 Canadian; -1 where the program does
     * not carry them.
     */
    public static int kind(Locale locale) {
        if (!locale.getScript().isEmpty() || !locale.getVariant().isEmpty() || keywords(locale)) {
            return -1;
        }
        String language = locale.getLanguage();
        if (language.equals("en")) {
            String country = locale.getCountry();
            return country.isEmpty() || country.equals("US") ? 1
                    : country.equals("GB") ? 2 : country.equals("CA") ? 3 : -1;
        }
        return language.isEmpty() || known().indexOf(" " + language + " ") < 0 ? 0 : -1;
    }

    /** Whether a locale's Unicode extension holds a keyword: a key of two characters. */
    private static boolean keywords(Locale locale) {
        String u = locale.getExtension('u');
        if (u == null) {
            return false;
        }
        int start = 0;
        for (int i = 0; i <= u.length(); i++) {
            if (i == u.length() || u.charAt(i) == '-') {
                if (i - start == 2) {
                    return true;
                }
                start = i + 1;
            }
        }
        return false;
    }

    /**
     * The refusal of what a locale needs: an UnsupportedOperationException, declared as a
     * RuntimeException so that reading this class reads no class a program without
     * another locale would not read as early (the order of its structures).
     */
    public static RuntimeException refused(String what, Locale locale) {
        return new UnsupportedOperationException(
                what + " in the locale " + locale.toLanguageTag() + " is not supported by the experimental Java subset yet");
    }

    /** Refuses what needs a locale's words, patterns or numbers where the program does not carry them. */
    public static void words(String what, Locale locale) {
        if (kind(locale) < 0) {
            throw refused(what, locale);
        }
    }

    /**
     * A format's control, once its locale is known to write it: the conversions of
     * numbers and of dates and times need the locale's own digits, separators and words,
     * an upper-case one its letters' cases.
     */
    public static String control(Locale l, String format) {
        if (l == null || format == null) {
            return format;
        }
        boolean words = kind(l) < 0;
        boolean cases = special(l.getLanguage());
        if (!words && !cases) {
            return format;
        }
        int n = format.length();
        for (int i = 0; i < n; i++) {
            if (format.charAt(i) != '%') {
                continue;
            }
            int j = i + 1;
            while (j < n && "0123456789$<-#+ ,(.".indexOf(format.charAt(j)) >= 0) {
                j++;
            }
            if (j == n) {
                break;
            }
            char c = format.charAt(j);
            if (words && "deEfgGtT".indexOf(c) >= 0 || cases && "SCBHXEGAT".indexOf(c) >= 0) {
                throw refused("the conversion %" + c + " of a format", l);
            }
            i = j;
        }
        return format;
    }

    /** Whether a language's letters change case otherwise than the root's. */
    private static boolean special(String language) {
        return language.equals("tr") || language.equals("az") || language.equals("lt");
    }

    /**
     * A text in upper case in a locale: Turkish and Azerbaijani write a dotted capital I
     * for i, Lithuanian drops a dot above after a soft-dotted letter, which only a dot
     * above written apart asks for.
     */
    public static String upper(String text, Locale locale) {
        String language = locale.getLanguage();
        if (language.equals("tr") || language.equals("az")) {
            text = text.replace('i', 'İ');
        }
        else if (language.equals("lt") && text.indexOf('̇') >= 0) {
            throw refused("toUpperCase of a dot above", locale);
        }
        return text.toUpperCase();
    }

    /**
     * A text in lower case in a locale: Turkish and Azerbaijani write a dotless i for I
     * and i for a dotted capital I or an I before a dot above; Lithuanian keeps the dot
     * of an I, a J or an I with ogonek before an accent above, and of an I with a grave,
     * an acute or a tilde. Where that needs a letter's combining class -- what stands
     * between an I and a dot above, or after an I that is no ASCII letter -- it is refused.
     */
    public static String lower(String text, Locale locale) {
        String language = locale.getLanguage();
        if (language.equals("tr") || language.equals("az")) {
            StringBuilder lowered = new StringBuilder(text.length());
            boolean capital = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == 'I' && i + 1 < text.length() && text.charAt(i + 1) == '̇') {
                    lowered.append('i');
                    i++;
                }
                else if (c == '̇' && capital) {
                    throw refused("toLowerCase of a dot above", locale);
                }
                else {
                    lowered.append(c == 'I' ? 'ı' : c == 'İ' ? 'i' : c);
                }
                capital |= c == 'I';
            }
            return lowered.toString().toLowerCase();
        }
        if (language.equals("lt")) {
            StringBuilder lowered = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if ((c == 'I' || c == 'J' || c == 'Į') && i + 1 < text.length() && text.charAt(i + 1) >= '̀') {
                    throw refused("toLowerCase of an accent above", locale);
                }
                lowered.append(c == 'Ì' ? "i̇̀" : c == 'Í' ? "i̇́"
                        : c == 'Ĩ' ? "i̇̃" : String.valueOf(c));
            }
            return lowered.toString().toLowerCase();
        }
        return text.toLowerCase();
    }

    /**
     * A locale's week: its first day (1 Monday .. 7 Sunday) times eight plus the days its
     * first week holds at least. The region is the country, or the one a Unicode keyword
     * rg names; a keyword fw names the first day.
     */
    public static int week(Locale locale) {
        String region = locale.getCountry();
        String rg = locale.getUnicodeLocaleType("rg");
        if (rg != null && rg.length() == 6 && rg.endsWith("zzzz") && rg.charAt(0) >= 'a' && rg.charAt(0) <= 'z'
                && rg.charAt(1) >= 'a' && rg.charAt(1) <= 'z') {
            region = rg.substring(0, 2).toUpperCase();
        }
        int first;
        int days = 1;
        if (region.isEmpty()) {
            first = 7;
        }
        else {
            String at = " " + region + " ";
            // CLDR's weeks of the regions as a JDK 25 answers them; any other starts on
            // Monday and its first week holds a day
            String sunday = " AG AS BD BR BS BT BW BZ CA CO DM DO ET GT GU HK HN ID IL IN JM JP KE KH KR LA MH MM"
                    + " MO MT MX MZ NI NP PA PE PH PK PR PY SA SG SV TH TT TW UM US VE VI WS YE ZA ZW PT ";
            String saturday = " AF BH DJ DZ EG IQ IR JO KW LY OM QA SD SY ";
            String four = " AD AT AX BE BG CH CZ DE DK EE ES FI FJ FO FR GB GF GG GI GP GR HU IE IM IS IT JE LI LT"
                    + " LU MC MQ NL NO PL RE RU SE SJ SK SM VA AN PT ";
            first = sunday.contains(at) ? 7 : saturday.contains(at) ? 6 : region.equals("MV") ? 5 : 1;
            days = four.contains(at) ? 4 : 1;
        }
        String fw = locale.getUnicodeLocaleType("fw");
        if (fw != null) {
            int day = " mon tue wed thu fri sat sun ".indexOf(" " + fw + " ");
            if (day >= 0 && fw.length() == 3) {
                first = day / 4 + 1;
            }
        }
        return first * 8 + days;
    }
}
