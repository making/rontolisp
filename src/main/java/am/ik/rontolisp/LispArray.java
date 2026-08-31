package am.ik.rontolisp;

import org.jspecify.annotations.Nullable;

/**
 * An array value (Common Lisp {@code array}/{@code simple-array}).
 *
 * <p>
 * Arrays of any rank {@code >= 0} are supported, matching the compiled backends. Elements
 * are stored row-major in a flat {@link LispVal} array; the flat index is the Horner fold
 * over the subscripts (a rank-2 element {@code (i, j)} lives at {@code i * cols + j},
 * where {@code cols} is the second dimension). A RANK-0 array is the degenerate case of
 * that model rather than a special one: no dimensions, an empty fold (so its one element
 * lives at flat index 0), and a total size of 1 -- Common Lisp's box for "a scalar seen
 * as an array".
 *
 * <p>
 * Arrays are compared by identity ({@code eq}); two distinct arrays are never
 * {@code equal} even with the same contents, matching Common Lisp.
 */
public final class LispArray implements LispVal {

	private int[] dimensions;

	private LispVal[] data;

	// The fill pointer of a rank-1 vector, or -1 when the array has none. When present it
	// is the effective length (0 <= fillPointer <= dimensions[0]); aref/row-major-aref
	// may
	// still reach the full backing store, but length, printing and the sequence functions
	// stop at the fill pointer.
	private int fillPointer;

	// Whether the array was created :adjustable. vector-push-extend grows any vector with
	// a fill pointer regardless (matching common practice), but adjustable-array-p
	// reports
	// this flag verbatim.
	private final boolean adjustable;

	// The displacement target (make-array :displaced-to), or null when the array has its
	// own storage. A displaced array holds no data of its own (data is a shared empty
	// array): element access resolves through the target chain, adding displacedOffset at
	// each hop, so writes are visible through every view. Displacement excludes a fill
	// pointer, adjustability and an initial element (lite semantics, enforced by
	// make-array).
	@Nullable private final LispArray displacedTo;

	private final int displacedOffset;

	private static final LispVal[] NO_DATA = new LispVal[0];

	/**
	 * Creates a simple array with the given dimensions backed by {@code data}
	 * (row-major), with no fill pointer and not adjustable.
	 * @param dimensions the dimension sizes (length = rank, {@code >= 0})
	 * @param data the flat backing store (length = product of dimensions)
	 */
	public LispArray(int[] dimensions, LispVal[] data) {
		this(dimensions, data, -1, false);
	}

	/**
	 * Creates an array with the given dimensions, backing store, fill pointer and
	 * adjustability.
	 * @param dimensions the dimension sizes (length = rank, {@code >= 0})
	 * @param data the flat backing store (length = product of dimensions)
	 * @param fillPointer the fill pointer, or {@code -1} for none (only rank-1 arrays may
	 * have one)
	 * @param adjustable whether the array is adjustable
	 */
	public LispArray(int[] dimensions, LispVal[] data, int fillPointer, boolean adjustable) {
		this.dimensions = dimensions;
		this.data = data;
		this.fillPointer = fillPointer;
		this.adjustable = adjustable;
		this.displacedTo = null;
		this.displacedOffset = 0;
	}

	/**
	 * Creates a displaced array: a view over {@code target}'s storage starting at
	 * {@code offset} (row-major). The view has no fill pointer, is not adjustable and
	 * owns no data.
	 * @param dimensions the dimension sizes of the view (length = rank, {@code >= 0})
	 * @param target the array supplying the storage
	 * @param offset the row-major index into {@code target} where the view starts
	 */
	public LispArray(int[] dimensions, LispArray target, int offset) {
		this.dimensions = dimensions;
		this.data = NO_DATA;
		this.fillPointer = -1;
		this.adjustable = false;
		this.displacedTo = target;
		this.displacedOffset = offset;
	}

	/**
	 * Returns the dimension sizes.
	 * @return the dimensions (length = rank)
	 */
	public int[] dimensions() {
		return this.dimensions;
	}

	/**
	 * Returns the flat row-major backing store. Empty for a displaced array (which owns
	 * no storage; use {@link #readFlat}/{@link #writeFlat}).
	 * @return the backing data (length = product of dimensions, or 0 when displaced)
	 */
	public LispVal[] data() {
		return this.data;
	}

	/**
	 * Returns the displacement target, or {@code null} when the array owns its storage.
	 * @return the {@code :displaced-to} array, or {@code null}
	 */
	@Nullable public LispArray displacedTo() {
		return this.displacedTo;
	}

	/**
	 * Returns the displacement offset ({@code :displaced-index-offset}; 0 when the array
	 * is not displaced).
	 * @return the row-major offset into the displacement target
	 */
	public int displacedOffset() {
		return this.displacedOffset;
	}

	/**
	 * Returns the total element count (the product of the dimensions).
	 * @return the total size
	 */
	public int totalSize() {
		int total = 1;
		for (int d : this.dimensions) {
			total *= d;
		}
		return total;
	}

