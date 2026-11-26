package am.ik.rontolisp.runtime;

import sun.misc.Signal;

/**
 * SIGINT as {@code rontolisp:interactive-interrupt} in a compiled program with a handler
 * naming it ({@code .kb/error-handling.md}, "SIGINT"). The host's own SIGINT stands until
 * that handler is established. From then on a SIGINT is a request the establishing thread
 * takes at its next poll; a SIGINT finding the last one still untaken, or that thread
 * ended, ends the process as the host would.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside the program
 * ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoInterruptSignal {

	/** The JVM backend's {@code t}. */
	private static final String T = "T";

	/** The process status SIGINT ends a process with: 128 + its number. */
	private static final int SIGINT_STATUS = 130;

	/** The thread that established the first handler; null before it. */
	// This package cannot import the annotation that would say so (it would travel with
	// the class); nor can the two nil answers below, nil being null when compiled.
	@SuppressWarnings("NullAway")
	private static Thread target;

	/** The thread with a request to take; null while none is pending. */
	@SuppressWarnings("NullAway")
	private static volatile Thread requested;

	private RontoInterruptSignal() {
	}

	/**
	 * Asks each SIGINT to be signalled on the calling thread, the first time only: what a
	 * handler naming the condition does as it is established.
	 */
	public static synchronized void listen() {
		if (target != null) {
			return;
		}
		target = Thread.currentThread();
		try {
			Signal.handle(new Signal("INT"), (signal) -> request());
		}
		catch (IllegalArgumentException ex) {
			// The host keeps SIGINT to itself (-Xrs), so it ends the process as ever.
		}
	}

	private static synchronized void request() {
		if (requested != null || !target.isAlive()) {
			System.exit(SIGINT_STATUS);
		}
		requested = target;
		target.interrupt();
	}

	/**
	 * Takes the request pending for the calling thread, and the interrupt status that
	 * only woke a blocked call: the handlers about to run may block.
	 * @return {@code t} when there was one, else {@code nil}
	 */
	@SuppressWarnings("NullAway")
	public static Object take() {
		if (requested != Thread.currentThread()) {
			return null;
		}
		// The lock waits for request's interrupt, or it would outlive the request taken
		// and wake the next blocked call.
		synchronized (RontoInterruptSignal.class) {
			requested = null;
			Thread.interrupted();
		}
		return T;
	}

	/**
	 * {@code Thread.sleep}, returning early for a request: the poll after it signals.
	 * @param millis the time to sleep
	 * @throws InterruptedException when the thread is interrupted for anything else
	 */
	public static void sleep(long millis) throws InterruptedException {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			if (requested != Thread.currentThread()) {
				throw ex;
			}
		}
	}

	/**
	 * {@code %condition-wait}, returning early for a request with the lock held again:
	 * the poll after it signals, and its {@code was interrupted} error reaches no clause
	 * for {@code error} first.
	 * @param variable the condition variable
	 * @param lock the held mutex handle
	 * @param timeoutNanos as {@link RontoConditionVariable#waitOn}
	 * @return as {@link RontoConditionVariable#waitOn}; {@code nil} for a request
	 */
	@SuppressWarnings("NullAway")
	public static Object waitOn(Object variable, Object lock, Object timeoutNanos) {
		try {
			return RontoConditionVariable.waitOn(variable, lock, timeoutNanos);
		}
		catch (IllegalStateException ex) {
			if (requested != Thread.currentThread()) {
				throw ex;
			}
			return null;
		}
	}

}
