package am.ik.rontolisp;

import java.util.concurrent.CompletableFuture;

/**
 * A thread handle: the value {@code rontolisp:make-thread} returns on the interpreter,
 * carrying the spawned (virtual) thread and the future its body's value settles.
 * {@code rontolisp:join-thread} waits on the result, {@code rontolisp:thread-alive-p} and
 * {@code rontolisp:destroy-thread} act on the thread, and {@code rontolisp:threadp} tests
 * for this type. The handle is OPAQUE, like a mutex handle: the JVM backend hands out a
 * marker-headed {@code Object[]} instead, and the WASM backends have no thread values at
 * all (the {@code bordeaux-threads} shim's spawn entry points signal there), so nothing
 * portable may print, compare or do arithmetic on one. It has no reader syntax and prints
 * as {@code #&lt;THREAD&gt;}.
 *
 * @param thread the spawned thread
 * @param result the future settling with the thread function's value, or exceptionally
 * with what it threw
 */
public record LispThread(Thread thread, CompletableFuture<LispVal> result) implements LispVal {

	@Override
	public String print() {
		return "#<THREAD>";
	}

}
