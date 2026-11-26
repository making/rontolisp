// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;

// A package as a class answers it (Class.getPackage()): its name and the annotations its
// package-info.java carries, which the lowering made where the program asks. One object for
// each name, the first a program asks for. No manifest says more of it here: its titles,
// versions and vendors are none, as a JDK answers them of a source launcher's program.
public class Package implements AnnotatedElement {

    // the packages asked for so far: a program asks for few
    private static Package[] made = new Package[0];

    private final String name;

    private Package(String name) {
        this.name = name;
    }

    static synchronized Package of(String name) {
        for (Package known : made) {
            if (known.name.equals(name)) {
                return known;
            }
        }
        Package[] grown = new Package[made.length + 1];
        for (int index = 0; index < made.length; index++) {
            grown[index] = made[index];
        }
        grown[made.length] = new Package(name);
        made = grown;
        return grown[made.length - 1];
    }

    public String getName() {
        return name;
    }

    public String getSpecificationTitle() {
        return null;
    }

    public String getSpecificationVersion() {
        return null;
    }

    public String getSpecificationVendor() {
        return null;
    }

    public String getImplementationTitle() {
        return null;
    }

    public String getImplementationVersion() {
        return null;
    }

    public String getImplementationVendor() {
        return null;
    }

    public boolean isSealed() {
        return false;
    }

    @SuppressWarnings("unchecked")
    public <A extends Annotation> A getAnnotation(Class<A> annotationClass) {
        return getDeclaredAnnotation(annotationClass);
    }

    public Annotation[] getAnnotations() {
        return getDeclaredAnnotations();
    }

    public Annotation[] getDeclaredAnnotations() {
        return rontolisp.internal.Annotations.listed(annotations0(name));
    }

    public int hashCode() {
        return name.hashCode();
    }

    public String toString() {
        return "package " + name;
    }

    // new annotations of the package-info.java of a package of the program, or null
    private static native Object annotations0(String name);
}
