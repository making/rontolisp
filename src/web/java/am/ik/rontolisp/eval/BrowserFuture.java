package am.ik.rontolisp.eval;

import java.util.concurrent.CompletableFuture;

/**
 * A {@link CompletableFuture} whose {@link #join()} first runs a <em>settler</em> that
 * blocks (via {@code Atomics.wait} in {@code BrowserHttp.awaitResponse}) until the
 * browser delivers the response and then completes the root future. This is how the
 * single-threaded Web Image runtime awaits a pending promise: nothing can complete a
 * future in the background there, so the blocking must happen inside {@code join()}
 * itself, on the JS side.
 *
 * <p>
 * {@link #newIncompleteFuture()} propagates the settler to derived stages, so the future
 * {@code Environment} builds with {@code thenApply} over
 * {@code HttpSupport.requestAsync}'s result is itself a {@code BrowserFuture}: joining
 * the derived future runs the settler, which completes the root, whose dependents run
 * synchronously on this thread, so {@code super.join()} then returns immediately.
 */
final class BrowserFuture<T> extends CompletableFuture<T> {

	private Runnable settler = () -> {
	};

	BrowserFuture() {
	}

	private BrowserFuture(Runnable settler) {
		this.settler = settler;
	}

	void settler(Runnable settler) {
		this.settler = settler;
	}

	@Override
	public <U> CompletableFuture<U> newIncompleteFuture() {
		return new BrowserFuture<>(this.settler);
	}

	@Override
	public T join() {
		this.settler.run();
		return super.join();
	}

}
