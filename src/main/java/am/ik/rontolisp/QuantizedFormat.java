package am.ik.rontolisp;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The block-quantization formats a {@link LispQuantizedMatrix} can hold. Each one is a
 * ggml storage format, byte for byte: a matrix is a row-major sequence of BLOCKS, every
 * block {@link #blockElements()} consecutive elements of one row packed into
 * {@link #blockBytes()} bytes, so a tensor read out of a GGUF file is the matrix's own
 * storage and a matrix written back is what {@code llama.cpp} reads.
 *
 * <p>
 * Only {@link #Q8_0} exists today. The enum is what a second format ({@code q4-k},
 * {@code .todo/490}'s device successor) joins; every switch over it is written as an
 * EXPRESSION so a new constant is a compile error at each site that decides something.
 */
public enum QuantizedFormat {

	/**
	 * ggml's {@code Q8_0}: blocks of 32 elements, each block one IEEE binary16 scale
	 * {@code d} followed by 32 signed 8-bit quants, {@code value = q * d}. 34 bytes a
	 * block, 1.0625 bytes an element.
	 */
	Q8_0("Q8-0", 32, 34);

	private final String lispName;

	private final int blockElements;

	private final int blockBytes;

	QuantizedFormat(String lispName, int blockElements, int blockBytes) {
		this.lispName = lispName;
		this.blockElements = blockElements;
		this.blockBytes = blockBytes;
	}

	/**
	 * The symbol name a program spells the format with ({@code q8-0}), upper-cased as the
	 * reader leaves it.
	 * @return the symbol name
	 */
	public String lispName() {
		return this.lispName;
	}

	/**
	 * The elements one block holds; a row length must be a multiple of it.
	 * @return the block size in elements
	 */
	public int blockElements() {
		return this.blockElements;
	}

	/**
	 * The bytes one block occupies.
	 * @return the block size in bytes
	 */
	public int blockBytes() {
		return this.blockBytes;
	}

	/**
	 * The format a designator symbol names, by its LOCAL name -- {@code q8-0},
	 * {@code rontolisp:q8-0} and {@code :q8-0} all name {@link #Q8_0} -- or {@code null}
	 * for any other symbol.
	 * @param symbolName the symbol's name as the resolver left it
	 * @return the format, or {@code null}
	 */
	public static @Nullable QuantizedFormat ofSymbolName(String symbolName) {
		int colon = symbolName.lastIndexOf(':');
		String local = (colon >= 0 ? symbolName.substring(colon + 1) : symbolName).toUpperCase(Locale.ROOT);
		for (QuantizedFormat format : values()) {
			if (format.lispName.equals(local)) {
				return format;
			}
		}
		return null;
	}

}
