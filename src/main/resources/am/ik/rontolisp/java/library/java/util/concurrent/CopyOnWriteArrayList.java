// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import rontolisp.internal.Splits;

// A list whose array is never changed: every change, made under the list's monitor,
// puts a new array in its place, so a read needs no lock and an iterator walks the
// array there was when it was made, and changes nothing.
public class CopyOnWriteArrayList<E> extends AbstractList<E> implements RandomAccess, Cloneable {

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

    // the same array, which no change writes into, under a monitor of its own
    public Object clone() {
        return rontolisp.internal.Support.copy(this);
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
        Object[] held = this.items;
        Object old = held[index];
        if (old != element) {
            Object[] changed = held.clone();
            changed[index] = element;
            this.items = changed;
        }
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

    public synchronized boolean addAll(int index, Collection<? extends E> c) {
        Object[] held = this.items;
        if (index < 0 || index > held.length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + held.length);
        }
        Object[] more = c.toArray();
        if (more.length == 0) {
            return false;
        }
        insertAt(index, more);
        return true;
    }

    private synchronized void insertAt(int index, Object[] more) {
        Object[] held = this.items;
        Object[] longer = new Object[held.length + more.length];
        System.arraycopy(held, 0, longer, 0, index);
        System.arraycopy(more, 0, longer, index, more.length);
        System.arraycopy(held, index, longer, index + more.length, held.length - index);
        this.items = longer;
    }

    // the elements from..to closed over, in a new array whether or not there are any
    private synchronized void dropRange(int from, int to) {
        Object[] held = this.items;
        Object[] shorter = new Object[held.length - (to - from)];
        System.arraycopy(held, 0, shorter, 0, from);
        System.arraycopy(held, to, shorter, from, held.length - to);
        this.items = shorter;
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

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return removeIf(e -> c.contains(e));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return removeIf(e -> !c.contains(e));
    }

    // a range outside the list is refused with no message
    public List<E> subList(int from, int to) {
        if (from < 0 || to > size() || from > to) {
            throw new IndexOutOfBoundsException();
        }
        return new COWSubList<>(this, from, to - from);
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
        if (this.items.length > 0) {
            this.items = new Object[0];
        }
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

    public Spliterator<E> spliterator() {
        return snapshot(Spliterator.ORDERED | Spliterator.SIZED | Spliterator.IMMUTABLE | Spliterator.SUBSIZED);
    }

    // the elements there when it is made
    Spliterator<E> snapshot(int marks) {
        Object[] items = this.items;
        return new Splits<E>(null, new Snapshot<>(items, 0), items.length, marks, true);
    }

    public ListIterator<E> listIterator() {
        return new Snapshot<>(this.items, 0);
    }

    public ListIterator<E> listIterator(int index) {
        Object[] held = this.items;
        if (index < 0 || index > held.length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + held.length);
        }
        return new Snapshot<>(held, index);
    }

    // The view reversed() answers: an index is the list's other end, handed to the list
    // unchecked, and the list's own members do the rest, as a JDK's view of this list
    // does (its words of an index out of range are the list's).
    public List<E> reversed() {
        return new Reversed<>(this);
    }

    private static final class Reversed<E> extends AbstractList<E> implements RandomAccess {

        private final CopyOnWriteArrayList<E> base;

        Reversed(CopyOnWriteArrayList<E> base) {
            this.base = base;
        }

        public List<E> reversed() {
            return base;
        }

        public int size() {
            return base.size();
        }

        public boolean isEmpty() {
            return base.isEmpty();
        }

        public boolean contains(Object o) {
            return base.contains(o);
        }

        public boolean containsAll(Collection<?> c) {
            return base.containsAll(c);
        }

        public E get(int index) {
            return base.get(base.size() - 1 - index);
        }

        public E set(int index, E element) {
            return base.set(base.size() - 1 - index, element);
        }

        public boolean add(E e) {
            base.add(0, e);
            return true;
        }

        public void add(int index, E element) {
            base.add(base.size() - index, element);
        }

