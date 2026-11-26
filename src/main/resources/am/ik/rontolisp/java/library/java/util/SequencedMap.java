// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A map whose mappings come in an order from end to end. The defaults read the first
// mapping through the entries and the last through the reversed view; an entry they
// answer is a copy nothing changes, a null key or value kept.
public interface SequencedMap<K, V> extends Map<K, V> {

    SequencedMap<K, V> reversed();

    default Map.Entry<K, V> firstEntry() {
        Iterator<Map.Entry<K, V>> each = entrySet().iterator();
        if (!each.hasNext()) {
            return null;
        }
        Map.Entry<K, V> entry = each.next();
        return new ImmutableCollections.Pair<>(entry.getKey(), entry.getValue());
    }

    default Map.Entry<K, V> lastEntry() {
        Iterator<Map.Entry<K, V>> each = reversed().entrySet().iterator();
        if (!each.hasNext()) {
            return null;
        }
        Map.Entry<K, V> entry = each.next();
        return new ImmutableCollections.Pair<>(entry.getKey(), entry.getValue());
    }

    default Map.Entry<K, V> pollFirstEntry() {
        Iterator<Map.Entry<K, V>> each = entrySet().iterator();
        if (!each.hasNext()) {
            return null;
        }
        Map.Entry<K, V> entry = each.next();
        Map.Entry<K, V> taken = new ImmutableCollections.Pair<>(entry.getKey(), entry.getValue());
        each.remove();
        return taken;
    }

    default Map.Entry<K, V> pollLastEntry() {
        Iterator<Map.Entry<K, V>> each = reversed().entrySet().iterator();
        if (!each.hasNext()) {
            return null;
        }
        Map.Entry<K, V> entry = each.next();
        Map.Entry<K, V> taken = new ImmutableCollections.Pair<>(entry.getKey(), entry.getValue());
        each.remove();
        return taken;
    }

    default V putFirst(K key, V value) {
        throw new UnsupportedOperationException();
    }

    default V putLast(K key, V value) {
        throw new UnsupportedOperationException();
    }

    default SequencedSet<K> sequencedKeySet() {
        return new MapSequence.Keys<>(this);
    }

    default SequencedCollection<V> sequencedValues() {
        return new MapSequence.Values<>(this);
    }

    default SequencedSet<Map.Entry<K, V>> sequencedEntrySet() {
        return new MapSequence.Entries<>(this);
    }
}
