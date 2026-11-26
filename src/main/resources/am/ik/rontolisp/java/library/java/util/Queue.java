// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public interface Queue<E> extends Collection<E> {

    boolean offer(E e);

    E remove();

    E poll();

    E element();

    E peek();
}
