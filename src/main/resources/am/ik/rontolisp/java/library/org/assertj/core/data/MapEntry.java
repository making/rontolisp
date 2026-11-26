// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.data;

import java.util.Map;

// A key and a value: what a map is said to contain.
public class MapEntry<K, V> implements Map.Entry<K, V> {

    public final K key;

    public final V value;

    private MapEntry(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public static <K, V> MapEntry<K, V> entry(K key, V value) {
        return new MapEntry<>(key, value);
    }

    @Override
    public K getKey() {
        return key;
    }

    @Override
    public V getValue() {
        return value;
    }

    @Override
    public V setValue(V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Map.Entry<?, ?> entry && java.util.Objects.equals(key, entry.getKey())
                && java.util.Objects.equals(value, entry.getValue());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(key) ^ java.util.Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return rontolisp.internal.Shown.of(key) + "=" + rontolisp.internal.Shown.of(value);
    }
}
