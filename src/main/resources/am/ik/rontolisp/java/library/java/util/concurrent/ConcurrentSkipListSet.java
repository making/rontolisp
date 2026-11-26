// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.Spliterator;

// A set over the keys of a ConcurrentSkipListMap, or of a view of one: a view of a set
// is a set over the view of its map.
public class ConcurrentSkipListSet<E> extends AbstractSet<E> implements NavigableSet<E>, Cloneable, Serializable {

    ConcurrentNavigableMap<E, Object> map;

    ConcurrentSkipListSet(ConcurrentNavigableMap<E, Object> map) {
        this.map = map;
    }

    public ConcurrentSkipListSet() {
        map = new ConcurrentSkipListMap<>();
    }

    public ConcurrentSkipListSet(Comparator<? super E> comparator) {
        map = new ConcurrentSkipListMap<>(comparator);
    }

    public ConcurrentSkipListSet(Collection<? extends E> c) {
        map = new ConcurrentSkipListMap<>();
        addAll(c);
    }

    public ConcurrentSkipListSet(SortedSet<E> s) {
        map = new ConcurrentSkipListMap<>(s.comparator());
        addAll(s);
    }

    // the same elements over a map of its own, in the order of this set, of a view too
    @SuppressWarnings("unchecked")
    public ConcurrentSkipListSet<E> clone() {
        ConcurrentSkipListSet<E> copy = (ConcurrentSkipListSet<E>) rontolisp.internal.Support.copy(this);
        copy.map = new ConcurrentSkipListMap<>(map);
        return copy;
    }

    // the set over a view of the map
    NavigableSet<E> made(ConcurrentNavigableMap<E, Object> m) {
        return new ConcurrentSkipListSet<>(m);
    }

    public Iterator<E> iterator() {
        return new ConcurrentSkipListMap.Walk<E, Object, E>((Ordered<E, Object>) map, Ordered.KEYS);
    }

    public Iterator<E> descendingIterator() {
        return new ConcurrentSkipListMap.Walk<E, Object, E>((Ordered<E, Object>) map.descendingMap(), Ordered.KEYS);
    }

    public Spliterator<E> spliterator() {
        return ((Ordered<E, Object>) map).keySplit(this);
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
        return map.putIfAbsent(e, Boolean.TRUE) == null;
    }

    public boolean remove(Object o) {
        return map.remove(o) != null;
    }

    public void clear() {
        map.clear();
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
