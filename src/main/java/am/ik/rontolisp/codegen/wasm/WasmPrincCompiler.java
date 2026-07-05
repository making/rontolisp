package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code princ} built-in function. Prints without quotes and without
 * newline.
 */
final class WasmPrincCompiler {

	private WasmPrincCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() > 2) {
			// (princ value stream): render to a string, then route via
			// _write_stream_str.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRINC_TO_STR);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRINC_VAL);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
