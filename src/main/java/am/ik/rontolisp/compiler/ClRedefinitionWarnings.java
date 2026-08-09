package am.ik.rontolisp.compiler;

import java.util.Set;

import am.ik.rontolisp.PackageRegistry;

/**
 * The one place that words what happens when a program defines a function on a
 * {@code COMMON-LISP} symbol the compile backends compile INLINE.
 *
 * <p>
 * The interpreter resolves {@code (random 1000)} through the function cell, so a
 * {@code (defun random ...)} wins there. Both compile backends instead recognize the
 * operator in their expression dispatcher and emit the standard one, so the definition
 * never runs -- and until this existed they did it without saying anything, which is the
 * actual defect: the same source computed different answers depending on where it ran,
 * with no diagnostic anywhere. It is not a conformance bug (CLHS 11.1.2.1.2 leaves
 * defining a function on a {@code COMMON-LISP} symbol undefined, and SBCL refuses it
 * outright with a package lock), so the answer is to SAY it rather than to pick a
 * behaviour.
 *
 * <p>
 * <strong>Why the backends are not simply made to honour it</strong> (the re-evaluation
 * trigger): the interpreter's own honoured set is an accident of which names
 * {@code LispEvaluator.evalCons} expands before it consults the environment -- it honours
 * {@code car} but not {@code first}, {@code length} but not {@code nth} -- so "make the
 * compilers agree with the interpreter" means freezing that accident into a hand-kept
 * list on three more dispatchers. Honouring it for every {@code cl} function instead
 * collides with the compile-time fold of pure built-ins
 * ({@code .kb/pure-builtin-fold.md}), with inlining and with the funcall-dispatch gate,
 * and buys a program nothing CL promises. What a user actually loses without a diagnostic
 * is the knowledge that it happened; that is what this restores. If the language ever
 * grows package locks, this is the message to turn into the lock's report.
 *
 * <p>
 * The warning is raised per NAME, and only where an interception really overrode a
 * definition: the dispatchers arm it before their operator switch and disarm it in the
 * default (ordinary call) arm, so a {@code cl} name they do not intercept -- the
 * {@code sleep} of {@code wait.lisp}, the {@code compile} of
 * {@code compile-runtime.lisp}, both deliberate Lisp-source definitions of a standard
 * function -- stays silent.
 */
public final class ClRedefinitionWarnings {

	private ClRedefinitionWarnings() {
	}

	/**
	 * Returns whether a call to {@code name} would use the standard operator even though
	 * the program defines a function of that name -- i.e. whether the dispatcher should
	 * arm the warning before its operator switch.
	 * @param name the canonical (upper-case, unqualified) operator name at the call site
	 * @param userDefunNames every top-level {@code defun} name in the program
	 * @return {@code true} when the name is a {@code cl} function the program redefines
	 */
	public static boolean redefinesClFunction(String name, Set<String> userDefunNames) {
		return userDefunNames.contains(name) && PackageRegistry.isClFunctionName(name);
	}

	/**
	 * The warning line for one overridden redefinition, worded once so both backends say
	 * the same thing. It states what THIS compilation did and nothing about the others:
	 * which backend resolves such a call through the function cell is exactly the
	 * accident described above, so a message claiming "the interpreter honours it" would
	 * be wrong for {@code first} while being right for {@code car}.
	 * @param name the {@code cl} function name the program redefined
	 * @return the complete warning line minus the position prefix, which the caller
	 * prepends (the shape {@code CompileWarnings} callers already use)
	 */
	public static String message(String name) {
		return "warning: (defun " + name + " ...) redefines the COMMON-LISP function " + name + ", but a (" + name
				+ " ...) call site here compiles to the standard operator, so the definition is not what runs"
				+ " (a #'" + name + " function value still names it). Defining a function on a COMMON-LISP symbol"
				+ " has undefined consequences (CLHS 11.1.2.1.2): rename it, or shadow the symbol in a package"
				+ " of your own";
	}

}
