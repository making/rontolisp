// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// What writes itself into a Formatter: %s and %S of an instance call formatTo with the
// flags of FormattableFlags, the width and the precision (-1 where none is given).
public interface Formattable {

    void formatTo(Formatter formatter, int flags, int width, int precision);
}
