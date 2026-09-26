package am.ik.rontolisp;

import java.util.Arrays;

/**
 * A packed unsigned-integer vector: a RANK-1 array of unboxed {@code (unsigned-byte 8)},
 * {@code (unsigned-byte 16)} or {@code (unsigned-byte 32)} elements, stored AT THE
 * ELEMENT WIDTH -- a {@code byte[]}, a {@code short[]} or an {@code int[]}. Produced by
 * {@code (make-array n :element-type '(unsigned-byte 8|16|32))} (with no fill pointer,
 * adjustability or displacement -- those, and any rank other than 1, fall back to the
 * general boxed {@link LispArray}) and by ironclad's {@code #N@(...)} table literal. This
 * is the representation that lets the compile backends keep byte/word buffers unboxed
 * (todo 194 stage 2): on the wasm-GC backend the same value is a raw
 * {@code (array (mut i8|i16|i32))}, on the JVM a {@code byte[]} (width 8) or a
 * {@code long[]} (16/32) with a width header.
 *
 * <p>
 * The storage is the width's own Java array because an octet vector is what every HTTP
 * body, binary stream and digest buffer is made of: a {@code long[]} spent eight bytes an
 * octet, and a 256 MiB body read with {@code read-all} peaked at 6.2 GB of live heap, two
 * thirds of it the body's octets held twice in {@code long[]}s
 * ({@code .kb/fetch-http.md}, "Throughput").
 *
 * <p>
 * Element semantics, identical on every backend by construction: a store masks the value
 * to the element width (two's-complement truncation -- exactly what the narrowing store
 * into the width's array does, and what raw {@code i8/i16/i32} storage does on the
 * compiled backends); a read returns the stored value widened UNSIGNED. Storing a
 * non-integer is a type error. {@code aref} past the end is a bounds error (the compiled
 * backends trap).
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

	// The elements at the width: byte[] for 8, short[] for 16, int[] for 32. Each slot
	// holds the low `width` bits of the stored value; a read widens them unsigned.
	private final Object storage;

	private final int length;

	private LispIntVector(int width, Object storage, int length) {
		this.width = width;
		this.storage = storage;
		this.length = length;
	}

	/**
	 * Creates a zero-filled packed vector.
	 * @param width the element width in bits (8, 16 or 32)
	 * @param length the number of elements
	 * @return the vector
	 */
	public static LispIntVector zeros(int width, int length) {
		return new LispIntVector(width, switch (checkWidth(width)) {
			case 8 -> new byte[length];
			case 16 -> new short[length];
			default -> new int[length];
		}, length);
	}

	/**
	 * Creates a packed vector holding {@code values}, each masked to the width. The array
	 * is copied: the vector never aliases it.
	 * @param width the element width in bits (8, 16 or 32)
	 * @param values the elements
	 * @return the vector
	 */
	public static LispIntVector of(int width, long[] values) {
		LispIntVector vector = zeros(width, values.length);
		for (int i = 0; i < values.length; i++) {
			vector.setElement(i, values[i]);
		}
		return vector;
	}

	/**
	 * Wraps {@code octets} as an {@code (unsigned-byte 8)} vector WITHOUT copying: the
	 * vector owns the array from here on, so the caller must not write it again. This is
	 * how a body, a file's bytes or a digest crosses from Java into a program at no cost.
	 * @param octets the octets
	 * @return the vector over them
	 */
	public static LispIntVector wrapOctets(byte[] octets) {
		return new LispIntVector(8, octets, octets.length);
	}

	private static int checkWidth(int width) {
		if (width != 8 && width != 16 && width != 32) {
			throw new IllegalArgumentException("Unsupported packed integer width: " + width);
		}
		return width;
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
	 * Returns the vector length.
	 * @return the number of elements
	 */
	public int length() {
		return this.length;
	}

	/**
	 * Returns the LIVE storage of an {@code (unsigned-byte 8)} vector: element {@code i}
	 * is {@code octets()[i] & 0xFF}, and a write through the array is a store into the
	 * vector.
	 * @return the octets
	 * @throws IllegalStateException when the width is not 8
	 */
	public byte[] octets() {
		if (this.width != 8) {
			throw new IllegalStateException("not an (unsigned-byte 8) vector: width " + this.width);
		}
		return (byte[]) this.storage;
	}

	/**
	 * Returns the LIVE storage of an {@code (unsigned-byte 16)} vector: element {@code i}
	 * is {@code shorts()[i] & 0xFFFF}, a write through the array is a store into the
	 * vector.
	 * @return the 16-bit elements
	 * @throws IllegalStateException when the width is not 16
	 */
	public short[] shorts() {
		if (this.width != 16) {
			throw new IllegalStateException("not an (unsigned-byte 16) vector: width " + this.width);
		}
		return (short[]) this.storage;
	}

	/**
	 * Returns the LIVE storage of an {@code (unsigned-byte 32)} vector: element {@code i}
	 * is {@code ints()[i] & 0xFFFFFFFFL}, a write through the array is a store into the
	 * vector.
	 * @return the 32-bit elements
	 * @throws IllegalStateException when the width is not 32
	 */
	public int[] ints() {
		if (this.width != 32) {
			throw new IllegalStateException("not an (unsigned-byte 32) vector: width " + this.width);
		}
		return (int[]) this.storage;
	}

	/**
	 * Reads an element (always non-negative: the stored value widened unsigned).
	 * @param index the element index
	 * @return the stored element
	 */
	public long elementAt(int index) {
		return switch (this.width) {
			case 8 -> ((byte[]) this.storage)[index] & 0xFFL;
			case 16 -> ((short[]) this.storage)[index] & 0xFFFFL;
			default -> ((int[]) this.storage)[index] & 0xFFFFFFFFL;
		};
	}

	/**
	 * Stores an element, masked to the element width.
	 * @param index the element index
	 * @param value the value to store
	 * @return the value as stored (masked)
	 */
	public long setElement(int index, long value) {
		switch (this.width) {
			case 8 -> ((byte[]) this.storage)[index] = (byte) value;
			case 16 -> ((short[]) this.storage)[index] = (short) value;
			default -> ((int[]) this.storage)[index] = (int) value;
		}
		return value & mask(this.width);
	}

	/**
	 * Stores {@code value}, masked to the width, into every element of
	 * {@code [from, to)}.
	 * @param from the first index
	 * @param to the index past the last
	 * @param value the value to store
	 */
	public void fill(int from, int to, long value) {
		switch (this.width) {
			case 8 -> Arrays.fill((byte[]) this.storage, from, to, (byte) value);
			case 16 -> Arrays.fill((short[]) this.storage, from, to, (short) value);
			default -> Arrays.fill((int[]) this.storage, from, to, (int) value);
		}
	}

	/**
	 * Copies {@code count} elements of {@code src} from {@code srcPos} into {@code dst}
	 * at {@code dstPos}, each masked to {@code dst}'s width -- one
	 * {@link System#arraycopy} when the widths agree (overlapping ranges of one vector
	 * included), an element loop otherwise.
	 * @param src the source vector
	 * @param srcPos the first source index
	 * @param dst the destination vector
	 * @param dstPos the first destination index
	 * @param count the number of elements
	 */
	public static void copy(LispIntVector src, int srcPos, LispIntVector dst, int dstPos, int count) {
		if (src.width == dst.width) {
			System.arraycopy(src.storage, srcPos, dst.storage, dstPos, count);
			return;
		}
		for (int i = 0; i < count; i++) {
			dst.setElement(dstPos + i, src.elementAt(srcPos + i));
		}
	}

	/**
	 * Returns a fresh vector of the same width holding elements {@code [from, to)}.
	 * @param from the first index
	 * @param to the index past the last
	 * @return the copy
	 */
	public LispIntVector copyOfRange(int from, int to) {
		return new LispIntVector(this.width, switch (this.width) {
			case 8 -> Arrays.copyOfRange((byte[]) this.storage, from, to);
			case 16 -> Arrays.copyOfRange((short[]) this.storage, from, to);
			default -> Arrays.copyOfRange((int[]) this.storage, from, to);
		}, to - from);
	}

	/**
	 * Returns a fresh vector of the same width and elements.
	 * @return the copy
	 */
	public LispIntVector copy() {
		return copyOfRange(0, this.length);
	}

	/**
	 * Returns the elements widened unsigned into a fresh {@code long[]}.
	 * @return the elements
	 */
	public long[] toLongArray() {
		long[] values = new long[this.length];
		for (int i = 0; i < this.length; i++) {
			values[i] = elementAt(i);
		}
		return values;
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
		LispVal[] boxed = new LispVal[this.length];
		for (int i = 0; i < this.length; i++) {
			boxed[i] = new LispInteger(elementAt(i));
		}
		return new LispArray(new int[] { this.length }, boxed);
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
			for (int i = 0; i < this.length; i++) {
				if (i > 0) {
					sb.append(' ');
				}
				sb.append(elementAt(i));
			}
			return sb.append(')').toString();
		}
		finally {
			RenderCycleGuard.exit();
		}
	}

}
