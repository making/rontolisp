// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import rontolisp.internal.Splits;

// A list over get and size; one that changes also has set, add at an index and remove
// at an index.
public abstract class AbstractList<E> extends AbstractCollection<E> implements List<E> {

    // how many times the list changed size: a list of a program that changes keeps it up,
    // and a walk or a sublist made before refuses once it moved
    protected int modCount;

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
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }
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
        removeRange(0, size());
    }

    // what clear() and the clear of a part call: the elements from..to removed, each by
    // remove(from)
    protected void removeRange(int from, int to) {
        if (from < 0 || from > size()) {
            throw new IndexOutOfBoundsException("Index: " + from + ", Size: " + size());
        }
        for (int i = to - from; i > 0; i--) {
            remove(from);
        }
    }

    public Iterator<E> iterator() {
        return new IndexIterator<>(this);
    }

    public ListIterator<E> listIterator() {
        return listIterator(0);
    }

    public ListIterator<E> listIterator(int index) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }
        return new IndexWalk<>(this, index);
    }

    public List<E> subList(int from, int to) {
        partRange(from, to, size());
        return new SubList<>(this, from, to - from);
    }

    static void partRange(int from, int to, int size) {
        if (from < 0) {
            throw new IndexOutOfBoundsException("fromIndex = " + from);
        }
        if (to > size) {
            throw new IndexOutOfBoundsException("toIndex = " + to);
        }
        if (from > to) {
            throw new IllegalArgumentException("fromIndex(" + from + ") > toIndex(" + to + ")");
        }
    }

    // the size a walk of this list stops at: a part's own, which a change behind it does not move
    int walkEnd() {
        return size();
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

        private final AbstractList<E> list;

        private int cursor;

        private int last = -1;

        private int expected;

        IndexIterator(AbstractList<E> list) {
            this.list = list;
            this.expected = list.modCount;
        }

        public boolean hasNext() {
            return cursor != list.walkEnd();
        }

        public E next() {
            if (list.modCount != expected) {
                throw new ConcurrentModificationException();
            }
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
            if (list.modCount != expected) {
                throw new ConcurrentModificationException();
            }
            list.remove(last);
            cursor = last;
            last = -1;
            expected = list.modCount;
        }
    }

    // An iterator both ways over get, set, add and remove at an index.
    static final class IndexWalk<E> implements ListIterator<E> {

        private final AbstractList<E> list;

        private int cursor;

        // the element next() or previous() gave last, -1 once it was removed or one added
        private int last = -1;

        private int expected;

        IndexWalk(AbstractList<E> list, int index) {
            this.list = list;
            this.cursor = index;
            this.expected = list.modCount;
        }

        private void unchanged() {
            if (list.modCount != expected) {
                throw new ConcurrentModificationException();
            }
        }

        public boolean hasNext() {
            return cursor != list.walkEnd();
        }

        public E next() {
            unchanged();
            if (cursor >= list.size()) {
                throw new NoSuchElementException();
            }
            last = cursor;
            return list.get(cursor++);
        }

        public boolean hasPrevious() {
            return cursor != 0;
        }

        public E previous() {
            unchanged();
            if (cursor <= 0) {
                throw new NoSuchElementException();
            }
            last = --cursor;
            return list.get(cursor);
        }

        public int nextIndex() {
            return cursor;
        }

        public int previousIndex() {
            return cursor - 1;
        }

        public void remove() {
            if (last < 0) {
                throw new IllegalStateException();
            }
            unchanged();
            list.remove(last);
            cursor = last;
            last = -1;
            expected = list.modCount;
        }

        public void set(E e) {
            if (last < 0) {
                throw new IllegalStateException();
            }
            unchanged();
            list.set(last, e);
            expected = list.modCount;
        }

        public void add(E e) {
            unchanged();
            list.add(cursor++, e);
            last = -1;
            expected = list.modCount;
        }
    }

    // A part of a list, in place: a change through it is the list's, and a change made to the
    // list some other way refuses it. The count it keeps is the list's as it saw it last.
    static final class SubList<E> extends AbstractList<E> {

        private final AbstractList<E> whole;

        // the list itself, the part of a part included
        private final AbstractList<?> root;

        private final int offset;

        private int length;

        // a part of a list of random access, or of a part of one
        private final boolean random;

        SubList(AbstractList<E> whole, int offset, int length) {
            this.whole = whole;
            this.root = whole instanceof SubList<?> part ? part.root : whole;
            this.offset = offset;
            this.length = length;
            this.random = whole instanceof RandomAccess || whole instanceof SubList<?> part && part.random;
            this.modCount = root.modCount;
        }

        private void fresh() {
            if (root.modCount != modCount) {
                throw new ConcurrentModificationException();
            }
        }

        private void inside(int index) {
            if (index < 0 || index >= length) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
            }
        }

        public Spliterator<E> spliterator() {
            fresh();
            int marks = Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED;
            // the size it has now, which a change made after answers no differently
            return random ? Splits.counted(this, marks, length) : Splits.of(this, marks);
        }

        public int size() {
            fresh();
            return length;
        }

        int walkEnd() {
            return length;
        }

        public List<E> subList(int from, int to) {
            partRange(from, to, length);
            return new SubList<>(this, from, to - from);
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            if (index < 0 || index > length) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + length);
            }
            if (c.isEmpty()) {
                return false;
            }
            return super.addAll(index, c);
        }

        protected void removeRange(int from, int to) {
            fresh();
            whole.removeRange(offset + from, offset + to);
            modCount = root.modCount;
            length -= to - from;
        }

        public Iterator<E> iterator() {
            fresh();
            return super.iterator();
        }

        public E get(int index) {
            inside(index);
            fresh();
            return whole.get(offset + index);
        }

        public E set(int index, E element) {
            inside(index);
            fresh();
            return whole.set(offset + index, element);
        }

        public void add(int index, E element) {
            if (index < 0 || index > length) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + length);
            }
            fresh();
            whole.add(offset + index, element);
            modCount = root.modCount;
            length++;
        }

        public E remove(int index) {
            inside(index);
            fresh();
            E old = whole.remove(offset + index);
            modCount = root.modCount;
            length--;
            return old;
        }
    }
}
