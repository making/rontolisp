package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

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
 * and over-approximates. Two refinements were tried and rejected on measurement:
 * <ul>
 * <li>judging the ARGUMENT shape ({@code (intern "EX-FN" :pkg)} spells its name,
 * {@code (intern (symbol-name n))} does not) -- it shrank nothing, because every real
 * program reaching here does so through a computed name anyway, and it broke
 * {@code internIntoALiteralPackage} on both backends: the two-argument lowering folds the
 * literal into the qualified symbol before the constant probe can see either half;</li>
 * <li>exempting rontolisp's own generated {@code (intern (symbol-name n))} slot-name
 * normalizer as a known-harmless use -- it is harmless, but it is not what holds the gate
 * open in practice. The measured blocker in a library program is the spliced runtime
 * {@code format} renderer's {@code %fmt-function-designator}, which resolves the
 * {@code ~/name/} directive's target and then genuinely funcalls it.</li>
 * </ul>
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
			if (scan(form)) {
				return true;
			}
		}
		return false;
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
