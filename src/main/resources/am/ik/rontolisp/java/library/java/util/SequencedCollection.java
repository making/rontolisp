// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A collection whose elements come in an order it has from end to end: the defaults
// read the first through its iterator and the last through its reversed view.
public interface SequencedCollection<E> extends Collection<E> {

    SequencedCollection<E> reversed();

    default void addFirst(E e) {
        throw new UnsupportedOperationException();
    }

    default void addLast(E e) {
        throw new UnsupportedOperationException();
    }

    default E getFirst() {
        return iterator().next();
    }

    default E getLast() {
        return reversed().iterator().next();
    }

    default E removeFirst() {
        Iterator<E> each = iterator();
        E e = each.next();
        each.remove();
        return e;
    }

    default E removeLast() {
        Iterator<E> each = reversed().iterator();
        E e = each.next();
        each.remove();
        return e;
    }
}
