package am.ik.rontolisp;

/**
 * Umbrella type for a packed float array: a rank-n array of unboxed floats stored as a
 * flat row-major primitive backing plus an {@code int[]} of dimension sizes. Two concrete
 * widths implement it: {@link LispDoubleFloatArray} ({@code element-type double-float}, a
 * {@code double[]} backing) and {@link LispSingleFloatArray} ({@code element-type
 * single-float}, a {@code float[]} backing). Both are first-class value types, distinct
 * from the general (heterogeneous, boxed) {@link LispArray}.
 *
 * <p>
 * A packed array is produced by the {@code #d(...)} / {@code #f(...)} reader literals
 * (double / single respectively) and by {@code (make-array dims :element-type
 * 'double-float | 'single-float)} (with no fill pointer, adjustability or displacement --
 * those fall back to {@link LispArray}). Element access is width-agnostic at the Lisp
 * level: reading ({@link #elementAt}) widens the stored value to a {@code double} (a
 * single-float element widens f32 -&gt; f64) and boxes it into a {@link LispDouble};
 * storing ({@link #setElement}) narrows the {@code double} to the backing width (f64
 * -&gt; f32 for a single-float array). Scalars therefore stay {@code double} throughout
 * -- there is no single-float scalar type; the width lives entirely in the array storage.
 *
 * <p>
 * Packed arrays are pure compute buffers: they never carry a fill pointer, adjustability
 * or displacement. Printing uses the {@code #d(...)} / {@code #f(...)} reader syntax at
 * every rank (rank 1 as {@code #d(e1 e2 ...)}, rank n as the numpy-style nested
 * {@code #d((...) ...)}), so a packed array round-trips to a packed array of the same
 * width -- reading its printed form back preserves the unboxed representation (and its
 * performance) rather than degrading to a general boxed array. The data part after the
 * opening {@code (} is rendered by the shared {@link LispArray#renderArrayData}
 * algorithm, so it stays byte-for-byte identical to the general-array data syntax; only
 * the {@code #d} / {@code #f} prefix (see {@link #openPrefix}) distinguishes the two.
 *
 * <p>
 * Like every array, packed arrays are compared by identity ({@code eq}): two distinct
 * packed arrays are never {@code equal} even with the same contents, matching Common
 * Lisp.
 */
public sealed interface LispFloatArray extends LispVal permits LispDoubleFloatArray, LispSingleFloatArray {

	/**
	 * Returns the dimension sizes; the rank is {@code dims().length}.
	 * @return the dimensions (length = rank, {@code >= 1})
	 */
	int[] dims();

	/**
	 * Returns the Common Lisp {@code element-type} specifier of this array:
	 * {@code "double-float"} or {@code "single-float"}.
	 * @return the element-type specifier name
	 */
	String elementType();

	/**
	 * Reads the element at the given row-major index, widened to a {@code double} (a
	 * single-float element is widened f32 -&gt; f64).
	 * @param flat the row-major index ({@code 0 <= flat < totalSize()})
	 * @return the stored element as a {@code double}
	 */
	double elementAt(int flat);

	/**
	 * Stores {@code value} at the given row-major index, narrowing it to the backing
	 * width (f64 -&gt; f32 for a single-float array).
	 * @param flat the row-major index ({@code 0 <= flat < totalSize()})
	 * @param value the value to store (as a {@code double})
	 */
	void setElement(int flat, double value);

	/**
	 * Returns the opening prefix used to print this array ({@code "#d("} for double,
	 * {@code "#f("} for single). Only this prefix distinguishes the two widths in the
	 * printed form.
	 * @return the reader-syntax opening prefix
	 */
	String openPrefix();

	/**
	 * Returns an equivalent general (boxed) {@link LispArray} of the same dimensions
	 * whose elements are {@link LispDouble}s (each widened from the backing width). Used
	 * by the compile backends that lower a packed literal to a general array where a
	 * native packed representation is not yet available. (Printing does not go through
	 * here -- {@link #print}/{@link #display} render the primitive backing directly via
	 * {@link LispArray#renderArrayData}.)
	 * @return a boxed general-array view of this packed array
	 */
	LispArray toGeneralArray();

	/**
	 * Returns the rank (number of dimensions).
	 * @return the rank ({@code >= 1})
	 */
	default int rank() {
		return dims().length;
	}

	/**
	 * Returns the total element count (the product of the dimensions).
	 * @return the total size
	 */
	default int totalSize() {
		int total = 1;
		for (int d : dims()) {
			total *= d;
		}
		return total;
	}

	/**
	 * Reads the element at the given row-major index, boxed into a {@link LispDouble}
	 * (widened from the backing width).
	 * @param flat the row-major index ({@code 0 <= flat < totalSize()})
	 * @return the stored element as a {@link LispDouble}
	 */
	default LispVal readFlat(int flat) {
		return new LispDouble(elementAt(flat));
	}

	/**
	 * Returns the element at the given subscripts, boxed into a {@link LispDouble}.
	 * @param subscripts the indices (one per dimension)
	 * @return the stored element as a {@link LispDouble}
	 */
	default LispVal aref(int... subscripts) {
		return readFlat(flatIndex(subscripts));
	}

	/**
	 * Stores {@code value} at the given subscripts, narrowing to the backing width.
	 * @param value the value to store (as a {@code double})
	 * @param subscripts the indices (one per dimension)
	 */
	default void aset(double value, int... subscripts) {
		setElement(flatIndex(subscripts), value);
	}

	/**
	 * Converts subscripts to a bounds-checked flat row-major index (the Horner fold over
	 * the subscripts).
	 * @param subscripts the indices (one per dimension)
	 * @return the flat row-major index ({@code 0 <= result < totalSize()})
	 */
	default int flatIndex(int[] subscripts) {
		int[] dims = dims();
		if (subscripts.length != dims.length) {
			throw new IllegalArgumentException(
					"aref: expected " + dims.length + " subscripts, got " + subscripts.length);
		}
		int flat = subscripts[0];
		for (int k = 1; k < subscripts.length; k++) {
			flat = flat * dims[k] + subscripts[k];
		}
		if (flat < 0 || flat >= totalSize()) {
			throw new IndexOutOfBoundsException("aref: index out of bounds");
		}
		return flat;
	}

	/**
	 * The printed text of one element, at the array's own float width: a single-float
	 * array element prints the shortest f32 decimal (so {@code #f(0.1)} round-trips), a
	 * double-float array element the shortest f64 decimal. {@code aref} still widens to a
	 * scalar {@code double} whose own text is the f64 spelling of the widened value; the
	 * width lives in the array, not in the scalar.
	 * @param flat the flat row-major index
	 * @return the element's printed text
	 */
	String elementText(int flat);

	@Override
	default String print() {
		return renderGuarded();
	}

	@Override
	default String display() {
		return renderGuarded();
	}

	// A packed float array holds no references, so it cannot close a cycle -- but it
	// still opens one render frame (RenderCycleGuard) so the depth cap truncates at the
	// same frame on every backend: the JVM backend renders a packed array through the
	// guarded _arrayToString, and the WASM printers' array arm is guarded as a whole.
	private String renderGuarded() {
		if (!RenderCycleGuard.enter(this)) {
			return "#";
		}
		try {
			return LispArray.renderArrayData(dims(), totalSize(), openPrefix(), this::elementText);
		}
		finally {
			RenderCycleGuard.exit();
		}
	}

}
