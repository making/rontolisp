// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

// The older list: an ArrayList under its older names, every member of which holds the
// list's monitor. An iterator holds it for no longer than the member it calls. An index
// outside it is refused with an ArrayIndexOutOfBoundsException in each member's own words.
// A JDK words a negative index by the capacity of its array, which this list does not
// keep: the words here say the size.
public class Vector<E> extends ArrayList<E> {

    public Vector() {
    }

    public Vector(int capacity) {
        super(capacity);
    }

    public Vector(Collection<? extends E> c) {
        super(c);
    }

    public synchronized Object clone() {
        return super.clone();
    }

    public synchronized int size() {
        return super.size();
    }

    public synchronized boolean isEmpty() {
        return super.isEmpty();
    }

    // the refusal of get, set and remove(int): past the end names the index, below zero
    // names the array
    private ArrayIndexOutOfBoundsException named(int index) {
        return index < 0 ? new ArrayIndexOutOfBoundsException(inArray(index)) : new ArrayIndexOutOfBoundsException(index);
    }

    private ArrayIndexOutOfBoundsException compared(int index) {
        return index < 0 ? new ArrayIndexOutOfBoundsException(inArray(index))
                : new ArrayIndexOutOfBoundsException(index + " >= " + size());
    }

    private String inArray(int index) {
        return "Index " + index + " out of bounds for length " + size();
    }

    public synchronized E get(int index) {
        if (index < 0 || index >= size()) {
            throw named(index);
        }
        return super.get(index);
    }

    public synchronized E set(int index, E element) {
        if (index < 0 || index >= size()) {
            throw named(index);
        }
        return super.set(index, element);
    }

    public synchronized boolean add(E e) {
        return super.add(e);
    }

    // an index below zero reaches the copy that opens the gap, which refuses in its own words
    private ArrayIndexOutOfBoundsException beyond(int index) {
        return index < 0
                ? new ArrayIndexOutOfBoundsException("arraycopy: source index " + index + " out of bounds for object array["
                        + size() + "]")
                : new ArrayIndexOutOfBoundsException(index + " > " + size());
    }

    public synchronized void add(int index, E element) {
        if (index < 0 || index > size()) {
            throw beyond(index);
        }
        super.add(index, element);
    }

    public synchronized E remove(int index) {
        if (index < 0 || index >= size()) {
            throw named(index);
        }
        return super.remove(index);
    }

    public synchronized boolean remove(Object o) {
        return super.remove(o);
    }

    public synchronized int indexOf(Object o) {
        return super.indexOf(o);
    }

    public synchronized int lastIndexOf(Object o) {
        return super.lastIndexOf(o);
    }

    public synchronized boolean contains(Object o) {
        return super.contains(o);
    }

    public synchronized boolean containsAll(Collection<?> c) {
        return super.containsAll(c);
    }

    public synchronized void clear() {
        super.clear();
    }

    public synchronized boolean addAll(Collection<? extends E> c) {
        return super.addAll(c);
    }

    public synchronized boolean addAll(int index, Collection<? extends E> c) {
        if (index < 0 || index > size()) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return super.addAll(index, c);
    }

    public synchronized boolean removeAll(Collection<?> c) {
        return super.removeAll(c);
    }

    public synchronized boolean retainAll(Collection<?> c) {
        return super.retainAll(c);
    }

    public synchronized boolean removeIf(Predicate<? super E> filter) {
        boolean removed = false;
        Iterator<E> each = iterator();
        while (each.hasNext()) {
            if (filter.test(each.next())) {
                each.remove();
                removed = true;
            }
        }
        return removed;
    }

    public synchronized void replaceAll(UnaryOperator<E> operator) {
        super.replaceAll(operator);
    }

    public synchronized Object[] toArray() {
        return super.toArray();
    }

    public synchronized <T> T[] toArray(T[] a) {
        return super.toArray(a);
    }

    public synchronized void sort(Comparator<? super E> c) {
        super.sort(c);
    }

    public synchronized void forEach(Consumer<? super E> action) {
        super.forEach(action);
    }

    public synchronized void ensureCapacity(int capacity) {
        super.ensureCapacity(capacity);
    }

    public synchronized void trimToSize() {
        super.trimToSize();
    }

    public synchronized boolean equals(Object o) {
        return super.equals(o);
    }

    public synchronized int hashCode() {
        return super.hashCode();
    }

    public synchronized String toString() {
        return super.toString();
    }

    public synchronized ListIterator<E> listIterator(int index) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return super.listIterator(index);
    }

    public synchronized void addElement(E e) {
        add(e);
    }

    public synchronized E elementAt(int index) {
        if (index < 0 || index >= size()) {
            throw compared(index);
        }
        return super.get(index);
    }

    public synchronized E firstElement() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(0);
    }

    public synchronized E lastElement() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(size() - 1);
    }

    public synchronized void setElementAt(E e, int index) {
        if (index < 0 || index >= size()) {
            throw compared(index);
        }
        super.set(index, e);
    }

    public synchronized void insertElementAt(E e, int index) {
        add(index, e);
    }

    public synchronized void removeElementAt(int index) {
        if (index < 0) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        if (index >= size()) {
            throw compared(index);
        }
        super.remove(index);
    }

    public synchronized boolean removeElement(Object o) {
        return remove(o);
    }

    public synchronized void removeAllElements() {
        clear();
    }

    public synchronized Enumeration<E> elements() {
        return new Elements<>(iterator());
    }

    static final class Elements<E> implements Enumeration<E> {

        private final Iterator<E> each;

        Elements(Iterator<E> each) {
            this.each = each;
        }

        public boolean hasMoreElements() {
            return each.hasNext();
        }

        public E nextElement() {
            return each.next();
        }
    }
}
