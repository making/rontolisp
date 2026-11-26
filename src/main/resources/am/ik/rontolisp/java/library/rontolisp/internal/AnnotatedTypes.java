// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedTypeVariable;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Objects;

// The uses of types reflection answers (Field.getAnnotatedType,
// Method.getAnnotatedReturnType ..): each made of the type a declaration names -- its
// generic type -- and of the type annotations written in it, which the lowering answers by
// the path from the use asked to where each stands: C the component of an array, O the
// type a member type is a member of, A<n>; the n-th type argument, U<n>; and L<n>; a
// wildcard's upper and lower bounds. A use is made anew each time it is asked, and its
// parts each time they are; two are equal where their types, their annotations and their
// parts are, as a JDK's answers to probes show; the texts are a JDK's.
public final class AnnotatedTypes {

    private AnnotatedTypes() {
    }

    // what the lowering answers of a declaration of a class (member null), of a method (its
    // place among the methods getDeclaredMethods() lists), a constructor (-1 less its
    // place) or a field (its name): 1 the use of its generic type (a class's superclass, a
    // method's or a constructor's return type, a field's type), 2 of each of its declared
    // parameters (a class's interfaces), 3 of each type it throws, 4 of its receiver, 10 and
    // on of each bound of a type parameter -- the paths and the annotations written there,
    // a path followed by its annotations, null for none --; of a class, 7 the class a member
    // class is declared in and 8 whether it is an inner one (non-null), null of any other
    public static native Object annotated0(Class<?> holder, Object member, int what);

    // the class a member class is declared in, or null
    public static Class<?> owner(Class<?> type) {
        return type.isArray() || type.isPrimitive() ? null : (Class<?>) annotated0(type, null, 7);
    }

    // whether a class is an inner member class: an instance holds one of the class it is
    // declared in
    public static boolean inner(Class<?> type) {
        return owner(type) != null && annotated0(type, null, 8) != null;
    }

    // the use of a type, with the annotations the lowering answers of it
    public static AnnotatedType of(Type type, Object placed) {
        return made(type, "", (Object[]) placed);
    }

    // the uses of types, the first of which no entry of placed answers, each with its own
    // entry's annotations
    public static AnnotatedType[] all(Type[] types, Object placed, int first) {
        Object[] each = (Object[]) placed;
        AnnotatedType[] made = new AnnotatedType[types.length];
        for (int index = 0; index < made.length; index++) {
            int at = index - first;
            made[index] = of(types[index], each == null || at < 0 || at >= each.length ? null : each[at]);
        }
        return made;
    }

    static AnnotatedType made(Type type, String path, Object[] placed) {
        Annotation[] annotations = at(placed, path);
        if (type instanceof ParameterizedType parameterized) {
            return new Parameterized(parameterized, path, placed, annotations);
        }
        if (type instanceof GenericArrayType || type instanceof Class<?> array && array.isArray()) {
            return new Array(type, path, placed, annotations);
        }
        if (type instanceof TypeVariable<?> variable) {
            return new Variable(variable, path, placed, annotations);
        }
        if (type instanceof WildcardType wildcard) {
            return new Wildcard(wildcard, path, placed, annotations);
        }
        return new Plain(type, path, placed, annotations);
    }

    // the annotations written at a path, none where none is
    static Annotation[] at(Object[] placed, String path) {
        for (int index = 0; placed != null && index < placed.length; index += 2) {
            if (path.equals(placed[index])) {
                return Annotations.listed(placed[index + 1]);
            }
        }
        return new Annotation[0];
    }

    static AnnotatedType[] made(Type[] types, String path, String step, Object[] placed) {
        AnnotatedType[] made = new AnnotatedType[types.length];
        for (int index = 0; index < made.length; index++) {
            made[index] = made(types[index], path + step + index + ";", placed);
        }
        return made;
    }

    // a use of a class naming no type argument, of a primitive type, of void
    static class Plain implements AnnotatedType {

        final Type type;

        final String path;

        final Object[] placed;

        final Annotation[] annotations;

        Plain(Type type, String path, Object[] placed, Annotation[] annotations) {
            this.type = type;
            this.path = path;
            this.placed = placed;
            this.annotations = annotations;
        }

        public Type getType() {
            return type;
        }

        public AnnotatedType getAnnotatedOwnerType() {
            Class<?> owner = type instanceof Class<?> named ? owner(named) : null;
            return owner == null ? null : made(owner, path + "O", placed);
        }

        @SuppressWarnings("unchecked")
        public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
            for (Annotation each : annotations) {
                if (each.annotationType() == annotationClass) {
                    return (T) each;
                }
            }
            return null;
        }

        public Annotation[] getAnnotations() {
            return annotations.clone();
        }

        public Annotation[] getDeclaredAnnotations() {
            return annotations.clone();
        }

        // the parts beside the type, the annotations and the owner two equal uses have
        // equal: a kind's own
        boolean same(AnnotatedType other) {
            return true;
        }

        public boolean equals(Object other) {
            return other instanceof AnnotatedType that && that.getClass() == getClass() && type.equals(that.getType())
                    && Arrays.equals(annotations, that.getAnnotations())
                    && Objects.equals(getAnnotatedOwnerType(), that.getAnnotatedOwnerType()) && same(that);
        }

