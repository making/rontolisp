package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles {@code (dotimes (var count [result]) body...)}.
 *
 * <p>
 * The general shape goes through {@link LispMacroExpander#expandDotimes}: a {@code let}
 * binding the variable to 0 and the count to a temporary, a {@code while} over
 * {@code (< var limit)} and a {@code (setq var (+ var 1))} step. That lowering carries a
 * BOXED induction variable -- a generic {@code <} call per iteration whose {@code t}/nil
 * answer is immediately tested for nullness, and a re-box of the counter per step.
 *
 * <p>
 * When the count is a LITERAL non-negative integer below the i31 ceiling the whole loop
 * is decidable at compile time: the counter starts at 0, only ever grows by one, and
 * never leaves the fixnum range -- so it is compiled as a bare {@code i64} local with an
 * {@code i64.ge_s} exit test and an {@code i64.add} step, registered as a COUNTED
 * {@link WasmIntFusionCompiler.RawLocal} so that reads inside the body resolve to the
 * slot itself (raw inside a fused tree, a bare {@code ref.i31} outside it).
 *
 * <p>
 * Unlike the dual-representation locals it borrows the registration from
 * ({@code .kb/wasm-unboxed-locals.md}) this is not a speed-for-size trade and is
 * therefore NOT gated on {@code --optimize=size}: there is no boxed shadow, no per-leaf
 * guard and no duplicated generic fallback to pay for, so the counted loop is strictly
 * smaller AND strictly faster than the expansion it replaces.
 *
 * <p>
 * The eligibility scan is what keeps the shadow-free representation sound: it refuses the
 * shape whenever anything in the loop could write the variable, capture it or observe it
 * through a channel the slot is not on. See {@code .kb/wasm-counted-loops.md}.
 */
final class WasmDotimesCompiler {

	private WasmDotimesCompiler() {
	}

	/**
	 * The largest literal count a counted loop accepts. The counter is boxed with a bare
	 * {@code ref.i31}, which is only exact within the i31 signed range, and the RESULT
	 * form sees the counter holding the count itself -- so the count, not just the last
	 * iteration's index, has to fit.
	 */
	private static final long MAX_COUNT = (1L << 30) - 1;

	/**
	 * Operators that can write a variable place. The AST reaching a backend has every
	 * user macro already expanded ({@code UserMacroExpander}), so this list is closed:
	 * anything else that assigns does it by expanding into one of these.
	 */
	private static final Set<String> MODIFY_OPERATORS = Set.of(LispNames.SETQ, LispNames.PSETQ, LispNames.SETF,
			LispNames.PSETF, LispNames.MULTIPLE_VALUE_SETQ, LispNames.INCF, LispNames.DECF, LispNames.PUSH,
			LispNames.PUSHNEW, LispNames.POP, LispNames.ROTATEF, LispNames.SHIFTF, LispNames.REMF, LispNames.DEFVAR,
			LispNames.DEFPARAMETER, LispNames.DEFCONSTANT);

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!compileCounted(cons, ctx)) {
			WasmExprCompiler.compileExpr(LispMacroExpander.expandDotimes(cons), ctx);
		}
	}

	/**
	 * Emits the counted loop, or returns false having emitted NOTHING when the form does
	 * not qualify.
	 */
	private static boolean compileCounted(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncResume != null || ctx.dynamic) {
			// An async body's locals belong to the spill machinery, and --dynamic
			// resolves variables through the environment. A top-level counter is fine:
			// the eval mirror writes a global backing store, never a lexical slot
			// (WasmSetqCompiler.mirrorsTopLevelGlobal), and a dotimes counter is a
			// lexical no eval'd form can name.
			return false;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispCons spec) || !spec.isProperList()) {
			return false;
		}
		List<LispVal> specParts = spec.toList();
		if (specParts.size() < 2 || specParts.size() > 3 || !(specParts.get(0) instanceof LispSymbol varSym)
				|| varSym.isKeyword() || !(specParts.get(1) instanceof LispInteger count)) {
			return false;
		}
		if (count.value() < 0 || count.value() > MAX_COUNT) {
			return false;
		}
		String name = varSym.name();
		if (ctx.specialVars.contains(name)) {
			return false;
		}
		List<LispVal> scoped = new ArrayList<>(parts.subList(2, parts.size()));
		if (specParts.size() == 3) {
			scoped.add(specParts.get(2));
		}
		if (ctx.topLevel && FreeVarAnalyzer.createsAClosure(scoped)) {
			// Top level only: the capture test below has blind spots there that the
			// ordinary expansion's boxed slot tolerates and this i64 counter does not
			// (FreeVarAnalyzer.createsAClosure).
			return false;
		}
		if (assignsName(scoped, name)
				|| FreeVarAnalyzer.findCapturedVars(scoped, Set.of(name), ctx.functions.keySet()).contains(name)) {
			// A captured counter needs a cell a nested lambda can read; an assigned one
			// needs somewhere to put a value that is not a fixnum. Either way the
			// ordinary expansion owns the shape.
			return false;
		}

		int slot = ctx.allocI64Temp();
		Map<String, Integer> savedLocals = ctx.locals;
		Map<String, WasmIntFusionCompiler.RawLocal> savedRawLocals = ctx.rawLocals;
		Map<String, WasmIntFusionCompiler.LocalIntLambda> savedLocalLambdas = ctx.localIntLambdas;
		Set<String> savedBoxed = ctx.boxedVars;
		int savedNextI64Local = ctx.nextI64Local;
		// The binding SHADOWS whatever the name meant outside, in every representation
		// the reader compilers consult (WasmExprCompiler.compileSymbolRef order: raw
		// locals, ordinary locals, captures, module globals).
		ctx.locals = new HashMap<>(savedLocals);
		ctx.locals.remove(name);
		Map<String, WasmIntFusionCompiler.RawLocal> rawLocals = new HashMap<>(savedRawLocals);
		rawLocals.put(name, WasmIntFusionCompiler.RawLocal.counted(slot));
		ctx.rawLocals = rawLocals;
		if (savedLocalLambdas.containsKey(name)) {
			ctx.localIntLambdas = new HashMap<>(savedLocalLambdas);
			ctx.localIntLambdas.remove(name);
		}
		if (savedBoxed.contains(name)) {
			ctx.boxedVars = new HashSet<>(savedBoxed);
			ctx.boxedVars.remove(name);
		}

		// The %block boundary dotimes wraps its expansion in: a bare (return v) inside
		// the body exits the whole form carrying v.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		ctx.blockMarkers.push(new WasmLispCompiler.BlockMarker(ctx.wasmCtrlDepth, null, true, false));

		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
		ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth += 2;
		// Exit test: the counter has reached the count. (The ordinary lowering spells
		// this as a generic (< i limit) whose t/nil answer is then tested for null.)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(count.value());
		ctx.writer.write(Instruction.I64_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		for (int i = 2; i < parts.size(); i++) {
			WasmExprCompiler.compileForEffect(parts.get(i), ctx);
		}
		// Step: +1 with no overflow check -- the exit test above bounds the counter by a
		// literal that fits in an i31.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I64_ADD);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.wasmCtrlDepth -= 2;
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block

		// The result form runs with the counter holding the count, like the let/while
		// expansion's post-loop value.
		if (specParts.size() == 3) {
			WasmExprCompiler.compileExpr(specParts.get(2), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.blockMarkers.pop();
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // %block

		ctx.boxedVars = savedBoxed;
		ctx.locals = savedLocals;
		ctx.localIntLambdas = savedLocalLambdas;
		ctx.rawLocals = savedRawLocals;
		ctx.nextI64Local = savedNextI64Local;
		return true;
	}

	/** Whether any form in the list could assign {@code name}. */
	private static boolean assignsName(List<LispVal> forms, String name) {
		for (LispVal form : forms) {
			if (assignsName(form, name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether {@code form} contains, at any depth, an assignment whose target could be
	 * the variable {@code name}. Deliberately blind to lexical scope and to quoting: an
	 * inner binding's assignment also counts, which only narrows eligibility.
	 */
	private static boolean assignsName(LispVal form, String name) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head && cons.isProperList() && MODIFY_OPERATORS.contains(head.name())) {
			for (LispVal place : places(head.name(), cons.toList())) {
				if (writesName(place, name)) {
					return true;
				}
			}
		}
		LispVal cur = cons;
		while (cur instanceof LispCons cell) {
			if (assignsName(cell.car(), name)) {
				return true;
			}
			cur = cell.cdr();
		}
		return false;
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
