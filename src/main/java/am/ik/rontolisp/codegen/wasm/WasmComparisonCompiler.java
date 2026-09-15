package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles comparison operations ({@code =}, {@code <}, {@code >}, {@code <=},
 * {@code >=}).
 */
final class WasmComparisonCompiler {

	private WasmComparisonCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int i32Opcode, int f64Opcode) {
		List<LispVal> args = cons.toList();
		// The unboxed f64 path is taken only when BOTH operands prove double: a
		// float beside an exact number compares exact values, so coercing the
		// exact side here would round a near tie to equality. Anything else goes
		// through _rat_cmp_bits, whose float-vs-exact arm is exact.
		if (WasmLispCompiler.isDefinitelyDouble(args.get(1)) && WasmLispCompiler.isDefinitelyDouble(args.get(2))) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(f64Opcode);
		}
		else if (WasmIntFusionCompiler.tryCompileCompare(cons, ctx, i64OpcodeFor(i32Opcode), maskFor(i32Opcode))) {
			// Fused raw i64 compare over integer expression trees (boxes its own
			// t/nil result; a non-integer operand falls back to _rat_cmp_bits).
			return;
		}
		else {
			// _rat_cmp_bits returns the comparison as a bitmask (1 = lt, 2 = eq,
			// 4 = gt, 0 = unordered), so a NaN operand fails every operator. The old
			// signum _rat_cmp against zero answered "equal" for NaN.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(am.ik.wasm.Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_CMP_BITS);
			ctx.writer.write(am.ik.wasm.Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(maskFor(i32Opcode));
			ctx.writer.write(am.ik.wasm.Instruction.I32_AND);
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/**
	 * Compiles a CONDITION-position test as a raw i32 truth value (0 = false, non-0 =
	 * true) when it is a binary numeric comparison -- fused when the fusion compiler
	 * takes it, through the generic {@code _rat_cmp_bits} mask test otherwise -- so the
	 * consumer tests the i32 directly, skipping the boxed t/nil round trip (a
	 * {@code _t_sym} call per true evaluation). Returns {@code false} having emitted
	 * nothing for every other shape (a literal-double or complex operand keeps its own
	 * compilation). The numeric arm of {@link WasmConditionCompiler}, which is what the
	 * consumers ({@code if}/{@code while}/{@code not}) call: a {@code (not ...)} wrapper
	 * flips {@code negated} there instead of emitting an {@code i32.eqz}, so
	 * {@code loop}'s numeric head -- whose test is spelled {@code (not (> i limit))}
	 * ({@code .kb/loop-iteration-heads.md}) -- exits on the bare compare.
	 * @param test the test form
	 * @param ctx the function context
	 * @param negated whether to answer the COMPLEMENT: non-0 exactly when the test is
	 * false, which is what a loop's exit {@code br_if} wants
	 * @return whether the test was compiled
	 */
	static boolean tryCompileConditionI32(LispVal test, WasmLispCompiler.Ctx ctx, boolean negated) {
		if (!(test instanceof LispCons cons) || !cons.isProperList()
				|| !(cons.car() instanceof am.ik.rontolisp.LispSymbol head)) {
			return false;
		}
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			return false;
		}
		// A syntactic complex takes the complex-aware compilation (which signals
		// for ordering), never the fused i32 fast path.
		if (WasmComplexCompiler.hasComplex(test)) {
			return false;
		}
		int i32Opcode = switch (head.name()) {
			case am.ik.rontolisp.LispNames.EQ -> am.ik.wasm.Instruction.I32_EQ;
			case am.ik.rontolisp.LispNames.LT -> am.ik.wasm.Instruction.I32_LT_S;
			case am.ik.rontolisp.LispNames.GT -> am.ik.wasm.Instruction.I32_GT_S;
			case am.ik.rontolisp.LispNames.LE -> am.ik.wasm.Instruction.I32_LE_S;
			case am.ik.rontolisp.LispNames.GE -> am.ik.wasm.Instruction.I32_GE_S;
			default -> -1;
		};
		if (i32Opcode < 0 || WasmLispCompiler.hasDoubleLiteral(args)) {
			return false;
		}
		if (!WasmIntFusionCompiler.tryCompileCompare(cons, ctx, i64OpcodeFor(i32Opcode), maskFor(i32Opcode), false)) {
			// The generic comparison, still RAW: the mask test's i32 is the truth value,
			// and boxing it into t/nil only for the consumer to test the box again would
			// cost a _t_sym call per true answer -- and would root the symbol machinery
			// (_t_sym, _str_build) in a module that otherwise never makes a string.
			// Not a speed-for-size trade, so it holds at every optimize level.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(am.ik.wasm.Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_CMP_BITS);
			ctx.writer.write(am.ik.wasm.Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(maskFor(i32Opcode));
			ctx.writer.write(am.ik.wasm.Instruction.I32_AND);
		}
		if (negated) {
			ctx.writer.write(am.ik.wasm.Instruction.I32_EQZ);
		}
		return true;
	}

	private static int i64OpcodeFor(int i32Opcode) {
		return switch (i32Opcode) {
			case am.ik.wasm.Instruction.I32_EQ -> am.ik.wasm.Instruction.I64_EQ;
			case am.ik.wasm.Instruction.I32_LT_S -> am.ik.wasm.Instruction.I64_LT_S;
			case am.ik.wasm.Instruction.I32_GT_S -> am.ik.wasm.Instruction.I64_GT_S;
			case am.ik.wasm.Instruction.I32_LE_S -> am.ik.wasm.Instruction.I64_LE_S;
			case am.ik.wasm.Instruction.I32_GE_S -> am.ik.wasm.Instruction.I64_GE_S;
			default -> throw new IllegalArgumentException("unexpected comparison opcode: " + i32Opcode);
		};
	}

	private static int maskFor(int i32Opcode) {
		return switch (i32Opcode) {
			case am.ik.wasm.Instruction.I32_EQ -> 0b010;
			case am.ik.wasm.Instruction.I32_LT_S -> 0b001;
			case am.ik.wasm.Instruction.I32_GT_S -> 0b100;
			case am.ik.wasm.Instruction.I32_LE_S -> 0b011;
			case am.ik.wasm.Instruction.I32_GE_S -> 0b110;
			default -> throw new IllegalArgumentException("unexpected comparison opcode: " + i32Opcode);
		};
	}

}
