// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

import java.io.Serializable;
import java.util.Enumeration;

public interface Name extends Cloneable, Serializable, Comparable<Object> {

    long serialVersionUID = -3617482732056931635L;

    Object clone();

    int compareTo(Object obj);

    int size();

    boolean isEmpty();

    Enumeration<String> getAll();

    String get(int posn);

    Name getPrefix(int posn);

    Name getSuffix(int posn);

    boolean startsWith(Name n);

    boolean endsWith(Name n);

    Name addAll(Name suffix) throws InvalidNameException;

    Name addAll(int posn, Name n) throws InvalidNameException;

    Name add(String comp) throws InvalidNameException;

    Name add(int posn, String comp) throws InvalidNameException;

    Object remove(int posn) throws InvalidNameException;

}
