package am.ik.rontolisp;

import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

/**
 * A promise value: an asynchronous computation that eventually settles with a Lisp value
 * (or an error). Promises are created by asynchronous producers such as
 * {@code rontolisp:fetch} and derived with {@code rontolisp:then};
 * {@code rontolisp:await} blocks until the promise settles and returns its value, and
 * {@code rontolisp:promisep} tests for this type. The promise itself is opaque: it has no
 * reader syntax and prints as {@code #<PROMISE>}.
 *
 * <p>
 * Two shapes share this class: a <em>root</em> promise wraps a {@link CompletableFuture}
 * (the producer's asynchronous result), and a <em>chained</em> promise (from
 * {@code rontolisp:then}) wraps a base value plus a callback. The callback is applied
 * lazily, when the chained promise is first awaited, and the result is memoized so
 * repeated awaits run the callback once; this at-await timing is what all three backends
 * can implement identically (the WASM backend has no event loop to run callbacks on
 * settlement). The evaluator owns the resolution logic because applying the callback
 * needs the evaluator's {@code apply}.
 */
public final class LispPromise implements LispVal {

	private final @Nullable CompletableFuture<LispVal> future;

	private final @Nullable LispVal base;

	private final @Nullable LispVal fn;

	private boolean settled;

	private @Nullable LispVal value;

	private LispPromise(@Nullable CompletableFuture<LispVal> future, @Nullable LispVal base, @Nullable LispVal fn) {
		this.future = future;
		this.base = base;
		this.fn = fn;
	}

	/**
	 * Creates a root promise backed by the given future.
	 * @param future the asynchronous computation; its result is the value
	 * {@code rontolisp:await} returns
	 * @return the promise
	 */
	public static LispPromise root(CompletableFuture<LispVal> future) {
		return new LispPromise(future, null, null);
	}

	/**
	 * Creates a chained promise: awaiting it awaits {@code base} (a promise or a plain
	 * value) and applies {@code fn} to the result.
	 * @param base the value or promise the callback consumes
	 * @param fn the callback (a function value or designator accepted by {@code apply})
	 * @return the derived promise
	 */
	public static LispPromise chain(LispVal base, LispVal fn) {
		return new LispPromise(null, base, fn);
	}

	/**
	 * Returns the backing future of a root promise, or {@code null} for a chained
	 * promise.
	 * @return the future this promise wraps, if any
	 */
	public @Nullable CompletableFuture<LispVal> future() {
		return this.future;
	}

	/**
	 * Returns the base value of a chained promise.
	 * @return the chained base
	 * @throws NullPointerException if this is a root promise
	 */
	public LispVal base() {
		return java.util.Objects.requireNonNull(this.base);
	}

	/**
	 * Returns the callback of a chained promise.
	 * @return the chained callback
	 * @throws NullPointerException if this is a root promise
	 */
	public LispVal fn() {
		return java.util.Objects.requireNonNull(this.fn);
	}

	/**
	 * Returns whether a chained promise has been resolved (its callback has run).
	 * @return {@code true} once {@link #settle(LispVal)} has been called
	 */
	public boolean isSettled() {
		return this.settled;
	}

	/**
	 * Returns the memoized value of a settled chained promise.
	 * @return the settled value
	 * @throws NullPointerException if this promise has not settled
	 */
	public LispVal settledValue() {
		return java.util.Objects.requireNonNull(this.value);
	}

	/**
	 * Memoizes the resolved value of a chained promise so repeated awaits do not re-run
	 * the callback.
	 * @param resolved the resolved value
	 */
	public void settle(LispVal resolved) {
		this.settled = true;
		this.value = resolved;
	}

	@Override
	public String print() {
		// the legacy chain prints like the future it resolves through, so the opaque
		// label is uniform across backends during the promise-to-future transition
		return "#<FUTURE>";
	}

}
