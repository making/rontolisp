package am.ik.rontolisp.testsupport;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

/**
 * Runs a test's in-process program work on a thread with the stack the CLI hands every
 * program, rather than on the JUnit worker's.
 *
 * <p>
 * The CLI runs the whole command line -- the interpreter AND the compile path's front end
 * and backend -- on a thread of its own ({@code SizedThread.WORKER_STACK_BYTES}, 16 MiB,
 * .kb/interpreter-stack.md). A JUnit worker carries the JVM default, 1 MiB on linux-x64,
 * and how much of it a given depth costs depends on how warm the JIT is: a leg that
 * passes alone overflows when its class runs concurrently and meets the deep program
 * before the recursing pass is compiled. In-process work therefore runs here, so it
 * measures the product's ceiling rather than the harness's (.kb/test-execution.md).
 * {@link CliStackExtension} applies the same to every test method of a class.
 */
public final class CliStack {

	/**
	 * The stack, in bytes.
	 * {@code RontoLispCliTest#theStackOptionIsReadOffTheRawArgumentsAndConsumed} pins it
	 * to the CLI's default.
	 */
	public static final long BYTES = 16L << 20;

	private CliStack() {
	}

	/**
	 * Runs {@code body} on a thread of {@link #BYTES} and waits for it.
	 * @param <T> the result type
	 * @param name the thread's name
	 * @param body the work
	 * @return what {@code body} returned
	 * @throws Exception what {@code body} threw, as itself; an {@link Error} is rethrown
	 * unchanged too, so an assertion failure reports as the caller's own
	 */
	public static <T extends @Nullable Object> T call(String name, Callable<T> body) throws Exception {
		try {
			return callThrowing(name, body::call);
		}
		catch (Exception | Error ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Runs {@code body} on a thread of {@link #BYTES} for at most {@code timeout}. A body
	 * still running then is interrupted and abandoned -- the thread is a daemon, so it
	 * cannot keep the test JVM alive -- and the answer is empty.
	 * @param <T> the result type
	 * @param name the thread's name
	 * @param body the work
	 * @param timeout how long to wait
	 * @return what {@code body} returned, or empty when it did not finish in time
	 * @throws Exception what {@code body} threw, as {@link #call} rethrows it
	 */
	public static <T> Optional<T> callWithin(String name, Callable<T> body, Duration timeout) throws Exception {
		Worker<T> worker = new Worker<>(name, body::call);
		worker.thread.setDaemon(true);
		worker.thread.start();
		worker.thread.join(timeout);
		if (worker.thread.isAlive()) {
			worker.thread.interrupt();
			return Optional.empty();
		}
		try {
			return Optional.of(worker.result());
		}
		catch (Exception | Error ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * The core of {@link #call}, for a body that may throw any {@link Throwable} -- a
	 * JUnit {@code Invocation}.
	 */
	static <T extends @Nullable Object> T callThrowing(String name, Body<T> body) throws Throwable {
		Worker<T> worker = new Worker<>(name, body);
		worker.thread.start();
		worker.thread.join();
		return worker.result();
	}

	/** Work that may throw anything. */
	@FunctionalInterface
	interface Body<T extends @Nullable Object> {

		T run() throws Throwable;

	}

	private static final class Worker<T extends @Nullable Object> {

		private final Thread thread;

		private final AtomicReference<@Nullable T> result = new AtomicReference<>();

		private final AtomicReference<@Nullable Throwable> thrown = new AtomicReference<>();

		Worker(String name, Body<T> body) {
			this.thread = new Thread(null, () -> {
				try {
					this.result.set(body.run());
				}
				catch (Throwable ex) {
					this.thrown.set(ex);
				}
			}, name, BYTES);
		}

		// Called after the thread has ended: join is the happens-before edge.
		@SuppressWarnings("NullAway")
		T result() throws Throwable {
			Throwable ex = this.thrown.get();
			if (ex != null) {
				throw ex;
			}
			return this.result.get();
		}

	}

}
