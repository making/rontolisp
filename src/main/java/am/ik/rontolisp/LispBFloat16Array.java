package am.ik.rontolisp;

/**
 * A packed bfloat16 array: the {@code bfloat16} width of the {@link LispFloatArray}
 * umbrella, stored unboxed as a flat row-major {@code short[]} of the TOP 16 BITS of an
 * f32, plus an {@code int[]} of dimension sizes. Produced by the {@code #bf16(...)}
 * reader literal and by {@code (make-array dims :element-type 'bfloat16)}.
 *
 * <p>
 * bfloat16 is an f32 with 16 mantissa bits thrown away: same 8 exponent bits, same range,
 * 8 mantissa bits instead of 24. Both conversions live in {@link BFloat16} and NOWHERE
 * else, so this array and the bulk widen/narrow primitives cannot answer different
 * patterns for one value: {@link BFloat16#value} is exact and total, and
 * {@link BFloat16#bits} rounds to NEAREST EVEN (a bare {@code >>> 16} would truncate,
 * biasing every sum downward -- drift in a model's output rather than a failure) and
 * carries the NaN cases a plain bias-add gets wrong.
 *
 * <p>
 * IEEE binary16 ({@code Float.floatToFloat16}) is a DIFFERENT format -- 5 exponent bits,
 * 10 mantissa bits -- and neither conversion may be written in terms of it.
 *
 * <p>
 * The array is a quarter of the memory of the {@code double} width and half of
 * {@code single-float}, but scalars stay {@code double}: {@link #elementAt} widens the
 * stored pattern to a {@code double} on read and {@link #setElement} narrows the
 * {@code double} on write. There is no bfloat16 SCALAR type -- the width lives entirely
 * in the array storage, exactly as it does for {@link LispSingleFloatArray}. See
 * {@link LispFloatArray} for the shared semantics (rank-n layout, printing, identity
 * equality).
 *
 * <p>
 * {@code bfloat16} is a rontolisp extension rather than a Common Lisp type: it belongs to
 * the {@code rontolisp} package, and enters the type lattice as a fourth subtype of
 * {@code float}, disjoint from the three standard ones.
 *
 * @param data the flat row-major elements, unboxed (each the top 16 bits of an f32)
 * @param dims the dimension sizes; the rank is {@code dims.length}
 */
public record LispBFloat16Array(short[] data, int[] dims) implements LispFloatArray {

	@Override
	public String elementType() {
		return LispNames.BFLOAT16;
	}

	@Override
	public FloatWidth width() {
		return FloatWidth.BFLOAT16;
	}

	/**
	 * The storage, for a HOST read: every reader goes through here (the kernels, the
	 * printer, a record pattern, Java interop), and the access hook first brings home any
	 * copy of it a device holds ({@code .kb/gpu.md}). A read costs one volatile load when
	 * no accelerator is installed.
	 * @return the flat row-major elements
	 */
	@Override
	public short[] data() {
		return (short[]) FloatArrayAccessHook.read(this.data);
	}

	/**
	 * The storage WITHOUT the read hook -- for the device interceptor alone, which hands
	 * the array to the accelerator that may already hold it and must not have it
	 * downloaded first, and for an in-place kernel reporting a write, which names the
	 * identity the device is keyed on. Every other caller reads through {@link #data()}.
	 * No accelerator accepts this width today, but the seam is reported through all the
	 * same so residency stays correct the moment one does.
	 * @return the flat row-major elements, possibly stale on the host
	 */
	public short[] storage() {
		return this.data;
	}

	@Override
	public double elementAt(int flat) {
		return BFloat16.value(data()[flat]);
	}

	@Override
	public void setElement(int flat, double value) {
		// Reported BEFORE the store, like the two CL widths: a device copy that was the
		// authoritative one comes home first, then is dropped, and the narrowed value
		// lands on the array's real bytes -- the array the hook answers.
		((short[]) FloatArrayAccessHook.written(this.data))[flat] = (short) BFloat16.bits(value);
	}

	@Override
	public String elementText(int flat) {
		// The shortest decimal that round-trips AT THIS WIDTH, so a printed array
		// re-reads to the same patterns. 8 mantissa bits want about 3 significant
		// digits, where the f32 spelling of the same value would run to 9.
		return FloatText.bfloat16Text(BFloat16.value(data()[flat]));
	}

	@Override
	public String openPrefix() {
		return "#bf16(";
	}

	@Override
	public LispArray toGeneralArray() {
		short[] d = data();
		LispVal[] boxed = new LispVal[d.length];
		for (int i = 0; i < d.length; i++) {
			boxed[i] = new LispDouble(BFloat16.value(d[i]));
		}
		return new LispArray(this.dims, boxed);
	}

}
