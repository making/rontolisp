// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import rontolisp.internal.Splits;

// A set over the keys of a HashMap, so in a HashMap's order.
public class HashSet<E> extends AbstractSet<E> implements Cloneable {

    HashMap<E, Boolean> map;

    public HashSet() {
        map = new HashMap<>();
    }

    public HashSet(int capacity) {
        map = new HashMap<>(capacity);
    }

    public HashSet(Collection<? extends E> c) {
        // room for them all, and for twelve at least, under three quarters
        int held = c.size() < 12 ? 12 : c.size();
        map = new HashMap<>((held * 4 + 2) / 3);
        for (E e : c) {
            map.put(e, Boolean.TRUE);
        }
    }

    // the same elements over a clone of the map
    @SuppressWarnings("unchecked")
    public Object clone() {
        HashSet<E> copy = (HashSet<E>) rontolisp.internal.Support.copy(this);
        copy.map = (HashMap<E, Boolean>) map.clone();
        return copy;
    }

    // A LinkedHashSet's: over a LinkedHashMap.
    HashSet(HashMap<E, Boolean> map) {
        this.map = map;
    }

    public static <T> HashSet<T> newHashSet(int numElements) {
        return new HashSet<>(capacityFor(numElements));
    }

    // what newHashSet and its kin ask for: room for so many under three quarters
    static int capacityFor(int elements) {
        if (elements < 0) {
            throw new IllegalArgumentException("Negative number of elements: " + elements);
        }
        return (int) Math.ceil(elements / 0.75);
    }

    public Iterator<E> iterator() {
        return map.keySet().iterator();
    }

    public Spliterator<E> spliterator() {
        return Splits.of(this, Spliterator.DISTINCT | Spliterator.SIZED);
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    public boolean add(E e) {
        return map.put(e, Boolean.TRUE) == null;
    }

    public boolean remove(Object o) {
        return map.remove(o) != null;
    }

    public void clear() {
        map.clear();
    }
}
