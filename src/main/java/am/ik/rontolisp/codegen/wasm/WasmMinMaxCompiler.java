package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * The shared body of the {@code min} and {@code max} built-in functions.
 * <p>
 * Both are the same select, and the only thing that differs is which comparison keeps the
 * left operand:
 *
 * <pre>
 * min(a, b) = (a &lt;= b) ? a : b     max(a, b) = (a &gt;= b) ? a : b
 * </pre>
 *
 * with the IEEE comparisons, so the test is FALSE whenever either operand is NaN. That
 * one rule settles both float edge axes at once:
 * <ul>
 * <li>an equal-value tie satisfies both {@code <=} and {@code >=}, so the LEFT operand
 * survives it and {@code (min -0.0 0.0)} is {@code -0.0} while {@code (min 0.0 -0.0)} is
 * {@code 0.0}. CL leaves the tie to the implementation; this is what upstream
 * answers.</li>
 * <li>an unordered pair satisfies neither, so a NaN operand always yields {@code b} --
 * {@code (min nan 1.0)} is {@code 1.0} and {@code (min 1.0 nan)} is NaN.</li>
 * </ul>
 * Both were verified against SBCL bit-for-bit over every ordered pair drawn from
 * {@code {-0.0, 0.0, 1.0, -1.0, NaN, +inf, -inf}}, and the interpreter and the JVM
 * backend implement the identical rule ({@code Environment}'s fold and
 * {@code JvmNumericRuntimeBuilder}'s {@code _min}/{@code _max}).
 * <p>
 * Deliberately NOT {@code f64.min}/{@code f64.max}: those resolve a signed-zero tie by
 * SIGN whichever way round the arguments come, and propagate NaN from either side. They
 * used to serve the double-literal path here while the general path selected through
 * {@code _rat_cmp}, so one program could answer two different things depending on whether
 * an operand happened to be written as a literal.
 * <p>
 * The general path selects on {@code _rat_cmp_bits} (1 = lt, 2 = eq, 4 = gt, 0 =
 * unordered) rather than the {@code _rat_cmp} signum, because only the bitmask can tell
 * an equal pair from a NaN pair -- the signum reports {@code 0} for both, which is what
 * made every tie AND every NaN answer {@code b}.
 */
final class WasmMinMaxCompiler {

	/** {@code _rat_cmp_bits} bit for {@code a < b}. */
	private static final int CMP_LT = 1;

	/** {@code _rat_cmp_bits} bit for {@code a = b}. */
	private static final int CMP_EQ = 2;

	/** {@code _rat_cmp_bits} bit for {@code a > b}. */
	private static final int CMP_GT = 4;

	private WasmMinMaxCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, boolean min) {
		List<LispVal> args = cons.toList();
		// Both operands are kept boxed in temps: the result IS one of them, so the
		// winner is handed back untouched rather than rebuilt with a struct.new.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int aSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(aSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int bSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bSlot);

		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			// Float path: one native f64.le / f64.ge, no call. It is already false for
			// an unordered pair, so NaN needs no separate rung.
			getLocal(ctx, aSlot);
			WasmEmitHelper.castFloatGetF64(ctx);
			getLocal(ctx, bSlot);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(min ? Instruction.F64_LE : Instruction.F64_GE);
		}
		else {
			// General path: integers, ratios and bignums as well as floats.
			getLocal(ctx, aSlot);
			getLocal(ctx, bSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_CMP_BITS);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(CMP_EQ | (min ? CMP_LT : CMP_GT));
			ctx.writer.write(Instruction.I32_AND);
		}

		// The comparison held: keep a. Otherwise take b.
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, aSlot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, bSlot);
		ctx.writer.write(Instruction.END);
	}

	private static void getLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

}
