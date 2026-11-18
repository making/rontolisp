package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code rontolisp:float16-bits} / {@code rontolisp:bits-float16}: sixteen bits
 * fit an i31 fixnum, so -- like {@link WasmBFloat16Compiler} beside it -- this is a real
 * primitive here, not a call-time signal the {@code %ieee754-*} quartet needs (that
 * pair's 32/64-bit unsigned model has no room in this numeric model).
 *
 * <p>
 * {@link #compileFromBits} (the DECODE direction, bits -&gt; real) is the magic-multiply
 * plus a MASKED "exponent field is all-ones" fixup -- the
 * {@code .todo/artefacts/482-bfloat16-a-narrow-width-that-pays/Load.java} shape, not
 * {@code Dec.java}'s variant D as literally written (that one's fixup mask was found
 * wrong for NaN while building this; see the fixed {@code Dec.java} and the section 4
 * re-evaluation note in
 * {@code .todo/artefacts/482-bfloat16-a-narrow-width-that-pays/README.md}).
 *
 * <p>
 * {@link #compileBits} (the ENCODE direction, real -&gt; bits) is IEEE 754's conversion
 * of a binary32 to binary16, round to nearest with ties to even, written here from the
 * two formats ({@link #emitFloat16Encode}): at most 2^-25 is a signed zero, 2^16 and up
 * an infinity, and between them the 24-bit significand is shifted down to the 11 bits
 * binary16 keeps -- further by each exponent below its smallest normal one -- and the
 * rounded significand ADDED to the exponent field, so a carry out of it lands on the next
 * exponent, and on infinity from the largest one. What the standard leaves open is a
 * NaN's payload; a JDK's {@code Float.floatToFloat16} was asked (it answers a quiet NaN
 * with the payload's top ten bits). The same arithmetic written in Java agrees with that
 * method on all 2^32 inputs, and the emitted code is pinned against it over every regime
 * ({@code WasmLispCompilerIntegrationTest}).
 *
 * <p>
 * Every intermediate value lives in an {@code i64} scratch slot (this compiler has no
 * i32-local mechanism, only {@link WasmLispCompiler.Ctx#allocI64Temp()} -- WASM has no
 * stack {@code dup}, so a value read more than once needs a local), wrapped down to
 * {@code i32} for the actual bit arithmetic and extended back up (unsigned) to store,
 * matching {@link WasmBFloat16Compiler}'s idiom.
 */
final class WasmFloat16Compiler {

	/**
	 * {@code 0x7c00 << 13}: the f16 exponent-all-ones field, shifted into u's position.
	 */
	private static final int SE = 0x7c00 << 13;

	/** {@code 0x7f800000 - 0x47800000}: the exponent fixup added when u's field is SE. */
	private static final int INF_FIXUP = 0x7f800000 - 0x47800000;

	/** {@code 0x1.0p112f}: the magic-multiply rescale constant. */
	private static final float DECODE_SCALE = Float.intBitsToFloat(0x77800000);

	/** The bits of 2^16: from here up a binary32 is past every finite binary16. */
	private static final int OVERFLOW_THRESHOLD = 0x47800000;

	/**
	 * The bits of 2^-25, half the smallest binary16 subnormal: up to here (the tie goes
	 * to the even zero) a binary32 rounds to zero.
	 */
	private static final int ZERO_THRESHOLD = 0x33000000;

	/** The bits of the binary32 infinity; a magnitude above it is a NaN. */
	private static final int F32_INFINITY = 0x7f800000;

	/** The exponent field of binary16's smallest normal binade (2^-14) in a binary32. */
	private static final int F16_MIN_NORMAL_EXPONENT = 113;

	/**
	 * How a packed float array's data field (field 1 of {@code TYPE_FARRAY}) is laid out
	 * at the widen destination / narrow source. The two scalar arms are told apart at
	 * RUNTIME by {@code ref.test $f32arr}; {@link #VBLOCK} is a COMPILE-time fact
	 * ({@code ctx.simd}), because a {@code --simd} module has one representation for both
	 * widths and a default module cannot even declare the type ({@code .kb/vec.md}, "The
	 * four acceleration layers").
	 */
	private enum Layout {

		/**
		 * {@code (array (mut f32))} -- a {@code single-float} array, no {@code --simd}.
		 */
		F32ARR,
		/**
		 * {@code (array (mut f64))} -- a {@code double-float} array, no {@code --simd}.
		 */
		F64ARR,
		/**
		 * A {@code TYPE_VBLOCK} of {@code (array (mut v128))} lane groups, both widths,
		 * {@code --simd} only: element access goes through {@code _v_get}/{@code _v_set},
		 * exactly as {@code %read-sequence-packed} and {@code (setf aref)} do.
		 */
		VBLOCK

	}

	private WasmFloat16Compiler() {
	}

	/** {@code (rontolisp:float16-bits x)}: the real narrowed to its binary16 bits. */
	static void compileBits(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F32_DEMOTE_F64);
		ctx.writer.write(Instruction.I32_REINTERPRET_F32);
		int savedI64Locals = ctx.nextI64Local;
		int bits = ctx.allocI64Temp();
		Float16Slots slots = Float16Slots.alloc(ctx);
		storeI32(ctx, bits);

		emitFloat16Encode(ctx, bits, slots);

		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.nextI64Local = savedI64Locals;
	}

	/** {@code (rontolisp:bits-float16 bits)}: the real a binary16 pattern encodes. */
	static void compileFromBits(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		constI32(ctx, 0xffff);
		i32(ctx, Instruction.I32_AND);
		int savedI64Locals = ctx.nextI64Local;
		int bits = ctx.allocI64Temp();
		int u = ctx.allocI64Temp();
		int r = ctx.allocI64Temp();
		storeI32(ctx, bits);

		// u = (bits & 0x7fff) << 13
		loadI32(ctx, bits);
		constI32(ctx, 0x7fff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 13);
		i32(ctx, Instruction.I32_SHL);
		storeI32(ctx, u);

		// r = i32.reinterpret_f32(f32.reinterpret_i32(u) * DECODE_SCALE)
		loadI32(ctx, u);
		ctx.writer.write(Instruction.F32_REINTERPRET_I32);
		ctx.writer.write(Instruction.F32_CONST);
		ctx.writer.writeF32(DECODE_SCALE);
		ctx.writer.write(Instruction.F32_MUL);
		ctx.writer.write(Instruction.I32_REINTERPRET_F32);
		storeI32(ctx, r);

		// if (u & SE) == SE: r += INF_FIXUP -- masked, not a bare u == SE (that fixed-up
		// only true infinity, missing every NaN; see this file's class doc).
		loadI32(ctx, u);
		constI32(ctx, SE);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, SE);
		i32(ctx, Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF, 0x40); // void
		loadI32(ctx, r);
		constI32(ctx, INF_FIXUP);
		i32(ctx, Instruction.I32_ADD);
		storeI32(ctx, r);
		ctx.writer.write(Instruction.END);

		// r |= (bits & 0x8000) << 16; result = f64(f32.reinterpret_i32(r))
		loadI32(ctx, r);
		loadI32(ctx, bits);
		constI32(ctx, 0x8000);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHL);
		i32(ctx, Instruction.I32_OR);
		ctx.writer.write(Instruction.F32_REINTERPRET_I32);
		ctx.writer.write(Instruction.F64_PROMOTE_F32);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.nextI64Local = savedI64Locals;
	}

	/**
	 * {@code (rontolisp:widen-float-bits bits format dst &key (start 0))}: {@code bits} a
	 * bare {@code TYPE_I16ARR}, {@code dst} a {@code TYPE_FARRAY} whose data (field 1) is
	 * a bare {@code TYPE_F32ARR}/{@code TYPE_F64ARR} -- or, under {@code --simd}, a
	 * {@code TYPE_VBLOCK} of v128 lane groups instead ({@link Layout}) -- wasm-GC's
	 * packed float array carries no header to offset past (unlike the JVM's
	 * bare-array-with-header; the class most likely to bite a future port of this file is
	 * assuming one exists here too). Fills {@code dst}'s data array from {@code start},
	 * row-major, one pass, no shared runtime helper (this primitive's typical program has
	 * one or two call sites, each executed many times at ITS OWN call site, so there is
	 * nothing to de-duplicate the way {@code FUNC_READ_PACKED} de-duplicates a call site
	 * that recurs across a whole program).
	 */
	static void compileWiden(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		refCast(ctx, WasmLispCompiler.TYPE_I16ARR);
		int bitsArr = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bitsArr);

		int savedI64Locals = ctx.nextI64Local;
		int n = ctx.allocI64Temp();
		int isFloat16 = ctx.allocI64Temp();
		int start = ctx.allocI64Temp();
		int i = ctx.allocI64Temp();

		getRef(ctx, bitsArr);
		refCast(ctx, WasmLispCompiler.TYPE_I16ARR);
		arrayLen(ctx);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, n);

		emitFormatFlag(ctx, args.get(2), isFloat16);

		WasmExprCompiler.compileExpr(args.get(3), ctx);
		refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
		int dstStruct = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(dstStruct);

		emitStartFlag(ctx, args, 4, start);

		if (ctx.simd) {
			getRef(ctx, dstStruct);
			refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
			structGet(ctx, WasmLispCompiler.TYPE_FARRAY, 1);
			int dataArr = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(dataArr);
			emitWidenLoop(ctx, Layout.VBLOCK, dataArr, bitsArr, n, isFloat16, start, i);
		}
		else {
			getRef(ctx, dstStruct);
			refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
			structGet(ctx, WasmLispCompiler.TYPE_FARRAY, 1);
			refTest(ctx, WasmLispCompiler.TYPE_F32ARR);
			ctx.writer.write(Instruction.IF, 0x40);
			{
				getRef(ctx, dstStruct);
				refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
				structGet(ctx, WasmLispCompiler.TYPE_FARRAY, 1);
				refCast(ctx, WasmLispCompiler.TYPE_F32ARR);
				int dataArr = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(dataArr);
				emitWidenLoop(ctx, Layout.F32ARR, dataArr, bitsArr, n, isFloat16, start, i);
			}
			ctx.writer.write(Instruction.ELSE);
			{
				getRef(ctx, dstStruct);
				refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
				structGet(ctx, WasmLispCompiler.TYPE_FARRAY, 1);
				refCast(ctx, WasmLispCompiler.TYPE_F64ARR);
				int dataArr = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(dataArr);
				emitWidenLoop(ctx, Layout.F64ARR, dataArr, bitsArr, n, isFloat16, start, i);
			}
			ctx.writer.write(Instruction.END);
		}

		getRef(ctx, dstStruct);
		ctx.nextI64Local = savedI64Locals;
	}

	private static void emitWidenLoop(WasmLispCompiler.Ctx ctx, Layout layout, int dataArr, int bitsArr, int n,
			int isFloat16, int start, int i) {
		int bitsVal = ctx.allocI64Temp();
		int u = ctx.allocI64Temp();
		int r = ctx.allocI64Temp();

		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(0);
		setI64(ctx, i);
		ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth += 2;

		getI64(ctx, i);
		getI64(ctx, n);
		ctx.writer.write(Instruction.I64_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);

		// bitsVal = bitsArr[i] (unsigned)
		getRef(ctx, bitsArr);
		refCast(ctx, WasmLispCompiler.TYPE_I16ARR);
		i64ToI32Index(ctx, i);
		arrayGet(ctx, WasmLispCompiler.TYPE_I16ARR, Instruction.ARRAY_GET_U);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, bitsVal);

		// push (dataArr, index) -- the value computed below lands on top, ready for
		// array.set's (arrayref, index, value) stack shape, or for _v_set's identical
		// (eq, i32, f64) one under --simd.
		getRef(ctx, dataArr);
		if (layout != Layout.VBLOCK) {
			refCast(ctx, layout == Layout.F32ARR ? WasmLispCompiler.TYPE_F32ARR : WasmLispCompiler.TYPE_F64ARR);
		}
		getI64(ctx, start);
		getI64(ctx, i);
		ctx.writer.write(Instruction.I64_ADD);
		ctx.writer.write(Instruction.I32_WRAP_I64);

		getI64(ctx, isFloat16);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		ctx.writer.write(Instruction.IF, 0x7D); // (result f32)
		// f16: magic-multiply + MASKED exponent-all-ones fixup (Load.java's
		// f16ToF32Vector shape, not Dec.java variant D's unmasked one -- see class doc).
		loadI32(ctx, bitsVal);
		constI32(ctx, 0x7fff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 13);
		i32(ctx, Instruction.I32_SHL);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, u);
		loadI32(ctx, u);
		ctx.writer.write(Instruction.F32_REINTERPRET_I32);
		ctx.writer.write(Instruction.F32_CONST);
		ctx.writer.writeF32(DECODE_SCALE);
		ctx.writer.write(Instruction.F32_MUL);
		ctx.writer.write(Instruction.I32_REINTERPRET_F32);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, r);
		loadI32(ctx, u);
		constI32(ctx, SE);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, SE);
		i32(ctx, Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF, 0x40);
		loadI32(ctx, r);
		constI32(ctx, INF_FIXUP);
		i32(ctx, Instruction.I32_ADD);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, r);
		ctx.writer.write(Instruction.END);
		loadI32(ctx, r);
		loadI32(ctx, bitsVal);
		constI32(ctx, 0x8000);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHL);
		i32(ctx, Instruction.I32_OR);
		ctx.writer.write(Instruction.F32_REINTERPRET_I32);
		ctx.writer.write(Instruction.ELSE);
		// bf16: the top half of an f32, so widening is a shift.
		loadI32(ctx, bitsVal);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHL);
		ctx.writer.write(Instruction.F32_REINTERPRET_I32);
		ctx.writer.write(Instruction.END);

		switch (layout) {
			case F32ARR -> arraySet(ctx, WasmLispCompiler.TYPE_F32ARR);
			case F64ARR -> {
				ctx.writer.write(Instruction.F64_PROMOTE_F32);
				arraySet(ctx, WasmLispCompiler.TYPE_F64ARR);
			}
			// _v_set owns the width branch (a kind-1 vblock demotes the f64 straight
			// back to the f32 computed above, bit-exact) and returns the value AS
			// STORED, which this loop does not need.
			case VBLOCK -> {
				ctx.writer.write(Instruction.F64_PROMOTE_F32);
				callVec(ctx, WasmVecSimdRuntimeBuilder.V_SET);
				ctx.writer.write(Instruction.DROP);
			}
		}

		getI64(ctx, i);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I64_ADD);
		setI64(ctx, i);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		ctx.wasmCtrlDepth -= 2;
	}

	/**
	 * {@code (rontolisp:narrow-float-bits src format dst &key (start 0))}: the inverse of
	 * {@link #compileWiden}, over the same array shapes.
	 *
	 * <p>
	 * <b>Known simplification for a {@code double-float} source's {@code :bfloat16}
	 * direction</b>: every element is demoted {@code f64 -> f32} once before the bf16
	 * round, exactly like the {@code :float16} direction (binary16's conversion here
	 * starts from a binary32, as the interpreter's {@code Float.floatToFloat16} does).
	 * {@code BFloat16.bits(double)} avoids this by checking the DOUBLE's own NaN bits
	 * before narrowing, so a double source right at a bf16 rounding tie can answer one
	 * ULP differently between backends; nothing here needs bit-for-bit parity with that
	 * function today (NaN payload is "aside" throughout this primitive), so the simpler
	 * shape was kept rather than porting {@code BFloat16}'s full 64-bit NaN detection
	 * into a fourth WASM arm.
	 */
	static void compileNarrow(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
		int srcStruct = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(srcStruct);

		int savedI64Locals = ctx.nextI64Local;
		int isFloat16 = ctx.allocI64Temp();
		int start = ctx.allocI64Temp();
		int i = ctx.allocI64Temp();
		int n = ctx.allocI64Temp();

		emitFormatFlag(ctx, args.get(2), isFloat16);

		WasmExprCompiler.compileExpr(args.get(3), ctx);
		refCast(ctx, WasmLispCompiler.TYPE_I16ARR);
		int dstArr = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(dstArr);

		emitStartFlag(ctx, args, 4, start);

		if (ctx.simd) {
			getRef(ctx, srcStruct);
			refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
			structGet(ctx, WasmLispCompiler.TYPE_FARRAY, 1);
			int dataArr = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(dataArr);
			// A vblock's groups array is PADDED (and carries a sentinel group), so its
			// length is not the element count: the count is the struct's own field 0.
			getRef(ctx, dataArr);
			refCast(ctx, WasmLispCompiler.TYPE_VBLOCK);
			structGet(ctx, WasmLispCompiler.TYPE_VBLOCK, 0);
			ctx.writer.write(Instruction.I64_EXTEND_U_I32);
			setI64(ctx, n);
			emitNarrowLoop(ctx, Layout.VBLOCK, dataArr, dstArr, n, isFloat16, start, i);
		}
		else {
			getRef(ctx, srcStruct);
			refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
			structGet(ctx, WasmLispCompiler.TYPE_FARRAY, 1);
			refTest(ctx, WasmLispCompiler.TYPE_F32ARR);
			ctx.writer.write(Instruction.IF, 0x40);
			{
				getRef(ctx, srcStruct);
				refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
				structGet(ctx, WasmLispCompiler.TYPE_FARRAY, 1);
				refCast(ctx, WasmLispCompiler.TYPE_F32ARR);
				int dataArr = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(dataArr);
				getRef(ctx, dataArr);
				refCast(ctx, WasmLispCompiler.TYPE_F32ARR);
				arrayLen(ctx);
				ctx.writer.write(Instruction.I64_EXTEND_U_I32);
				setI64(ctx, n);
				emitNarrowLoop(ctx, Layout.F32ARR, dataArr, dstArr, n, isFloat16, start, i);
			}
			ctx.writer.write(Instruction.ELSE);
			{
				getRef(ctx, srcStruct);
				refCast(ctx, WasmLispCompiler.TYPE_FARRAY);
				structGet(ctx, WasmLispCompiler.TYPE_FARRAY, 1);
				refCast(ctx, WasmLispCompiler.TYPE_F64ARR);
				int dataArr = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(dataArr);
				getRef(ctx, dataArr);
				refCast(ctx, WasmLispCompiler.TYPE_F64ARR);
				arrayLen(ctx);
				ctx.writer.write(Instruction.I64_EXTEND_U_I32);
				setI64(ctx, n);
				emitNarrowLoop(ctx, Layout.F64ARR, dataArr, dstArr, n, isFloat16, start, i);
			}
			ctx.writer.write(Instruction.END);
		}

		getRef(ctx, dstArr);
		ctx.nextI64Local = savedI64Locals;
	}

	private static void emitNarrowLoop(WasmLispCompiler.Ctx ctx, Layout layout, int srcDataArr, int dstArr, int n,
			int isFloat16, int start, int i) {
		// The source element's f32 bits and the float16 encode's own scratch slots,
		// reused for every element.
		int bits = ctx.allocI64Temp();
		Float16Slots slots = Float16Slots.alloc(ctx);

		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(0);
		setI64(ctx, i);
		ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth += 2;

		getI64(ctx, i);
		getI64(ctx, n);
		ctx.writer.write(Instruction.I64_GE_S);
		ctx.writer.write(Instruction.BR_IF, 1);

		// bits = the source element narrowed to f32 (row-major from 0, no
		// :start offset on the SOURCE side -- narrow-float-bits reads src whole).
		getRef(ctx, srcDataArr);
		switch (layout) {
			case F32ARR -> {
				refCast(ctx, WasmLispCompiler.TYPE_F32ARR);
				i64ToI32Index(ctx, i);
				arrayGet(ctx, WasmLispCompiler.TYPE_F32ARR, Instruction.ARRAY_GET);
			}
			case F64ARR -> {
				refCast(ctx, WasmLispCompiler.TYPE_F64ARR);
				i64ToI32Index(ctx, i);
				arrayGet(ctx, WasmLispCompiler.TYPE_F64ARR, Instruction.ARRAY_GET);
				ctx.writer.write(Instruction.F32_DEMOTE_F64);
			}
			// _v_get answers an f64 at either width (a kind-1 vblock's lane is promoted
			// from the stored f32), so the demote below is exact for a single-float
			// source and is the same narrowing the F64ARR arm does for a double one.
			case VBLOCK -> {
				i64ToI32Index(ctx, i);
				callVec(ctx, WasmVecSimdRuntimeBuilder.V_GET);
				ctx.writer.write(Instruction.F32_DEMOTE_F64);
			}
		}
		ctx.writer.write(Instruction.I32_REINTERPRET_F32);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, bits);

		// push (dstArr, index) -- the encoded i32 bits land on top for array.set.
		getRef(ctx, dstArr);
		refCast(ctx, WasmLispCompiler.TYPE_I16ARR);
		getI64(ctx, start);
		getI64(ctx, i);
		ctx.writer.write(Instruction.I64_ADD);
		ctx.writer.write(Instruction.I32_WRAP_I64);

		getI64(ctx, isFloat16);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32): the bits to store
		emitFloat16Encode(ctx, bits, slots);
		ctx.writer.write(Instruction.ELSE);
		emitBfloat16Encode(ctx, bits);
		ctx.writer.write(Instruction.END);

		arraySet(ctx, WasmLispCompiler.TYPE_I16ARR);

		getI64(ctx, i);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I64_ADD);
		setI64(ctx, i);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		ctx.wasmCtrlDepth -= 2;
	}

	/** The scratch slots of {@link #emitFloat16Encode}, i64 locals holding i32 values. */
	private record Float16Slots(int sign, int mag, int e14, int shift, int signif) {

		static Float16Slots alloc(WasmLispCompiler.Ctx ctx) {
			return new Float16Slots(ctx.allocI64Temp(), ctx.allocI64Temp(), ctx.allocI64Temp(), ctx.allocI64Temp(),
					ctx.allocI64Temp());
		}

	}

	/**
	 * The {@code float16-bits} encode (see the class comment), shared by
	 * {@link #compileBits} and the per-element body of {@link #emitNarrowLoop}.
	 * {@code bits} must already hold the source's f32 bits; leaves the encoded i32
	 * (0..65535) on the stack.
	 */
	private static void emitFloat16Encode(WasmLispCompiler.Ctx ctx, int bits, Float16Slots t) {
		// sign = the sign bit where binary16 keeps it; mag = the bits of |x|
		loadI32(ctx, bits);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 0x8000);
		i32(ctx, Instruction.I32_AND);
		storeI32(ctx, t.sign());
		loadI32(ctx, bits);
		constI32(ctx, 0x7fffffff);
		i32(ctx, Instruction.I32_AND);
		storeI32(ctx, t.mag());

		// |x| <= 2^-25: a zero
		loadI32(ctx, t.mag());
		constI32(ctx, ZERO_THRESHOLD);
		i32(ctx, Instruction.I32_LE_S);
		ctx.writer.write(Instruction.IF, 0x7F);
		loadI32(ctx, t.sign());
		ctx.writer.write(Instruction.ELSE);

		// |x| >= 2^16: an infinity, or a quiet NaN carrying the payload's top ten bits
		loadI32(ctx, t.mag());
		constI32(ctx, OVERFLOW_THRESHOLD);
		i32(ctx, Instruction.I32_GE_S);
		ctx.writer.write(Instruction.IF, 0x7F);
		loadI32(ctx, t.sign());
		constI32(ctx, 0x7c00);
		i32(ctx, Instruction.I32_OR);
		loadI32(ctx, t.mag());
		constI32(ctx, 13);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 0x3ff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0x200);
		i32(ctx, Instruction.I32_OR);
		constI32(ctx, 0);
		loadI32(ctx, t.mag());
		constI32(ctx, F32_INFINITY);
		i32(ctx, Instruction.I32_GT_S);
		ctx.writer.write(Instruction.SELECT);
		i32(ctx, Instruction.I32_OR);
		ctx.writer.write(Instruction.ELSE);

		// e14 = the exponent counted from binary16's smallest normal binade
		loadI32(ctx, t.mag());
		constI32(ctx, 23);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, F16_MIN_NORMAL_EXPONENT);
		i32(ctx, Instruction.I32_SUB);
		storeI32(ctx, t.e14());
		// shift = 13, the bits binary16 does not keep, plus one per exponent below it
		constI32(ctx, 13);
		loadI32(ctx, t.e14());
		i32(ctx, Instruction.I32_SUB);
		constI32(ctx, 13);
		loadI32(ctx, t.e14());
		constI32(ctx, 0);
		i32(ctx, Instruction.I32_LT_S);
		ctx.writer.write(Instruction.SELECT);
		storeI32(ctx, t.shift());
		// signif = the 24-bit significand, implicit bit included
		loadI32(ctx, t.mag());
		constI32(ctx, 0x7fffff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0x800000);
		i32(ctx, Instruction.I32_OR);
		storeI32(ctx, t.signif());

		// sign | ((max(e14, 0) << 10) + rounded), where rounded is signif >> shift to
		// nearest, ties to even: half an ulp less one is added, plus the kept part's
		// last bit, so only an odd one is carried up by an exact half.
		loadI32(ctx, t.sign());
		loadI32(ctx, t.e14());
		constI32(ctx, 0);
		loadI32(ctx, t.e14());
		constI32(ctx, 0);
		i32(ctx, Instruction.I32_GT_S);
		ctx.writer.write(Instruction.SELECT);
		constI32(ctx, 10);
		i32(ctx, Instruction.I32_SHL);
		loadI32(ctx, t.signif());
		constI32(ctx, 1);
		loadI32(ctx, t.shift());
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SUB);
		i32(ctx, Instruction.I32_SHL);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SUB);
		i32(ctx, Instruction.I32_ADD);
		loadI32(ctx, t.signif());
		loadI32(ctx, t.shift());
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_AND);
		i32(ctx, Instruction.I32_ADD);
		loadI32(ctx, t.shift());
		i32(ctx, Instruction.I32_SHR_U);
		i32(ctx, Instruction.I32_ADD);
		i32(ctx, Instruction.I32_OR);

		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * The bf16 round-to-nearest-even narrow, over an f32 bit pattern already in
	 * {@code bits}: {@code (bits + 0x7fff + lsb) >>> 16}, NaN special-cased (a plain
	 * bias-add can carry a heavy-payload NaN's low bits into the sign). Leaves the
	 * encoded i32 (0..65535) on the stack.
	 */
	private static void emitBfloat16Encode(WasmLispCompiler.Ctx ctx, int bits) {
		loadI32(ctx, bits);
		constI32(ctx, 23);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 0xff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0xff);
		i32(ctx, Instruction.I32_EQ);
		loadI32(ctx, bits);
		constI32(ctx, 0x7fffff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0);
		i32(ctx, Instruction.I32_NE);
		i32(ctx, Instruction.I32_AND);

		ctx.writer.write(Instruction.IF, 0x7F);
		// u = bits >>> 16 (the top sixteen bits ARE already sign + all-ones exponent +
		// the payload's top seven bits -- a direct copy, not force-quieted) | a
		// correction bit that fires only when u's payload is all-zero (which would
		// otherwise read back as infinity, not NaN): ((u & 0x7f) - 1) >>> 31. NOT
		// `u | 0x40` (this file's first version) -- that forces the quiet bit on every
		// NaN, which quiets a signalling one exactly as often as the double-detour bug
		// this pair otherwise avoids (126/65536, measured): a different way to lose the
		// same patterns, not a fix.
		loadI32(ctx, bits);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHR_U);
		loadI32(ctx, bits);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 0x7f);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SUB);
		constI32(ctx, 31);
		i32(ctx, Instruction.I32_SHR_U);
		i32(ctx, Instruction.I32_OR);
		ctx.writer.write(Instruction.ELSE);
		loadI32(ctx, bits);
		constI32(ctx, 0x7fff);
		i32(ctx, Instruction.I32_ADD);
		loadI32(ctx, bits);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_AND);
		i32(ctx, Instruction.I32_ADD);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 0xffff);
		i32(ctx, Instruction.I32_AND);
		ctx.writer.write(Instruction.END);
	}

	private static void emitFormatFlag(WasmLispCompiler.Ctx ctx, LispVal formatExpr, int isFloat16Slot) {
		WasmEmitHelper.compileStringLiteral(":FLOAT16", ctx);
		WasmExprCompiler.compileExpr(formatExpr, ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_STRING_EQUAL);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, isFloat16Slot);
	}

	private static void emitStartFlag(WasmLispCompiler.Ctx ctx, List<LispVal> args, int from, int startSlot) {
		LispVal startExpr = null;
		for (int idx = from; idx + 1 < args.size(); idx += 2) {
			if (args.get(idx) instanceof am.ik.rontolisp.LispSymbol kw
					&& am.ik.rontolisp.LispNames.START_KEYWORD.equals(kw.name())) {
				startExpr = args.get(idx + 1);
			}
		}
		if (startExpr != null) {
			WasmExprCompiler.compileExpr(startExpr, ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I64_EXTEND_S_I32);
		}
		else {
			ctx.writer.write(Instruction.I64_CONST);
			ctx.writer.writeSignedLeb128(0);
		}
		setI64(ctx, startSlot);
	}

	private static void getRef(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void i64ToI32Index(WasmLispCompiler.Ctx ctx, int i64Slot) {
		getI64(ctx, i64Slot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
	}

	private static void setI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void getI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void refCast(WasmLispCompiler.Ctx ctx, int heapType) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(heapType);
	}

	private static void refTest(WasmLispCompiler.Ctx ctx, int heapType) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(heapType);
	}

	private static void structGet(WasmLispCompiler.Ctx ctx, int type, int field) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(type);
		ctx.writer.writeUnsignedLeb128(field);
	}

	private static void arrayLen(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

	private static void arrayGet(WasmLispCompiler.Ctx ctx, int type, int opcode) {
		ctx.writer.write(Instruction.GC_PREFIX, opcode);
		ctx.writer.writeUnsignedLeb128(type);
	}

	/**
	 * A call to one of the {@code --simd} vec runtime helpers
	 * ({@code _v_get}/{@code _v_set}).
	 */
	private static void callVec(WasmLispCompiler.Ctx ctx, int index) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + index);
	}

	private static void arraySet(WasmLispCompiler.Ctx ctx, int type) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(type);
	}

	/**
	 * An {@code i64} scratch slot is named by a PLACEHOLDER index the function's second
	 * locals run splices out, never by a raw one ({@code .kb/wasm-unboxed-locals.md}).
	 */
	private static void storeI32(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void loadI32(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
	}

	private static void constI32(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	private static void i32(WasmLispCompiler.Ctx ctx, int opcode) {
		ctx.writer.write(opcode);
	}

}
