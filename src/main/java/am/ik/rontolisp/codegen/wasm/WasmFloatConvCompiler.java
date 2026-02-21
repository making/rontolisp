package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code float} type conversion function. Converts any number to a
 * floating-point value. Uses runtime dispatch: i31ref (integer) is converted via
 * f64.convert_s_i32, float_struct is passed through.
 */
final class WasmFloatConvCompiler {

	private WasmFloatConvCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

}
