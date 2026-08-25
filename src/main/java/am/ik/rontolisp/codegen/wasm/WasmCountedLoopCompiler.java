package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Recognizes the counted loop hiding inside a {@code let} + {@code while} sandwich: a
 * binding that starts at a literal integer, is stepped by a literal integer in exactly
 * one statement-position {@code setq} inside a {@code while}, and whose range is bounded
 * by that {@code while}'s own test.
 *
 * <p>
 * That is the shape {@code loop}'s numeric {@code for} head lowers to
 * ({@code .kb/loop-iteration-heads.md}) -- {@code (loop for i from 1 to 10 sum i)}
 * becomes {@code (%block (let* ((i 1) (acc 0)) (while (not (> i 10)) ... (setq i (+ i
 * 1))) acc))} -- and it is also what a hand-written {@code let}/{@code while} loop spells
 * out. Both then get what {@link WasmDotimesCompiler} gives {@code dotimes}: the variable
 * becomes a bare {@code i64} local with NO boxed shadow, registered as a COUNTED
 * {@link WasmIntFusionCompiler.RawLocal}, so a read inside a fused tree is the slot
 * itself and the step is an {@code i64.add}.
 *
 * <p>
 * The eligibility scan IS the soundness proof, and it is the one
 * {@code .kb/wasm-counted-loops.md} writes down for {@code dotimes}, re-established for
 * the more general head: nothing may assign the variable except the one step, nothing may
 * capture it, it may not be special, and the loop's own test must bound every value it
 * can take inside the i31 range -- which is what makes the shadow-free {@code ref.i31}
 * boxing of a read exact.
 */
final class WasmCountedLoopCompiler {

	private WasmCountedLoopCompiler() {
	}

	/**
	 * The largest magnitude a counted variable may reach. A counted read boxes with a
	 * bare {@code ref.i31}, exact only within the i31 signed range, and the value that
	 * FAILS the loop test is observable (by the forms after the loop, and by a
	 * {@code loop}'s {@code finally} clause), so the bound has to cover one step past the
	 * limit.
	 */
	private static final long MAX_MAGNITUDE = (1L << 30) - 1;

	/**
	 * Operators that can write a variable place. The AST reaching a backend has every
	 * user macro already expanded ({@code UserMacroExpander}), so this list is closed:
	 * anything else that assigns does it by expanding into one of these.
	 */
	static final Set<String> MODIFY_OPERATORS = Set.of(LispNames.SETQ, LispNames.PSETQ, LispNames.SETF, LispNames.PSETF,
			LispNames.MULTIPLE_VALUE_SETQ, LispNames.INCF, LispNames.DECF, LispNames.PUSH, LispNames.PUSHNEW,
			LispNames.POP, LispNames.ROTATEF, LispNames.SHIFTF, LispNames.REMF, LispNames.DEFVAR,
			LispNames.DEFPARAMETER, LispNames.DEFCONSTANT);

	/** A binding proven to be a counted loop's induction variable. */
	record Induction(String name, long start) {
	}

