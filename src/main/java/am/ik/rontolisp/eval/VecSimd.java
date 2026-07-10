package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;

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
	 */
	public static void install(Environment globalEnv) {
		define(globalEnv, LispNames.VEC_ADD, VecSimdKernels::add, VecSimdKernels::addF);
		define(globalEnv, LispNames.VEC_SUB, VecSimdKernels::sub, VecSimdKernels::subF);
		define(globalEnv, LispNames.VEC_MUL, VecSimdKernels::mul, VecSimdKernels::mulF);
		defineFn(globalEnv, LispNames.VEC_SCALE, 2, (name, args) -> {
			LispFloatArray v = array(name, args.get(0));
			double s = scalar(name, args.get(1));
			return switch (v) {
				case LispDoubleFloatArray d -> vector(VecSimdKernels.scale(d.data(), s));
				case LispSingleFloatArray f -> vector(VecSimdKernels.scaleF(f.data(), s));
			};
		});
		defineFn(globalEnv, LispNames.VEC_SUM, 1, (name, args) -> {
			LispFloatArray v = array(name, args.get(0));
			return new LispDouble(switch (v) {
				case LispDoubleFloatArray d -> VecSimdKernels.sum(d.data());
				case LispSingleFloatArray f -> VecSimdKernels.sumF(f.data());
			});
		});
		defineFn(globalEnv, LispNames.VEC_DOT, 2, (name, args) -> {
			LispFloatArray a = array(name, args.get(0));
			LispFloatArray b = array(name, args.get(1));
			if (a instanceof LispDoubleFloatArray x && b instanceof LispDoubleFloatArray y) {
				return new LispDouble(VecSimdKernels.dot(x.data(), y.data()));
			}
			if (a instanceof LispSingleFloatArray x && b instanceof LispSingleFloatArray y) {
				return new LispDouble(VecSimdKernels.dotF(x.data(), y.data()));
			}
			throw mixedWidth(name);
		});
		defineFn(globalEnv, LispNames.VEC_MATVEC, 2, (name, args) -> {
			LispFloatArray w = array(name, args.get(0));
			LispFloatArray x = array(name, args.get(1));
			if (w.rank() != 2) {
				throw new LispEvalException(qualified(name) + " expects a rank-2 matrix, got rank " + w.rank());
			}
			int rows = w.dims()[0];
			int cols = w.dims()[1];
			if (w instanceof LispDoubleFloatArray mw && x instanceof LispDoubleFloatArray vx) {
				return vector(VecSimdKernels.matvec(mw.data(), rows, cols, vx.data()));
			}
			if (w instanceof LispSingleFloatArray mw && x instanceof LispSingleFloatArray vx) {
				return vector(VecSimdKernels.matvecF(mw.data(), rows, cols, vx.data()));
			}
			throw mixedWidth(name);
		});
		installUnary(globalEnv);
		installInto(globalEnv);
	}

	/**
	 * The element-wise unary ufuncs (todo 109): {@code exp}/{@code sqrt}/{@code abs}/
	 * {@code negative}/{@code sign}/{@code reciprocal}, each with its {@code -into}
	 * sibling. {@code square}/{@code square-into} are NOT installed -- their
	 * {@code vec.lisp} bodies call {@code vec:mul}/{@code vec:mul-into}, which resolve
	 * through the global function namespace to the installed natives, exactly like
	 * {@code mean}/{@code norm}. An {@code -into} destination MAY alias the operand
	 * (element {@code i} depends only on element {@code i}, the add-into rule) -- these
	 * natives replace the scalar {@code vec.lisp} defuns, so that contract is repeated
	 * here.
	 */
	private static void installUnary(Environment globalEnv) {
		defineUnary(globalEnv, LispNames.VEC_EXP, VecSimdKernels::expInto, VecSimdKernels::expIntoF);
		defineUnary(globalEnv, LispNames.VEC_SQRT, VecSimdKernels::sqrtInto, VecSimdKernels::sqrtIntoF);
		defineUnary(globalEnv, LispNames.VEC_ABS, VecSimdKernels::absInto, VecSimdKernels::absIntoF);
		defineUnary(globalEnv, LispNames.VEC_NEGATIVE, VecSimdKernels::negInto, VecSimdKernels::negIntoF);
		defineUnary(globalEnv, LispNames.VEC_SIGN, VecSimdKernels::signInto, VecSimdKernels::signIntoF);
		defineUnary(globalEnv, LispNames.VEC_RECIPROCAL, VecSimdKernels::reciprocalInto,
				VecSimdKernels::reciprocalIntoF);
		defineUnaryInto(globalEnv, LispNames.VEC_EXP_INTO, VecSimdKernels::expInto, VecSimdKernels::expIntoF);
		defineUnaryInto(globalEnv, LispNames.VEC_SQRT_INTO, VecSimdKernels::sqrtInto, VecSimdKernels::sqrtIntoF);
		defineUnaryInto(globalEnv, LispNames.VEC_ABS_INTO, VecSimdKernels::absInto, VecSimdKernels::absIntoF);
		defineUnaryInto(globalEnv, LispNames.VEC_NEGATIVE_INTO, VecSimdKernels::negInto, VecSimdKernels::negIntoF);
		defineUnaryInto(globalEnv, LispNames.VEC_SIGN_INTO, VecSimdKernels::signInto, VecSimdKernels::signIntoF);
		defineUnaryInto(globalEnv, LispNames.VEC_RECIPROCAL_INTO, VecSimdKernels::reciprocalInto,
				VecSimdKernels::reciprocalIntoF);
	}

	/**
	 * The destination-passing kernels (todo 103). Each writes into its first argument and
	 * returns THAT LispVal unchanged, so {@code (vec:add-into acc acc d)} keeps
	 * {@code acc} {@code eq} to itself -- wrapping the backing array in a fresh
	 * {@link LispDoubleFloatArray} would not.
	 *
	 * <p>
	 * These natives replace the scalar {@code vec.lisp} defuns, so the
	 * {@code vec:matvec-into} alias guard written there has to be repeated here.
	 */
	private static void installInto(Environment globalEnv) {
		defineInto(globalEnv, LispNames.VEC_ADD_INTO, VecSimdKernels::addInto, VecSimdKernels::addIntoF);
		defineInto(globalEnv, LispNames.VEC_SUB_INTO, VecSimdKernels::subInto, VecSimdKernels::subIntoF);
		defineInto(globalEnv, LispNames.VEC_MUL_INTO, VecSimdKernels::mulInto, VecSimdKernels::mulIntoF);
		defineFn(globalEnv, LispNames.VEC_SCALE_INTO, 3, (name, args) -> {
			LispFloatArray out = array(name, args.get(0));
			LispFloatArray v = array(name, args.get(1));
			double s = scalar(name, args.get(2));
			if (out instanceof LispDoubleFloatArray r && v instanceof LispDoubleFloatArray x) {
				VecSimdKernels.scaleInto(r.data(), x.data(), s);
			}
			else if (out instanceof LispSingleFloatArray r && v instanceof LispSingleFloatArray x) {
				VecSimdKernels.scaleIntoF(r.data(), x.data(), s);
			}
			else {
				throw mixedWidth(name);
			}
			return args.get(0);
		});
		defineFn(globalEnv, LispNames.VEC_MATVEC_INTO, 3, (name, args) -> {
			LispFloatArray out = array(name, args.get(0));
			LispFloatArray w = array(name, args.get(1));
			LispFloatArray x = array(name, args.get(2));
			if (w.rank() != 2) {
				throw new LispEvalException(qualified(name) + " expects a rank-2 matrix, got rank " + w.rank());
			}
			int rows = w.dims()[0];
			int cols = w.dims()[1];
			if (out instanceof LispDoubleFloatArray r && w instanceof LispDoubleFloatArray mw
					&& x instanceof LispDoubleFloatArray vx) {
				requireDisjoint(name, r.data() == mw.data() || r.data() == vx.data());
				VecSimdKernels.matvecInto(r.data(), mw.data(), rows, cols, vx.data());
			}
			else if (out instanceof LispSingleFloatArray r && w instanceof LispSingleFloatArray mw
					&& x instanceof LispSingleFloatArray vx) {
				requireDisjoint(name, r.data() == mw.data() || r.data() == vx.data());
				VecSimdKernels.matvecIntoF(r.data(), mw.data(), rows, cols, vx.data());
			}
			else {
				throw mixedWidth(name);
			}
			return args.get(0);
		});
	}

	/**
	 * A unary element-wise kernel pair, wrapped as the allocating form: a fresh
	 * destination of the operand's width and length, written by the same loop the
	 * {@code -into} form uses (so the two stay bit-identical by construction).
	 */
	private static void defineUnary(Environment globalEnv, String name, DoubleKernel1Into f64, FloatKernel1Into f32) {
		defineFn(globalEnv, name, 1, (fnName, args) -> {
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
			};
		});
	}

	/** A unary element-wise -into kernel pair; returns the destination it was given. */
	private static void defineUnaryInto(Environment globalEnv, String name, DoubleKernel1Into f64,
			FloatKernel1Into f32) {
		defineFn(globalEnv, name, 2, (fnName, args) -> {
			LispFloatArray out = array(fnName, args.get(0));
			LispFloatArray v = array(fnName, args.get(1));
			if (out instanceof LispDoubleFloatArray r && v instanceof LispDoubleFloatArray x) {
				f64.apply(r.data(), x.data());
			}
			else if (out instanceof LispSingleFloatArray r && v instanceof LispSingleFloatArray x) {
				f32.apply(r.data(), x.data());
			}
			else {
				throw mixedWidth(fnName);
			}
			return args.get(0);
		});
	}

	/**
	 * An element-wise -into kernel pair (the f64 and f32 lane loops of one operation).
	 */
	private static void defineInto(Environment globalEnv, String name, DoubleKernel2Into f64, FloatKernel2Into f32) {
		defineFn(globalEnv, name, 3, (fnName, args) -> {
			LispFloatArray out = array(fnName, args.get(0));
			LispFloatArray a = array(fnName, args.get(1));
			LispFloatArray b = array(fnName, args.get(2));
			if (out instanceof LispDoubleFloatArray r && a instanceof LispDoubleFloatArray x
					&& b instanceof LispDoubleFloatArray y) {
				f64.apply(r.data(), x.data(), y.data());
			}
			else if (out instanceof LispSingleFloatArray r && a instanceof LispSingleFloatArray x
					&& b instanceof LispSingleFloatArray y) {
				f32.apply(r.data(), x.data(), y.data());
			}
			else {
				throw mixedWidth(fnName);
			}
			return args.get(0);
		});
	}

	/** An element-wise kernel pair (the f64 and f32 lane loops of one operation). */
	private static void define(Environment globalEnv, String name, DoubleKernel2 f64, FloatKernel2 f32) {
		defineFn(globalEnv, name, 2, (fnName, args) -> {
			LispFloatArray a = array(fnName, args.get(0));
			LispFloatArray b = array(fnName, args.get(1));
			if (a instanceof LispDoubleFloatArray x && b instanceof LispDoubleFloatArray y) {
				return vector(f64.apply(x.data(), y.data()));
			}
			if (a instanceof LispSingleFloatArray x && b instanceof LispSingleFloatArray y) {
				return vector(f32.apply(x.data(), y.data()));
			}
			throw mixedWidth(fnName);
		});
	}

	private static void defineFn(Environment globalEnv, String name, int arity, Kernel body) {
		String qualified = qualified(name);
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() != arity) {
				throw new LispEvalException(qualified + " expects " + arity + " arguments, got " + args.size());
			}
			return body.apply(name, args);
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

		LispVal apply(String name, List<LispVal> args);

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
