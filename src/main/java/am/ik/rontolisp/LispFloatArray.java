package am.ik.rontolisp;

/**
 * A packed float array: a rank-n array whose {@code element-type} is
 * {@code double-float}, stored unboxed as a flat row-major {@code double[]} plus an
 * {@code int[]} of dimension sizes. This is a first-class value type, distinct from the
 * general (heterogeneous, boxed) {@link LispArray}.
 *
 * <p>
 * A packed array is produced by the {@code #f(...)} reader literal and by
 * {@code (make-array dims :element-type 'double-float)} (with no fill pointer,
 * adjustability or displacement -- those fall back to {@link LispArray}). Reading an
 * element boxes it into a {@link LispDouble}; storing one coerces any real to a double
 * and signals a type error for a non-real (there is no degrade path -- the element type
 * is always {@code double-float}).
 *
 * <p>
 * Packed arrays are pure compute buffers: they never carry a fill pointer, adjustability
 * or displacement. Printing is defined by the dimensions and elements exactly as for a
 * general array of the same doubles, so a packed array prints byte-for-byte identically
 * to its boxed counterpart (rank 1 as {@code #(...)}, rank n as {@code #nA(...)}); the
 * rendering is delegated to {@link LispArray} to keep the two in lock-step.
 *
 * <p>
 * Like every array, packed arrays are compared by identity ({@code eq}): two distinct
 * packed arrays are never {@code equal} even with the same contents, matching Common
 * Lisp.
 */
public record LispFloatArray(double[] data, int[] dims) implements LispVal {

	/**
	 * Returns the rank (number of dimensions).
	 * @return the rank ({@code >= 1})
	 */
	public int rank() {
		return this.dims.length;
	}

	/**
	 * Returns the total element count (the product of the dimensions).
	 * @return the total size
	 */
	public int totalSize() {
		int total = 1;
		for (int d : this.dims) {
			total *= d;
		}
		return total;
	}

	/**
	 * Reads the element at the given row-major index, boxed into a {@link LispDouble}.
	 * @param flat the row-major index ({@code 0 <= flat < totalSize()})
	 * @return the stored element as a {@link LispDouble}
	 */
	public LispVal readFlat(int flat) {
		return new LispDouble(this.data[flat]);
	}

	/**
	 * Stores {@code value} at the given row-major index.
	 * @param flat the row-major index ({@code 0 <= flat < totalSize()})
	 * @param value the double to store
	 */
	public void writeFlat(int flat, double value) {
		this.data[flat] = value;
	}

	/**
	 * Returns the element at the given subscripts, boxed into a {@link LispDouble}.
	 * @param subscripts the indices (one per dimension)
	 * @return the stored element as a {@link LispDouble}
	 */
	public LispVal aref(int... subscripts) {
		return readFlat(flatIndex(subscripts));
	}

	/**
	 * Stores the double {@code value} at the given subscripts.
	 * @param value the double to store
	 * @param subscripts the indices (one per dimension)
	 */
	public void aset(double value, int... subscripts) {
		writeFlat(flatIndex(subscripts), value);
	}

	private int flatIndex(int[] subscripts) {
		if (subscripts.length != this.dims.length) {
			throw new IllegalArgumentException(
					"aref: expected " + this.dims.length + " subscripts, got " + subscripts.length);
		}
		int flat = subscripts[0];
		for (int k = 1; k < subscripts.length; k++) {
			flat = flat * this.dims[k] + subscripts[k];
		}
		if (flat < 0 || flat >= totalSize()) {
			throw new IndexOutOfBoundsException("aref: index out of bounds");
		}
		return flat;
	}

	@Override
	public String print() {
		return toGeneralArray().print();
	}

	@Override
	public String display() {
		return toGeneralArray().display();
	}

	/**
	 * Returns an equivalent general (boxed) {@link LispArray} of the same dimensions
	 * whose elements are {@link LispDouble}s. Used to render the readable {@code #(...)}/
	 * {@code #nA(...)} syntax through exactly one code path (so a packed array prints
	 * byte-for-byte identically to a general double array), and by the compile backends
	 * that lower a packed literal to a general array where a native packed representation
	 * is not yet available.
	 * @return a boxed general-array view of this packed array
	 */
	public LispArray toGeneralArray() {
		LispVal[] boxed = new LispVal[this.data.length];
		for (int i = 0; i < this.data.length; i++) {
			boxed[i] = new LispDouble(this.data[i]);
		}
		return new LispArray(this.dims, boxed);
	}

}
