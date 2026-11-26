// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A list over its list iterator: every access at an index walks to it.
public abstract class AbstractSequentialList<E> extends AbstractList<E> {

    protected AbstractSequentialList() {
    }

    public abstract ListIterator<E> listIterator(int index);

    public Iterator<E> iterator() {
        return listIterator();
    }

    // The iterator at an index, whose next() answers the element there.
    private ListIterator<E> at(int index) {
        ListIterator<E> walk = listIterator(index);
        if (!walk.hasNext()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return walk;
    }

    public E get(int index) {
        return at(index).next();
    }

    public E set(int index, E element) {
        ListIterator<E> walk = at(index);
        E old = walk.next();
        walk.set(element);
        return old;
    }

    public void add(int index, E element) {
        listIterator(index).add(element);
    }

    public E remove(int index) {
        ListIterator<E> walk = at(index);
        E old = walk.next();
        walk.remove();
        return old;
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        ListIterator<E> walk = listIterator(index);
        boolean added = false;
        for (E e : c) {
            walk.add(e);
            added = true;
        }
        return added;
    }
}
