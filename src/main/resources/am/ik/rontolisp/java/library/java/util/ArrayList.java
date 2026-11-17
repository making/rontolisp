// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;

// A list over an array that grows by half when it is full.
public class ArrayList<E> extends AbstractList<E> {

    private Object[] items;

    private int count;

    // how many times the list changed size: an iterator made before a change refuses
    private int changes;

    public ArrayList() {
        items = new Object[10];
    }

    public ArrayList(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Illegal Capacity: " + capacity);
        }
        items = new Object[capacity];
    }

    public ArrayList(Collection<? extends E> c) {
        items = c.toArray();
        count = items.length;
    }

    private void room(int wanted) {
        if (wanted > items.length) {
            int grown = items.length + (items.length >> 1);
            Object[] larger = new Object[grown < wanted ? wanted : grown];
            System.arraycopy(items, 0, larger, 0, count);
            items = larger;
        }
    }

    public void ensureCapacity(int capacity) {
        room(capacity);
    }

    public void trimToSize() {
        if (count < items.length) {
            Object[] exact = new Object[count];
            System.arraycopy(items, 0, exact, 0, count);
            items = exact;
        }
    }

    private String outside(int index) {
        return "Index " + index + " out of bounds for length " + count;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public E get(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(outside(index));
        }
        return (E) items[index];
    }

    public E set(int index, E element) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(outside(index));
        }
        E old = (E) items[index];
        items[index] = element;
        return old;
    }

    public boolean add(E e) {
        if (count == items.length) {
            room(count + 1);
        }
        items[count++] = e;
        changes++;
        return true;
    }

    public void add(int index, E element) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
        room(count + 1);
        System.arraycopy(items, index, items, index + 1, count - index);
        items[index] = element;
        count++;
        changes++;
    }

    public E remove(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(outside(index));
        }
        E old = (E) items[index];
        System.arraycopy(items, index + 1, items, index, count - index - 1);
        items[--count] = null;
        changes++;
        return old;
    }

    public boolean remove(Object o) {
        int index = indexOf(o);
        if (index < 0) {
            return false;
        }
        remove(index);
        return true;
    }

    public int indexOf(Object o) {
        for (int i = 0; i < count; i++) {
            Object e = items[i];
            if (o == null ? e == null : o.equals(e)) {
                return i;
            }
        }
        return -1;
    }

    public int lastIndexOf(Object o) {
        for (int i = count - 1; i >= 0; i--) {
            Object e = items[i];
            if (o == null ? e == null : o.equals(e)) {
                return i;
            }
        }
        return -1;
    }

    public void clear() {
        for (int i = 0; i < count; i++) {
            items[i] = null;
        }
        count = 0;
        changes++;
    }

    public boolean addAll(Collection<? extends E> c) {
        Object[] added = c.toArray();
        if (added.length == 0) {
            return false;
        }
        room(count + added.length);
        System.arraycopy(added, 0, items, count, added.length);
        count += added.length;
        changes++;
        return true;
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
        Object[] added = c.toArray();
        if (added.length == 0) {
            return false;
        }
        room(count + added.length);
        System.arraycopy(items, index, items, index + added.length, count - index);
        System.arraycopy(added, 0, items, index, added.length);
        count += added.length;
        changes++;
        return true;
    }

    public Object[] toArray() {
        Object[] out = new Object[count];
        System.arraycopy(items, 0, out, 0, count);
        return out;
    }

    public <T> T[] toArray(T[] a) {
        T[] out = a.length >= count ? a : Arrays.newArray(a, count);
        System.arraycopy(items, 0, out, 0, count);
        if (out.length > count) {
            Object[] target = out;
            target[count] = null;
        }
        return out;
    }

    public void sort(Comparator<? super E> c) {
        Arrays.sortObjects(items, 0, count, c);
        changes++;
    }

    public void forEach(Consumer<? super E> action) {
        int expected = changes;
        for (int i = 0; i < count; i++) {
            action.accept((E) items[i]);
            if (changes != expected) {
                throw new ConcurrentModificationException();
            }
        }
    }

    public Iterator<E> iterator() {
        return new Each<>(this);
    }

    static final class Each<E> implements Iterator<E> {

        private final ArrayList<E> list;

        private int cursor;

        private int last = -1;

        private int expected;

        Each(ArrayList<E> list) {
            this.list = list;
            this.expected = list.changes;
        }

        public boolean hasNext() {
            return cursor != list.count;
        }

        public E next() {
            ArrayList<E> all = list;
            int at = cursor;
            if (all.changes != expected) {
                throw new ConcurrentModificationException();
            }
            if (at >= all.count) {
                throw new NoSuchElementException();
            }
            cursor = at + 1;
            last = at;
            return (E) all.items[at];
        }

        public void remove() {
            if (last < 0) {
                throw new IllegalStateException();
            }
            if (list.changes != expected) {
                throw new ConcurrentModificationException();
            }
            list.remove(last);
            cursor = last;
            last = -1;
            expected = list.changes;
        }
    }
}
