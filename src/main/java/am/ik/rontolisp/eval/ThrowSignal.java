package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispVal;

/**
 * Internal control-flow signal used by the interpreter to implement {@code throw}. It is
 * thrown by {@code (throw tag result)} and caught by the nearest enclosing
 * {@code (catch tag ...)} whose already-evaluated tag is {@code eq} to the carried one; a
 * non-matching {@code catch} rethrows it, so the innermost matching catcher wins, as in
 * CL. Unlike {@link BlockReturnSignal} the target is DYNAMIC -- the tag is an ordinary
 * runtime value, not a lexically visible block name.
 * <p>
 * It is deliberately NOT a {@link am.ik.rontolisp.eval.LispEvalException}: a
 * {@code throw} unwinding through a {@code handler-case} region must not be caught as a
 * condition. An {@code unwind-protect} still runs its cleanups, because the interpreter
 * implements that with {@code try}/{@code finally}. Like the other signals it is not a
 * user-visible error and suppresses its stack trace.
 */
final class ThrowSignal extends RuntimeException {

	private final transient LispVal tag;

	private final transient LispVal value;

	ThrowSignal(LispVal tag, LispVal value) {
		super(null, null, false, false);
		this.tag = tag;
		this.value = value;
	}

	LispVal tag() {
		return this.tag;
	}

	LispVal value() {
		return this.value;
	}

}
