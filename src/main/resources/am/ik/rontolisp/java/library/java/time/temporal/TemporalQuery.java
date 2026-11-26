// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

// A question asked of a date or a time.
@FunctionalInterface
public interface TemporalQuery<R> {

    R queryFrom(TemporalAccessor temporal);
}
