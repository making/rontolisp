// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A queue over offer, poll and peek: add, remove, element and clear are those, refusing
// where the queue is full or empty, as the specification states them.
public abstract class AbstractQueue<E> extends AbstractCollection<E> implements Queue<E> {

    protected AbstractQueue() {
    }

    public boolean add(E e) {
        if (offer(e)) {
            return true;
        }
        throw new IllegalStateException("Queue full");
    }

    public E remove() {
        E head = poll();
        if (head == null) {
            throw new NoSuchElementException();
        }
        return head;
    }

    public E element() {
        E head = peek();
        if (head == null) {
            throw new NoSuchElementException();
        }
        return head;
    }

    public void clear() {
        while (poll() != null) {
        }
    }

    public boolean addAll(Collection<? extends E> c) {
        Objects.requireNonNull(c);
        if (c == this) {
            throw new IllegalArgumentException();
        }
        boolean changed = false;
        for (E e : c) {
            if (add(e)) {
                changed = true;
            }
        }
        return changed;
    }
}
