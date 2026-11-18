// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A queue with no bound whose producer may wait for its element to be taken. The
// elements and the threads waiting to take one are kept under the queue's monitor,
// each oldest first: an element coming while a thread waits is handed to that thread
// and never queued, and a transfer queues its element and waits until a thread took
// it, taking it back when it gives up. No element is null. An iterator walks the
// elements there were when it was made.
package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class LinkedTransferQueue<E> extends AbstractCollection<E> implements TransferQueue<E> {

    /** An element in the queue, or a thread's wait for one. */
    private static final class Slot<E> {

        E item;

        /** An element's: taken. A wait's: met with an element. */
        boolean met;
    }

    private final ArrayDeque<Slot<E>> held = new ArrayDeque<>();

    private final ArrayDeque<Slot<E>> taking = new ArrayDeque<>();

    public LinkedTransferQueue() {
    }

    public LinkedTransferQueue(Collection<? extends E> c) {
        for (E e : c) {
            add(e);
        }
    }

    private static <T> T given(T e) {
        if (e == null) {
            throw new NullPointerException();
        }
        return e;
    }

    // The members that never wait call nothing that can (Bounded says why).

    /** Hands an element to the thread that waited longest for one, when one waits. */
    public synchronized boolean tryTransfer(E e) {
        given(e);
        Slot<E> taker = this.taking.poll();
        if (taker == null) {
            return false;
        }
        taker.item = e;
        taker.met = true;
        notifyAll();
        return true;
    }

    public synchronized boolean offer(E e) {
        if (!tryTransfer(e)) {
            Slot<E> queued = new Slot<>();
            queued.item = e;
            this.held.add(queued);
        }
        return true;
    }

    public boolean offer(E e, long timeout, TimeUnit unit) {
        return offer(e);
    }

    public boolean add(E e) {
        return offer(e);
    }

    public void put(E e) {
        offer(e);
    }

    /** Waits for a slot to be met: for ever below 0 nanoseconds, else no longer than that. */
    private boolean await(Slot<E> slot, ArrayDeque<Slot<E>> among, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        try {
            while (!slot.met) {
                if (nanos < 0) {
                    wait();
                }
                else if (!Timing.await(this, deadline)) {
                    break;
                }
            }
        }
        finally {
            if (!slot.met) {
                among.remove(slot);
            }
        }
        return slot.met;
    }

    /** Queues an element and waits until a thread took it; takes it back when none did. */
    private synchronized boolean hand(E e, long nanos) throws InterruptedException {
        if (tryTransfer(e)) {
            return true;
        }
        Slot<E> queued = new Slot<>();
        queued.item = e;
        this.held.add(queued);
        return await(queued, this.held, nanos);
    }

    public void transfer(E e) throws InterruptedException {
        given(e);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        hand(e, -1L);
    }

    public boolean tryTransfer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        given(e);
        long nanos = unit.toNanos(timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return nanos <= 0 ? tryTransfer(e) : hand(e, nanos);
    }

    public synchronized E poll() {
        Slot<E> first = this.held.poll();
        if (first == null) {
            return null;
        }
        first.met = true;
        notifyAll();
        return first.item;
    }

    /** Takes the first element, or waits for one; null when none came. */
    private synchronized E receive(long nanos) throws InterruptedException {
        E taken = poll();
        if (taken != null) {
            return taken;
        }
        Slot<E> mine = new Slot<>();
        this.taking.add(mine);
        return await(mine, this.taking, nanos) ? mine.item : null;
    }

    public E take() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return receive(-1L);
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return nanos <= 0 ? poll() : receive(nanos);
    }

    public E remove() {
        E taken = poll();
        if (taken == null) {
            throw new NoSuchElementException();
        }
        return taken;
    }

    public synchronized E peek() {
        Slot<E> first = this.held.peek();
        return first == null ? null : first.item;
    }

    public E element() {
        E first = peek();
        if (first == null) {
            throw new NoSuchElementException();
        }
        return first;
    }

    public synchronized boolean hasWaitingConsumer() {
        return !this.taking.isEmpty();
    }

    public synchronized int getWaitingConsumerCount() {
        return this.taking.size();
    }

    public synchronized int size() {
        return this.held.size();
    }

    public synchronized boolean isEmpty() {
        return this.held.isEmpty();
    }

    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    /** The elements in their order, under the monitor. */
    private ArrayList<E> elements() {
        ArrayList<E> all = new ArrayList<>();
        for (Slot<E> each : this.held) {
            all.add(each.item);
        }
        return all;
    }

    public synchronized boolean contains(Object o) {
        return elements().contains(o);
    }

    /** Takes an element out of the queue: one a transfer waits for counts as taken. */
    public synchronized boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        for (Slot<E> each : this.held) {
            if (o.equals(each.item)) {
                this.held.remove(each);
                each.met = true;
                notifyAll();
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        while (poll() != null) {
        }
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
        E taken;
        while (drained < maxElements && (taken = poll()) != null) {
            c.add(taken);
            drained++;
        }
        return drained;
    }

    public synchronized Object[] toArray() {
        return elements().toArray();
    }

    public synchronized <T> T[] toArray(T[] a) {
        return elements().toArray(a);
    }

    public synchronized String toString() {
        return elements().toString();
    }

    /** Walks the elements there were, taking out of the queue the one it is asked to. */
    private static final class Walk<E> implements Iterator<E> {

        private final LinkedTransferQueue<E> queue;

        private final Iterator<E> over;

        private E last;

        Walk(LinkedTransferQueue<E> queue, Iterator<E> over) {
            this.queue = queue;
            this.over = over;
        }

        public boolean hasNext() {
            return this.over.hasNext();
        }

        public E next() {
            this.last = this.over.next();
            return this.last;
        }

        public void remove() {
            if (this.last == null) {
                throw new IllegalStateException();
            }
            this.queue.remove(this.last);
            this.last = null;
        }
    }

    public synchronized Iterator<E> iterator() {
        return new Walk<E>(this, elements().iterator());
    }
}
