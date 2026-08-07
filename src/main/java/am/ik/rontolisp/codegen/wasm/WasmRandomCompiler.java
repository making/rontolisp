package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code random} built-in function for WASM. Unlike the interpreter and JVM
 * backends (which use {@code Math.random()}), WASM draws entropy from the WASI
 * {@code random_get} host function (imported in both Preview 1 and {@code --component}
 * mode; see {@link WasmLispCompiler#FUNC_RANDOM_GET}), so {@code (random N)} differs each
 * run. The float path masks the low 32 bits of the filled buffer to {@code [0, 2^31)} for
 * its fraction; the integer path draws all 64 bits masked to {@code [0, 2^63)}, so an
 * integer limit beyond the i31 fixnum range (a {@code TYPE_BIGNUM} box) works and the
 * result normalizes through {@code _int_new}.
 *
 * <p>
 * A float-literal argument compiles straight to the float path
 * ({@code (rand / 2^31) * limit}). Otherwise the limit's type is tested at runtime
 * ({@code ref.test TYPE_FLOAT}): a float limit takes the float path, an integer limit the
 * {@code rand mod limit} i31 path. The runtime test is what lets a float limit reaching
 * {@code random} through a variable work (the compile-time literal shape alone cannot
 * detect it).
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
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			// Float-literal limit: the float path directly, no runtime test needed.
			emitRandomI32(ctx);
			emitFloatLimitProduct(ctx, () -> {
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
			});
		}
		else {
			// Test the limit's runtime type: a float -> float path, otherwise the i31
			// path.
			int limitSlot = ctx.allocTemp();
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			// Float limit: (rand / 2^31) * limit, a TYPE_FLOAT struct.
			emitRandomI32(ctx);
			emitFloatLimitProduct(ctx, () -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(limitSlot);
				WasmEmitHelper.castFloatGetF64(ctx);
			});
			ctx.writer.write(Instruction.ELSE);
			// Integer limit: rand mod limit in i64, normalized through _int_new. The
			// masked value is non-negative and the limit is positive, so the unsigned
			// remainder stays in [0, limit); _int_val accepts an i31 or boxed limit.
			emitRandomI63(ctx);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
			ctx.writer.write(Instruction.I64_REM_U);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
			ctx.writer.write(Instruction.END);
		}
	}

	/**
	 * Compiles the internal {@code rontolisp::%random-byte} primitive: one
	 * cryptographically strong byte (0-255) as an i31 fixnum. The entropy source is the
	 * same {@code random_get} host function {@code random} draws from -- real host
	 * entropy in Preview 1, {@code wasi:random} under {@code --component} -- so the byte
	 * is as strong as the host's generator on every WASM target.
	 * @param cons the call form (no arguments)
	 * @param ctx the compilation context
	 */
	static void compileRandomByte(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (cons.toList().size() != 1) {
			throw new UnsupportedOperationException(
					"%random-byte expects 0 arguments, got " + (cons.toList().size() - 1));
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(8);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RANDOM_GET);
		ctx.writer.write(Instruction.DROP);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	// Consumes a random i32 on the stack and emits (rand / 2^31) * limit as a TYPE_FLOAT
	// struct. pushLimitF64 is run after the [0,1) fraction is on the stack and must
	// append
	// the limit as an f64 (e.g. compile it and castFloatGetF64), which F64_MUL then
	// scales.
	private static void emitFloatLimitProduct(WasmLispCompiler.Ctx ctx, Runnable pushLimitF64) {
		ctx.writer.write(Instruction.F64_CONVERT_U_I32);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(RANDOM_SCALE);
		ctx.writer.write(Instruction.F64_DIV);
		pushLimitF64.run();
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RANDOM_GET);
		ctx.writer.write(Instruction.DROP);
		// Load the low 32 bits and mask to [0, 2^31).
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(RANDOM_MASK);
		ctx.writer.write(Instruction.I32_AND);
	}

	// Leaves a non-negative random i64 in [0, 2^63) on the stack: the same 8-byte
	// random_get draw, loaded whole and masked to 63 bits.
	private static void emitRandomI63(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(8);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RANDOM_GET);
		ctx.writer.write(Instruction.DROP);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I64_LOAD, 0x03, 0x00);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(Long.MAX_VALUE);
		ctx.writer.write(Instruction.I64_AND);
	}

}
