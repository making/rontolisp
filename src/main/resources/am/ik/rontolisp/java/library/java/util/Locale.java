// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The locales whose numbers and letter cases are the ones this library writes: the
// root and the English ones. No other can be made.
public final class Locale {

    public static final Locale ROOT = new Locale("", "");

    public static final Locale ENGLISH = new Locale("en", "");

    public static final Locale US = new Locale("en", "US");

    public static final Locale UK = new Locale("en", "GB");

    public static final Locale CANADA = new Locale("en", "CA");

    private final String language;

    private final String country;

    private Locale(String language, String country) {
        this.language = language;
        this.country = country;
    }

    public static Locale getDefault() {
        return US;
    }

    public String getLanguage() {
        return language;
    }

    public String getCountry() {
        return country;
    }

    public String toLanguageTag() {
        return language.isEmpty() ? "und" : country.isEmpty() ? language : language + "-" + country;
    }

    public String toString() {
        return country.isEmpty() ? language : language + "_" + country;
    }
}
