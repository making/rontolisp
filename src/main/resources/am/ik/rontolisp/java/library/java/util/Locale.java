// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A locale is a language, a script, a country, a variant and the extensions of a BCP 47
// tag (RFC 5646; the grandfathered tags and their preferred values are its registry's).
// What the specification leaves open -- where a tag that is not well-formed stops, the
// order a locale's texts are written in, the words of a builder's refusals and their
// indices, which makings answer one object -- are a JDK 25's answers to probes.
//
// The five locales below are the ones every program may have; any other is made by
// made() or by a constructor the program calls, which the compiler watches
// (rontolisp.internal.Locales.foreign()): a program that does neither holds none of what
// the others need, and the members below answer for the five alone.
package java.util;

public final class Locale implements Cloneable {

    public static final Locale ROOT = new Locale("", "");

    public static final Locale ENGLISH = new Locale("en", "");

    public static final Locale US = new Locale("en", "US");

    public static final Locale UK = new Locale("en", "GB");

    public static final Locale CANADA = new Locale("en", "CA");

    public static final Locale FRENCH = wanted() ? made("fr", "") : null;

    public static final Locale GERMAN = wanted() ? made("de", "") : null;

    public static final Locale ITALIAN = wanted() ? made("it", "") : null;

    public static final Locale JAPANESE = wanted() ? made("ja", "") : null;

    public static final Locale KOREAN = wanted() ? made("ko", "") : null;

    public static final Locale CHINESE = wanted() ? made("zh", "") : null;

    public static final Locale SIMPLIFIED_CHINESE = wanted() ? made("zh", "CN") : null;

    public static final Locale TRADITIONAL_CHINESE = wanted() ? made("zh", "TW") : null;

    public static final Locale FRANCE = wanted() ? made("fr", "FR") : null;

    public static final Locale GERMANY = wanted() ? made("de", "DE") : null;

    public static final Locale ITALY = wanted() ? made("it", "IT") : null;

    public static final Locale JAPAN = wanted() ? made("ja", "JP") : null;

    public static final Locale KOREA = wanted() ? made("ko", "KR") : null;

    public static final Locale CANADA_FRENCH = wanted() ? made("fr", "CA") : null;

    public static final Locale CHINA = wanted() ? made("zh", "CN") : null;

    public static final Locale PRC = wanted() ? made("zh", "CN") : null;

    public static final Locale TAIWAN = wanted() ? made("zh", "TW") : null;

    /** Whether the program reads the field being initialized. */
    private static native boolean wanted();

    /**
     * The locales made by of, forLanguageTag and the builder, by their fields: a Map, as
     * the class's declarations name no class a program without another locale would not
     * read as early (the order of its structures).
     */
    private static Map<String, Locale> made;

    private final String language;

    /**
     * The country; in a locale with a script, a variant or extensions (or a country
     * starting with a NUL), a NUL, the country's length as a character, the country, the
     * script's length and the script, the variant, a NUL and the extensions, which hold
     * no NUL (part()). The five locales hold their country alone, and so does every
     * locale of a program that makes no other.
     */
    private final String country;

    private Locale(String[] fields) {
        this.language = fields[0];
        this.country = held(fields);
    }

    /** The country of a locale's fields, with what follows it. */
    private static String held(String[] fields) {
        String country = fields[2];
        if (fields[1].isEmpty() && fields[3].isEmpty() && fields[4].isEmpty()
                && (country.isEmpty() || country.charAt(0) != '\0')) {
            return country;
        }
        return "\0" + (char) country.length() + country + (char) fields[1].length() + fields[1] + fields[3] + "\0"
                + fields[4];
    }

    /** A part of what the country holds: 0 the country, 1 the script, 2 the variant, 3 the extensions. */
    private String part(int which) {
        if (country.isEmpty() || country.charAt(0) != '\0') {
            return which == 0 ? country : "";
        }
        int script = 2 + country.charAt(1);
        int variant = script + 1 + country.charAt(script);
        int extensions = country.lastIndexOf('\0');
        return which == 0 ? country.substring(2, script)
                : which == 1 ? country.substring(script + 1, variant)
                        : which == 2 ? country.substring(variant, extensions) : country.substring(extensions + 1);
    }

    private String extensions() {
        return part(3);
    }

