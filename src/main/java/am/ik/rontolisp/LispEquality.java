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
 * so refusing to descend past {@value #HASH_DEPTH_CAP} levels is free CORRECTNESS: two
 * {@code equal} keys still hash equal, because the cap is by DEPTH -- a deterministic
 * function of the structure alone, never of insertion order or of an address. It is not
 * free COST, which is why {@linkplain #HASH_WORK_CAP a work budget} runs beside it: a key
 * whose substructure is SHARED has exponentially many root-to-leaf paths, so bounding the
 * height of the walk bounds nothing about its size. Comparison terminates for the same
 * reason {@code equal} does: {@link #equal} answers true on identity before it recurses,
 * so storing and retrieving under the SAME cyclic object works. Two DISTINCT cyclic
 * structures compared with {@code equal} may still not terminate; ANSI leaves that
 * undefined and so does this implementation.
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

	/**
	 * How many NODES {@link #hash} may visit in one whole traversal before it folds a
	 * constant instead of descending -- the budget that bounds the WORK the depth cap
	 * bounds only the height of.
	 *
	 * <p>
	 * The depth cap alone admits at most {@code 2 * } {@value #HASH_DEPTH_CAP} nodes for
	 * a LINEAR key (a list, a string, a chain of instances), and the number of
	 * root-to-leaf PATHS through a key whose substructure is SHARED is exponential in its
	 * height -- a scene graph, a doubly-linked list, a parse tree with parent pointers
	 * and an ORM entity are all that shape, and at 64 levels the walk is astronomical.
	 * {@value} is 32x what a linear key can cost, so no key a program plausibly places (a
	 * list, a small tree, an instance and its slots) is truncated by it, and it bounds
	 * one placement at a few thousand field reads -- the same order as the ONE
	 * {@link #equal} comparison the bucket scan then runs against the key it finds.
	 *
	 * <p>
	 * <strong>The soundness argument is the depth cap's.</strong> Two {@code equal} keys
	 * have the same shape, so the deterministic traversal below visits them in the same
	 * order and exhausts the budget at the same place, and they still hash equal. Like
	 * the depth cap, the budget may NEVER be made order-of-insertion or address
	 * dependent: it is reset at the start of every top-level {@link #hash} call, so what
	 * a key hashes to is a function of that key alone and not of what was hashed before
	 * it. All four backends carry the same number ({@code .kb/hash-tables.md}).
	 */
	public static final int HASH_WORK_CAP = 4096;

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
	 * a character fold to their upper case, a float WHOSE VALUE IS AN INTEGER to that
	 * integer (so {@code 1}, {@code 1.0} and {@code 2/2} are one key), and a cons folds
	 * element-wise. Everything else is its own representative, and two of those are real
	 * deviations from ANSI {@code equalp} tables, both recorded in
	 * {@code .kb/hash-tables.md}:
	 * <ul>
	 * <li>an ARRAY does not fold, since {@link #equal} on a vector is identity on every
	 * backend and a folded copy would never find itself;</li>
	 * <li>a float with a FRACTION does not fold to the ratio it equals, because the WASM
	 * backends' ratio holds two i32 components and cannot represent one (a float's exact
	 * value has a power-of-two denominator far outside that range), so {@code 0.5} and
	 * {@code 1/2} are two keys everywhere rather than one key here and two there.</li>
	 * </ul>
	 * Both deviations are a MISS, never a false match: the fold only ever refuses to
	 * merge two keys {@code equalp} would call the same.
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
		return equalpKey(v, HASH_DEPTH_CAP, new int[] { HASH_WORK_CAP });
	}

	// Capped at the same depth as the hash, and by the same work budget, for the same two
	// reasons: a CYCLIC key must terminate, and a key with SHARED substructure has
	// exponentially many root-to-leaf paths -- which the fold does not merely walk, it
	// ALLOCATES. Past either cap the subtree is its own fold, so the pair (fold, then
	// hash and equal) still agrees with itself -- what a deep or wide key loses is only
	// the case- and number-insensitivity below the cut, never a false match.
	private static LispVal equalpKey(LispVal v, int depth, int[] budget) {
		if (depth <= 0 || budget[0] <= 0) {
			return v;
		}
		budget[0]--;
		return switch (v) {
			case LispString string -> new LispString(upcase(string.value()));
			case LispChar character -> new LispChar(Character.toUpperCase(character.codePoint()));
			case LispDouble number -> integerValued(number.value());
			case LispCons cons ->
				new LispCons(equalpKey(cons.car(), depth - 1, budget), equalpKey(cons.cdr(), depth - 1, budget));
			default -> v;
		};
	}

	/**
	 * Upper case ONE CODE POINT AT A TIME, which is the mapping every backend can apply:
	 * the compiled backends fold through their own {@code char-upcase} table (generated
	 * from {@link Character#toUpperCase(int)}), and a whole-string
	 * {@code String.toUpperCase} would part company with them wherever it expands one
	 * code point into several ({@code ss} for a sharp s). It is also the mapping the
	 * {@code equalp} PREDICATE uses -- {@code string-equal} compares character by
	 * character -- so the fold and the predicate agree on the same strings.
	 */
	private static String upcase(String value) {
		StringBuilder folded = new StringBuilder(value.length());
		int i = 0;
		while (i < value.length()) {
			int codePoint = value.codePointAt(i);
			folded.appendCodePoint(Character.toUpperCase(codePoint));
			i += Character.charCount(codePoint);
		}
		return folded.toString();
	}

	/**
	 * The exact integer a double equals, so a float and the integer it equals fold to one
	 * key. A float with a fraction, and a non-finite one, have no integer value and are
	 * their own key -- see the {@link #equalpKey} note on why the fraction is not folded
	 * to a ratio.
	 *
	 * <p>
	 * A finite double is exactly {@code mantissa * 2^exponent}, which is what the
	 * compiled backends read out of its bits too, so the three folds answer the same
	 * integer at every magnitude without a decimal detour.
	 */
	private static LispVal integerValued(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return new LispDouble(value);
		}
		if (value == 0.0) {
			// Both zeros fold to the integer 0, which is what (= -0.0 0) answers.
			return new LispInteger(0);
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
			return new LispDouble(value);
		}
		java.math.BigInteger magnitude = java.math.BigInteger.valueOf(bits < 0 ? -mantissa : mantissa);
		return LispRatio.valueOf(magnitude.shiftLeft(exponent), java.math.BigInteger.ONE);
	}

	/**
	 * The structural hash that agrees with {@link #equal}: equal values hash equal.
	 * @param v the value to hash
	 * @return the hash
	 */
	public static int hash(LispVal v) {
		return hash(v, HASH_DEPTH_CAP, new int[] { HASH_WORK_CAP });
	}

	// The two recursive containers -- a cons and an instance -- are folded here rather
	// than through their own hashCode, which recurses without a bound (and is what a
	// cyclic key used to run out of stack on). Everything else answers with its own
	// hashCode, which its equals agrees with by the Java contract, and which is identity
	// exactly where equal is identity.
	//
	// budget is the WHOLE traversal's remaining node visits, a one-cell array because it
	// has to be shared by the siblings rather than handed down each branch -- a
	// per-branch
	// count bounds nothing when the branches share their substructure.
	private static int hash(LispVal v, int depth, int[] budget) {
		if (depth <= 0 || budget[0] <= 0) {
			return 0;
		}
		budget[0]--;
		if (v instanceof LispCons cons) {
			return 31 * hash(cons.car(), depth - 1, budget) + hash(cons.cdr(), depth - 1, budget) + 1;
		}
		if (v instanceof LispInstance inst) {
			int h = inst.layout().tag().hashCode();
			for (int i = 0; i < inst.slotCount(); i++) {
				h = 31 * h + hash(inst.slot(i), depth - 1, budget);
			}
			return h;
		}
		return v.hashCode();
	}

}
