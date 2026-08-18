package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

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
 */
public final class JvmHashTableShape {

	/** The runtime class of a table, in internal (slash-separated) form. */
	public static final String MAP_CLASS = "java/util/LinkedHashMap";

	/** The runtime class of a bucket and of the insertion-order list. */
	public static final String LIST_CLASS = "java/util/ArrayList";

	/** The key the insertion-order list hangs off inside the table. */
	public static final String ORDER_KEY = "#order";

	private JvmHashTableShape() {
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
		List<Object> bucket = Objects.requireNonNull(bucket(table, key, true));
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
	 * Returns the value stored under an atom key, or null when absent.
	 * @param table the table
	 * @param key the key
	 * @return the stored value, or null
	 */
	public static @Nullable Object get(Map<Object, Object> table, Object key) {
		List<Object> bucket = bucket(table, key, false);
		if (bucket == null) {
			return null;
		}
		for (Object entry : bucket) {
			Object[] pair = (Object[]) entry;
			if (key.equals(pair[0])) {
				return pair[1];
			}
		}
		return null;
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

	@SuppressWarnings("unchecked")
	private static @Nullable List<Object> bucket(Map<Object, Object> table, Object key, boolean create) {
		Integer hash = key.hashCode();
		List<Object> bucket = (List<Object>) table.get(hash);
		if (bucket == null && create) {
			bucket = new ArrayList<>();
			table.put(hash, bucket);
		}
		return bucket;
	}

}
