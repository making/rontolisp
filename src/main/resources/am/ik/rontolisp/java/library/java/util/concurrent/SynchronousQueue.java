// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A queue that holds nothing: a thread putting an element waits for one taking it, and
// one taking for one putting. The threads waiting are kept under the queue's monitor,
// each kind oldest first; the one that comes second ends the first's wait.
package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class SynchronousQueue<E> extends AbstractCollection<E> implements BlockingQueue<E> {

    /** One thread's wait: a putter's with its element, a taker's for one. */
    private static final class Waiter<E> {

        E item;

        boolean met;
    }

    private final ArrayDeque<Waiter<E>> putting = new ArrayDeque<>();

    private final ArrayDeque<Waiter<E>> taking = new ArrayDeque<>();

    public SynchronousQueue() {
    }

    public SynchronousQueue(boolean fair) {
    }

    private static <T> T given(T e) {
        if (e == null) {
            throw new NullPointerException();
        }
        return e;
    }

    /** Waits to be met: for ever below 0 nanoseconds, else no longer than that. */
    private boolean await(Waiter<E> waiter, ArrayDeque<Waiter<E>> among, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        try {
            while (!waiter.met) {
                if (nanos < 0) {
                    wait();
                }
                else if (!Timing.await(this, deadline)) {
                    break;
                }
            }
        }
        finally {
            if (!waiter.met) {
                among.remove(waiter);
            }
        }
        return waiter.met;
    }

    // offer(e) and poll() never wait and call nothing that can (Bounded says why).

    /** Hands an element to a thread taking, or waits for one. */
    private synchronized boolean hand(E e, long nanos) throws InterruptedException {
        if (offer(e)) {
            return true;
        }
        Waiter<E> mine = new Waiter<>();
        mine.item = e;
        this.putting.add(mine);
        return await(mine, this.putting, nanos);
    }

    /** Takes the element of a thread putting, or waits for one; null when none came. */
    private synchronized E receive(long nanos) throws InterruptedException {
        E taken = poll();
        if (taken != null) {
            return taken;
        }
        Waiter<E> mine = new Waiter<>();
        this.taking.add(mine);
        return await(mine, this.taking, nanos) ? mine.item : null;
    }

    public void put(E e) throws InterruptedException {
        given(e);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        hand(e, -1L);
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        given(e);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long nanos = unit.toNanos(timeout);
        return nanos <= 0 ? offer(e) : hand(e, nanos);
    }

    /** Hands an element to a thread taking, when one waits. */
    public synchronized boolean offer(E e) {
        given(e);
        Waiter<E> taker = this.taking.poll();
        if (taker == null) {
            return false;
        }
        taker.item = e;
        taker.met = true;
        notifyAll();
        return true;
    }

    public boolean add(E e) {
        if (!offer(e)) {
            throw new IllegalStateException("Queue full");
        }
        return true;
    }

    public E take() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return receive(-1L);
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long nanos = unit.toNanos(timeout);
        return nanos <= 0 ? poll() : receive(nanos);
    }

    /** Takes the element of a thread putting, when one waits. */
    public synchronized E poll() {
        Waiter<E> putter = this.putting.poll();
        if (putter == null) {
            return null;
        }
        putter.met = true;
        notifyAll();
        return putter.item;
    }

    public E remove() {
        E taken = poll();
        if (taken == null) {
            throw new NoSuchElementException();
        }
        return taken;
    }

    public E peek() {
        return null;
    }

    public E element() {
        throw new NoSuchElementException();
    }

    public int size() {
        return 0;
    }

    public boolean isEmpty() {
        return true;
    }

    public int remainingCapacity() {
        return 0;
    }

    public boolean contains(Object o) {
        return false;
    }

    public boolean remove(Object o) {
        return false;
    }

    public void clear() {
    }

    public Iterator<E> iterator() {
        return new ArrayDeque<E>().iterator();
    }

    public Object[] toArray() {
        return new Object[0];
    }

    public String toString() {
        return "[]";
    }

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null) {
            throw new NullPointerException();
        }
        if (c == this) {
            throw new IllegalArgumentException();
        }
        int moved = 0;
        E taken;
        while (moved < maxElements && (taken = poll()) != null) {
            c.add(taken);
            moved++;
        }
        return moved;
    }
}
