package am.ik.rontolisp;

/**
 * The {@code equal} predicate and the structural hash that agrees with it -- the pair a
 * hash table is built from, shared by the interpreter and (as the specification the
 * emitted code reproduces) by both compiled backends.
 *
 * <p>
 * Hashing and comparison are deliberately SEPARATE. {@link #hash} folds the key's
 * structure down to an {@code int} under a fixed {@linkplain #HASH_DEPTH_CAP depth cap},
 * and {@link #equal} then decides the candidates that landed in one bucket. Nothing here
 * prints the key: a hash table that keyed on the printed text of its key paid the size of
 * the key's whole graph on every lookup, and never terminated at all on a cyclic one.
 *
 * <p>
 * <strong>The cap is what makes a cyclic key safe.</strong> A hash need not be injective,
 * so refusing to descend past {@value #HASH_DEPTH_CAP} levels is free correctness: two
 * {@code equal} keys still hash equal, because the cap is by DEPTH -- a deterministic
 * function of the structure alone, never of insertion order or of an address. Comparison
 * terminates for the same reason {@code equal} does: {@link #equal} answers true on
 * identity before it recurses, so storing and retrieving under the SAME cyclic object
 * works. Two DISTINCT cyclic structures compared with {@code equal} may still not
 * terminate; ANSI leaves that undefined and so does this implementation.
 */
public final class LispEquality {

	/**
	 * How many levels {@link #hash} descends before folding in a constant instead. Both
	 * compiled backends reproduce the same cap (the JVM {@code _hash} carries the
	 * remaining depth as its second parameter, the WASM {@code _hash} counts the live
	 * recursion depth in a global), so a key hashes to the same bucket population on
	 * every backend.
	 */
	public static final int HASH_DEPTH_CAP = 64;

	private LispEquality() {
	}

	/**
	 * The {@code equal} predicate: identical objects are equal; two conses are equal when
	 * their cars and cdrs are recursively equal; everything else falls back to
	 * {@code eql} (numbers and characters by value and type, strings and instances
	 * structurally through their own {@code equals}, other aggregates by identity).
	 * @param a the first value
	 * @param b the second value
	 * @return whether the two values are {@code equal}
	 */
	public static boolean equal(LispVal a, LispVal b) {
		if (a == b) {
			return true;
		}
		if (a instanceof LispCons consA) {
			return b instanceof LispCons consB && equal(consA.car(), consB.car()) && equal(consA.cdr(), consB.cdr());
		}
		if (b instanceof LispCons) {
			return false;
		}
		if (a instanceof LispNil || b instanceof LispNil) {
			return a instanceof LispNil && b instanceof LispNil;
		}
		return a.equals(b);
	}

	/**
	 * Folds a value into the key an {@code equalp} hash table places it under: the
	 * canonical representative of everything {@code equalp} calls the same. A string and
	 * a character fold to their upper case, a number to its exact rational value (so
	 * {@code 1}, {@code 1.0} and {@code 2/2} are one key), and a cons folds element-wise.
	 * Everything else is its own representative -- an ARRAY deliberately included, since
	 * {@link #equal} on a vector is identity on every backend and a folded copy would
	 * never find itself. That is a real deviation from ANSI {@code equalp} tables and is
	 * recorded in {@code .kb/hash-tables.md}.
	 *
	 * <p>
	 * Folding rather than a second hash/compare pair is what keeps ONE structural table
	 * behind both tests: {@code equalp} on two values is {@link #equal} on their folds,
	 * so the existing {@link #hash} / {@link #equal} pair -- and every backend's own copy
	 * of it -- carries {@code equalp} unchanged. The fold must stay in step with the
	 * {@code equalp} predicate itself, which is defined once in Lisp
	 * ({@code LispPreludeLibrary}) and shared by all four backends.
	 * @param v the key as the caller wrote it
	 * @return the folded key
	 */
	public static LispVal equalpKey(LispVal v) {
		return equalpKey(v, HASH_DEPTH_CAP);
	}

	// Capped at the same depth as the hash, and for the same reason: a CYCLIC key must
	// terminate. Past the cap the subtree is its own fold, so the pair (fold, then hash
	// and equal) still agrees with itself -- what a deep key loses is only the case- and
	// number-insensitivity below level 64, never a false match.
	private static LispVal equalpKey(LispVal v, int depth) {
		if (depth <= 0) {
			return v;
		}
		return switch (v) {
			case LispString string -> new LispString(string.value().toUpperCase(java.util.Locale.ROOT));
			case LispChar character -> new LispChar(Character.toUpperCase(character.codePoint()));
			case LispDouble number -> exactRational(number.value());
			case LispCons cons -> new LispCons(equalpKey(cons.car(), depth - 1), equalpKey(cons.cdr(), depth - 1));
			default -> v;
		};
	}

	/**
	 * The exact rational value of a double, so a float and the integer or ratio it equals
	 * fold to one key. A non-finite value has no rational value and is its own key.
	 */
	private static LispVal exactRational(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return new LispDouble(value);
		}
		java.math.BigDecimal decimal = new java.math.BigDecimal(value);
		java.math.BigInteger scaled = decimal.unscaledValue();
		int scale = decimal.scale();
		java.math.BigInteger numerator = scale < 0 ? scaled.multiply(java.math.BigInteger.TEN.pow(-scale)) : scaled;
		java.math.BigInteger denominator = scale > 0 ? java.math.BigInteger.TEN.pow(scale) : java.math.BigInteger.ONE;
		return LispRatio.valueOf(numerator, denominator);
	}

	/**
	 * The structural hash that agrees with {@link #equal}: equal values hash equal.
	 * @param v the value to hash
	 * @return the hash
	 */
	public static int hash(LispVal v) {
		return hash(v, HASH_DEPTH_CAP);
	}

	// The two recursive containers -- a cons and an instance -- are folded here rather
	// than through their own hashCode, which recurses without a bound (and is what a
	// cyclic key used to run out of stack on). Everything else answers with its own
	// hashCode, which its equals agrees with by the Java contract, and which is identity
	// exactly where equal is identity.
	private static int hash(LispVal v, int depth) {
		if (depth <= 0) {
			return 0;
		}
		if (v instanceof LispCons cons) {
			return 31 * hash(cons.car(), depth - 1) + hash(cons.cdr(), depth - 1) + 1;
		}
		if (v instanceof LispInstance inst) {
			int h = inst.layout().tag().hashCode();
			for (int i = 0; i < inst.slotCount(); i++) {
				h = 31 * h + hash(inst.slot(i), depth - 1);
			}
			return h;
		}
		return v.hashCode();
	}

}
