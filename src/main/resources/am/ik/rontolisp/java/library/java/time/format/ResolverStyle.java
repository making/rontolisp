// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.format;

// How the fields read from a text are made a date and a time: each in its range and the
// date one that exists, each in its range, or added up whatever their values.
public enum ResolverStyle {

    STRICT, SMART, LENIENT
}
