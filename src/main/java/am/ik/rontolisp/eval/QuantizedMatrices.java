package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.BFloat16;
import am.ik.rontolisp.LispBFloat16Array;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispQuantizedMatrix;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.QuantizedFormat;

/**
 * The interpreter arm of the {@code rontolisp:quantized-matrix} primitives
 * ({@code .kb/quantized-matrix.md}): {@code quantize}, {@code dequantize},
 * {@code make-quantized-matrix}, {@code quantized-matrix-p} and the two raw accessors
 * {@code vec.lisp}'s integer-dot GEMV reads a matrix through,
 * {@code rontolisp::%quantized-quant} / {@code %quantized-scale}.
 *
 * <p>
 * {@link #quantizeRowQ8_0} is ggml's {@code quantize_row_q8_0_ref} transcribed: the
 * absmax of each 32-element block over the f32 values, {@code d = amax / 127} in f32,
 * {@code id = 1 / d} in f32 (a multiply per element, not a division), {@code d} stored as
 * a binary16 rounded to nearest even, and each quant {@code roundf(x * id)} -- half AWAY
 * from zero, which is {@code Math.round} on the magnitude with the sign put back
 * ({@code Math.round(-2.5f)} is {@code -2}, {@code roundf} gives {@code -3}). A matrix
 * quantized here has the bytes {@code llama-quantize} writes for the same f32 values, and
 * the test oracle is a second transcription of the same C.
 */
final class QuantizedMatrices {

	private QuantizedMatrices() {
	}

	/** {@code (rontolisp:quantize array format)}. */
	static LispVal quantize(String fnName, List<LispVal> args) {
		if (args.size() != 2) {
			throw new LispEvalException(fnName + " expects 2 arguments, got " + args.size());
		}
		if (!(args.get(0) instanceof LispFloatArray src)) {
			throw new LispEvalException(fnName + ": expects a packed float array, got " + args.get(0).print());
		}
		QuantizedFormat format = format(fnName, args.get(1));
		int[] dims = src.dims().clone();
		int bytes = checkedByteCount(format, dims, fnName);
		byte[] blocks = new byte[bytes];
		int total = src.totalSize();
		float[] row = new float[format.blockElements()];
		for (int block = 0; block * format.blockElements() < total; block++) {
			int base = block * format.blockElements();
			for (int k = 0; k < row.length; k++) {
				// A single-float element widens and narrows back exactly; a
				// double-float or bfloat16 one is narrowed to the f32 ggml quantizes.
				row[k] = (float) src.elementAt(base + k);
			}
			quantizeRowQ8_0(row, 0, blocks, block * format.blockBytes());
		}
		return new LispQuantizedMatrix(format, dims, blocks);
	}

	/**
	 * ggml's {@code quantize_row_q8_0_ref} over ONE block: 32 floats at {@code srcOff}
	 * into the 34 bytes at {@code dstOff}.
	 * @param src the values
	 * @param srcOff where the block's 32 values start
	 * @param dst the block storage
	 * @param dstOff where the block's 34 bytes start
	 */
	static void quantizeRowQ8_0(float[] src, int srcOff, byte[] dst, int dstOff) {
		float amax = 0.0f;
		for (int k = 0; k < 32; k++) {
			float v = Math.abs(src[srcOff + k]);
			if (v > amax) {
				amax = v;
			}
		}
		float d = amax / 127.0f;
		float id = d != 0.0f ? 1.0f / d : 0.0f;
		short dh = Float.floatToFloat16(d);
		dst[dstOff] = (byte) dh;
		dst[dstOff + 1] = (byte) (dh >>> 8);
		for (int k = 0; k < 32; k++) {
			float x0 = src[srcOff + k] * id;
			dst[dstOff + 2 + k] = (byte) roundHalfAwayFromZero(x0);
		}
	}

	/**
	 * C's {@code roundf}: to the nearest integer, ties AWAY from zero. {@code Math.round}
	 * rounds a tie towards positive infinity, so the magnitude is rounded and the sign
	 * restored; {@code Math.round(float)} is exact (no {@code + 0.5f} in float
	 * arithmetic, which double-rounds just below a half).
	 * @param x the value, within int range
	 * @return the rounded integer
	 */
	static int roundHalfAwayFromZero(float x) {
		return x < 0 ? -Math.round(-x) : Math.round(x);
	}

	/** {@code (rontolisp:dequantize matrix element-type)}. */
	static LispVal dequantize(String fnName, List<LispVal> args) {
		if (args.size() != 2) {
			throw new LispEvalException(fnName + " expects 2 arguments, got " + args.size());
		}
		LispQuantizedMatrix m = matrix(fnName, args.get(0));
		String elementType = elementTypeName(fnName, args.get(1));
		int[] dims = m.dims().clone();
		int total = m.totalSize();
		switch (elementType) {
			case "SINGLE-FLOAT" -> {
				float[] out = new float[total];
				for (int i = 0; i < total; i++) {
					out[i] = (float) m.elementAt(i);
				}
				return new LispSingleFloatArray(out, dims);
			}
			case "DOUBLE-FLOAT" -> {
				double[] out = new double[total];
				for (int i = 0; i < total; i++) {
					out[i] = m.elementAt(i);
				}
				return new LispDoubleFloatArray(out, dims);
			}
			case "BFLOAT16" -> {
				short[] out = new short[total];
				for (int i = 0; i < total; i++) {
					out[i] = (short) BFloat16.bits(m.elementAt(i));
				}
				return new LispBFloat16Array(out, dims);
			}
			default -> throw new LispEvalException(fnName
					+ ": element-type must be single-float, double-float or bfloat16, got " + args.get(1).print());
		}
	}

