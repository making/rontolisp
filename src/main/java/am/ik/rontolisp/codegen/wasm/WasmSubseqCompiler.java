package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles {@code subseq} for strings and cons chains: {@code (subseq seq start [end])}.
 *
 * <p>
 * A general array runs through the {@link LispMacroExpander#expandSubseqCompat} rewrite
 * that dispatches on {@link am.ik.rontolisp.LispNames#ARRAYP_INTERNAL} and copies the
 * requested range into a fresh {@code make-array}; the string/list arm remains this
 * class's {@link am.ik.rontolisp.LispNames#SUBSEQ_CORE} lane, which calls the
 * {@code _subseq} runtime helper.
 */
final class WasmSubseqCompiler {

	/**
	 * The bounds report's pieces, interned up front in EH mode
	 * ({@code WasmLispCompiler.compile}): {@code "SUBSEQ: invalid bounds "} and
	 * {@code ", "}, which {@code _subseq_bad} cites, and the string report's
	 * {@code " for string of length "}, whose {@code " for "} and {@code " of length "}
	 * the list and vector reports cut out of it
	 * ({@code WasmStringRuntimeBuilder.emitKindOfLength}).
	 */
	static final String BOUNDS_PREFIX = "\"" + LispNames.SUBSEQ + ": invalid bounds \"";

	static final String BOUNDS_COMMA = "\", \"";

	static final String STRING_LENGTH = "\" for string of length \"";

	private WasmSubseqCompiler() {
	}

	/**
	 * Compiles {@code (%subseq-end start end length)}: the resolved end, or the
	 * {@code vector} bounds report (see {@link LispNames#SUBSEQ_END}) -- signalled
	 * through {@code _subseq_bad} in EH mode, a bare {@code unreachable} outside it.
	 */
	static void compileEnd(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int start = ctx.allocTemp();
		int end = ctx.allocTemp();
		int len = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		set(ctx, start);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		set(ctx, end);
		WasmExprCompiler.compileExpr(args.get(3), ctx);
		set(ctx, len);
		// end = (end == nil) ? len : end
		get(ctx, end);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		get(ctx, len);
		set(ctx, end);
		ctx.writer.write(Instruction.END);
		// start < 0 | end > len | start > end, over the bounds' indices
		// (WasmEmitHelper.emitBoundIndex: -1 for a bound that is no fixnum)
		WasmEmitHelper.emitBoundIndex(ctx.writer, start);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_LT_S);
		WasmEmitHelper.emitBoundIndex(ctx.writer, end);
		getInt(ctx, len);
		ctx.writer.write(Instruction.I32_GT_S);
		ctx.writer.write(Instruction.I32_OR);
		WasmEmitHelper.emitBoundIndex(ctx.writer, start);
		WasmEmitHelper.emitBoundIndex(ctx.writer, end);
		ctx.writer.write(Instruction.I32_GT_S);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		if (ctx.ehMode) {
			WasmLispCompiler.StringTable.StringEntry forString = ctx.stringTable.addBodyString(STRING_LENGTH);
			WasmStringRuntimeBuilder.emitSubseqBoundsThrow(ctx.writer,
					() -> WasmStringRuntimeBuilder.emitKindOfLength(ctx.writer, forString, "vector"),
					() -> get(ctx, start), () -> get(ctx, end), () -> get(ctx, len));
		}
		else {
			ctx.writer.write(Instruction.UNREACHABLE);
		}
		ctx.writer.write(Instruction.END);
		get(ctx, end);
	}

	/**
	 * Compiles {@code (%check-bounds seq start end)} (see
	 * {@link LispNames#CHECK_BOUNDS_INTERNAL}): a call of the module's one
	 * {@code _ck_bounds}, which answers nil or refuses the range
	 * ({@code WasmStringRuntimeBuilder.buildCheckBoundsBody}).
	 */
	static void compileCheckBounds(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		for (int i = 1; i <= 3; i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_CK_BOUNDS);
	}

	private static void get(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void set(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	// The fixnum in a (ref null eq) slot, as an i32.
	private static void getInt(WasmLispCompiler.Ctx ctx, int slot) {
		get(ctx, slot);
		WasmEmitHelper.castI31GetS(ctx);
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal rewritten = LispMacroExpander.expandSubseqCompat(cons, true,
				ctx.functions.containsKey(LispNames.SUBSEQ_RUNTIME));
		if (rewritten != null) {
			WasmExprCompiler.compileExpr(rewritten, ctx);
			return;
		}
		List<LispVal> args = cons.toList();
		// _subseq_str answers a MUTABLE character vector for a string input in either
		// representation (a copy-seq/subseq result has a writable identity, .todo/559
		// step 2); a cons chain passes through the byte-level _subseq unchanged.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		if (args.size() >= 4) {
			WasmExprCompiler.compileExpr(args.get(3), ctx);
		}
		else {
			// No end: pass nil so the helper defaults to the content length.
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		// One of the three charvec CONSTRUCTORS: the scan that answers
		// Ctx.charvecPossible has to have seen this site, or the boundary normalization
		// it turned off would silently hand a character vector to a host
		// (.kb/wasm-gc-strings.md). Loud here rather than wrong there.
		WasmEmitHelper.requireCharvecPossible(ctx, "subseq");
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_SUBSEQ_STR);
	}

}
