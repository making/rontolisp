// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.Comparator;

// String.CASE_INSENSITIVE_ORDER: one object, made where a program first reads the field.
public final class CaseInsensitive {

    private CaseInsensitive() {
    }

    private static final Comparator<String> ORDER = (String a, String b) -> a.compareToIgnoreCase(b);

    public static Comparator<String> order() {
        return ORDER;
    }
}
