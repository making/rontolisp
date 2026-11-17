// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface Map<K, V> {

    interface Entry<K, V> {

        K getKey();

        V getValue();

        V setValue(V value);

        static <K extends Comparable<? super K>, V> Comparator<Map.Entry<K, V>> comparingByKey() {
            return (Map.Entry<K, V> a, Map.Entry<K, V> b) -> a.getKey().compareTo(b.getKey());
        }

        static <K, V extends Comparable<? super V>> Comparator<Map.Entry<K, V>> comparingByValue() {
            return (Map.Entry<K, V> a, Map.Entry<K, V> b) -> a.getValue().compareTo(b.getValue());
        }
    }

    int size();

    boolean isEmpty();

    boolean containsKey(Object key);

    boolean containsValue(Object value);

    V get(Object key);

    V put(K key, V value);

    V remove(Object key);

    void putAll(Map<? extends K, ? extends V> m);

    void clear();

    Set<K> keySet();

    Collection<V> values();

    Set<Map.Entry<K, V>> entrySet();

    default V getOrDefault(Object key, V other) {
        V value = get(key);
        return value != null || containsKey(key) ? value : other;
    }

    default void forEach(BiConsumer<? super K, ? super V> action) {
        for (Map.Entry<K, V> entry : entrySet()) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }

    default void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        for (Map.Entry<K, V> entry : entrySet()) {
            entry.setValue(function.apply(entry.getKey(), entry.getValue()));
        }
    }

    default V putIfAbsent(K key, V value) {
        V held = get(key);
        if (held == null) {
            held = put(key, value);
        }
        return held;
    }

    default V replace(K key, V value) {
        if (containsKey(key)) {
            return put(key, value);
        }
        return null;
    }

    default V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        V held = get(key);
        if (held == null) {
            V made = function.apply(key);
            if (made != null) {
                put(key, made);
                return made;
            }
        }
        return held;
    }

    default V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        V held = get(key);
        if (held == null) {
            return null;
        }
        V made = function.apply(key, held);
        if (made == null) {
            remove(key);
        }
        else {
            put(key, made);
        }
        return made;
    }

    default V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        V held = get(key);
        V made = function.apply(key, held);
        if (made == null) {
            if (held != null || containsKey(key)) {
                remove(key);
            }
            return null;
        }
        put(key, made);
        return made;
    }

    default V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        V held = get(key);
        V made = held == null ? value : function.apply(held, value);
        if (made == null) {
            remove(key);
        }
        else {
            put(key, made);
        }
        return made;
    }

    static <K, V> Map.Entry<K, V> entry(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        return new ImmutableCollections.Pair<>(key, value);
    }

    static <K, V> Map<K, V> copyOf(Map<? extends K, ? extends V> m) {
        HashMap<K, V> held = new HashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new NullPointerException();
            }
            held.put(entry.getKey(), entry.getValue());
        }
        return new ImmutableCollections.MapN<>(held);
    }

    static <K, V> Map<K, V> ofEntries(Map.Entry<? extends K, ? extends V>... entries) {
        HashMap<K, V> held = new HashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : entries) {
            if (held.put(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate key: " + entry.getKey());
            }
        }
        return new ImmutableCollections.MapN<>(held);
    }

    static <K, V> Map<K, V> of() {
        return ImmutableCollections.map();
    }

    static <K, V> Map<K, V> of(K k1, V v1) {
        return ImmutableCollections.map(k1, v1);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2) {
        return ImmutableCollections.map(k1, v1, k2, v2);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        return ImmutableCollections.map(k1, v1, k2, v2, k3, v3);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        return ImmutableCollections.map(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        return ImmutableCollections.map(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
        return ImmutableCollections.map(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) {
        return ImmutableCollections.map(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8) {
        return ImmutableCollections.map(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        return ImmutableCollections.map(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
    }

    static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        return ImmutableCollections.map(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10);
    }
}
