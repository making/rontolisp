// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

// The object standing for a class, an interface, an array type or a primitive type. The
// lowering makes one of each type a program has as a value -- a class literal or
// getClass() read as anything but a comparison with another or its name -- before
// anything of the program runs, with what the program asks of one: the classes above it
// where a member reads them, the test of an instance where isInstance or cast is
// reached, the constants where getEnumConstants is. Its members by reflection are the
// lowering's: of a class of the program, what its source declares; of a class of the JDK,
// what the JDK's reflection answers of it (the lowering's table of the JDK's members); the
// annotations of a class the program wrote and, by a name computed where the program
// runs, the classes an index of annotated classes lists (META-INF/annotations/<the
// annotation type>). The generic signatures, the annotations of the members and a
// record's components are the lowering's too, made where the program asks for them.
public final class Class<T> implements GenericDeclaration, Type, AnnotatedElement {

    private final String name;

    private final String simpleName;

    // null where it is the name, empty for a local or an anonymous class, which has none
    private final String canonical;

    // 1 an interface, 2 an array type, 4 a primitive type, 8 an enum, 16 a record
    private final int kind;

    private final Class<?> superclass;

    // null where the program has no member reading them
    private final Class<?>[] interfaces;

    private final Class<?> component;

    // the function of one value answering whether it is an instance
    private final Object test;

    // the function of no value answering an enum's constants, or null
    private final Object constants;

    Class(String name, String simpleName, String canonical, int kind, Class<?> superclass, Class<?>[] interfaces,
            Class<?> component, Object test, Object constants) {
        this.name = name;
        this.simpleName = simpleName;
        this.canonical = canonical;
        this.kind = kind;
        this.superclass = superclass;
        this.interfaces = interfaces;
        this.component = component;
        this.test = test;
        this.constants = constants;
    }

