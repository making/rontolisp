// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A class with type arguments: List<String>, Map.Entry<K, V>, Outer<T>.Inner.
public interface ParameterizedType extends Type {

    Type[] getActualTypeArguments();

    Type getRawType();

    // the class around a member class, a parameterized type where an inner class's
    // instance is of one; null for a top-level class
    Type getOwnerType();
}
