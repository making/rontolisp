// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public interface Set<E> extends Collection<E> {

    boolean add(E e);

    boolean addAll(Collection<? extends E> c);

    @SafeVarargs
    static <E> Set<E> of(E... elements) {
        HashSet<E> held = new HashSet<>();
        for (E element : elements) {
            if (element == null) {
                throw new NullPointerException();
            }
            if (!held.add(element)) {
                throw new IllegalArgumentException("duplicate element: " + element);
            }
        }
        return new ImmutableCollections.SetN<>(held);
    }

    static <E> Set<E> copyOf(Collection<? extends E> c) {
        HashSet<E> held = new HashSet<>();
        for (E element : c) {
            if (element == null) {
                throw new NullPointerException();
            }
            held.add(element);
        }
        return new ImmutableCollections.SetN<>(held);
    }
}
