// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A type as reflection answers it: a class object, or a type a declaration's generic
// signature names -- a parameterized type, a type variable, a wildcard, an array of one of
// them.
public interface Type {

    default String getTypeName() {
        return toString();
    }
}
