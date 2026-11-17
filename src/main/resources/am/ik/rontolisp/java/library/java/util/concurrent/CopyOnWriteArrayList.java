// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

// A list whose array is never changed: every change, made under the list's monitor,
// puts a new array in its place, so a read needs no lock and an iterator walks the
// array there was when it was made, and changes nothing.
public class CopyOnWriteArrayList<E> extends AbstractList<E> {

    private volatile Object[] items = new Object[0];

    public CopyOnWriteArrayList() {
    }

    public CopyOnWriteArrayList(Collection<? extends E> c) {
        this.items = c.toArray();
    }

    public CopyOnWriteArrayList(E[] toCopyIn) {
        Object[] copied = new Object[toCopyIn.length];
        System.arraycopy(toCopyIn, 0, copied, 0, toCopyIn.length);
        this.items = copied;
    }

    public int size() {
        return this.items.length;
    }

    public boolean isEmpty() {
        return this.items.length == 0;
    }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        return (E) this.items[index];
    }

    private static int find(Object[] held, Object o) {
        for (int i = 0; i < held.length; i++) {
            if (o == null ? held[i] == null : o.equals(held[i])) {
                return i;
            }
        }
        return -1;
    }

    public int indexOf(Object o) {
        return find(this.items, o);
    }

    public boolean contains(Object o) {
        return find(this.items, o) >= 0;
    }

    @SuppressWarnings("unchecked")
    public synchronized E set(int index, E element) {
        Object[] changed = this.items.clone();
        Object old = changed[index];
        changed[index] = element;
        this.items = changed;
        return (E) old;
    }

    public synchronized boolean add(E e) {
        Object[] held = this.items;
        Object[] longer = new Object[held.length + 1];
        System.arraycopy(held, 0, longer, 0, held.length);
        longer[held.length] = e;
        this.items = longer;
        return true;
    }

    public synchronized void add(int index, E element) {
        Object[] held = this.items;
        if (index < 0 || index > held.length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + held.length);
        }
        Object[] longer = new Object[held.length + 1];
        System.arraycopy(held, 0, longer, 0, index);
        longer[index] = element;
        System.arraycopy(held, index, longer, index + 1, held.length - index);
        this.items = longer;
    }

    public synchronized boolean addIfAbsent(E e) {
        return find(this.items, e) < 0 && add(e);
    }

    public synchronized int addAllAbsent(Collection<? extends E> c) {
        int added = 0;
        for (E e : c) {
            if (addIfAbsent(e)) {
                added++;
            }
        }
        return added;
    }

    public synchronized boolean addAll(Collection<? extends E> c) {
        Object[] more = c.toArray();
        if (more.length == 0) {
            return false;
        }
        Object[] held = this.items;
        Object[] longer = new Object[held.length + more.length];
        System.arraycopy(held, 0, longer, 0, held.length);
        System.arraycopy(more, 0, longer, held.length, more.length);
        this.items = longer;
        return true;
    }

    @SuppressWarnings("unchecked")
    public synchronized E remove(int index) {
        Object[] held = this.items;
        Object old = held[index];
        Object[] shorter = new Object[held.length - 1];
        System.arraycopy(held, 0, shorter, 0, index);
        System.arraycopy(held, index + 1, shorter, index, held.length - index - 1);
        this.items = shorter;
        return (E) old;
    }

    public synchronized boolean remove(Object o) {
        int index = find(this.items, o);
        if (index < 0) {
            return false;
        }
        remove(index);
        return true;
    }

    @SuppressWarnings("unchecked")
    public synchronized boolean removeIf(Predicate<? super E> filter) {
        Object[] held = this.items;
        Object[] kept = new Object[held.length];
        int count = 0;
        for (int i = 0; i < held.length; i++) {
            if (!filter.test((E) held[i])) {
                kept[count++] = held[i];
            }
        }
        if (count == held.length) {
            return false;
        }
        this.items = Arrays.copyOf(kept, count);
        return true;
    }

    @SuppressWarnings("unchecked")
    public synchronized void replaceAll(UnaryOperator<E> operator) {
        Object[] changed = this.items.clone();
        for (int i = 0; i < changed.length; i++) {
            changed[i] = operator.apply((E) changed[i]);
        }
        this.items = changed;
    }

    @SuppressWarnings("unchecked")
    public synchronized void sort(Comparator<? super E> c) {
        Object[] sorted = this.items.clone();
        Arrays.sort((E[]) sorted, c);
        this.items = sorted;
    }

    public synchronized void clear() {
        this.items = new Object[0];
    }

    public Object[] toArray() {
        return this.items.clone();
    }

    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super E> action) {
        for (Object each : this.items) {
            action.accept((E) each);
        }
    }

    public Iterator<E> iterator() {
        return new Snapshot<>(this.items, 0);
    }

    public ListIterator<E> listIterator() {
        return new Snapshot<>(this.items, 0);
    }

    public ListIterator<E> listIterator(int index) {
        Object[] held = this.items;
        if (index < 0 || index > held.length) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return new Snapshot<>(held, index);
    }

    private static final class Snapshot<E> implements ListIterator<E> {

        private final Object[] held;

        private int cursor;

        Snapshot(Object[] held, int cursor) {
            this.held = held;
            this.cursor = cursor;
        }

        public boolean hasNext() {
            return this.cursor < this.held.length;
        }

        @SuppressWarnings("unchecked")
        public E next() {
            if (this.cursor >= this.held.length) {
                throw new NoSuchElementException();
            }
            return (E) this.held[this.cursor++];
        }

        public boolean hasPrevious() {
            return this.cursor > 0;
        }

        @SuppressWarnings("unchecked")
        public E previous() {
            if (this.cursor <= 0) {
                throw new NoSuchElementException();
            }
            return (E) this.held[--this.cursor];
        }

        public int nextIndex() {
            return this.cursor;
        }

        public int previousIndex() {
            return this.cursor - 1;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

        public void set(E e) {
            throw new UnsupportedOperationException();
        }

        public void add(E e) {
            throw new UnsupportedOperationException();
        }
    }
}
