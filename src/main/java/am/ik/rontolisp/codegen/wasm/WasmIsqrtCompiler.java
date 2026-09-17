package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code isqrt} built-in: the integer square root (floor of the real square
 * root). An i31 operand (or a float, which the f64 path has always accepted) takes
 * {@code trunc(floor(sqrt((f64) x)))}, exact on that range, so no integer-domain
 * correction is needed. A boxed-i64 or limb integer -- where the f64 path traps past 2^31
 * and rounds past 2^53 -- takes an exact Newton iteration over the tier-aware
 * {@code _big_*} helpers: {@code x = 2^ceil(len/2)} is above the root, and
 * {@code y = (x + n/x) >> 1} decreases strictly until the first {@code y >= x}, when
 * {@code x} is the floor of the root. A complex operand takes the integer-funnel exit
 * first (isqrt is integer-only, like the interpreter's asBigInteger funnel and the JVM
 * backend), as does a negative wide integer.
 */
final class WasmIsqrtCompiler {

	private WasmIsqrtCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		int xSlot = ctx.allocTemp();
		int ySlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		get(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.IF, 0x40);
		typeError(ctx, slot);
		ctx.writer.write(Instruction.END);

		// if (n is a boxed-i64 or limb integer) { Newton into xSlot } else { f64 path }
		get(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		get(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, 0x40);
		emitNewton(ctx, slot, xSlot, ySlot);
		ctx.writer.write(Instruction.ELSE);
		get(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_SQRT);
		ctx.writer.write(Instruction.F64_FLOOR);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		set(ctx, xSlot);
		ctx.writer.write(Instruction.END);
		get(ctx, xSlot);
	}

	private static void emitNewton(WasmLispCompiler.Ctx ctx, int nSlot, int xSlot, int ySlot) {
		// A negative wide integer has no integer root.
		get(ctx, nSlot);
		i31(ctx, 0);
		call(ctx, WasmLispCompiler.FUNC_BIG_CMP);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		typeError(ctx, nSlot);
		ctx.writer.write(Instruction.END);
		// x = 1 << ((integer-length(n) + 1) >> 1)
		i31(ctx, 1);
		get(ctx, nSlot);
		call(ctx, WasmLispCompiler.FUNC_BIG_INTLEN);
		i31(ctx, 1);
		call(ctx, WasmLispCompiler.FUNC_BIG_ADD);
		i31(ctx, -1);
		call(ctx, WasmLispCompiler.FUNC_BIG_ASH);
		call(ctx, WasmLispCompiler.FUNC_BIG_ASH);
		set(ctx, xSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// y = (x + n / x) >> 1
		get(ctx, xSlot);
		get(ctx, nSlot);
		get(ctx, xSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		call(ctx, WasmLispCompiler.FUNC_BIG_DIVREM);
		call(ctx, WasmLispCompiler.FUNC_BIG_ADD);
		i31(ctx, -1);
		call(ctx, WasmLispCompiler.FUNC_BIG_ASH);
		set(ctx, ySlot);
		// if (y >= x) exit with x
		get(ctx, ySlot);
		get(ctx, xSlot);
		call(ctx, WasmLispCompiler.FUNC_BIG_CMP);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		get(ctx, ySlot);
		set(ctx, xSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
	}

	private static void typeError(WasmLispCompiler.Ctx ctx, int slot) {
		get(ctx, slot);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_INT);
		ctx.writer.write(Instruction.UNREACHABLE);
	}

	private static void i31(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	private static void get(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void set(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void call(WasmLispCompiler.Ctx ctx, int function) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(function);
	}

}
