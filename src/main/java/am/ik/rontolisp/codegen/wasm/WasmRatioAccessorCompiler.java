package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code numerator} and {@code denominator} accessors via the
 * {@code _rat_num}/{@code _rat_den} runtime helpers: a ratio yields the requested
 * component, an exact integer of any tier is its own numerator and has denominator one,
 * and any other value lands in the helpers' INTEGER type-error. A complex operand takes
 * the real-funnel exit first (both accessors are real-only, like the interpreter and the
 * JVM backend).
 */
final class WasmRatioAccessorCompiler {

	private WasmRatioAccessorCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int ratioFunc) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmOperandTypes.emitCall(ctx, ratioFunc);
	}

}
