/*
 * Third-party: the names and signatures of this class are those of the class of the same
 * name in Spring Framework 7.1 (Apache-2.0), Copyright 2002-present the original author
 * or authors. Changed: a part of the members, their bodies written for this module.
 * Notice: THIRD-PARTY-NOTICES.txt, "Spring Framework".
 */
package org.springframework.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/** A {@link MultiValueMap} that keeps its keys in the order they were added. */
public class LinkedMultiValueMap<K, V> implements MultiValueMap<K, V>, Serializable {

	private static final long serialVersionUID = 1L;

	private final Map<K, List<V>> map;

	public LinkedMultiValueMap() {
		this.map = new LinkedHashMap<>();
	}

	public LinkedMultiValueMap(int expectedSize) {
		this.map = new LinkedHashMap<>();
	}

	/** A copy of a map: the lists are shared, as the original's {@code deepCopy} says. */
	public LinkedMultiValueMap(Map<K, List<V>> otherMap) {
		this.map = new LinkedHashMap<>(otherMap);
	}

	@Override
	public @Nullable V getFirst(K key) {
		List<V> values = this.map.get(key);
		return (values != null && !values.isEmpty()) ? values.get(0) : null;
	}

	@Override
	public void add(K key, @Nullable V value) {
		List<V> values = this.map.get(key);
		if (values == null) {
			values = new ArrayList<>(1);
			this.map.put(key, values);
		}
		values.add(value);
	}

	@Override
	public void addAll(K key, List<? extends V> values) {
		List<V> current = this.map.get(key);
		if (current == null) {
			current = new ArrayList<>(values.size());
			this.map.put(key, current);
		}
		current.addAll(values);
	}

	@Override
	public void addAll(MultiValueMap<K, V> values) {
		for (Map.Entry<K, List<V>> entry : values.entrySet()) {
			addAll(entry.getKey(), entry.getValue());
		}
	}

	@Override
	public void set(K key, @Nullable V value) {
		List<V> values = new ArrayList<>(1);
		values.add(value);
		this.map.put(key, values);
	}

	@Override
	public void setAll(Map<K, V> values) {
		for (Map.Entry<K, V> entry : values.entrySet()) {
			set(entry.getKey(), entry.getValue());
		}
	}

	@Override
	public Map<K, V> toSingleValueMap() {
		Map<K, V> first = new LinkedHashMap<>();
		for (Map.Entry<K, List<V>> entry : this.map.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				first.put(entry.getKey(), entry.getValue().get(0));
			}
		}
		return first;
	}

	/** A copy whose lists are copies too. */
	public LinkedMultiValueMap<K, V> deepCopy() {
		LinkedMultiValueMap<K, V> copy = new LinkedMultiValueMap<>();
		for (Map.Entry<K, List<V>> entry : this.map.entrySet()) {
			copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		return copy;
	}

	@Override
	public int size() {
		return this.map.size();
	}

	@Override
	public boolean isEmpty() {
		return this.map.isEmpty();
	}

	@Override
	public boolean containsKey(Object key) {
		return this.map.containsKey(key);
	}

	@Override
	public boolean containsValue(Object value) {
		return this.map.containsValue(value);
	}

	@Override
	public @Nullable List<V> get(Object key) {
		return this.map.get(key);
	}

	@Override
	public @Nullable List<V> put(K key, List<V> value) {
		return this.map.put(key, value);
	}

	@Override
	public @Nullable List<V> remove(Object key) {
		return this.map.remove(key);
	}

	@Override
	public void putAll(Map<? extends K, ? extends List<V>> values) {
		this.map.putAll(values);
	}

	@Override
	public void clear() {
		this.map.clear();
	}

	@Override
	public Set<K> keySet() {
		return this.map.keySet();
	}

	@Override
	public Collection<List<V>> values() {
		return this.map.values();
	}

	@Override
	public Set<Map.Entry<K, List<V>>> entrySet() {
		return this.map.entrySet();
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || this.map.equals(other);
	}

	@Override
	public int hashCode() {
		return this.map.hashCode();
	}

	@Override
	public String toString() {
		return this.map.toString();
	}

}
