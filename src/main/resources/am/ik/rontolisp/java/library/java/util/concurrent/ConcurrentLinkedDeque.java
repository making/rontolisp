// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;

// A queue with no bound read and written at both ends, under its own monitor; no
// thread waits on it. No element is null. An iterator walks the elements there were
// when it was made.
public class ConcurrentLinkedDeque<E> extends AbstractCollection<E> implements Deque<E> {

    private final ArrayDeque<E> held = new ArrayDeque<>();

    public ConcurrentLinkedDeque() {
    }

    public ConcurrentLinkedDeque(Collection<? extends E> c) {
        for (E e : c) {
            addLast(e);
        }
    }

    public synchronized void addFirst(E e) {
        this.held.addFirst(e);
    }

    public synchronized void addLast(E e) {
        this.held.addLast(e);
    }

    public boolean offerFirst(E e) {
        addFirst(e);
        return true;
    }

    public boolean offerLast(E e) {
        addLast(e);
        return true;
    }

    public boolean add(E e) {
        addLast(e);
        return true;
    }

    public boolean offer(E e) {
        addLast(e);
        return true;
    }

    public void push(E e) {
        addFirst(e);
    }

    public synchronized E pollFirst() {
        return this.held.pollFirst();
    }

    public synchronized E pollLast() {
        return this.held.pollLast();
    }

    public synchronized E removeFirst() {
        return this.held.removeFirst();
    }

    public synchronized E removeLast() {
        return this.held.removeLast();
    }

    public synchronized E peekFirst() {
        return this.held.peekFirst();
    }

    public synchronized E peekLast() {
        return this.held.peekLast();
    }

    public synchronized E getFirst() {
        return this.held.getFirst();
    }

    public synchronized E getLast() {
        return this.held.getLast();
    }

    public E poll() {
        return pollFirst();
    }

    public E peek() {
        return peekFirst();
    }

    public E remove() {
        return removeFirst();
    }

    public E element() {
        return getFirst();
    }

    public E pop() {
        return removeFirst();
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
        return new ArrayDeque<>(this.held).iterator();
    }

    public synchronized Iterator<E> descendingIterator() {
        return new ArrayDeque<>(this.held).descendingIterator();
    }
}
