package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's opt-in {@code --simd} acceleration of the {@code linalg:} kernels: it
 * replaces thirty-four of the {@code linalg.lisp} defuns with native
 * {@link LispFunction}s driving the lane loops in {@link LinalgSimdKernels}. The
 * {@code linalg:} sibling of {@link VecSimd}, deliberately a separate class --
 * {@code vec.lisp} and {@code linalg.lisp} never call each other, so their interceptors
 * do not either. Only the KERNELS are shared.
 *
 * <h2>The fallback protocol</h2>
 *
 * {@code vec:}'s kernels accept packed float arrays and nothing else, so
 * {@link VecSimd}'s natives can simply signal on anything else. {@code linalg:} cannot:
 * its defuns also accept general (boxed) arrays, mixed widths, scalar operands on either
 * side, plain numbers, and mismatched shapes (which they turn into a specific
 * {@code error}). So each kernel here is a PARTIAL function -- it returns Java
 * {@code null} for any input it does not handle -- and the wrapper then applies the
 * original {@code linalg.lisp} defun, captured before the override. The scalar library
 * therefore remains the single source of truth for every edge case, including the exact
 * error messages, and nothing is duplicated. (Java {@code null} is safe as the sentinel:
 * Lisp {@code nil} is {@link LispNil}, and none of the intercepted members returns it.)
 *
 * <p>
 * This is opt-in per invocation ({@code rontolisp prog.lisp --simd}). The DEFAULT
 * interpreter keeps running the scalar {@code linalg.lisp} defuns -- they are the
 * cross-backend byte-identity oracle and must not change.
 *
 * <p>
 * {@code mean}, {@code matmul}, {@code flatten} and {@code solve} are accelerated
 * TRANSITIVELY: their defun bodies call {@code sum} / {@code dot} / {@code reshape},
 * which resolve through the Lisp-2 global function namespace to the installed natives.
 * {@code emap}, {@code det}, {@code inv} and {@code array-equal} are never intercepted.
 *
 * <p>
 * {@link #available()} and {@link #install} are the only two entry points into
 * {@link LinalgSimdKernels}, which is what makes
 * {@code src/web/java/.../Target_LinalgSimd.java} sufficient to keep the incubator Vector
 * API out of the browser Web Image build.
 *
 * @see LinalgSimdKernels
 * @see VecSimd
 */
public final class LinalgSimd {

	private LinalgSimd() {
	}

	/**
	 * Returns whether the {@code jdk.incubator.vector} module is present in this runtime,
	 * by linking {@link LinalgSimdKernels}.
	 * @return {@code true} when the linalg: kernels can be vectorized
	 */
	public static boolean available() {
		try {
			return LinalgSimdKernels.laneCount() > 0;
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * Overrides the accelerated {@code linalg:} functions in the given (global)
	 * environment. Must be called AFTER the scalar {@code linalg.lisp} forms have been
	 * evaluated into the same environment -- each override captures the defun it replaces
	 * and falls back to it -- and only when {@link #available()} is {@code true}.
	 * @param globalEnv the global environment holding the loaded linalg library
	 * @param evaluator the evaluator used to apply a captured scalar defun on fallback
	 */
	public static void install(Environment globalEnv, LispEvaluator evaluator) {
		Elementwise add = new Elementwise(LinalgSimdKernels::add, LinalgSimdKernels::addF, LinalgSimdKernels::addScalar,
				LinalgSimdKernels::addScalarF, (s, x) -> LinalgSimdKernels.addScalar(x, s),
				(s, x) -> LinalgSimdKernels.addScalarF(x, s));
		Elementwise sub = new Elementwise(LinalgSimdKernels::sub, LinalgSimdKernels::subF, LinalgSimdKernels::subScalar,
				LinalgSimdKernels::subScalarF, LinalgSimdKernels::subFrom, LinalgSimdKernels::subFromF);
		Elementwise mul = new Elementwise(LinalgSimdKernels::mul, LinalgSimdKernels::mulF, LinalgSimdKernels::mulScalar,
				LinalgSimdKernels::mulScalarF, (s, x) -> LinalgSimdKernels.mulScalar(x, s),
				(s, x) -> LinalgSimdKernels.mulScalarF(x, s));
		Elementwise div = new Elementwise(LinalgSimdKernels::div, LinalgSimdKernels::divF, LinalgSimdKernels::divScalar,
				LinalgSimdKernels::divScalarF, LinalgSimdKernels::divFrom, LinalgSimdKernels::divFromF);

		// The comparison-select ufuncs (todo 109 Phase 3) share the elementwise
		// dispatch: same three %la-bcast shapes, same decline protocol. linalg:clip
		// and linalg:relu are accelerated transitively -- their defuns compose
		// linalg:maximum / linalg:minimum (the square / reciprocal pattern).
		Elementwise maximum = new Elementwise(LinalgSimdKernels::maximum, LinalgSimdKernels::maximumF,
				LinalgSimdKernels::maxScalar, LinalgSimdKernels::maxScalarF, LinalgSimdKernels::maxFrom,
				LinalgSimdKernels::maxFromF);
		Elementwise minimum = new Elementwise(LinalgSimdKernels::minimum, LinalgSimdKernels::minimumF,
				LinalgSimdKernels::minScalar, LinalgSimdKernels::minScalarF, LinalgSimdKernels::minFrom,
				LinalgSimdKernels::minFromF);

		define(globalEnv, evaluator, LispNames.LINALG_ADD, 2, args -> elementwise(add, args));
		define(globalEnv, evaluator, LispNames.LINALG_SUB, 2, args -> elementwise(sub, args));
		define(globalEnv, evaluator, LispNames.LINALG_MUL, 2, args -> elementwise(mul, args));
		define(globalEnv, evaluator, LispNames.LINALG_DIV, 2, args -> elementwise(div, args));
		define(globalEnv, evaluator, LispNames.LINALG_MAXIMUM, 2, args -> elementwise(maximum, args));
		define(globalEnv, evaluator, LispNames.LINALG_MINIMUM, 2, args -> elementwise(minimum, args));
		define(globalEnv, evaluator, LispNames.LINALG_SUM, 1, LinalgSimd::sum);
		define(globalEnv, evaluator, LispNames.LINALG_NORM, 1, LinalgSimd::norm);
		define(globalEnv, evaluator, LispNames.LINALG_AMAX, 1, args -> extremum(args, true));
		define(globalEnv, evaluator, LispNames.LINALG_AMIN, 1, args -> extremum(args, false));
		define(globalEnv, evaluator, LispNames.LINALG_ARGMAX, 1, args -> argExtremum(args, true));
		define(globalEnv, evaluator, LispNames.LINALG_ARGMIN, 1, args -> argExtremum(args, false));
		define(globalEnv, evaluator, LispNames.LINALG_TRACE, 1, LinalgSimd::trace);
		define(globalEnv, evaluator, LispNames.LINALG_TRANSPOSE, 1, LinalgSimd::transpose);
		define(globalEnv, evaluator, LispNames.LINALG_RESHAPE, 2, LinalgSimd::reshape);
		define(globalEnv, evaluator, LispNames.LINALG_DOT, 2, LinalgSimd::dot);
		define(globalEnv, evaluator, LispNames.LINALG_OUTER, 2, LinalgSimd::outer);
		// The named element-wise unary ufuncs (todo 109). linalg:square and
		// linalg:reciprocal are accelerated transitively -- their defuns call
		// linalg:mul / linalg:div, which resolve to the natives installed above.
		define(globalEnv, evaluator, LispNames.LINALG_EXP, 1,
				args -> unary(args, LinalgSimdKernels::exp, LinalgSimdKernels::expF));
		define(globalEnv, evaluator, LispNames.LINALG_LOG, 1,
				args -> unary(args, LinalgSimdKernels::log, LinalgSimdKernels::logF));
		define(globalEnv, evaluator, LispNames.LINALG_TANH, 1,
				args -> unary(args, LinalgSimdKernels::tanh, LinalgSimdKernels::tanhF));
		define(globalEnv, evaluator, LispNames.LINALG_SIN, 1,
				args -> unary(args, LinalgSimdKernels::sin, LinalgSimdKernels::sinF));
		define(globalEnv, evaluator, LispNames.LINALG_COS, 1,
				args -> unary(args, LinalgSimdKernels::cos, LinalgSimdKernels::cosF));
		define(globalEnv, evaluator, LispNames.LINALG_TAN, 1,
				args -> unary(args, LinalgSimdKernels::tan, LinalgSimdKernels::tanF));
		define(globalEnv, evaluator, LispNames.LINALG_ASIN, 1,
				args -> unary(args, LinalgSimdKernels::asin, LinalgSimdKernels::asinF));
		define(globalEnv, evaluator, LispNames.LINALG_ACOS, 1,
				args -> unary(args, LinalgSimdKernels::acos, LinalgSimdKernels::acosF));
		define(globalEnv, evaluator, LispNames.LINALG_ATAN, 1,
				args -> unary(args, LinalgSimdKernels::atan, LinalgSimdKernels::atanF));
		define(globalEnv, evaluator, LispNames.LINALG_SINH, 1,
				args -> unary(args, LinalgSimdKernels::sinh, LinalgSimdKernels::sinhF));
		define(globalEnv, evaluator, LispNames.LINALG_COSH, 1,
				args -> unary(args, LinalgSimdKernels::cosh, LinalgSimdKernels::coshF));
		define(globalEnv, evaluator, LispNames.LINALG_SQRT, 1,
				args -> unary(args, LinalgSimdKernels::sqrt, LinalgSimdKernels::sqrtF));
		define(globalEnv, evaluator, LispNames.LINALG_ABS, 1,
				args -> unary(args, LinalgSimdKernels::abs, LinalgSimdKernels::absF));
		define(globalEnv, evaluator, LispNames.LINALG_NEGATIVE, 1,
				args -> unary(args, LinalgSimdKernels::negative, LinalgSimdKernels::negativeF));
		define(globalEnv, evaluator, LispNames.LINALG_SIGN, 1,
				args -> unary(args, LinalgSimdKernels::sign, LinalgSimdKernels::signF));
		// The internal CNN window unfolding pair (todo 117): pure index arithmetic, no
		// lanes -- intercepted because the boxed do-loop dominates the accelerated
		// convolution runs (~97% of ch07 train time under --simd was im2col/col2im).
		define(globalEnv, evaluator, LispNames.LINALG_IM2COL, 5, LinalgSimd::im2col);
		define(globalEnv, evaluator, LispNames.LINALG_COL2IM, 6, LinalgSimd::col2im);
	}

	/**
	 * Binds {@code linalg:<member>} to a native that tries {@code kernel} and, when the
	 * kernel declines (or the arity is wrong), applies the {@code linalg.lisp} defun it
	 * replaced.
	 */
	private static void define(Environment globalEnv, LispEvaluator evaluator, String member, int arity,
			Kernel kernel) {
		// A %-prefixed member is an internal symbol, whose canonical qualified spelling
		// carries the double colon (linalg::%la-im2col).
		String qualified = member.startsWith("%") ? LispNames.LINALG_PKG + "::" + member
				: LispNames.LINALG_PKG + ":" + member;
		LispVal scalarDefun = globalEnv.lookupFunctionOrNull(qualified);
		if (scalarDefun == null) {
			throw new IllegalStateException("linalg.lisp must be loaded before " + qualified + " can be accelerated");
		}
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() == arity) {
				LispVal fast = kernel.apply(args);
				if (fast != null) {
					return fast;
				}
			}
			return evaluator.applyGlobal(scalarDefun, args);
		}));
	}

	// --- element-wise (add / sub / mul / div) ----------------------------------------

	/**
	 * The three shapes {@code linalg::%la-bcast} distinguishes: array with array (equal
	 * shapes), array with scalar, and scalar with array. Anything else -- two numbers, a
	 * general (boxed) array, a ratio scalar, mixed widths, mismatched shapes -- declines,
	 * and the scalar defun handles it (or signals).
	 */
	private static @Nullable LispVal elementwise(Elementwise op, List<LispVal> args) {
		LispVal av = args.get(0);
		LispVal bv = args.get(1);
		LispFloatArray a = packed(av);
		LispFloatArray b = packed(bv);
		if (a != null && b != null) {
			if (!Arrays.equals(a.dims(), b.dims())) {
				return null;
			}
			if (a instanceof LispDoubleFloatArray x && b instanceof LispDoubleFloatArray y) {
				return like(a, op.dd().apply(x.data(), y.data()));
			}
			if (a instanceof LispSingleFloatArray x && b instanceof LispSingleFloatArray y) {
				return like(a, op.ff().apply(x.data(), y.data()));
			}
			// Mixed widths: the oracle widens both operands to double and keeps a's
			// width. Rare enough to leave to it.
			return null;
		}
		if (a != null) {
			Double s = scalar(bv);
			return s == null ? null : switch (a) {
				case LispDoubleFloatArray x -> like(a, op.ds().apply(x.data(), s));
				case LispSingleFloatArray x -> like(a, op.fs().apply(x.data(), s));
			};
		}
		if (b != null) {
			Double s = scalar(av);
			return s == null ? null : switch (b) {
				case LispDoubleFloatArray x -> like(b, op.sd().apply(s, x.data()));
				case LispSingleFloatArray x -> like(b, op.sf().apply(s, x.data()));
			};
		}
		return null;
	}

	/**
	 * A named element-wise unary ufunc over a packed operand of either width and any
	 * rank; anything else (a general boxed array, a plain number) declines to the defun.
	 */
	private static @Nullable LispVal unary(List<LispVal> args, UnaryD f64, UnaryF f32) {
		LispFloatArray a = packed(args.get(0));
		return a == null ? null : switch (a) {
			case LispDoubleFloatArray x -> like(a, f64.apply(x.data()));
			case LispSingleFloatArray x -> like(a, f32.apply(x.data()));
		};
	}

	// --- reductions -------------------------------------------------------------------

	private static @Nullable LispVal sum(List<LispVal> args) {
		LispFloatArray a = nonEmpty(args.get(0));
		return a == null ? null : new LispDouble(switch (a) {
			case LispDoubleFloatArray x -> LinalgSimdKernels.sum(x.data());
			case LispSingleFloatArray x -> LinalgSimdKernels.sumF(x.data());
		});
	}

	private static @Nullable LispVal norm(List<LispVal> args) {
		LispFloatArray a = nonEmpty(args.get(0));
		return a == null ? null : new LispDouble(switch (a) {
			case LispDoubleFloatArray x -> LinalgSimdKernels.norm(x.data());
			case LispSingleFloatArray x -> LinalgSimdKernels.normF(x.data());
		});
	}

	private static @Nullable LispVal extremum(List<LispVal> args, boolean max) {
		LispFloatArray a = nonEmpty(args.get(0));
		return a == null ? null : new LispDouble(switch (a) {
			case LispDoubleFloatArray x -> max ? LinalgSimdKernels.amax(x.data()) : LinalgSimdKernels.amin(x.data());
			case LispSingleFloatArray x -> max ? LinalgSimdKernels.amaxF(x.data()) : LinalgSimdKernels.aminF(x.data());
		});
	}

	/** {@code argmax}/{@code argmin} are vector-only in linalg.lisp (they use length). */
	private static @Nullable LispVal argExtremum(List<LispVal> args, boolean max) {
		LispFloatArray a = nonEmpty(args.get(0));
		if (a == null || a.rank() != 1) {
			return null;
		}
		return new LispInteger(switch (a) {
			case LispDoubleFloatArray x ->
				max ? LinalgSimdKernels.argmax(x.data()) : LinalgSimdKernels.argmin(x.data());
			case LispSingleFloatArray x ->
				max ? LinalgSimdKernels.argmaxF(x.data()) : LinalgSimdKernels.argminF(x.data());
		});
	}

	private static @Nullable LispVal trace(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		if (a == null || a.rank() != 2 || a.dims()[0] != a.dims()[1]) {
			return null;
		}
		int n = a.dims()[0];
		return new LispDouble(switch (a) {
			case LispDoubleFloatArray x -> LinalgSimdKernels.trace(x.data(), n);
			case LispSingleFloatArray x -> LinalgSimdKernels.traceF(x.data(), n);
		});
	}

	// --- shape ------------------------------------------------------------------------

	private static @Nullable LispVal transpose(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		if (a == null || a.rank() > 2) {
			return null;
		}
		if (a.rank() == 1) {
			// linalg.lisp returns a vector unchanged -- the same object, so eq holds.
			return args.get(0);
		}
		int r = a.dims()[0];
		int c = a.dims()[1];
		int[] dims = { c, r };
		return switch (a) {
			case LispDoubleFloatArray x -> new LispDoubleFloatArray(LinalgSimdKernels.transpose(x.data(), r, c), dims);
			case LispSingleFloatArray x -> new LispSingleFloatArray(LinalgSimdKernels.transposeF(x.data(), r, c), dims);
		};
	}

	private static @Nullable LispVal reshape(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		if (a == null) {
			return null;
		}
		int[] dims = shape(args.get(1));
		if (dims == null) {
			return null;
		}
		long total = 1;
		for (int d : dims) {
			total *= d;
		}
		if (total != a.totalSize()) {
			return null;
		}
		return switch (a) {
			case LispDoubleFloatArray x -> new LispDoubleFloatArray(LinalgSimdKernels.copy(x.data()), dims);
			case LispSingleFloatArray x -> new LispSingleFloatArray(LinalgSimdKernels.copyF(x.data()), dims);
		};
	}

	// --- products ---------------------------------------------------------------------

	/**
	 * The numpy dispatch of {@code linalg:dot}, for two packed operands of the same width
	 * and rank {@code <= 2}. A scalar operand declines (the defun routes it to
	 * {@code linalg:mul}, itself intercepted); so does any dimension mismatch, so the
	 * defun raises its own error.
	 */
	private static @Nullable LispVal dot(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		LispFloatArray b = packed(args.get(1));
		if (a == null || b == null || a.getClass() != b.getClass() || a.rank() > 2 || b.rank() > 2) {
			return null;
		}
		boolean single = a instanceof LispSingleFloatArray;
		if (a.rank() == 1 && b.rank() == 1) {
			if (a.dims()[0] != b.dims()[0]) {
				return null;
			}
			return new LispDouble(single ? LinalgSimdKernels.dotF(floats(a), floats(b))
					: LinalgSimdKernels.dot(doubles(a), doubles(b)));
		}
		if (a.rank() == 2 && b.rank() == 1) {
			int rows = a.dims()[0];
			int cols = a.dims()[1];
			if (cols != b.dims()[0]) {
				return null;
			}
			int[] dims = { rows };
			return single ? new LispSingleFloatArray(LinalgSimdKernels.matvecF(floats(a), rows, cols, floats(b)), dims)
					: new LispDoubleFloatArray(LinalgSimdKernels.matvec(doubles(a), rows, cols, doubles(b)), dims);
		}
		if (a.rank() == 1 && b.rank() == 2) {
			int n = b.dims()[0];
			int p = b.dims()[1];
			if (a.dims()[0] != n) {
				return null;
			}
			// A row vector times a matrix is the n = 1 case of the matrix product.
			int[] dims = { p };
			return single ? new LispSingleFloatArray(LinalgSimdKernels.matmulF(floats(a), floats(b), 1, n, p), dims)
					: new LispDoubleFloatArray(LinalgSimdKernels.matmul(doubles(a), doubles(b), 1, n, p), dims);
		}
		int n = a.dims()[0];
		int m = a.dims()[1];
		int p = b.dims()[1];
		if (m != b.dims()[0]) {
			return null;
		}
		int[] dims = { n, p };
		return single ? new LispSingleFloatArray(LinalgSimdKernels.matmulF(floats(a), floats(b), n, m, p), dims)
				: new LispDoubleFloatArray(LinalgSimdKernels.matmul(doubles(a), doubles(b), n, m, p), dims);
	}

	/**
	 * {@code linalg:outer} flattens both operands first, so the packed backing already IS
	 * the flattened data at any rank.
	 */
	private static @Nullable LispVal outer(List<LispVal> args) {
		LispFloatArray u = packed(args.get(0));
		LispFloatArray v = packed(args.get(1));
		if (u == null || v == null || u.getClass() != v.getClass()) {
			return null;
		}
		int[] dims = { u.totalSize(), v.totalSize() };
		return u instanceof LispSingleFloatArray
				? new LispSingleFloatArray(LinalgSimdKernels.outerF(floats(u), floats(v)), dims)
				: new LispDoubleFloatArray(LinalgSimdKernels.outer(doubles(u), doubles(v)), dims);
	}

	// --- CNN window unfolding: %la-im2col / %la-col2im (todo 117) ----------------------

	/**
	 * {@code (linalg::%la-im2col x fh fw stride pad)} over a rank-4 packed NCHW operand.
	 * Anything else -- a general boxed array, a non-integer window parameter, a window
	 * larger than the padded extent (whose floor is no longer a plain truncation) --
	 * declines to the defun.
	 */
	private static @Nullable LispVal im2col(List<LispVal> args) {
		LispFloatArray x = packed(args.get(0));
		if (x == null || x.rank() != 4) {
			return null;
		}
		int[] d = x.dims();
		int[] p = windowParams(d[2], d[3], args.get(1), args.get(2), args.get(3), args.get(4));
		if (p == null) {
			return null;
		}
		int n = d[0];
		int c = d[1];
		int fh = p[0], fw = p[1], stride = p[2], pad = p[3], oh = p[4], ow = p[5];
		long rows = (long) n * oh * ow;
		long cols = (long) c * fh * fw;
		if (!sizeFits(rows) || !sizeFits(cols) || !sizeFits(rows * cols)) {
			return null;
		}
		int[] dims = { (int) rows, (int) cols };
		return switch (x) {
			case LispDoubleFloatArray a -> new LispDoubleFloatArray(
					LinalgSimdKernels.im2col(a.data(), n, c, d[2], d[3], fh, fw, stride, pad), dims);
			case LispSingleFloatArray a -> new LispSingleFloatArray(
					LinalgSimdKernels.im2colF(a.data(), n, c, d[2], d[3], fh, fw, stride, pad), dims);
		};
	}

	/**
	 * {@code (linalg::%la-col2im col dims fh fw stride pad)}: the adjoint scatter-add
	 * into a fresh zero rank-4 array of the given dims. The column matrix must hold
	 * exactly the unfolded element count; any surplus or shortfall declines (the defun
	 * signals on the shortfall).
	 */
	private static @Nullable LispVal col2im(List<LispVal> args) {
		LispFloatArray col = packed(args.get(0));
		if (col == null) {
			return null;
		}
		int[] dims = shape(args.get(1));
		if (dims == null || dims.length != 4) {
			return null;
		}
		int[] p = windowParams(dims[2], dims[3], args.get(2), args.get(3), args.get(4), args.get(5));
		if (p == null) {
			return null;
		}
		int n = dims[0];
		int c = dims[1];
		int h = dims[2];
		int w = dims[3];
		int fh = p[0], fw = p[1], stride = p[2], pad = p[3], oh = p[4], ow = p[5];
		long rows = (long) n * oh * ow;
		long cols = (long) c * fh * fw;
		if (!sizeFits((long) n * c * h * w) || !sizeFits(rows) || !sizeFits(cols) || !sizeFits(rows * cols)
				|| col.totalSize() != rows * cols) {
			return null;
		}
		return switch (col) {
			case LispDoubleFloatArray a -> new LispDoubleFloatArray(
					LinalgSimdKernels.col2im(a.data(), n, c, h, w, fh, fw, stride, pad), dims.clone());
			case LispSingleFloatArray a -> new LispSingleFloatArray(
					LinalgSimdKernels.col2imF(a.data(), n, c, h, w, fh, fw, stride, pad), dims.clone());
		};
	}

	/**
	 * Validates the four window parameters against the spatial extent {@code (h, w)}:
	 * integers, positive filter/stride, non-negative pad, and both padded extents
	 * non-negative so the defun's {@code floor} is a plain truncating division. Returns
	 * {@code [fh, fw, stride, pad, oh, ow]}, or {@code null} to decline.
	 */
	private static int @Nullable [] windowParams(int h, int w, LispVal fhv, LispVal fwv, LispVal stridev,
			LispVal padv) {
		Integer fh = smallInt(fhv);
		Integer fw = smallInt(fwv);
		Integer stride = smallInt(stridev);
		Integer pad = smallInt(padv);
		if (fh == null || fw == null || stride == null || pad == null) {
			return null;
		}
		if (fh < 1 || fw < 1 || stride < 1 || pad < 0) {
			return null;
		}
		long eh = h + 2L * pad - fh;
		long ew = w + 2L * pad - fw;
		if (eh < 0 || ew < 0) {
			return null;
		}
		long oh = eh / stride + 1;
		long ow = ew / stride + 1;
		if (oh > Integer.MAX_VALUE || ow > Integer.MAX_VALUE) {
			return null;
		}
		return new int[] { fh, fw, stride, pad, (int) oh, (int) ow };
	}

	private static boolean sizeFits(long total) {
		return total >= 0 && total <= Integer.MAX_VALUE - 8;
	}

	private static @Nullable Integer smallInt(LispVal value) {
		return value instanceof LispInteger i && i.value() >= Integer.MIN_VALUE && i.value() <= Integer.MAX_VALUE
				? (int) i.value() : null;
	}

	// --- marshalling ------------------------------------------------------------------

	private static @Nullable LispFloatArray packed(LispVal value) {
		return value instanceof LispFloatArray a ? a : null;
	}

	/**
	 * A packed operand with at least one element, or {@code null} (the empty-array and
	 * general-array cases both signal or answer differently in the defun).
	 */
	private static @Nullable LispFloatArray nonEmpty(LispVal value) {
		LispFloatArray a = packed(value);
		return a != null && a.totalSize() > 0 ? a : null;
	}

	/**
	 * A number the kernels can broadcast. Only integers and doubles: a ratio would need
	 * the defun's exact arithmetic on the scalar side.
	 */
	private static @Nullable Double scalar(LispVal value) {
		return switch (value) {
			case LispDouble d -> d.value();
			case LispInteger i -> (double) i.value();
			default -> null;
		};
	}

	/**
	 * A {@code make-array} shape designator: an integer, or a proper list of integers.
	 */
	private static int @Nullable [] shape(LispVal value) {
		if (value instanceof LispInteger n) {
			return n.value() >= 0 && n.value() <= Integer.MAX_VALUE ? new int[] { (int) n.value() } : null;
		}
		List<Integer> dims = new ArrayList<>();
		LispVal cursor = value;
		while (cursor instanceof LispCons cons) {
			if (!(cons.car() instanceof LispInteger d) || d.value() < 0 || d.value() > Integer.MAX_VALUE) {
				return null;
			}
			dims.add((int) d.value());
			cursor = cons.cdr();
		}
		if (!(cursor instanceof LispNil) || dims.isEmpty()) {
			return null;
		}
		int[] out = new int[dims.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = dims.get(i);
		}
		return out;
	}

	private static double[] doubles(LispFloatArray a) {
		return ((LispDoubleFloatArray) a).data();
	}

	private static float[] floats(LispFloatArray a) {
		return ((LispSingleFloatArray) a).data();
	}

	/**
	 * A fresh packed array with {@code a}'s shape (an element-wise result preserves the
	 * rank, unlike every {@code vec:} kernel, which always produces a rank-1 vector). The
	 * dimensions are copied rather than shared, matching {@code linalg::%la-like}'s fresh
	 * {@code make-array}.
	 */
	private static LispVal like(LispFloatArray a, double[] data) {
		return new LispDoubleFloatArray(data, a.dims().clone());
	}

	private static LispVal like(LispFloatArray a, float[] data) {
		return new LispSingleFloatArray(data, a.dims().clone());
	}

	@FunctionalInterface
	private interface Kernel {

		@Nullable LispVal apply(List<LispVal> args);

	}

	/** The six lane loops of one element-wise operator (two widths x three shapes). */
	private record Elementwise(ArrayArrayD dd, ArrayArrayF ff, ArrayScalarD ds, ArrayScalarF fs, ScalarArrayD sd,
			ScalarArrayF sf) {
	}

	@FunctionalInterface
	private interface UnaryD {

		double[] apply(double[] x);

	}

	@FunctionalInterface
	private interface UnaryF {

		float[] apply(float[] x);

	}

	@FunctionalInterface
	private interface ArrayArrayD {

		double[] apply(double[] a, double[] b);

	}

	@FunctionalInterface
	private interface ArrayArrayF {

		float[] apply(float[] a, float[] b);

	}

	@FunctionalInterface
	private interface ArrayScalarD {

		double[] apply(double[] a, double s);

	}

	@FunctionalInterface
	private interface ArrayScalarF {

		float[] apply(float[] a, double s);

	}

	@FunctionalInterface
	private interface ScalarArrayD {

		double[] apply(double s, double[] a);

	}

	@FunctionalInterface
	private interface ScalarArrayF {

		float[] apply(double s, float[] a);

	}

}
