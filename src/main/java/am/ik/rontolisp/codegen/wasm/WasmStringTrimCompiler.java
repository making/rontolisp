package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code string-trim} / {@code string-left-trim} / {@code string-right-trim}.
 * The character bag, the target string and a mode selector (0 = both ends, 1 = left, 2 =
 * right) are passed to the {@code _string_trim} runtime helper.
 */
final class WasmStringTrimCompiler {

	private static final int BOTH = 0;

	private static final int LEFT = 1;

	private static final int RIGHT = 2;

	private WasmStringTrimCompiler() {
	}

	static void compileTrim(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, BOTH);
	}

	static void compileLeft(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, LEFT);
	}

	static void compileRight(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, RIGHT);
	}

	private static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int mode) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		// mode selector as an i31 integer
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(mode);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_STRING_TRIM);
	}

}
