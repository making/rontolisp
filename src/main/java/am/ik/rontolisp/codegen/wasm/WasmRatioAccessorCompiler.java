package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code numerator} and {@code denominator} accessors via the
 * {@code _rat_num}/{@code _rat_den} runtime helpers: a ratio yields the requested
 * component, an integer is its own numerator and has denominator one. A complex operand
 * takes the real-funnel exit first (both accessors are real-only, like the interpreter
 * and the JVM backend) -- without it a complex would fall through to the integer-or-one
 * tail. A boxed-i64 or limb integer is answered here, not by the helpers: they return i32
 * (ratio components stay i32, {@code .kb/wasm-bignum.md}) and would wrap it.
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
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// if (x is a wide integer) { x, or 1 } else { the i31 helper answer }
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(ratioFunc);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.ELSE);
		if (ratioFunc == WasmLispCompiler.FUNC_RAT_DEN) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
		}
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

}
