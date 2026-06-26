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
		// cursor = offset + 1 (skip the opening quote)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_CURSOR_ADDR);
		structField(ctx, val, 0);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
		// end = offset + length - 1 (before the closing quote)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_END_ADDR);
		structField(ctx, val, 0);
		structField(ctx, val, 1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
		// parse one datum
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
	}

	private static void structField(WasmLispCompiler.Ctx ctx, int slot, int field) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeSignedLeb128(field);
	}

}
