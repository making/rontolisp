// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import rontolisp.internal.Splits;

// The view List.reversed() answers: its index i is the list's size - 1 - i, read and
// changed through the list's public members, and its walks are the list's own iterator
// walked back. An index is checked against the view before the list is asked; the view
// of a list nothing changes (ImmutableCollections.ListN) refuses every change at once, as
// that list does.
class ReversedList<E> extends AbstractList<E> {

    final List<E> list;

    private final boolean changing;

    ReversedList(List<E> list, boolean changing) {
        this.list = list;
        this.changing = changing;
    }

    static <E> List<E> of(List<E> list, boolean changing) {
        return list instanceof RandomAccess ? new Indexed<>(list, changing) : new ReversedList<>(list, changing);
    }

    // the view of a list of random access is one
    static final class Indexed<E> extends ReversedList<E> implements RandomAccess {

        Indexed(List<E> list, boolean changing) {
            super(list, changing);
        }
    }

    private void change() {
        if (!changing) {
            throw new UnsupportedOperationException();
        }
    }

    private static void position(int index, int size) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    public List<E> reversed() {
        return list;
    }

    public int size() {
        return list.size();
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public boolean contains(Object o) {
        return list.contains(o);
    }

    public boolean containsAll(Collection<?> c) {
        return list.containsAll(c);
    }

    public E get(int index) {
        int size = list.size();
        Objects.checkIndex(index, size);
        return list.get(size - 1 - index);
    }

    public E set(int index, E element) {
        change();
        int size = list.size();
        Objects.checkIndex(index, size);
        return list.set(size - 1 - index, element);
    }

    public boolean add(E e) {
        change();
        list.add(0, e);
        return true;
    }

    public void add(int index, E element) {
        change();
        int size = list.size();
        position(index, size);
        list.add(size - index, element);
    }

    public boolean addAll(Collection<? extends E> c) {
        change();
        Object[] added = c.toArray();
        for (Object e : added) {
            list.add(0, (E) e);
        }
        return added.length > 0;
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        change();
        position(index, list.size());
        Object[] added = c.toArray();
        int at = index;
        for (Object e : added) {
            list.add(list.size() - at, (E) e);
            at++;
        }
        return added.length > 0;
    }

    public E remove(int index) {
        change();
        int size = list.size();
        Objects.checkIndex(index, size);
        return list.remove(size - 1 - index);
    }

    public boolean remove(Object o) {
        change();
        int index = list.lastIndexOf(o);
        if (index < 0) {
            return false;
        }
        list.remove(index);
        return true;
    }

    public int indexOf(Object o) {
        int index = list.lastIndexOf(o);
        return index < 0 ? -1 : list.size() - 1 - index;
    }

    public int lastIndexOf(Object o) {
        int index = list.indexOf(o);
        return index < 0 ? -1 : list.size() - 1 - index;
    }

    public void addFirst(E e) {
        change();
        list.addLast(e);
    }

    public void addLast(E e) {
        change();
        list.addFirst(e);
    }

    public E getFirst() {
        return list.getLast();
    }

    public E getLast() {
        return list.getFirst();
    }

    // an end of nothing is refused before a change is
    public E removeFirst() {
        if (list.isEmpty()) {
            throw new NoSuchElementException();
        }
        change();
        return list.removeLast();
    }

    public E removeLast() {
        if (list.isEmpty()) {
            throw new NoSuchElementException();
        }
        change();
        return list.removeFirst();
    }

    public void clear() {
        change();
        list.clear();
    }

    public boolean removeIf(Predicate<? super E> filter) {
        change();
        return list.removeIf(filter);
    }

    // these two walk the view and remove through its iterator, as a JDK's view does
    public boolean removeAll(Collection<?> c) {
        change();
        return super.removeAll(c);
    }

    public boolean retainAll(Collection<?> c) {
        change();
        return super.retainAll(c);
    }

    public void replaceAll(UnaryOperator<E> operator) {
        change();
        list.replaceAll(operator);
    }

    public void sort(Comparator<? super E> c) {
        change();
        list.sort(Collections.reverseOrder(c));
    }

    public Iterator<E> iterator() {
        return listIterator(0);
    }

    public ListIterator<E> listIterator(int index) {
        int size = list.size();
        position(index, size);
        return new Backward<>(list, list.listIterator(size - index), changing);
    }

    public List<E> subList(int from, int to) {
        int size = list.size();
        Objects.checkFromToIndex(from, to, size);
        return new ReversedList<>(list.subList(size - to, size - from), changing);
    }

    public Spliterator<E> spliterator() {
        return Splits.of(this, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    // The list's own iterator walked the other way.
    static final class Backward<E> implements ListIterator<E> {

        private final List<E> list;

        private final ListIterator<E> each;

        private final boolean changing;

        // an element was added since the last step: none to set or remove
        private boolean added;

        Backward(List<E> list, ListIterator<E> each, boolean changing) {
            this.list = list;
            this.each = each;
            this.changing = changing;
        }

        private void change() {
            if (!changing) {
                throw new UnsupportedOperationException();
            }
        }

        public boolean hasNext() {
            return each.hasPrevious();
        }

        public E next() {
            added = false;
            return each.previous();
        }

        public boolean hasPrevious() {
            return each.hasNext();
        }

        public E previous() {
            added = false;
            return each.next();
        }

        public int nextIndex() {
            return list.size() - each.nextIndex();
        }

        public int previousIndex() {
            return list.size() - each.nextIndex() - 1;
        }

        public void remove() {
            change();
            if (added) {
                throw new IllegalStateException();
            }
            each.remove();
        }

        public void set(E e) {
            change();
            if (added) {
                throw new IllegalStateException();
            }
            each.set(e);
        }

        // in the list's order the element goes after the one next() gives, so the
        // list's iterator steps back over it
        public void add(E e) {
            change();
            each.add(e);
            each.previous();
            added = true;
        }
    }
}
