package am.ik.rontolisp;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The two seams every access to a packed float array's storage passes through on the
 * interpreter, so that an acceleration flag which keeps a copy of that storage elsewhere
 * -- {@code --gpu}'s device residency, {@code .kb/gpu.md} -- can be told (1) that the
 * copy is about to be stale, and (2) that the host is about to read bytes the device may
 * hold the only copy of.
 *
 * <p>
 * {@link LispDoubleFloatArray#setElement} and {@link LispSingleFloatArray#setElement}
 * call {@link #written} BEFORE every store, which covers {@code aset},
 * {@code row-major-aset}, {@code fill}, {@code replace} and every Lisp-level loop; the
 * {@code --simd} kernels that write a caller's array in place
 * ({@code linalg::%la-adam-step}, {@code %la-scatter-rows}, {@code %la-scale},
 * {@code %la-rng-fill} and the {@code vec:} {@code -into} family) and the bulk
 * {@code read-sequence} primitive call it themselves, because they bypass the setter.
 * That enumeration is the whole set -- every other producer allocates a fresh array --
 * and it is pinned by the residency tests on the interpreter.
 *
 * <p>
 * {@link #read} is the other half, and on the interpreter it has ONE seam: the records'
 * {@code data()} accessor, which every reader of a packed array's storage goes through
 * (the kernels, {@code aref}, the printer, {@code read-sequence}'s mirror, record
 * patterns, Java interop), plus the records' own {@code elementAt} / {@code elementText}
 * / {@code toGeneralArray}, which read the field directly. The one reader that must NOT
 * materialize is the device interceptor itself, which takes {@code storage()} instead.
 *
 * <p>
 * Lives in the root package, which depends on nothing, as a plain static hook rather than
 * a reference to any accelerator: the browser playground's build cuts {@code am.ik.gpu}
 * out by substituting {@code eval/LinalgGpu}, and a reference from here would pull it
 * back in. With no listener installed an access costs one volatile read.
 */
public final class FloatArrayAccessHook {

	private static volatile @Nullable Consumer<Object> writer;

	private static volatile @Nullable Consumer<Object> reader;

	private FloatArrayAccessHook() {
	}

	/**
	 * Installs the listeners every packed float array access is reported to, or removes
	 * them.
	 * @param onWrite given the {@code double[]} or {@code float[]} that is about to be
	 * written; {@code null} to uninstall
	 * @param onRead given the {@code double[]} or {@code float[]} that is about to be
	 * read; {@code null} to uninstall
	 */
	public static void install(@Nullable Consumer<Object> onWrite, @Nullable Consumer<Object> onRead) {
		writer = onWrite;
		reader = onRead;
	}

	/**
	 * Reports that {@code data} -- a packed array's {@code double[]} or {@code float[]}
	 * storage -- is about to be written in place.
	 * @param data the storage that is being written
	 */
	public static void written(Object data) {
		Consumer<Object> hook = writer;
		if (hook != null) {
			hook.accept(data);
		}
	}

	/**
	 * Reports that {@code data} is about to be read on the host.
	 * @param data the storage that is being read
	 */
	public static void read(Object data) {
		Consumer<Object> hook = reader;
		if (hook != null) {
			hook.accept(data);
		}
	}

}
