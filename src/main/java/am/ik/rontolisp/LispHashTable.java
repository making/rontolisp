package am.ik.rontolisp;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * A hash table value (Common Lisp {@code hash-table}).
 *
 * <p>
 * Keys are compared by the table's test and hashed to agree with it. An {@code equal}
 * table separates placement ({@link LispEquality#hash}, a depth-capped fold over the
 * key's structure) from comparison (real {@code equal} within the bucket). An {@code eql}
 * or {@code eq} table compares with {@link LispEquality#eql} / {@link LispEquality#eq}
 * instead; its aggregates (conses, instances, allocated strings) hash by identity
 * ({@link System#identityHashCode}), so a key mutated after insertion keeps its bucket,
 * while every other value hashes structurally exactly as an {@code equal} table hashes
 * it. Both compiled backends reproduce each pair, so all four backends agree on which
 * keys are one key. A CYCLIC key is therefore usable -- the cap bounds the hash and
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
	 * The test codes every backend agrees on: the tag a table carries for the test its
	 * lookups implement ({@code .kb/hash-tables.md}).
	 */
	public static final int TEST_EQUAL = 0;

	public static final int TEST_EQUALP = 1;

	public static final int TEST_EQL = 2;

	public static final int TEST_EQ = 3;

	/**
	 * Answers the test code for a {@code :test} name: {@code eq} and {@code eql} key by
	 * identity, {@code equalp} folds its keys, and {@code equal} -- like anything else,
	 * including no test at all -- places structurally.
	 * @param testName the test name as written ({@code EQ}, {@code EQL}, {@code EQUAL},
	 * {@code EQUALP})
	 * @return the test code
	 */
	public static int testCodeFor(String testName) {
		// LispNames.EQ is the numeric =, so the identity test is EQ_GENERAL.
		if (LispNames.EQ_GENERAL.equals(testName)) {
			return TEST_EQ;
		}
		if (LispNames.EQL.equals(testName)) {
			return TEST_EQL;
		}
		if (LispNames.EQUALP.equals(testName)) {
			return TEST_EQUALP;
		}
		return TEST_EQUAL;
	}

	/**
	 * Answers the test name a table carrying {@code testCode} reports through
	 * {@code hash-table-test} and the printed {@code :TEST} field.
	 * @param testCode the test code
	 * @return the test name
	 */
	public static String testNameFor(int testCode) {
		if (testCode == TEST_EQ) {
			return LispNames.EQ_GENERAL;
		}
		if (testCode == TEST_EQL) {
			return LispNames.EQL;
		}
		if (testCode == TEST_EQUALP) {
			return LispNames.EQUALP;
		}
		return LispNames.EQUAL;
	}

	/**
	 * The unreadable-object prefix every backend prints before an {@code equal} table's
	 * entry count. SBCL's trailing identity hash is deliberately absent (it would vary
	 * between runs of one program).
	 */
	public static final String HASH_TABLE_PREFIX = "#<HASH-TABLE :TEST EQUAL :COUNT ";

	/**
	 * The same prefix for an {@code equalp} table, whose keys are folded
	 * ({@link LispEquality#equalpKey}) before they are placed. One constant per test
	 * rather than one assembled at run time: each backend interns only the ones its
	 * programs can print.
	 */
	public static final String HASH_TABLE_PREFIX_EQUALP = "#<HASH-TABLE :TEST EQUALP :COUNT ";

	/** The same prefix for an {@code eql} table, whose aggregates key by identity. */
	public static final String HASH_TABLE_PREFIX_EQL = "#<HASH-TABLE :TEST EQL :COUNT ";

	/** The same prefix for an {@code eq} table, whose aggregates key by identity. */
	public static final String HASH_TABLE_PREFIX_EQ = "#<HASH-TABLE :TEST EQ :COUNT ";

	private final int testCode;

	private final LinkedHashMap<Key, Entry> map = new LinkedHashMap<>();

	/**
	 * A key as the backing {@link LinkedHashMap} sees it: the Lisp value plus its
	 * precomputed hash for the table's test. Bucket membership is that hash and bucket
	 * comparison is the test's predicate, which is the whole point -- the value's own
	 * {@code hashCode} recurses without a bound.
	 */
	private record Key(LispVal val, int hash, int testCode) {

		static Key of(LispVal val, int testCode) {
			return new Key(val, placementHash(val, testCode), testCode);
		}

		// The hash the test agrees with: an eql/eq table's aggregates hash by
		// identity, so a key mutated after insertion keeps its bucket; everything
		// else -- including an eql/eq table's numbers, symbols and source-literal
		// strings, which compare by value exactly as equal compares them -- hashes
		// structurally.
		private static int placementHash(LispVal val, int testCode) {
			if ((testCode == TEST_EQL || testCode == TEST_EQ) && LispEquality.isIdentityAggregate(val)) {
				return System.identityHashCode(val);
			}
			return LispEquality.hash(val);
		}

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Key other) || this.testCode != other.testCode) {
				return false;
			}
			if (this.val == other.val) {
				return true;
			}
			return switch (this.testCode) {
				case TEST_EQL -> LispEquality.eql(this.val, other.val);
				case TEST_EQ -> LispEquality.eq(this.val, other.val);
				default -> LispEquality.equal(this.val, other.val);
			};
		}
	}

	/**
	 * Creates an empty {@code equal} hash table -- the structural placement every table
	 * without a test uses.
	 */
	public LispHashTable() {
		this(TEST_EQUAL);
	}

	/**
	 * Creates an empty hash table with the given test.
	 * @param equalpTest {@code true} when the table was created with
	 * {@code :test 'equalp}, whose keys are folded ({@link LispEquality#equalpKey})
	 * before they are placed; {@code false} for every other test
	 */
	public LispHashTable(boolean equalpTest) {
		this(equalpTest ? TEST_EQUALP : TEST_EQUAL);
	}

	/**
	 * Creates an empty hash table with the given test code ({@link #testCodeFor}).
	 * @param testCode the test lookups implement
	 */
	public LispHashTable(int testCode) {
		this.testCode = testCode;
	}

	/**
	 * Whether this table places its keys by the {@code equalp} fold -- what
	 * {@code hash-table-test} and the printed {@code :TEST} field report.
	 * @return {@code true} for a table made with {@code :test 'equalp}
	 */
	public boolean equalpTest() {
		return this.testCode == TEST_EQUALP;
	}

	/**
	 * The test code lookups implement ({@link #TEST_EQUAL}, {@link #TEST_EQUALP},
	 * {@link #TEST_EQL} or {@link #TEST_EQ}).
	 * @return the test code
	 */
	public int testCode() {
		return this.testCode;
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
		return this.testCode == TEST_EQUALP ? LispEquality.equalpKey(val) : val;
	}

	/**
	 * Returns the value stored under {@code key}, or {@code dflt} if absent.
	 * @param key the lookup key
	 * @param dflt the value to return when the key is not present
	 * @return the stored value, or {@code dflt}
	 */
	public LispVal get(LispVal key, LispVal dflt) {
		Entry e = this.map.get(Key.of(placed(key), this.testCode));
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
		this.map.put(Key.of(placed, this.testCode), new Entry(placed, value));
		return value;
	}

	/**
	 * Removes the entry for {@code key}, if any.
	 * @param key the key to remove
	 * @return {@code true} if an entry was removed
	 */
	public boolean remove(LispVal key) {
		return this.map.remove(Key.of(placed(key), this.testCode)) != null;
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
		return switch (this.testCode) {
			case TEST_EQ -> HASH_TABLE_PREFIX_EQ + count() + ">";
			case TEST_EQL -> HASH_TABLE_PREFIX_EQL + count() + ">";
			case TEST_EQUALP -> HASH_TABLE_PREFIX_EQUALP + count() + ">";
			default -> HASH_TABLE_PREFIX + count() + ">";
		};
	}

}
