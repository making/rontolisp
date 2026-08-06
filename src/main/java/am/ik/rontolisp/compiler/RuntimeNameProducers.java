package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Whether a program can name a function at run time with a spelling this compile never
 * sees -- the one question behind the compile paths' funcall-dispatch gate
 * ({@code Wasm/JvmLispCompiler.dispatchableFuncIds},
 * {@code .kb/optimize-dead-code-elimination.md}).
 *
 * <p>
 * That gate gives a function a dispatcher case only when the program materializes it as a
 * value or spells its name as a compile-time constant, so {@code --optimize} can drop
 * everything reachable only through the dispatch ladder. It is sound exactly while no
 * operator can conjure a name out of runtime data. This class answers "can one?", and
 * lives in {@code compiler} because BOTH compile backends have to answer it identically:
 * a program that keeps resolving a forged designator on one backend and stops on the
 * other is the divergence one shared answer exists to prevent.
 *
 * <p>
 * <strong>Trigger-shaped, not dataflow-shaped, and deliberately so.</strong> The precise
 * question is whether a symbol out of one of these operators can reach a funcall; getting
 * that wrong is a trap at run time rather than a diagnosis, so the rule stays syntactic
 * and over-approximates. One refinement was tried and rejected on measurement: judging
 * the {@code intern} ARGUMENT shape ({@code (intern "EX-FN" :pkg)} spells its name)
 * shrank nothing -- every real program reaching here computes the name anyway -- and it
 * broke {@code internIntoALiteralPackage} on both backends, because the two-argument
 * lowering folds the literal into the qualified symbol before the constant probe can see
 * either half.
 *
 * <p>
 * <strong>The one exemption is rontolisp's own scaffolding</strong>, and it is matched by
 * IDENTITY rather than by shape: {@link #isCompilerScaffolding} skips the generated
 * slot-name fold defun ({@link LispMacroExpander#slotNameKeyDefun()}), whose
 * {@code (intern (symbol-name n))} re-spells a symbol the program already holds and whose
 * result reaches nothing but a {@code member} test. Structural equality against the
 * builder's own output is what keeps the two from drifting. This was rejected once, on
 * the ground that the real blocker was the format renderer's {@code ~/name/} arm; with
 * that arm now injected only for a program that spells the directive
 * ({@code .kb/format.md}), the fold is what remains, and it is worth 21% of a
 * {@code cl-ppcre} module.
 *
 * <p>
 * The boundary, stated plainly: a program that CALLS {@code %slot-name-key} itself and
 * funcalls the answer forges a name the gate no longer sees. That is the carve-out the
 * gate already documents for a name assembled out of computed strings, and the escape is
 * the same one -- compile with {@code --dynamic}.
 *
 * <p>
 * Run any compile with {@code -Drontolisp.debug.dispatchgate=true} to have the offending
 * operator named.
 */
public final class RuntimeNameProducers {

	private RuntimeNameProducers() {
	}

	/**
	 * Operators that turn data into code: {@code (eval (read))} calls a function whose
	 * name exists only in the input stream.
	 */
	private static final Set<String> EVALUATES_DATA = Set.of(LispNames.EVAL, LispNames.READ, LispNames.READ_FROM_STRING,
			LispNames.LOAD);

	/**
	 * Operators that produce a symbol -- or its function -- from a name, plus
	 * {@code uiop:symbol-call}, which assembles a qualified spelling out of a package
	 * part and a member part so that neither half carries it.
	 */
	private static final Set<String> BUILDS_A_NAME = Set.of(LispNames.INTERN, LispNames.FIND_SYMBOL,
			LispNames.MAKE_SYMBOL, LispNames.SYMBOL_FUNCTION, LispNames.FDEFINITION, LispNames.FBOUNDP,
			LispNames.UIOP_SYMBOL_CALL);

	/**
	 * Whether the program can resolve a function name this compile never sees spelled
	 * out.
	 * @param program the program, after every AST pass the backend runs before codegen
	 * @return true when the funcall-dispatch gate must keep every function dispatchable
	 */
	public static boolean anyNameResolvable(List<LispVal> program) {
		for (LispVal form : program) {
			if (!isCompilerScaffolding(form) && scan(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the top-level form is one the compiler generated and whose name-producing
	 * operator it therefore vouches for. Exactly one form qualifies today -- the
	 * runtime-slot-name fold -- and it is compared against the builder that emits it, so
	 * an edit there cannot leave a stale pattern here.
	 * @param form a top-level form of the program
	 * @return true when the scan must skip it
	 */
	private static boolean isCompilerScaffolding(LispVal form) {
		// Name first, so an ordinary form costs one string compare rather than a rebuild
		// of the canonical definition.
		return form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name && LispNames.SLOT_NAME_KEY.equals(name.name())
				&& LispMacroExpander.slotNameKeyDefun().equals(form);
	}

	/**
	 * Any occurrence of one of the names anywhere in the form -- operator position,
	 * argument position, {@code #'} reference, quoted data. Position does not narrow it:
	 * a {@code #'intern} handed to a higher-order function resolves just as much as a
	 * call does.
	 */
	private static boolean scan(LispVal val) {
		if (val instanceof LispSymbol sym) {
			if (EVALUATES_DATA.contains(sym.name()) || BUILDS_A_NAME.contains(sym.name())) {
				report(sym.name());
				return true;
			}
			return false;
		}
		LispVal cur = val;
		while (cur instanceof LispCons cell) {
			if (scan(cell.car())) {
				return true;
			}
			cur = cell.cdr();
		}
		return cur instanceof LispSymbol && scan(cur);
	}

	/**
	 * Names the operator that turned the gate off. The question a user asks when
	 * {@code --optimize} does not shrink a program is "which operator kept every function
	 * dispatchable?", and only the compiler can answer it.
	 */
	private static void report(String name) {
		if (Boolean.getBoolean("rontolisp.debug.dispatchgate")) {
			System.err.println("[dispatch-gate] every function stays dispatchable because of: " + name);
		}
	}

}
