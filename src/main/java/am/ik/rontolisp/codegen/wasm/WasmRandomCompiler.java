package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

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
 * {@code _int_new}. A limb-tier limit ({@code TYPE_BIGINT}) is the shared
 * {@code _rand_big}'s ({@link #buildRandBigBody}), tested only once the limit is known to
 * be no float.
 *
 * <p>
 * A limit proven a float ({@link WasmLispCompiler#isDefinitelyDouble}) compiles straight
 * to the float path ({@code (rand / 2^31) * limit}). Otherwise the limit's type is tested
 * at runtime ({@code ref.test TYPE_FLOAT}): a float limit takes the float path, an
 * integer limit the {@code rand mod limit} i31 path. The runtime test is what lets a
 * float limit reaching {@code random} through a variable work, and an integer limit whose
 * form only mentions a float ({@code (random (if c 1.0 10))}) draw an integer. ONE draw
 * is taken before the test and shared by both branches, so a call advances the generator
 * exactly once either way.
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
		if (WasmLispCompiler.isDefinitelyDouble(args.get(1))) {
			// A limit proven a float: the float path directly, no runtime test needed --
			// but reject a non-positive one first, which this path would
			// otherwise never check.
			int limitSlot = ctx.allocTemp();
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			if (WasmEmitHelper.checksConsFields(ctx)) {
				emitPositiveFloatCheck(ctx, limitSlot);
			}
			int savedI64 = ctx.nextI64Local;
			emitRandomI32(ctx);
			ctx.nextI64Local = savedI64;
			emitFloatLimitProduct(ctx, () -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(limitSlot);
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
			// Float limit: reject <= 0.0 first under EH mode, then
			// (rand / 2^31) * limit, a TYPE_FLOAT struct. The fraction spends the
			// draw's low 32 bits, masked to [0, 2^31).
			if (WasmEmitHelper.checksConsFields(ctx)) {
				emitPositiveFloatCheck(ctx, limitSlot);
			}
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
			// A limb-tier limit draws in _rand_big, which no i64 remainder can serve.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_RAND_BIG);
			ctx.writer.write(Instruction.ELSE);
			if (WasmEmitHelper.checksConsFields(ctx)) {
				// EH mode: a limit that is no real is RANDOM's type-error, through
				// _as_f64 under the operator's register.
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(limitSlot);
				WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_AS_F64);
				ctx.writer.write(Instruction.DROP);
				// A ratio passes _as_f64 (float contagion) but is neither integer nor
				// float: _int_val rejects it too, now under the SAME register so it
				// reports RANDOM's own REAL type instead of an unnamed INTEGER one.
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(limitSlot);
				WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_INT_VAL);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(Instruction.I64_LE_S);
				ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(limitSlot);
				WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_TYPE_ERR_REAL);
				ctx.writer.write(Instruction.UNREACHABLE);
				ctx.writer.write(Instruction.END);
			}
			// Integer limit: rand mod limit in i64, normalized through _int_new. The
			// masked value is non-negative and the limit is positive (checked above
			// under EH mode; outside it a non-positive limit still traps or wraps, like
			// any other unrecoverable failure there), so the unsigned remainder stays
			// in [0, limit); _int_val accepts an i31 or boxed limit and is pure, so
			// calling it again here (after the check above) draws nothing extra.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writeI64LocalIndex(drawSlot);
			ctx.writer.write(Instruction.I64_CONST);
			ctx.writer.writeSignedLeb128(Long.MAX_VALUE);
			ctx.writer.write(Instruction.I64_AND);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(limitSlot);
			WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_INT_VAL);
			ctx.writer.write(Instruction.I64_REM_U);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
			ctx.writer.write(Instruction.END); // limb-tier if
			ctx.writer.write(Instruction.END); // float if
			ctx.nextI64Local = savedI64;
		}
	}

	/**
	 * Builds {@code _rand_big (limit) -> value} ({@link WasmLispCompiler#FUNC_RAND_BIG}):
	 * {@code random} of a limb-tier limit, uniform below it with every bit drawn. A
	 * candidate is as many limbs of generator output as the limit has, the top one masked
	 * to the top limb's width, so it is below twice the limit; one at or past the limit
	 * is drawn again ({@code _limb_cmp}), fewer than two candidates on average, and the
	 * one kept is canonicalized by {@code _limb_new}. The interpreter and the JVM draw
	 * the same way ({@code .kb/random.md}); no division is reached. A negative limit is
	 * RANDOM's {@code REAL} type-error in EH mode (the site sets the register), a trap
	 * outside it. The site has drawn once before calling, so the generator is seeded.
	 * @param ehMode whether the module lands type-errors
	 * @return the function body (signature {@code ((ref null eq)) -> (ref null eq)},
	 * {@code TYPE_CALLABLE_BASE + 0})
	 */
	static byte[] buildRandBigBody(boolean ehMode) {
		java.io.ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		int limit = 0, limitLimbs = 1, draw = 2, n = 3, i = 4, mask = 5, scratch = 6;
		// locals: 1 = the limit's limbs, 2 = the candidate (ref null $limbs); 3 = n,
		// 4 = i, 5 = the top limb's mask (i32); 6 = scratch (i64)
		w.writeUnsignedLeb128(3);
		w.writeUnsignedLeb128(2);
		w.writeRefType(true, WasmLispCompiler.TYPE_LIMBS);
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.I64);
		get(w, limit);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGINT);
		w.writeUnsignedLeb128(0);
		set(w, limitLimbs);
		get(w, limitLimbs);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		set(w, n);
		// mask = the top limb's width as ones (0 for a zero top limb); a negative top
		// limb is a negative limit, refused
		get(w, limitLimbs);
		get(w, n);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(mask);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		if (ehMode) {
			get(w, limit);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		}
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		i32(w, -1);
		get(w, mask);
		w.write(Instruction.I32_CLZ);
		w.write(Instruction.I32_SHR_U);
		i32(w, 0);
		get(w, mask);
		w.write(Instruction.SELECT);
		set(w, mask);
		// draw candidates until one is below the limit
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY);
		get(w, n);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		set(w, draw);
		i32(w, 0);
		set(w, i);
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY);
		get(w, i);
		get(w, n);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// draw[i] = the high half of one generator step
		get(w, draw);
		get(w, i);
		WasmIoRuntimeBuilder.emitSplitMix64Next(w, x -> x.writeUnsignedLeb128(scratch));
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(32L);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		get(w, i);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// draw[n - 1] &= mask
		get(w, draw);
		get(w, n);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		get(w, draw);
		get(w, n);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		get(w, mask);
		w.write(Instruction.I32_AND);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		// below the limit: the answer
		get(w, draw);
		get(w, limitLimbs);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_LIMB_CMP);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		get(w, draw);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_LIMB_NEW);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	private static void get(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void set(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	/**
	 * Rejects a {@code TYPE_FLOAT} limit whose value is {@code <= 0.0}: RANDOM's domain
	 * violation, reported like a non-real limit -- a catchable {@code type-error} naming
	 * RANDOM's own REAL type under EH mode, a trap outside it
	 * ({@link WasmLispCompiler#FUNC_TYPE_ERR_REAL}'s own gate).
	 * @param ctx the compilation context
	 * @param limitSlot the {@code (ref null eq)} local holding the limit
	 */
	private static void emitPositiveFloatCheck(WasmLispCompiler.Ctx ctx, int limitSlot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(limitSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(0.0);
		ctx.writer.write(Instruction.F64_LE);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(limitSlot);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
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
