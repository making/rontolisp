// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A set over the keys of a TreeMap, or of a view of one: a view of a set is a set over
// the view of its map.
public class TreeSet<E> extends AbstractSet<E> implements NavigableSet<E>, Cloneable {

    NavigableMap<E, Object> map;

    TreeSet(NavigableMap<E, Object> map) {
        this.map = map;
    }

    public TreeSet() {
        map = new TreeMap<>();
    }

    public TreeSet(Comparator<? super E> comparator) {
        map = new TreeMap<>(comparator);
    }

    public TreeSet(Collection<? extends E> c) {
        map = new TreeMap<>();
        addAll(c);
    }

    public TreeSet(SortedSet<E> s) {
        map = new TreeMap<>(s.comparator());
        addAll(s);
    }

    // the same elements over a tree of its own, in the order of this set, of a view too
    @SuppressWarnings("unchecked")
    public Object clone() {
        TreeSet<E> copy = (TreeSet<E>) rontolisp.internal.Support.copy(this);
        copy.map = new TreeMap<>(map);
        return copy;
    }

    // the set over a view of the map
    NavigableSet<E> made(NavigableMap<E, Object> m) {
        return new TreeSet<>(m);
    }

    public Iterator<E> iterator() {
        return TreeMap.keys(map, false);
    }

    public Iterator<E> descendingIterator() {
        return TreeMap.keys(map, true);
    }

    public Spliterator<E> spliterator() {
        return TreeMap.keySplit(map, this);
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

    // Into an empty set, the elements of a set in the same order are taken as they come.
    public boolean addAll(Collection<? extends E> c) {
        if (map.isEmpty() && !c.isEmpty() && c instanceof SortedSet<?> sorted && map instanceof TreeMap
                && Objects.equals(sorted.comparator(), map.comparator())) {
            TreeMap<E, Object> tree = (TreeMap<E, Object>) map;
            tree.fill(c.iterator(), c.size(), true, Boolean.TRUE);
            return true;
        }
        return super.addAll(c);
    }

    public Comparator<? super E> comparator() {
        return map.comparator();
    }

    public E first() {
        return map.firstKey();
    }

    public E last() {
        return map.lastKey();
    }

    public E lower(E e) {
        return map.lowerKey(e);
    }

    public E floor(E e) {
        return map.floorKey(e);
    }

    public E ceiling(E e) {
        return map.ceilingKey(e);
    }

    public E higher(E e) {
        return map.higherKey(e);
    }

    public E pollFirst() {
        Map.Entry<E, ?> e = map.pollFirstEntry();
        return e == null ? null : e.getKey();
    }

    public E pollLast() {
        Map.Entry<E, ?> e = map.pollLastEntry();
        return e == null ? null : e.getKey();
    }

    public NavigableSet<E> descendingSet() {
        return made(map.descendingMap());
    }

    public NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
        return made(map.subMap(fromElement, fromInclusive, toElement, toInclusive));
    }

    public NavigableSet<E> headSet(E toElement, boolean inclusive) {
        return made(map.headMap(toElement, inclusive));
    }

    public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
        return made(map.tailMap(fromElement, inclusive));
    }

    public SortedSet<E> subSet(E fromElement, E toElement) {
        return subSet(fromElement, true, toElement, false);
    }

    public SortedSet<E> headSet(E toElement) {
        return headSet(toElement, false);
    }

    public SortedSet<E> tailSet(E fromElement) {
        return tailSet(fromElement, true);
    }
}
