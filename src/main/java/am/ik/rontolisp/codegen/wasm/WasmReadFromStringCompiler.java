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
		List<LispVal> args = cons.toList();
		int val = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(val);
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
		ctx.writer.writeSignedLeb128(val);
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
		// parse one datum
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
	}

	// Pushes mem[HEAP_PTR_ADDR] (the reader input scratch base).
	private static void loadHeapPtr(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

}
