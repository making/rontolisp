package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * An exit whose value form has not run yet: a {@code return}/{@code return-from} in a
 * statement a loop or a {@code tagbody} runs in a frame of its own, aimed at a block the
 * enclosing {@code LispEvaluator.evalCons} frame owns, with nothing between the two that
 * opens a dynamic extent. That frame catches it and continues with the value form as its
 * own tail, so the form runs where the block's value is the frame's
 * ({@code .kb/interpreter-tail-calls.md}). It is not a {@link BlockReturnSignal}, which
 * carries a value every other catcher takes as is; only the owning frame catches this
 * one. Not a user-visible error: no stack trace.
 */
final class BlockTailExit extends RuntimeException {

	private final transient Environment target;

	private final transient LispVal form;

	private final transient Environment env;

	private final transient @Nullable CompiledForm compiled;

	BlockTailExit(Environment target, LispVal form, Environment env) {
		this(target, form, env, null);
	}

	/**
	 * An exit a compiled body makes, its value form compiled.
	 * @param target the owner of the block activation it targets
	 * @param form the value form
	 * @param env the scope the value form runs in
	 * @param compiled the value form compiled for that scope, or null
	 */
	BlockTailExit(Environment target, LispVal form, Environment env, @Nullable CompiledForm compiled) {
		super(null, null, false, false);
		this.target = target;
		this.form = form;
		this.env = env;
		this.compiled = compiled;
	}

	/** The owner of the block activation this exit targets. */
	Environment target() {
		return this.target;
	}

	/** The exit's value form. */
	LispVal form() {
		return this.form;
	}

	/** The scope the value form runs in: the exit site's. */
	Environment env() {
		return this.env;
	}

	/**
	 * The value form compiled ({@code .kb/interpreter-compiled-bodies.md}), which a
	 * compiled frame runs in its place; null for a walk's exit.
	 */
	@Nullable CompiledForm compiled() {
		return this.compiled;
	}

}
