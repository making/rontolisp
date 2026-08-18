package am.ik.rontolisp;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * A hash table value (Common Lisp {@code hash-table}).
 *
 * <p>
 * Keys are compared structurally, with hashing and comparison separated the way a hash
 * table separates them: a key is placed by {@link LispEquality#hash} (a depth-capped fold
 * over its structure) and decided by {@link LispEquality#equal} against the other keys in
 * its bucket. Both compiled backends reproduce that pair, so all four backends agree on
 * which keys are one key. A CYCLIC key is therefore usable -- the cap bounds the hash and
 * {@code equal} answers on identity -- where keying on the printed text of the key never
 * terminated.
 *
 * <p>
 * Insertion order is preserved for {@code maphash}, but portable programs should not rely
 * on iteration order (the JVM/WASM backends do not guarantee it).
 */
public final class LispHashTable implements LispVal {

	/**
	 * One stored entry: the original key object (so {@code maphash} can hand it back) and
	 * its value.
	 *
	 * @param key the original key value
	 * @param value the stored value
	 */
	public record Entry(LispVal key, LispVal value) {
	}

	/**
	 * The unreadable-object prefix every backend prints before a table's entry count. The
	 * test is always {@code EQUAL} -- lookup is structural on every backend, so that is
	 * the test the table actually implements and the one {@code hash-table-test} reports;
	 * SBCL's trailing identity hash is deliberately absent (it would vary between runs of
	 * one program).
	 */
	public static final String HASH_TABLE_PREFIX = "#<HASH-TABLE :TEST EQUAL :COUNT ";

	private final boolean equalTest;

	private final LinkedHashMap<Key, Entry> map = new LinkedHashMap<>();

	/**
	 * A key as the backing {@link LinkedHashMap} sees it: the Lisp value plus its
	 * precomputed structural hash. Bucket membership is {@link LispEquality#hash} and
	 * bucket comparison is {@link LispEquality#equal}, which is the whole point -- the
	 * value's own {@code hashCode} recurses without a bound.
	 */
	private record Key(LispVal val, int hash) {

		static Key of(LispVal val) {
			return new Key(val, LispEquality.hash(val));
		}

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key other && LispEquality.equal(this.val, other.val);
		}
	}

	/**
	 * Creates an empty hash table.
	 * @param equalTest {@code true} if created with {@code :test 'equal}, {@code false}
	 * for the default {@code eql} (informational only; lookup is always structural)
	 */
	public LispHashTable(boolean equalTest) {
		this.equalTest = equalTest;
	}

	/**
	 * Returns the value stored under {@code key}, or {@code dflt} if absent.
	 * @param key the lookup key
	 * @param dflt the value to return when the key is not present
	 * @return the stored value, or {@code dflt}
	 */
	public LispVal get(LispVal key, LispVal dflt) {
		Entry e = this.map.get(Key.of(key));
		return (e == null) ? dflt : e.value();
	}

	/**
	 * Stores {@code value} under {@code key}, replacing any existing entry.
	 * @param key the key
	 * @param value the value to store
	 * @return the stored value
	 */
	public LispVal put(LispVal key, LispVal value) {
		this.map.put(Key.of(key), new Entry(key, value));
		return value;
	}

	/**
	 * Removes the entry for {@code key}, if any.
	 * @param key the key to remove
	 * @return {@code true} if an entry was removed
	 */
	public boolean remove(LispVal key) {
		return this.map.remove(Key.of(key)) != null;
	}

	/**
	 * Removes all entries.
	 */
	public void clear() {
		this.map.clear();
	}

	/**
	 * Returns the number of entries.
	 * @return the entry count
	 */
	public int count() {
		return this.map.size();
	}

	/**
	 * Returns the stored entries in insertion order.
	 * @return the entries
	 */
	public Collection<Entry> entries() {
		return this.map.values();
	}

	@Override
	public String print() {
		return HASH_TABLE_PREFIX + count() + ">";
	}

}
