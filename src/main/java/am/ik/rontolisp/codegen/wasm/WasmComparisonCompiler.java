package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
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
		if (tryCompileFloatI32(args, ctx, i32Opcode, f64Opcode)) {
			WasmEmitHelper.emitBoolFromI32(ctx);
			return;
		}
		if (WasmIntFusionCompiler.tryCompileCompare(cons, ctx, i64OpcodeFor(i32Opcode), maskFor(i32Opcode))) {
			// Fused raw i64 compare over integer expression trees (boxes its own
			// t/nil result; a non-integer operand falls back to _rat_cmp_bits).
			return;
		}
		// _rat_cmp_bits returns the comparison as a bitmask (1 = lt, 2 = eq,
		// 4 = gt, 0 = unordered), so a NaN operand fails every operator. The old
		// signum _rat_cmp against zero answered "equal" for NaN.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		emitGenericCompare(ctx, maskFor(i32Opcode));
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/**
	 * Emits the comparison's i32 truth on a float path when one applies, else nothing.
	 * The unboxed f64 path is taken only when BOTH operands prove double: a float beside
	 * an exact number compares exact values, so coercing the exact side here would round
	 * a near tie to equality. With ONE operand a double literal and the other unproven,
	 * the other is tested for a float box at run time: a float compares raw against the
	 * literal's f64, anything else goes through {@code _rat_cmp_bits}, whose
	 * float-vs-exact arm is exact (a speed trade, so not under {@code --optimize=size}).
	 * @param args the form, operator first
	 * @param ctx the compile context
	 * @param i32Opcode the operator's i32 opcode
	 * @param f64Opcode the operator's f64 opcode
	 * @return whether the i32 was emitted
	 */
	private static boolean tryCompileFloatI32(List<LispVal> args, WasmLispCompiler.Ctx ctx, int i32Opcode,
			int f64Opcode) {
		boolean leftDouble = WasmLispCompiler.isDefinitelyDouble(args.get(1));
		boolean rightDouble = WasmLispCompiler.isDefinitelyDouble(args.get(2));
		if (leftDouble && rightDouble && WasmFloatOperands.guards(args.subList(1, 3), ctx)) {
			// Proven doubles -- unless an operand holds a complex the form does not spell
			// ((* 2.0 z)): then = compares part-wise and an ordering signals, through the
			// generic comparison.
			WasmFloatOperands.Operands operands = WasmFloatOperands.evaluate(args.subList(1, 3), ctx);
			operands.emitHoldsComplex(ctx);
			ctx.writer.write(am.ik.wasm.Instruction.IF);
			ctx.writer.write(am.ik.wasm.Type.I32);
			operands.pushBoxed(0, ctx);
			operands.pushBoxed(1, ctx);
			emitGenericCompare(ctx, maskFor(i32Opcode));
			ctx.writer.write(am.ik.wasm.Instruction.ELSE);
			operands.pushRaw(0, ctx);
			operands.pushRaw(1, ctx);
			ctx.writer.write(f64Opcode);
			ctx.writer.write(am.ik.wasm.Instruction.END);
			return true;
		}
		if (leftDouble && rightDouble) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(f64Opcode);
			return true;
		}
		int literal = args.get(1) instanceof LispDouble ? 1 : args.get(2) instanceof LispDouble ? 2 : 0;
		if (literal == 0 || !WasmIntFusionCompiler.speedTradesEnabled(ctx)) {
			return false;
		}
		emitLiteralGuarded(args, literal, ctx, i32Opcode, f64Opcode);
		return true;
	}

	// (op x 1.5) with x unproven: x; local.tee t; ref.test FLOAT; if (result i32)
	// [t's f64 and the literal's, in operand order; f64 op] else [t and the boxed
	// literal, in operand order; the generic compare] end. The literal is a constant,
	// so evaluating x first keeps the argument order.
	private static void emitLiteralGuarded(List<LispVal> args, int literal, WasmLispCompiler.Ctx ctx, int i32Opcode,
			int f64Opcode) {
		int other = 3 - literal;
		double value = ((LispDouble) args.get(literal)).value();
		WasmExprCompiler.compileExpr(args.get(other), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(am.ik.wasm.Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(am.ik.wasm.Instruction.GC_PREFIX, am.ik.wasm.Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(am.ik.wasm.Instruction.IF);
		ctx.writer.write(am.ik.wasm.Type.I32);
		ctx.wasmCtrlDepth++;
		for (int i = 1; i <= 2; i++) {
			if (i == literal) {
				ctx.writer.write(am.ik.wasm.Instruction.F64_CONST);
				ctx.writer.writeF64(value);
			}
			else {
				ctx.writer.write(am.ik.wasm.Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(am.ik.wasm.Instruction.GC_PREFIX, am.ik.wasm.Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
				ctx.writer.write(am.ik.wasm.Instruction.GC_PREFIX, am.ik.wasm.Instruction.STRUCT_GET);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
				ctx.writer.writeUnsignedLeb128(0);
			}
		}
		ctx.writer.write(f64Opcode);
		ctx.writer.write(am.ik.wasm.Instruction.ELSE);
		for (int i = 1; i <= 2; i++) {
			if (i == literal) {
				WasmExprCompiler.compileExpr(args.get(literal), ctx);
			}
			else {
				ctx.writer.write(am.ik.wasm.Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
			}
		}
		emitGenericCompare(ctx, maskFor(i32Opcode));
		ctx.wasmCtrlDepth--;
		ctx.writer.write(am.ik.wasm.Instruction.END);
	}

	/**
	 * The generic comparison of the two operands on the stack, as the i32 truth of the
	 * operator: {@code _rat_cmp_bits} masked -- or, for {@code =} in a module whose
	 * program may observe a complex, the complex block's {@code =}, which compares a
	 * complex arriving through a variable part-wise ({@link WasmComplexBlock}). An
	 * ordering keeps {@code _rat_cmp_bits}, where a complex lands in the REAL report the
	 * interpreter gives.
	 * @param ctx the compile context
	 * @param mask the operator's {@code _rat_cmp_bits} mask ({@link #maskFor})
	 */
	static void emitGenericCompare(WasmLispCompiler.Ctx ctx, int mask) {
		WasmComplexBlock complexBlock = ctx.complexBlock;
		if (complexBlock != null && mask == maskFor(am.ik.wasm.Instruction.I32_EQ)) {
			// Answers 2 or 0: already the mask's bit.
			complexBlock.emitCall(ctx, WasmComplexBlock.Fn.NUM_EQ);
			return;
		}
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		ctx.writer.write(am.ik.wasm.Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(mask);
		ctx.writer.write(am.ik.wasm.Instruction.I32_AND);
	}

	/**
	 * Compiles a CONDITION-position test as a raw i32 truth value (0 = false, non-0 =
	 * true) when it is a binary numeric comparison -- fused when the fusion compiler
	 * takes it, through the generic {@code _rat_cmp_bits} mask test otherwise -- so the
	 * consumer tests the i32 directly, skipping the boxed t/nil round trip (a
	 * {@code _t_sym} call per true evaluation). The float paths of {@link #compile}
	 * answer here as the same i32. Returns {@code false} having emitted nothing for every
	 * other shape (a complex operand keeps its own compilation). The numeric arm of
	 * {@link WasmConditionCompiler}, which is what the consumers
	 * ({@code if}/{@code while}/{@code not}) call: a {@code (not ...)} wrapper flips
	 * {@code negated} there instead of emitting an {@code i32.eqz}, so {@code loop}'s
	 * numeric head -- whose test is spelled {@code (not (> i limit))}
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
		if (i32Opcode < 0) {
			return false;
		}
		// The consumer handed the test over without compiling it as a form, so the
		// test's operator is set here (WasmOperandTypes).
		WasmOperandTypes.withOperator(ctx, head.name(), () -> compileConditionI32(cons, args, ctx, i32Opcode));
		if (negated) {
			ctx.writer.write(am.ik.wasm.Instruction.I32_EQZ);
		}
		return true;
	}

	private static void compileConditionI32(LispCons cons, List<LispVal> args, WasmLispCompiler.Ctx ctx,
			int i32Opcode) {
		if (tryCompileFloatI32(args, ctx, i32Opcode, f64OpcodeFor(i32Opcode))) {
			return;
		}
		if (!WasmIntFusionCompiler.tryCompileCompare(cons, ctx, i64OpcodeFor(i32Opcode), maskFor(i32Opcode), false)) {
			// The generic comparison, still RAW: the mask test's i32 is the truth value,
			// and boxing it into t/nil only for the consumer to test the box again would
			// cost a _t_sym call per true answer -- and would root the symbol machinery
			// (_t_sym, _str_build) in a module that otherwise never makes a string.
			// Not a speed-for-size trade, so it holds at every optimize level.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			emitGenericCompare(ctx, maskFor(i32Opcode));
		}
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

	private static int f64OpcodeFor(int i32Opcode) {
		return switch (i32Opcode) {
			case am.ik.wasm.Instruction.I32_EQ -> am.ik.wasm.Instruction.F64_EQ;
			case am.ik.wasm.Instruction.I32_LT_S -> am.ik.wasm.Instruction.F64_LT;
			case am.ik.wasm.Instruction.I32_GT_S -> am.ik.wasm.Instruction.F64_GT;
			case am.ik.wasm.Instruction.I32_LE_S -> am.ik.wasm.Instruction.F64_LE;
			case am.ik.wasm.Instruction.I32_GE_S -> am.ik.wasm.Instruction.F64_GE;
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
