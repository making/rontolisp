// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The two eras of the ISO calendar: before the year 1 (0) and from it (1).
package java.time.chrono;

import java.time.DateTimeException;

public enum IsoEra implements Era {

    BCE, CE;

    public static IsoEra of(int isoEra) {
        if (isoEra == 0) {
            return BCE;
        }
        if (isoEra == 1) {
            return CE;
        }
        throw new DateTimeException("Invalid era: " + isoEra);
    }

    public int getValue() {
        return ordinal();
    }
}
