package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code list} built-in function.
 */
final class WasmListCompiler {

	private WasmListCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Build cons list right-to-left
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		for (int i = args.size() - 1; i >= 1; i--) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			// Swap car and cdr on stack using temp
			int tmpCdr = ctx.allocTemp();
			int tmpCar = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCar);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCdr);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCar);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCdr);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		}
	}

}
