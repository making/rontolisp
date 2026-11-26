// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A use of a class with type arguments: each argument's use.
public interface AnnotatedParameterizedType extends AnnotatedType {

    AnnotatedType[] getAnnotatedActualTypeArguments();

    AnnotatedType getAnnotatedOwnerType();
}
