package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code lcm} built-in: the least common multiple of two integers, computed
 * as {@code abs((a / gcd(a, b)) * b)} over the tier-aware {@code _big_*} runtime helpers,
 * so the result is exact at any magnitude. Returns 0 when either argument is 0.
 */
final class WasmLcmCompiler {

	private WasmLcmCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int aSlot = ctx.allocTemp();
		int bSlot = ctx.allocTemp();
		int prodSlot = ctx.allocTemp();

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);

		// if (a == 0 || b == 0) result is 0 (zero is always the i31 0, so ref.eq works)
		emitEqZero(ctx, aSlot);
		emitEqZero(ctx, bSlot);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		emitI31Zero(ctx);

		ctx.writer.write(Instruction.ELSE);
		// prod = (a / gcd(a, b)) * b -- the division is always even, so exact
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_BIG_GCD);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_BIG_DIVREM);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_BIG_MUL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(prodSlot);
		// abs
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(prodSlot);
		emitI31Zero(ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_BIG_CMP);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(prodSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_BIG_NEG);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(prodSlot);
		ctx.writer.write(Instruction.END);

		ctx.writer.write(Instruction.END);
	}

	private static void emitEqZero(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		emitI31Zero(ctx);
		ctx.writer.write(Instruction.REF_EQ);
	}

	private static void emitI31Zero(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

}
