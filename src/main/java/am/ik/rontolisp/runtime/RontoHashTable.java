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

	/**
	 * The key an {@code equalp} table's marker hangs off inside the table -- present (any
	 * non-null value) exactly when the table folds its keys through
	 * {@link #equalpKey(Object, int)} before placing them. A second String key beside
	 * {@link #ORDER_KEY}, so it collides with no {@code Integer} bucket key and the table
	 * stays ONE object.
	 */
	public static final String EQUALP_KEY = "#equalp";

	/**
	 * How many levels {@link #equalpKey(Object, int)} descends before answering the
	 * subtree unfolded -- the same cap the structural hash uses, and for the same reason:
	 * a CYCLIC key must terminate.
	 */
	public static final int FOLD_DEPTH_CAP = 64;

	private RontoHashTable() {
	}

	/**
	 * Folds a key into the representative an {@code equalp} table places it under:
	 * {@code equalp} on two values is the structural {@code equal} on their folds, so ONE
	 * table carries both tests and the emitted {@code _hash}/{@code _equal} pair is
	 * untouched.
	 *
	 * <p>
	 * Over the JVM backend's value representation: a Lisp string (a Java String with its
	 * framing quotes) and a character (an {@code int[]} of one code point) fold to upper
	 * case ONE CODE POINT AT A TIME, a float whose value is an INTEGER to that integer
	 * (so {@code 1}, {@code 1.0} and {@code 2/2} are one key), and a cons
	 * ({@code Object[2]}) folds element-wise. Everything else is its own representative
	 * -- a SYMBOL (a bare String, with no framing quotes), a general ARRAY and a float
	 * with a fraction deliberately included.
	 *
	 * <p>
	 * The specification is {@code LispEquality.equalpKey}, which folds the same values in
	 * the interpreter's representation; the two are pinned against each other so an
	 * {@code equalp} table places the same keys on every backend
	 * ({@code .kb/hash-tables.md}).
	 * @param key the key as the caller wrote it
	 * @param depth how many levels are left to descend; at zero the value is its own fold
	 * @return the folded key
	 */
	public static Object equalpKey(Object key, int depth) {
		if (depth <= 0 || key == null) {
			return key;
		}
		if (key instanceof String text) {
			// A framed string folds its content; a SYMBOL (unframed) is its own key.
			return (text.length() >= 2 && text.charAt(0) == '"') ? upcase(text) : text;
		}
		if (key instanceof int[] character) {
			return character.length == 1 ? new int[] { Character.toUpperCase(character[0]) } : key;
		}
		if (key instanceof Double number) {
			return integerValued(number.doubleValue());
		}
		// The cons discrimination the emitted _hash makes: an Object[] that is neither a
		// ratio (a BigInteger[]) nor a function reference (an Integer funcId in slot 0)
		// nor an instance (its interned String[] layout in slot 0).
		if (key instanceof Object[] cell && !(key instanceof java.math.BigInteger[]) && !(key instanceof String[])
				&& cell.length == 2 && !(cell[0] instanceof Integer) && !(cell[0] instanceof String[])) {
			return new Object[] { equalpKey(cell[0], depth - 1), equalpKey(cell[1], depth - 1) };
		}
		return key;
	}

	// Upper case one code point at a time -- the mapping every backend's char-upcase
	// applies, which a whole-string toUpperCase would part company with wherever one
	// code point expands into several.
	private static String upcase(String text) {
		StringBuilder folded = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			int codePoint = text.codePointAt(i);
			folded.appendCodePoint(Character.toUpperCase(codePoint));
			i += Character.charCount(codePoint);
		}
		return folded.toString();
	}

	// The exact integer a double equals, in the shape the emitted _norm produces: a Long
	// when it fits one, a BigInteger otherwise. A finite double is exactly
	// mantissa * 2^exponent, which is where the integer is read from -- no decimal
	// detour, exact at every magnitude. A float with a FRACTION does not fold to the
	// ratio it equals (the WASM ratio cannot hold one, so folding it here would split
	// the backends) and neither does a non-finite one; both are their own key.
	private static Object integerValued(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return Double.valueOf(value);
		}
		if (value == 0.0) {
			// Both zeros fold to the integer 0, which is what (= -0.0 0) answers.
			return Long.valueOf(0);
		}
		long bits = Double.doubleToLongBits(value);
		long fraction = bits & 0x000fffffffffffffL;
		int biasedExponent = (int) ((bits >> 52) & 0x7ff);
		long mantissa = (biasedExponent == 0) ? fraction : (fraction | 0x0010000000000000L);
		int exponent = (biasedExponent == 0) ? -1074 : biasedExponent - 1075;
		while (exponent < 0 && (mantissa & 1L) == 0L) {
			mantissa >>= 1;
			exponent++;
		}
		if (exponent < 0) {
			// An odd mantissa still owing a division by a power of two: a fraction.
			return Double.valueOf(value);
		}
		java.math.BigInteger magnitude = java.math.BigInteger.valueOf(bits < 0 ? -mantissa : mantissa)
			.shiftLeft(exponent);
		return magnitude.bitLength() < 64 ? (Object) Long.valueOf(magnitude.longValue()) : magnitude;
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
