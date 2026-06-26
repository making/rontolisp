package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code random} built-in function for WASM. Unlike the interpreter and JVM
 * backends (which use {@code Math.random()}), WASM draws entropy from the WASI
 * {@code random_get} host function (imported in both Preview 1 and {@code --component}
 * mode; see {@link WasmLispCompiler#FUNC_RANDOM_GET}), so {@code (random N)} differs each
 * run. The low 32 bits of the filled buffer are masked to {@code [0, 2^31)} to match the
 * range the integer/float paths below assume.
 *
 * <p>
 * Like {@link WasmAbsCompiler}, the integer and float paths are selected at compile time
 * from the literal shape of the argument: a float literal yields
 * {@code (rand / 2^31) * limit} (a float), otherwise the integer path yields
 * {@code rand mod limit}.
 */
final class WasmRandomCompiler {

	private static final int RANDOM_MASK = 0x7fffffff;

	// 2^31, used to scale the random i32 into a [0,1) fraction for floats.
	private static final double RANDOM_SCALE = 2147483648.0;

	private WasmRandomCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("random expects 1 argument, got " + (args.size() - 1));
		}
		// Draw a random i32 from WASI entropy and leave it on the stack.
		emitRandomI32(ctx);
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			// Float limit: (rand / 2^31) * limit, a TYPE_FLOAT struct.
			ctx.writer.write(Instruction.F64_CONVERT_U_I32);
			ctx.writer.write(Instruction.F64_CONST);
			ctx.writer.writeF64(RANDOM_SCALE);
			ctx.writer.write(Instruction.F64_DIV);
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(Instruction.F64_MUL);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}
		else {
			// Integer limit: rand mod limit, an i31ref. The masked value is non-negative
			// and the limit is positive, so the unsigned remainder stays in [0, limit).
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_REM_U);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
	}

	// Leaves a non-negative random i32 in [0, 2^31) on the stack, drawn from the WASI
	// random_get host function (Preview 1: real host entropy; component: the adapter's
	// wasi:random-backed implementation). random_get is imported in both modes, so the
	// path is identical.
	private static void emitRandomI32(WasmLispCompiler.Ctx ctx) {
		// random_get(RANDOM_SCRATCH_ADDR, 8) fills 8 bytes; drop the errno.
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(8);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RANDOM_GET);
		ctx.writer.write(Instruction.DROP);
		// Load the low 32 bits and mask to [0, 2^31).
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(RANDOM_MASK);
		ctx.writer.write(Instruction.I32_AND);
	}

}
