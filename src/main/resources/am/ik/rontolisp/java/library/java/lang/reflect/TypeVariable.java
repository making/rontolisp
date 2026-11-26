// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A type parameter a class, a method or a constructor declares, where a signature names it.
public interface TypeVariable<D extends GenericDeclaration> extends Type {

    // its upper bounds, Object where it declares none
    Type[] getBounds();

    D getGenericDeclaration();

    String getName();
}
