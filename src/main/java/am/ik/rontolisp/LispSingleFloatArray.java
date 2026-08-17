package am.ik.rontolisp;

/**
 * A packed single-float array: the {@code single-float} width of the
 * {@link LispFloatArray} umbrella, stored unboxed as a flat row-major {@code float[]}
 * plus an {@code int[]} of dimension sizes. Produced by the {@code #f(...)} reader
 * literal and by {@code (make-array dims :element-type 'single-float)}.
 *
 * <p>
 * The array halves the memory of the {@code double} width and doubles the SIMD lane
 * count, but scalars stay {@code double}: {@link #elementAt} widens the stored
 * {@code float} to a {@code double} on read and {@link #setElement} narrows the
 * {@code double} to a {@code float} on write (numpy-like). No single-float scalar
 * arithmetic is introduced -- the width lives entirely in the array storage. See
 * {@link LispFloatArray} for the shared semantics (rank-n layout, printing, identity
 * equality).
 *
 * @param data the flat row-major elements, unboxed (each stored as a {@code float})
 * @param dims the dimension sizes; the rank is {@code dims.length}
 */
public record LispSingleFloatArray(float[] data, int[] dims) implements LispFloatArray {

	@Override
	public String elementType() {
		return LispNames.SINGLE_FLOAT;
	}

	@Override
	public double elementAt(int flat) {
		// Widen f32 -> f64 on read.
		return this.data[flat];
	}

	@Override
	public void setElement(int flat, double value) {
		// Narrow f64 -> f32 on store.
		this.data[flat] = (float) value;
	}

	@Override
	public String elementText(int flat) {
		// The element prints at its stored f32 width, so #f(0.1) round-trips.
		return FloatText.singleText(this.data[flat]);
	}

	@Override
	public String openPrefix() {
		return "#f(";
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
