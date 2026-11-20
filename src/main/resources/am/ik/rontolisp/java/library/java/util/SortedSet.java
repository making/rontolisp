// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public interface SortedSet<E> extends Set<E>, SequencedSet<E> {

    Comparator<? super E> comparator();

    SortedSet<E> subSet(E fromElement, E toElement);

    SortedSet<E> headSet(E toElement);

    SortedSet<E> tailSet(E fromElement);

    E first();

    E last();

    // A sorted set puts an element where its order says: addFirst and addLast refuse,
    // as SequencedCollection's do.

    default E getFirst() {
        return first();
    }

    default E getLast() {
        return last();
    }

    default E removeFirst() {
        E e = first();
        remove(e);
        return e;
    }

    default E removeLast() {
        E e = last();
        remove(e);
        return e;
    }

    default SortedSet<E> reversed() {
        return new ReversedSortedSet<>(this);
    }
}
