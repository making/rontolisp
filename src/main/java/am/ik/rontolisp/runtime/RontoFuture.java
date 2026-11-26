package am.ik.rontolisp.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * A future the JVM backend makes, which knows whether the program knows it settled: it
 * was settled before the call that made it returned, or an await has resolved it. An
 * await of one the program does not know settled ends an asynchronous body's eager part
 * even once it has settled, so where the part ends depends on no thread's timing
 * ({@code .kb/async-await.md}, "Cross-backend contract"). Any other
 * {@link CompletableFuture} -- one Java hands over -- is never known settled.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside a compiled program that
 * uses it ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoFuture extends CompletableFuture<Object> {

	private volatile boolean knownSettled;

	/**
	 * A pending future: an asynchronous body's ({@link #settle}) or a timer's.
	 */
	public RontoFuture() {
	}

	/**
	 * A future settled before the program holds it: what a stream write or a pull
	 * stream's read answers.
	 * @param value the settled value
	 * @return the future
	 */
	public static CompletableFuture<Object> settled(Object value) {
		RontoFuture future = new RontoFuture();
		future.knownSettled = true;
		future.complete(value);
		return future;
	}

	/**
	 * A future that settles with a computation Java runs, such as the HTTP client's.
	 * @param stage the computation
	 * @return the future
	 */
	public static CompletableFuture<Object> of(CompletionStage<?> stage) {
		RontoFuture future = new RontoFuture();
		stage.whenComplete((value, failure) -> {
			if (failure != null) {
				future.completeExceptionally(failure);
			}
			else {
				future.complete(value);
			}
		});
		return future;
	}

	/**
	 * Settles an asynchronous body's future on the body's thread. A body whose caller
	 * still waits for the eager-start handoff finished before its first suspension, so
	 * its future is known settled; only this thread counts the latch down, so the test
	 * cannot race.
	 * @param future the body's {@link RontoFuture}
	 * @param value the value or payload it settles with
	 * @param handoff the body's eager-start handoff latch
	 */
	public static void settle(Object future, Object value, Object handoff) {
		RontoFuture settling = (RontoFuture) future;
		if (((CountDownLatch) handoff).getCount() > 0) {
			settling.knownSettled = true;
		}
		settling.complete(value);
	}

	/**
	 * Whether a value is a future the program knows settled.
	 * @param value any value
	 * @return whether an await of it may go on without suspending
	 */
	public static boolean knownSettled(Object value) {
		return value instanceof RontoFuture future && future.knownSettled;
	}

	/**
	 * An await's join: waits for the future, which the program then knows settled, so an
	 * await of it again goes on.
	 * @param future a {@link CompletableFuture}
	 * @return its value
	 */
	public static Object join(Object future) {
		CompletableFuture<?> joined = (CompletableFuture<?>) future;
		try {
			return joined.join();
		}
		finally {
			if (joined instanceof RontoFuture own) {
				own.knownSettled = true;
			}
		}
	}

}
