// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

import java.util.Enumeration;

public interface NamingEnumeration<T> extends Enumeration<T> {

    T next() throws NamingException;

    boolean hasMore() throws NamingException;

    void close() throws NamingException;

}
