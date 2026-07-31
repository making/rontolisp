package am.ik.rontolisp.macro;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects the names of <em>special</em> (dynamically bound) variables in a program.
 * Shared by the interpreter and both compilers so all three agree on which names get
 * dynamic-extent binding under {@code let}/{@code let*} instead of a fresh lexical slot.
 *
 * <p>
 * A name becomes special when Common Lisp would proclaim it special: it is the name of a
 * top-level {@code defvar}/{@code defparameter}/{@code defconstant}, or it appears in a
 * {@code (special ...)} clause of a top-level {@code declaim} or {@code proclaim}. A
 * local {@code (declare (special x))} anywhere inside a form is honored PESSIMISTICALLY:
 * the name becomes special program-wide, not just for that binding form -- the same
 * global treatment a declaim would give it (cl-ppcre's convert phase threads its state
 * through let-bound locally-declared specials, so the lite reading must cover it). The
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
		// The seeded stream specials: `*standard-output*` / `*standard-input*` are
		// special by contract (the stream-argument-less print and read families read
		// them at call time), but registering them unconditionally would force the
		// dynamic-binding machinery onto every program. So each becomes special exactly
		// when the program binds it somewhere -- directly or via
		// `(with-output-to-string (*standard-output*) ...)` /
		// `(with-input-from-string (*standard-input* s) ...)` -- which is also the only
		// case whose behavior differs from the plain-stdio default.
		out.addAll(collectDynamicallyBound(List.of(form),
				Set.of(LispNames.STANDARD_OUTPUT_VAR, LispNames.STANDARD_INPUT_VAR)));
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
		collectLocalDeclares(form, out);
	}

	/**
	 * Returns the subset of {@code specials} that is <em>dynamically bound</em> somewhere
	 * in the program -- appears as a binding name of a {@code let}/{@code let*}
	 * (directly, or inside the expansion of a built-in binding macro such as
	 * {@code do}/{@code
	 * dolist}/{@code loop}/{@code multiple-value-bind}; user macros are already expanded
	 * in the compile-path program this runs on). The JVM compiler gives only these names
	 * a thread-scoped (ThreadLocal) dynamic store; a special never bound keeps its plain
	 * static-field representation, so the common read stays a single {@code getstatic}.
	 * Over-collection is harmless (the bound representation is correct, just slower);
	 * under-collection is a loud compile error in {@code JvmLetCompiler}, never a silent
	 * process-global binding.
	 * @param topLevelExprs the fully macro-expanded top-level forms
	 * @param specials the special-variable names ({@link #collect})
	 * @return the names that are dynamically bound, in first-binding order
	 */
	public static LinkedHashSet<String> collectDynamicallyBound(List<LispVal> topLevelExprs, Set<String> specials) {
		LinkedHashSet<String> bound = new LinkedHashSet<>();
		if (specials.isEmpty()) {
			return bound;
		}
		for (LispVal expr : topLevelExprs) {
			collectBoundForm(expr, specials, bound);
		}
		return bound;
	}

	private static void collectBoundForm(LispVal form, Set<String> specials, Set<String> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			String h = head.name();
			if (LispNames.QUOTE.equals(h)) {
				return;
			}
			if (LispNames.LET.equals(h) || LispNames.LET_STAR.equals(h)) {
				List<LispVal> parts = cons.toList();
				if (parts.size() >= 2) {
					LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
					if (bindings instanceof LispCons bindingsCons) {
						for (LispVal binding : bindingsCons.toList()) {
							if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol name) {
								if (specials.contains(name.name())) {
									out.add(name.name());
								}
								collectBoundForm(pair.cdr(), specials, out);
							}
						}
					}
					for (int i = 2; i < parts.size(); i++) {
						collectBoundForm(parts.get(i), specials, out);
					}
				}
				return;
			}
			// Binding sugar (do/dolist/dotimes/loop/multiple-value-bind/with-*/...)
			// reveals its lets one expansion step at a time; a form the expander
			// rejects (it may validate shapes the compiler checks later) is walked
			// raw instead -- no binding macro both fails to expand AND binds.
			LispVal expansion = null;
			try {
				expansion = LispMacroExpander.expandBuiltinMacro(cons);
			}
			catch (RuntimeException ignored) {
			}
			if (expansion != null && expansion != cons) {
				collectBoundForm(expansion, specials, out);
				return;
			}
		}
		collectBoundForm(cons.car(), specials, out);
		collectBoundForm(cons.cdr(), specials, out);
	}

	/**
	 * Walks the form for local {@code (declare (special ...))} clauses (skipping quoted
	 * data) and records their names -- pessimistically program-wide, see the class
	 * comment.
	 */
	private static void collectLocalDeclares(LispVal form, Set<String> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return;
			}
			if (LispNames.DECLARE.equals(member(head.name()))) {
				List<LispVal> parts = cons.toList();
				for (int i = 1; i < parts.size(); i++) {
					addSpecialClause(parts.get(i), out);
				}
				return;
			}
		}
		collectLocalDeclares(cons.car(), out);
		collectLocalDeclares(cons.cdr(), out);
	}

	/** Strips a package qualifier: {@code pkg::special} matches like {@code special}. */
	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	/**
	 * Adds the names in a {@code (special a b ...)} declaration specifier to the set. The
	 * clause head is matched package-insensitively: {@code special} is not a registered
	 * {@code cl} symbol, so under {@code (in-package p)} the resolver spells it
	 * {@code p::special}.
	 */
	private static void addSpecialClause(LispVal spec, Set<String> out) {
		if (spec instanceof LispCons clause && clause.car() instanceof LispSymbol op
				&& LispNames.SPECIAL.equals(member(op.name()))) {
			List<LispVal> names = clause.toList();
			for (int i = 1; i < names.size(); i++) {
				if (names.get(i) instanceof LispSymbol name) {
					out.add(name.name());
				}
			}
		}
	}

}