    @Deprecated
    public Locale(String language, String country, String variant) {
        this(made(language, country, variant));
    }

    /**
     * The five locales' constructor too, which takes their parts as they are: the
     * compiler answers foreign() where code outside the library constructs a locale,
     * which only the five are inside it.
     */
    @Deprecated
    public Locale(String language, String country) {
        if (rontolisp.internal.Locales.foreign()) {
            String[] fields = made(language, country, "");
            this.language = fields[0];
            this.country = held(fields);
        }
        else {
            this.language = language;
            this.country = country;
        }
    }

    @Deprecated
    public Locale(String language) {
        this(language, "");
    }

    public static Locale of(String language, String country, String variant) {
        return made(made(language, country, variant));
    }

    public static Locale of(String language, String country) {
        return of(language, country, "");
    }

    public static Locale of(String language) {
        return of(language, "", "");
    }

    public static Locale forLanguageTag(String languageTag) {
        if (languageTag == null) {
            throw new NullPointerException("Cannot invoke \"String.length()\" because \"s\" is null");
        }
        return made(parsed(languageTag, false));
    }

    public static Locale getDefault() {
        return US;
    }

    /** What a default locale is the default of: what a program shows, or its formats. */
    public enum Category {
        DISPLAY, FORMAT
    }

    // one default, whatever the use
    public static Locale getDefault(Category category) {
        if (category == null) {
            throw new NullPointerException();
        }
        return US;
    }

    private static Locale made(String language, String country) {
        return made(new String[] {language, "", country, "", ""});
    }

    /** The locale of these parts and no extension but the one its variant names. */
    static Locale made(String language, String script, String country, String variant) {
        String[] fields = {language, script, country, variant, ""};
        special(fields);
        return made(fields);
    }

    /**
     * The fields of a locale of three parts: the language in lower case and by its new
     * code, the country in upper case, the variant as it is; the two variants that name
     * a calendar and digits get their extension.
     */
    private static String[] made(String language, String country, String variant) {
        if (language == null || country == null || variant == null) {
            throw new NullPointerException();
        }
        language = lower(language);
        language = language.equals("iw") ? "he" : language.equals("ji") ? "yi" : language.equals("in") ? "id" : language;
        country = upper(country);
        String[] fields = {language, "", country, variant, ""};
        special(fields);
        return fields;
    }

    /** The one locale of these fields: one of the five, or the one made first. */
    private static synchronized Locale made(String[] fields) {
        if (fields[1].isEmpty() && fields[3].isEmpty() && fields[4].isEmpty()) {
            String language = fields[0];
            String country = fields[2];
            if (language.isEmpty() && country.isEmpty()) {
                return ROOT;
            }
            if (language.equals("en")) {
                if (country.isEmpty()) {
                    return ENGLISH;
                }
                if (country.equals("US")) {
                    return US;
                }
                if (country.equals("GB")) {
                    return UK;
                }
                if (country.equals("CA")) {
                    return CANADA;
                }
            }
        }
        String key = fields[0].length() + ":" + fields[0] + fields[1] + fields[2].length() + ":" + fields[2]
                + fields[3].length() + ":" + fields[3] + fields[4];
        if (made == null) {
            made = new HashMap<>();
        }
        Locale locale = made.get(key);
        if (locale == null) {
            locale = new Locale(fields);
            made.put(key, locale);
        }
        return locale;
    }

    public String getLanguage() {
        return language;
    }

    public String getScript() {
        return part(1);
    }

    public String getCountry() {
        return rontolisp.internal.Locales.foreign() ? part(0) : country;
    }

    public String getVariant() {
        return part(2);
    }

    public boolean hasExtensions() {
        return !extensions().isEmpty();
    }

    public Locale stripExtensions() {
        return extensions().isEmpty() ? this : made(new String[] {language, part(1), part(0), part(2), ""});
    }

    public String getExtension(char key) {
        if (!alpha(key)) {
            throw new IllegalArgumentException("Ill-formed extension key: " + key);
        }
        return extension(extensions(), lower(key));
    }

