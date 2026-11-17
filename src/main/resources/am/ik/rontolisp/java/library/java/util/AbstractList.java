// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A list over get and size; one that changes also has set, add at an index and remove
// at an index.
public abstract class AbstractList<E> extends AbstractCollection<E> implements List<E> {

    protected AbstractList() {
    }

    public abstract E get(int index);

    public E set(int index, E element) {
        throw new UnsupportedOperationException();
    }

    public void add(int index, E element) {
        throw new UnsupportedOperationException();
    }

    public E remove(int index) {
        throw new UnsupportedOperationException();
    }

    public boolean add(E e) {
        add(size(), e);
        return true;
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        int at = index;
        for (E e : c) {
            add(at++, e);
        }
        return at != index;
    }

    public int indexOf(Object o) {
        int n = size();
        for (int i = 0; i < n; i++) {
            E e = get(i);
            if (o == null ? e == null : o.equals(e)) {
                return i;
            }
        }
        return -1;
    }

    public int lastIndexOf(Object o) {
        for (int i = size() - 1; i >= 0; i--) {
            E e = get(i);
            if (o == null ? e == null : o.equals(e)) {
                return i;
            }
        }
        return -1;
    }

    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    public void clear() {
        for (int i = size() - 1; i >= 0; i--) {
            remove(i);
        }
    }

    public Iterator<E> iterator() {
        return new IndexIterator<>(this);
    }

    public List<E> subList(int from, int to) {
        if (from < 0 || to > size() || from > to) {
            throw new IndexOutOfBoundsException("fromIndex: " + from + ", toIndex: " + to + ", size: " + size());
        }
        return new SubList<>(this, from, to - from);
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof List)) {
            return false;
        }
        List<?> other = (List<?>) o;
        if (other.size() != size()) {
            return false;
        }
        Iterator<?> theirs = other.iterator();
        for (E mine : this) {
            Object their = theirs.next();
            if (!(mine == null ? their == null : mine.equals(their))) {
                return false;
            }
        }
        return true;
    }

    public int hashCode() {
        int hash = 1;
        for (E e : this) {
            hash = 31 * hash + (e == null ? 0 : e.hashCode());
        }
        return hash;
    }

    static final class IndexIterator<E> implements Iterator<E> {

        private final List<E> list;

        private int cursor;

        private int last = -1;

        IndexIterator(List<E> list) {
            this.list = list;
        }

        public boolean hasNext() {
            return cursor < list.size();
        }

        public E next() {
            if (cursor >= list.size()) {
                throw new NoSuchElementException();
            }
            last = cursor;
            return list.get(cursor++);
        }

        public void remove() {
            if (last < 0) {
                throw new IllegalStateException();
            }
            list.remove(last);
            cursor = last;
            last = -1;
        }
    }

    static final class SubList<E> extends AbstractList<E> {

        private final List<E> whole;

        private final int offset;

        private int length;

        SubList(List<E> whole, int offset, int length) {
            this.whole = whole;
            this.offset = offset;
            this.length = length;
        }

        private void check(int index, int limit) {
            if (index < 0 || index >= limit) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
            }
        }

        public int size() {
            return length;
        }

        public E get(int index) {
            check(index, length);
            return whole.get(offset + index);
        }

        public E set(int index, E element) {
            check(index, length);
            return whole.set(offset + index, element);
        }

        public void add(int index, E element) {
            check(index, length + 1);
            whole.add(offset + index, element);
            length++;
        }

        public E remove(int index) {
            check(index, length);
            length--;
            return whole.remove(offset + index);
        }
    }
}
