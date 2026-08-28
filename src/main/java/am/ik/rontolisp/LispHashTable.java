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
 * A table made with {@code :test 'equalp} FOLDS each key first
 * ({@link LispEquality#equalpKey}), which is how one structural table carries both tests;
 * see {@link #placed}.
 *
 * <p>
 * Insertion order is preserved for {@code maphash}, but portable programs should not rely
 * on iteration order (the JVM/WASM backends do not guarantee it).
 */
public final class LispHashTable implements LispVal {

	/**
	 * One stored entry: the key as it was PLACED (so {@code maphash} can hand it back)
	 * and its value. For an {@code equalp} table that is the folded key, not the spelling
	 * the caller stored it under -- see {@link #placed}.
	 *
	 * @param key the placed key value
	 * @param value the stored value
	 */
	public record Entry(LispVal key, LispVal value) {
	}

	/**
	 * The unreadable-object prefix every backend prints before an {@code equal} table's
	 * entry count. {@code EQUAL} is the test lookup implements for every table that is
	 * not an {@code equalp} one -- an {@code eql} table still matches structurally
	 * ({@code .todo/012}) -- and it is what {@code hash-table-test} answers for them;
	 * SBCL's trailing identity hash is deliberately absent (it would vary between runs of
	 * one program).
	 */
	public static final String HASH_TABLE_PREFIX = "#<HASH-TABLE :TEST EQUAL :COUNT ";

	/**
	 * The same prefix for an {@code equalp} table, whose keys are folded
	 * ({@link LispEquality#equalpKey}) before they are placed. Two whole constants rather
	 * than one assembled at run time: each backend interns only the one it can print, so
	 * a program with no {@code equalp} table carries exactly the bytes it carried before
	 * the fold existed.
	 */
	public static final String HASH_TABLE_PREFIX_EQUALP = "#<HASH-TABLE :TEST EQUALP :COUNT ";

	private final boolean equalpTest;

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
	 * Creates an empty {@code equal} hash table -- the structural placement every table
	 * that is not {@code equalp} uses.
	 */
	public LispHashTable() {
		this(false);
	}

	/**
	 * Creates an empty hash table with the given test.
	 * @param equalpTest {@code true} when the table was created with
	 * {@code :test 'equalp}, whose keys are folded ({@link LispEquality#equalpKey})
	 * before they are placed; {@code false} for every other test, all of which place
	 * structurally
	 */
	public LispHashTable(boolean equalpTest) {
		this.equalpTest = equalpTest;
	}

	/**
	 * Whether this table places its keys by the {@code equalp} fold -- what
	 * {@code hash-table-test} and the printed {@code :TEST} field report.
	 * @return {@code true} for a table made with {@code :test 'equalp}
	 */
	public boolean equalpTest() {
		return this.equalpTest;
	}

	/**
	 * The value this table PLACES {@code val} under: {@code val} itself, or its
	 * {@code equalp} fold. The folded value is also what is STORED as the entry's key --
	 * a bucket decides by {@code equal} against the keys already in it, so the fold has
	 * to be what is there, and {@code maphash} therefore hands back the representative
	 * rather than the spelling the caller stored it under. Every backend does the same;
	 * keeping the original as well would cost a second slot in every entry of every table
	 * ({@code .kb/hash-tables.md}).
	 */
	private LispVal placed(LispVal val) {
		return this.equalpTest ? LispEquality.equalpKey(val) : val;
	}

	/**
	 * Returns the value stored under {@code key}, or {@code dflt} if absent.
	 * @param key the lookup key
	 * @param dflt the value to return when the key is not present
	 * @return the stored value, or {@code dflt}
	 */
	public LispVal get(LispVal key, LispVal dflt) {
		Entry e = this.map.get(Key.of(placed(key)));
		return (e == null) ? dflt : e.value();
	}

	/**
	 * Stores {@code value} under {@code key}, replacing any existing entry.
	 * @param key the key
	 * @param value the value to store
	 * @return the stored value
	 */
	public LispVal put(LispVal key, LispVal value) {
		LispVal placed = placed(key);
		this.map.put(Key.of(placed), new Entry(placed, value));
		return value;
	}

	/**
	 * Removes the entry for {@code key}, if any.
	 * @param key the key to remove
	 * @return {@code true} if an entry was removed
	 */
	public boolean remove(LispVal key) {
		return this.map.remove(Key.of(placed(key))) != null;
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
		return (this.equalpTest ? HASH_TABLE_PREFIX_EQUALP : HASH_TABLE_PREFIX) + count() + ">";
	}

}
