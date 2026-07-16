package am.ik.rontolisp.codegen.wasm;

import java.util.IdentityHashMap;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Counts the {@code rontolisp:await} suspend points a subtree contributes to the
 * ENCLOSING async function's state machine (the {@code --component} backend). The
 * traversal mirrors {@link am.ik.rontolisp.LispAsync#check}: quoted data and the bodies
 * of plain functions ({@code defun}/{@code lambda}/{@code defmethod}, {@code flet}/
 * {@code labels} definitions) contribute nothing (an await there is a placement error
 * caught up front), and a nested {@code rontolisp:async-lambda} compiles its own state
 * machine, so its body's awaits belong to it, not to the enclosing function.
 *
 * <p>
 * The count is what sizes each structure's contiguous state range: emission assigns state
 * numbers in this same order, and the async emitters assert the two agree after every
 * guarded statement (a macro expansion that duplicated an await would corrupt the
 * dispatch silently otherwise).
 */
final class WasmAwaitAnalysis {

	private WasmAwaitAnalysis() {
	}

	/**
	 * Counts the awaits of {@code form} that belong to the enclosing async function.
	 * @param form the resolved form
	 * @return the suspend-point count
	 */
	static int countAwaits(LispVal form) {
		return count(form, new IdentityHashMap<>());
	}

	private static int count(LispVal form, IdentityHashMap<LispVal, Integer> memo) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return 0;
		}
		Integer cached = memo.get(form);
		if (cached != null) {
			return cached;
		}
		int n = 0;
		List<LispVal> parts = cons.toList();
		if (cons.car() instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.QUOTE, LispNames.DEFMACRO, LispNames.MACROLET -> {
					memo.put(form, 0);
					return 0;
				}
				case LispNames.AWAIT_QUALIFIED -> {
					n = 1;
					for (int i = 1; i < parts.size(); i++) {
						n += count(parts.get(i), memo);
					}
					memo.put(form, n);
					return n;
				}
				case LispNames.ASYNC_LAMBDA_QUALIFIED, LispNames.ASYNC_DEFUN_QUALIFIED -> {
					// its own state machine
					memo.put(form, 0);
					return 0;
				}
				case LispNames.DEFUN, LispNames.LAMBDA, LispNames.DEFMETHOD -> {
					memo.put(form, 0);
					return 0;
				}
				case LispNames.FLET, LispNames.LABELS -> {
					// definitions are plain function bodies; only the flet body counts
					for (int i = 2; i < parts.size(); i++) {
						n += count(parts.get(i), memo);
					}
					memo.put(form, n);
					return n;
				}
				default -> {
					// fall through to the generic walk
				}
			}
		}
		for (LispVal part : parts) {
			n += count(part, memo);
		}
		memo.put(form, n);
		return n;
	}

}
