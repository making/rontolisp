// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public interface SortedMap<K, V> extends Map<K, V> {

    Comparator<? super K> comparator();

    SortedMap<K, V> subMap(K fromKey, K toKey);

    SortedMap<K, V> headMap(K toKey);

    SortedMap<K, V> tailMap(K fromKey);

    K firstKey();

    K lastKey();

    default Map.Entry<K, V> firstEntry() {
        Iterator<Map.Entry<K, V>> each = entrySet().iterator();
        if (!each.hasNext()) {
            return null;
        }
        Map.Entry<K, V> entry = each.next();
        return new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue());
    }

    default Map.Entry<K, V> lastEntry() {
        if (isEmpty()) {
            return null;
        }
        K key = lastKey();
        return new AbstractMap.SimpleImmutableEntry<>(key, get(key));
    }

    default Map.Entry<K, V> pollFirstEntry() {
        Iterator<Map.Entry<K, V>> each = entrySet().iterator();
        if (!each.hasNext()) {
            return null;
        }
        Map.Entry<K, V> entry = each.next();
        Map.Entry<K, V> taken = new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue());
        each.remove();
        return taken;
    }

    default Map.Entry<K, V> pollLastEntry() {
        if (isEmpty()) {
            return null;
        }
        K key = lastKey();
        Map.Entry<K, V> taken = new AbstractMap.SimpleImmutableEntry<>(key, get(key));
        remove(key);
        return taken;
    }

    // A sorted map puts a key where its order says.
    default V putFirst(K key, V value) {
        throw new UnsupportedOperationException();
    }

    default V putLast(K key, V value) {
        throw new UnsupportedOperationException();
    }
}
