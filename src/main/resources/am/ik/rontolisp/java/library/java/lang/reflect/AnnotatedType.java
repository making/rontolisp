// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

import java.lang.annotation.Annotation;

// A use of a type with the type annotations written on it: a field's type, a method's
// return type, a parameter's, a superclass, a bound ... Its annotations are its own: none is
// inherited.
public interface AnnotatedType extends AnnotatedElement {

    // the type a member type is a member of, with the annotations written on it; none of a
    // top-level, a local or an anonymous class, of a primitive type, an array, a type
    // variable or a wildcard
    default AnnotatedType getAnnotatedOwnerType() {
        return null;
    }

    Type getType();

    <T extends Annotation> T getAnnotation(Class<T> annotationClass);

    Annotation[] getAnnotations();

    Annotation[] getDeclaredAnnotations();
}
