package am.ik.rontolisp.eval;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import am.ik.rontolisp.LispFuture;
import am.ik.rontolisp.LispThread;
import am.ik.rontolisp.LispVal;

/**
 * The interpreter's asynchronous execution mechanism -- and deliberately the ONLY
 * LispEvaluator-reachable place that touches threads, so the browser playground can
 * substitute it wholesale (see {@code src/web/java/.../eval/Target_AsyncRuntime.java},
 * where GraalVM Web Image has no thread support and bodies run synchronously to
 * completion instead).
 *
 * <p>
 * {@link #run(Supplier)} implements the cross-backend <em>eager-start</em> contract of
 * {@code rontolisp:async-defun}: the body starts on a virtual thread, but the caller does
 * not resume until the body reaches its first real suspension point (an await of an
 * unsettled future -- which calls {@link #releaseHandoffIfPending()} before blocking) or
 * completes, whichever is first. Output and side effects up to the first suspension are
 * therefore ordered identically on every backend; after it, the body progresses in
 * parallel with the caller (the interpreter's documented concurrency model).
 */
final class AsyncRuntime {

	/** The handoff latch of the asynchronous body running on the current thread. */
	private static final ThreadLocal<CountDownLatch> HANDOFF = new ThreadLocal<>();

	private AsyncRuntime() {
	}

	/**
	 * Runs an asynchronous body: eagerly to its first suspension on the calling thread's
	 * clock, then concurrently on its virtual thread.
	 * @param body the zero-argument body (the {@code %async-run} thunk applied by the
	 * evaluator)
	 * @return a future settling with the body's value, or exceptionally with what it
	 * threw
	 */
	static LispFuture run(Supplier<LispVal> body) {
		CompletableFuture<LispVal> result = new CompletableFuture<>();
		CountDownLatch handoff = new CountDownLatch(1);
		Thread.ofVirtual().start(() -> {
			HANDOFF.set(handoff);
			try {
				result.complete(body.get());
			}
			catch (Throwable ex) {
				result.completeExceptionally(ex);
			}
			finally {
				HANDOFF.remove();
				handoff.countDown();
			}
		});
		try {
			handoff.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new LispEvalException("async body interrupted before its first suspension");
		}
		return LispFuture.of(result);
	}

	/**
	 * Spawns a plain (non-async) virtual thread running the given body:
	 * {@code rontolisp:make-thread}. Unlike {@link #run(Supplier)} there is no
	 * eager-start handoff -- the caller resumes immediately, because a spawned body (a
	 * server accept loop, say) may neither await nor complete. Kept HERE so the browser
	 * playground's wholesale substitution of this class covers thread creation too (Web
	 * Image has no threads; the substituted spawn runs the body synchronously to
	 * completion).
	 * @param body the zero-argument thread function
	 * @return the thread handle carrying the thread and its result future
	 */
	static LispThread spawnThread(Supplier<LispVal> body) {
		CompletableFuture<LispVal> result = new CompletableFuture<>();
		Thread thread = Thread.ofVirtual().start(() -> {
			try {
				result.complete(body.get());
			}
			catch (Throwable ex) {
				result.completeExceptionally(ex);
			}
		});
		return new LispThread(thread, result);
	}

	/**
	 * The calling thread's own EQ-stable handle ({@code rontolisp:current-thread}):
	 * lazily wraps the current {@code Thread} on first ask and caches it, so repeated
	 * calls from one thread return the SAME record and can key an {@code eq} hash table
	 * (dbi's per-thread connection cache). Works for any thread, not only
	 * {@code spawnThread} spawns; the never-completed result future means joining your
	 * own handle blocks forever, which is what joining yourself means anyway. Kept HERE
	 * for the same reason as {@link #spawnThread}: the playground substitutes this class
	 * wholesale.
	 */
	private static final ThreadLocal<LispThread> CURRENT_THREAD_HANDLE = new ThreadLocal<>();

	static LispThread currentThreadHandle() {
		LispThread handle = CURRENT_THREAD_HANDLE.get();
		if (handle == null) {
			handle = new LispThread(Thread.currentThread(), new CompletableFuture<>());
			CURRENT_THREAD_HANDLE.set(handle);
		}
		return handle;
	}

	/**
	 * Starts a timer: the returned future settles to {@code nil} after the given number
	 * of milliseconds ({@code rontolisp:wait-for}). Uses
	 * {@link CompletableFuture#completeOnTimeout} so the delay rides the JDK's shared
	 * delayer thread -- kept HERE (not in Environment) so the browser playground's
	 * wholesale substitution of this class also covers the timer (Web Image has no
	 * threads; the substituted timer settles immediately).
	 * @param millis the delay in milliseconds
	 * @return a future settling to {@code nil} once the delay elapsed
	 */
	static LispFuture timer(long millis) {
		return LispFuture.of(new CompletableFuture<LispVal>().completeOnTimeout(am.ik.rontolisp.LispNil.INSTANCE,
				millis, java.util.concurrent.TimeUnit.MILLISECONDS));
	}

	/**
	 * Releases the caller of the asynchronous body running on this thread, if it is still
	 * waiting for the eager-start handoff. Called by {@code rontolisp:await} before
	 * blocking on an unsettled future (the body's first real suspension point) and
	 * idempotent afterwards. A no-op on threads that are not an asynchronous body.
	 */
	static void releaseHandoffIfPending() {
		CountDownLatch handoff = HANDOFF.get();
		if (handoff != null) {
			handoff.countDown();
		}
	}

}
