package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.runtime.RontoHashTable;

/**
 * A hash table value (Common Lisp {@code hash-table}).
 *
 * <p>
 * Keys are compared by the table's test and hashed to agree with it. An {@code equal}
 * table separates placement ({@link LispEquality#hash}, a depth-capped fold over the
 * key's structure) from comparison (real {@code equal} within the chain). An {@code eql}
 * or {@code eq} table compares with {@link LispEquality#eql} / {@link LispEquality#eq}
 * instead; its aggregates (conses, instances, allocated strings) hash by identity
 * ({@link System#identityHashCode}), so a key mutated after insertion keeps its chain,
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
 * The entries live in a {@link RontoHashTable}, the structure a compiled JVM program's
 * tables are: hash chains, scanned here with the table's test, and the pairs in insertion
 * order, which {@code maphash} walks. A copy ({@link #copyEqual}) shares it -- the first
 * one turns the table into a trie, every later one is O(1) -- so a Clojure map grown one
 * {@code assoc} at a time costs O(log n) a step ({@code .kb/hash-tables.md}, "Copies
 * share structure").
 */
public final class LispHashTable implements LispVal {

	/**
	 * One stored entry: the key as it was FIRST stored (what {@code maphash} hands back)
	 * and its value. For an {@code equalp} table that is the spelling the caller stored
	 * it under, not the fold it is placed by -- the fold lives in the map's own key; see
	 * {@link #placed}.
	 *
	 * @param key the key as first stored
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

	private final RontoHashTable table;

	// The hash the test agrees with: an eql/eq table's aggregates hash by identity, so a
	// key mutated after insertion keeps its chain, and so does a host object, by the
	// identity of the object it wraps (its own hashCode moves when it is mutated, and the
	// wrapper is fresh per Java call); everything else -- including an eql/eq table's
	// numbers, symbols and source-literal strings, which compare by value exactly as
	// equal compares them -- hashes structurally.
	private static int placementHash(LispVal val, int testCode) {
		if (testCode == TEST_EQL || testCode == TEST_EQ) {
			if (LispEquality.isIdentityAggregate(val)) {
				return System.identityHashCode(val);
			}
			if (val instanceof LispJavaObject host) {
				return System.identityHashCode(host.ref());
			}
		}
		return LispEquality.hash(val);
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
		this(testCode, RontoHashTable.newTable(testCode));
	}

	private LispHashTable(int testCode, RontoHashTable table) {
		this.testCode = testCode;
		this.table = table;
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
	 * {@code equalp} fold. The fold is the pair's slot 0 -- a chain decides by
	 * {@code equal} against the keys already in it, so the fold has to be what is there
	 * -- while the pair keeps the key as first stored in a third slot, which is what
	 * {@code maphash} hands back, as SBCL does ({@code .kb/hash-tables.md}).
	 */
	private LispVal placed(LispVal val) {
		return this.testCode == TEST_EQUALP ? LispEquality.equalpKey(val) : val;
	}

	// The entry of the chain from `first` whose key the test calls the placed key, or
	// null. A flat table's bucket holds other hashes too: their entries are skipped.
	private RontoHashTable.@Nullable Entry match(RontoHashTable.Entry first, LispVal placed, int hash) {
		for (RontoHashTable.Entry entry = first; entry != RontoHashTable.END; entry = entry.next) {
			if (entry.hash != hash) {
				continue;
			}
			LispVal stored = (LispVal) entry.pair[0];
			if (stored == placed) {
				return entry;
			}
			boolean same = switch (this.testCode) {
				case TEST_EQL -> LispEquality.eql(placed, stored);
				case TEST_EQ -> LispEquality.eq(placed, stored);
				default -> LispEquality.equal(placed, stored);
			};
			if (same) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * Returns the value stored under {@code key}, or {@code dflt} if absent.
	 * @param key the lookup key
	 * @param dflt the value to return when the key is not present
	 * @return the stored value, or {@code dflt}
	 */
	public LispVal get(LispVal key, LispVal dflt) {
		LispVal placed = placed(key);
		int hash = placementHash(placed, this.testCode);
		RontoHashTable.Entry entry = match(RontoHashTable.find(this.table, hash), placed, hash);
		return (entry == null) ? dflt : (LispVal) entry.pair[1];
	}

	/**
	 * Stores {@code value} under {@code key}, replacing the value of an existing entry.
	 * An existing entry keeps the key it was first stored under, as SBCL's does.
	 * @param key the key
	 * @param value the value to store
	 * @return the stored value
	 */
	public LispVal put(LispVal key, LispVal value) {
		LispVal placed = placed(key);
		int hash = placementHash(placed, this.testCode);
		// A key its fold changed keeps itself in a third slot: what maphash hands back.
		RontoHashTable.Entry chain = RontoHashTable.putOrChain(this.table, hash, placed, value, key);
		if (chain == RontoHashTable.END) {
			return value;
		}
		RontoHashTable.Entry entry = match(chain, placed, hash);
		if (entry != null) {
			RontoHashTable.setValue(this.table, entry, value);
		}
		else {
			RontoHashTable.add(this.table, hash,
					(placed == key) ? new Object[] { key, value } : new Object[] { placed, value, key });
		}
		return value;
	}

	/**
	 * Removes the entry for {@code key}, if any.
	 * @param key the key to remove
	 * @return {@code true} if an entry was removed
	 */
	public boolean remove(LispVal key) {
		LispVal placed = placed(key);
		int hash = placementHash(placed, this.testCode);
		RontoHashTable.Entry entry = match(RontoHashTable.find(this.table, hash), placed, hash);
		if (entry == null) {
			return false;
		}
		RontoHashTable.remove(this.table, entry);
		return true;
	}

	/**
	 * Removes all entries.
	 */
	public void clear() {
		RontoHashTable.clear(this.table);
	}

	/**
	 * Returns the number of entries.
	 * @return the entry count
	 */
	public int count() {
		return RontoHashTable.count(this.table);
	}

	/**
	 * Returns the stored entries in insertion order: a snapshot, which storing into the
	 * table afterwards leaves as it was.
	 * @return the entries
	 */
	public List<Entry> entries() {
		Object[] pairs = RontoHashTable.liveValues(this.table);
		List<Entry> entries = new ArrayList<>(pairs.length);
		for (Object pair : pairs) {
			Object[] slots = (Object[]) pair;
			entries.add(new Entry((LispVal) slots[(slots.length == 3) ? 2 : 0], (LispVal) slots[1]));
		}
		return entries;
	}

	/**
	 * A fresh {@code equal} table holding this table's entries -- each key as first
	 * stored -- in its insertion order, which a later store into either table leaves the
	 * other without: what {@code rontolisp::%copy-equal-table} answers. An {@code equal}
	 * table's copy shares its structure in O(1) ({@link RontoHashTable#copyEqual}); any
	 * other table's keys are placed again under {@code equal}, as storing them into a
	 * fresh table would.
	 * @return the copy
	 */
	public LispHashTable copyEqual() {
		if (this.testCode == TEST_EQUAL) {
			return new LispHashTable(TEST_EQUAL, RontoHashTable.copyEqual(this.table));
		}
		LispHashTable copy = new LispHashTable(TEST_EQUAL);
		for (Entry entry : entries()) {
			copy.put(entry.key(), entry.value());
		}
		return copy;
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
