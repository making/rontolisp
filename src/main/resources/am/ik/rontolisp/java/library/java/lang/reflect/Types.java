// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

// The types a declaration's generic signature names, as the lowering makes them where the
// program asks (Class.getGenericSuperclass, Method.getGenericReturnType ..): what each
// answers, how each is written and when two are equal -- the texts, the equality and the
// hash codes a JDK's answers to probes show. The lowering knows every declared type, so a
// signature's types are made of the class objects of the classes it names; a type variable
// asks for its bounds where they are asked, since a bound may name the variable itself.
final class Types {

    private Types() {
    }

    // a class with type arguments: its class, the type around it -- the class around a
    // member class, a parameterized type where an inner class's instance is of one, null
    // for any other -- and its arguments
    static Type parameterized(Class<?> raw, Type owner, Object[] arguments) {
        return new Parameterized(raw, owner, types(arguments));
    }

    static Type wildcard(Object[] upper, Object[] lower) {
        return new Wildcard(types(upper), types(lower));
    }

    static Type array(Type component) {
        return new GenericArray(component);
    }

    // a type parameter: the class, method or constructor declaring it, its name and place
    // among the declaration's, and where its bounds are asked -- the class object of the
    // class declaring the declaration and the declaration's place among its members
    static TypeVariable<?> variable(Object declaration, String name, int index, Class<?> holder, Object member) {
        return new Variable<>((GenericDeclaration) declaration, name, index, holder, member);
    }

    // what the lowering answers of a declaration of a class (member null), of a method (its
    // place among the methods getDeclaredMethods() lists), a constructor (-1 less its
    // place) or a field (its name): 0 its type parameters, 1 its generic type (a class's
    // superclass, a method's return type, a field's type), 2 the types of its parameters
    // (a class's interfaces), 3 the types it throws, 10 and on the bounds of each type
    // parameter; null where the declaration names no generic type there, which reflection
    // then answers with the types it erases to
    static native Object generic0(Class<?> holder, Object member, int what, Object declaration);

    // new annotations written on a type parameter of a declaration of a class (member null),
    // a method or a constructor (generic0), by its place, that may stand on one; null for none
    static native Object variableAnnotations0(Class<?> holder, Object member, int index);

    static Type[] types(Object[] made) {
        Type[] taken = new Type[made == null ? 0 : made.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (Type) made[index];
        }
        return taken;
    }

    @SuppressWarnings("unchecked")
    static <D extends GenericDeclaration> TypeVariable<D>[] variables(Object made) {
        Object[] all = (Object[]) made;
        TypeVariable<D>[] taken = new TypeVariable[all == null ? 0 : all.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (TypeVariable<D>) all[index];
        }
        return taken;
    }

    // the generic types a declaration names, or what they erase to where it names none
    static Type[] generic(Class<?> holder, Object member, int what, Object declaration, Class<?>[] erased) {
        Object made = generic0(holder, member, what, declaration);
        if (made == null) {
            Type[] taken = new Type[erased.length];
            for (int index = 0; index < taken.length; index++) {
                taken[index] = erased[index];
            }
            return taken;
        }
        return types((Object[]) made);
    }

    // the type of this in a class's instance methods: a parameterized type of its type
    // variables where it or the class around an inner class has some, else the class
    static Type self(Class<?> type) {
        TypeVariable<?>[] variables = type.getTypeParameters();
        Class<?> outer = rontolisp.internal.AnnotatedTypes.owner(type);
        Type owner = outer == null ? null : rontolisp.internal.AnnotatedTypes.inner(type) ? self(outer) : outer;
        if (variables.length == 0 && !(owner instanceof ParameterizedType)) {
            return type;
        }
        return new Parameterized(type, owner, variables);
    }

    // whether bounds are Object's alone, which a text leaves out
    static boolean unbounded(Type[] bounds) {
        return bounds.length == 1 && bounds[0] instanceof Class<?> type && type.getName().equals("java.lang.Object");
    }