	/**
	 * The first binding in this {@code let}'s binding list that is the induction variable
	 * of a {@code while} in its body, or null when there is none.
	 *
	 * <p>
	 * The caller has already established the context conditions the whole unboxing
	 * machinery shares ({@code --dynamic}, async bodies, a top-level closure); this
	 * decides only the per-variable question.
	 */
	@org.jspecify.annotations.Nullable
	static Induction analyze(LispVal bindings, List<LispVal> bodyExprs, Set<String> capturedInLet,
			WasmLispCompiler.Ctx ctx) {
		if (!(bindings instanceof LispCons bindingsCons) || !bindingsCons.isProperList()) {
			return null;
		}
		List<LispVal> bindingList = bindingsCons.toList();
		Set<String> seen = new HashSet<>();
		Set<String> duplicates = new HashSet<>();
		for (LispVal binding : bindingList) {
			if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol name && !seen.add(name.name())) {
				duplicates.add(name.name());
			}
		}
		for (LispVal binding : bindingList) {
			if (!(binding instanceof LispCons pair) || !pair.isProperList()) {
				continue;
			}
			List<LispVal> pairParts = pair.toList();
			if (pairParts.size() != 2 || !(pairParts.get(0) instanceof LispSymbol varSym)
					|| !(pairParts.get(1) instanceof LispInteger start)) {
				continue;
			}
			String name = varSym.name();
			if (varSym.isKeyword() || duplicates.contains(name) || ctx.specialVars.contains(name)
					|| capturedInLet.contains(name) || !fits(start.value())) {
				continue;
			}
			if (isInductionVariable(name, start.value(), bodyExprs)) {
				return new Induction(name, start.value());
			}
		}
		return null;
	}

	/**
	 * Whether the variable is stepped by exactly one {@code (setq VAR (+ VAR LITERAL))}
	 * that sits in the statement list of a {@code while} whose test bounds it.
	 */
	private static boolean isInductionVariable(String name, long start, List<LispVal> bodyExprs) {
		List<LispCons> writers = new ArrayList<>();
		for (LispVal form : bodyExprs) {
			collectWriters(form, name, writers);
		}
		if (writers.size() != 1) {
			// No step at all, or a second assignment that the i64 slot could not
			// represent -- and, either way, one the range proof cannot account for.
			return false;
		}
		LispCons step = writers.get(0);
		List<LispVal> stepParts = step.toList();
		if (stepParts.size() != 3 || !(stepParts.get(0) instanceof LispSymbol setq)
				|| !LispNames.SETQ.equals(setq.name()) || !isVar(stepParts.get(1), name)) {
			return false;
		}
		long delta = stepDelta(stepParts.get(2), name);
		if (delta == 0) {
			return false;
		}
		// The step has to run in STATEMENT position -- a counted variable has no boxed
		// value to hand back -- which being a direct element of a while's body list is.
		List<LispCons> loops = new ArrayList<>();
		for (LispVal form : bodyExprs) {
			collectLoopsStepping(form, step, loops);
		}
		if (loops.size() != 1) {
			return false;
		}
		Long bound = boundAtLoopTop(((LispCons) loops.get(0).cdr()).car(), name, delta > 0, false);
		if (bound == null || !fits(bound)) {
			return false;
		}
		// Every value the variable can take: the initial one, and (at the widest) one
		// step past the last that passed the test. Both have to box exactly as an i31.
		return fits(bound + delta);
	}

	/**
	 * Whether the value boxes exactly as an i31. Spelled as a range rather than through
	 * {@code Math.abs}, which answers a NEGATIVE number for {@code Long.MIN_VALUE} and
	 * would let that literal through.
	 */
	private static boolean fits(long value) {
		return value >= -MAX_MAGNITUDE && value <= MAX_MAGNITUDE;
	}

	/**
	 * The literal amount the value of a {@code setq} of {@code name} adds to it per
	 * iteration -- {@code (+ VAR n)}, {@code (+ n VAR)} or {@code (- VAR n)} -- and 0 for
	 * every other shape, including a zero step (which never terminates and never needed
	 * an unboxed counter).
	 */
	private static long stepDelta(LispVal valueExpr, String name) {
		if (!(valueExpr instanceof LispCons value) || !value.isProperList()) {
			return 0;
		}
		List<LispVal> parts = value.toList();
		if (parts.size() != 3 || !(parts.get(0) instanceof LispSymbol op)) {
			return 0;
		}
		boolean add = LispNames.ADD.equals(op.name());
		if (!add && !LispNames.SUB.equals(op.name())) {
			return 0;
		}
		if (isVar(parts.get(1), name) && parts.get(2) instanceof LispInteger by) {
			long delta = add ? by.value() : -by.value();
			return fits(delta) ? delta : 0;
		}
		// (+ n VAR) commutes; (- n VAR) reflects the variable instead of stepping it.
		if (add && isVar(parts.get(2), name) && parts.get(1) instanceof LispInteger by) {
			return fits(by.value()) ? by.value() : 0;
		}
		return 0;
	}

	private static boolean isVar(LispVal form, String name) {
		return form instanceof LispSymbol sym && name.equals(sym.name());
	}

	/**
	 * Every {@code while} whose body list holds {@code step} ITSELF (by identity). One
	 * means the step is a statement of exactly that loop; anything else -- none, or a
	 * hand-built AST that shares the cons between two loops -- is a shape the range proof
	 * cannot follow.
	 */
	private static void collectLoopsStepping(LispVal form, LispCons step, List<LispCons> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.WHILE.equals(head.name()) && cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			for (int i = 2; i < parts.size(); i++) {
				if (parts.get(i) == step) {
					out.add(cons);
					break;
				}
			}
		}
		LispVal cur = cons;
		while (cur instanceof LispCons cell) {
			collectLoopsStepping(cell.car(), step, out);
			cur = cell.cdr();
		}
	}

	/**
	 * The inclusive extreme the variable is known to hold whenever the loop test passes
	 * -- the largest value for an ascending step, the smallest for a descending one -- or
	 * null when the test proves no such bound.
	 *
	 * <p>
	 * A conjunction is enough: whichever conjunct bounds the variable, failing it ends
	 * the loop before the step runs again. A NEGATED conjunction is not (it is a
	 * disjunction of negations, and a bound from one disjunct proves nothing), so the
	 * recursion into {@code and} only happens on the positive side.
	 */
	@org.jspecify.annotations.Nullable
	private static Long boundAtLoopTop(LispVal test, String name, boolean ascending, boolean negated) {
		if (!(test instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		String op = head.name();
		if ((LispNames.NOT.equals(op) || LispNames.NULL.equals(op)) && parts.size() == 2) {
			return boundAtLoopTop(parts.get(1), name, ascending, !negated);
		}
		if (!negated && LispNames.AND.equals(op)) {
			for (int i = 1; i < parts.size(); i++) {
				Long bound = boundAtLoopTop(parts.get(i), name, ascending, false);
				if (bound != null) {
					return bound;
				}
			}
			return null;
		}
		if (parts.size() != 3) {
			return null;
		}
		// Normalize to "VAR op LITERAL", then apply the pending negation. Negating a
		// comparison is sound because both operands are integers -- the variable by
		// construction, the other by this very test -- so there is no NaN to make an
		// operator and its negation both false.
		String effective;
		long limit;
		if (isVar(parts.get(1), name) && parts.get(2) instanceof LispInteger right) {
			effective = op;
			limit = right.value();
		}
		else if (isVar(parts.get(2), name) && parts.get(1) instanceof LispInteger left) {
			effective = mirror(op);
			limit = left.value();
		}
		else {
			return null;
		}
		if (effective == null || !fits(limit)) {
			return null;
		}
		if (negated) {
			effective = negate(effective);
		}
		if (effective == null) {
			return null;
		}
		if (ascending) {
			return switch (effective) {
				case LispNames.LT -> limit - 1;
				case LispNames.LE -> limit;
				default -> null;
			};
		}
		return switch (effective) {
			case LispNames.GT -> limit + 1;
			case LispNames.GE -> limit;
			default -> null;
		};
	}

	/** The operator with its operands swapped. */
	@org.jspecify.annotations.Nullable
	private static String mirror(String op) {
		return switch (op) {
			case LispNames.LT -> LispNames.GT;
			case LispNames.GT -> LispNames.LT;
			case LispNames.LE -> LispNames.GE;
			case LispNames.GE -> LispNames.LE;
			default -> null;
		};
	}

	/** The operator that holds exactly when this one fails. */
	@org.jspecify.annotations.Nullable
	private static String negate(String op) {
		return switch (op) {
			case LispNames.LT -> LispNames.GE;
			case LispNames.GE -> LispNames.LT;
			case LispNames.GT -> LispNames.LE;
			case LispNames.LE -> LispNames.GT;
			default -> null;
		};
	}

	/**
	 * Emits the binding of a counted variable: the literal start straight into a fresh
	 * i64 slot, with no ordinary local and no boxed shadow behind it.
	 */
	static WasmIntFusionCompiler.RawLocal compileBinding(Induction induction, WasmLispCompiler.Ctx ctx) {
		int slot = ctx.allocI64Temp();
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(induction.start());
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
		return WasmIntFusionCompiler.RawLocal.counted(slot);
	}

	/**
	 * Emits the step of a counted variable: an {@code i64.add} into the slot, with no
	 * overflow check and no re-box, because the loop test bounds the result
	 * ({@link #isInductionVariable}).
	 *
	 * <p>
	 * Reaching this with any other value shape means the eligibility scan and the emitter
	 * disagree -- and a counted variable has no shadow to fall back into -- so it throws
	 * rather than inventing a representation.
	 */
	static void compileStep(String name, LispVal valueExpr, WasmIntFusionCompiler.RawLocal raw,
			WasmLispCompiler.Ctx ctx) {
		long delta = stepDelta(valueExpr, name);
		if (delta == 0) {
			throw new IllegalStateException("a counted loop variable cannot be assigned: " + name);
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(raw.i64Slot());
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(delta);
		ctx.writer.write(Instruction.I64_ADD);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(raw.i64Slot());
	}

	/** Whether any form in the list could assign {@code name}. */
	static boolean assignsName(List<LispVal> forms, String name) {
		for (LispVal form : forms) {
			List<LispCons> writers = new ArrayList<>();
			collectWriters(form, name, writers);
			if (!writers.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Every node in the tree that ASSIGNS {@code name} -- the modify form itself, so a
	 * caller can ask what shape it has and where it sits. Deliberately blind to lexical
	 * scope and to quoting: an inner binding's assignment also lands here, which only
	 * narrows eligibility.
	 */
	private static void collectWriters(LispVal form, String name, List<LispCons> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && cons.isProperList() && MODIFY_OPERATORS.contains(head.name())) {
			for (LispVal place : places(head.name(), cons.toList())) {
				if (writesName(place, name)) {
					out.add(cons);
					break;
				}
			}
		}
		LispVal cur = cons;
		while (cur instanceof LispCons cell) {
			collectWriters(cell.car(), name, out);
			cur = cell.cdr();
		}
	}

	/** The place arguments of a modify form, by operator. */
	private static List<LispVal> places(String op, List<LispVal> parts) {
		List<LispVal> out = new ArrayList<>();
		switch (op) {
			// (setq place value ...) and its parallel/generalized siblings.
			case LispNames.SETQ, LispNames.PSETQ, LispNames.SETF, LispNames.PSETF -> {
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					out.add(parts.get(i));
				}
			}
			// (multiple-value-setq (place...) form)
			case LispNames.MULTIPLE_VALUE_SETQ -> {
				if (parts.size() > 1 && parts.get(1) instanceof LispCons names) {
					out.addAll(names.toList());
				}
			}
			// (push item place) / (pushnew item place key...)
			case LispNames.PUSH, LispNames.PUSHNEW -> {
				if (parts.size() > 2) {
					out.add(parts.get(2));
				}
			}
			// (rotatef place...) -- every argument is a place.
			case LispNames.ROTATEF -> out.addAll(parts.subList(1, parts.size()));
			// (shiftf place... newvalue) -- all but the last.
			case LispNames.SHIFTF -> {
				if (parts.size() > 2) {
					out.addAll(parts.subList(1, parts.size() - 1));
				}
			}
			// (incf place [delta]) / (decf ...) / (pop place) / (remf place indicator)
			// / (defvar name [value]) and its siblings: the first argument.
			default -> {
				if (parts.size() > 1) {
					out.add(parts.get(1));
				}
			}
		}
		return out;
	}

	/**
	 * Whether writing through this place could write the VARIABLE {@code name}.
	 *
	 * <p>
	 * A bare symbol place is the variable itself. A place FORM writes its own sub-place
	 * -- {@code (setf (getf pl k) v)} stores back through {@code pl}, {@code (setf (ldb
	 * spec x) v)} through {@code x}, {@code (setf (aref s i) c)} through {@code s} when
	 * {@code s} turns out to be a string. For the indexed accessors below the sub-place
	 * is a known argument and the rest are pure subscript/key READS, which is what keeps
	 * the overwhelmingly common {@code (dotimes (i 16) (setf (aref buf i) 0))} eligible;
	 * every other accessor is treated as writing anything it mentions.
	 */
	private static boolean writesName(LispVal place, String name) {
		if (place instanceof LispSymbol sym) {
			return name.equals(sym.name());
		}
		if (!(place instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			// Not a place shape this knows how to read: assume it writes anything it
			// mentions (an atom that is not a symbol writes nothing).
			return mentions(place, name);
		}
		List<LispVal> parts = cons.toList();
		int subPlace = switch (head.name()) {
			// (aref a i...) / (svref v i) / (elt s i) / (char s i) / (schar s i) /
			// (row-major-aref a k) / (vec:aref v i) / (fill-pointer v) / (subseq s a b)
			case LispNames.AREF, LispNames.SVREF, LispNames.ELT, LispNames.CHAR, LispNames.SCHAR,
					LispNames.ROW_MAJOR_AREF, LispNames.VEC_QUALIFIED_AREF, LispNames.FILL_POINTER, LispNames.SUBSEQ ->
				1;
			// (the type place)
			case LispNames.THE -> 2;
			// (gethash key table [default]) -- the TABLE is the sub-place.
			case LispNames.GETHASH -> 2;
			// (values place...) -- setf distributes over every one of them.
			case LispNames.VALUES -> -1;
			default -> 0;
		};
		if (subPlace == -1) {
			for (int i = 1; i < parts.size(); i++) {
				if (writesName(parts.get(i), name)) {
					return true;
				}
			}
			return false;
		}
		if (subPlace == 0) {
			// An accessor with no known sub-place: assume anything it mentions.
			return mentions(cons, name);
		}
		return subPlace < parts.size() && writesName(parts.get(subPlace), name);
	}

	/** Whether the symbol occurs anywhere in the form. */
	private static boolean mentions(LispVal form, String name) {
		if (form instanceof LispSymbol sym) {
			return name.equals(sym.name());
		}
		LispVal cur = form;
		while (cur instanceof LispCons cell) {
			if (mentions(cell.car(), name)) {
				return true;
			}
			cur = cell.cdr();
		}
		return cur instanceof LispSymbol tail && name.equals(tail.name());
	}

}
