// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

import java.lang.annotation.Annotation;

// What carries annotations a program reads where it runs: a class, a member, a parameter, a
// record's component, a package. Its own are the ones written on it; a class's are with
// those of an inherited type a class above it carries.
public interface AnnotatedElement {

    <T extends Annotation> T getAnnotation(Class<T> annotationClass);

    default boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
        return getAnnotation(annotationClass) != null;
    }

    Annotation[] getAnnotations();

    default <T extends Annotation> T[] getAnnotationsByType(Class<T> annotationClass) {
        return getDeclaredAnnotationsByType(annotationClass);
    }

    @SuppressWarnings("unchecked")
    default <T extends Annotation> T getDeclaredAnnotation(Class<T> annotationClass) {
        if (annotationClass == null) {
            throw new NullPointerException();
        }
        for (Annotation each : getDeclaredAnnotations()) {
            if (each.annotationType() == annotationClass) {
                return (T) each;
            }
        }
        return null;
    }

    // of a repeatable type, the ones its container holds too, where the container stands
    default <T extends Annotation> T[] getDeclaredAnnotationsByType(Class<T> annotationClass) {
        return Types.byType(getDeclaredAnnotations(), annotationClass);
    }

    Annotation[] getDeclaredAnnotations();
}
