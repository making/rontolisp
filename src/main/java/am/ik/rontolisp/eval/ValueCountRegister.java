package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>
 * Beside the values, each thread holds the exit a compiled statement handed to the frame
 * whose block it leaves, on the way up to it: the statement answers
 * {@link LispEvaluator#EXITED} rather than throwing
 * ({@code .kb/interpreter-compiled-bodies.md}, "Frames, tails and the stack").
 */
final class ValueCountRegister {

	private final Thread owner = Thread.currentThread();

	private LispVal ownerValue = LispNil.INSTANCE;

	private @Nullable BlockTailExit ownerExit;

	/** Another thread's register: its values, then its exit on the way to its frame. */
	private final ThreadLocal<@Nullable Object[]> others = ThreadLocal
		.withInitial(() -> new @Nullable Object[] { LispNil.INSTANCE, null });

	/**
	 * The calling thread's register.
	 * @return nil, the extra values, or the zero-values marker
	 */
	LispVal get() {
		return Thread.currentThread() == this.owner ? this.ownerValue
				: (LispVal) java.util.Objects.requireNonNull(this.others.get()[0]);
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

	/**
	 * Holds the exit a statement hands to the frame whose block it leaves, until the
	 * frame takes it.
	 * @param exit the exit
	 */
	void deferExit(BlockTailExit exit) {
		if (Thread.currentThread() == this.owner) {
			this.ownerExit = exit;
		}
		else {
			this.others.get()[1] = exit;
		}
	}

	/**
	 * The exit a statement handed on, which the frame it aims at takes; null for none.
	 * @return the exit, or null
	 */
	@Nullable BlockTailExit pendingExit() {
		return Thread.currentThread() == this.owner ? this.ownerExit : (BlockTailExit) this.others.get()[1];
	}

	/**
	 * Takes the exit a statement handed on.
	 * @return the exit
	 */
	BlockTailExit takeExit() {
		BlockTailExit exit;
		if (Thread.currentThread() == this.owner) {
			exit = this.ownerExit;
			this.ownerExit = null;
		}
		else {
			@Nullable Object[] slots = this.others.get();
			exit = (BlockTailExit) slots[1];
			slots[1] = null;
		}
		return java.util.Objects.requireNonNull(exit, "no exit handed on");
	}

}
