// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

// What ArrayBlockingQueue and LinkedBlockingQueue are: a queue of at most so many
// elements, first in first out, read and written under its own monitor, on which a
// thread putting into a full queue and one taking from an empty queue wait. No element
// is null. An iterator walks the elements there were when it was made.
abstract class Bounded<E> extends AbstractCollection<E> implements BlockingQueue<E> {

    private final ArrayDeque<E> held = new ArrayDeque<>();

    private final int capacity;

    Bounded(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
    }

    private static <T> T given(T e) {
        if (e == null) {
            throw new NullPointerException();
        }
        return e;
    }

    public synchronized int size() {
        return this.held.size();
    }

    public synchronized boolean isEmpty() {
        return this.held.isEmpty();
    }

    public synchronized int remainingCapacity() {
        return this.capacity - this.held.size();
    }

    public synchronized boolean offer(E e) {
        given(e);
        if (this.held.size() >= this.capacity) {
            return false;
        }
        this.held.addLast(e);
        notifyAll();
        return true;
    }

    public boolean add(E e) {
        if (!offer(e)) {
            throw new IllegalStateException("Queue full");
        }
        return true;
    }

    public void put(E e) throws InterruptedException {
        given(e);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (this) {
            while (this.held.size() >= this.capacity) {
                wait();
            }
            this.held.addLast(e);
            notifyAll();
        }
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        given(e);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (this.held.size() >= this.capacity) {
                if (!Timing.await(this, deadline)) {
                    return false;
                }
            }
            this.held.addLast(e);
            notifyAll();
            return true;
        }
    }

    public synchronized E poll() {
        E first = this.held.pollFirst();
        if (first != null) {
            notifyAll();
        }
        return first;
    }

    public E take() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (this) {
            while (this.held.isEmpty()) {
                wait();
            }
            notifyAll();
            return this.held.pollFirst();
        }
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (this.held.isEmpty()) {
                if (!Timing.await(this, deadline)) {
                    return null;
                }
            }
            notifyAll();
            return this.held.pollFirst();
        }
    }

    public E remove() {
        E first = poll();
        if (first == null) {
            throw new NoSuchElementException();
        }
        return first;
    }

    public synchronized E peek() {
        return this.held.peekFirst();
    }

    public E element() {
        E first = peek();
        if (first == null) {
            throw new NoSuchElementException();
        }
        return first;
    }

    public synchronized boolean contains(Object o) {
        return this.held.contains(o);
    }

    public synchronized boolean remove(Object o) {
        if (this.held.remove(o)) {
            notifyAll();
            return true;
        }
        return false;
    }

    public synchronized void clear() {
        this.held.clear();
        notifyAll();
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public synchronized int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null) {
            throw new NullPointerException();
        }
        if (c == this) {
            throw new IllegalArgumentException();
        }
        int drained = 0;
        while (drained < maxElements && !this.held.isEmpty()) {
            c.add(this.held.pollFirst());
            drained++;
        }
        if (drained > 0) {
            notifyAll();
        }
        return drained;
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
}
