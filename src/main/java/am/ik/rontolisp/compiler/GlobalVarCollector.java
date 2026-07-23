package am.ik.rontolisp.compiler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Collects the names of top-level global variables in a program. Shared by the JVM and
 * WASM compilers, which give each global its own persistent backing store (a JVM static
 * field / a WASM module-level global) so a {@code defun}/{@code lambda} body can read and
 * assign it. A symbol is a top-level global when it is the name of a top-level
 * {@code defvar}/{@code defparameter}/{@code defconstant}, or the place of a top-level
 * {@code setq}/{@code setf} whose place is a bare symbol (Lisp-2: a top-level assignment
 * targets the global variable namespace).
 */
public final class GlobalVarCollector {

	private GlobalVarCollector() {
	}

	/**
	 * Returns the ordered set of top-level global variable names declared by the given
	 * top-level forms. Order is deterministic (declaration order) so backing-store
	 * indices are stable.
	 * @param topLevelExprs the top-level forms (excluding {@code defun}, which defines a
	 * function, not a variable)
	 * @return the global variable names in declaration order
	 */
	public static LinkedHashSet<String> collect(List<LispVal> topLevelExprs) {
		LinkedHashSet<String> globals = new LinkedHashSet<>();
		for (LispVal expr : topLevelExprs) {
			if (!(expr instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
				continue;
			}
			List<LispVal> parts = cons.toList();
			switch (head.name()) {
				case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
					if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol name) {
						globals.add(name.name());
					}
				}
				case LispNames.SETQ, LispNames.SETF -> {
					// setq/setf take place/value pairs; record each place that is a bare
					// symbol (a symbol place under setf expands to setq).
					for (int i = 1; i + 1 < parts.size(); i += 2) {
						if (parts.get(i) instanceof LispSymbol place && !place.isKeyword()) {
							globals.add(place.name());
						}
					}
				}
				default -> {
					// A defun nested inside a top-level non-defun form (the CL
					// closure-over-let idiom) compiles to (setq name (lambda ...)), so
					// the name needs the same global backing store; call sites then
					// dispatch through the variable (expandCallThroughVariable).
					collectNestedDefunNames(cons, globals);
					// An assignment NESTED in a top-level form -- (print (progn (setq a
					// 10)
					// a)) -- assigns the same top-level variable a head-position setq
					// would,
					// and every backend already lets a later top-level form read it back.
					// Without a global it is backed by a local of the enclosing top-level
					// function instead, which pins the whole top level into that one
					// function: an outlined chunk cannot see another chunk's locals
					// (WasmToplevelEmit, .kb/wasm-function-body-size.md).
					collectNestedAssignedNames(cons, globals);
				}
			}
		}
		return globals;
	}

	private static void collectNestedDefunNames(LispVal form, Set<String> globals) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return;
			}
			if (LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons nameCell
					&& nameCell.car() instanceof LispSymbol name) {
				globals.add(name.name());
			}
		}
		collectNestedDefunNames(cons.car(), globals);
		collectNestedDefunNames(cons.cdr(), globals);
	}

	/**
	 * Records every {@code setq}/{@code setf} bare-symbol place and
	 * {@code defvar}/{@code defparameter}/{@code defconstant} name found at any depth
	 * inside a top-level form, excluding quoted data.
	 * <p>
	 * Deliberately blind to lexical scope: a name that is only ever a {@code let}
	 * variable also gets a backing store it never uses, because every assignment site
	 * resolves a lexical slot before it looks at the global. Over-collecting costs an
	 * unused store; missing a name costs a variable that a later top-level form cannot
	 * read.
	 */
	private static void collectNestedAssignedNames(LispVal form, Set<String> globals) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return;
			}
			List<LispVal> parts = cons.toList();
			switch (head.name()) {
				case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
					if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol name) {
						globals.add(name.name());
					}
				}
				case LispNames.SETQ, LispNames.SETF -> {
					for (int i = 1; i + 1 < parts.size(); i += 2) {
						if (parts.get(i) instanceof LispSymbol place && !place.isKeyword()) {
							globals.add(place.name());
						}
					}
				}
				default -> {
				}
			}
		}
		collectNestedAssignedNames(cons.car(), globals);
		collectNestedAssignedNames(cons.cdr(), globals);
	}

}
