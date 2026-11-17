// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.Map;

public interface ConcurrentMap<K, V> extends Map<K, V> {

    boolean remove(Object key, Object value);

    boolean replace(K key, V oldValue, V newValue);
}