        public boolean addAll(Collection<? extends E> c) {
            Object[] added = c.toArray();
            for (Object e : added) {
                base.add(0, (E) e);
            }
            return added.length > 0;
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            Object[] added = c.toArray();
            int at = index;
            for (Object e : added) {
                base.add(base.size() - at, (E) e);
                at++;
            }
            return added.length > 0;
        }

        public E remove(int index) {
            return base.remove(base.size() - 1 - index);
        }

        public boolean remove(Object o) {
            int index = base.lastIndexOf(o);
            if (index < 0) {
                return false;
            }
            base.remove(index);
            return true;
        }

        public int indexOf(Object o) {
            int index = base.lastIndexOf(o);
            return index < 0 ? -1 : base.size() - 1 - index;
        }

        public int lastIndexOf(Object o) {
            int index = base.indexOf(o);
            return index < 0 ? -1 : base.size() - 1 - index;
        }

        public void addFirst(E e) {
            base.addLast(e);
        }

        public void addLast(E e) {
            base.addFirst(e);
        }

        public E getFirst() {
            return base.getLast();
        }

        public E getLast() {
            return base.getFirst();
        }

        public E removeFirst() {
            return base.removeLast();
        }

        public E removeLast() {
            return base.removeFirst();
        }

        public void clear() {
            base.clear();
        }

        public boolean removeIf(Predicate<? super E> filter) {
            return base.removeIf(filter);
        }

        public boolean removeAll(Collection<?> c) {
            return base.removeAll(c);
        }

        public boolean retainAll(Collection<?> c) {
            return base.retainAll(c);
        }

        public void replaceAll(UnaryOperator<E> operator) {
            base.replaceAll(operator);
        }

        public void sort(Comparator<? super E> c) {
            base.sort(Collections.reverseOrder(c));
        }

        public Iterator<E> iterator() {
            return listIterator(0);
        }

        public ListIterator<E> listIterator(int index) {
            int size = base.size();
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return new Backward<>(base, base.listIterator(size - index));
        }

        public Spliterator<E> spliterator() {
            return Splits.of(this, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
        }
    }

    // a snapshot of the list walked the other way, which changes nothing
    private static final class Backward<E> implements ListIterator<E> {

        private final CopyOnWriteArrayList<E> base;

        private final ListIterator<E> each;

        Backward(CopyOnWriteArrayList<E> base, ListIterator<E> each) {
            this.base = base;
            this.each = each;
        }

        public boolean hasNext() {
            return each.hasPrevious();
        }

        public E next() {
            return each.previous();
        }

        public boolean hasPrevious() {
            return each.hasNext();
        }

        public E previous() {
            return each.next();
        }

        public int nextIndex() {
            return base.size() - each.nextIndex();
        }

