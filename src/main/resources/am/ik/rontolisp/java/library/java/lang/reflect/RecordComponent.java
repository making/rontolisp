// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

import java.lang.annotation.Annotation;

// A component of a record, as Class.getRecordComponents() answers it: its name, its type
// and the generic one, the signature a class file holds of a generic one, its accessor and
// the annotations written on it that a record component may carry. Each is a new object,
// equal to itself alone.
public final class RecordComponent implements AnnotatedElement {

    private final Class<?> declaring;

    private final String name;

    private final Class<?> type;

    // the generic signature, or null where its type is no generic one
    private final String signature;

    private RecordComponent(Class<?> declaring, String name, Class<?> type, String signature) {
        this.declaring = declaring;
        this.name = name;
        this.type = type;
        this.signature = signature;
    }

    // what the lowering makes of each component of a record's
    static RecordComponent made(Class<?> declaring, String name, Class<?> type, String signature) {
        return new RecordComponent(declaring, name, type, signature);
    }

    public String getName() {
        return name;
    }

    public Class<?> getType() {
        return type;
    }

    public String getGenericSignature() {
        return signature;
    }

    public Type getGenericType() {
        Object made = Types.generic0(declaring, name, 1, declaring);
        return made != null ? (Type) made : type;
    }

    // its type as the declaration names it, with the type annotations written in it
    public AnnotatedType getAnnotatedType() {
        return rontolisp.internal.AnnotatedTypes.of(getGenericType(),
                rontolisp.internal.AnnotatedTypes.annotated0(declaring, name, 1));
    }

    // the record's method answering the component
    public Method getAccessor() {
        try {
            return declaring.getDeclaredMethod(name);
        }
        catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public Class<?> getDeclaringRecord() {
        return declaring;
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return getDeclaredAnnotation(annotationClass);
    }

    public Annotation[] getAnnotations() {
        return getDeclaredAnnotations();
    }

    public Annotation[] getDeclaredAnnotations() {
        return AccessibleObject.annotations(AccessibleObject.annotations0(declaring, name, 3));
    }

    public String toString() {
        return type.getTypeName() + " " + name;
    }
}
