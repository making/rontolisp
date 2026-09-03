package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.BFloat16;
import am.ik.rontolisp.FloatArrayAccessHook;
import am.ik.rontolisp.LispBFloat16Array;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The interpreter arm of {@code rontolisp:widen-float-bits} / {@code
 * rontolisp:narrow-float-bits} (.todo/671): bulk conversion between a packed {@code
 * (unsigned-byte 16)} vector of IEEE {@code :float16} or {@code :bfloat16} bit patterns
 * and an existing packed float array, row-major from a {@code :start} offset in whichever
 * side is the DESTINATION.
 *
 * <p>
 * Every loop below runs against the raw {@code float[]}/{@code double[]}/{@code long[]}
 * backing (through {@link LispFloatArray#storage()}/{@link FloatArrayAccessHook} for a
 * write target, {@link LispFloatArray#data()} for a read source, exactly
 * {@link PackedBuffer}'s idiom) rather than the boxed {@code elementAt}/{@code
 * setElement} accessors: a 1.1B-element checkpoint tensor through a per-element virtual
 * call and a fresh {@code LispDouble} box per element does not reach the Gelem/s-class
 * throughput this primitive exists for (a tight primitive-array loop is what
 * {@code .todo/482}'s {@code Load.java} measured; the boxed path is orders of magnitude
 * slower and was never benchmarked as a candidate).
 *
 * <p>
 * The destination's/source's concrete packed-float width is dispatched with an EXHAUSTIVE
 * {@code switch} over the sealed {@link LispFloatArray} permits, not an
 * {@code instanceof LispSingleFloatArray} check with "anything else is double-float" --
 * so a third permit (a future {@code #bf16} array, {@code .todo/484}) fails to COMPILE
 * here instead of silently widening into the wrong width.
 */
final class FloatBitsWidening {

	private FloatBitsWidening() {
	}

	/**
	 * {@code (rontolisp:widen-float-bits bits format dst &key (start 0))}.
	 * @param fnName the operator name, for error messages
	 * @param args the argument list
	 * @return {@code dst}
	 */
	static LispVal widen(String fnName, List<LispVal> args) {
		if (args.size() < 3) {
			throw new LispEvalException(fnName + " expects at least 3 arguments");
		}
		if (!(args.get(0) instanceof LispIntVector bits) || bits.width() != 16) {
			throw new LispEvalException(fnName + ": bits must be a packed (unsigned-byte 16) vector");
		}
		boolean float16 = isFloat16Format(fnName, args.get(1));
		if (!(args.get(2) instanceof LispFloatArray dst)) {
			throw new LispEvalException(fnName + ": dst must be a packed float array");
		}
		int start = parseStart(fnName, args, 3);
		long[] bitsData = bits.data();
		int n = bitsData.length;
		checkBounds(fnName, start, n, dst.totalSize());
		switch (dst) {
			case LispSingleFloatArray f -> {
				float[] out = (float[]) FloatArrayAccessHook.written(f.storage());
				if (float16) {
					for (int i = 0; i < n; i++) {
						out[start + i] = Float.float16ToFloat((short) bitsData[i]);
					}
				}
				else {
					for (int i = 0; i < n; i++) {
						out[start + i] = Float.intBitsToFloat((int) bitsData[i] << 16);
					}
				}
			}
			case LispDoubleFloatArray d -> {
				double[] out = (double[]) FloatArrayAccessHook.written(d.storage());
				if (float16) {
					for (int i = 0; i < n; i++) {
						out[start + i] = Float.float16ToFloat((short) bitsData[i]);
					}
				}
				else {
					for (int i = 0; i < n; i++) {
						out[start + i] = Float.intBitsToFloat((int) bitsData[i] << 16);
					}
				}
			}
			// A bfloat16 destination is a TEMPORARY decline, not an impossibility: from
			// :bfloat16 patterns it is a straight copy and from :float16 one conversion,
			// which is a capability to be added deliberately rather than a side effect
			// of the width existing.
			case LispBFloat16Array ignored ->
				throw new LispEvalException(fnName + ": does not yet write a bfloat16 destination");
		}
		return dst;
	}

	/**
	 * {@code (rontolisp:narrow-float-bits src format dst &key (start 0))}: the inverse of
	 * {@link #widen}. {@code :bfloat16} narrowing rounds to nearest even, matching
	 * {@code .todo/487}'s {@code bfloat16-bits} (a separate copy of the same six-line
	 * trick -- see {@link #bfloat16BitsOf}, kept so this primitive needs no dependency on
	 * that item's landing order).
	 * @param fnName the operator name, for error messages
	 * @param args the argument list
	 * @return {@code dst}
	 */
	static LispVal narrow(String fnName, List<LispVal> args) {
		if (args.size() < 3) {
			throw new LispEvalException(fnName + " expects at least 3 arguments");
		}
		if (!(args.get(0) instanceof LispFloatArray src)) {
			throw new LispEvalException(fnName + ": src must be a packed float array");
		}
		boolean float16 = isFloat16Format(fnName, args.get(1));
		if (!(args.get(2) instanceof LispIntVector dst) || dst.width() != 16) {
			throw new LispEvalException(fnName + ": dst must be a packed (unsigned-byte 16) vector");
		}
		int start = parseStart(fnName, args, 3);
		int n = src.totalSize();
		checkBounds(fnName, start, n, dst.length());
		long[] out = dst.data();
		switch (src) {
			case LispSingleFloatArray f -> {
				float[] in = f.data();
				if (float16) {
					for (int i = 0; i < n; i++) {
						out[start + i] = Float.floatToFloat16(in[i]) & 0xFFFFL;
					}
				}
				else {
					for (int i = 0; i < n; i++) {
						out[start + i] = bfloat16BitsOf(in[i]);
					}
				}
			}
			case LispDoubleFloatArray d -> {
				double[] in = d.data();
				if (float16) {
					for (int i = 0; i < n; i++) {
						out[start + i] = Float.floatToFloat16((float) in[i]) & 0xFFFFL;
					}
				}
				else {
					for (int i = 0; i < n; i++) {
						out[start + i] = bfloat16BitsOf((float) in[i]);
					}
				}
			}
			// A bfloat16 SOURCE is the same temporary decline as the destination above:
			// to :bfloat16 patterns it is a straight copy, to :float16 one conversion.
			case LispBFloat16Array ignored ->
				throw new LispEvalException(fnName + ": does not yet read a bfloat16 source");
		}
		return dst;
	}

	/**
	 * The bf16 round-to-nearest-even narrow of a float, as an unsigned 16-bit pattern.
	 * The rounding itself -- including the NaN cases a plain bias-add gets wrong -- lives
	 * ONCE, in {@link BFloat16}, so this primitive and {@code (setf (aref a i))} on a
	 * packed bfloat16 array cannot answer different patterns for one value.
	 * @param f the value to narrow
	 * @return the bf16 bit pattern, unsigned in {@code [0, 65535]}
	 */
	private static long bfloat16BitsOf(float f) {
		return BFloat16.bits(f);
	}

	private static boolean isFloat16Format(String fnName, LispVal formatArg) {
		if (formatArg instanceof LispSymbol sym) {
			if (LispNames.FLOAT16_KEYWORD.equals(sym.name())) {
				return true;
			}
			if (LispNames.BFLOAT16_KEYWORD.equals(sym.name())) {
				return false;
			}
		}
		throw new LispEvalException(fnName + ": format must be :float16 or :bfloat16");
	}

	private static int parseStart(String fnName, List<LispVal> args, int from) {
		int start = 0;
		for (int i = from; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && LispNames.START_KEYWORD.equals(kw.name())) {
				LispVal v = args.get(i + 1);
				if (!(v instanceof LispInteger li)) {
					throw new LispEvalException(fnName + ": :start must be an integer");
				}
				start = (int) li.value();
			}
		}
		if (start < 0) {
			throw new LispEvalException(fnName + ": :start must be non-negative");
		}
		return start;
	}

	private static void checkBounds(String fnName, int start, int n, int dstTotal) {
		if ((long) start + n > dstTotal) {
			throw new LispEvalException(
					fnName + ": destination too small (" + dstTotal + ") for " + n + " elements at :start " + start);
		}
	}

}