        public int previousIndex() {
            return base.size() - each.nextIndex() - 1;
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

    // A part of the list, over the array the list had when the part was made or last changed
    // it: a change made to the list any other way, through another part included, puts another
    // array in its place and the part refuses with ConcurrentModificationException. Its
    // walks are of the array there was.
    private static final class COWSubList<E> extends AbstractList<E> implements RandomAccess {

        private final CopyOnWriteArrayList<E> list;

        private final int offset;

        private int size;

        private Object[] expected;

        COWSubList(CopyOnWriteArrayList<E> list, int offset, int size) {
            this.list = list;
            this.offset = offset;
            this.size = size;
            this.expected = list.items;
        }

        private void fresh() {
            if (list.items != expected) {
                throw new ConcurrentModificationException();
            }
        }

        private void inside(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
        }

        private void insideOrEnd(int index) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
        }

        public int size() {
            synchronized (list) {
                fresh();
                return size;
            }
        }

        public E get(int index) {
            synchronized (list) {
                inside(index);
                fresh();
                return list.get(offset + index);
            }
        }

        public E set(int index, E element) {
            synchronized (list) {
                inside(index);
                fresh();
                E old = list.set(offset + index, element);
                expected = list.items;
                return old;
            }
        }

        public void add(int index, E element) {
            synchronized (list) {
                fresh();
                insideOrEnd(index);
                list.add(offset + index, element);
                expected = list.items;
                size++;
            }
        }

        public boolean addAll(Collection<? extends E> c) {
            return addAll(size(), c);
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            synchronized (list) {
                insideOrEnd(index);
                Object[] more = c.toArray();
                fresh();
                if (more.length == 0) {
                    return false;
                }
                list.insertAt(offset + index, more);
                expected = list.items;
                size += more.length;
                return true;
            }
        }

        public E remove(int index) {
            synchronized (list) {
                inside(index);
                fresh();
                E old = list.remove(offset + index);
                expected = list.items;
                size--;
                return old;
            }
        }

        public boolean remove(Object o) {
            synchronized (list) {
                int index = indexOf(o);
                if (index < 0) {
                    return false;
                }
                remove(index);
                return true;
            }
        }

        public void clear() {
            synchronized (list) {
                fresh();
                list.dropRange(offset, offset + size);
                expected = list.items;
                size = 0;
            }
        }

        @SuppressWarnings("unchecked")
        public boolean removeIf(Predicate<? super E> filter) {
            Objects.requireNonNull(filter);
            synchronized (list) {
                fresh();
                Object[] held = list.items;
                Object[] kept = new Object[size];
                int count = 0;
                for (int i = 0; i < size; i++) {
                    Object each = held[offset + i];
                    if (!filter.test((E) each)) {
                        kept[count++] = each;
                    }
                }
                if (count == size) {
                    return false;
                }
                Object[] shorter = new Object[held.length - (size - count)];
                System.arraycopy(held, 0, shorter, 0, offset);
                System.arraycopy(kept, 0, shorter, offset, count);
                System.arraycopy(held, offset + size, shorter, offset + count, held.length - offset - size);
                list.items = shorter;
                expected = shorter;
                size = count;
                return true;
            }
        }

        public boolean removeAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return removeIf(e -> c.contains(e));
        }

        public boolean retainAll(Collection<?> c) {
            Objects.requireNonNull(c);
            return removeIf(e -> !c.contains(e));
        }

        @SuppressWarnings("unchecked")
        public void replaceAll(UnaryOperator<E> operator) {
            Objects.requireNonNull(operator);
            synchronized (list) {
                fresh();
                Object[] changed = list.items.clone();
                for (int i = offset; i < offset + size; i++) {
                    changed[i] = operator.apply((E) changed[i]);
                }
                list.items = changed;
                expected = changed;
            }
        }

        @SuppressWarnings("unchecked")
        public void sort(Comparator<? super E> c) {
            synchronized (list) {
                fresh();
                Object[] sorted = list.items.clone();
                Arrays.sort((E[]) sorted, offset, offset + size, c);
                list.items = sorted;
                expected = sorted;
            }
        }

        public List<E> subList(int from, int to) {
            synchronized (list) {
                fresh();
                if (from < 0 || to > size || from > to) {
                    throw new IndexOutOfBoundsException();
                }
                return new COWSubList<>(list, offset + from, to - from);
            }
        }

        public Iterator<E> iterator() {
            return listIterator(0);
        }

        public ListIterator<E> listIterator() {
            return listIterator(0);
        }

        public ListIterator<E> listIterator(int index) {
            synchronized (list) {
                fresh();
                insideOrEnd(index);
                return new Snapshot<>(Arrays.copyOfRange(expected, offset, offset + size), index);
            }
        }

        public Spliterator<E> spliterator() {
            synchronized (list) {
                fresh();
                int marks = Spliterator.ORDERED | Spliterator.SIZED | Spliterator.IMMUTABLE | Spliterator.SUBSIZED;
                Object[] range = Arrays.copyOfRange(expected, offset, offset + size);
                return new Splits<E>(null, new Snapshot<>(range, 0), size, marks, true);
            }
        }
    }
}
