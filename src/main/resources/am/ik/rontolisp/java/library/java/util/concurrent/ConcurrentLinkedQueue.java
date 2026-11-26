// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Spliterator;
import java.util.stream.Stream;
import rontolisp.internal.ObjectFlow;
import rontolisp.internal.Splits;

// A queue with no bound, first in first out, read and written under its own monitor;
// no thread waits on it. No element is null. An iterator walks the elements there were
// when it was made and takes out of the queue the one it gave (Copied).
public class ConcurrentLinkedQueue<E> extends AbstractCollection<E> implements Queue<E>, Serializable {

    private final ArrayDeque<E> held = new ArrayDeque<>();

    public ConcurrentLinkedQueue() {
    }

    public ConcurrentLinkedQueue(Collection<? extends E> c) {
        for (E e : c) {
            offer(e);
        }
    }

    public synchronized boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        this.held.addLast(e);
        return true;
    }

    public boolean add(E e) {
        return offer(e);
    }

    public synchronized E poll() {
        return this.held.pollFirst();
    }

    public synchronized E peek() {
        return this.held.peekFirst();
    }

    public E remove() {
        E first = poll();
        if (first == null) {
            throw new NoSuchElementException();
        }
        return first;
    }

    public E element() {
        E first = peek();
        if (first == null) {
            throw new NoSuchElementException();
        }
        return first;
    }

    public synchronized int size() {
        return this.held.size();
    }

    public synchronized boolean isEmpty() {
        return this.held.isEmpty();
    }

    public synchronized boolean contains(Object o) {
        return o != null && this.held.contains(o);
    }

    public synchronized boolean remove(Object o) {
        return o != null && this.held.remove(o);
    }

    public synchronized void clear() {
        this.held.clear();
    }

    public synchronized Object[] toArray() {
        return this.held.toArray();
    }

    public synchronized <T> T[] toArray(T[] a) {
        return this.held.toArray(a);
    }

    public synchronized String toString() {
        return this.held.toString();
    }

    public synchronized Iterator<E> iterator() {
        return new Copied<>(this, this.held, new ArrayDeque<>(this.held).iterator(), false);
    }

    public Spliterator<E> spliterator() {
        return Splits.unsized(this, Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
    }

    // how many elements a concurrent collection holds is told by running the stream
    public Stream<E> stream() {
        return ObjectFlow.over(this, null);
    }
}
