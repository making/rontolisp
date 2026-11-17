// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A set over the keys of a HashMap, so in a HashMap's order.
public class HashSet<E> extends AbstractSet<E> {

    private final HashMap<E, Boolean> map;

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

    public Iterator<E> iterator() {
        return map.keySet().iterator();
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
