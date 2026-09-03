package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code rontolisp:float16-bits} / {@code rontolisp:bits-float16} (.todo/671):
 * sixteen bits fit an i31 fixnum, so -- like {@link WasmBFloat16Compiler} beside it --
 * this is a real primitive here, not a call-time signal the {@code %ieee754-*} quartet
 * needs (that pair's 32/64-bit unsigned model has no room in this numeric model).
 *
 * <p>
 * {@link #compileFromBits} (the DECODE direction, bits -&gt; real) is the magic-multiply
 * plus a MASKED "exponent field is all-ones" fixup -- {@code .todo/482}'s
 * {@code Load.java} shape, not {@code Dec.java}'s variant D as literally written (that
 * one's fixup mask was found wrong for NaN while building this; see the fixed
 * {@code Dec.java} and {@code .todo/482}'s README section 4 re-evaluation note).
 *
 * <p>
 * {@link #compileBits} (the ENCODE direction, real -&gt; bits, round-to-nearest-even) is
 * a BRANCHLESS reformulation of a literal port of {@code java.lang.Float.floatToFloat16}
 * (JDK 20+, {@code java.base/java/lang/Float.java}): the three top-level cases (NaN,
 * overflow, underflow-to-zero) are still an if/else-if/else chain (their RESULT shapes
 * differ), but the "normal" branch's own internal subnormal-vs-normal split is turned
 * into arithmetic (a {@code shift}/{@code msb}/{@code biasedExpField} formula scaled by
 * the 0/1 {@code isSubnormal} flag, and the round-to-nearest-even increment as a 0/1
 * multiply) rather than nested branches -- verified exhaustively against the original
 * branchy port over all 2^32 float32 inputs (NaN payload included; {@code .todo/482}'s
 * {@code Enc.java} test harness) before being transliterated here. Two branchless
 * "magic-multiply" alternatives were tried and abandoned because they round
 * DENORMAL-target ties wrongly (same file).
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

	/** The overflow threshold: bits of {@code (0x1.ffcp15f + 0x0.002p15f)}. */
	private static final int OVERFLOW_THRESHOLD = 0x477ff000;

	/** The zero/subnormal-source threshold: bits of {@code (0x1.0p-24f * 0.5f)}. */
	private static final int ZERO_THRESHOLD = 0x33000000;

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
		int doppel = ctx.allocI64Temp();
		int signBit = ctx.allocI64Temp();
		int absBits = ctx.allocI64Temp();
		int exp = ctx.allocI64Temp();
		int isSubnormal = ctx.allocI64Temp();
		int shift = ctx.allocI64Temp();
		int fSignifBits = ctx.allocI64Temp();
		storeI32(ctx, doppel);

		emitFloat16Encode(ctx, doppel, signBit, absBits, exp, isSubnormal, shift, fSignifBits);

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
	 * a bare {@code TYPE_F32ARR}/{@code TYPE_F64ARR} -- wasm-GC's packed float array
	 * carries no header to offset past (unlike the JVM's bare-array-with-header; the
	 * class most likely to bite a future port of this file is assuming one exists here
	 * too). Fills {@code dst}'s data array from {@code start}, row-major, one pass, no
	 * shared runtime helper (this primitive's typical program has one or two call sites,
	 * each executed many times at ITS OWN call site, so there is nothing to de-duplicate
	 * the way {@code FUNC_READ_PACKED} de-duplicates a call site that recurs across a
	 * whole program).
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
			emitWidenLoop(ctx, true, dataArr, bitsArr, n, isFloat16, start, i);
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
			emitWidenLoop(ctx, false, dataArr, bitsArr, n, isFloat16, start, i);
		}
		ctx.writer.write(Instruction.END);

		getRef(ctx, dstStruct);
		ctx.nextI64Local = savedI64Locals;
	}

	private static void emitWidenLoop(WasmLispCompiler.Ctx ctx, boolean single, int dataArr, int bitsArr, int n,
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
		// array.set's (arrayref, index, value) stack shape.
		getRef(ctx, dataArr);
		refCast(ctx, single ? WasmLispCompiler.TYPE_F32ARR : WasmLispCompiler.TYPE_F64ARR);
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

		if (!single) {
			ctx.writer.write(Instruction.F64_PROMOTE_F32);
		}
		arraySet(ctx, single ? WasmLispCompiler.TYPE_F32ARR : WasmLispCompiler.TYPE_F64ARR);

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
	 * round, exactly like the {@code :float16} direction (whose JDK reference,
	 * {@code Float.floatToFloat16}, only ever takes a {@code float}). {@code .todo/487}'s
	 * {@code BFloat16.bits(double)} avoids this by checking the DOUBLE's own NaN bits
	 * before narrowing, so a double source right at a bf16 rounding tie can answer one
	 * ULP differently between backends; nothing here needs bit-for-bit parity with that
	 * function today (NaN payload is "aside" throughout this primitive, per
	 * {@code .todo/671}'s Verify section), so the simpler shape was kept rather than
	 * porting {@code BFloat16}'s full 64-bit NaN detection into a fourth WASM arm.
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
			emitNarrowLoop(ctx, true, dataArr, dstArr, n, isFloat16, start, i);
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
			emitNarrowLoop(ctx, false, dataArr, dstArr, n, isFloat16, start, i);
		}
		ctx.writer.write(Instruction.END);

		getRef(ctx, dstArr);
		ctx.nextI64Local = savedI64Locals;
	}

	private static void emitNarrowLoop(WasmLispCompiler.Ctx ctx, boolean single, int srcDataArr, int dstArr, int n,
			int isFloat16, int start, int i) {
		// The branchless float16-bits encode's own scratch slots (see compileBits),
		// re-run once per element.
		int doppel = ctx.allocI64Temp();
		int signBit = ctx.allocI64Temp();
		int absBits = ctx.allocI64Temp();
		int exp = ctx.allocI64Temp();
		int isSubnormal = ctx.allocI64Temp();
		int shift = ctx.allocI64Temp();
		int fSignifBits = ctx.allocI64Temp();

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

		// doppel = bits of the source element narrowed to f32 (row-major from 0, no
		// :start offset on the SOURCE side -- narrow-float-bits reads src whole).
		getRef(ctx, srcDataArr);
		refCast(ctx, single ? WasmLispCompiler.TYPE_F32ARR : WasmLispCompiler.TYPE_F64ARR);
		i64ToI32Index(ctx, i);
		arrayGet(ctx, single ? WasmLispCompiler.TYPE_F32ARR : WasmLispCompiler.TYPE_F64ARR, Instruction.ARRAY_GET);
		if (!single) {
			ctx.writer.write(Instruction.F32_DEMOTE_F64);
		}
		ctx.writer.write(Instruction.I32_REINTERPRET_F32);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, doppel);

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
		emitFloat16Encode(ctx, doppel, signBit, absBits, exp, isSubnormal, shift, fSignifBits);
		ctx.writer.write(Instruction.ELSE);
		emitBfloat16Encode(ctx, doppel);
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

	/**
	 * The branchless {@code float16-bits} encode (see {@link #compileBits}), factored out
	 * so {@link #emitNarrowLoop} can run it per element without duplicating the
	 * ~30-instruction body. {@code doppel} must already hold the source's f32 bits;
	 * leaves the encoded i32 (0..65535) on the stack.
	 */
	private static void emitFloat16Encode(WasmLispCompiler.Ctx ctx, int doppel, int signBit, int absBits, int exp,
			int isSubnormal, int shift, int fSignifBits) {
		loadI32(ctx, doppel);
		constI32(ctx, 0x80000000);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHR_U);
		storeI32(ctx, signBit);

		loadI32(ctx, doppel);
		constI32(ctx, 23);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 0xff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0xff);
		i32(ctx, Instruction.I32_EQ);
		loadI32(ctx, doppel);
		constI32(ctx, 0x7fffff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0);
		i32(ctx, Instruction.I32_NE);
		i32(ctx, Instruction.I32_AND);

		ctx.writer.write(Instruction.IF, 0x7F);
		loadI32(ctx, signBit);
		constI32(ctx, 0x7c00);
		i32(ctx, Instruction.I32_OR);
		loadI32(ctx, doppel);
		constI32(ctx, 0x7fe000);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 13);
		i32(ctx, Instruction.I32_SHR_U);
		i32(ctx, Instruction.I32_OR);
		loadI32(ctx, doppel);
		constI32(ctx, 0x1ff0);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 4);
		i32(ctx, Instruction.I32_SHR_U);
		i32(ctx, Instruction.I32_OR);
		loadI32(ctx, doppel);
		constI32(ctx, 0xf);
		i32(ctx, Instruction.I32_AND);
		i32(ctx, Instruction.I32_OR);
		ctx.writer.write(Instruction.ELSE);

		loadI32(ctx, doppel);
		constI32(ctx, 0x7fffffff);
		i32(ctx, Instruction.I32_AND);
		storeI32(ctx, absBits);

		loadI32(ctx, absBits);
		constI32(ctx, OVERFLOW_THRESHOLD);
		i32(ctx, Instruction.I32_GE_S);
		ctx.writer.write(Instruction.IF, 0x7F);
		loadI32(ctx, signBit);
		constI32(ctx, 0x7c00);
		i32(ctx, Instruction.I32_OR);
		ctx.writer.write(Instruction.ELSE);

		loadI32(ctx, absBits);
		constI32(ctx, ZERO_THRESHOLD);
		i32(ctx, Instruction.I32_LE_S);
		ctx.writer.write(Instruction.IF, 0x7F);
		loadI32(ctx, signBit);
		ctx.writer.write(Instruction.ELSE);

		loadI32(ctx, absBits);
		constI32(ctx, 23);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 0xff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 127);
		i32(ctx, Instruction.I32_SUB);
		storeI32(ctx, exp);

		loadI32(ctx, exp);
		constI32(ctx, -14);
		i32(ctx, Instruction.I32_LT_S);
		storeI32(ctx, isSubnormal);

		constI32(ctx, 13);
		loadI32(ctx, isSubnormal);
		constI32(ctx, -14);
		loadI32(ctx, exp);
		i32(ctx, Instruction.I32_SUB);
		i32(ctx, Instruction.I32_MUL);
		i32(ctx, Instruction.I32_ADD);
		storeI32(ctx, shift);

		loadI32(ctx, absBits);
		constI32(ctx, 0x7fffff);
		i32(ctx, Instruction.I32_AND);
		loadI32(ctx, isSubnormal);
		constI32(ctx, 0x800000);
		i32(ctx, Instruction.I32_MUL);
		i32(ctx, Instruction.I32_OR);
		storeI32(ctx, fSignifBits);

		loadI32(ctx, signBit);
		loadI32(ctx, exp);
		constI32(ctx, 15);
		i32(ctx, Instruction.I32_ADD);
		constI32(ctx, 1);
		loadI32(ctx, isSubnormal);
		i32(ctx, Instruction.I32_SUB);
		i32(ctx, Instruction.I32_MUL);
		constI32(ctx, 10);
		i32(ctx, Instruction.I32_SHL);
		loadI32(ctx, fSignifBits);
		loadI32(ctx, shift);
		i32(ctx, Instruction.I32_SHR_U);
		loadI32(ctx, fSignifBits);
		loadI32(ctx, shift);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SUB);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SHL);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0);
		i32(ctx, Instruction.I32_NE);
		loadI32(ctx, fSignifBits);
		loadI32(ctx, shift);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SHL);
		i32(ctx, Instruction.I32_AND);
		loadI32(ctx, fSignifBits);
		loadI32(ctx, shift);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SUB);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SHL);
		constI32(ctx, 1);
		i32(ctx, Instruction.I32_SUB);
		i32(ctx, Instruction.I32_AND);
		i32(ctx, Instruction.I32_OR);
		constI32(ctx, 0);
		i32(ctx, Instruction.I32_NE);
		i32(ctx, Instruction.I32_AND);
		i32(ctx, Instruction.I32_ADD);
		i32(ctx, Instruction.I32_ADD);
		i32(ctx, Instruction.I32_OR);

		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * The bf16 round-to-nearest-even narrow, over an f32 bit pattern already in
	 * {@code doppel}: {@code (bits + 0x7fff + lsb) >>> 16}, NaN special-cased (a plain
	 * bias-add can carry a heavy-payload NaN's low bits into the sign --
	 * {@code .todo/482}'s {@code Enc.java} note). Leaves the encoded i32 (0..65535) on
	 * the stack.
	 */
	private static void emitBfloat16Encode(WasmLispCompiler.Ctx ctx, int doppel) {
		loadI32(ctx, doppel);
		constI32(ctx, 23);
		i32(ctx, Instruction.I32_SHR_U);
		constI32(ctx, 0xff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0xff);
		i32(ctx, Instruction.I32_EQ);
		loadI32(ctx, doppel);
		constI32(ctx, 0x7fffff);
		i32(ctx, Instruction.I32_AND);
		constI32(ctx, 0);
		i32(ctx, Instruction.I32_NE);
		i32(ctx, Instruction.I32_AND);

		ctx.writer.write(Instruction.IF, 0x7F);
		// u = doppel >>> 16 (the top sixteen bits ARE already sign + all-ones exponent +
		// the payload's top seven bits -- a direct copy, not force-quieted) | a
		// correction bit that fires only when u's payload is all-zero (which would
		// otherwise read back as infinity, not NaN): ((u & 0x7f) - 1) >>> 31. NOT
		// `u | 0x40` (this file's first version) -- that forces the quiet bit on every
		// NaN, which quiets a signalling one exactly as often as the double-detour bug
		// this pair otherwise avoids (126/65536, measured): a different way to lose the
		// same patterns, not a fix.
		loadI32(ctx, doppel);
		constI32(ctx, 16);
		i32(ctx, Instruction.I32_SHR_U);
		loadI32(ctx, doppel);
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
		loadI32(ctx, doppel);
		constI32(ctx, 0x7fff);
		i32(ctx, Instruction.I32_ADD);
		loadI32(ctx, doppel);
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