    // the type parameters as a declaration's generic text writes them, a comma between two
    static String declared(TypeVariable<?>[] variables) {
        if (variables.length == 0) {
            return "";
        }
        StringBuilder written = new StringBuilder("<");
        for (int index = 0; index < variables.length; index++) {
            if (index > 0) {
                written.append(',');
            }
            written.append(variables[index].getName());
            Type[] bounds = variables[index].getBounds();
            if (!unbounded(bounds)) {
                written.append(" extends ").append(joined(bounds, " & "));
            }
        }
        return written.append('>').toString();
    }

    static String joined(Type[] types, String between) {
        StringBuilder written = new StringBuilder();
        for (int index = 0; index < types.length; index++) {
            if (index > 0) {
                written.append(between);
            }
            written.append(types[index].getTypeName());
        }
        return written.toString();
    }

    // the generic text of a method or a constructor: its modifiers, its type parameters,
    // what a method returns, its class and name, its parameters -- the last of a variable
    // arity written with ... -- and what it throws
    static String text(int modifiers, boolean isDefault, TypeVariable<?>[] variables, Type returnType,
            Class<?> declaring, String name, Type[] parameterTypes, Type[] exceptionTypes, boolean varargs) {
        StringBuilder written = new StringBuilder();
        int access = modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
        int rest = modifiers & ~access;
        if (access != 0) {
            written.append(Modifier.toString(access)).append(' ');
        }
        if (isDefault) {
            written.append("default ");
        }
        if (rest != 0) {
            written.append(Modifier.toString(rest)).append(' ');
        }
        if (variables.length > 0) {
            written.append(declared(variables)).append(' ');
        }
        if (returnType != null) {
            written.append(returnType.getTypeName()).append(' ').append(declaring.getTypeName()).append('.');
        }
        written.append(name).append('(');
        for (int index = 0; index < parameterTypes.length; index++) {
            if (index > 0) {
                written.append(',');
            }
            Type type = parameterTypes[index];
            if (varargs && index == parameterTypes.length - 1) {
                Type element = type instanceof GenericArrayType array ? array.getGenericComponentType()
                        : ((Class<?>) type).getComponentType();
                written.append(element.getTypeName()).append("...");
            }
            else {
                written.append(type.getTypeName());
            }
        }
        written.append(')');
        if (exceptionTypes.length > 0) {
            written.append(" throws ").append(joined(exceptionTypes, ","));
        }
        return written.toString();
    }

    // a class whose generic signatures the program does not keep: one of a library the
    // program uses, the library's own that is no public class of the JDK, or one of the
    // JDK no table of the program's describes
    static Object unknown(Class<?> holder, int what) {
        throw new UnsupportedOperationException("the generic signatures of " + holder.getName()
                + ", whose members the program does not keep, are not supported by the experimental Java subset yet");
    }

    // a class whose annotations or record components the program does not keep: one of the
    // JDK, or a library's whose members reflection does not answer
    static Object unknownAnnotations(Class<?> holder) {
        throw new UnsupportedOperationException("the annotations of " + holder.getName()
                + ", whose members the program does not keep, are not supported by the experimental Java subset yet");
    }

    static Object unknownComponents(Class<?> holder) {
        throw new UnsupportedOperationException("getRecordComponents() of " + holder.getName()
                + ", whose members the program does not keep, is not supported by the experimental Java subset yet");
    }