    public String getName() {
        return name;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public String getCanonicalName() {
        if (component != null) {
            String element = component.getCanonicalName();
            return element == null ? null : element + "[]";
        }
        return canonical == null ? name : canonical.isEmpty() ? null : canonical;
    }

    public String getTypeName() {
        return component != null ? component.getTypeName() + "[]" : name;
    }

    public String getPackageName() {
        Class<?> element = this;
        while (element.component != null) {
            element = element.component;
        }
        int dot = element.name.lastIndexOf('.');
        return (element.kind & 4) != 0 ? "java.lang" : dot < 0 ? "" : element.name.substring(0, dot);
    }

    public boolean isInterface() {
        return (kind & 1) != 0;
    }

    public boolean isArray() {
        return (kind & 2) != 0;
    }

    public boolean isPrimitive() {
        return (kind & 4) != 0;
    }

    public boolean isEnum() {
        return (kind & 8) != 0;
    }

    public boolean isRecord() {
        return (kind & 16) != 0;
    }

    // a local class has a simple name, an anonymous one none
    public boolean isAnonymousClass() {
        return canonical != null && canonical.isEmpty() && simpleName.isEmpty();
    }

    @SuppressWarnings("unchecked")
    public Class<? super T> getSuperclass() {
        return (Class<? super T>) superclass;
    }

    public Class<?> getComponentType() {
        return component;
    }

    public Class<?> componentType() {
        return component;
    }

    // the class object of the array type of this one, the same each time: one made before
    // the program runs, else the first made where it runs. void has none, nor an array
    // type of 255 dimensions
    public Class<?> arrayType() {
        if ((kind & 4) != 0 && name.equals("void") || name.startsWith("[") && name.lastIndexOf('[') >= 254) {
            throw new UnsupportedOperationException(new IllegalArgumentException());
        }
        return arrayType0(this);
    }

    public boolean isInstance(Object value) {
        return test0(test, value);
    }

    @SuppressWarnings("unchecked")
    public T cast(Object value) {
        if (value != null && !test0(test, value)) {
            throw new ClassCastException("Cannot cast " + value.getClass().getName() + " to " + name);
        }
        return (T) value;
    }

    public boolean isAssignableFrom(Class<?> other) {
        if (other == this) {
            return true;
        }
        if ((kind & 2) != 0) {
            // an array of references is one of what its elements are
            return (other.kind & 2) != 0 && (component.kind & 4) == 0
                    && (other.component.kind & 4) == 0 && component.isAssignableFrom(other.component);
        }
        if ((other.kind & 4) != 0) {
            return false;
        }
        if (superclass == null && (kind & 5) == 0) {
            // Object: every class, interface and array type
            return true;
        }
        if (other.superclass != null && isAssignableFrom(other.superclass)) {
            return true;
        }
        for (Class<?> implemented : other.interfaces) {
            if (isAssignableFrom(implemented)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public T[] getEnumConstants() {
        return constants == null ? null : (T[]) constants0(constants);
    }

    // the methods the class's source declares, the ones the language declares with them
    // (an enum's values and valueOf, a record's accessors and Object's three) after
    // them; of a class of the JDK, its public and protected ones; none of an array or a
    // primitive type. Each in full where the program asks more of a method than its name
    // and its class, else by name
    public Method[] getDeclaredMethods() {
        Object[] full = (kind & 6) != 0 ? null : members0(this, 0);
        if (full != null) {
            return methods(full, full.length);
        }
        String names = methods0(this);
        if (names == null) {
            throw new UnsupportedOperationException(unknown("getDeclaredMethods()"));
        }
        int count = names.isEmpty() ? 0 : 1;
        for (int at = 0; at < names.length(); at++) {
            if (names.charAt(at) == ' ') {
                count++;
            }
        }
        Method[] declared = new Method[count];
        int start = 0;
        for (int index = 0; index < count; index++) {
            int end = names.indexOf(' ', start);
            end = end < 0 ? names.length() : end;
            declared[index] = method0(this, names.substring(start, end), index);
            start = end + 1;
        }
        return declared;
    }

    // the interfaces the class or interface declares, in their order; an array type's
    // two. A class of the JDK has those of this library's class that the program has
    public Class<?>[] getInterfaces() {
        Class<?>[] declared = new Class<?>[interfaces.length];
        for (int index = 0; index < declared.length; index++) {
            declared[index] = interfaces[index];
        }
        return declared;
    }

    // the method the class declares by that name and those types, whatever its access (of
    // a class of the JDK, a public or a protected one); one of a class whose members the
    // program does not keep is refused where the program runs. A call naming no type
    // finds a method of no parameter by its name alone
    public Method getDeclaredMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        if (name == null) {
            throw new NullPointerException();
        }
        if (parameterTypes != null && parameterTypes.length != 0) {
            // by its types, where a call of the program names some
            return typed0() ? declaredTyped(name, parameterTypes) : null;
        }
        Method found = (kind & 6) != 0 ? null : declaredMethod0(this, name);
        if (found == null) {
            throw new NoSuchMethodException(this.name + "." + name + "()");
        }
        return found;
    }

    private Method declaredTyped(String name, Class<?>[] parameterTypes) throws NoSuchMethodException {
        Object[] declared = (kind & 6) != 0 ? new Object[0] : members0(this, 0);
        if (declared == null) {
            throw new UnsupportedOperationException(unknown("getDeclaredMethod(\"" + name + "\")"));
        }
        for (Object each : declared) {
            Method method = (Method) each;
            if (method.getName().equals(name) && takes(method.getParameterTypes(), parameterTypes)) {
                return method;
            }
        }
        throw new NoSuchMethodException(signature(this.name, name, parameterTypes));
    }

    // the public method the class has by that name and those types: its own or a class
    // above it's, else one an interface it implements declares that is not static. A call
    // naming no type finds a method of no parameter by its name alone, through the classes
    // above and then the interfaces
    public Method getMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        if (name == null) {
            throw new NullPointerException();
        }
        if (parameterTypes != null && parameterTypes.length != 0) {
            // by its types, where a call of the program names some
            return typed0() ? publicTyped(name, parameterTypes) : null;
        }
        Method found = (kind & 4) != 0 ? null : publicMethod(name);
        if (found == null) {
            throw new NoSuchMethodException(this.name + "." + name + "()");
        }
        return found;
    }

    private Method publicTyped(String name, Class<?>[] parameterTypes) throws NoSuchMethodException {
        Object[] listed = (kind & 4) != 0 ? new Object[] { null } : members0(this, 3);
        if (listed == null) {
            throw new UnsupportedOperationException(unknown("getMethod(\"" + name + "\")"));
        }
        for (int index = 0; index < listed.length - 1; index++) {
            Method method = (Method) listed[index];
            if (method.getName().equals(name) && takes(method.getParameterTypes(), parameterTypes)) {
                return method;
            }
        }
        if (listed[listed.length - 1] != null) {
            throw new UnsupportedOperationException("getMethod(\"" + name + "\") of " + this.name + ", under "
                    + listed[listed.length - 1] + ", is not supported by the experimental Java subset yet");
        }
        throw new NoSuchMethodException(signature(this.name, name, parameterTypes));
    }

    // the public methods of the class and of the classes and interfaces above it, as a JDK
    // lists them: of a signature the one a class declares before an interface's, the one
    // below before the one above, so that a method overridden is left out; none of a
    // primitive type, Object's of an array type. One whose class the library does not have
    // (a class of the JDK above, which only the JDK's classes reach) is refused where the
    // program runs
    public Method[] getMethods() {
        Object[] listed = members0(this, 3);
        if (listed == null) {
            throw new UnsupportedOperationException(unknown("getMethods()"));
        }
        if (listed[listed.length - 1] != null) {
            throw new UnsupportedOperationException("getMethods() of " + name + ", under " + listed[listed.length - 1]
                    + ", is not supported by the experimental Java subset yet");
        }
        return methods(listed, listed.length - 1);
    }

    // the words refusing a member of a class whose members are not kept: one of a library
    // the program uses, the library's own that is no public class of the JDK, or one of the
    // JDK the program holds only as the type of a member reflection found
    private String unknown(String member) {
        return member + " of " + name
                + ", whose members the program does not keep, is not supported by the experimental Java subset yet";
    }

    // whether a member takes the types looked for
    private static boolean takes(Class<?>[] own, Class<?>[] types) {
        int given = types == null ? 0 : types.length;
        if (own.length != given) {
            return false;
        }
        for (int index = 0; index < given; index++) {
            if (own[index] != types[index]) {
                return false;
            }
        }
        return true;
    }

    // the name of a member with the types it was looked for by, as NoSuchMethodException
    // tells it
    private static String signature(String type, String name, Class<?>[] types) {
        StringBuilder written = new StringBuilder(type).append('.').append(name).append('(');
        if (types != null) {
            for (int index = 0; index < types.length; index++) {
                if (index > 0) {
                    written.append(',');
                }
                written.append(types[index] == null ? "null" : types[index].getName());
            }
        }
        return written.append(')').toString();
    }

    private static Method[] methods(Object[] members, int count) {
        Method[] taken = new Method[count];
        for (int index = 0; index < count; index++) {
            taken[index] = (Method) members[index];
        }
        return taken;
    }

    private Method publicMethod(String name) {
        for (Class<?> type = this; type != null; type = type.getSuperclass()) {
            // an array type declares none
            Method own = (type.kind & 2) != 0 ? null : declaredMethod0(type, name);
            if (own != null && (own.getModifiers() & 1) != 0) {
                return own;
            }
        }
        for (Class<?> type = this; type != null && type != Object.class; type = type.getSuperclass()) {
            Method inherited = interfaceMethod(type, name);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private static Method interfaceMethod(Class<?> type, String name) {
        for (Class<?> implemented : type.getInterfaces()) {
            Method own = declaredMethod0(implemented, name);
            if (own != null && (own.getModifiers() & 9) == 1) {
                return own;
            }
            Method above = interfaceMethod(implemented, name);
            if (above != null) {
                return above;
            }
        }
        return null;
    }

    // the field the class declares by that name, whatever its access (of a class of the
    // JDK, a public or a protected one); one of a class whose members the program does not
    // keep is refused where the program runs
    public Field getDeclaredField(String name) throws NoSuchFieldException {
        if (name == null) {
            throw new NullPointerException();
        }
        Field found = (kind & 6) != 0 ? null : declaredField0(this, name);
        if (found == null) {
            throw new NoSuchFieldException(name);
        }
        return found;
    }

    // the public field the class has by that name: its own, else an interface's it
    // implements (each before those it extends), else a class above it's
    public Field getField(String name) throws NoSuchFieldException {
        if (name == null) {
            throw new NullPointerException();
        }
        Field found = (kind & 6) != 0 ? null : publicField(name);
        if (found == null) {
            throw new NoSuchFieldException(name);
        }
        return found;
    }

    private Field publicField(String name) {
        Field own = declaredField0(this, name);
        if (own != null && (own.getModifiers() & 1) != 0) {
            return own;
        }
        for (Class<?> implemented : getInterfaces()) {
            Field inherited = implemented.publicField(name);
            if (inherited != null) {
                return inherited;
            }
        }
        Class<?> above = getSuperclass();
        return above == null || above == Object.class ? null : above.publicField(name);
    }

    // the fields the class declares, whatever their access (of a class of the JDK, the
    // public and protected ones); none of an array or a primitive type; one of a class
    // whose members the program does not keep is refused where the program runs
    public Field[] getDeclaredFields() {
        Object[] declared = (kind & 6) != 0 ? new Object[0] : members0(this, 1);
        if (declared == null) {
            throw new UnsupportedOperationException(unknown("getDeclaredFields()"));
        }
        Field[] taken = new Field[declared.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (Field) declared[index];
        }
        return taken;
    }

    // the public fields of the class, of the interfaces above it and of the classes above
    // it
    public Field[] getFields() {
        return publicFields(new Field[0], true);
    }

    private Field[] publicFields(Field[] found, boolean own) {
        Object[] declared = (kind & 6) != 0 ? new Object[0] : members0(this, 1);
        if (declared == null) {
            if (own) {
                throw new UnsupportedOperationException(unknown("getFields()"));
            }
            return found;
        }
        Field[] more = found;
        for (Object each : declared) {
            Field field = (Field) each;
            boolean listed = false;
            for (Field taken : more) {
                listed |= taken.equals(field);
            }
            if ((field.getModifiers() & 1) != 0 && !listed) {
                Field[] grown = new Field[more.length + 1];
                for (int index = 0; index < more.length; index++) {
                    grown[index] = more[index];
                }
                grown[more.length] = field;
                more = grown;
            }
        }
        for (Class<?> implemented : getInterfaces()) {
            more = implemented.publicFields(more, false);
        }
        Class<?> above = getSuperclass();
        return above == null ? more : above.publicFields(more, false);
    }

    // the class of a binary name: one the lowering listed in an index of annotated
    // classes the program asks for, the only classes a name computed where the program
    // runs can find
    public static Class<?> forName(String name) throws ClassNotFoundException {
        return forName(name, true, null);
    }

    public static Class<?> forName(String name, boolean initialize, ClassLoader loader)
            throws ClassNotFoundException {
        if (name == null) {
            throw new NullPointerException();
        }
        Class<?> found = named0(name);
        if (found == null) {
            throw new ClassNotFoundException(name);
        }
        if (initialize) {
            initialize0(found);
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    public <U> Class<? extends U> asSubclass(Class<U> type) {
        if (!type.isAssignableFrom(this)) {
            throw new ClassCastException(toString() + " cannot be cast to " + type.toString());
        }
        return (Class<? extends U>) this;
    }

    // the annotation of a type the class carries where the program can read it (its
    // retention is RUNTIME): written on the class, or on a class above it where the
    // annotation type is @Inherited. A new object each time, as its arrays are
    @SuppressWarnings("unchecked")
    public <A extends Annotation> A getAnnotation(Class<A> annotationClass) {
        if (annotationClass == null) {
            throw new NullPointerException();
        }
        return (A) annotation0(this, annotationClass, false);
    }

    @SuppressWarnings("unchecked")
    public <A extends Annotation> A getDeclaredAnnotation(Class<A> annotationClass) {
        if (annotationClass == null) {
            throw new NullPointerException();
        }
        return (A) annotation0(this, annotationClass, true);
    }

    public boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
        return getAnnotation(annotationClass) != null;
    }

    // the annotations of the class: those of an inherited type a class above carries, then
    // its own, an own one of a type taking the inherited one's place, as a JDK lists them
    public Annotation[] getAnnotations() {
        return annotations(1);
    }

    public Annotation[] getDeclaredAnnotations() {
        return annotations(0);
    }

    private Annotation[] annotations(int what) {
        return rontolisp.internal.Annotations.listed((kind & 6) != 0 ? null : annotations0(this, null, what));
    }

    // of a type the class carries none of, directly or in a container, those a class above
    // carries where the type is inherited
    public <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationClass) {
        A[] own = getDeclaredAnnotationsByType(annotationClass);
        Class<?> above = getSuperclass();
        if (own.length > 0 || above == null
                || !annotationClass.isAnnotationPresent(java.lang.annotation.Inherited.class)) {
            return own;
        }
        return above.getAnnotationsByType(annotationClass);
    }

    // the type parameters the class declares, in their order; none of an array or a
    // primitive type
    @SuppressWarnings("unchecked")
    public TypeVariable<Class<T>>[] getTypeParameters() {
        Object[] made = (kind & 6) != 0 ? null : (Object[]) generic0(this, null, 0, this);
        TypeVariable<Class<T>>[] taken = new TypeVariable[made == null ? 0 : made.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (TypeVariable<Class<T>>) made[index];
        }
        return taken;
    }

    // the class it extends as its declaration writes it: a parameterized type where it gives
    // type arguments, else the class; none of an interface, a primitive type or Object
    public Type getGenericSuperclass() {
        if ((kind & 5) != 0) {
            return null;
        }
        Object made = (kind & 2) != 0 ? null : generic0(this, null, 1, this);
        return made != null ? (Type) made : getSuperclass();
    }

    // the interfaces it implements or extends as its declaration writes them
    public Type[] getGenericInterfaces() {
        Object made = (kind & 6) != 0 ? null : generic0(this, null, 2, this);
        Object[] all = made != null ? (Object[]) made : getInterfaces();
        Type[] taken = new Type[all.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (Type) all[index];
        }
        return taken;
    }

    // its modifiers, its kind, its name and its type parameters with their bounds
    public String toGenericString() {
        if ((kind & 6) != 0) {
            return getTypeName();
        }
        StringBuilder written = new StringBuilder();
        int modifiers = getModifiers();
        int shown = modifiers & Modifier.classModifiers();
        if (shown != 0) {
            written.append(Modifier.toString(shown)).append(' ');
        }
        if ((modifiers & 0x2000) != 0) {
            written.append('@');
        }
        written.append((kind & 1) != 0 ? "interface" : (kind & 8) != 0 ? "enum" : (kind & 16) != 0 ? "record" : "class")
            .append(' ')
            .append(name);
        TypeVariable<Class<T>>[] parameters = getTypeParameters();
        for (int index = 0; index < parameters.length; index++) {
            written.append(index == 0 ? "<" : ",").append(parameters[index].getName());
            Type[] bounds = parameters[index].getBounds();
            if (bounds.length != 1 || !bounds[0].getTypeName().equals("java.lang.Object")) {
                for (int at = 0; at < bounds.length; at++) {
                    written.append(at == 0 ? " extends " : " & ").append(bounds[at].getTypeName());
                }
            }
        }
        return parameters.length > 0 ? written.append('>').toString() : written.toString();
    }

    // the components of a record, in their order, new ones each time; null of any other
    // class
    public RecordComponent[] getRecordComponents() {
        Object[] made = (kind & 16) == 0 ? null : (Object[]) components0(this);
        if (made == null) {
            return null;
        }
        RecordComponent[] taken = new RecordComponent[made.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (RecordComponent) made[index];
        }
        return taken;
    }

    // the package of the class -- of an array's element class --, none of a primitive type
    // or an array of one: one object for each package's name
    public Package getPackage() {
        Class<?> element = this;
        while (element.component != null) {
            element = element.component;
        }
        return (element.kind & 4) != 0 ? null : Package.of(getPackageName());
    }

    // the constructor the class declares taking those types: one of no parameter of a class
    // of the program or of a library it uses, any of a class of the program or a public or
    // protected one of a class of the JDK
    @SuppressWarnings("unchecked")
    public Constructor<T> getDeclaredConstructor(Class<?>... parameterTypes) throws NoSuchMethodException {
        if (parameterTypes != null && parameterTypes.length != 0) {
            // by its types, where a call of the program names some
            return typed0() ? (Constructor<T>) constructorTyped(parameterTypes, false) : null;
        }
        Object found = (kind & 7) != 0 ? null : constructor0(this);
        if (found == null) {
            throw new NoSuchMethodException(name + ".<init>()");
        }
        return (Constructor<T>) found;
    }

    // the public constructor the class declares taking those types
    @SuppressWarnings("unchecked")
    public Constructor<T> getConstructor(Class<?>... parameterTypes) throws NoSuchMethodException {
        if (parameterTypes != null && parameterTypes.length != 0) {
            return typed0() ? (Constructor<T>) constructorTyped(parameterTypes, true) : null;
        }
        Object found = (kind & 7) != 0 ? null : constructor0(this);
        if (found == null || (((Constructor<T>) found).getModifiers() & 1) == 0) {
            throw new NoSuchMethodException(name + ".<init>()");
        }
        return (Constructor<T>) found;
    }

    private Object constructorTyped(Class<?>[] parameterTypes, boolean publicOnly) throws NoSuchMethodException {
        Object[] declared = (kind & 7) != 0 ? new Object[0] : members0(this, 2);
        if (declared == null) {
            throw new UnsupportedOperationException(unknown("getDeclaredConstructor(..)"));
        }
        for (Object each : declared) {
            Constructor<?> constructor = (Constructor<?>) each;
            if (takes(constructor.getParameterTypes(), parameterTypes)
                    && (!publicOnly || (constructor.getModifiers() & 1) != 0)) {
                return each;
            }
        }
        throw new NoSuchMethodException(signature(name, "<init>", parameterTypes));
    }

    // the constructors the class declares: of a class of the program's; none of an
    // interface, an array or a primitive type
    public Constructor<?>[] getDeclaredConstructors() {
        Object[] declared = (kind & 7) != 0 ? new Object[0] : members0(this, 2);
        if (declared == null) {
            throw new UnsupportedOperationException(unknown("getDeclaredConstructors()"));
        }
        Constructor<?>[] taken = new Constructor<?>[declared.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (Constructor<?>) declared[index];
        }
        return taken;
    }

    // the public ones of them
    public Constructor<?>[] getConstructors() {
        java.util.ArrayList<Constructor<?>> found = new java.util.ArrayList<>();
        for (Constructor<?> each : getDeclaredConstructors()) {
            if ((each.getModifiers() & 1) != 0) {
                found.add(each);
            }
        }
        return found.toArray(new Constructor<?>[0]);
    }

    // the modifiers a class's declaration writes and the language gives it, as Modifier
    // decodes them (and an enum's bit, 0x4000); a class of the JDK's, an array type's and a
    // primitive type's as a JDK answers them. A class of a library the program uses is
    // refused where the program runs
    public native int getModifiers();

    // an instance made by the constructor of no parameter, as the class calling may make one:
    // what the constructor throws is thrown as it is
    @Deprecated(since = "9")
    public native T newInstance() throws InstantiationException, IllegalAccessException;

    // the modifiers of a class of a library the program uses, or of the library's own that
    // is no public class of the JDK
    static int unknownModifiers(Class<?> type) {
        throw new UnsupportedOperationException("getModifiers() of " + type.name
                + ", no class of the program or of the JDK, is not supported by the experimental Java subset yet");
    }

    // a resource beside the class, as its loader finds it: a name with no leading '/' in
    // the class's package. The lowering finds it, so the name is a constant; a class of
    // the JDK and a primitive type have none of the program's
    public native InputStream getResourceAsStream(String name);

    // where that resource was found when the program was compiled
    public native java.net.URL getResource(String name);

    // the program's loader for a class of the program, the system's for one of a library
    // on the source path, none for a class of the JDK and a primitive type
    public native ClassLoader getClassLoader();

    // the unnamed module of that loader, java.base for none
    public native Module getModule();

    // the binary name of the class a resource is found beside -- an array's element
    // class --, or null for a primitive type
    static String located(Class<?> type) {
        Class<?> element = type;
        while (element.component != null) {
            element = element.component;
        }
        return (element.kind & 4) != 0 ? null : element.name;
    }

    public String toString() {
        return ((kind & 1) != 0 ? "interface " : (kind & 4) != 0 ? "" : "class ") + name;
    }

    private static native boolean test0(Object test, Object value);

    private static native Object[] constants0(Object constants);

    // the names of the methods the class declares, a space between two; null for a class
    // whose methods are not known
    private static native String methods0(Class<?> type);

    // a new object standing for the method of the class at that place among them
    private static native Method method0(Class<?> type, String name, int index);

    // the class object of a binary name among the classes an index lists, or null
    private static native Class<?> named0(String name);

    // runs the static initialization of a class found by its name, if it has not run
    private static native void initialize0(Class<?> type);

    // a new annotation of a type the class carries, or null
    private static native Object annotation0(Class<?> type, Class<?> annotationType, boolean declared);

    // the Constructor of no parameter of a class, or null where it declares none
    private static native Object constructor0(Class<?> type);

    // a new Method of the method of no parameter a class declares by a name, or null
    // where it declares none; refused for a class whose members are not kept
    private static native Method declaredMethod0(Class<?> type, String name);

    // a new Field of the field a class declares by a name, or null where it declares
    // none; refused for a class whose members are not kept
    private static native Field declaredField0(Class<?> type, String name);

    // new objects of the members a class declares: its methods (0), its fields (1), its
    // constructors (2), or the public methods it has (3: with a last element naming a class
    // above it the library does not have, else null); null for a class whose members are
    // not kept
    private static native Object[] members0(Class<?> type, int kind);

    // whether a call of the program looks a member up by its types: the lookups by types
    // are lowered where one does
    private static native boolean typed0();

    // the class object of a class's array type
    private static native Class<?> arrayType0(Class<?> type);

    // what the lowering answers of the generic signature of the class (member null), of a
    // method, a constructor or a field it declares (java.lang.reflect.Types)
    private static native Object generic0(Class<?> type, Object member, int what, Object declaration);

    // new annotations of the class (member null) or of a member: 0 its own, 1 a class's
    // with the inherited ones (java.lang.reflect.AccessibleObject)
    private static native Object annotations0(Class<?> type, Object member, int what);

    // new components of a record
    private static native Object components0(Class<?> type);
}
