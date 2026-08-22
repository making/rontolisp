package am.ik.rontolisp;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The one seam every in-place write to a packed float array's storage passes through on
 * the interpreter, so that an acceleration flag which keeps a copy of that storage
 * elsewhere -- {@code --gpu}'s device residency, {@code .kb/gpu.md} -- can be told the
 * copy is stale.
 *
 * <p>
 * {@link LispDoubleFloatArray#setElement} and {@link LispSingleFloatArray#setElement}
 * call {@link #written} on every store, which covers {@code aset},
 * {@code row-major-aset}, {@code fill}, {@code replace} and every Lisp-level loop; the
 * {@code --simd} kernels that write a caller's array in place
 * ({@code linalg::%la-adam-step}, {@code %la-scatter-rows}, {@code %la-scale},
 * {@code %la-rng-fill} and the {@code vec:} {@code -into} family) call it themselves,
 * because they bypass the setter. That enumeration is the whole set -- every other
 * producer allocates a fresh array -- and it is pinned by the residency tests on the
 * interpreter.
 *
 * <p>
 * Lives in the root package, which depends on nothing, as a plain static hook rather than
 * a reference to any accelerator: the browser playground's build cuts {@code am.ik.gpu}
 * out by substituting {@code eval/LinalgGpu}, and a reference from here would pull it
 * back in. With no listener installed a write costs one volatile read.
 */
public final class FloatArrayWriteHook {

	private static volatile @Nullable Consumer<Object> listener;

	private FloatArrayWriteHook() {
	}

	/**
	 * Installs the listener every packed float array write is reported to, or removes it.
	 * @param hook the listener, given the {@code double[]} or {@code float[]} that was
	 * written; {@code null} to uninstall
	 */
	public static void install(@Nullable Consumer<Object> hook) {
		listener = hook;
	}

	/**
	 * Reports that {@code data} -- a packed array's {@code double[]} or {@code float[]}
	 * storage -- has been written in place.
	 * @param data the storage that was written
	 */
	public static void written(Object data) {
		Consumer<Object> hook = listener;
		if (hook != null) {
			hook.accept(data);
		}
	}

}
