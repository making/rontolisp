package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code random} built-in function for WASM. Unlike the interpreter and JVM
 * backends (which use {@code Math.random()}), WASM has no entropy source, so this emits a
 * deterministic linear-congruential generator over the mutable
 * {@link WasmLispCompiler#GLOBAL_SEED} global:
 * {@code seed = (seed * 1103515245 + 12345) & 0x7fffffff}.
 *
 * <p>
 * Like {@link WasmAbsCompiler}, the integer and float paths are selected at compile time
 * from the literal shape of the argument: a float literal yields
 * {@code (seed / 2^31) * limit} (a float), otherwise the integer path yields
 * {@code seed mod limit}.
 */
final class WasmRandomCompiler {

	private static final int LCG_MULTIPLIER = 1103515245;

	private static final int LCG_INCREMENT = 12345;

	private static final int LCG_MASK = 0x7fffffff;

	// 2^31, the modulus, used to scale the seed into a [0,1) fraction for floats.
	private static final double LCG_MODULUS = 2147483648.0;

	private WasmRandomCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("random expects 1 argument, got " + (args.size() - 1));
		}
		// Advance the seed and leave the new value (i32) on the stack.
		emitAdvanceSeed(ctx);
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			// Float limit: (seed / 2^31) * limit, a TYPE_FLOAT struct.
			ctx.writer.write(Instruction.F64_CONVERT_U_I32);
			ctx.writer.write(Instruction.F64_CONST);
			ctx.writer.writeF64(LCG_MODULUS);
			ctx.writer.write(Instruction.F64_DIV);
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(Instruction.F64_MUL);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}
		else {
			// Integer limit: seed mod limit, an i31ref. The masked seed is non-negative
			// and the limit is positive, so the unsigned remainder stays in [0, limit).
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_REM_U);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
	}

	// Emits seed = (seed * mult + inc) & mask, stores it back into GLOBAL_SEED, and
	// leaves
	// the updated value on the stack as an i32.
	private static void emitAdvanceSeed(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_SEED);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(LCG_MULTIPLIER);
		ctx.writer.write(Instruction.I32_MUL);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(LCG_INCREMENT);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(LCG_MASK);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_SEED);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_SEED);
	}

}
