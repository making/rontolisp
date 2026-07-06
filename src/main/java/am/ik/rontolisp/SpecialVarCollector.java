package am.ik.rontolisp;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects the names of <em>special</em> (dynamically bound) variables in a program.
 * Shared by the interpreter and both compilers so all three agree on which names get
 * dynamic-extent binding under {@code let}/{@code let*} instead of a fresh lexical slot.
 *
 * <p>
 * A name becomes special exactly when Common Lisp would proclaim it special: it is the
 * name of a top-level {@code defvar}/{@code defparameter}/{@code defconstant}, or it
 * appears in a {@code (special ...)} clause of a top-level {@code declaim} or
 * {@code proclaim}. (Local {@code (declare (special x))} is not honored -- rare.) The
 * earmuffs convention ({@code *x*}) is a style hint, not the mechanism: a variable is
 * special because it was declared, not because of its name.
 *
 * <p>
 * Runs against the resolved, top-level-flattened program before macro expansion turns
 * {@code declaim}/{@code proclaim} into {@code nil}, alongside the compiler's
 * {@code GlobalVarCollector} (special variables are a subset of the globals it collects;
 * each still gets a global backing store, and dynamic binding is save/restore over that
 * store on the compile path). Lives in the shared AST package because the interpreter --
 * which may not depend on the {@code compiler} package -- needs it too.
 */
public final class SpecialVarCollector {

	private SpecialVarCollector() {
	}

	/**
	 * Returns the set of special-variable names declared by the given top-level forms.
	 * @param topLevelExprs the top-level forms
	 * @return the special variable names in declaration order
	 */
	public static LinkedHashSet<String> collect(List<LispVal> topLevelExprs) {
		LinkedHashSet<String> specials = new LinkedHashSet<>();
		for (LispVal expr : topLevelExprs) {
			collectForm(expr, specials);
		}
		return specials;
	}

	/**
	 * Records any special names declared by a single form (a {@code defvar}-family form
	 * or a {@code declaim}/{@code proclaim} carrying {@code (special ...)} clauses) into
	 * the given set. Used both by {@link #collect(List)} and by the interpreter, which
	 * discovers specials incrementally as it evaluates top-level forms.
	 * @param form the form to inspect
	 * @param out the set to add discovered special names to
	 */
	public static void collectForm(LispVal form, Set<String> out) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return;
		}
		List<LispVal> parts = cons.toList();
		switch (head.name()) {
			case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
				if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol name) {
					out.add(name.name());
				}
			}
			case LispNames.DECLAIM -> {
				// (declaim (special *a* *b*) (type integer *c*) ...): each argument is a
				// declaration specifier; collect the ones headed by `special`.
				for (int i = 1; i < parts.size(); i++) {
					addSpecialClause(parts.get(i), out);
				}
			}
			case LispNames.PROCLAIM -> {
				// (proclaim '(special *x*)): the single argument is a quoted specifier.
				if (parts.size() >= 2 && parts.get(1) instanceof LispCons quoted && quoted.car() instanceof LispSymbol q
						&& LispNames.QUOTE.equals(q.name()) && quoted.cdr() instanceof LispCons rest) {
					addSpecialClause(rest.car(), out);
				}
			}
			default -> {
			}
		}
	}

	/** Adds the names in a {@code (special a b ...)} declaration specifier to the set. */
	private static void addSpecialClause(LispVal spec, Set<String> out) {
		if (spec instanceof LispCons clause && clause.car() instanceof LispSymbol op
				&& LispNames.SPECIAL.equals(op.name())) {
			List<LispVal> names = clause.toList();
			for (int i = 1; i < names.size(); i++) {
				if (names.get(i) instanceof LispSymbol name) {
					out.add(name.name());
				}
			}
		}
	}

}
