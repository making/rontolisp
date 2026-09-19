package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code read-from-string}. Points the runtime reader's cursor/end at the string
 * argument's bytes (skipping the surrounding quotes) and calls the embedded
 * {@code _read_expr}, which itself skips leading whitespace. Subject to the same
 * integer/symbol limitation as the rest of the WASM reader; {@code #\} character literals
 * and floats parsed at runtime are out of scope.
 */
final class WasmReadFromStringCompiler {

	private WasmReadFromStringCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		emitSetup(cons, ctx);
		// parse one datum
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
	}

	/**
	 * Compiles {@code (%read-from-string-end s)} -- the stop index
	 * {@code read-from-string} answers as its SECOND value, emitted only by the
	 * multiple-value lowering of a {@code read-from-string} producer. The datum is parsed
	 * and dropped; the answer is how far the reader's cursor moved. The cursor's starting
	 * value rides the operand stack ACROSS the parse call (negated, so the two land as an
	 * addition) rather than in a scratch word, which would cost a memory address and the
	 * layout shift that comes with one.
	 * @param cons the call form
	 * @param ctx the compilation context
	 */
	static void compileEnd(LispCons cons, WasmLispCompiler.Ctx ctx) {
		emitSetup(cons, ctx);
		// -cursor_before
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		loadCursor(ctx);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		ctx.writer.write(Instruction.DROP);
		// CLHS 2.2 / 23.2: read consumes one whitespace byte that terminates the datum --
		// a list and a character literal alike, not only a token -- so advance the
		// cursor past it here (mirroring the frontend lexer's "<=32" byte-level
		// whitespace test) before the delta below is computed. This only moves the
		// cursor used by the SECOND value; the datum above was already parsed and
		// dropped.
		// if (cursor < end) { if (byte_at_cursor <= 32) mem[CURSOR] = cursor + 1 }
		loadCursor(ctx);
		loadEnd(ctx);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		curByte(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(32);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_CURSOR_ADDR);
		loadCursor(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		loadCursor(ctx);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	// Pushes mem[READ_CURSOR_ADDR].
	private static void loadCursor(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_CURSOR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	// Pushes mem[READ_END_ADDR].
	private static void loadEnd(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_END_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	// Pushes the byte at the cursor.
	private static void curByte(WasmLispCompiler.Ctx ctx) {
		loadCursor(ctx);
		ctx.writer.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
	}

	private static void emitSetup(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int val = ctx.allocTemp();
		// A mutable character vector normalizes to a string first.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(val);
		// The string's bytes live on the GC heap, so copy them into the reader input
		// scratch at HEAP_PTR and point the cursor/end there. sp = HEAP_PTR (all i32
		// intermediates stay on the stack / in memory -- ctx temps are ref-typed). The
		// scratch is then RESERVED (HEAP_PTR = sp + totalLen) so symbols interned and
		// strings built during the parse stack above the still-unparsed input.
		// end = sp + _str_to_mem(val, sp) - 1 (before the closing quote)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_END_ADDR);
		loadHeapPtr(ctx);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(val);
		loadHeapPtr(ctx);
		WasmEmitHelper.emitStrToMemCall(ctx.writer);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
		// cursor = sp + 1 (skip the opening quote); sp is still HEAP_PTR (_str_to_mem
		// does
		// not advance it)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_CURSOR_ADDR);
		loadHeapPtr(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
		// reserve: HEAP_PTR = READ_END + 1 = sp + totalLen
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_END_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	// Pushes mem[HEAP_PTR_ADDR] (the reader input scratch base).
	private static void loadHeapPtr(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

}
