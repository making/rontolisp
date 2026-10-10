package am.ik.rontolisp.runtime;

/**
 * The JVM-backend runtime representation of a {@code rontolisp:quantized-matrix}
 * ({@code .kb/quantized-matrix.md}): one {@code byte[]} holding the header -- the format
 * code and the rank as little-endian ints, then one int per dimension -- followed by the
 * ggml blocks verbatim.
 *
 * <p>
 * A holder of its own rather than the bare {@code byte[]}, because a bare {@code byte[]}
 * is the {@code (unsigned-byte 8)} vector: Java's own array, which Java hands a program
 * and keeps, so no slot of it can tell the two apart
 * ({@code .kb/packed-integer-vectors.md}). An {@code instanceof} test keeps every
 * predicate and accessor exact without reading the octets, and the class sits in this
 * package, so no {@code java:} call counts it as a host object. It stays dumb so it keeps
 * importing nothing and keeps travelling beside a compiled program that builds one
 * ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoQuantizedMatrix {

	/** The header, then the blocks: the layout the compiled {@code _qm*} helpers read. */
	public final byte[] data;

	/**
	 * Holds a matrix's bytes.
	 * @param data the header, then the blocks
	 */
	public RontoQuantizedMatrix(byte[] data) {
		this.data = data;
	}

}