	/**
	 * Reads the element at the given row-major index, following the displacement chain.
	 * @param flat the row-major index ({@code 0 <= flat < totalSize()})
	 * @return the stored element
	 */
	public LispVal readFlat(int flat) {
		LispArray a = this;
		LispArray target = a.displacedTo;
		while (target != null) {
			flat += a.displacedOffset;
			a = target;
			target = a.displacedTo;
		}
		return a.data[flat];
	}

	/**
	 * Stores the element at the given row-major index, following the displacement chain.
	 * @param flat the row-major index ({@code 0 <= flat < totalSize()})
	 * @param value the value to store
	 */
	public void writeFlat(int flat, LispVal value) {
		LispArray a = this;
		LispArray target = a.displacedTo;
		while (target != null) {
			flat += a.displacedOffset;
			a = target;
			target = a.displacedTo;
		}
		a.data[flat] = value;
	}

	/**
	 * Replaces this array's dimensions, data and fill pointer with {@code other}'s (the
	 * in-place half of {@code adjust-array} on an adjustable array). The adjustable flag
	 * is kept; {@code other} is discarded by the caller.
	 * @param other the freshly built replacement array
	 */
	public void become(LispArray other) {
		this.dimensions = other.dimensions;
		this.data = other.data;
		this.fillPointer = other.fillPointer;
	}

	/**
	 * Returns the fill pointer, or {@code -1} when the array has none.
	 * @return the fill pointer, or {@code -1}
	 */
	public int fillPointer() {
		return this.fillPointer;
	}

	/**
	 * Returns whether the array has a fill pointer.
	 * @return {@code true} if a fill pointer is present
	 */
	public boolean hasFillPointer() {
		return this.fillPointer >= 0;
	}

	/**
	 * Returns whether the array is adjustable.
	 * @return {@code true} if the array was created adjustable
	 */
	public boolean adjustable() {
		return this.adjustable;
	}

	/**
	 * Sets the fill pointer to {@code value}.
	 * @param value the new fill pointer ({@code 0 <= value <= dimensions[0]})
	 */
	public void setFillPointer(int value) {
		if (this.fillPointer < 0) {
			throw new IllegalStateException("array has no fill pointer");
		}
		if (value < 0 || value > this.dimensions[0]) {
			throw new IndexOutOfBoundsException("fill pointer out of range: " + value);
		}
		this.fillPointer = value;
	}

	/**
	 * Returns the effective length: the fill pointer when present, otherwise the total
	 * element count.
	 * @return the effective length
	 */
	public int effectiveLength() {
		return this.fillPointer >= 0 ? this.fillPointer : totalSize();
	}

	/**
	 * Pushes {@code value} at the fill pointer of a rank-1 vector, returning the index it
	 * was stored at, or {@code -1} when the vector is full. Requires a fill pointer.
	 * @param value the element to store
	 * @return the index used, or {@code -1} if full
	 */
	public int vectorPush(LispVal value) {
		requireVectorWithFillPointer("vector-push");
		if (this.fillPointer >= this.dimensions[0]) {
			return -1;
		}
		this.data[this.fillPointer] = value;
		return this.fillPointer++;
	}

	/**
	 * Pops and returns the element below the fill pointer of a rank-1 vector. Requires a
	 * fill pointer.
	 * @return the popped element
	 */
	public LispVal vectorPop() {
		requireVectorWithFillPointer("vector-pop");
		if (this.fillPointer == 0) {
			throw new IllegalStateException("vector-pop: empty vector");
		}
		return this.data[--this.fillPointer];
	}

	/**
	 * Pushes {@code value} like {@link #vectorPush}, growing the backing store (by at
	 * least {@code extension}) when the vector is full. Requires a fill pointer.
	 * @param value the element to store
	 * @param extension the minimum number of elements to grow by when full
	 * @return the index used
	 */
	public int vectorPushExtend(LispVal value, int extension) {
		requireVectorWithFillPointer("vector-push-extend");
		if (this.fillPointer >= this.dimensions[0]) {
			int grow = Math.max(extension, 1);
			int newCap = this.dimensions[0] + grow;
			LispVal[] grown = new LispVal[newCap];
			System.arraycopy(this.data, 0, grown, 0, this.data.length);
			for (int i = this.data.length; i < newCap; i++) {
				grown[i] = LispNil.INSTANCE;
			}
			this.data = grown;
			this.dimensions = new int[] { newCap };
		}
		this.data[this.fillPointer] = value;
		return this.fillPointer++;
	}

	private void requireVectorWithFillPointer(String fn) {
		if (this.dimensions.length != 1) {
			throw new IllegalStateException(fn + ": not a vector");
		}
		if (this.fillPointer < 0) {
			throw new IllegalStateException(fn + ": vector has no fill pointer");
		}
	}

	/**
	 * Returns the element at the given subscripts.
	 * @param subscripts the indices (one per dimension)
	 * @return the stored element
	 */
	public LispVal aref(int... subscripts) {
		return readFlat(flatIndex(subscripts));
	}

	/**
	 * Stores {@code value} at the given subscripts.
	 * @param value the value to store
	 * @param subscripts the indices (one per dimension)
	 */
	public void aset(LispVal value, int... subscripts) {
		writeFlat(flatIndex(subscripts), value);
	}

