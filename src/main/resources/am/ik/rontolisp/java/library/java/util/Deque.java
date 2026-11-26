// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public interface Deque<E> extends Queue<E>, SequencedCollection<E> {

    void addFirst(E e);

    void addLast(E e);

    boolean offerFirst(E e);

    boolean offerLast(E e);

    E removeFirst();

    E removeLast();

    E pollFirst();

    E pollLast();

    E getFirst();

    E getLast();

    E peekFirst();

    E peekLast();

    void push(E e);

    E pop();

    boolean removeFirstOccurrence(Object o);

    boolean removeLastOccurrence(Object o);

    Iterator<E> descendingIterator();

    default Deque<E> reversed() {
        return new ReversedDeque<>(this);
    }
}
