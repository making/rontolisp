// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.math;

// How a number that does not fit is brought to one that does. The order is the one the
// older int constants of BigDecimal count in.
public enum RoundingMode {

    UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN, UNNECESSARY;

    public static RoundingMode valueOf(int rm) {
        RoundingMode[] modes = values();
        if (rm < 0 || rm >= modes.length) {
            throw new IllegalArgumentException("argument out of range");
        }
        return modes[rm];
    }

}
