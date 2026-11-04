package am.ik.rontolisp.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Compare-and-swap on a compiled cons, an {@code Object[]} of car and cdr: the JVM
 * backend's {@code rontolisp::%cons-cas-car} and {@code %cons-cas-cdr}, behind
 * {@code atomics:cas}. The comparison is by reference; {@code eq} over a value is the
 * caller's, who passes the object it read.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside a compiled program that
 * uses it ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoConsCas {

	/** The JVM backend's {@code t}. */
	private static final String T = "T";

	private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);

	private RontoConsCas() {
	}

	/**
	 * {@code %cons-cas-car} on the JVM backend.
	 * @param cell the cons
	 * @param expected the object the car must be
	 * @param value the new car
	 * @return {@code t} when the car was replaced, otherwise {@code nil}
	 */
	// nil is null in the compiled representation; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static Object car(Object cell, Object expected, Object value) {
		return SLOT.compareAndSet((Object[]) cell, 0, expected, value) ? T : null;
	}

	/**
	 * {@code %cons-cas-cdr} on the JVM backend.
	 * @param cell the cons
	 * @param expected the object the cdr must be
	 * @param value the new cdr
	 * @return {@code t} when the cdr was replaced, otherwise {@code nil}
	 */
	// nil is null in the compiled representation; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static Object cdr(Object cell, Object expected, Object value) {
		return SLOT.compareAndSet((Object[]) cell, 1, expected, value) ? T : null;
	}

}
