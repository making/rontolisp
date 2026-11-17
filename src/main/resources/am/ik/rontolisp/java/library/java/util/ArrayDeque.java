// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A ring in an array that doubles when it is full: no element is null.
public class ArrayDeque<E> extends AbstractCollection<E> implements Deque<E> {

    private Object[] ring;

    private int head;

    private int count;

    public ArrayDeque() {
        ring = new Object[16];
    }

    public ArrayDeque(int capacity) {
        ring = new Object[capacity < 1 ? 1 : capacity];
    }

    public ArrayDeque(Collection<? extends E> c) {
        ring = new Object[c.size() < 16 ? 16 : c.size()];
        for (E e : c) {
            addLast(e);
        }
    }

    private void room() {
        if (count == ring.length) {
            Object[] larger = new Object[ring.length << 1];
            int tail = ring.length - head;
            System.arraycopy(ring, head, larger, 0, tail);
            System.arraycopy(ring, 0, larger, tail, head);
            ring = larger;
            head = 0;
        }
    }

    private int slot(int offset) {
        int index = head + offset;
        return index >= ring.length ? index - ring.length : index;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public void addFirst(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        room();
        head = head == 0 ? ring.length - 1 : head - 1;
        ring[head] = e;
        count++;
    }

    public void addLast(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        room();
        ring[slot(count)] = e;
        count++;
    }

    public boolean offerFirst(E e) {
        addFirst(e);
        return true;
    }

    public boolean offerLast(E e) {
        addLast(e);
        return true;
    }

    public E pollFirst() {
        if (count == 0) {
            return null;
        }
        E first = (E) ring[head];
        ring[head] = null;
        head = slot(1);
        count--;
        return first;
    }

    public E pollLast() {
        if (count == 0) {
            return null;
        }
        int index = slot(count - 1);
        E last = (E) ring[index];
        ring[index] = null;
        count--;
        return last;
    }

    public E removeFirst() {
        if (count == 0) {
            throw new NoSuchElementException();
        }
        return pollFirst();
    }

    public E removeLast() {
        if (count == 0) {
            throw new NoSuchElementException();
        }
        return pollLast();
    }

    public E peekFirst() {
        return count == 0 ? null : (E) ring[head];
    }

    public E peekLast() {
        return count == 0 ? null : (E) ring[slot(count - 1)];
    }

    public E getFirst() {
        if (count == 0) {
            throw new NoSuchElementException();
        }
        return (E) ring[head];
    }

    public E getLast() {
        if (count == 0) {
            throw new NoSuchElementException();
        }
        return (E) ring[slot(count - 1)];
    }

    public boolean add(E e) {
        addLast(e);
        return true;
    }

    public boolean offer(E e) {
        addLast(e);
        return true;
    }

    public E remove() {
        return removeFirst();
    }

    public E poll() {
        return pollFirst();
    }

    public E element() {
        return getFirst();
    }

    public E peek() {
        return peekFirst();
    }

    public void push(E e) {
        addFirst(e);
    }

    public E pop() {
        return removeFirst();
    }

    public void clear() {
        for (int i = 0; i < count; i++) {
            ring[slot(i)] = null;
        }
        head = 0;
        count = 0;
    }

    public boolean contains(Object o) {
        if (o != null) {
            for (int i = 0; i < count; i++) {
                if (o.equals(ring[slot(i)])) {
                    return true;
                }
            }
        }
        return false;
    }

    // Takes out the element at an offset from the head, closing the ring behind it.
    private void removeAt(int offset) {
        for (int i = offset; i < count - 1; i++) {
            ring[slot(i)] = ring[slot(i + 1)];
        }
        ring[slot(count - 1)] = null;
        count--;
    }

    public boolean remove(Object o) {
        if (o != null) {
            for (int i = 0; i < count; i++) {
                if (o.equals(ring[slot(i)])) {
                    removeAt(i);
                    return true;
                }
            }
        }
        return false;
    }

    public Iterator<E> iterator() {
        return new Each<>(this, false);
    }

    public Iterator<E> descendingIterator() {
        return new Each<>(this, true);
    }

    static final class Each<E> implements Iterator<E> {

        private final ArrayDeque<E> deque;

        private final boolean backward;

        private int cursor;

        Each(ArrayDeque<E> deque, boolean backward) {
            this.deque = deque;
            this.backward = backward;
        }

        public boolean hasNext() {
            return cursor < deque.count;
        }

        public E next() {
            if (cursor >= deque.count) {
                throw new NoSuchElementException();
            }
            int offset = backward ? deque.count - 1 - cursor : cursor;
            cursor++;
            return (E) deque.ring[deque.slot(offset)];
        }

        public void remove() {
            if (cursor == 0) {
                throw new IllegalStateException();
            }
            cursor--;
            deque.removeAt(backward ? deque.count - 1 - cursor : cursor);
        }
    }
}
