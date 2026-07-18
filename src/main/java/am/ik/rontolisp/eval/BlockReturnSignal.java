package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispVal;

/**
 * Internal control-flow signal used by the interpreter to implement a NAMED
 * {@code return-from}. It is thrown by {@code (return-from name value)} and caught by the
 * nearest enclosing {@code (block name ...)} whose name matches (a {@code defun} body is
 * wrapped in a block named after the function, a {@code defmethod} body in one named
 * after its generic). It passes through the internal {@code %block} loop boundaries
 * uncaught -- that transparency is what lets a named return exit a function from inside a
 * {@code do}/{@code loop}. Like {@link LispReturnSignal}, it is not a user-visible error
 * and suppresses its stack trace.
 */
final class BlockReturnSignal extends RuntimeException {

	private final transient String name;

	private final transient LispVal value;

	BlockReturnSignal(String name, LispVal value) {
		super(null, null, false, false);
		this.name = name;
		this.value = value;
	}

	String name() {
		return this.name;
	}

	LispVal value() {
		return this.value;
	}

}
