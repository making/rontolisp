package am.ik.rontolisp;

/**
 * An array value (Common Lisp {@code array}/{@code simple-array}).
 *
 * <p>
 * Arrays of any rank {@code >= 1} are supported, matching the compiled backends. Elements
 * are stored row-major in a flat {@link LispVal} array; the flat index is the Horner fold
 * over the subscripts (a rank-2 element {@code (i, j)} lives at {@code i * cols + j},
 * where {@code cols} is the second dimension).
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

	/**
	 * Creates a simple array with the given dimensions backed by {@code data}
	 * (row-major), with no fill pointer and not adjustable.
	 * @param dimensions the dimension sizes (length = rank, {@code >= 1})
	 * @param data the flat backing store (length = product of dimensions)
	 */
	public LispArray(int[] dimensions, LispVal[] data) {
		this(dimensions, data, -1, false);
	}

	/**
	 * Creates an array with the given dimensions, backing store, fill pointer and
	 * adjustability.
	 * @param dimensions the dimension sizes (length = rank, {@code >= 1})
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
	}

	/**
	 * Returns the dimension sizes.
	 * @return the dimensions (length = rank)
	 */
	public int[] dimensions() {
		return this.dimensions;
	}

	/**
	 * Returns the flat row-major backing store.
	 * @return the backing data (length = product of dimensions)
	 */
	public LispVal[] data() {
		return this.data;
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
		return this.fillPointer >= 0 ? this.fillPointer : this.data.length;
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
		return this.data[flatIndex(subscripts)];
	}

	/**
	 * Stores {@code value} at the given subscripts.
	 * @param value the value to store
	 * @param subscripts the indices (one per dimension)
	 */
	public void aset(LispVal value, int... subscripts) {
		this.data[flatIndex(subscripts)] = value;
	}

	private int flatIndex(int[] subscripts) {
		if (subscripts.length != this.dimensions.length) {
			throw new IllegalArgumentException(
					"aref: expected " + this.dimensions.length + " subscripts, got " + subscripts.length);
		}
		int flat = subscripts[0];
		for (int k = 1; k < subscripts.length; k++) {
			flat = flat * this.dimensions[k] + subscripts[k];
		}
		if (flat < 0 || flat >= this.data.length) {
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
	// rank-n array as #nA((...) ...). Each element is rendered with the supplied
	// function (print for prin1, display for princ), so princ propagates to the elements
	// the way Common Lisp's *print-escape* does. A nested group paren opens where the
	// flat index is a multiple of that dimension's stride and closes where the next
	// index is.
	private String render(java.util.function.Function<LispVal, String> renderElement) {
		int rank = this.dimensions.length;
		int count = effectiveLength();
		StringBuilder sb = new StringBuilder();
		sb.append(rank == 1 ? "#(" : "#" + rank + "A(");
		for (int k = 0; k < count; k++) {
			if (k > 0) {
				sb.append(' ');
			}
			for (int j = 1; j < rank; j++) {
				if (k % stride(j) == 0) {
					sb.append('(');
				}
			}
			sb.append(renderElement.apply(elementOrNil(k)));
			for (int j = rank - 1; j >= 1; j--) {
				if ((k + 1) % stride(j) == 0) {
					sb.append(')');
				}
			}
		}
		return sb.append(')').toString();
	}

	// The flat-index span of one step of dimension j-1: the product of the dimension
	// sizes from j to the last.
	private int stride(int j) {
		int s = 1;
		for (int m = j; m < this.dimensions.length; m++) {
			s *= this.dimensions[m];
		}
		return s;
	}

	private LispVal elementOrNil(int flat) {
		LispVal element = this.data[flat];
		return element == null ? LispNil.INSTANCE : element;
	}

}
