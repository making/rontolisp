package am.ik.rontolisp.runtime;

import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A condition variable over any {@link ReentrantLock}: the waiting half of the
 * {@code bordeaux-threads} shim's {@code condition-wait} / {@code condition-notify} /
 * {@code condition-broadcast}, behind {@code rontolisp::%condition-variable-new},
 * {@code %condition-wait} and {@code %condition-notify} on the interpreter and the JVM
 * backend. The lock is a mutex handle's ({@code rontolisp:make-mutex}), which the
 * variable does not know until a wait names it -- so {@code ReentrantLock.newCondition()}
 * cannot serve, and the variable keeps its own waiter queue under its own monitor.
 *
 * <p>
 * Each waiter enqueues a ticket and waits for it to be signalled: a notify signals the
 * oldest ticket, a broadcast every queued one, so a wakeup goes to a thread that was
 * waiting when it was sent, and a timed-out waiter withdraws its ticket. The lock is
 * released, by every hold the caller has, only once the ticket is queued under the
 * monitor, and taken back after the monitor is left -- so no notify sent between the
 * release and the wait is lost, and a notifier holding the lock never deadlocks against a
 * waiter retaking it.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside a compiled program that
 * uses it ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoConditionVariable {

	/** The JVM backend's {@code t}. */
	private static final String T = "T";

	private final ArrayDeque<boolean[]> waiters = new ArrayDeque<>();

	/**
	 * Waits on this variable, releasing the lock while waiting.
	 * @param lock the lock the caller holds
	 * @param timeoutNanos the longest wait in nanoseconds, or a negative number for no
	 * limit
	 * @return {@code true} when signalled, {@code false} when the timeout elapsed first
	 * @throws InterruptedException when the waiting thread is interrupted (the lock is
	 * held again)
	 */
	public boolean await(ReentrantLock lock, long timeoutNanos) throws InterruptedException {
		if (!lock.isHeldByCurrentThread()) {
			throw new IllegalStateException("CONDITION-WAIT: the lock is not held by this thread");
		}
		int holds = lock.getHoldCount();
		boolean[] ticket = new boolean[1];
		try {
			synchronized (this) {
				this.waiters.addLast(ticket);
				for (int i = 0; i < holds; i++) {
					lock.unlock();
				}
				long deadline = System.nanoTime() + timeoutNanos;
				try {
					while (!ticket[0]) {
						if (timeoutNanos < 0) {
							wait();
						}
						else {
							long remaining = deadline - System.nanoTime();
							if (remaining <= 0) {
								return false;
							}
							wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
						}
					}
					return true;
				}
				finally {
					if (!ticket[0]) {
						this.waiters.remove(ticket);
					}
				}
			}
		}
		finally {
			for (int i = 0; i < holds; i++) {
				lock.lock();
			}
		}
	}

	/**
	 * Wakes the oldest waiter, or every waiter.
	 * @param all whether to wake every waiter
	 */
	public synchronized void signal(boolean all) {
		do {
			boolean[] ticket = this.waiters.pollFirst();
			if (ticket == null) {
				break;
			}
			ticket[0] = true;
		}
		while (all);
		notifyAll();
	}

	/**
	 * {@code %condition-variable-new} on the JVM backend.
	 * @return a fresh condition variable
	 */
	public static Object create() {
		return new RontoConditionVariable();
	}

	/**
	 * {@code %condition-wait} on the JVM backend, whose mutex handle IS the
	 * {@link ReentrantLock}.
	 * @param variable the condition variable
	 * @param lock the held mutex handle
	 * @param timeoutNanos a {@code Long} count of nanoseconds, or {@code null} for no
	 * limit (a bignum waits without one too)
	 * @return {@code t} when signalled, {@code nil} on timeout
	 */
	// nil is null in the compiled representation; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static Object waitOn(Object variable, Object lock, Object timeoutNanos) {
		long nanos = timeoutNanos instanceof Long count ? Math.max(0L, count) : -1L;
		try {
			return ((RontoConditionVariable) variable).await((ReentrantLock) lock, nanos) ? T : null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("CONDITION-WAIT: interrupted while waiting");
		}
	}

	/**
	 * {@code %condition-notify} on the JVM backend.
	 * @param variable the condition variable
	 * @param all {@code nil} to wake the oldest waiter, anything else to wake every one
	 * @return {@code nil}
	 */
	// nil is null in the compiled representation; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static Object wake(Object variable, Object all) {
		((RontoConditionVariable) variable).signal(all != null);
		return null;
	}

}
