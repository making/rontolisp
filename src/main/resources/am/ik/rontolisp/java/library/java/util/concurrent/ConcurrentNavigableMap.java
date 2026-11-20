// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.NavigableMap;
import java.util.NavigableSet;

public interface ConcurrentNavigableMap<K, V> extends ConcurrentMap<K, V>, NavigableMap<K, V> {

    ConcurrentNavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive);

    ConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive);

    ConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive);

    ConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey);

    ConcurrentNavigableMap<K, V> headMap(K toKey);

    ConcurrentNavigableMap<K, V> tailMap(K fromKey);

    ConcurrentNavigableMap<K, V> descendingMap();

    NavigableSet<K> navigableKeySet();

    NavigableSet<K> keySet();

    NavigableSet<K> descendingKeySet();

    default ConcurrentNavigableMap<K, V> reversed() {
        return descendingMap();
    }
}
