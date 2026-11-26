// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A wildcard type argument: ?, ? extends T, ? super T.
public interface WildcardType extends Type {

    // Object where it names none
    Type[] getUpperBounds();

    Type[] getLowerBounds();
}
