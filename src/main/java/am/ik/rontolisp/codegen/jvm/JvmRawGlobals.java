package am.ik.rontolisp.codegen.jvm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Decides which promoted top-level globals carry the unboxed dual representation -- a raw
 * {@code long} field and an {@code int} flag beside the ordinary {@code _g$} field, which
 * stays the boxed shadow ({@code .kb/jvm-int-fusion.md}).
 *
 * <p>
 * A global is an {@code Object} static field, so {@code (setq s (+ s 1))} boxes the sum
 * and stores it through a GC write barrier every iteration -- the one allocation escape
 * analysis cannot remove, because the value escapes into a static. The same accumulator
 * spelled as a {@code let} local takes the dual representation and allocates nothing.
 * This class closes that gap for the globals whose every read and write goes through the
 * three emissions that know about the representation
 * ({@link JvmExprCompiler#compileSpecialRead}, {@link JvmSetqCompiler} and
 * {@link JvmDefvarCompiler}).
 *
 * <p>
 * Eligibility is deliberately narrow -- a false negative costs the fast path, a false
 * positive costs correctness:
 *
 * <ul>
 * <li>The whole program gate ({@link #enabled}): fusion on (so not
 * {@code --optimize=size}), not {@code --dynamic} (late binding must keep observing
 * redefinition), no {@code eval} runtime (the {@code _genv} mirror holds the BOX, and
 * {@code eval}/{@code load}/the FFI seams can reach a variable by name) and nothing
 * concurrent -- threads, an http handler, async spawn or sockets. The triple is three
 * fields where there was one, and a non-volatile {@code long} may tear: one field is at
 * least always some value that was written.</li>
 * <li>Never dynamically bound: a special that some {@code let} binds keeps its
 * save/restore over the single {@code _g$} field ({@code JvmDynVarRuntimeBuilder}). A
 * {@code defvar} name is special by CL's rule but is only DYNAMICALLY BOUND when a
 * {@code let} names it, which is what disqualifies it here.</li>
 * <li>Not a compiler-internal cell (the multiple-value spill) and not one of the stream
 * specials, whose values are stream designators the print family reads.</li>
 * <li>At least one integer-shaped assignment, and few enough assignment SITES that the
 * per-site dispatch bytes stay bounded -- the same trade {@code JvmIntFusionCompiler}'s
 * {@code MAX_RAW_ASSIGN_SITES} makes for a local.</li>
 * </ul>
 *
 * <p>
 * Everything else about the name keeps working unchanged, because a lexical binding of
 * the same name still wins at every site (the local is resolved before the global) and a
 * non-integer value still lands in the shadow, which is the field it always was.
 */
final class JvmRawGlobals {

	private JvmRawGlobals() {
	}

	/**
	 * Assignment sites past this bound decline the representation: the raw store's
	 * per-site type dispatch is larger than a plain {@code putstatic}, and a generated
	 * straight-line body must not grow for a shape that only pays off in a loop.
	 */
	private static final int MAX_ASSIGN_SITES = 64;

	/**
	 * The names eligible for the unboxed dual representation, in declaration order.
	 * @param program the whole macro-expanded program
	 * @param globals the promoted top-level global names
	 * @param boundSpecials the specials some {@code let} binds dynamically
	 * @param enabled the whole-program gate (see the class comment)
	 * @return the eligible names, empty when the gate is closed
	 */
	static Set<String> collect(List<LispVal> program, Set<String> globals, Set<String> boundSpecials, boolean enabled) {
		LinkedHashSet<String> eligible = new LinkedHashSet<>();
		if (!enabled) {
			return eligible;
		}
		for (String name : globals) {
			if (boundSpecials.contains(name) || reserved(name)) {
				continue;
			}
			int[] shapedAndSites = new int[2];
			for (LispVal form : program) {
				scanAssigns(form, name, shapedAndSites);
			}
			if (shapedAndSites[0] > 0 && shapedAndSites[1] <= MAX_ASSIGN_SITES) {
				eligible.add(name);
			}
		}
		return eligible;
	}

	/**
	 * A global the compiler itself reads or writes through its {@code _g$} field
	 * directly, outside the three representation-aware emissions.
	 */
	private static boolean reserved(String name) {
		return LispNames.MV_SPILL.equals(name) || LispNames.STANDARD_OUTPUT_VAR.equals(name)
				|| LispNames.STANDARD_INPUT_VAR.equals(name) || LispNames.ERROR_OUTPUT_VAR.equals(name);
	}

	/**
	 * Counts assignment sites of {@code name} anywhere in a form (into {@code out[1]}),
	 * integer-shaped ones also into {@code out[0]}. Unlike the {@code let}-local scan
	 * this descends INTO {@code defun} bodies: a global's home is a static field every
	 * method body reaches, so an assignment inside a function is the same assignment.
	 */
	private static void scanAssigns(LispVal form, String name, int[] out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return;
			}
			if (cons.isProperList()) {
				List<LispVal> parts = cons.toList();
				if (LispNames.SETQ.equals(head.name()) || LispNames.SETF.equals(head.name())) {
					for (int i = 1; i + 1 < parts.size(); i += 2) {
						if (parts.get(i) instanceof LispSymbol target && name.equals(target.name())) {
							out[1]++;
							if (integerShaped(parts.get(i + 1))) {
								out[0]++;
							}
						}
					}
				}
				else if ((LispNames.DEFVAR.equals(head.name()) || LispNames.DEFPARAMETER.equals(head.name())
						|| LispNames.DEFCONSTANT.equals(head.name())) && parts.size() > 2
						&& parts.get(1) instanceof LispSymbol target && name.equals(target.name())) {
					out[1]++;
				}
			}
		}
		LispVal cur = cons;
		while (cur instanceof LispCons cell) {
			scanAssigns(cell.car(), name, out);
			cur = cell.cdr();
		}
	}

	/**
	 * Whether an assigned value can land in the raw slot: an integer literal or an
	 * integer-operation tree that is not float-contaminated. A value that never fills the
	 * raw slot would make every read pay {@code _ubRead} for nothing.
	 */
	private static boolean integerShaped(LispVal expr) {
		if (expr instanceof LispInteger) {
			return true;
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		// Syntactic only, no scope: this is a program-wide pre-pass, so a declared
		// float lexical is invisible here. A miss is harmless -- the store site
		// dispatches on the RESULT's runtime type, and a Double lands in the shadow.
		if (JvmLispCompiler.containsDoubleLiteral(cons)) {
			return false;
		}
		return switch (head.name()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.MOD, LispNames.REM, LispNames.LOGAND,
					LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.ONE_PLUS,
					LispNames.ONE_MINUS, LispNames.LDB, LispNames.AREF ->
				true;
			default -> false;
		};
	}

}
