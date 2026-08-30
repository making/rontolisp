package am.ik.rontolisp;

/**
 * A packed unsigned-integer vector: a RANK-1 array of unboxed {@code (unsigned-byte 8)},
 * {@code (unsigned-byte 16)} or {@code (unsigned-byte 32)} elements, stored pre-masked in
 * a flat {@code long[]}. Produced by {@code (make-array n :element-type '(unsigned-byte
 * 8|16|32))} (with no fill pointer, adjustability or displacement -- those, and any rank
 * other than 1, fall back to the general boxed {@link LispArray}) and by ironclad's
 * {@code #N@(...)} table literal. This is the representation that lets the compile
 * backends keep byte/word buffers unboxed (todo 194 stage 2): on the wasm-GC backend the
 * same value is a raw {@code (array (mut i8|i16|i32))}, on the JVM a {@code long[]} with
 * a width header.
 *
 * <p>
 * Element semantics, identical on every backend by construction: a store masks the value
 * to the element width (two's-complement truncation -- exactly what raw {@code i8/i16/
 * i32} storage does on the compiled backends); a read returns the stored value widened
 * UNSIGNED. Storing a non-integer is a type error. {@code aref} past the end is a bounds
 * error (the compiled backends trap).
 *
 * <p>
 * A packed integer vector prints as a plain {@code #(...)} vector (like Common Lisp
 * prints specialized vectors); reading that form back yields a general vector, which is
 * CL-conformant ({@code *print-readably*} does not promise representation identity).
 * {@code array-element-type} reports the real {@code (unsigned-byte N)} specifier. Like
 * every array, packed integer vectors are compared by identity ({@code eq}).
 */
public final class LispIntVector implements LispVal {

	private final int width;

	private final long[] data;

	/**
	 * Creates a packed vector; the caller's elements are masked to the width.
	 * @param width the element width in bits (8, 16 or 32)
	 * @param data the elements (taken by reference; each is masked in place)
	 */
	public LispIntVector(int width, long[] data) {
		if (width != 8 && width != 16 && width != 32) {
			throw new IllegalArgumentException("Unsupported packed integer width: " + width);
		}
		this.width = width;
		long mask = mask(width);
		for (int i = 0; i < data.length; i++) {
			data[i] &= mask;
		}
		this.data = data;
	}

	/**
	 * Returns the unsigned mask for an element width.
	 * @param width the element width in bits (8, 16 or 32)
	 * @return the width's low-bits mask
	 */
	public static long mask(int width) {
		return (1L << width) - 1;
	}

	/**
	 * Returns the element width in bits.
	 * @return 8, 16 or 32
	 */
	public int width() {
		return this.width;
	}

	/**
	 * Returns the backing storage (pre-masked unsigned values).
	 * @return the elements
	 */
	public long[] data() {
		return this.data;
	}

	/**
	 * Returns the vector length.
	 * @return the number of elements
	 */
	public int length() {
		return this.data.length;
	}

	/**
	 * Reads an element (always non-negative: the stored value widened unsigned).
	 * @param index the element index
	 * @return the stored element
	 */
	public long elementAt(int index) {
		return this.data[index];
	}

	/**
	 * Stores an element, masked to the element width.
	 * @param index the element index
	 * @param value the value to store
	 * @return the value as stored (masked)
	 */
	public long setElement(int index, long value) {
		long masked = value & mask(this.width);
		this.data[index] = masked;
		return masked;
	}

	/**
	 * Returns the {@code (unsigned-byte N)} element-type specifier as a Lisp value.
	 * @return the specifier list
	 */
	public LispVal elementTypeSpec() {
		return new LispCons(new LispSymbol(LispNames.UNSIGNED_BYTE),
				new LispCons(new LispInteger(this.width), LispNil.INSTANCE));
	}

	/**
	 * Returns an equivalent general (boxed) rank-1 {@link LispArray} whose elements are
	 * {@link LispInteger}s. Used where a general representation is needed (e.g. lowering
	 * a literal on a backend without the packed representation).
	 * @return the boxed copy
	 */
	public LispArray toGeneralArray() {
		LispVal[] boxed = new LispVal[this.data.length];
		for (int i = 0; i < this.data.length; i++) {
			boxed[i] = new LispInteger(this.data[i]);
		}
		return new LispArray(new int[] { this.data.length }, boxed);
	}

	@Override
	public String print() {
		// A packed integer vector cannot close a cycle, but it still opens one render
		// frame (RenderCycleGuard) so the depth cap truncates at the same frame on
		// every backend -- see LispFloatArray's twin note.
		if (!RenderCycleGuard.enter(this)) {
			return "#";
		}
		try {
			StringBuilder sb = new StringBuilder("#(");
			for (int i = 0; i < this.data.length; i++) {
				if (i > 0) {
					sb.append(' ');
				}
				sb.append(this.data[i]);
			}
			return sb.append(')').toString();
		}
		finally {
			RenderCycleGuard.exit();
		}
	}

}
