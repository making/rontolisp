// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A field, a method or a constructor of a class.
public interface Member {

    int PUBLIC = 0;

    int DECLARED = 1;

    Class<?> getDeclaringClass();

    String getName();

    int getModifiers();

    boolean isSynthetic();
}
