// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import rontolisp.internal.Shown;

// The checks of a class: its kind, its modifiers, its place among the others.
public abstract class AbstractClassAssert<SELF extends AbstractClassAssert<SELF>> extends AbstractAssert<SELF, Class<?>> {

    // The modifier bit a JDK gives an annotation type (JVMS 4.1, ACC_ANNOTATION).
    private static final int ANNOTATION = 0x2000;

    protected AbstractClassAssert(Class<?> actual) {
        super(actual);
    }

    private SELF expected(boolean holds, String what) {
        isNotNull();
        if (!holds) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\n" + what);
        }
        return myself;
    }

    public SELF isInterface() {
        isNotNull();
        return expected(actual.isInterface() && !annotation(), "to be an interface");
    }

    public SELF isNotInterface() {
        isNotNull();
        return expected(!actual.isInterface() || annotation(), " not to be an interface");
    }

    public SELF isAnnotation() {
        isNotNull();
        return expected(annotation(), "to be an annotation");
    }

    public SELF isNotAnnotation() {
        isNotNull();
        return expected(!annotation(), "to  not be an annotation");
    }

    private boolean annotation() {
        return (actual.getModifiers() & ANNOTATION) != 0;
    }

    public SELF isRecord() {
        isNotNull();
        return expected(actual.isRecord(), "to be a record");
    }

    public SELF isNotRecord() {
        isNotNull();
        return expected(!actual.isRecord(), "not to be a record");
    }

    public SELF isAbstract() {
        isNotNull();
        if (!Modifier.isAbstract(actual.getModifiers())) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto be abstract");
        }
        return myself;
    }

    // A class checked to have, or not, a modifier, which a failure names with all it has.
    private SELF modified(boolean holds, String expectation) {
        isNotNull();
        if (!holds) {
            int modifiers = actual.getModifiers() & ~ANNOTATION & ~Modifier.INTERFACE;
            String had = Modifier.toString(modifiers);
            if ((modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0) {
                had = had.isEmpty() ? "package-private" : "package-private " + had;
            }
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\n" + expectation + " class but was \"" + had
                    + "\".");
        }
        return myself;
    }

    public SELF isFinal() {
        return modified(Modifier.isFinal(actual.getModifiers()), "to be a \"final\"");
    }

    public SELF isNotFinal() {
        return modified(!Modifier.isFinal(actual.getModifiers()), "not to be a \"final\"");
    }

    public SELF isPublic() {
        return modified(Modifier.isPublic(actual.getModifiers()), "to be a \"public\"");
    }

    public SELF isProtected() {
        return modified(Modifier.isProtected(actual.getModifiers()), "to be a \"protected\"");
    }

    public SELF isPrivate() {
        return modified(Modifier.isPrivate(actual.getModifiers()), "to be a \"private\"");
    }

    public SELF isPackagePrivate() {
        int modifiers = actual.getModifiers();
        return modified((modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0,
                "to be a \"package-private\"");
    }

    public SELF isStatic() {
        return modified(Modifier.isStatic(actual.getModifiers()), "to be a \"static\"");
    }

    public SELF isNotStatic() {
        return modified(!Modifier.isStatic(actual.getModifiers()), "not to be a \"static\"");
    }

    public SELF isAssignableFrom(Class<?>... others) {
        isNotNull();
        List<Class<?>> not = new ArrayList<>();
        for (Class<?> other : others) {
            if (!actual.isAssignableFrom(other)) {
                not.add(other);
            }
        }
        if (!not.isEmpty()) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto be assignable from:\n" + Shown.under(others)
                    + "\nbut was not assignable from:\n" + Shown.under(not));
        }
        return myself;
    }

    public SELF isAssignableTo(Class<?> other) {
        isNotNull();
        if (!other.isAssignableFrom(actual)) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto be assignable to:\n" + Shown.under(other));
        }
        return myself;
    }

    public SELF hasSuperclass(Class<?> superclass) {
        isNotNull();
        Class<?> had = actual.getSuperclass();
        if (had == null) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto have superclass:\n" + Shown.under(superclass)
                    + "\nbut had none.");
        }
        if (!had.equals(superclass)) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto have superclass:\n" + Shown.under(superclass)
                    + "\nbut had:\n" + Shown.under(had));
        }
        return myself;
    }

    public SELF hasNoSuperclass() {
        isNotNull();
        Class<?> had = actual.getSuperclass();
        if (had != null) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto have no superclass, but had:\n" + Shown.under(had));
        }
        return myself;
    }

    public SELF isPrimitive() {
        isNotNull();
        if (!actual.isPrimitive()) {
            throw failure("\nExpecting " + Shown.of(actual) + " to be a primitive type");
        }
        return myself;
    }

    public SELF isNotPrimitive() {
        isNotNull();
        if (actual.isPrimitive()) {
            throw failure("\nExpecting " + Shown.of(actual) + " not to be a primitive type");
        }
        return myself;
    }

    public SELF hasPackage(String packageName) {
        isNotNull();
        String had = actual.getPackageName();
        if (!had.equals(packageName)) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto have package:\n" + Shown.under(packageName)
                    + "\nbut had:\n" + Shown.under(had));
        }
        return myself;
    }
}
