// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A set over the keys of a LinkedHashMap, so in the order its elements were added.
public class LinkedHashSet<E> extends HashSet<E> {

    public LinkedHashSet() {
        super(new LinkedHashMap<E, Boolean>());
    }

    public LinkedHashSet(int capacity) {
        super(new LinkedHashMap<E, Boolean>(capacity));
    }

    public LinkedHashSet(int capacity, float loadFactor) {
        super(new LinkedHashMap<E, Boolean>(capacity, loadFactor));
    }

    public LinkedHashSet(Collection<? extends E> c) {
        super(new LinkedHashMap<E, Boolean>());
        addAll(c);
    }
}
