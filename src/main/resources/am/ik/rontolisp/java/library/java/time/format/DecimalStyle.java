// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The characters a formatter writes and reads numbers with: the zero digit (the nine
// after it are the others), the two signs and the decimal separator. Every locale Locale
// has writes the standard ones, as a JDK 25 answers for each; the hash code is the four
// characters added up, a JDK's answer too.
package java.time.format;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class DecimalStyle {

    public static final DecimalStyle STANDARD = new DecimalStyle('0', '+', '-', '.');

    private final char zeroDigit;

    private final char positiveSign;

    private final char negativeSign;

    private final char decimalSeparator;

    private DecimalStyle(char zeroDigit, char positiveSign, char negativeSign, char decimalSeparator) {
        this.zeroDigit = zeroDigit;
        this.positiveSign = positiveSign;
        this.negativeSign = negativeSign;
        this.decimalSeparator = decimalSeparator;
    }

    /** The locales Locale has, as a new set. */
    public static Set<Locale> getAvailableLocales() {
        Set<Locale> locales = new HashSet<>();
        locales.add(Locale.ROOT);
        locales.add(Locale.ENGLISH);
        locales.add(Locale.US);
        locales.add(Locale.UK);
        locales.add(Locale.CANADA);
        return locales;
    }

    public static DecimalStyle ofDefaultLocale() {
        return of(Locale.getDefault());
    }

    public static DecimalStyle of(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        return STANDARD;
    }

    public char getZeroDigit() {
        return zeroDigit;
    }

    public DecimalStyle withZeroDigit(char zeroDigit) {
        return zeroDigit == this.zeroDigit ? this
                : new DecimalStyle(zeroDigit, positiveSign, negativeSign, decimalSeparator);
    }

    public char getPositiveSign() {
        return positiveSign;
    }

    public DecimalStyle withPositiveSign(char positiveSign) {
        return positiveSign == this.positiveSign ? this
                : new DecimalStyle(zeroDigit, positiveSign, negativeSign, decimalSeparator);
    }

    public char getNegativeSign() {
        return negativeSign;
    }

    public DecimalStyle withNegativeSign(char negativeSign) {
        return negativeSign == this.negativeSign ? this
                : new DecimalStyle(zeroDigit, positiveSign, negativeSign, decimalSeparator);
    }

    public char getDecimalSeparator() {
        return decimalSeparator;
    }

    public DecimalStyle withDecimalSeparator(char decimalSeparator) {
        return decimalSeparator == this.decimalSeparator ? this
                : new DecimalStyle(zeroDigit, positiveSign, negativeSign, decimalSeparator);
    }

    public boolean equals(Object other) {
        return other instanceof DecimalStyle that && that.zeroDigit == zeroDigit && that.positiveSign == positiveSign
                && that.negativeSign == negativeSign && that.decimalSeparator == decimalSeparator;
    }

    public int hashCode() {
        return zeroDigit + positiveSign + negativeSign + decimalSeparator;
    }

    public String toString() {
        return "DecimalStyle[" + zeroDigit + positiveSign + negativeSign + decimalSeparator + "]";
    }
}
