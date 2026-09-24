package am.ik.rontolisp.cli;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * Runs a piece of work on a thread of a chosen stack size and waits for it, so the work's
 * depth ceiling is the product's rather than whatever the caller's thread was given
 * (.kb/interpreter-stack.md).
 * <p>
 * The outcome is carried back to the caller as if the work had run in place: its result,
 * or what it threw -- an {@link Error} such as {@link StackOverflowError} or a
 * {@link RuntimeException} rethrown as itself, so a caller's {@code catch} and a
 * launcher's uncaught-exception report see the original. The worker inherits the caller's
 * context class loader and inheritable thread locals (the {@link Thread} constructor
 * copies both). An interrupt aimed at the waiting thread is remembered and re-asserted
 * once the work is done, never a reason to stop waiting: the work cannot be abandoned
 * half way.
 */
final class SizedThread {

	/**
	 * The stack of the thread the CLI runs on, and an embedder's compile with it:
	 * comfortably more than the 8 MiB the most generous platform gives the first thread,
	 * since the interpreter's recursion depth is the program's.
	 */
	static final long WORKER_STACK_BYTES = 16L << 20;

	private SizedThread() {
	}

	/**
	 * Runs {@code body} on a new thread and waits for it.
	 * @param <T> the result type
	 * @param name the thread's name
	 * @param stackBytes its stack size
	 * @param body the work
	 * @return what {@code body} returned
	 */
	static <T extends @Nullable Object> T call(String name, long stackBytes, Supplier<T> body) {
		Outcome<T> outcome = new Outcome<>();
		Thread worker = new Thread(null, () -> {
			try {
				outcome.value = body.get();
			}
			catch (Throwable ex) {
				outcome.thrown = ex;
			}
		}, name, stackBytes);
		worker.start();
		boolean interrupted = false;
		while (true) {
			try {
				worker.join();
				break;
			}
			catch (InterruptedException ex) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
		// join is the happens-before edge for both fields.
		switch (outcome.thrown) {
			case null -> {
			}
			case Error error -> throw error;
			case RuntimeException runtime -> throw runtime;
			default -> throw new IllegalStateException(outcome.thrown);
		}
		return outcome.result();
	}

	private static final class Outcome<T extends @Nullable Object> {

		private @Nullable T value;

		private @Nullable Throwable thrown;

		@SuppressWarnings("NullAway")
		T result() {
			return this.value;
		}

	}

}
