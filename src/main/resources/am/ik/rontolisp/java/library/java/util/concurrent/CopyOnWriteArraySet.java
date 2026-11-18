// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

// A set over a CopyOnWriteArrayList: the elements in the order they were added, a
// read with no lock, an iterator that walks what there was when it was made.
public class CopyOnWriteArraySet<E> extends AbstractSet<E> {

    private final CopyOnWriteArrayList<E> held = new CopyOnWriteArrayList<>();

    public CopyOnWriteArraySet() {
    }

    public CopyOnWriteArraySet(Collection<? extends E> c) {
        this.held.addAllAbsent(c);
    }

    public int size() {
        return this.held.size();
    }

    public boolean isEmpty() {
        return this.held.isEmpty();
    }

    public boolean contains(Object o) {
        return this.held.contains(o);
    }

    public boolean add(E e) {
        return this.held.addIfAbsent(e);
    }

    public boolean addAll(Collection<? extends E> c) {
        return this.held.addAllAbsent(c) > 0;
    }

    public boolean remove(Object o) {
        return this.held.remove(o);
    }

    public boolean removeIf(Predicate<? super E> filter) {
        return this.held.removeIf(filter);
    }

    public void clear() {
        this.held.clear();
    }

    public Object[] toArray() {
        return this.held.toArray();
    }

    public void forEach(Consumer<? super E> action) {
        this.held.forEach(action);
    }

    public Iterator<E> iterator() {
        return this.held.iterator();
    }
}
