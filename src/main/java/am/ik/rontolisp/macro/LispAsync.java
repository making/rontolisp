package am.ik.rontolisp.macro;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

import java.util.List;

/**
 * Shared placement rules for {@code rontolisp:await}: it is legal lexically inside an
 * {@code rontolisp:async-defun}/{@code async-lambda} body and at top level (the top level
 * is implicitly asynchronous), and illegal anywhere else -- in particular inside a plain
 * {@code defun}/{@code lambda}/{@code flet}/{@code labels} function nested in an
 * asynchronous body (the JavaScript rule: an inner plain function does not inherit
 * awaitability). Every backend runs this check on the resolved AST so the diagnosis is
 * identical everywhere; the walker matches the canonical package-qualified spellings the
 * resolver produces.
 */
public final class LispAsync {

	private LispAsync() {
	}

	/**
	 * The diagnostic for an illegally placed {@code rontolisp:await}.
	 */
	public static final String AWAIT_PLACEMENT_MESSAGE = LispNames.AWAIT_QUALIFIED
			+ " is only allowed inside rontolisp:async-defun/async-lambda or at top level";

	/**
	 * Checks a whole program's top-level forms.
	 * @param forms the resolved top-level forms
	 * @throws IllegalArgumentException on an illegally placed await
	 */
	public static void checkTopLevel(List<LispVal> forms) {
		for (LispVal form : forms) {
			check(form, true);
		}
	}

	/**
	 * Checks one form.
	 * @param form the resolved form
	 * @param asyncContext whether the surrounding context may await (an asynchronous body
	 * or the top level)
	 * @throws IllegalArgumentException on an illegally placed await
	 */
	public static void check(LispVal form, boolean asyncContext) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return;
		}
		List<LispVal> parts = cons.toList();
		if (cons.car() instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.QUOTE, LispNames.DEFMACRO, LispNames.MACROLET -> {
					// quoted data is not code; macro bodies are checked on their
					// expansion output, not their definition
					return;
				}
				case LispNames.AWAIT_QUALIFIED -> {
					if (!asyncContext) {
						throw new IllegalArgumentException(AWAIT_PLACEMENT_MESSAGE);
					}
					checkAll(parts, 1, asyncContext);
					return;
				}
				case LispNames.ASYNC_QUALIFIED -> {
					// the wrapper macro; check its async-defun/async-lambda expansion
					check(LispMacroExpander.expandAsync(cons), asyncContext);
					return;
				}
				case LispNames.ASYNC_DEFUN_QUALIFIED -> {
					// the lambda list's default init forms run synchronously at entry
					if (parts.size() > 2) {
						check(parts.get(2), false);
					}
					checkAll(parts, 3, true);
					return;
				}
				case LispNames.ASYNC_LAMBDA_QUALIFIED -> {
					if (parts.size() > 1) {
						check(parts.get(1), false);
					}
					checkAll(parts, 2, true);
					return;
				}
				case LispNames.ASYNC_RUN_QUALIFIED -> {
					// the thunk handed to the async primitive IS the asynchronous body
					// (this is what the async-defun/async-lambda lowering synthesizes),
					// so a lambda argument's body is walked in async context directly --
					// the plain LAMBDA reset below must not apply to it
					for (int i = 1; i < parts.size(); i++) {
						LispVal arg = parts.get(i);
						if (arg instanceof LispCons argCons && argCons.isProperList()
								&& argCons.car() instanceof LispSymbol head && LispNames.LAMBDA.equals(head.name())) {
							List<LispVal> lambdaParts = argCons.toList();
							if (lambdaParts.size() > 1) {
								check(lambdaParts.get(1), false);
							}
							checkAll(lambdaParts, 2, true);
						}
						else {
							check(arg, true);
						}
					}
					return;
				}
				case LispNames.DEFUN, LispNames.LAMBDA, LispNames.DEFMETHOD -> {
					checkAll(parts, 1, false);
					return;
				}
				case LispNames.FLET, LispNames.LABELS -> {
					// ((name (ll) body...)...) definitions are plain function bodies;
					// the flet body itself stays in the surrounding context
					if (parts.size() > 1 && parts.get(1) instanceof LispCons defs && defs.isProperList()) {
						for (LispVal def : defs.toList()) {
							check(def instanceof LispCons ? def : LispNil.INSTANCE, false);
						}
					}
					checkAll(parts, 2, asyncContext);
					return;
				}
				default -> {
					// fall through to the generic walk
				}
			}
		}
		checkAll(parts, 0, asyncContext);
	}

	private static void checkAll(List<LispVal> parts, int from, boolean asyncContext) {
		for (int i = from; i < parts.size(); i++) {
			check(parts.get(i), asyncContext);
		}
	}

	/**
	 * Lowers every {@code rontolisp:async-defun}/{@code async-lambda} in a program --
	 * top-level and nested -- into the shared {@code %async-run} shape
	 * ({@link LispMacroExpander#expandAsyncDefun(LispCons)}), so a compile pipeline that
	 * implements asynchrony in the one {@code %async-run} primitive (the JVM and
	 * Preview-1 WASM backends) sees only ordinary defuns and lambdas afterwards (Pass 1
	 * collects the defuns unchanged). Run {@link #checkTopLevel(List)} first: the
	 * placement rules read the raw async forms. The {@code --component} backend does not
	 * call this (its state machines compile the raw forms).
	 * @param program the resolved top-level forms
	 * @return the program with every async defining form lowered
	 */
	public static List<LispVal> lowerProgram(List<LispVal> program) {
		List<LispVal> out = new java.util.ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(lowerForm(form));
		}
		return out;
	}

	private static LispVal lowerForm(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		if (cons.car() instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.QUOTE -> {
					return form;
				}
				case LispNames.ASYNC_QUALIFIED -> {
					return lowerForm(LispMacroExpander.expandAsync(cons));
				}
				case LispNames.ASYNC_DEFUN_QUALIFIED -> {
					return lowerForm(LispMacroExpander.expandAsyncDefun(cons));
				}
				case LispNames.ASYNC_LAMBDA_QUALIFIED -> {
					return lowerForm(LispMacroExpander.expandAsyncLambda(cons));
				}
				default -> {
					// fall through to the element walk
				}
			}
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> lowered = new java.util.ArrayList<>(parts.size());
		boolean changed = false;
		for (LispVal part : parts) {
			LispVal l = lowerForm(part);
			changed |= (l != part);
			lowered.add(l);
		}
		if (!changed) {
			return form;
		}
		LispVal rebuilt = LispNil.INSTANCE;
		for (int i = lowered.size() - 1; i >= 0; i--) {
			rebuilt = new LispCons(lowered.get(i), rebuilt);
		}
		return rebuilt;
	}

}
