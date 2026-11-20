// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public interface SortedMap<K, V> extends SequencedMap<K, V> {

    Comparator<? super K> comparator();

    SortedMap<K, V> subMap(K fromKey, K toKey);

    SortedMap<K, V> headMap(K toKey);

    SortedMap<K, V> tailMap(K fromKey);

    K firstKey();

    K lastKey();

    // The last mapping is the last key's, so not a walk of the reversed view.
    default Map.Entry<K, V> lastEntry() {
        if (isEmpty()) {
            return null;
        }
        K key = lastKey();
        return new ImmutableCollections.Pair<>(key, get(key));
    }

    default Map.Entry<K, V> pollLastEntry() {
        if (isEmpty()) {
            return null;
        }
        K key = lastKey();
        Map.Entry<K, V> taken = new ImmutableCollections.Pair<>(key, get(key));
        remove(key);
        return taken;
    }

    // A sorted map puts a key where its order says: putFirst and putLast refuse, as
    // SequencedMap's do.

    default SortedMap<K, V> reversed() {
        return new ReversedSortedMap<>(this);
    }
}
