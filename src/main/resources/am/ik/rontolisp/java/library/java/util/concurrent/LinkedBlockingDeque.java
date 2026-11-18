// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

// A bounded queue read and written at both ends: Bounded's monitor and waits.
public class LinkedBlockingDeque<E> extends Bounded<E> implements BlockingDeque<E> {

    public LinkedBlockingDeque() {
        super(Integer.MAX_VALUE);
    }

    public LinkedBlockingDeque(int capacity) {
        super(capacity);
    }

    public LinkedBlockingDeque(Collection<? extends E> c) {
        super(Integer.MAX_VALUE);
        for (E e : c) {
            add(e);
        }
    }

    private static <T> T present(T e) {
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    public void addFirst(E e) {
        if (!insertNow(e, true)) {
            throw new IllegalStateException("Deque full");
        }
    }

    public void addLast(E e) {
        if (!insertNow(e, false)) {
            throw new IllegalStateException("Deque full");
        }
    }

    public boolean add(E e) {
        addLast(e);
        return true;
    }

    public boolean offerFirst(E e) {
        return insertNow(e, true);
    }

    public boolean offerLast(E e) {
        return insertNow(e, false);
    }

    public void putFirst(E e) throws InterruptedException {
        insert(e, true, -1L);
    }

    public void putLast(E e) throws InterruptedException {
        insert(e, false, -1L);
    }

    public boolean offerFirst(E e, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        return nanos <= 0 ? insertNow(e, true) : insert(e, true, nanos);
    }

    public boolean offerLast(E e, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        return nanos <= 0 ? insertNow(e, false) : insert(e, false, nanos);
    }

    public E pollFirst() {
        return extractNow(true);
    }

    public E pollLast() {
        return extractNow(false);
    }

    public E removeFirst() {
        return present(extractNow(true));
    }

    public E removeLast() {
        return present(extractNow(false));
    }

    public E takeFirst() throws InterruptedException {
        return extract(true, -1L);
    }

    public E takeLast() throws InterruptedException {
        return extract(false, -1L);
    }

    public E pollFirst(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        return nanos <= 0 ? extractNow(true) : extract(true, nanos);
    }

    public E pollLast(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        return nanos <= 0 ? extractNow(false) : extract(false, nanos);
    }

    public E peekFirst() {
        return end(true);
    }

    public E peekLast() {
        return end(false);
    }

    public E getFirst() {
        return present(end(true));
    }

    public E getLast() {
        return present(end(false));
    }

    public void push(E e) {
        addFirst(e);
    }

    public E pop() {
        return removeFirst();
    }

    public Iterator<E> descendingIterator() {
        return backwards();
    }
}
