package am.ik.rontolisp;

/**
 * An array value (Common Lisp {@code array}/{@code simple-array}).
 *
 * <p>
 * Only arrays of rank 1 (vectors) and rank 2 are supported, matching the compiled
 * backends. Elements are stored row-major in a flat {@link LispVal} array; a rank-2
 * element {@code (i, j)} lives at flat index {@code i * cols + j}, where {@code cols} is
 * the second dimension.
 *
 * <p>
 * Arrays are compared by identity ({@code eq}); two distinct arrays are never
 * {@code equal} even with the same contents, matching Common Lisp.
 */
public final class LispArray implements LispVal {

	private final int[] dimensions;

	private final LispVal[] data;

	/**
	 * Creates an array with the given dimensions backed by {@code data} (row-major).
	 * @param dimensions the dimension sizes (length 1 or 2)
	 * @param data the flat backing store (length = product of dimensions)
	 */
	public LispArray(int[] dimensions, LispVal[] data) {
		this.dimensions = dimensions;
		this.data = data;
	}

	/**
	 * Returns the dimension sizes.
	 * @return the dimensions (length 1 or 2)
	 */
	public int[] dimensions() {
		return this.dimensions;
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
		return "#<ARRAY>";
	}

}
