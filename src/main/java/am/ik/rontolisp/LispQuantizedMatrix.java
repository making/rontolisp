package am.ik.rontolisp;

import java.util.Locale;

/**
 * A block-quantized weight matrix: ggml's {@link QuantizedFormat Q8_0} storage held
 * verbatim -- the row-major sequence of 34-byte blocks a GGUF tensor is on disk -- with
 * the dimensions beside it. What PyTorch and ggml both make of a quantized tensor:
 * dequantize-on-read and IMMUTABLE, because an element has no slot of its own (a value is
 * {@code q[i] * scale[i / 32]}, and writing one element would mean re-quantizing its
 * block).
 *
 * <p>
 * It is deliberately NOT a fourth width of the sealed {@link LispFloatArray}: every
 * element-wise kernel and every {@code (setf aref)} would gain an arm they can only
 * refuse. {@code aref} / {@code row-major-aref} answer the dequantized {@code double},
 * {@code (setf aref)} signals, {@code array-dimensions} / {@code array-rank} /
 * {@code array-total-size} work, {@code array-element-type} answers the format symbol
 * ({@code q8-0}), and {@code arrayp} / {@code typep 'array} are false -- it is its own
 * type, {@code rontolisp:quantized-matrix}. Rank 2 is the shape that matters; rank 1 is a
 * matrix of one row. The last dimension is a multiple of the block size.
 *
 * <p>
 * The storage is a bare {@code byte[]} of blocks, and that is load-bearing: the packed
 * integer vector ({@link LispIntVector}) stores one byte in eight, which would make this
 * type twice the size of the f32 matrix it exists to shrink. The compiled JVM backend
 * uses the same block bytes behind an int header
 * ({@code codegen.jvm.JvmQuantizedLayout}); {@code read-sequence} /
 * {@code write-sequence} move the blocks as they are, so a checkpoint's tensor is one
 * bulk transfer and a written matrix is one {@code llama.cpp} reads.
 *
 * <p>
 * Built by {@code rontolisp:quantize} (ggml's absmax-over-32 rule) or by a checkpoint
 * reader through {@code rontolisp:make-quantized-matrix} + {@code read-sequence}; there
 * is no literal syntax and there should not be one. Prints as
 * {@code #<quantized-matrix q8-0 (rows cols)>}. Compared by identity, like every array.
 *
 * @param format the block format
 * @param dims the dimension sizes (rank 1 or 2)
 * @param blocks the ggml blocks, row-major
 */
public record LispQuantizedMatrix(QuantizedFormat format, int[] dims, byte[] blocks) implements LispVal {

	/**
	 * Checks a dimension list for the format and answers the block bytes it needs.
	 * @param format the block format
	 * @param dims the dimension sizes
	 * @param operator the operator, for the message
	 * @return the byte count of the blocks
	 * @throws IllegalArgumentException when the shape cannot hold the format
	 */
	public static int byteCount(QuantizedFormat format, int[] dims, String operator) {
		if (dims.length < 1 || dims.length > 2) {
			throw new IllegalArgumentException(
					operator + ": a quantized matrix has rank 1 or 2, got rank " + dims.length);
		}
		for (int d : dims) {
			if (d < 0) {
				throw new IllegalArgumentException(operator + ": a dimension must be non-negative, got " + d);
			}
		}
		int cols = dims[dims.length - 1];
		if (cols % format.blockElements() != 0) {
			throw new IllegalArgumentException(
					operator + ": the last dimension must be a multiple of " + format.blockElements() + " (the "
							+ format.lispName().toLowerCase(Locale.ROOT) + " block), got " + cols);
		}
		long total = 1;
		for (int d : dims) {
			total *= d;
		}
		long bytes = total / format.blockElements() * format.blockBytes();
		if (total > Integer.MAX_VALUE || bytes > Integer.MAX_VALUE - 16) {
			throw new IllegalArgumentException(operator + ": the matrix is too large (" + total + " elements)");
		}
		return (int) bytes;
	}

	/**
	 * The row count: dimension 0 at rank 2, 1 at rank 1.
	 * @return the rows
	 */
	public int rows() {
		return this.dims.length == 2 ? this.dims[0] : 1;
	}

	/**
	 * The column count, the last dimension.
	 * @return the columns
	 */
	public int cols() {
		return this.dims[this.dims.length - 1];
	}

	/**
	 * The rank (1 or 2).
	 * @return the rank
	 */
	public int rank() {
		return this.dims.length;
	}

	/**
	 * The element count.
	 * @return rows times columns
	 */
	public int totalSize() {
		return rows() * cols();
	}

	/**
	 * The block count.
	 * @return elements over the block size
	 */
	public int blockCount() {
		return totalSize() / this.format.blockElements();
	}

	/**
	 * The signed 8-bit quant of the element at a row-major index. Only
	 * {@link QuantizedFormat#Q8_0} exists, so the block layout is spelled here: two scale
	 * bytes, then the quants.
	 * @param flat the row-major index
	 * @return the quant, {@code -128..127}
	 */
	public int quant(int flat) {
		int block = flat / this.format.blockElements();
		return this.blocks[block * this.format.blockBytes() + 2 + flat % this.format.blockElements()];
	}

	/**
	 * The scale of a block: its binary16 {@code d}, widened. Exact -- every binary16
	 * value is a float.
	 * @param block the block index
	 * @return the scale
	 */
	public float scale(int block) {
		int off = block * this.format.blockBytes();
		return Float.float16ToFloat((short) ((this.blocks[off] & 0xff) | (this.blocks[off + 1] << 8)));
	}

	/**
	 * The dequantized element at a row-major index, {@code q * d}, as a double. The
	 * product of an integer below 2^8 and a binary16 is exact in a float and therefore in
	 * a double, so every backend answers the same bits here.
	 * @param flat the row-major index
	 * @return the value
	 */
	public double elementAt(int flat) {
		if (flat < 0 || flat >= totalSize()) {
			throw new IndexOutOfBoundsException("aref: index out of bounds");
		}
		return quant(flat) * scale(flat / this.format.blockElements());
	}

	/**
	 * The dequantized element at the given subscripts.
	 * @param subscripts one per dimension
	 * @return the value
	 */
	public double aref(int... subscripts) {
		if (subscripts.length != this.dims.length) {
			throw new IllegalArgumentException(
					"aref: expected " + this.dims.length + " subscripts, got " + subscripts.length);
		}
		int flat = 0;
		for (int k = 0; k < subscripts.length; k++) {
			if (subscripts[k] < 0 || subscripts[k] >= this.dims[k]) {
				throw new IndexOutOfBoundsException("aref: index out of bounds");
			}
			flat = flat * this.dims[k] + subscripts[k];
		}
		return elementAt(flat);
	}

	@Override
	public String print() {
		StringBuilder sb = new StringBuilder("#<quantized-matrix ");
		sb.append(this.format.lispName().toLowerCase(Locale.ROOT)).append(" (");
		for (int k = 0; k < this.dims.length; k++) {
			if (k > 0) {
				sb.append(' ');
			}
			sb.append(this.dims[k]);
		}
		return sb.append(")>").toString();
	}

}
