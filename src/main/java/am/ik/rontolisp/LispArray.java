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
	 * Returns the flat row-major backing store.
	 * @return the backing data (length = product of dimensions)
	 */
	public LispVal[] data() {
		return this.data;
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
	// rank-2 array as #2A((row) (row) ...). Each element is rendered with the supplied
	// function (print for prin1, display for princ), so princ propagates to the elements
	// the way Common Lisp's *print-escape* does.
	private String render(java.util.function.Function<LispVal, String> renderElement) {
		StringBuilder sb = new StringBuilder();
		if (this.dimensions.length == 1) {
			sb.append("#(");
			for (int i = 0; i < this.data.length; i++) {
				if (i > 0) {
					sb.append(' ');
				}
				sb.append(renderElement.apply(elementOrNil(i)));
			}
			return sb.append(')').toString();
		}
		int rows = this.dimensions[0];
		int cols = this.dimensions[1];
		sb.append("#2A(");
		for (int i = 0; i < rows; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append('(');
			for (int j = 0; j < cols; j++) {
				if (j > 0) {
					sb.append(' ');
				}
				sb.append(renderElement.apply(elementOrNil(i * cols + j)));
			}
			sb.append(')');
		}
		return sb.append(')').toString();
	}

	private LispVal elementOrNil(int flat) {
		LispVal element = this.data[flat];
		return element == null ? LispNil.INSTANCE : element;
	}

}
