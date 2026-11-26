// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

import java.lang.annotation.Annotation;

// A parameter of a method or a constructor, at its place among the executable's (an inner
// class's instance around it and an enum's name and ordinal included, as getParameterTypes()
// lists them). Its name is the one written where a compiler keeps it -- a record's canonical
// constructor's -- else arg and its place, as javac with no -parameters leaves a class file;
// its modifiers what the compiler records of it (an implicit or a synthetic one's).
public final class Parameter implements AnnotatedElement {

    // the lowering's bits of a parameter's modifiers
    static final int MANDATED = 0x8000;

    static final int SYNTHETIC = 0x1000;

    private final Executable executable;

    private final int index;

    // the name kept, or null
    private final String name;

    private final int modifiers;

    Parameter(Executable executable, int index, String name, int modifiers) {
        this.executable = executable;
        this.index = index;
        this.name = name;
        this.modifiers = modifiers;
    }

    public String getName() {
        return name != null ? name : "arg" + index;
    }

    public boolean isNamePresent() {
        return name != null;
    }

    public int getModifiers() {
        return modifiers;
    }

    public Executable getDeclaringExecutable() {
        return executable;
    }

    public Class<?> getType() {
        return executable.getParameterTypes()[index];
    }

    // the generic type its declaration names; the class of one a compiler adds
    public Type getParameterizedType() {
        Type[] generic = executable.getGenericParameterTypes();
        Class<?>[] erased = executable.getParameterTypes();
        if (generic.length == erased.length) {
            return generic[index];
        }
        int first = erased.length - generic.length;
        return index < first ? erased[index] : generic[index - first];
    }

    public boolean isImplicit() {
        return (modifiers & MANDATED) != 0;
    }

    public boolean isSynthetic() {
        return (modifiers & SYNTHETIC) != 0;
    }

    public boolean isVarArgs() {
        return executable.isVarArgs() && index == executable.getParameterCount() - 1;
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return getDeclaredAnnotation(annotationClass);
    }

    public Annotation[] getAnnotations() {
        return getDeclaredAnnotations();
    }

    public Annotation[] getDeclaredAnnotations() {
        return executable.getParameterAnnotations()[index];
    }

    public boolean equals(Object other) {
        return other instanceof Parameter that && that.executable.equals(executable) && that.index == index;
    }

    public int hashCode() {
        return executable.hashCode() ^ index;
    }

    public String toString() {
        StringBuilder written = new StringBuilder();
        if (modifiers != 0) {
            written.append(Modifier.toString(modifiers)).append(' ');
        }
        Type type = getParameterizedType();
        if (isVarArgs()) {
            Type element = type instanceof GenericArrayType array ? array.getGenericComponentType()
                    : ((Class<?>) type).getComponentType();
            written.append(element.getTypeName()).append("...");
        }
        else {
            written.append(type.getTypeName());
        }
        return written.append(' ').append(getName()).toString();
    }
}
