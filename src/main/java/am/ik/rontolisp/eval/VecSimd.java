package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.FloatArrayAccessHook;
import am.ik.rontolisp.LispBFloat16Array;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's opt-in {@code --simd} acceleration of the {@code vec:} kernels: it
 * replaces the seven vectorizable {@code vec.lisp} defuns
 * ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code sum}/{@code dot}/
 * {@code matvec}) with native {@link LispFunction}s driving the
 * {@code jdk.incubator.vector} lane loops in {@link VecSimdKernels}.
 * {@code mean}/{@code norm} are accelerated transitively: their {@code vec.lisp} bodies
 * call {@code vec:sum} / {@code vec:dot}, which resolve through the global function
 * namespace to the installed natives.
 *
 * <p>
 * This is opt-in per invocation ({@code rontolisp prog.lisp --simd}). The DEFAULT
 * interpreter keeps running the scalar {@code vec.lisp} defuns -- it is the cross-backend
 * byte-identity oracle and must not change. Where the Vector API is absent
 * ({@code java -jar} without {@code --add-modules jdk.incubator.vector}; the native
 * binary bakes it in), {@link #available()} reports {@code false} and the caller falls
 * back to the scalar reference rather than failing.
 *
 * <p>
 * Only these two methods touch {@link VecSimdKernels}, so substituting them is enough to
 * keep the incubator Vector API out of the browser Web Image build (see
 * {@code src/web/java/.../Target_VecSimd.java}).
 *
 * @see VecSimdKernels
 */
public final class VecSimd {

	private VecSimd() {
	}

	/**
	 * Returns whether the {@code jdk.incubator.vector} module is present in this runtime,
	 * by linking {@link VecSimdKernels}. A JVM started without {@code --add-modules
	 * jdk.incubator.vector} raises {@link NoClassDefFoundError} on that link; the native
	 * binary has the module baked in and always reports {@code true}.
	 * @return {@code true} when the vec: kernels can be vectorized
	 */
	public static boolean available() {
		try {
			return VecSimdKernels.laneCount() > 0;
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * Overrides the vectorizable {@code vec:} functions in the given (global) environment
	 * with the Vector-API natives. Must be called AFTER the scalar {@code vec.lisp} forms
	 * have been evaluated into the same environment, so these definitions win, and only
	 * when {@link #available()} is {@code true}.
	 * @param globalEnv the global environment holding the loaded vec library
	 * @param evaluator the evaluator used to run the scalar defun a kernel declines to
	 * @param parallel {@code --parallel}: run {@code vec:matvec} /
	 * {@code vec:matvec-into} over a row range per thread when the call is worth it
	 * ({@link SimdParallel}) -- the same row chains, so the same bits
	 */
	public static void install(Environment globalEnv, LispEvaluator evaluator, boolean parallel) {
		define(globalEnv, evaluator, LispNames.VEC_ADD, VecSimdKernels::add, VecSimdKernels::addF);
		define(globalEnv, evaluator, LispNames.VEC_SUB, VecSimdKernels::sub, VecSimdKernels::subF);
		define(globalEnv, evaluator, LispNames.VEC_MUL, VecSimdKernels::mul, VecSimdKernels::mulF);
		define(globalEnv, evaluator, LispNames.VEC_DIV, VecSimdKernels::div, VecSimdKernels::divF);
		// The CL operator spellings bind the very kernels their named siblings bind, so
		// an accelerated build never runs the one-line alias defun from vec.lisp.
		define(globalEnv, evaluator, LispNames.VEC_PLUS, VecSimdKernels::add, VecSimdKernels::addF);
		define(globalEnv, evaluator, LispNames.VEC_MINUS, VecSimdKernels::sub, VecSimdKernels::subF);
		define(globalEnv, evaluator, LispNames.VEC_STAR, VecSimdKernels::mul, VecSimdKernels::mulF);
		define(globalEnv, evaluator, LispNames.VEC_SLASH, VecSimdKernels::div, VecSimdKernels::divF);
		defineFn(globalEnv, evaluator, LispNames.VEC_SCALE, 2, (name, args) -> {
			LispFloatArray v = array(name, args.get(0));
			double s = scalar(name, args.get(1));
			return switch (v) {
				case LispDoubleFloatArray d -> vector(VecSimdKernels.scale(d.data(), s));
				case LispSingleFloatArray f -> vector(VecSimdKernels.scaleF(f.data(), s));
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		});
		defineFn(globalEnv, evaluator, LispNames.VEC_SUM, 1, (name, args) -> {
			LispFloatArray v = array(name, args.get(0));
			return switch (v) {
				case LispDoubleFloatArray d -> new LispDouble(VecSimdKernels.sum(d.data()));
				case LispSingleFloatArray f -> new LispDouble(VecSimdKernels.sumF(f.data()));
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		});
		defineFn(globalEnv, evaluator, LispNames.VEC_DOT, 2, (name, args) -> {
			LispFloatArray a = array(name, args.get(0));
			LispFloatArray b = array(name, args.get(1));
			return switch (a) {
				case LispDoubleFloatArray x -> {
					if (!(b instanceof LispDoubleFloatArray y)) {
						throw mixedWidth(name);
					}
					yield new LispDouble(VecSimdKernels.dot(x.data(), y.data()));
				}
				case LispSingleFloatArray x -> {
					if (!(b instanceof LispSingleFloatArray y)) {
						throw mixedWidth(name);
					}
					yield new LispDouble(VecSimdKernels.dotF(x.data(), y.data()));
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		});
		defineFn(globalEnv, evaluator, LispNames.VEC_MATVEC, 2, (name, args) -> {
			LispFloatArray w = array(name, args.get(0));
			LispFloatArray x = array(name, args.get(1));
			if (w.rank() != 2) {
				throw new LispEvalException(qualified(name) + " expects a rank-2 matrix, got rank " + w.rank());
			}
			int rows = w.dims()[0];
			int cols = w.dims()[1];
			return switch (w) {
				case LispDoubleFloatArray mw -> {
					if (!(x instanceof LispDoubleFloatArray vx)) {
						throw mixedWidth(name);
					}
					yield vector(VecSimdKernels.matvec(mw.data(), rows, cols, vx.data(), parallel));
				}
				case LispSingleFloatArray mw -> {
					if (!(x instanceof LispSingleFloatArray vx)) {
						throw mixedWidth(name);
					}
					yield vector(VecSimdKernels.matvecF(mw.data(), rows, cols, vx.data(), parallel));
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		});
		installUnary(globalEnv, evaluator);
		installCompare(globalEnv, evaluator);
		installInto(globalEnv, evaluator, parallel);
	}

	/**
	 * The comparison-select ufuncs: {@code maximum}/{@code minimum}/
	 * {@code relu}/{@code clip} with their {@code -into} siblings. All are defined by the
	 * strict comparison select the {@code vec.lisp} defuns spell out ({@code (if (>
	 * x y) x y)} and its mirrors), never {@code Math.max}/{@code Math.min}, so the second
	 * operand or the bound wins on any false comparison (ties and NaN included).
	 */
	private static void installCompare(Environment globalEnv, LispEvaluator evaluator) {
		define(globalEnv, evaluator, LispNames.VEC_MAXIMUM, VecSimdKernels::maximum, VecSimdKernels::maximumF);
		define(globalEnv, evaluator, LispNames.VEC_MINIMUM, VecSimdKernels::minimum, VecSimdKernels::minimumF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_RELU, VecSimdKernels::reluInto, VecSimdKernels::reluIntoF);
		defineFn(globalEnv, evaluator, LispNames.VEC_CLIP, 3, (name, args) -> {
			LispFloatArray v = array(name, args.get(0));
			double lo = scalar(name, args.get(1));
			double hi = scalar(name, args.get(2));
			return switch (v) {
				case LispDoubleFloatArray x -> {
					double[] r = new double[x.data().length];
					VecSimdKernels.clipInto(r, x.data(), lo, hi);
					yield vector(r);
				}
				case LispSingleFloatArray x -> {
					float[] r = new float[x.data().length];
					VecSimdKernels.clipIntoF(r, x.data(), lo, hi);
					yield vector(r);
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		});
		defineInto(globalEnv, evaluator, LispNames.VEC_MAXIMUM_INTO, VecSimdKernels::maximumInto,
				VecSimdKernels::maximumIntoF);
		defineInto(globalEnv, evaluator, LispNames.VEC_MINIMUM_INTO, VecSimdKernels::minimumInto,
				VecSimdKernels::minimumIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_RELU_INTO, VecSimdKernels::reluInto,
				VecSimdKernels::reluIntoF);
		defineFn(globalEnv, evaluator, LispNames.VEC_CLIP_INTO, 4, (name, args) -> {
			LispFloatArray out = array(name, args.get(0));
			LispFloatArray v = array(name, args.get(1));
			double lo = scalar(name, args.get(2));
			double hi = scalar(name, args.get(3));
			switch (out) {
				case LispDoubleFloatArray r -> {
					if (!(v instanceof LispDoubleFloatArray x)) {
						throw mixedWidth(name);
					}
					VecSimdKernels.clipInto(r.data(), x.data(), lo, hi);
					FloatArrayAccessHook.written(r.storage());
				}
				case LispSingleFloatArray r -> {
					if (!(v instanceof LispSingleFloatArray x)) {
						throw mixedWidth(name);
					}
					VecSimdKernels.clipIntoF(r.data(), x.data(), lo, hi);
					FloatArrayAccessHook.written(r.storage());
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> {
					return null;
				}
			}
			return args.get(0);
		});
	}

	/**
	 * The element-wise unary ufuncs: {@code exp}/{@code log}/{@code tanh}/
	 * {@code sin}/{@code cos}/{@code tan}/{@code asin}/{@code acos}/{@code atan}/
	 * {@code sinh}/{@code cosh}/{@code sqrt}/{@code abs}/{@code negative}/
	 * {@code sign}/{@code reciprocal}, each with its {@code -into} sibling.
	 * {@code square}/{@code square-into} are NOT installed -- their {@code vec.lisp}
	 * bodies call {@code vec:mul}/{@code vec:mul-into}, which resolve through the global
	 * function namespace to the installed natives, exactly like
	 * {@code mean}/{@code norm}. An {@code -into} destination MAY alias the operand
	 * (element {@code i} depends only on element {@code i}, the add-into rule) -- these
	 * natives replace the scalar {@code vec.lisp} defuns, so that contract is repeated
	 * here.
	 */
	private static void installUnary(Environment globalEnv, LispEvaluator evaluator) {
		defineUnary(globalEnv, evaluator, LispNames.VEC_EXP, VecSimdKernels::expInto, VecSimdKernels::expIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_LOG, VecSimdKernels::logInto, VecSimdKernels::logIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_TANH, VecSimdKernels::tanhInto, VecSimdKernels::tanhIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_SIN, VecSimdKernels::sinInto, VecSimdKernels::sinIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_COS, VecSimdKernels::cosInto, VecSimdKernels::cosIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_TAN, VecSimdKernels::tanInto, VecSimdKernels::tanIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_ASIN, VecSimdKernels::asinInto, VecSimdKernels::asinIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_ACOS, VecSimdKernels::acosInto, VecSimdKernels::acosIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_ATAN, VecSimdKernels::atanInto, VecSimdKernels::atanIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_SINH, VecSimdKernels::sinhInto, VecSimdKernels::sinhIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_COSH, VecSimdKernels::coshInto, VecSimdKernels::coshIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_SQRT, VecSimdKernels::sqrtInto, VecSimdKernels::sqrtIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_ABS, VecSimdKernels::absInto, VecSimdKernels::absIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_NEGATIVE, VecSimdKernels::negInto, VecSimdKernels::negIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_SIGN, VecSimdKernels::signInto, VecSimdKernels::signIntoF);
		defineUnary(globalEnv, evaluator, LispNames.VEC_RECIPROCAL, VecSimdKernels::reciprocalInto,
				VecSimdKernels::reciprocalIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_EXP_INTO, VecSimdKernels::expInto,
				VecSimdKernels::expIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_LOG_INTO, VecSimdKernels::logInto,
				VecSimdKernels::logIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_TANH_INTO, VecSimdKernels::tanhInto,
				VecSimdKernels::tanhIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_SIN_INTO, VecSimdKernels::sinInto,
				VecSimdKernels::sinIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_COS_INTO, VecSimdKernels::cosInto,
				VecSimdKernels::cosIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_TAN_INTO, VecSimdKernels::tanInto,
				VecSimdKernels::tanIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_ASIN_INTO, VecSimdKernels::asinInto,
				VecSimdKernels::asinIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_ACOS_INTO, VecSimdKernels::acosInto,
				VecSimdKernels::acosIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_ATAN_INTO, VecSimdKernels::atanInto,
				VecSimdKernels::atanIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_SINH_INTO, VecSimdKernels::sinhInto,
				VecSimdKernels::sinhIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_COSH_INTO, VecSimdKernels::coshInto,
				VecSimdKernels::coshIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_SQRT_INTO, VecSimdKernels::sqrtInto,
				VecSimdKernels::sqrtIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_ABS_INTO, VecSimdKernels::absInto,
				VecSimdKernels::absIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_NEGATIVE_INTO, VecSimdKernels::negInto,
				VecSimdKernels::negIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_SIGN_INTO, VecSimdKernels::signInto,
				VecSimdKernels::signIntoF);
		defineUnaryInto(globalEnv, evaluator, LispNames.VEC_RECIPROCAL_INTO, VecSimdKernels::reciprocalInto,
				VecSimdKernels::reciprocalIntoF);
	}

	/**
	 * The destination-passing kernels. Each writes into its first argument and returns
	 * THAT LispVal unchanged, so {@code (vec:add-into acc acc d)} keeps {@code acc}
	 * {@code eq} to itself -- wrapping the backing array in a fresh
	 * {@link LispDoubleFloatArray} would not.
	 *
	 * <p>
	 * These natives replace the scalar {@code vec.lisp} defuns, so the
	 * {@code vec:matvec-into} alias guard written there has to be repeated here.
	 */
	private static void installInto(Environment globalEnv, LispEvaluator evaluator, boolean parallel) {
		defineInto(globalEnv, evaluator, LispNames.VEC_ADD_INTO, VecSimdKernels::addInto, VecSimdKernels::addIntoF);
		defineInto(globalEnv, evaluator, LispNames.VEC_SUB_INTO, VecSimdKernels::subInto, VecSimdKernels::subIntoF);
		defineInto(globalEnv, evaluator, LispNames.VEC_MUL_INTO, VecSimdKernels::mulInto, VecSimdKernels::mulIntoF);
		defineInto(globalEnv, evaluator, LispNames.VEC_DIV_INTO, VecSimdKernels::divInto, VecSimdKernels::divIntoF);
		defineFn(globalEnv, evaluator, LispNames.VEC_SCALE_INTO, 3, (name, args) -> {
			LispFloatArray out = array(name, args.get(0));
			LispFloatArray v = array(name, args.get(1));
			double s = scalar(name, args.get(2));
			switch (out) {
				case LispDoubleFloatArray r -> {
					if (!(v instanceof LispDoubleFloatArray x)) {
						throw mixedWidth(name);
					}
					VecSimdKernels.scaleInto(r.data(), x.data(), s);
					FloatArrayAccessHook.written(r.storage());
				}
				case LispSingleFloatArray r -> {
					if (!(v instanceof LispSingleFloatArray x)) {
						throw mixedWidth(name);
					}
					VecSimdKernels.scaleIntoF(r.data(), x.data(), s);
					FloatArrayAccessHook.written(r.storage());
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> {
					return null;
				}
			}
			return args.get(0);
		});
		defineFn(globalEnv, evaluator, LispNames.VEC_MATVEC_INTO, 3, (name, args) -> {
			LispFloatArray out = array(name, args.get(0));
			LispFloatArray w = array(name, args.get(1));
			LispFloatArray x = array(name, args.get(2));
			if (w.rank() != 2) {
				throw new LispEvalException(qualified(name) + " expects a rank-2 matrix, got rank " + w.rank());
			}
			int rows = w.dims()[0];
			int cols = w.dims()[1];
			switch (out) {
				case LispDoubleFloatArray r -> {
					if (!(w instanceof LispDoubleFloatArray mw) || !(x instanceof LispDoubleFloatArray vx)) {
						throw mixedWidth(name);
					}
					requireDisjoint(name, r.data() == mw.data() || r.data() == vx.data());
					VecSimdKernels.matvecInto(r.data(), mw.data(), rows, cols, vx.data(), parallel);
					FloatArrayAccessHook.written(r.storage());
				}
				case LispSingleFloatArray r -> {
					if (!(w instanceof LispSingleFloatArray mw) || !(x instanceof LispSingleFloatArray vx)) {
						throw mixedWidth(name);
					}
					requireDisjoint(name, r.data() == mw.data() || r.data() == vx.data());
					VecSimdKernels.matvecIntoF(r.data(), mw.data(), rows, cols, vx.data(), parallel);
					FloatArrayAccessHook.written(r.storage());
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> {
					return null;
				}
			}
			return args.get(0);
		});
	}

	/**
	 * A unary element-wise kernel pair, wrapped as the allocating form: a fresh
	 * destination of the operand's width and length, written by the same loop the
	 * {@code -into} form uses (so the two stay bit-identical by construction).
	 */
	private static void defineUnary(Environment globalEnv, LispEvaluator evaluator, String name, DoubleKernel1Into f64,
			FloatKernel1Into f32) {
		defineFn(globalEnv, evaluator, name, 1, (fnName, args) -> {
			LispFloatArray v = array(fnName, args.get(0));
			return switch (v) {
				case LispDoubleFloatArray x -> {
					double[] r = new double[x.data().length];
					f64.apply(r, x.data());
					yield vector(r);
				}
				case LispSingleFloatArray x -> {
					float[] r = new float[x.data().length];
					f32.apply(r, x.data());
					yield vector(r);
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		});
	}

	/** A unary element-wise -into kernel pair; returns the destination it was given. */
	private static void defineUnaryInto(Environment globalEnv, LispEvaluator evaluator, String name,
			DoubleKernel1Into f64, FloatKernel1Into f32) {
		defineFn(globalEnv, evaluator, name, 2, (fnName, args) -> {
			LispFloatArray out = array(fnName, args.get(0));
			LispFloatArray v = array(fnName, args.get(1));
			switch (out) {
				case LispDoubleFloatArray r -> {
					if (!(v instanceof LispDoubleFloatArray x)) {
						throw mixedWidth(fnName);
					}
					f64.apply(r.data(), x.data());
					FloatArrayAccessHook.written(r.storage());
				}
				case LispSingleFloatArray r -> {
					if (!(v instanceof LispSingleFloatArray x)) {
						throw mixedWidth(fnName);
					}
					f32.apply(r.data(), x.data());
					FloatArrayAccessHook.written(r.storage());
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> {
					return null;
				}
			}
			return args.get(0);
		});
	}

	/**
	 * An element-wise -into kernel pair (the f64 and f32 lane loops of one operation).
	 */
	private static void defineInto(Environment globalEnv, LispEvaluator evaluator, String name, DoubleKernel2Into f64,
			FloatKernel2Into f32) {
		defineFn(globalEnv, evaluator, name, 3, (fnName, args) -> {
			LispFloatArray out = array(fnName, args.get(0));
			LispFloatArray a = array(fnName, args.get(1));
			LispFloatArray b = array(fnName, args.get(2));
			switch (out) {
				case LispDoubleFloatArray r -> {
					if (!(a instanceof LispDoubleFloatArray x) || !(b instanceof LispDoubleFloatArray y)) {
						throw mixedWidth(fnName);
					}
					f64.apply(r.data(), x.data(), y.data());
					FloatArrayAccessHook.written(r.storage());
				}
				case LispSingleFloatArray r -> {
					if (!(a instanceof LispSingleFloatArray x) || !(b instanceof LispSingleFloatArray y)) {
						throw mixedWidth(fnName);
					}
					f32.apply(r.data(), x.data(), y.data());
					FloatArrayAccessHook.written(r.storage());
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> {
					return null;
				}
			}
			return args.get(0);
		});
	}

	/** An element-wise kernel pair (the f64 and f32 lane loops of one operation). */
	private static void define(Environment globalEnv, LispEvaluator evaluator, String name, DoubleKernel2 f64,
			FloatKernel2 f32) {
		defineFn(globalEnv, evaluator, name, 2, (fnName, args) -> {
			LispFloatArray a = array(fnName, args.get(0));
			LispFloatArray b = array(fnName, args.get(1));
			return switch (a) {
				case LispDoubleFloatArray x -> {
					if (!(b instanceof LispDoubleFloatArray y)) {
						throw mixedWidth(fnName);
					}
					yield vector(f64.apply(x.data(), y.data()));
				}
				case LispSingleFloatArray x -> {
					if (!(b instanceof LispSingleFloatArray y)) {
						throw mixedWidth(fnName);
					}
					yield vector(f32.apply(x.data(), y.data()));
				}
				// No lane kernel reads this width; the scalar defun answers.
				case LispBFloat16Array ignored -> null;
			};
		});
	}

	/**
	 * Registers one native over the binding that is already there, which it CAPTURES:
	 * when the kernel declines (answers {@code null}) that binding runs instead. Every
	 * member goes through here, so the decline protocol is the same one
	 * {@code LinalgSimd} uses one layer up, and {@code --simd} cannot change an answer --
	 * only how fast it arrives.
	 */
	private static void defineFn(Environment globalEnv, LispEvaluator evaluator, String name, int arity, Kernel body) {
		String qualified = qualified(name);
		LispVal scalarDefun = globalEnv.lookupFunctionOrNull(qualified);
		if (scalarDefun == null) {
			throw new IllegalStateException("vec.lisp must be loaded before " + qualified + " can be accelerated");
		}
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() != arity) {
				throw new LispEvalException(qualified + " expects " + arity + " arguments, got " + args.size());
			}
			LispVal fast = body.apply(name, args);
			return fast != null ? fast : evaluator.applyGlobal(scalarDefun, args);
		}));
	}

	private static String qualified(String name) {
		return LispNames.VEC_PKG + ":" + name;
	}

	private static LispFloatArray array(String name, LispVal value) {
		if (value instanceof LispFloatArray packed) {
			return packed;
		}
		throw new LispEvalException(qualified(name) + " expects a packed float array, got " + value.print());
	}

	private static double scalar(String name, LispVal value) {
		return switch (value) {
			case LispDouble d -> d.value();
			case LispInteger i -> i.value();
			default -> throw new LispEvalException(qualified(name) + " expects a number, got " + value.print());
		};
	}

	/**
	 * Signals when a {@code vec:matvec-into} destination shares storage with {@code w} or
	 * {@code x}: {@code out[row]} is a fold over all of {@code x}, so a store would
	 * clobber an element a later row still has to read. Aliasing is checked on the
	 * BACKING array, the actual sharing condition.
	 */
	private static void requireDisjoint(String name, boolean aliased) {
		if (aliased) {
			throw new LispEvalException(qualified(name)
					+ ": out must not be the same array as w or x (each out element folds over all of x)");
		}
	}

	private static LispEvalException mixedWidth(String name) {
		return new LispEvalException(
				qualified(name) + ": operands must share an element type (mixed single-float and double-float)");
	}

	private static LispVal vector(double[] data) {
		return new LispDoubleFloatArray(data, new int[] { data.length });
	}

	private static LispVal vector(float[] data) {
		return new LispSingleFloatArray(data, new int[] { data.length });
	}

	@FunctionalInterface
	private interface Kernel {

		/**
		 * The native answer, or {@code null} to DECLINE -- at which point
		 * {@link #defineFn} runs the binding this one replaced (the scalar
		 * {@code vec.lisp} defun). Declining rather than signalling is what keeps
		 * {@code --simd} a speed flag: a width the lane kernels cannot read still gets
		 * the defun's answer instead of an error.
		 * @param name the member's unqualified name, for error messages
		 * @param args the argument list
		 * @return the answer, or {@code null} to decline
		 */
		@Nullable LispVal apply(String name, List<LispVal> args);

	}

	@FunctionalInterface
	private interface DoubleKernel2 {

		double[] apply(double[] a, double[] b);

	}

	@FunctionalInterface
	private interface FloatKernel2 {

		float[] apply(float[] a, float[] b);

	}

	@FunctionalInterface
	private interface DoubleKernel1Into {

		void apply(double[] r, double[] x);

	}

	@FunctionalInterface
	private interface FloatKernel1Into {

		void apply(float[] r, float[] x);

	}

	@FunctionalInterface
	private interface DoubleKernel2Into {

		void apply(double[] out, double[] a, double[] b);

	}

	@FunctionalInterface
	private interface FloatKernel2Into {

		void apply(float[] out, float[] a, float[] b);

	}

}