        public int hashCode() {
            return type.hashCode() ^ Arrays.hashCode(annotations) ^ Objects.hashCode(getAnnotatedOwnerType());
        }

        // the annotations as a text writes them ahead of a type, a space after each
        String written() {
            StringBuilder written = new StringBuilder();
            for (Annotation each : annotations) {
                written.append(each).append(' ');
            }
            return written.toString();
        }

        public String toString() {
            return written() + (type instanceof Class<?> named ? named.getName() : type.getTypeName());
        }
    }

    static final class Parameterized extends Plain implements AnnotatedParameterizedType {

        Parameterized(ParameterizedType type, String path, Object[] placed, Annotation[] annotations) {
            super(type, path, placed, annotations);
        }

        public AnnotatedType[] getAnnotatedActualTypeArguments() {
            return made(((ParameterizedType) type).getActualTypeArguments(), path, "A", placed);
        }

        public AnnotatedType getAnnotatedOwnerType() {
            Type owner = ((ParameterizedType) type).getOwnerType();
            return owner == null ? null : made(owner, path + "O", placed);
        }

        boolean same(AnnotatedType other) {
            return Arrays.equals(getAnnotatedActualTypeArguments(),
                    ((AnnotatedParameterizedType) other).getAnnotatedActualTypeArguments());
        }

        public String toString() {
            StringBuilder written = new StringBuilder(written());
            written.append(((Class<?>) ((ParameterizedType) type).getRawType()).getName());
            AnnotatedType[] arguments = getAnnotatedActualTypeArguments();
            if (arguments.length > 0) {
                written.append('<');
                for (int index = 0; index < arguments.length; index++) {
                    written.append(index > 0 ? ", " : "").append(arguments[index]);
                }
                written.append('>');
            }
            return written.toString();
        }
    }

    static final class Array extends Plain implements AnnotatedArrayType {

        Array(Type type, String path, Object[] placed, Annotation[] annotations) {
            super(type, path, placed, annotations);
        }

        public AnnotatedType getAnnotatedGenericComponentType() {
            Type component = type instanceof GenericArrayType array ? array.getGenericComponentType()
                    : ((Class<?>) type).getComponentType();
            return made(component, path + "C", placed);
        }

        public AnnotatedType getAnnotatedOwnerType() {
            return null;
        }

        boolean same(AnnotatedType other) {
            return getAnnotatedGenericComponentType()
                .equals(((AnnotatedArrayType) other).getAnnotatedGenericComponentType());
        }

        // the element's use, then each dimension's annotations ahead of its []
        public String toString() {
            StringBuilder dimensions = new StringBuilder();
            AnnotatedType at = this;
            while (at instanceof Array array) {
                String written = array.written();
                dimensions.append(written.isEmpty() ? "" : " " + written.strip()).append("[]");
                at = array.getAnnotatedGenericComponentType();
            }
            return at + dimensions.toString();
        }
    }

    static final class Variable extends Plain implements AnnotatedTypeVariable {

        Variable(TypeVariable<?> type, String path, Object[] placed, Annotation[] annotations) {
            super(type, path, placed, annotations);
        }

        public AnnotatedType[] getAnnotatedBounds() {
            return ((TypeVariable<?>) type).getAnnotatedBounds();
        }

        public AnnotatedType getAnnotatedOwnerType() {
            return null;
        }

        public String toString() {
            return written() + ((TypeVariable<?>) type).getName();
        }
    }

    static final class Wildcard extends Plain implements AnnotatedWildcardType {

        Wildcard(WildcardType type, String path, Object[] placed, Annotation[] annotations) {
            super(type, path, placed, annotations);
        }

        public AnnotatedType[] getAnnotatedLowerBounds() {
            return made(((WildcardType) type).getLowerBounds(), path, "L", placed);
        }

        public AnnotatedType[] getAnnotatedUpperBounds() {
            return made(((WildcardType) type).getUpperBounds(), path, "U", placed);
        }

        public AnnotatedType getAnnotatedOwnerType() {
            return null;
        }

        boolean same(AnnotatedType other) {
            AnnotatedWildcardType that = (AnnotatedWildcardType) other;
            return Arrays.equals(getAnnotatedUpperBounds(), that.getAnnotatedUpperBounds())
                    && Arrays.equals(getAnnotatedLowerBounds(), that.getAnnotatedLowerBounds());
        }

        // ? alone where its bound is Object's with no annotation
        public String toString() {
            AnnotatedType[] lower = getAnnotatedLowerBounds();
            StringBuilder written = new StringBuilder(written()).append('?');
            AnnotatedType[] bounds = lower.length > 0 ? lower : getAnnotatedUpperBounds();
            boolean object = lower.length == 0 && bounds.length == 1 && bounds[0].getAnnotations().length == 0
                    && bounds[0].getType() == Object.class;
            if (!object) {
                written.append(lower.length > 0 ? " super " : " extends ");
                for (int index = 0; index < bounds.length; index++) {
                    written.append(index > 0 ? " & " : "").append(bounds[index]);
                }
            }
            return written.toString();
        }
    }
}
