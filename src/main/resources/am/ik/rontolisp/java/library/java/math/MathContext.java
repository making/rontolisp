// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.math;

import java.io.Serializable;

// How many digits an operation keeps, none meaning all of them, and how it rounds to
// them. The words of a refusal are a JDK's.
public final class MathContext implements Serializable {

    public static final MathContext UNLIMITED = new MathContext(0, RoundingMode.HALF_UP);

    public static final MathContext DECIMAL32 = new MathContext(7, RoundingMode.HALF_EVEN);

    public static final MathContext DECIMAL64 = new MathContext(16, RoundingMode.HALF_EVEN);

    public static final MathContext DECIMAL128 = new MathContext(34, RoundingMode.HALF_EVEN);

    private static final String PRECISION = "precision=";

    private static final String MODE = "roundingMode=";

    private final int precision;

    private final RoundingMode roundingMode;

    public MathContext(int setPrecision) {
        this(setPrecision, RoundingMode.HALF_UP);
    }

    public MathContext(int setPrecision, RoundingMode setRoundingMode) {
        if (setPrecision < 0) {
            throw new IllegalArgumentException("Digits < 0");
        }
        if (setRoundingMode == null) {
            throw new NullPointerException("null RoundingMode");
        }
        this.precision = setPrecision;
        this.roundingMode = setRoundingMode;
    }

    /** Reads what toString writes. */
    public MathContext(String val) {
        int space = val.indexOf(' ');
        if (!val.startsWith(PRECISION) || space < 0 || !val.startsWith(MODE, space + 1)) {
            throw new IllegalArgumentException("bad string format");
        }
        int digits;
        RoundingMode mode;
        try {
            digits = Integer.parseInt(val.substring(PRECISION.length(), space));
            mode = RoundingMode.valueOf(val.substring(space + 1 + MODE.length()));
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("bad string format");
        }
        if (digits < 0) {
            throw new IllegalArgumentException("Digits < 0");
        }
        this.precision = digits;
        this.roundingMode = mode;
    }

    public int getPrecision() {
        return this.precision;
    }

    public RoundingMode getRoundingMode() {
        return this.roundingMode;
    }

    @Override
    public boolean equals(Object x) {
        return x instanceof MathContext other && other.precision == this.precision
                && other.roundingMode == this.roundingMode;
    }

    // The precision plus a multiple of the mode's ordinal, so two contexts of one mode differ
    // by their precisions' difference (m35's MathContextHash probe: a JDK's does) and equal
    // contexts agree. The multiple, 31, is a choice made here; no JDK value is meant.
    @Override
    public int hashCode() {
        return this.precision + 31 * this.roundingMode.ordinal();
    }

    @Override
    public String toString() {
        return PRECISION + this.precision + " " + MODE + this.roundingMode;
    }

}
