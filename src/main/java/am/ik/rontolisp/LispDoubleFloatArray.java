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

	/**
	 * The storage, for a HOST read: every reader goes through here (the kernels, the
	 * printer, a record pattern, Java interop), and the access hook first brings home any
	 * copy of it a device holds ({@code .kb/gpu.md}). A read costs one volatile load when
	 * no accelerator is installed.
	 * @return the flat row-major elements
	 */
	@Override
	public double[] data() {
		FloatArrayAccessHook.read(this.data);
		return this.data;
	}

	/**
	 * The storage WITHOUT the read hook -- for the device interceptor alone, which hands
	 * the array to the accelerator that may already hold it and must not have it
	 * downloaded first. Every other caller reads through {@link #data()}.
	 * @return the flat row-major elements, possibly stale on the host
	 */
	public double[] storage() {
		return this.data;
	}

	@Override
	public double elementAt(int flat) {
		FloatArrayAccessHook.read(this.data);
		return this.data[flat];
	}

	@Override
	public void setElement(int flat, double value) {
		// Reported BEFORE the store: a device copy that was the authoritative one comes
		// home first, then is dropped, and the value lands on the array's real bytes.
		FloatArrayAccessHook.written(this.data);
		this.data[flat] = value;
	}

	@Override
	public String elementText(int flat) {
		FloatArrayAccessHook.read(this.data);
		return FloatText.doubleText(this.data[flat]);
	}

	@Override
	public String openPrefix() {
		return "#d(";
	}

	@Override
	public LispArray toGeneralArray() {
		FloatArrayAccessHook.read(this.data);
		LispVal[] boxed = new LispVal[this.data.length];
		for (int i = 0; i < this.data.length; i++) {
			boxed[i] = new LispDouble(this.data[i]);
		}
		return new LispArray(this.dims, boxed);
	}

}
