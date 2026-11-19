package am.ik.rontolisp.runtime;

import java.util.concurrent.locks.ReentrantLock;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link RontoConditionVariable}'s wait: the page has one
 * thread, so nothing runs while it waits and nothing can notify it. A wait with a limit
 * is its time, answering that the limit passed; a wait with none would never end and is
 * refused in words instead -- what the WASM backends' {@code condition-wait} does for the
 * same reason ({@code .kb/mutexes.md}). Compiled only under the {@code web} Maven
 * profile.
 */
@TargetClass(RontoConditionVariable.class)
final class Target_RontoConditionVariable {

	@Substitute
	public boolean await(ReentrantLock lock, long timeoutNanos) {
		if (!lock.isHeldByCurrentThread()) {
			throw new IllegalStateException("CONDITION-WAIT: the lock is not held by this thread");
		}
		if (timeoutNanos < 0) {
			throw new IllegalStateException("CONDITION-WAIT: this wait would never end -- the browser playground "
					+ "runs one thread, so nothing runs while it waits and nothing can notify it");
		}
		long deadline = System.nanoTime() + timeoutNanos;
		while (System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		return false;
	}

}
