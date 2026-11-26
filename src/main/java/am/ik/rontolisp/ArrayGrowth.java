package am.ik.rontolisp;

/**
 * The ONE growth policy {@code vector-push-extend} follows, on every backend and for
 * every vector representation.
 *
 * <p>
 * CLHS leaves the extension a full vector grows by implementation-dependent, but the
 * capacity it lands on is OBSERVABLE -- {@code array-dimension} reads it back, and a
 * sized sequence type ({@code (typep v (list 'string (array-dimension v 0)))}) tests it
 * -- so it must not depend on which backend ran the program. It is stated here once and
 * read by all four: the interpreter CALLS {@link #grownCapacity} (from
 * {@link LispArray#vectorPushExtend} for a general vector and
 * {@link LispString#vectorPushExtend} for a character vector), and the two compile paths
 * EMIT the same arithmetic inline ({@code JvmArrayRuntimeBuilder}'s
 * {@code _vectorPushExtend}, {@code WasmArrayCompiler.compileVectorPushExtend}) --
 * generated code cannot call this class, so the emitters spell the formula out against
 * the constants below and the {@code vector-push-extend-growth-cross-backend} ci-spec
 * case pins that they still agree.
 *
 * <p>
 * The policy is DOUBLING, which is also SBCL 2.2.9's (measured 2026-08-31): growing by a
 * fixed one element per push makes a push loop quadratic in the number of pushes, which
 * is the wrong default for the operation programs build strings and buffers with. An
 * explicit {@code extension} argument is honoured verbatim -- it is a request for room,
 * and a caller that asked for a hundred elements gets exactly a hundred, not two hundred.
 * A zero-capacity vector doubles to {@link #MIN_CAPACITY} rather than staying at zero,
 * which is the one edge case doubling cannot express.
 */
public final class ArrayGrowth {

	/**
	 * The {@code extension} value standing for "argument not supplied", so that one
	 * runtime entry point serves both arities: the compile paths pass this literal where
	 * the optional argument is missing, and {@link #grownCapacity} answers with the
	 * default policy. CLHS requires a supplied extension to be a positive integer, so a
	 * zero or negative one is undefined there and takes the default here too.
	 */
	public static final int NO_EXTENSION = 0;

	/** What the capacity is multiplied by when no extension is supplied. */
	public static final int GROWTH_FACTOR = 2;

	/**
	 * The capacity a zero-capacity vector grows to, since doubling would keep it at 0.
	 */
	public static final int MIN_CAPACITY = 1;

	private ArrayGrowth() {
	}

	/**
	 * The capacity a full fill-pointer vector grows to on the push that overflows it.
	 * @param capacity the current capacity (the vector's dimension), never negative
	 * @param extension the {@code vector-push-extend} extension argument, or
	 * {@link #NO_EXTENSION} when it was not supplied
	 * @return the new capacity, always greater than {@code capacity}
	 */
	public static int grownCapacity(int capacity, int extension) {
		if (extension > NO_EXTENSION) {
			return capacity + extension;
		}
		return capacity < MIN_CAPACITY ? MIN_CAPACITY : capacity * GROWTH_FACTOR;
	}

}
