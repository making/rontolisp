package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
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
 * through a channel the slot is not on. It lives in {@link WasmCountedLoopCompiler},
 * which applies the same proof to {@code loop}'s numeric {@code for} head. See
 * {@code .kb/wasm-counted-loops.md}.
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
		if (WasmCountedLoopCompiler.assignsName(scoped, name)
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

}