	private int flatIndex(int[] subscripts) {
		if (subscripts.length != this.dimensions.length) {
			throw new IllegalArgumentException(
					"aref: expected " + this.dimensions.length + " subscripts, got " + subscripts.length);
		}
		// The Horner fold, started at 0 so a RANK-0 array (no subscripts) folds to the
		// flat index 0 of its single element instead of reading a subscript that is not
		// there.
		int flat = 0;
		for (int k = 0; k < subscripts.length; k++) {
			flat = flat * this.dimensions[k] + subscripts[k];
		}
		if (flat < 0 || flat >= totalSize()) {
			throw new IndexOutOfBoundsException("aref: index out of bounds");
		}
		return flat;
	}

	@Override
	public String print() {
		return render(LispVal::print);
	}

	@Override
	public String display() {
		return render(LispVal::display);
	}

	// Renders the readable vector/array syntax: a rank-1 array as #(e1 e2 ...) and a
	// rank-n array as #nA((...) ...) (a rank-0 array as #0Ae, handled in
	// renderArrayData). Each element is rendered with the supplied
	// function (print for prin1, display for princ), so princ propagates to the elements
	// the way Common Lisp's *print-escape* does. A nested group paren opens where the
	// flat index is a multiple of that dimension's stride and closes where the next
	// index is.
	private String render(java.util.function.Function<LispVal, String> renderElement) {
		// The cycle guard (RenderCycleGuard, shared with the instance and cons
		// renderers): an array holding itself -- directly or through a list -- would
		// recurse without end, so an array already on the current rendering path, or the
		// frame past the depth cap, prints as "#", the *print-level* cutoff marker.
		if (!RenderCycleGuard.enter(this)) {
			return "#";
		}
		try {
			int rank = this.dimensions.length;
			return render(renderElement, rank == 1 ? "#(" : "#" + rank + "A(");
		}
		finally {
			RenderCycleGuard.exit();
		}
	}

	// Renders the array data with a caller-supplied opening prefix (up to and including
	// the outermost '('). A packed {@link LispFloatArray} renders through the shared
	// {@link #renderArrayData} with its width-specific prefix ("#d(" for double, "#f("
	// for
	// single) so it round-trips to a packed array of the same width; the data part after
	// the '(' is identical to the general-array syntax at every rank (rank is inferred
	// from
	// nesting).
	String render(java.util.function.Function<LispVal, String> renderElement, String openPrefix) {
		return renderArrayData(this.dimensions, effectiveLength(), openPrefix,
				k -> renderElement.apply(elementOrNil(k)));
	}

	/**
	 * Renders the {@code #(...)}/{@code #nA(...)}/{@code #d(...)}/{@code #f(...)} array
	 * syntax from dimensions and a per-flat-index element renderer, without materializing
	 * a boxed array. A nested group paren opens where the flat index is a multiple of
	 * that dimension's stride and closes where the next index is. Shared by the general
	 * {@link LispArray} and the packed {@link LispFloatArray} so the (subtle) paren
	 * layout lives in one place; the packed array renders its primitive backing directly,
	 * boxing only the transient {@link LispDouble} each element's string needs.
	 * @param dims the dimension sizes (length = rank; empty for a rank-0 array, which
	 * renders as {@code #0A} followed by its single element -- no parens, the shape
	 * {@code readArray} reads back)
	 * @param count the number of leading elements to render (the effective length)
	 * @param openPrefix the opening text through the outermost {@code (} (e.g.
	 * {@code "#("}, {@code "#2A("}, {@code "#d("} or {@code "#f("})
	 * @param renderElementAt renders the element at a given flat row-major index
	 * @return the readable array syntax
	 */
	static String renderArrayData(int[] dims, int count, String openPrefix,
			java.util.function.IntFunction<String> renderElementAt) {
		int rank = dims.length;
		if (rank == 0) {
			// #0A<datum>: a rank-0 array has no dimensions to group, so it carries no
			// parens at all and the caller's "#(" / "#2A(" / "#d(" prefix does not apply.
			return "#0A" + renderElementAt.apply(0);
		}
		StringBuilder sb = new StringBuilder();
		sb.append(openPrefix);
		for (int k = 0; k < count; k++) {
			if (k > 0) {
				sb.append(' ');
			}
			for (int j = 1; j < rank; j++) {
				if (k % strideOf(dims, j) == 0) {
					sb.append('(');
				}
			}
			sb.append(renderElementAt.apply(k));
			for (int j = rank - 1; j >= 1; j--) {
				if ((k + 1) % strideOf(dims, j) == 0) {
					sb.append(')');
				}
			}
		}
		return sb.append(')').toString();
	}

	// The flat-index span of one step of dimension j-1: the product of the dimension
	// sizes from j to the last.
	private static int strideOf(int[] dims, int j) {
		int s = 1;
		for (int m = j; m < dims.length; m++) {
			s *= dims[m];
		}
		return s;
	}

	private LispVal elementOrNil(int flat) {
		LispVal element = readFlat(flat);
		return element == null ? LispNil.INSTANCE : element;
	}

}
