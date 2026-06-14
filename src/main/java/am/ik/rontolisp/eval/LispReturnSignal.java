package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispVal;

/**
 * Internal control-flow signal used by the interpreter to implement {@code return}. It is
 * thrown by a {@code return} form and caught by the nearest enclosing {@code %block}
 * boundary (established by the {@code do}/{@code dolist}/{@code dotimes} loop macros),
 * carrying the value the block should yield. This is not a user-visible error; the stack
 * trace is suppressed because it is used purely for non-local exit.
 */
final class LispReturnSignal extends RuntimeException {

	private final transient LispVal value;

	LispReturnSignal(LispVal value) {
		super(null, null, false, false);
		this.value = value;
	}

	LispVal value() {
		return this.value;
	}

}
