package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;

/**
 * The interpreter's value-count register -- the {@code %mv-spill} channel
 * ({@code .kb/multiple-values.md}) -- one per THREAD, as a native implementation's is.
 * Asynchronous bodies, spawned threads and served requests run evaluator code in parallel
 * with the thread that owns the global environment, and every primitive step writes the
 * register: a single shared field let one thread's step clear or overwrite the values
 * another had just published, so a consumer on the second thread read the wrong count.
 *
 * <p>
 * The thread that created the global environment keeps its register in a plain field --
 * the one-compare fast path every single-threaded program takes; every other thread's
 * lives in a {@link ThreadLocal}.
 */
final class ValueCountRegister {

	private final Thread owner = Thread.currentThread();

	private LispVal ownerValue = LispNil.INSTANCE;

	private final ThreadLocal<LispVal[]> others = ThreadLocal.withInitial(() -> new LispVal[] { LispNil.INSTANCE });

	/**
	 * The calling thread's register.
	 * @return nil, the extra values, or the zero-values marker
	 */
	LispVal get() {
		return Thread.currentThread() == this.owner ? this.ownerValue : this.others.get()[0];
	}

	/**
	 * Writes the calling thread's register.
	 * @param value nil, the extra values, or the zero-values marker
	 */
	void set(LispVal value) {
		if (Thread.currentThread() == this.owner) {
			this.ownerValue = value;
		}
		else {
			this.others.get()[0] = value;
		}
	}

}
