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
				}
			}
		}
		return globals;
	}

}
