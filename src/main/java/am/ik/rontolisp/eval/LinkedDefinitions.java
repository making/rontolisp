package am.ik.rontolisp.eval;

import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * What the classes a FRAGMENT links against already define -- the host class of a Clojure
 * program that lowers as it runs and the fragments before it
 * ({@code .kb/jvm-run-time-lowering.md}): their functions, globals and classes by
 * canonical name. A fragment's front end splices only the library definitions none of
 * them holds, since one they hold is called there, and compiles its call sites onto the
 * stream shapes the host carries.
 *
 * @param functions the functions they define (a generic function's dispatcher among them)
 * @param globals the global variables they define
 * @param classes the classes their registries know
 */
public record LinkedDefinitions(Set<String> functions, Set<String> globals, Set<String> classes) {

	public LinkedDefinitions {
		functions = Set.copyOf(functions);
		globals = Set.copyOf(globals);
		classes = Set.copyOf(classes);
	}

	/**
	 * Whether a top-level definition defines what a class before already holds: a
	 * {@code defun} or {@code defgeneric} of a linked function, a {@code defmethod} of a
	 * linked generic (its generic is closed where it was compiled), a
	 * {@code defvar}/{@code defparameter}/{@code defconstant} of a linked global, a
	 * {@code defclass}/{@code define-condition} of a linked class.
	 * @param form a top-level form
	 * @return whether the form defines a linked name
	 */
	public boolean defines(LispVal form) {
		if (!(form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name)) {
			return false;
		}
		return switch (head.name()) {
			case LispNames.DEFUN, LispNames.DEFGENERIC, LispNames.DEFMETHOD -> this.functions.contains(name.name());
			case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> this.globals.contains(name.name());
			case LispNames.DEFCLASS, LispNames.DEFINE_CONDITION -> this.classes.contains(name.name());
			default -> false;
		};
	}

}
