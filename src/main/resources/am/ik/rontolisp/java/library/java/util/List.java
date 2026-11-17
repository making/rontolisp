// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.UnaryOperator;

public interface List<E> extends Collection<E> {

    E get(int index);

    E set(int index, E element);

    void add(int index, E element);

    boolean addAll(int index, Collection<? extends E> c);

    E remove(int index);

    int indexOf(Object o);

    int lastIndexOf(Object o);

    List<E> subList(int from, int to);

    default void sort(Comparator<? super E> c) {
        Object[] sorted = toArray();
        Arrays.sortObjects(sorted, 0, sorted.length, c);
        for (int i = 0; i < sorted.length; i++) {
            set(i, (E) sorted[i]);
        }
    }

    default void replaceAll(UnaryOperator<E> operator) {
        int n = size();
        for (int i = 0; i < n; i++) {
            set(i, operator.apply(get(i)));
        }
    }

    default E getFirst() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(0);
    }

    default E getLast() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(size() - 1);
    }

    default E removeFirst() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return remove(0);
    }

    default E removeLast() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return remove(size() - 1);
    }

    default void addFirst(E e) {
        add(0, e);
    }

    default void addLast(E e) {
        add(e);
    }

    default List<E> reversed() {
        List<E> out = new ArrayList<>(size());
        for (int i = size() - 1; i >= 0; i--) {
            out.add(get(i));
        }
        return out;
    }

    static <E> List<E> of(E... elements) {
        Object[] held = new Object[elements.length];
        for (int i = 0; i < held.length; i++) {
            if (elements[i] == null) {
                throw new NullPointerException();
            }
            held[i] = elements[i];
        }
        return new ImmutableCollections.ListN<>(held);
    }

    static <E> List<E> copyOf(Collection<? extends E> c) {
        Object[] held = c.toArray();
        for (int i = 0; i < held.length; i++) {
            if (held[i] == null) {
                throw new NullPointerException();
            }
        }
        return new ImmutableCollections.ListN<>(held);
    }
}
