package am.ik.rontolisp.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The runtime SHAPE of a hash table in the JVM backend's value representation, declared
 * once for the two parties that must agree on it: the emitter of the {@code _hash*}
 * helpers, and the hand-written Java runtimes that build a table for emitted code to read
 * (today the served-request environment).
 *
 * <p>
 * A table is a {@link #MAP_CLASS} used as a BUCKET INDEX: the boxed {@code Integer}
 * structural hash of a key maps to a {@link #LIST_CLASS} of {@code Object[2]} pairs
 * (original key, stored value), which the lookup scans with the recursive {@code _equal}.
 * Insertion order -- what {@code maphash} walks -- is a second {@link #LIST_CLASS} of the
 * same pairs, hanging off {@link #ORDER_KEY}; a String key can never collide with an
 * {@code Integer} bucket key, so the whole table stays ONE object that
 * {@code hash-table-p} and the printer recognise by its class alone.
 *
 * <p>
 * The class is exact, not merely map-shaped: the emitted helpers cast to it, so a plain
 * {@code HashMap} built here would fail the cast at the first {@code gethash} (pinned by
 * {@code JvmHashRuntimeBuilderTest}).
 *
 * <p>
 * It lives in {@code runtime} because {@link RontoHttpClack} builds a table at RUN time
 * and travels with a compiled program ({@code .kb/jvm-export.md}); like everything in
 * this package it therefore imports nothing but {@code java.base} -- not even the build's
 * {@code @Nullable}, whose class file reference would follow the class into a consumer's
 * artifact. That is why {@link #get} takes the absent value instead of answering null.
 */
public final class RontoHashTable {

	/** The runtime class of a table, in internal (slash-separated) form. */
	public static final String MAP_CLASS = "java/util/LinkedHashMap";

	/** The runtime class of a bucket and of the insertion-order list. */
	public static final String LIST_CLASS = "java/util/ArrayList";

	/** The key the insertion-order list hangs off inside the table. */
	public static final String ORDER_KEY = "#order";

	private RontoHashTable() {
	}

	/**
	 * Builds an empty table.
	 * @return the table
	 */
	public static LinkedHashMap<Object, Object> newTable() {
		LinkedHashMap<Object, Object> table = new LinkedHashMap<>();
		table.put(ORDER_KEY, new ArrayList<>());
		return table;
	}

	/**
	 * Stores {@code value} under an ATOM key -- a value whose structural hash is its own
	 * {@code hashCode} and whose {@code equal} is its own {@code equals} (a string, a
	 * symbol, a number), which is every key a hand-written runtime builds. An aggregate
	 * key would need the emitted {@code _hash}/{@code _equal} pair and belongs in Lisp.
	 * @param table the table
	 * @param key the key
	 * @param value the value to store
	 */
	public static void put(Map<Object, Object> table, Object key, Object value) {
		List<Object> bucket = writeBucket(table, key);
		for (Object entry : bucket) {
			Object[] pair = (Object[]) entry;
			if (key.equals(pair[0])) {
				pair[1] = value;
				return;
			}
		}
		Object[] pair = { key, value };
		bucket.add(pair);
		order(table).add(pair);
	}

	/**
	 * Returns the value stored under an atom key, or {@code ifAbsent} when the key is not
	 * in the table. The absent value is a PARAMETER rather than a null return because
	 * this package carries no annotation of any kind -- see the class javadoc.
	 * @param table the table
	 * @param key the key
	 * @param ifAbsent what to answer when the key is absent
	 * @return the stored value, or {@code ifAbsent}
	 */
	public static Object get(Map<Object, Object> table, Object key, Object ifAbsent) {
		for (Object entry : readBucket(table, key)) {
			Object[] pair = (Object[]) entry;
			if (key.equals(pair[0])) {
				return pair[1];
			}
		}
		return ifAbsent;
	}

	/**
	 * The entry pairs in insertion order -- the list {@code maphash} walks.
	 * @param table the table
	 * @return the insertion-order list
	 */
	@SuppressWarnings("unchecked")
	public static List<Object> order(Map<Object, Object> table) {
		return (List<Object>) Objects.requireNonNull(table.get(ORDER_KEY));
	}

	// The bucket to SCAN: empty (and immutable) when the key has none yet.
	@SuppressWarnings("unchecked")
	private static List<Object> readBucket(Map<Object, Object> table, Object key) {
		Object bucket = table.get(Integer.valueOf(key.hashCode()));
		return bucket == null ? List.of() : (List<Object>) bucket;
	}

	// The bucket to APPEND to, created on first use.
	@SuppressWarnings("unchecked")
	private static List<Object> writeBucket(Map<Object, Object> table, Object key) {
		Integer hash = key.hashCode();
		Object bucket = table.get(hash);
		if (bucket == null) {
			// Room for one entry, like the emitted _hashPut: a bucket holds exactly
			// one pair unless two structurally distinct keys hash alike.
			List<Object> created = new ArrayList<>(1);
			table.put(hash, created);
			return created;
		}
		return (List<Object>) bucket;
	}

}
