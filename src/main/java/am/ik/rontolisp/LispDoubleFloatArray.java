package am.ik.rontolisp;

/**
 * A packed double-float array: the {@code double-float} width of the
 * {@link LispFloatArray} umbrella, stored unboxed as a flat row-major {@code double[]}
 * plus an {@code int[]} of dimension sizes. Produced by the {@code #d(...)} reader
 * literal and by {@code (make-array dims :element-type 'double-float)}.
 *
 * <p>
 * Element read/write is exact (the backing width is already {@code double}):
 * {@link #elementAt} returns the stored {@code double} and {@link #setElement} stores it
 * verbatim. See {@link LispFloatArray} for the shared semantics (rank-n layout, printing,
 * identity equality).
 *
 * @param data the flat row-major elements, unboxed (each stored as a {@code double})
 * @param dims the dimension sizes; the rank is {@code dims.length}
 */
public record LispDoubleFloatArray(double[] data, int[] dims) implements LispFloatArray {

	@Override
	public String elementType() {
		return LispNames.DOUBLE_FLOAT;
	}

	@Override
	public double elementAt(int flat) {
		return this.data[flat];
	}

	@Override
	public void setElement(int flat, double value) {
		this.data[flat] = value;
	}

	@Override
	public String openPrefix() {
		return "#d(";
	}

	@Override
	public LispArray toGeneralArray() {
		LispVal[] boxed = new LispVal[this.data.length];
		for (int i = 0; i < this.data.length; i++) {
			boxed[i] = new LispDouble(this.data[i]);
		}
		return new LispArray(this.dims, boxed);
	}

}