    public Set<Character> getExtensionKeys() {
        String extensions = extensions();
        if (extensions.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Character> keys = new TreeSet<>();
        for (int at = 0; at < extensions.length(); at = next(extensions, at)) {
            if (next(extensions, at) == at + 2) {
                keys.add(extensions.charAt(at));
                if (extensions.charAt(at) == 'x') {
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    public Set<String> getUnicodeLocaleAttributes() {
        Set<String> attributes = new TreeSet<>();
        String u = extension(extensions(), 'u');
        for (int at = 0; u != null && at < u.length() && end(u, at) - at > 2; at = next(u, at)) {
            attributes.add(u.substring(at, end(u, at)));
        }
        return Collections.unmodifiableSet(attributes);
    }

    public Set<String> getUnicodeLocaleKeys() {
        Set<String> keys = new TreeSet<>();
        String u = extension(extensions(), 'u');
        for (int at = 0; u != null && at < u.length(); at = next(u, at)) {
            if (end(u, at) - at == 2) {
                keys.add(u.substring(at, at + 2));
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    public String getUnicodeLocaleType(String key) {
        if (key == null) {
            throw new NullPointerException("Cannot invoke \"String.length()\" because \"s\" is null");
        }
        if (key.length() != 2 || !alphanumeric(key)) {
            throw new IllegalArgumentException("Ill-formed Unicode locale key: " + key);
        }
        String u = extension(extensions(), 'u');
        if (u == null) {
            return null;
        }
        key = lower(key);
        for (int at = 0; at < u.length(); at = next(u, at)) {
            if (end(u, at) - at == 2 && u.startsWith(key, at)) {
                int start = next(u, at);
                int stop = start;
                while (stop < u.length() && end(u, stop) - stop != 2) {
                    stop = next(u, stop);
                }
                return start >= stop ? "" : u.substring(start, Math.min(stop, u.length() + 1) - 1);
            }
        }
        return null;
    }

    public String toLanguageTag() {
        if (!rontolisp.internal.Locales.foreign()) {
            return language.isEmpty() ? "und" : country.isEmpty() ? language : language + "-" + country;
        }
        String script = part(1);
        String country = part(0);
        String variant = part(2);
        String extensions = part(3);
        boolean nynorsk = language.equals("no") && country.equals("NO") && variant.equals("NY") && script.isEmpty();
        // what follows the language: the script, the region, the variant's subtags a tag
        // can hold, then the extensions; the variant's next subtags a private use can hold
        // follow lvariant
        StringBuilder tag = new StringBuilder();
        if (script.length() == 4 && alphabetic(script)) {
            tag.append('-').append(script);
        }
        if (region(country)) {
            tag.append('-').append(country);
        }
        String rest = null;
        boolean subtags = true;
        for (int at = 0; !nynorsk && !variant.isEmpty() && at <= variant.length();) {
            int stop = variant.indexOf('_', at);
            String piece = variant.substring(at, stop < 0 ? variant.length() : stop);
            subtags = subtags && variantSubtag(piece);
            if (subtags) {
                tag.append('-').append(piece);
            }
            else if (privateSubtag(piece)) {
                rest = rest == null ? piece : rest + "-" + piece;
            }
            else {
                break;
            }
            at = stop < 0 ? variant.length() + 1 : stop + 1;
        }
        // the extensions but the private use, which comes last
        String own = extensions;
        String privately = null;
        int x = extensions.startsWith("x-") ? 0 : extensions.indexOf("-x-") + 1;
        if (x > 0 || extensions.startsWith("x-")) {
            own = x == 0 ? "" : extensions.substring(0, x - 1);
            privately = extensions.substring(x + 2);
        }
        if (!own.isEmpty()) {
            tag.append('-').append(own);
        }
        String last = privately == null && rest == null ? ""
                : "x" + (privately == null ? "" : "-" + privately) + (rest == null ? "" : "-lvariant-" + rest);
        String head = nynorsk ? "nn" : wellFormedLanguage(language) ? language : "";
        if (head.isEmpty() && tag.length() == 0) {
            return last.isEmpty() ? "und" : last;
        }
        return (head.isEmpty() ? "und" : head) + tag + (last.isEmpty() ? "" : "-" + last);
    }

    public Object clone() {
        return rontolisp.internal.Support.copy(this);
    }

    // a clone is another object of the same locale; the country holds the other parts
    public boolean equals(Object obj) {
        return obj instanceof Locale other && language.equals(other.language) && country.equals(other.country);
    }

    public int hashCode() {
        return language.hashCode() * 31 + country.hashCode();
    }

    public String toString() {
        if (!rontolisp.internal.Locales.foreign()) {
            return country.isEmpty() ? language : language + "_" + country;
        }
        String script = part(1);
        String country = part(0);
        String variant = part(2);
        String extensions = part(3);
        if (language.isEmpty() && country.isEmpty()) {
            return "";
        }
        boolean more = !script.isEmpty() || !extensions.isEmpty();
        StringBuilder text = new StringBuilder(language);
        if (!country.isEmpty() || !variant.isEmpty() || more) {
            text.append('_').append(country);
        }
        if (!variant.isEmpty()) {
            text.append('_').append(variant);
        }
        if (more) {
            text.append("_#").append(script);
            if (!script.isEmpty() && !extensions.isEmpty()) {
                text.append('_');
            }
            text.append(extensions);
        }
        return text.toString();
    }

    // -- the subtags of a tag

    private static boolean alpha(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean alphabetic(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!alpha(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean alphanumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!alpha(s.charAt(i)) && !digit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean numeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!digit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    /** A text with its ASCII letters in lower case: no other letter changes. */
    private static String lower(String s) {
        StringBuilder text = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            text.append(lower(s.charAt(i)));
        }
        return text.toString();
    }

    private static String upper(String s) {
        StringBuilder text = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            text.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return text.toString();
    }

    private static boolean wellFormedLanguage(String s) {
        return s.length() >= 2 && s.length() <= 8 && alphabetic(s);
    }

    private static boolean region(String s) {
        return s.length() == 2 && alphabetic(s) || s.length() == 3 && numeric(s);
    }

    private static boolean privateSubtag(String s) {
        return !s.isEmpty() && s.length() <= 8 && alphanumeric(s);
    }

    private static boolean variantSubtag(String s) {
        return (s.length() >= 5 && s.length() <= 8 || s.length() == 4 && digit(s.charAt(0))) && alphanumeric(s);
    }

    /** Where the subtag starting at at ends. */
    private static int end(String s, int at) {
        int stop = s.indexOf('-', at);
        return stop < 0 ? s.length() : stop;
    }

    /** Where the subtag after the one at at starts, or past the end. */
    private static int next(String s, int at) {
        return end(s, at) + 1;
    }

    /** The subtags after a singleton in a locale's extensions, or null. */
    private static String extension(String extensions, char singleton) {
        for (int at = 0; at < extensions.length(); at = next(extensions, at)) {
            if (end(extensions, at) == at + 1 && extensions.charAt(at) != singleton && extensions.charAt(at) == 'x') {
                return null;
            }
            if (end(extensions, at) == at + 1 && extensions.charAt(at) == singleton) {
                int start = at + 2;
                int stop = start;
                if (singleton != 'x') {
                    while (stop < extensions.length() && end(extensions, stop) != stop + 1) {
                        stop = next(extensions, stop);
                    }
                }
                else {
                    stop = extensions.length() + 1;
                }
                return extensions.substring(start, stop - 1);
            }
        }
        return null;
    }

    // -- a tag read

    /** The preferred value of a grandfathered tag, or null. */
    private static String grandfathered(String tag) {
        String tags = " art-lojban=jbo cel-gaulish=xtg-x-cel-gaulish en-gb-oed=en-GB-x-oed i-ami=ami i-bnn=bnn"
                + " i-default=en-x-i-default i-enochian=und-x-i-enochian i-hak=hak i-klingon=tlh i-lux=lb"
                + " i-mingo=see-x-i-mingo i-navajo=nv i-pwn=pwn i-tao=tao i-tay=tay i-tsu=tsu no-bok=nb no-nyn=nn"
                + " sgn-be-fr=sfb sgn-be-nl=vgt sgn-ch-de=sgg zh-guoyu=cmn zh-hakka=hak zh-min=nan-x-zh-min"
                + " zh-min-nan=nan zh-xiang=hsn ";
        int at = tags.indexOf(" " + lower(tag) + "=");
        if (at < 0) {
            return null;
        }
        int start = at + tag.length() + 2;
        return tags.substring(start, tags.indexOf(' ', start));
    }

    /** The subtag starting at at. */
    private static String subtag(String tag, int at) {
        return tag.substring(at, end(tag, at));
    }

    /**
     * The fields of a tag: as far as it is well-formed, or, strict, all of it or the
     * refusal of the first subtag that is not. A singleton a tag names again is left out.
     */
    private static String[] parsed(String tag, boolean strict) {
        String preferred = grandfathered(tag);
        if (preferred != null) {
            tag = preferred;
        }
        // the fields, and the variant private use names after lvariant
        String[] fields = {"", "", "", "", "", ""};
        String[] slots = new String[128];
        int n = tag.length();
        // where the subtag being read starts; past the end where none is left
        int at = 0;
        String refusal = null;
        int refusedAt = 0;
        boolean ended = false;
        String s = subtag(tag, at);
        if (wellFormedLanguage(s)) {
            // und is the root's language
            fields[0] = lower(s).equals("und") ? "" : lower(s);
            at = next(tag, at);
            // the first of up to three extended languages stands for the language
            for (int i = 0; i < 3 && at <= n; i++) {
                String extended = subtag(tag, at);
                if (extended.length() != 3 || !alphabetic(extended)) {
                    break;
                }
                if (i == 0) {
                    fields[0] = lower(extended);
                }
                at = next(tag, at);
            }
            if (at <= n && subtag(tag, at).length() == 4 && alphabetic(subtag(tag, at))) {
                s = subtag(tag, at);
                fields[1] = upper(s.substring(0, 1)) + lower(s.substring(1));
                at = next(tag, at);
            }
            if (at <= n && region(subtag(tag, at))) {
                fields[2] = upper(subtag(tag, at));
                at = next(tag, at);
            }
            while (at <= n && variantSubtag(subtag(tag, at))) {
                fields[3] = fields[3].isEmpty() ? subtag(tag, at) : fields[3] + "_" + subtag(tag, at);
                at = next(tag, at);
            }
            while (at <= n && subtag(tag, at).length() == 1 && alpha(tag.charAt(at)) && lower(tag.charAt(at)) != 'x') {
                char singleton = lower(tag.charAt(at));
                int start = at;
                at = next(tag, at);
                StringBuilder content = new StringBuilder();
                while (at <= n) {
                    String piece = subtag(tag, at);
                    if (piece.length() < 2 || piece.length() > 8 || !alphanumeric(piece)) {
                        break;
                    }
                    content.append(content.length() == 0 ? "" : "-").append(lower(piece));
                    at = next(tag, at);
                }
                if (content.length() == 0) {
                    refusal = "Incomplete extension '" + tag.charAt(start) + "'";
                    refusedAt = start;
                    ended = true;
                    break;
                }
                // a singleton named again is read and left out
                if (slots[singleton] == null) {
                    slots[singleton] = singleton == 'u' ? unicode(content.toString()) : content.toString();
                }
            }
        }
        String lvariant = null;
        if (!ended && at <= n && subtag(tag, at).length() == 1 && lower(tag.charAt(at)) == 'x') {
            // the private use: what follows lvariant is the variant
            int start = at;
            at = next(tag, at);
            StringBuilder content = new StringBuilder();
            while (at <= n && privateSubtag(subtag(tag, at))) {
                String piece = subtag(tag, at);
                at = next(tag, at);
                if (lvariant == null && lower(piece).equals("lvariant") && at <= n && privateSubtag(subtag(tag, at))) {
                    lvariant = "";
                }
                else if (lvariant != null) {
                    lvariant = lvariant.isEmpty() ? piece : lvariant + "_" + piece;
                }
                else {
                    content.append(content.length() == 0 ? "" : "-").append(lower(piece));
                }
            }
            if (content.length() > 0) {
                slots['x'] = content.toString();
            }
            else if (lvariant == null) {
                refusal = "Incomplete privateuse";
                refusedAt = start;
                ended = true;
            }
        }
        if (strict) {
            if (refusal == null && !ended && at <= n) {
                s = subtag(tag, at);
                refusal = s.isEmpty() ? "Empty subtag" : "Invalid subtag: " + s;
                refusedAt = at;
            }
            if (refusal != null) {
                throw new IllformedLocaleException(refusal, refusedAt);
            }
        }
        fields[0] = made(fields[0], "", "")[0];
        if (lvariant != null) {
            fields[3] = fields[3].isEmpty() ? lvariant : fields[3] + "_" + lvariant;
            fields[5] = lvariant;
        }
        fields[4] = joined(slots);
        special(fields);
        return fields;
    }

    /** The extension a variant names: a calendar of ja_JP_JP, digits of th_TH_TH. */
    private static void special(String[] fields) {
        if (fields[4].isEmpty() && fields[1].isEmpty()) {
            String parts = fields[0] + "_" + fields[2] + "_" + fields[3];
            fields[4] = parts.equals("ja_JP_JP") ? "u-ca-japanese" : parts.equals("th_TH_TH") ? "u-nu-thai" : "";
        }
    }

    /** The extensions of their singletons, in order, the private use last. */
    private static String joined(String[] slots) {
        StringBuilder text = new StringBuilder();
        for (char c = '0'; c <= 'z'; c++) {
            if (c != 'x' && slots[c] != null) {
                text.append(text.length() == 0 ? "" : "-").append(c).append('-').append(slots[c]);
            }
        }
        if (slots['x'] != null) {
            text.append(text.length() == 0 ? "" : "-").append("x-").append(slots['x']);
        }
        return text.toString();
    }

    /**
     * A Unicode extension as a locale holds it: its attributes in order, then its
     * keywords by key, the first of a key kept.
     */
    private static String unicode(String content) {
        int n = content.length();
        String[] attributes = new String[n];
        String[] keywords = new String[n];
        int a = 0;
        int k = 0;
        int at = 0;
        while (at < n && end(content, at) - at > 2) {
            a = sorted(attributes, a, content.substring(at, end(content, at)), 0);
            at = next(content, at);
        }
        while (at < n) {
            int stop = next(content, at);
            while (stop < n && end(content, stop) - stop != 2) {
                stop = next(content, stop);
            }
            k = sorted(keywords, k, content.substring(at, Math.min(stop, n + 1) - 1), 2);
            at = stop;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < a + k; i++) {
            text.append(i == 0 ? "" : "-").append(i < a ? attributes[i] : keywords[i - a]);
        }
        return text.toString();
    }

    /**
     * Puts a text among the first count of an array in order, unless one is there whose
     * first length characters (all of it where length is 0) are its own: the new count.
     */
    private static int sorted(String[] texts, int count, String text, int length) {
        String key = length == 0 ? text : text.substring(0, length);
        int at = count;
        for (int i = 0; i < count; i++) {
            String held = length == 0 ? texts[i] : texts[i].substring(0, length);
            if (held.equals(key)) {
                return count;
            }
            if (at == count && held.compareTo(key) > 0) {
                at = i;
            }
        }
        for (int i = count; i > at; i--) {
            texts[i] = texts[i - 1];
        }
        texts[at] = text;
        return count + 1;
    }

    // -- the builder

    public static final class Builder {

        private String language = "";

        private String script = "";

        private String region = "";

        private String variant = "";

        /** The extensions by their singletons; the Unicode one's attributes and keywords apart. */
        private String[] slots = new String[128];

        private List<String> attributes = new ArrayList<>();

        private List<String> keywords = new ArrayList<>();

        public Builder() {
        }

        public Builder setLocale(Locale locale) {
            if (locale == null) {
                throw new NullPointerException("Cannot read field \"baseLocale\" because \"locale\" is null");
            }
            String language = locale.language;
            String variant = locale.part(2);
            String country = locale.part(0);
            if (language.equals("no") && country.equals("NO") && variant.equals("NY")) {
                language = "nn";
                variant = "";
            }
            else if (!locale.extensions().isEmpty() && (variant.equals("JP") && language.equals("ja")
                    && country.equals("JP") || variant.equals("TH") && language.equals("th")
                            && country.equals("TH"))) {
                variant = "";
            }
            if (!language.isEmpty() && !wellFormedLanguage(language)) {
                throw new IllformedLocaleException("Ill-formed language: " + language, 0);
            }
            if (!country.isEmpty() && !region(country)) {
                throw new IllformedLocaleException("Ill-formed region: " + country, 0);
            }
            checkVariant(variant);
            Builder taken = new Builder();
            taken.language = language;
            taken.script = locale.part(1);
            taken.region = country;
            taken.variant = variant;
            taken.extensions(locale.extensions());
            take(taken);
            return this;
        }

        public Builder setLanguageTag(String languageTag) {
            if (languageTag == null) {
                throw new NullPointerException("Cannot invoke \"String.length()\" because \"s\" is null");
            }
            String[] fields = parsed(languageTag, true);
            Builder taken = new Builder();
            taken.language = fields[0];
            taken.script = fields[1];
            taken.region = fields[2];
            taken.extensions(fields[4]);
            // the variant lvariant names stays in the private use until the locale is built
            String lvariant = fields[5];
            taken.variant = lvariant.isEmpty() ? fields[3]
                    : fields[3].substring(0, Math.max(0, fields[3].length() - lvariant.length() - 1));
            if (!lvariant.isEmpty()) {
                String privately = taken.slots['x'];
                taken.slots['x'] = (privately == null ? "" : privately + "-") + "lvariant-" + lvariant.replace('_', '-');
            }
            take(taken);
            return this;
        }

        public Builder setLanguage(String language) {
            if (language == null || language.isEmpty()) {
                this.language = "";
                return this;
            }
            if (!wellFormedLanguage(language)) {
                throw new IllformedLocaleException("Ill-formed language: " + language, 0);
            }
            this.language = made(language, "", "")[0];
            return this;
        }

        public Builder setScript(String script) {
            if (script == null || script.isEmpty()) {
                this.script = "";
                return this;
            }
            if (script.length() != 4 || !alphabetic(script)) {
                throw new IllformedLocaleException("Ill-formed script: " + script, 0);
            }
            this.script = upper(script.substring(0, 1)) + lower(script.substring(1));
            return this;
        }

        public Builder setRegion(String region) {
            if (region == null || region.isEmpty()) {
                this.region = "";
                return this;
            }
            if (!region(region)) {
                throw new IllformedLocaleException("Ill-formed region: " + region, 0);
            }
            this.region = upper(region);
            return this;
        }

        public Builder setVariant(String variant) {
            if (variant == null || variant.isEmpty()) {
                this.variant = "";
                return this;
            }
            checkVariant(variant);
            this.variant = variant.replace('-', '_');
            return this;
        }

        /**
         * A variant of subtags a tag can hold, by an underscore or a hyphen, refused at the
         * first that is not one.
         */
        private static void checkVariant(String variant) {
            int at = 0;
            while (!variant.isEmpty()) {
                int stop = at;
                while (stop < variant.length() && variant.charAt(stop) != '_' && variant.charAt(stop) != '-') {
                    stop++;
                }
                if (!variantSubtag(variant.substring(at, stop))) {
                    throw new IllformedLocaleException("Ill-formed variant: " + variant, at);
                }
                if (stop == variant.length()) {
                    return;
                }
                at = stop + 1;
            }
        }

        public Builder setExtension(char key, String value) {
            if (!alpha(key)) {
                throw new IllformedLocaleException("Ill-formed extension key: " + key, 0);
            }
            char singleton = lower(key);
            if (value == null || value.isEmpty()) {
                if (singleton == 'u') {
                    attributes.clear();
                    keywords.clear();
                }
                slots[singleton] = null;
                return this;
            }
            String text = value.replace('_', '-');
            int at = 0;
            while (true) {
                int stop = end(text, at);
                String piece = text.substring(at, stop);
                if (piece.length() < (singleton == 'x' ? 1 : 2) || piece.length() > 8 || !alphanumeric(piece)) {
                    throw new IllformedLocaleException("Ill-formed extension value: " + piece, at);
                }
                if (stop == text.length()) {
                    break;
                }
                at = stop + 1;
            }
            // a private use keeps the case of what follows lvariant, the variant
            int variantAt = singleton == 'x' ? ("-" + lower(text) + "-").indexOf("-lvariant-") : -1;
            text = variantAt < 0 ? lower(text) : lower(text.substring(0, variantAt + 8)) + text.substring(variantAt + 8);
            if (singleton == 'u') {
                unicodeParts(unicode(text));
            }
            else {
                slots[singleton] = text;
            }
            return this;
        }

        public Builder setUnicodeLocaleKeyword(String key, String type) {
            if (key == null) {
                throw new NullPointerException("Cannot invoke \"String.length()\" because \"s\" is null");
            }
            if (key.length() != 2 || !alphanumeric(key)) {
                throw new IllformedLocaleException("Ill-formed Unicode locale keyword key: " + key, 0);
            }
            key = lower(key);
            if (type != null && !type.isEmpty()) {
                int at = 0;
                while (true) {
                    int stop = at;
                    while (stop < type.length() && type.charAt(stop) != '-' && type.charAt(stop) != '_') {
                        stop++;
                    }
                    String piece = type.substring(at, stop);
                    if (piece.length() < 3 || piece.length() > 8 || !alphanumeric(piece)) {
                        throw new IllformedLocaleException("Ill-formed Unicode locale keyword type: " + type, at);
                    }
                    if (stop == type.length()) {
                        break;
                    }
                    at = stop + 1;
                }
            }
            for (int i = 0; i < keywords.size(); i++) {
                if (keywords.get(i).startsWith(key)
                        && (keywords.get(i).length() == 2 || keywords.get(i).charAt(2) == '-')) {
                    keywords.remove(i);
                    break;
                }
            }
            if (type != null) {
                keywords.add(type.isEmpty() ? key : key + "-" + lower(type));
            }
            return this;
        }

        public Builder addUnicodeLocaleAttribute(String attribute) {
            if (attribute == null) {
                throw new NullPointerException("Cannot invoke \"String.length()\" because \"s\" is null");
            }
            String checked = attribute(attribute);
            if (!attributes.contains(checked)) {
                attributes.add(checked);
            }
            return this;
        }

        public Builder removeUnicodeLocaleAttribute(String attribute) {
            if (attribute == null) {
                throw new NullPointerException();
            }
            attributes.remove(attribute(attribute));
            return this;
        }

        private static String attribute(String attribute) {
            if (attribute.length() < 3 || attribute.length() > 8 || !alphanumeric(attribute)) {
                throw new IllformedLocaleException("Ill-formed Unicode locale attribute: " + attribute, 0);
            }
            return lower(attribute);
        }

        public Builder clear() {
            language = "";
            script = "";
            region = "";
            variant = "";
            clearExtensions();
            return this;
        }

        public Builder clearExtensions() {
            slots = new String[128];
            attributes = new ArrayList<>();
            keywords = new ArrayList<>();
            return this;
        }

        public Locale build() {
            String[] fields = {language, script, region, variant, ""};
            String[] all = slots.clone();
            if (!attributes.isEmpty() || !keywords.isEmpty()) {
                List<String> parts = new ArrayList<>(attributes);
                parts.addAll(keywords);
                all['u'] = unicode(String.join("-", parts));
            }
            String privately = all['x'];
            if (privately != null) {
                int at = ("-" + lower(privately) + "-").indexOf("-lvariant-");
                if (at >= 0 && at + 9 < privately.length()) {
                    String rest = privately.substring(at + 9);
                    fields[3] = (fields[3].isEmpty() ? "" : fields[3] + "_") + rest.replace('-', '_');
                    all['x'] = at == 0 ? null : privately.substring(0, at - 1);
                }
            }
            fields[4] = joined(all);
            special(fields);
            return made(fields);
        }

        /** Takes the extensions of a locale's text. */
        private void extensions(String extensions) {
            for (int at = 0; at < extensions.length(); at = next(extensions, at)) {
                if (end(extensions, at) == at + 1) {
                    char singleton = extensions.charAt(at);
                    String content = extension(extensions, singleton);
                    if (singleton == 'u') {
                        unicodeParts(content);
                    }
                    else {
                        slots[singleton] = content;
                    }
                    if (singleton == 'x') {
                        return;
                    }
                }
            }
        }

        /** The attributes and the keywords of a Unicode extension, in place of the ones held. */
        private void unicodeParts(String content) {
            attributes = new ArrayList<>();
            keywords = new ArrayList<>();
            int at = 0;
            while (at < content.length() && end(content, at) - at > 2) {
                attributes.add(content.substring(at, end(content, at)));
                at = next(content, at);
            }
            while (at < content.length()) {
                int stop = next(content, at);
                while (stop < content.length() && end(content, stop) - stop != 2) {
                    stop = next(content, stop);
                }
                keywords.add(content.substring(at, Math.min(stop - 1, content.length())));
                at = stop;
            }
        }

        private void take(Builder taken) {
            language = taken.language;
            script = taken.script;
            region = taken.region;
            variant = taken.variant;
            slots = taken.slots;
            attributes = taken.attributes;
            keywords = taken.keywords;
        }
    }
}