	/** {@code (rontolisp:make-quantized-matrix format dims)}: all-zero blocks. */
	static LispVal make(String fnName, List<LispVal> args) {
		if (args.size() != 2) {
			throw new LispEvalException(fnName + " expects 2 arguments, got " + args.size());
		}
		QuantizedFormat format = format(fnName, args.get(0));
		int[] dims = dims(fnName, args.get(1));
		int bytes = checkedByteCount(format, dims, fnName);
		return new LispQuantizedMatrix(format, dims, new byte[bytes]);
	}

	/** {@code (rontolisp:quantized-matrix-p x)}. */
	static LispVal isMatrix(String fnName, List<LispVal> args) {
		if (args.size() != 1) {
			throw new LispEvalException(fnName + " expects 1 argument, got " + args.size());
		}
		return args.get(0) instanceof LispQuantizedMatrix ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	/** {@code (rontolisp::%quantized-quant m row col)}: the signed quant, an integer. */
	static LispVal quant(String fnName, List<LispVal> args) {
		if (args.size() != 3) {
			throw new LispEvalException(fnName + " expects 3 arguments, got " + args.size());
		}
		LispQuantizedMatrix m = matrix(fnName, args.get(0));
		int row = index(fnName, args.get(1), m.rows());
		int col = index(fnName, args.get(2), m.cols());
		return new LispInteger(m.quant(row * m.cols() + col));
	}

	/** {@code (rontolisp::%quantized-scale m row block)}: the block's scale, a double. */
	static LispVal scale(String fnName, List<LispVal> args) {
		if (args.size() != 3) {
			throw new LispEvalException(fnName + " expects 3 arguments, got " + args.size());
		}
		LispQuantizedMatrix m = matrix(fnName, args.get(0));
		int blocksPerRow = m.cols() / m.format().blockElements();
		int row = index(fnName, args.get(1), m.rows());
		int block = index(fnName, args.get(2), blocksPerRow);
		return new LispDouble(m.scale(row * blocksPerRow + block));
	}

	private static int checkedByteCount(QuantizedFormat format, int[] dims, String fnName) {
		try {
			return LispQuantizedMatrix.byteCount(format, dims, fnName);
		}
		catch (IllegalArgumentException ex) {
			throw new LispEvalException(String.valueOf(ex.getMessage()));
		}
	}

	private static LispQuantizedMatrix matrix(String fnName, LispVal value) {
		if (value instanceof LispQuantizedMatrix m) {
			return m;
		}
		throw new LispEvalException(fnName + ": expects a quantized matrix, got " + value.print());
	}

	private static QuantizedFormat format(String fnName, LispVal value) {
		QuantizedFormat format = value instanceof LispSymbol sym ? QuantizedFormat.ofSymbolName(sym.name()) : null;
		if (format == null) {
			throw new LispEvalException(fnName + ": the format must be q8-0, got " + value.print());
		}
		return format;
	}

	private static String elementTypeName(String fnName, LispVal value) {
		if (value instanceof LispSymbol sym) {
			String name = sym.name();
			int colon = name.lastIndexOf(':');
			return colon >= 0 ? name.substring(colon + 1) : name;
		}
		throw new LispEvalException(fnName + ": element-type must be a symbol, got " + value.print());
	}

	private static int[] dims(String fnName, LispVal value) {
		if (value instanceof LispInteger n) {
			return new int[] { (int) n.value() };
		}
		java.util.ArrayList<Integer> sizes = new java.util.ArrayList<>();
		LispVal cur = value;
		while (cur instanceof LispCons cons) {
			if (!(cons.car() instanceof LispInteger n)) {
				throw new LispEvalException(fnName + ": dimensions must be integers, got " + value.print());
			}
			sizes.add((int) n.value());
			cur = cons.cdr();
		}
		if (!(cur instanceof LispNil) || sizes.isEmpty()) {
			throw new LispEvalException(
					fnName + ": dimensions must be an integer or a list of integers, got " + value.print());
		}
		int[] dims = new int[sizes.size()];
		for (int k = 0; k < dims.length; k++) {
			dims[k] = sizes.get(k);
		}
		return dims;
	}

	private static int index(String fnName, LispVal value, int bound) {
		if (!(value instanceof LispInteger n) || n.value() < 0 || n.value() >= bound) {
			throw new LispEvalException(fnName + ": index out of bounds: " + value.print());
		}
		return (int) n.value();
	}

}
