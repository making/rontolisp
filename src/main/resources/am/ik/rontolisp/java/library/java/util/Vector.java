// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The older list: an ArrayList under its older names.
public class Vector<E> extends ArrayList<E> {

    public Vector() {
    }

    public Vector(int capacity) {
        super(capacity);
    }

    public Vector(Collection<? extends E> c) {
        super(c);
    }

    public ListIterator<E> listIterator(int index) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return super.listIterator(index);
    }

    public void addElement(E e) {
        add(e);
    }

    public E elementAt(int index) {
        return get(index);
    }

    public E firstElement() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(0);
    }

    public E lastElement() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(size() - 1);
    }

    public void setElementAt(E e, int index) {
        set(index, e);
    }

    public void insertElementAt(E e, int index) {
        add(index, e);
    }

    public void removeElementAt(int index) {
        remove(index);
    }

    public boolean removeElement(Object o) {
        return remove(o);
    }

    public void removeAllElements() {
        clear();
    }

    public Enumeration<E> elements() {
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
