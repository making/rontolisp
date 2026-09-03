package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.FloatArrayAccessHook;
import am.ik.rontolisp.BFloat16;
import am.ik.rontolisp.LispBFloat16Array;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.FloatWidth;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's opt-in {@code --simd} acceleration of the {@code linalg:} kernels: it
 * replaces forty-nine of the {@code linalg.lisp} defuns with native {@link LispFunction}s
 * driving the lane loops in {@link LinalgSimdKernels}. The {@code linalg:} sibling of
 * {@link VecSimd}, deliberately a separate class -- {@code vec.lisp} and
 * {@code linalg.lisp} never call each other, so their interceptors do not either. Only
 * the KERNELS are shared.
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
	 * @param parallel {@code --parallel}: run the matrix products ({@code dot}'s
	 * matrix-by-vector and matrix-by-matrix cases, the stacked {@code %la-matmul-nd})
	 * over a row range per thread when the call is worth it ({@link SimdParallel}) -- the
	 * same row chains, so the same bits; no other member changes
	 */
	public static void install(Environment globalEnv, LispEvaluator evaluator, boolean parallel) {
		Elementwise add = new Elementwise(LinalgSimdKernels.BOP_ADD, LinalgSimdKernels::add, LinalgSimdKernels::addF,
				LinalgSimdKernels::addScalar, LinalgSimdKernels::addScalarF,
				(s, x) -> LinalgSimdKernels.addScalar(x, s), (s, x) -> LinalgSimdKernels.addScalarF(x, s));
		Elementwise sub = new Elementwise(LinalgSimdKernels.BOP_SUB, LinalgSimdKernels::sub, LinalgSimdKernels::subF,
				LinalgSimdKernels::subScalar, LinalgSimdKernels::subScalarF, LinalgSimdKernels::subFrom,
				LinalgSimdKernels::subFromF);
		Elementwise mul = new Elementwise(LinalgSimdKernels.BOP_MUL, LinalgSimdKernels::mul, LinalgSimdKernels::mulF,
				LinalgSimdKernels::mulScalar, LinalgSimdKernels::mulScalarF,
				(s, x) -> LinalgSimdKernels.mulScalar(x, s), (s, x) -> LinalgSimdKernels.mulScalarF(x, s));
		Elementwise div = new Elementwise(LinalgSimdKernels.BOP_DIV, LinalgSimdKernels::div, LinalgSimdKernels::divF,
				LinalgSimdKernels::divScalar, LinalgSimdKernels::divScalarF, LinalgSimdKernels::divFrom,
				LinalgSimdKernels::divFromF);

		// The comparison-select ufuncs share the elementwise
		// dispatch: same three %la-bcast shapes, same decline protocol. linalg:clip
		// and linalg:relu are accelerated transitively -- their defuns compose
		// linalg:maximum / linalg:minimum (the square / reciprocal pattern).
		Elementwise maximum = new Elementwise(LinalgSimdKernels.BOP_MAX, LinalgSimdKernels::maximum,
				LinalgSimdKernels::maximumF, LinalgSimdKernels::maxScalar, LinalgSimdKernels::maxScalarF,
				LinalgSimdKernels::maxFrom, LinalgSimdKernels::maxFromF);
		Elementwise minimum = new Elementwise(LinalgSimdKernels.BOP_MIN, LinalgSimdKernels::minimum,
				LinalgSimdKernels::minimumF, LinalgSimdKernels::minScalar, LinalgSimdKernels::minScalarF,
				LinalgSimdKernels::minFrom, LinalgSimdKernels::minFromF);

		define(globalEnv, evaluator, LispNames.LINALG_ADD, 2, args -> elementwise(add, args));
		define(globalEnv, evaluator, LispNames.LINALG_SUB, 2, args -> elementwise(sub, args));
		define(globalEnv, evaluator, LispNames.LINALG_MUL, 2, args -> elementwise(mul, args));
		define(globalEnv, evaluator, LispNames.LINALG_DIV, 2, args -> elementwise(div, args));
		define(globalEnv, evaluator, LispNames.LINALG_MAXIMUM, 2, args -> elementwise(maximum, args));
		define(globalEnv, evaluator, LispNames.LINALG_MINIMUM, 2, args -> elementwise(minimum, args));
		// The option forms: sum/amax/amin also take (a :axis ax :keepdims k),
		// argmax/argmin (v :axis ax), transpose (a axes) -- each handled by a scalar
		// fold/permutation kernel when the axis argument is an exact integer (a
		// permutation list for transpose), declined to the defun otherwise. The arity
		// range spans the full keyword tail; a malformed tail declines too, so the
		// defun's &key prologue signals its own error.
		define(globalEnv, evaluator, LispNames.LINALG_SUM, 1, 5, LinalgSimd::sum);
		define(globalEnv, evaluator, LispNames.LINALG_NORM, 1, LinalgSimd::norm);
		define(globalEnv, evaluator, LispNames.LINALG_AMAX, 1, 5, args -> extremum(args, true));
		define(globalEnv, evaluator, LispNames.LINALG_AMIN, 1, 5, args -> extremum(args, false));
		define(globalEnv, evaluator, LispNames.LINALG_ARGMAX, 1, 3, args -> argExtremum(args, true));
		define(globalEnv, evaluator, LispNames.LINALG_ARGMIN, 1, 3, args -> argExtremum(args, false));
		define(globalEnv, evaluator, LispNames.LINALG_TRACE, 1, LinalgSimd::trace);
		define(globalEnv, evaluator, LispNames.LINALG_TRANSPOSE, 1, 2, LinalgSimd::transpose);
		define(globalEnv, evaluator, LispNames.LINALG_RESHAPE, 2, LinalgSimd::reshape);
		define(globalEnv, evaluator, LispNames.LINALG_DOT, 2, args -> dot(args, parallel));
		define(globalEnv, evaluator, LispNames.LINALG_OUTER, 2, LinalgSimd::outer);
		// The named element-wise unary ufuncs. linalg:square and
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
		// linalg:erf: the one activation primitive whose defun is an emap, so the
		// member is intercepted rather than reached transitively. The kernel is
		// %la-erf-1's own series in the defun's order -- bit-identical, and scalar by
		// decision (the iteration count is data-dependent).
		define(globalEnv, evaluator, LispNames.LINALG_ERF, 1,
				args -> unary(args, LinalgSimdKernels::erf, LinalgSimdKernels::erfF));
		// The internal CNN window unfolding pair: pure index arithmetic, no
		// lanes -- intercepted because the boxed do-loop dominates the accelerated
		// convolution runs (~97% of ch07 train time under --simd was im2col/col2im).
		define(globalEnv, evaluator, LispNames.LINALG_IM2COL, 5, LinalgSimd::im2col);
		define(globalEnv, evaluator, LispNames.LINALG_COL2IM, 6, LinalgSimd::col2im);
		// The internal STACKED matrix product behind linalg:matmul at rank >= 3
		// (torch.bmm): the rank <= 2 dispatch stays in the library, this is the batched
		// walk it routes to -- every attention layer and every torch:linear over a
		// (B T C) activation.
		define(globalEnv, evaluator, LispNames.LINALG_MATMUL_ND, 2, args -> matmulNd(args, parallel));
		// The two members todo-473 moved onto this seam: the FUSED optimizer update
		// (torch:adam / torch:adamw, 31% of a --gpu --simd training step as a boxed do
		// loop) and the one fill loop behind linalg:rand / randn / uniform. Both are
		// internal linalg: members precisely so that this seam -- which intercepts
		// linalg: names and nothing else -- can reach them.
		define(globalEnv, evaluator, LispNames.LINALG_ADAM_STEP, 5, LinalgSimd::adamStep);
		define(globalEnv, evaluator, LispNames.LINALG_RNG_FILL, 5, LinalgSimd::rngFill);
		// The comparison MASKS: the same three %la-bcast shapes as the arithmetic, with
		// a 1.0 / 0.0 result. The dropout mask (linalg:greater (linalg:rand shape) p)
		// ran the boxed emap in every training step (.kb/gpu.md).
		for (int[] pair : new int[][] { { 0, LinalgSimdKernels.BOP_GT }, { 1, LinalgSimdKernels.BOP_GE },
				{ 2, LinalgSimdKernels.BOP_LT }, { 3, LinalgSimdKernels.BOP_LE }, { 4, LinalgSimdKernels.BOP_EQ } }) {
			int bop = pair[1];
			String member = switch (pair[0]) {
				case 0 -> LispNames.LINALG_GREATER;
				case 1 -> LispNames.LINALG_GREATER_EQUAL;
				case 2 -> LispNames.LINALG_LESS;
				case 3 -> LispNames.LINALG_LESS_EQUAL;
				default -> LispNames.LINALG_EQUAL;
			};
			Elementwise compare = new Elementwise(bop, (x, y) -> LinalgSimdKernels.compare(bop, x, y),
					(x, y) -> LinalgSimdKernels.compareF(bop, x, y),
					(x, sc) -> LinalgSimdKernels.compareScalar(bop, x, sc),
					(x, sc) -> LinalgSimdKernels.compareScalarF(bop, x, sc),
					(sc, y) -> LinalgSimdKernels.compareFrom(bop, sc, y),
					(sc, y) -> LinalgSimdKernels.compareFromF(bop, sc, y));
			define(globalEnv, evaluator, member, 2, args -> elementwise(compare, args));
		}
		// The selects and copies behind torch:masked-fill, torch:slice / torch:cat and
		// torch:index-select: where over broadcast operands, the one strided read every
		// slice and broadcast-to is, and the embedding lookup with its scatter-add
		// adjoint. Pure copies -- bit-identical by construction -- that were a third of
		// a --gpu --simd training step as boxed odometer walks (.kb/gpu.md).
		define(globalEnv, evaluator, LispNames.LINALG_WHERE, 3, LinalgSimd::where);
		define(globalEnv, evaluator, LispNames.LINALG_GATHER_STRIDED, 5, LinalgSimd::gatherStrided);
		define(globalEnv, evaluator, LispNames.LINALG_TAKE_ROWS, 2, LinalgSimd::takeRows);
		define(globalEnv, evaluator, LispNames.LINALG_SCATTER_ROWS, 3, LinalgSimd::scatterRows);
		// The two halves of torch:clip-grad-norm: the left-fold sum of squares over a
		// gradient and the in-place scale -- moved here from the boxed loops they were,
		// like the Adam step before them.
		define(globalEnv, evaluator, LispNames.LINALG_SUM_SQUARES, 2, LinalgSimd::sumSquares);
		define(globalEnv, evaluator, LispNames.LINALG_SCALE, 2, LinalgSimd::scale);
	}

	/**
	 * Binds {@code linalg:<member>} to a native that tries {@code kernel} and, when the
	 * kernel declines (or the arity is wrong), applies the {@code linalg.lisp} defun it
	 * replaced.
	 */
	private static void define(Environment globalEnv, LispEvaluator evaluator, String member, int arity,
			Kernel kernel) {
		define(globalEnv, evaluator, member, arity, arity, kernel);
	}

	private static void define(Environment globalEnv, LispEvaluator evaluator, String member, int minArity,
			int maxArity, Kernel kernel) {
		// A %-prefixed member is an internal symbol, whose canonical qualified spelling
		// carries the double colon (linalg::%la-im2col).
		String qualified = member.startsWith("%") ? LispNames.LINALG_PKG + "::" + member
				: LispNames.LINALG_PKG + ":" + member;
		LispVal scalarDefun = globalEnv.lookupFunctionOrNull(qualified);
		if (scalarDefun == null) {
			throw new IllegalStateException("linalg.lisp must be loaded before " + qualified + " can be accelerated");
		}
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() >= minArity && args.size() <= maxArity) {
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
				// Two same-width arrays of different shapes broadcast by the numpy
				// rules; an incompatible pair declines so the
				// defun signals its own shape-mismatch error.
				return bcast(op.bop(), a, b);
			}
			// Mixed widths: the oracle widens both operands to double and keeps a's
			// width. Rare enough to leave to it, which is what the null is.
			return switch (a) {
				case LispDoubleFloatArray x ->
					b instanceof LispDoubleFloatArray y ? like(a, op.dd().apply(x.data(), y.data())) : null;
				case LispSingleFloatArray x ->
					b instanceof LispSingleFloatArray y ? like(a, op.ff().apply(x.data(), y.data())) : null;
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		}
		if (a != null) {
			Double s = scalar(bv);
			return s == null ? null : switch (a) {
				case LispDoubleFloatArray x -> like(a, op.ds().apply(x.data(), s));
				case LispSingleFloatArray x -> like(a, op.fs().apply(x.data(), s));
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		}
		if (b != null) {
			Double s = scalar(av);
			return s == null ? null : switch (b) {
				case LispDoubleFloatArray x -> like(b, op.sd().apply(s, x.data()));
				case LispSingleFloatArray x -> like(b, op.sf().apply(s, x.data()));
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
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
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * The general numpy broadcast of two same-width packed arrays of different shapes,
	 * mirroring {@code %la-bcast-loop} (every element computed in double, narrowed only
	 * by a store into a single-float result -- bit-identical at both widths). Mixed
	 * widths and incompatible shapes decline.
	 */
	private static @Nullable LispVal bcast(int bop, LispFloatArray a, LispFloatArray b) {
		int[] od = bcastShape(a.dims(), b.dims());
		if (od == null) {
			return null;
		}
		// A mixed-width pair declines (the null arms), like every other kernel here.
		return switch (a) {
			case LispDoubleFloatArray x -> b instanceof LispDoubleFloatArray y ? new LispDoubleFloatArray(
					LinalgSimdKernels.bcast(bop, x.data(), a.dims(), y.data(), b.dims(), od), od) : null;
			case LispSingleFloatArray x -> b instanceof LispSingleFloatArray y ? new LispSingleFloatArray(
					LinalgSimdKernels.bcastF(bop, x.data(), a.dims(), y.data(), b.dims(), od), od) : null;
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * The numpy broadcast shape of two dims arrays ({@code %la-bcast-shape}): trailing
	 * axes align, a pair agrees when equal or either is 1, the output extent is the
	 * larger. Returns {@code null} (decline) on any other disagreement or an output too
	 * large for one Java array.
	 */
	private static int @Nullable [] bcastShape(int[] dx, int[] dy) {
		int rank = Math.max(dx.length, dy.length);
		int[] od = new int[rank];
		long total = 1;
		for (int k = 0; k < rank; k++) {
			int i = dx.length - rank + k;
			int j = dy.length - rank + k;
			int a = i >= 0 ? dx[i] : 1;
			int b = j >= 0 ? dy[j] : 1;
			if (a != b && a != 1 && b != 1) {
				return null;
			}
			od[k] = Math.max(a, b);
			total *= od[k];
			if (!sizeFits(total)) {
				return null;
			}
		}
		return od;
	}

	// --- reductions -------------------------------------------------------------------

	/**
	 * The keyword tail of a call, {@code args[required..]}, read against the declared
	 * keyword names: one value per name, {@code LispNil} when absent. A tail that is not
	 * literal {@code :keyword value} pairs over those names (odd, unknown, repeated)
	 * yields {@code null}, and the kernel declines so the defun signals.
	 */
	static LispVal @Nullable [] options(List<LispVal> args, int required, String... keywords) {
		LispVal[] out = new LispVal[keywords.length];
		Arrays.fill(out, LispNil.INSTANCE);
		boolean[] seen = new boolean[keywords.length];
		if ((args.size() - required) % 2 != 0) {
			return null;
		}
		for (int i = required; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol sym) || !sym.isKeyword()) {
				return null;
			}
			int k = Arrays.asList(keywords).indexOf(sym.name().substring(1));
			if (k < 0 || seen[k]) {
				return null;
			}
			seen[k] = true;
			out[k] = args.get(i + 1);
		}
		return out;
	}

	private static @Nullable LispVal sum(List<LispVal> args) {
		if (args.size() > 1) {
			return foldAxis(LinalgSimdKernels.BOP_ADD, args);
		}
		LispFloatArray a = nonEmpty(args.get(0));
		if (a == null) {
			return null;
		}
		return switch (a) {
			case LispDoubleFloatArray x -> new LispDouble(LinalgSimdKernels.sum(x.data()));
			case LispSingleFloatArray x -> new LispDouble(LinalgSimdKernels.sumF(x.data()));
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	private static @Nullable LispVal norm(List<LispVal> args) {
		LispFloatArray a = nonEmpty(args.get(0));
		if (a == null) {
			return null;
		}
		return switch (a) {
			case LispDoubleFloatArray x -> new LispDouble(LinalgSimdKernels.norm(x.data()));
			case LispSingleFloatArray x -> new LispDouble(LinalgSimdKernels.normF(x.data()));
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	private static @Nullable LispVal extremum(List<LispVal> args, boolean max) {
		if (args.size() > 1) {
			return foldAxis(max ? LinalgSimdKernels.BOP_MAX : LinalgSimdKernels.BOP_MIN, args);
		}
		LispFloatArray a = nonEmpty(args.get(0));
		if (a == null) {
			return null;
		}
		return switch (a) {
			case LispDoubleFloatArray x ->
				new LispDouble(max ? LinalgSimdKernels.amax(x.data()) : LinalgSimdKernels.amin(x.data()));
			case LispSingleFloatArray x ->
				new LispDouble(max ? LinalgSimdKernels.amaxF(x.data()) : LinalgSimdKernels.aminF(x.data()));
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * The axis form of {@code sum}/{@code amax}/{@code amin} ({@code %la-fold-axis}): the
	 * axis argument must be an exact in-range integer (a nil axis, a non-integer, out of
	 * range -- all decline), the axis is dropped from the result or kept as extent 1
	 * under a non-nil keepdims, and a vector without keepdims reduces to the scalar
	 * accumulator itself. An empty axis declines (the defun errors for amax/amin and
	 * returns an INTEGER 0 for a keepdims-less vector sum).
	 */
	private static @Nullable LispVal foldAxis(int op, List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		LispVal[] opts = options(args, 1, "AXIS", "KEEPDIMS");
		if (a == null || opts == null) {
			return null;
		}
		Integer axis = normAxis(opts[0], a.rank());
		if (axis == null) {
			return null;
		}
		boolean keep = !(opts[1] instanceof LispNil);
		int[] d = a.dims();
		int axlen = d[axis];
		if (axlen == 0) {
			return null;
		}
		int outer = 1;
		int inner = 1;
		for (int i = 0; i < axis; i++) {
			outer *= d[i];
		}
		for (int i = axis + 1; i < d.length; i++) {
			inner *= d[i];
		}
		double[] acc;
		switch (a) {
			case LispDoubleFloatArray x -> acc = LinalgSimdKernels.foldAxis(op, x.data(), axlen, outer, inner);
			case LispSingleFloatArray x -> acc = LinalgSimdKernels.foldAxisF(op, x.data(), axlen, outer, inner);
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> {
				return null;
			}
		}
		int[] od = axisShape(d, axis, keep);
		if (od.length == 0) {
			return new LispDouble(acc[0]);
		}
		return switch (a) {
			case LispSingleFloatArray ignored -> {
				// The result keeps the input's width (%la-etype): narrow each accumulator
				// once, the defun's own store into a single-float out.
				float[] out = new float[acc.length];
				for (int i = 0; i < out.length; i++) {
					out[i] = (float) acc[i];
				}
				yield new LispSingleFloatArray(out, od);
			}
			case LispDoubleFloatArray ignored -> new LispDoubleFloatArray(acc, od);
			// Declined at the fold above; this is the same answer where the compiler
			// asks for it.
			case LispBFloat16Array ignored -> null;
		};
	}

	/** {@code argmax}/{@code argmin} are vector-only in linalg.lisp (they use length). */
	private static @Nullable LispVal argExtremum(List<LispVal> args, boolean max) {
		if (args.size() > 1) {
			return argFoldAxis(args, max);
		}
		LispFloatArray a = nonEmpty(args.get(0));
		if (a == null || a.rank() != 1) {
			return null;
		}
		return switch (a) {
			case LispDoubleFloatArray x ->
				new LispInteger(max ? LinalgSimdKernels.argmax(x.data()) : LinalgSimdKernels.argmin(x.data()));
			case LispSingleFloatArray x ->
				new LispInteger(max ? LinalgSimdKernels.argmaxF(x.data()) : LinalgSimdKernels.argminF(x.data()));
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * The axis form of {@code argmax}/{@code argmin} ({@code %la-argfold-axis}): the axis
	 * is always dropped, a vector reduces to the integer index itself and a higher rank
	 * fills a packed DOUBLE array of index values at any input width.
	 */
	private static @Nullable LispVal argFoldAxis(List<LispVal> args, boolean max) {
		LispFloatArray a = packed(args.get(0));
		LispVal[] opts = options(args, 1, "AXIS");
		if (a == null || opts == null) {
			return null;
		}
		Integer axis = normAxis(opts[0], a.rank());
		if (axis == null) {
			return null;
		}
		int[] d = a.dims();
		int axlen = d[axis];
		if (axlen == 0) {
			return null;
		}
		int outer = 1;
		int inner = 1;
		for (int i = 0; i < axis; i++) {
			outer *= d[i];
		}
		for (int i = axis + 1; i < d.length; i++) {
			inner *= d[i];
		}
		double[] idx;
		switch (a) {
			case LispDoubleFloatArray x -> idx = LinalgSimdKernels.argFoldAxis(max, x.data(), axlen, outer, inner);
			case LispSingleFloatArray x -> idx = LinalgSimdKernels.argFoldAxisF(max, x.data(), axlen, outer, inner);
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> {
				return null;
			}
		}
		int[] od = axisShape(d, axis, false);
		if (od.length == 0) {
			return new LispInteger((long) idx[0]);
		}
		return new LispDoubleFloatArray(idx, od);
	}

	/**
	 * Normalizes a possibly negative integer axis against the rank
	 * ({@code %la-norm-axis}); a non-integer or out-of-range axis declines.
	 */
	static @Nullable Integer normAxis(LispVal v, int rank) {
		Integer i = smallInt(v);
		if (i == null) {
			return null;
		}
		int ax = i < 0 ? i + rank : i;
		return ax >= 0 && ax < rank ? Integer.valueOf(ax) : null;
	}

	/** The dims with the axis dropped -- or kept as extent 1 ({@code %la-axis-shape}). */
	static int[] axisShape(int[] d, int ax, boolean keep) {
		int[] od = new int[keep ? d.length : d.length - 1];
		int k = 0;
		for (int i = 0; i < d.length; i++) {
			if (i != ax) {
				od[k++] = d[i];
			}
			else if (keep) {
				od[k++] = 1;
			}
		}
		return od;
	}

	private static @Nullable LispVal trace(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		if (a == null || a.rank() != 2 || a.dims()[0] != a.dims()[1]) {
			return null;
		}
		int n = a.dims()[0];
		return switch (a) {
			case LispDoubleFloatArray x -> new LispDouble(LinalgSimdKernels.trace(x.data(), n));
			case LispSingleFloatArray x -> new LispDouble(LinalgSimdKernels.traceF(x.data(), n));
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	// --- shape ------------------------------------------------------------------------

	private static @Nullable LispVal transpose(List<LispVal> args) {
		if (args.size() == 2) {
			return transposeAxes(args);
		}
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
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * The axes form of {@code linalg:transpose} ({@code %la-transpose-axes}): a rank-n
	 * axis permutation, a pure copy. The axes argument must be a proper list of exact
	 * integers forming a permutation of {@code 0..rank-1}; anything else -- nil (the
	 * defun's plain-transpose branch), a bare integer, a bad permutation (the defun's
	 * error) -- declines.
	 */
	private static @Nullable LispVal transposeAxes(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		if (a == null) {
			return null;
		}
		int rank = a.rank();
		int[] axes = permutation(args.get(1), rank);
		if (axes == null) {
			return null;
		}
		int[] od = new int[rank];
		for (int k = 0; k < rank; k++) {
			od[k] = a.dims()[axes[k]];
		}
		return switch (a) {
			case LispDoubleFloatArray x ->
				new LispDoubleFloatArray(LinalgSimdKernels.transposeAxes(x.data(), a.dims(), axes), od);
			case LispSingleFloatArray x ->
				new LispSingleFloatArray(LinalgSimdKernels.transposeAxesF(x.data(), a.dims(), axes), od);
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/** A proper list of exact integers forming a permutation of {@code 0..rank-1}. */
	static int @Nullable [] permutation(LispVal value, int rank) {
		int[] axes = new int[rank];
		boolean[] seen = new boolean[rank];
		int count = 0;
		LispVal cursor = value;
		while (cursor instanceof LispCons cons) {
			Integer ax = smallInt(cons.car());
			if (count >= rank || ax == null || ax < 0 || ax >= rank || seen[ax]) {
				return null;
			}
			seen[ax] = true;
			axes[count++] = ax;
			cursor = cons.cdr();
		}
		return cursor instanceof LispNil && count == rank ? axes : null;
	}

	private static @Nullable LispVal reshape(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		if (a == null) {
			return null;
		}
		int[] dims = reshapeShape(args.get(1), a.totalSize());
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
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	// --- products ---------------------------------------------------------------------

	/**
	 * The numpy dispatch of {@code linalg:dot}, for two packed operands of the same width
	 * and rank {@code <= 2}. A scalar operand declines (the defun routes it to
	 * {@code linalg:mul}, itself intercepted); so does any dimension mismatch, so the
	 * defun raises its own error.
	 */
	private static @Nullable LispVal dot(List<LispVal> args, boolean parallel) {
		LispFloatArray a = packed(args.get(0));
		LispFloatArray b = packed(args.get(1));
		if (a == null || b == null || a.getClass() != b.getClass() || a.rank() > 2 || b.rank() > 2) {
			return null;
		}
		if (a.rank() == 1 && b.rank() == 1) {
			if (a.dims()[0] != b.dims()[0]) {
				return null;
			}
			return switch (a) {
				case LispSingleFloatArray x -> new LispDouble(LinalgSimdKernels.dotF(x.data(), floats(b)));
				case LispDoubleFloatArray x -> new LispDouble(LinalgSimdKernels.dot(x.data(), doubles(b)));
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		}
		if (a.rank() == 2 && b.rank() == 1) {
			int rows = a.dims()[0];
			int cols = a.dims()[1];
			if (cols != b.dims()[0]) {
				return null;
			}
			int[] dims = { rows };
			return switch (a) {
				case LispSingleFloatArray x -> new LispSingleFloatArray(
						LinalgSimdKernels.matvecF(x.data(), rows, cols, floats(b), parallel), dims);
				case LispDoubleFloatArray x -> new LispDoubleFloatArray(
						LinalgSimdKernels.matvec(x.data(), rows, cols, doubles(b), parallel), dims);
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		}
		if (a.rank() == 1 && b.rank() == 2) {
			int n = b.dims()[0];
			int p = b.dims()[1];
			if (a.dims()[0] != n) {
				return null;
			}
			// A row vector times a matrix is the n = 1 case of the matrix product.
			int[] dims = { p };
			return switch (a) {
				case LispSingleFloatArray x ->
					new LispSingleFloatArray(LinalgSimdKernels.matmulF(x.data(), floats(b), 1, n, p, false), dims);
				case LispDoubleFloatArray x ->
					new LispDoubleFloatArray(LinalgSimdKernels.matmul(x.data(), doubles(b), 1, n, p, false), dims);
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		}
		int n = a.dims()[0];
		int m = a.dims()[1];
		int p = b.dims()[1];
		if (m != b.dims()[0]) {
			return null;
		}
		int[] dims = { n, p };
		return switch (a) {
			case LispSingleFloatArray x ->
				new LispSingleFloatArray(LinalgSimdKernels.matmulF(x.data(), floats(b), n, m, p, parallel), dims);
			case LispDoubleFloatArray x ->
				new LispDoubleFloatArray(LinalgSimdKernels.matmul(x.data(), doubles(b), n, m, p, parallel), dims);
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
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
		return switch (u) {
			case LispSingleFloatArray x ->
				new LispSingleFloatArray(LinalgSimdKernels.outerF(x.data(), floats(v)), dims);
			case LispDoubleFloatArray x ->
				new LispDoubleFloatArray(LinalgSimdKernels.outer(x.data(), doubles(v)), dims);
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * {@code (linalg::%la-matmul-nd a b)}, the STACKED matrix product: the last two axes
	 * are the matrix and every leading axis broadcasts. One {@code ikj} slab per batch --
	 * the same kernel {@code dot}'s M.M case runs -- over the {@code %la-batch-strides}
	 * offsets.
	 *
	 * <p>
	 * Declined (the defun handles it, and signals its own errors): a general boxed
	 * operand, mixed widths, a RANK-1 operand on either side (the numpy
	 * promote-then-drop-the-axis rule, which is not the hot shape), non-broadcastable
	 * batch shapes, mismatched inner dimensions, and any empty extent (the defun's
	 * zero-length {@code k} fold answers the INTEGER 0 it seeds with).
	 */
	private static @Nullable LispVal matmulNd(List<LispVal> args, boolean parallel) {
		LispFloatArray a = packed(args.get(0));
		LispFloatArray b = packed(args.get(1));
		if (a == null || b == null || a.getClass() != b.getClass() || a.rank() < 2 || b.rank() < 2) {
			return null;
		}
		int[] da = a.dims();
		int[] db = b.dims();
		int n = da[da.length - 2];
		int m = da[da.length - 1];
		int p = db[db.length - 1];
		if (m != db[db.length - 2] || n < 1 || m < 1 || p < 1) {
			return null;
		}
		int[] ba = Arrays.copyOf(da, da.length - 2);
		int[] bb = Arrays.copyOf(db, db.length - 2);
		int[] bd = bcastShape(ba, bb);
		if (bd == null) {
			return null;
		}
		long batches = 1;
		for (int d : bd) {
			batches *= d;
		}
		long total = batches * n * p;
		if (batches < 1 || !sizeFits(total)) {
			return null;
		}
		int[] sa = batchStrides(ba, bd, n * m);
		int[] sb = batchStrides(bb, bd, m * p);
		int[] od = Arrays.copyOf(bd, bd.length + 2);
		od[bd.length] = n;
		od[bd.length + 1] = p;
		return switch (a) {
			case LispDoubleFloatArray x -> new LispDoubleFloatArray(
					LinalgSimdKernels.matmulNd(x.data(), doubles(b), bd, sa, sb, n, m, p, (int) batches, parallel), od);
			case LispSingleFloatArray x -> new LispSingleFloatArray(
					LinalgSimdKernels.matmulNdF(x.data(), floats(b), bd, sa, sb, n, m, p, (int) batches, parallel), od);
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * {@code %la-batch-strides}: the row-major strides of the batch dims {@code d}
	 * aligned to the broadcast batch shape {@code od}, with 0 on every stretched axis,
	 * and the trailing matrix size as the innermost stride. That is
	 * {@code %la-bcast-strides} scaled by {@code base}, which is why a broadcast leading
	 * axis needs no special case.
	 */
	private static int[] batchStrides(int[] d, int[] od, long base) {
		int[] s = new int[od.length];
		long acc = base;
		for (int k = od.length - 1, i = d.length - 1; k >= 0; k--, i--) {
			int n = i >= 0 ? d[i] : 1;
			s[k] = n == 1 ? 0 : (int) acc;
			acc *= n;
		}
		return s;
	}

	// --- CNN window unfolding: %la-im2col / %la-col2im ---------------------------------

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
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
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
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	// --- the fused optimizer update and the generator fill ----------------------------

	/**
	 * {@code (linalg::%la-adam-step x g m v ps)}: Adam's fused element-wise update in
	 * place over four SAME-WIDTH packed arrays of the same element count, with the rule
	 * in an eleven-element packed double vector. A scalar parameter (whose {@code x} or
	 * {@code g} is a plain number), a mixed-width quadruple and a malformed rule vector
	 * all decline to the defun, which handles them.
	 */
	private static @Nullable LispVal adamStep(List<LispVal> args) {
		LispFloatArray x = packed(args.get(0));
		LispFloatArray g = packed(args.get(1));
		LispFloatArray m = packed(args.get(2));
		LispFloatArray v = packed(args.get(3));
		double[] ps = adamRule(args.get(4));
		if (x == null || g == null || m == null || v == null || ps == null) {
			return null;
		}
		int n = x.totalSize();
		if (g.totalSize() != n || m.totalSize() != n || v.totalSize() != n) {
			return null;
		}
		// Mixed widths (the null arms): the defun reads every element widened anyway.
		switch (x) {
			case LispDoubleFloatArray a -> {
				if (!(g instanceof LispDoubleFloatArray b) || !(m instanceof LispDoubleFloatArray c)
						|| !(v instanceof LispDoubleFloatArray d)) {
					return null;
				}
				LinalgSimdKernels.adamStep(a.data(), b.data(), c.data(), d.data(), ps);
				written(a.storage(), c.storage(), d.storage());
			}
			case LispSingleFloatArray a -> {
				if (!(g instanceof LispSingleFloatArray b) || !(m instanceof LispSingleFloatArray c)
						|| !(v instanceof LispSingleFloatArray d)) {
					return null;
				}
				LinalgSimdKernels.adamStepF(a.data(), b.data(), c.data(), d.data(), ps);
				written(a.storage(), c.storage(), d.storage());
			}
			// No lane kernel updates this width; the scalar defun answers.
			case LispBFloat16Array ignored -> {
				return null;
			}
		}
		return x;
	}

	/**
	 * The rule vector of {@code %la-adam-step}: eleven packed doubles whose last is the
	 * weight-decay mode (0 none, 1 coupled, 2 decoupled). Anything else declines.
	 */
	private static double @Nullable [] adamRule(LispVal value) {
		if (!(packed(value) instanceof LispDoubleFloatArray ps) || ps.data().length != 11) {
			return null;
		}
		double mode = ps.data()[10];
		return mode == 0.0 || mode == 1.0 || mode == 2.0 ? ps.data() : null;
	}

	/**
	 * {@code (linalg::%la-rng-fill out st mode lo span)}: fills a packed array of either
	 * width from the state vector {@code st} and answers the state the generator ends on.
	 * A general (boxed) destination, a state vector that is not three packed doubles in
	 * the generator's own range, a mode outside 0..2 or a non-numeric {@code lo} /
	 * {@code span} decline to the defun.
	 */
	private static @Nullable LispVal rngFill(List<LispVal> args) {
		LispFloatArray out = packed(args.get(0));
		Integer mode = smallInt(args.get(2));
		Double lo = scalar(args.get(3));
		Double span = scalar(args.get(4));
		if (out == null || mode == null || mode < 0 || mode > 2 || lo == null || span == null) {
			return null;
		}
		if (!(packed(args.get(1)) instanceof LispDoubleFloatArray s) || s.data().length != 3) {
			return null;
		}
		int[] w = rngState(s.data());
		if (w == null) {
			return null;
		}
		double[] end;
		switch (out) {
			case LispDoubleFloatArray a -> end = LinalgSimdKernels.rngFill(a.data(), mode, lo, span, w[0], w[1], w[2]);
			case LispSingleFloatArray a -> end = LinalgSimdKernels.rngFillF(a.data(), mode, lo, span, w[0], w[1], w[2]);
			// No lane kernel fills this width; the scalar defun answers.
			case LispBFloat16Array ignored -> {
				return null;
			}
		}
		written(switch (out) {
			case LispDoubleFloatArray d -> d.storage();
			case LispSingleFloatArray f -> f.storage();
			case LispBFloat16Array b -> b.storage();
		});
		return new LispDoubleFloatArray(end, new int[] { 3 });
	}

	// --- where, the strided gather, take-rows and its adjoint --------------------------

	/**
	 * {@code (linalg:where mask x y)}: the element of {@code x} where the mask is
	 * non-zero, of {@code y} where it is zero, all three operands packed arrays of either
	 * width or plain numbers, broadcast together by the numpy rules. The result keeps
	 * {@code x}'s width when {@code x} is an array, else {@code y}'s, else double -- the
	 * defun's rule. Declines when no operand is an array (the defun answers a number), on
	 * a general boxed array, a ratio scalar, or an incompatible broadcast (the defun
	 * signals its own shape error).
	 */
	private static @Nullable LispVal where(List<LispVal> args) {
		LispVal mv = args.get(0), xv = args.get(1), yv = args.get(2);
		LispFloatArray m = packed(mv), x = packed(xv), y = packed(yv);
		if (m == null && x == null && y == null) {
			return null;
		}
		Double ms = m == null ? scalar(mv) : null, xs = x == null ? scalar(xv) : null,
				ys = y == null ? scalar(yv) : null;
		if ((m == null && ms == null) || (x == null && xs == null) || (y == null && ys == null)) {
			return null;
		}
		int[] od = null;
		for (LispFloatArray a : new LispFloatArray[] { m, x, y }) {
			if (a != null) {
				od = od == null ? a.dims().clone() : bcastShape(od, a.dims());
				if (od == null) {
					return null;
				}
			}
		}
		if (od == null) {
			return null;
		}
		// The result keeps x's width when x is an array, else y's, else double.
		for (LispFloatArray operand : new LispFloatArray[] { m, x, y }) {
			if (operand != null && !laneReadable(operand)) {
				return null;
			}
		}
		LispFloatArray width = x != null ? x : y;
		boolean single = width != null && switch (width) {
			case LispSingleFloatArray ignored -> true;
			case LispDoubleFloatArray ignored -> false;
			// Declined by laneReadable above; this is where the compiler asks.
			case LispBFloat16Array ignored -> false;
		};
		Object out = LinalgSimdKernels.where(data(m), ms == null ? 0.0 : ms,
				m == null ? null : LinalgSimdKernels.bcastStrides(m.dims(), od), data(x), xs == null ? 0.0 : xs,
				x == null ? null : LinalgSimdKernels.bcastStrides(x.dims(), od), data(y), ys == null ? 0.0 : ys,
				y == null ? null : LinalgSimdKernels.bcastStrides(y.dims(), od), od, single);
		return out instanceof float[] f ? new LispSingleFloatArray(f, od)
				: new LispDoubleFloatArray((double[]) out, od);
	}

	/** The raw element store of a packed array, or {@code null} for a null operand. */
	private static @Nullable Object data(@Nullable LispFloatArray a) {
		return switch (a) {
			case null -> null;
			case LispDoubleFloatArray d -> d.data();
			case LispSingleFloatArray f -> f.data();
			case LispBFloat16Array b -> b.data();
		};
	}

	/**
	 * Whether a lane kernel reads this width at all. Exhaustive, so a width added to the
	 * umbrella has to answer here rather than being assumed readable -- the kernels are
	 * written against the two IEEE widths and would misread anything else's store.
	 * @param a the packed operand
	 * @return whether a lane kernel can read it
	 */
	private static boolean laneReadable(LispFloatArray a) {
		return switch (a) {
			case LispSingleFloatArray ignored -> true;
			case LispDoubleFloatArray ignored -> true;
			case LispBFloat16Array ignored -> false;
		};
	}

	/**
	 * {@code (linalg::%la-gather-strided a od rs base single)}: a fresh {@code od}-shaped
	 * array of the flagged width filled from the packed {@code a} by the strided walk.
	 * Declines a general boxed source, a malformed shape or stride list, and -- the one
	 * check the defun makes per element as a subscript error -- a walk that would reach
	 * outside {@code a}: the lowest and highest flat index the odometer visits are
	 * computed up front from the extents and strides, so a declined call has read nothing
	 * and the defun signals exactly what it always did.
	 */
	private static @Nullable LispVal gatherStrided(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		int[] od = shape(args.get(1));
		int[] rs = ints(args.get(2));
		Integer base = smallInt(args.get(3));
		if (a == null || od == null || rs == null || base == null || rs.length != od.length) {
			return null;
		}
		int rank = od.length;
		int[] s = new int[rank];
		for (int k = 0; k < rank; k++) {
			s[k] = rs[rank - 1 - k];
		}
		if (!gatherInBounds(od, s, base, a.totalSize())) {
			return null;
		}
		FloatWidth width = widthArg(args.get(4));
		if (width == null) {
			return null;
		}
		// A switch EXPRESSION, not a statement switch: only the expression form is
		// checked for exhaustiveness over an enum, so a fourth width is a compile error
		// here and a statement switch would have let it fall straight through.
		Boolean single = switch (width) {
			case SINGLE -> Boolean.TRUE;
			case DOUBLE -> Boolean.FALSE;
			// No lane kernel reads this width; the defun answers.
			case BFLOAT16 -> null;
		};
		if (single == null) {
			return null;
		}
		Object out = LinalgSimdKernels.gatherStrided(java.util.Objects.requireNonNull(data(a)), od, s, base, single);
		return out instanceof float[] f ? new LispSingleFloatArray(f, od)
				: new LispDoubleFloatArray((double[]) out, od);
	}

	/**
	 * Whether every flat index a strided walk over {@code od} from {@code base} reaches
	 * lies inside a store of {@code size} elements -- the extremes are the base plus each
	 * axis's full negative or positive travel, and the element count must fit an
	 * {@code int}. An empty output walks nothing and is always in bounds.
	 */
	/**
	 * The packed float width a {@code %la-gather-strided}-shaped call names in its width
	 * argument -- the small integer {@code linalg::%la-width-code} produces -- or
	 * {@code null} when the argument is not one, in which case the caller declines to the
	 * defun like any other unrecognized shape. It was a BOOLEAN until 2026-09-03, which
	 * admitted exactly two widths ({@code .kb/vec.md}).
	 * @param arg the width argument
	 * @return the width, or {@code null}
	 */
	static @Nullable FloatWidth widthArg(LispVal arg) {
		Integer code = smallInt(arg);
		if (code == null) {
			return null;
		}
		for (FloatWidth width : FloatWidth.values()) {
			if (width.code() == code) {
				return width;
			}
		}
		return null;
	}

	static boolean gatherInBounds(int[] od, int[] s, int base, int size) {
		long total = 1, lo = base, hi = base;
		for (int k = 0; k < od.length; k++) {
			total *= od[k];
			if (!sizeFits(total)) {
				return false;
			}
			long travel = (long) (od[k] - 1) * s[k];
			if (travel < 0) {
				lo += travel;
			}
			else {
				hi += travel;
			}
		}
		return total == 0 || (lo >= 0 && hi < size);
	}

	/** A proper list of small integers (negative allowed), or {@code null}. */
	static int @Nullable [] ints(LispVal value) {
		List<Integer> out = new ArrayList<>();
		LispVal cursor = value;
		while (cursor instanceof LispCons cons) {
			Integer v = smallInt(cons.car());
			if (v == null) {
				return null;
			}
			out.add(v);
			cursor = cons.cdr();
		}
		if (!(cursor instanceof LispNil)) {
			return null;
		}
		int[] r = new int[out.size()];
		for (int i = 0; i < r.length; i++) {
			r[i] = out.get(i);
		}
		return r;
	}

	/**
	 * An index vector as the defun reads it -- {@code (truncate (aref idx i))} -- as
	 * {@code int}s, each required to land inside {@code [0, rows)}: a packed vector of
	 * either width, and anything else (a boxed vector, a negative or out-of-range index,
	 * which the defun turns into its own subscript error) declines.
	 */
	static int @Nullable [] rowIndexes(LispVal value, int rows) {
		LispFloatArray idx = packed(value);
		if (idx == null || idx.dims().length != 1) {
			return null;
		}
		int m = idx.totalSize();
		int[] out = new int[m];
		for (int i = 0; i < m; i++) {
			double v = switch (idx) {
				case LispDoubleFloatArray d -> d.data()[i];
				case LispSingleFloatArray f -> f.data()[i];
				case LispBFloat16Array b -> BFloat16.value(b.data()[i]);
			};
			if (!(v > -1.0 && v < rows)) {
				return null;
			}
			out[i] = (int) v;
		}
		return out;
	}

	/**
	 * {@code (linalg:take-rows a idx)}: the axis-0 slabs of the packed {@code a} selected
	 * by the index vector, as a fresh array of {@code a}'s width.
	 */
	private static @Nullable LispVal takeRows(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		if (a == null || a.dims().length < 1) {
			return null;
		}
		int[] rows = rowIndexes(args.get(1), a.dims()[0]);
		if (rows == null) {
			return null;
		}
		int slab = a.dims()[0] == 0 ? 0 : a.totalSize() / a.dims()[0];
		if (!sizeFits((long) rows.length * slab)) {
			return null;
		}
		int[] od = a.dims().clone();
		od[0] = rows.length;
		return switch (a) {
			case LispDoubleFloatArray d ->
				new LispDoubleFloatArray(LinalgSimdKernels.takeRows(d.data(), slab, rows), od);
			case LispSingleFloatArray f ->
				new LispSingleFloatArray(LinalgSimdKernels.takeRowsF(f.data(), slab, rows), od);
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * {@code (linalg::%la-scatter-rows z g idx)}: slab {@code i} of {@code g} added into
	 * slab {@code idx[i]} of {@code z}, in place, for two SAME-WIDTH packed arrays whose
	 * slab sizes agree; answers {@code z}. A mixed-width pair, a slab-count mismatch and
	 * a bad index all decline to the defun, which handles (or signals) them.
	 */
	private static @Nullable LispVal scatterRows(List<LispVal> args) {
		LispFloatArray z = packed(args.get(0));
		LispFloatArray g = packed(args.get(1));
		if (z == null || g == null || z.dims().length < 1) {
			return null;
		}
		int[] rows = rowIndexes(args.get(2), z.dims()[0]);
		if (rows == null) {
			return null;
		}
		int slab = z.dims()[0] == 0 ? 0 : z.totalSize() / z.dims()[0];
		if ((long) rows.length * slab != g.totalSize()) {
			return null;
		}
		switch (z) {
			case LispDoubleFloatArray zd -> {
				if (!(g instanceof LispDoubleFloatArray gd)) {
					return null;
				}
				LinalgSimdKernels.scatterRows(zd.data(), gd.data(), slab, rows);
				written(zd.storage());
			}
			case LispSingleFloatArray zf -> {
				if (!(g instanceof LispSingleFloatArray gf)) {
					return null;
				}
				LinalgSimdKernels.scatterRowsF(zf.data(), gf.data(), slab, rows);
				written(zf.storage());
			}
			// No lane kernel scatters at this width; the scalar defun answers.
			case LispBFloat16Array ignored -> {
				return null;
			}
		}
		return z;
	}

	/**
	 * {@code (linalg::%la-sum-squares g acc)}: the accumulator plus the sum of the
	 * squares of a packed array's elements, left-folded in double from {@code acc} --
	 * answered as a double. A boxed array or a non-double/integer accumulator declines.
	 */
	private static @Nullable LispVal sumSquares(List<LispVal> args) {
		LispFloatArray g = packed(args.get(0));
		Double acc = scalar(args.get(1));
		if (g == null || acc == null) {
			return null;
		}
		return switch (g) {
			case LispDoubleFloatArray d -> new LispDouble(LinalgSimdKernels.sumSquares(d.data(), acc));
			case LispSingleFloatArray f -> new LispDouble(LinalgSimdKernels.sumSquares(f.data(), acc));
			// No lane kernel reads this width; the scalar defun answers.
			case LispBFloat16Array ignored -> null;
		};
	}

	/**
	 * {@code (linalg::%la-scale g s)}: the packed array scaled in place by the number
	 * {@code s}; answers {@code g}. A boxed array or a ratio scalar declines.
	 */
	private static @Nullable LispVal scale(List<LispVal> args) {
		LispFloatArray g = packed(args.get(0));
		Double s = scalar(args.get(1));
		if (g == null || s == null) {
			return null;
		}
		switch (g) {
			case LispDoubleFloatArray d -> {
				LinalgSimdKernels.scale(d.data(), s);
				written(d.storage());
			}
			case LispSingleFloatArray f -> {
				LinalgSimdKernels.scale(f.data(), s);
				written(f.storage());
			}
			// No lane kernel scales this width in place; the scalar defun answers.
			case LispBFloat16Array ignored -> {
				return null;
			}
		}
		return g;
	}

	/**
	 * The three generator state words as exact non-negative {@code int}s below
	 * {@code 2^23} -- the range {@code linalg:seed} produces and {@code %la-rng-next}
	 * keeps, and the range in which {@code a * s} cannot overflow an {@code int} and Java
	 * {@code %} agrees with Lisp {@code mod}. Anything else declines.
	 */
	private static int @Nullable [] rngState(double[] s) {
		int[] w = new int[3];
		for (int i = 0; i < 3; i++) {
			int v = (int) s[i];
			if (v != s[i] || v < 0 || v >= 1 << 23) {
				return null;
			}
			w[i] = v;
		}
		return w;
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

	static @Nullable Integer smallInt(LispVal value) {
		return value instanceof LispInteger i && i.value() >= Integer.MIN_VALUE && i.value() <= Integer.MAX_VALUE
				? (int) i.value() : null;
	}

	// --- marshalling ------------------------------------------------------------------

	static @Nullable LispFloatArray packed(LispVal value) {
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
	static @Nullable Double scalar(LispVal value) {
		return switch (value) {
			case LispDouble d -> d.value();
			case LispInteger i -> (double) i.value();
			default -> null;
		};
	}

	/**
	 * A {@code make-array} shape designator: an integer, or a proper list of integers.
	 */
	static int @Nullable [] shape(LispVal value) {
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

	/**
	 * {@code linalg:reshape}'s own shape designator: {@link #shape(LispVal)}'s spelling
	 * plus the one it does not take -- ONE extent may be {@code -1}, resolved against
	 * {@code total} elements the numpy way ({@code linalg::%la-infer-shape}'s rule: a
	 * bare {@code -1} flattens; more than one {@code -1}, or a known-extent product that
	 * does not divide {@code total} evenly, is not this reader's to accept, and the defun
	 * signals). No other reader of {@code shape} takes this spelling.
	 */
	static int @Nullable [] reshapeShape(LispVal value, long total) {
		if (value instanceof LispInteger n) {
			if (n.value() == -1) {
				return total >= 0 && total <= Integer.MAX_VALUE ? new int[] { (int) total } : null;
			}
			return n.value() >= 0 && n.value() <= Integer.MAX_VALUE ? new int[] { (int) n.value() } : null;
		}
		List<Integer> dims = new ArrayList<>();
		LispVal cursor = value;
		while (cursor instanceof LispCons cons) {
			if (!(cons.car() instanceof LispInteger d) || d.value() < -1 || d.value() > Integer.MAX_VALUE) {
				return null;
			}
			dims.add((int) d.value());
			cursor = cons.cdr();
		}
		if (!(cursor instanceof LispNil) || dims.isEmpty()) {
			return null;
		}
		int[] raw = new int[dims.size()];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = dims.get(i);
		}
		return resolveMinusOne(raw, total);
	}

	/**
	 * Resolves at most one {@code -1} extent in {@code raw} against {@code total}
	 * elements, or declines: a second {@code -1}, or a known-extent product that is zero
	 * or does not divide {@code total} evenly, answers {@code null}.
	 */
	private static int @Nullable [] resolveMinusOne(int[] raw, long total) {
		int minusAt = -1;
		long known = 1;
		for (int i = 0; i < raw.length; i++) {
			if (raw[i] == -1) {
				if (minusAt >= 0) {
					return null;
				}
				minusAt = i;
			}
			else {
				known *= raw[i];
			}
		}
		if (minusAt < 0) {
			return raw;
		}
		if (known <= 0 || total % known != 0) {
			return null;
		}
		long inferred = total / known;
		if (inferred < 0 || inferred > Integer.MAX_VALUE) {
			return null;
		}
		int[] out = raw.clone();
		out[minusAt] = (int) inferred;
		return out;
	}

	/**
	 * The backing of an operand ALREADY PROVEN to be a double-float array -- every caller
	 * is inside the arm of an exhaustive {@code switch} over a sibling operand whose
	 * class it was checked against, so the narrowing cannot fail whatever widths exist.
	 */
	private static double[] doubles(LispFloatArray a) {
		return ((LispDoubleFloatArray) a).data();
	}

	/** The single-float counterpart of {@link #doubles}, under the same proof. */
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

	/**
	 * The six lane loops of one element-wise operator (two widths x three shapes), plus
	 * its {@code LinalgSimdKernels.BOP_*} code for the general broadcast walk.
	 */
	private record Elementwise(int bop, ArrayArrayD dd, ArrayArrayF ff, ArrayScalarD ds, ArrayScalarF fs,
			ScalarArrayD sd, ScalarArrayF sf) {
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

	/**
	 * Reports that the given packed storage arrays were written in place, for whatever
	 * flag keeps a copy of them elsewhere -- {@code --gpu}'s device residency
	 * ({@code FloatArrayAccessHook}). The four in-place members here bypass the element
	 * setter that reports for every other write, so they report themselves -- naming the
	 * records' {@code storage()}, the identity the device is keyed on, and not the array
	 * {@code data()} handed them to write, which may be a result stub's backing.
	 */
	private static void written(Object... data) {
		for (Object array : data) {
			FloatArrayAccessHook.written(array);
		}
	}

}
