package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Whether a program may observe a complex value at run time: the one gate both compiled
 * backends read before emitting anything that tests a value for one. A complex exists
 * only through a {@code #C} literal, a {@code complex}/{@code conjugate} call, a real
 * argument leaving the real domain of {@code sqrt}, {@code cis}, the inverse hyperbolics
 * or a call {@link LispMacroExpander#escapesToComplex} cannot rule out, or a designator
 * of one of the constructors -- so a program that spells none of them compiles exactly as
 * if complex numbers did not exist (`.kb/jvm-complex.md`, `.kb/wasm-complex.md`). The JVM
 * reads it as its {@code _c*} group gate, the WASM backend as the gate of the holder arms
 * in its generic arithmetic and the complex block.
 */
public final class ComplexCapability {

	/** The operators whose result is complex only when an operand is. */
	private static final Set<String> ARITHMETIC = Set.of(LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV,
			LispNames.MOD, LispNames.REM);

	private ComplexCapability() {
	}

	/**
	 * Whether the program may observe a complex value. Over-approximating costs arms a
	 * run never takes; the JVM's under-prediction net is a recompile with its group
	 * forced on.
	 * @param program the program, after the compile-path front end
	 * @param closRegistry the condition registry (whose reports are scanned too)
	 * @return whether a complex value can exist while the program runs
	 */
	public static boolean mayObserveComplex(List<LispVal> program, ClosRegistry closRegistry) {
		return LispMacroExpander.mayCreateComplex(program, closRegistry)
				|| LispMacroExpander.mayEscapeToComplex(program, closRegistry) || mentions(program, LispNames.SQRT)
				|| mentions(program, LispNames.CIS) || mentions(program, LispNames.ASINH)
				|| mentions(program, LispNames.ACOSH) || mentions(program, LispNames.ATANH)
				|| designates(program, closRegistry, LispNames.COMPLEX)
				|| designates(program, closRegistry, LispNames.CONJUGATE)
				|| designates(program, closRegistry, LispNames.PHASE);
	}

	/**
	 * Whether evaluating an operand form may produce a complex its own text does not
	 * spell, in a program {@link #mayObserveComplex} admits: a variable or a call may, a
	 * constant may not (a complex constant steers its site at compile time), and an
	 * arithmetic operation may exactly when one of its operands may. The operands a
	 * compiled site tests for a complex at run time are the ones this answers true for.
	 * @param form the operand form
	 * @param knownReal whether a variable is known to hold a real (a float declaration
	 * the JVM backend unboxes on the strength of)
	 * @return whether a site must be ready for the operand to be a complex
	 */
	public static boolean mayYieldComplex(LispVal form, Predicate<String> knownReal) {
		if (ArgumentOrder.isOrderIndependent(form)) {
			return false;
		}
		if (form instanceof LispSymbol sym) {
			return !knownReal.test(sym.name());
		}
		if (form instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
				&& ARITHMETIC.contains(head.name())) {
			LispVal operands = cons.cdr();
			while (operands instanceof LispCons cell) {
				if (mayYieldComplex(cell.car(), knownReal)) {
					return true;
				}
				operands = cell.cdr();
			}
			return false;
		}
		return true;
	}

	// Whether some cons of the program has the symbol as its car: a call, a quote or a
	// #' of the name.
	private static boolean mentions(List<LispVal> program, String name) {
		for (LispVal expr : program) {
			if (mentions(expr, name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentions(LispVal val, String name) {
		while (val instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol sym && name.equals(sym.name())) {
				return true;
			}
			if (mentions(cons.car(), name)) {
				return true;
			}
			val = cons.cdr();
		}
		return false;
	}

	// Whether the program names the built-in as a function designator (#'op or 'op),
	// a condition's :report lambda included: it lives only in the registry, and the
	// error/signal expansions inject it back.
	private static boolean designates(List<LispVal> program, ClosRegistry closRegistry, String op) {
		return program.stream().anyMatch(expr -> BuiltinFunctionWrappers.referencesFunctionDesignator(expr, op))
				|| closRegistry.conditionReports()
					.values()
					.stream()
					.anyMatch(report -> BuiltinFunctionWrappers.referencesFunctionDesignator(report, op));
	}

}
