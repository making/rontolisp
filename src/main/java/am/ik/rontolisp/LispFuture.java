package am.ik.rontolisp;

import java.util.concurrent.CompletableFuture;

/**
 * A future value: an asynchronous computation that settles exactly once with a Lisp value
 * or with an error (a signaled condition). Futures are produced by calling an
 * {@code rontolisp:async-defun}/{@code async-lambda} function or an asynchronous built-in
 * such as {@code rontolisp:fetch}; {@code rontolisp:await} suspends the current
 * asynchronous function until the future settles (re-signaling a stored error), and
 * {@code rontolisp:futurep} tests for this type. The future itself is opaque: it has no
 * reader syntax and prints as {@code #&lt;FUTURE&gt;}.
 *
 * <p>
 * This class carries the interpreter's representation (the JVM compiler represents a
 * future as a bare {@link CompletableFuture}, and the WASM backends use their own value
 * structs). Error propagation rides {@link CompletableFuture}'s exceptional completion:
 * the eval layer completes the future exceptionally with its condition-carrying runtime
 * exception and unwraps it again at await.
 */
public final class LispFuture implements LispVal {

	private final CompletableFuture<LispVal> future;

	private LispFuture(CompletableFuture<LispVal> future) {
		this.future = future;
	}

	/**
	 * Wraps an asynchronous computation.
	 * @param future the computation; its result is the value awaiting yields
	 * @return the future value
	 */
	public static LispFuture of(CompletableFuture<LispVal> future) {
		return new LispFuture(future);
	}

	/**
	 * Creates an already-settled future. Awaiting it never suspends.
	 * @param value the settled value
	 * @return the future value
	 */
	public static LispFuture settled(LispVal value) {
		return new LispFuture(CompletableFuture.completedFuture(value));
	}

	/**
	 * Creates an already-failed future. Awaiting it re-signals the error.
	 * @param error the error to re-signal at await (the eval layer's condition-carrying
	 * exception)
	 * @return the future value
	 */
	public static LispFuture failed(RuntimeException error) {
		return new LispFuture(CompletableFuture.failedFuture(error));
	}

	/**
	 * Returns the backing computation.
	 * @return the future this value wraps
	 */
	public CompletableFuture<LispVal> future() {
		return this.future;
	}

	@Override
	public String print() {
		return "#<FUTURE>";
	}

}
