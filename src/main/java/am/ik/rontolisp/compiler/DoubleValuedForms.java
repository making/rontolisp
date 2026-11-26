package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Which forms are KNOWN, at compile time, to answer a double-float -- the
 * {@link StringValuedForms} twin, and used for the same reason: to keep a generic runtime
 * out of a module that has no use for it.
 *
 * <p>
 * The generic printer is the consumer. A {@code (princ x)} whose argument the compiler
 * cannot type has to reach the value dispatch ({@code _princ_val}), and that one function
 * is the root of nearly every other printer in the runtime -- conses, ratios, characters,
 * arrays, instances, the character-vector normalizer. On the WASM GC backend printing one
 * float used to cost 3.7 KB, of which the float printer itself was 379 bytes: the rest
 * was reachability, not rendering.
 *
 * <p>
 * The set is CONSERVATIVE, and deliberately narrower than the backends' own
 * {@code hasDoubleLiteral} float-path predicate (which recurses into arbitrary subforms):
 * an IMMEDIATE literal double argument of a float-contagious operator. Every form this
 * answers true for is therefore one the backends compile onto the unboxed f64 path, whose
 * result is a boxed double whatever the other operands turn out to be -- except in a
 * program that may observe a complex, where an operand a variable or a call produces may
 * be one and the path answers the complex instead
 * ({@link ComplexCapability#mayYieldComplex}). Answering true for something that can also
 * answer another type would print it as a float, so a new entry is only earned by
 * checking each backend's emission for that operator.
 */
public final class DoubleValuedForms {

	/**
	 * Operators whose result is a float as soon as one argument is (CL's float
	 * contagion), and which both compile backends lower to the f64 path on a literal
	 * double argument.
	 */
	private static final Set<String> FLOAT_CONTAGIOUS = Set.of(LispNames.ADD, LispNames.SUB, LispNames.MUL,
			LispNames.DIV);

	private DoubleValuedForms() {
	}

	/**
	 * Whether {@code form} evaluates to a double-float on every backend.
	 * @param form the argument form
	 * @param mayObserveComplex whether the program may observe a complex
	 * ({@link ComplexCapability#mayObserveComplex})
	 * @return true when the value needs no run-time type dispatch to print
	 */
	public static boolean certainlyDouble(LispVal form, boolean mayObserveComplex) {
		if (form instanceof LispDouble) {
			return true;
		}
		if (!(form instanceof LispCons call) || !call.isProperList() || !(call.car() instanceof LispSymbol head)
				|| !FLOAT_CONTAGIOUS.contains(head.name())) {
			return false;
		}
		List<LispVal> parts = call.toList();
		// A complex operand answers a complex, not a double -- even beside a literal
		// double argument -- so the form is not certainly double (the same gate the
		// JVM backend steers its complex arithmetic on). EVERY operand is scanned
		// before the literal double may decide: one interleaved pass returned true
		// for (+ 3d0 #c(1d0 2d0)) on the double it met FIRST, never reaching the
		// complex, and the WASM printer's ref.cast to TYPE_FLOAT then trapped on the
		// complex struct instead of printing #C(4.0 2.0). A program that may observe a
		// complex can hand one in through a variable or a call just the same.
		for (int i = 1; i < parts.size(); i++) {
			if (am.ik.rontolisp.macro.LispMacroExpander.containsComplex(parts.get(i))
					|| mayObserveComplex && ComplexCapability.mayYieldComplex(parts.get(i), name -> false)) {
				return false;
			}
		}
		for (int i = 1; i < parts.size(); i++) {
			if (parts.get(i) instanceof LispDouble) {
				return true;
			}
		}
		return false;
	}

}
