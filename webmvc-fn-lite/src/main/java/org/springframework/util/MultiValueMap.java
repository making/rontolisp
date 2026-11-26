/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.util;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** A map of each key to a list of values. */
public interface MultiValueMap<K, V extends @Nullable Object> extends Map<K, List<V>> {

	/** The first value of a key, or {@code null}. */
	@Nullable V getFirst(K key);

	/** Adds a value to the values of a key. */
	void add(K key, @Nullable V value);

	void addAll(K key, List<? extends V> values);

	void addAll(MultiValueMap<K, V> values);

	default void addIfAbsent(K key, @Nullable V value) {
		if (!containsKey(key)) {
			add(key, value);
		}
	}

	/** Makes a value the one value of a key. */
	void set(K key, @Nullable V value);

	void setAll(Map<K, V> values);

	/** A copy holding each key with its first value. */
	Map<K, V> toSingleValueMap();

}
