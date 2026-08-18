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
