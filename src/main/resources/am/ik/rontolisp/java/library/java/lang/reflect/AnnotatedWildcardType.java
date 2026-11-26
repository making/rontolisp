// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A use of a wildcard: its bounds' uses, Object's above where it writes none.
public interface AnnotatedWildcardType extends AnnotatedType {

    AnnotatedType[] getAnnotatedLowerBounds();

    AnnotatedType[] getAnnotatedUpperBounds();

    AnnotatedType getAnnotatedOwnerType();
}
