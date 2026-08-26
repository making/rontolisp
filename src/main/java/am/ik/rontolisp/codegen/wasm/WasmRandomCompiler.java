package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code random} built-in function for WASM. The draw is a SplitMix64 step
 * over the module-local state cell {@link WasmLispCompiler#RANDOM_STATE_ADDR}, inlined at
 * the call site -- on EVERY build, Preview 1 and {@code --component} included, not only
 * {@code --no-wasi}. CL's {@code random} is a pseudo-random draw from
 * {@code *random-state*}, so a module-local generator is inside its contract, and a host
 * call per draw is not something it ever promised: the WASI {@code random_get} route this
 * used to take cost ~177 ns a draw against ~4 ns here, nearly all of it on the host side
 * (an export-name hash, a {@code Vec} allocation, an unwind guard and a ChaCha20 CSPRNG,
 * per draw). See {@code .kb/random.md}.
 *
 * <p>
 * A module that HAS a host replaces the zero start state with eight bytes of
 * {@code random_get} entropy on its FIRST draw
 * ({@link WasmLispCompiler#RANDOM_SEEDED_ADDR} is the once-only flag), so two runs of the
 * same module still draw differently. A {@code --no-wasi} module without
 * {@code --host-random} has no host to ask and emits no seeding at all: it keeps the
 * fixed start state and its exported {@code __ronto_seed_random} hook, unchanged.
 *
 * <p>
 * {@code rontolisp::%random-byte} is NOT served from here: it promises cryptographic
 * entropy, so it keeps calling {@code random_get} once per byte (see
 * {@link #compileRandomByte}).
 *
 * <p>
 * The float path masks the low 32 bits of the draw to {@code [0, 2^31)} for its fraction;
 * the integer path masks all 64 bits to {@code [0, 2^63)}, so an integer limit beyond the
 * i31 fixnum range (a {@code TYPE_BIGNUM} box) works and the result normalizes through
 * {@code _int_new}.
 *
 * <p>
 * A float-literal argument compiles straight to the float path
 * ({@code (rand / 2^31) * limit}). Otherwise the limit's type is tested at runtime
 * ({@code ref.test TYPE_FLOAT}): a float limit takes the float path, an integer limit the
 * {@code rand mod limit} i31 path. The runtime test is what lets a float limit reaching
 * {@code random} through a variable work (the compile-time literal shape alone cannot
 * detect it). ONE draw is taken before the test and shared by both branches, so a call
 * advances the generator exactly once either way.
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
			int savedI64 = ctx.nextI64Local;
			emitRandomI32(ctx);
			ctx.nextI64Local = savedI64;
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
			// ONE draw, taken before the test and parked, so both branches spend the
			// same step and a call advances the generator exactly once.
			int savedI64 = ctx.nextI64Local;
			int drawSlot = ctx.allocI64Temp();
			emitRandomDraw(ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writeI64LocalIndex(drawSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			// Float limit: (rand / 2^31) * limit, a TYPE_FLOAT struct. The fraction
			// spends the draw's low 32 bits, masked to [0, 2^31).
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writeI64LocalIndex(drawSlot);
			ctx.writer.write(Instruction.I32_WRAP_I64);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(RANDOM_MASK);
			ctx.writer.write(Instruction.I32_AND);
			emitFloatLimitProduct(ctx, () -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(limitSlot);
				WasmEmitHelper.castFloatGetF64(ctx);
			});
			ctx.writer.write(Instruction.ELSE);
			// Integer limit: rand mod limit in i64, normalized through _int_new. The
			// masked value is non-negative and the limit is positive, so the unsigned
			// remainder stays in [0, limit); _int_val accepts an i31 or boxed limit.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writeI64LocalIndex(drawSlot);
			ctx.writer.write(Instruction.I64_CONST);
			ctx.writer.writeSignedLeb128(Long.MAX_VALUE);
			ctx.writer.write(Instruction.I64_AND);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
			ctx.writer.write(Instruction.I64_REM_U);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
			ctx.writer.write(Instruction.END);
			ctx.nextI64Local = savedI64;
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

	// Leaves a non-negative random i32 in [0, 2^31) on the stack: the low 32 bits of one
	// generator step, masked.
	private static void emitRandomI32(WasmLispCompiler.Ctx ctx) {
		emitRandomDraw(ctx);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(RANDOM_MASK);
		ctx.writer.write(Instruction.I32_AND);
	}

	// Leaves one SplitMix64 draw (a u64) on the stack, seeding the generator from the
	// host's entropy on the first draw of the instance when there IS a host to ask.
	private static void emitRandomDraw(WasmLispCompiler.Ctx ctx) {
		if (!ctx.noWasi || ctx.hostRandom) {
			// if (mem32[RANDOM_SEEDED_ADDR] == 0) { flag = 1; state = random_get(8) }
			// -- one host call per INSTANCE, not per draw. A --no-wasi module without
			// --host-random skips this entirely: it has no host, so its generator keeps
			// the fixed start state the __ronto_seed_random hook exists to replace.
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SEEDED_ADDR);
			ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SEEDED_ADDR);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(8);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RANDOM_GET);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_STATE_ADDR);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.RANDOM_SCRATCH_ADDR);
			ctx.writer.write(Instruction.I64_LOAD, 0x03, 0x00);
			ctx.writer.write(Instruction.I64_STORE, 0x03, 0x00);
			ctx.writer.write(Instruction.END);
		}
		int scratch = ctx.allocI64Temp();
		WasmIoRuntimeBuilder.emitSplitMix64Next(ctx.writer, w -> ctx.writeI64LocalIndex(scratch));
	}

}
