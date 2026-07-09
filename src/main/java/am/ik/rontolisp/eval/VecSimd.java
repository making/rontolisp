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

}