    // the annotations of a type present, those of a repeatable type's container where it
    // stands among them
    @SuppressWarnings("unchecked")
    static <T extends Annotation> T[] byType(Annotation[] present, Class<T> type) {
        if (type == null) {
            throw new NullPointerException();
        }
        ArrayList<Annotation> found = new ArrayList<>();
        Class<?> container = null;
        java.lang.annotation.Repeatable repeatable = type.getDeclaredAnnotation(java.lang.annotation.Repeatable.class);
        if (repeatable != null) {
            container = repeatable.value();
        }
        for (Annotation each : present) {
            if (each.annotationType() == type) {
                found.add(each);
            }
            else if (container != null && each.annotationType() == container) {
                for (Annotation held : ((rontolisp.internal.Annotations.Container) each).contained()) {
                    found.add(held);
                }
            }
        }
        T[] taken = (T[]) Array.newInstance(type, found.size());
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (T) found.get(index);
        }
        return taken;
    }

    static final class Parameterized implements ParameterizedType {

        private final Class<?> raw;

        private final Type owner;

        private final Type[] arguments;

        Parameterized(Class<?> raw, Type owner, Type[] arguments) {
            this.raw = raw;
            this.owner = owner;
            this.arguments = arguments;
        }

        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        public Type getRawType() {
            return raw;
        }

        public Type getOwnerType() {
            return owner;
        }

        public boolean equals(Object other) {
            return other instanceof ParameterizedType that && Objects.equals(owner, that.getOwnerType())
                    && Objects.equals(raw, that.getRawType()) && Arrays.equals(arguments, that.getActualTypeArguments());
        }

        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ Objects.hashCode(raw);
        }

        public String toString() {
            StringBuilder written = new StringBuilder();
            if (owner instanceof ParameterizedType) {
                written.append(owner.getTypeName()).append('$').append(raw.getSimpleName());
            }
            else {
                written.append(raw.getName());
            }
            if (arguments.length > 0) {
                written.append('<').append(joined(arguments, ", ")).append('>');
            }
            return written.toString();
        }
    }

    static final class Variable<D extends GenericDeclaration> implements TypeVariable<D> {

        private final D declaration;

        private final String name;

        private final int index;

        private final Class<?> holder;

        private final Object member;

        Variable(D declaration, String name, int index, Class<?> holder, Object member) {
            this.declaration = declaration;
            this.name = name;
            this.index = index;
            this.holder = holder;
            this.member = member;
        }

        public Type[] getBounds() {
            return types((Object[]) generic0(holder, member, 10 + index, declaration));
        }

        public AnnotatedType[] getAnnotatedBounds() {
            return rontolisp.internal.AnnotatedTypes.all(getBounds(),
                    rontolisp.internal.AnnotatedTypes.annotated0(holder, member, 10 + index), 0);
        }

        public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
            return getDeclaredAnnotation(annotationClass);
        }

        public Annotation[] getAnnotations() {
            return getDeclaredAnnotations();
        }

        // the annotations written on its declaration that may stand on a type parameter
        public Annotation[] getDeclaredAnnotations() {
            return AccessibleObject.annotations(variableAnnotations0(holder, member, index));
        }

        public D getGenericDeclaration() {
            return declaration;
        }

        public String getName() {
            return name;
        }

        public boolean equals(Object other) {
            return other instanceof TypeVariable<?> that && Objects.equals(declaration, that.getGenericDeclaration())
                    && name.equals(that.getName());
        }

        public int hashCode() {
            return Objects.hashCode(declaration) ^ name.hashCode();
        }

        public String toString() {
            return name;
        }
    }

    static final class Wildcard implements WildcardType {

        private final Type[] upper;

        private final Type[] lower;

        Wildcard(Type[] upper, Type[] lower) {
            this.upper = upper;
            this.lower = lower;
        }

        public Type[] getUpperBounds() {
            return upper.clone();
        }

        public Type[] getLowerBounds() {
            return lower.clone();
        }

        public boolean equals(Object other) {
            return other instanceof WildcardType that && Arrays.equals(upper, that.getUpperBounds())
                    && Arrays.equals(lower, that.getLowerBounds());
        }

        public int hashCode() {
            return Arrays.hashCode(upper) ^ Arrays.hashCode(lower);
        }

        public String toString() {
            if (lower.length > 0) {
                return "? super " + joined(lower, " & ");
            }
            return unbounded(upper) ? "?" : "? extends " + joined(upper, " & ");
        }
    }

    static final class GenericArray implements GenericArrayType {

        private final Type component;

        GenericArray(Type component) {
            this.component = component;
        }

        public Type getGenericComponentType() {
            return component;
        }

        public boolean equals(Object other) {
            return other instanceof GenericArrayType that && Objects.equals(component, that.getGenericComponentType());
        }

        public int hashCode() {
            return Objects.hashCode(component);
        }

        public String toString() {
            return component.getTypeName() + "[]";
        }
    }
}
