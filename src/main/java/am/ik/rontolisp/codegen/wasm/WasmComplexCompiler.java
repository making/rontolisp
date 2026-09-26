package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispComplex;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmFdlibmRuntimeBuilder.Fn;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the complex-number built-ins and the complex-aware steering of the real
 * operators. A complex value is a {@code TYPE_COMPLEX} struct (an {@code i32} tag plus
 * two real-part refs); construction canonicalizes through {@code _ccomplex}
 * ({@code WasmComplexRuntimeBuilder}), and every site below steers on
 * {@code LispMacroExpander.containsComplex} -- a {@code LispComplex} literal or a
 * {@code complex}/{@code conjugate} call in the tree -- the same syntactic gate the JVM
 * backend steers on ({@code .kb/jvm-complex.md}). A complex arriving only through a
 * variable beside a real operator takes that operator's ordinary path (documented in
 * {@code .kb/wasm-complex.md}), never a silently wrong number.
 *
 * <p>
 * Float parts are coerced through the ONE shared {@code _as_f64}
 * ({@code .kb/wasm-shared-coercion.md}); exact parts fold through the shared
 * {@code _rat_*} helpers, so funnels match real arithmetic. The transcendental formulas
 * are the interpreter's term for term, over the fdlibm runtime
 * ({@link WasmTranscendentalCompiler}) -- the same bits as {@code StrictMath} on the
 * other backends.
 */
final class WasmComplexCompiler {

	private WasmComplexCompiler() {
	}

	// A canonical complex literal in code position: the reader already demoted a
	// rational-zero imaginary part, so the parts emit plus struct.new is the value.
	static void compileLiteral(LispComplex c, WasmLispCompiler.Ctx ctx) {
		emitComplexTag(ctx);
		WasmExprCompiler.compileExpr(c.real(), ctx);
		WasmExprCompiler.compileExpr(c.imag(), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
	}

	// (complex re [im]): canonicalize through _ccomplex (a missing im is the i31
	// zero; a non-real part lands in _type_err_num there).
	static void compileComplex(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() < 2 || args.size() > 3) {
			throw new UnsupportedOperationException(
					"complex expects a real part and an optional imaginary part, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		if (args.size() == 3) {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
		}
		else {
			constI32(ctx, 0);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		call(ctx, WasmLispCompiler.FUNC_C_COMPLEX);
	}

	// (complexp x): one ref.test, like floatp.
	static void compileComplexp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// (realp x): every real tier (an exact integer, a ratio, a float) -- never a
	// complex.
	static void compileRealp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestReal(ctx, slot);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// (realpart x): field 0 for a complex, the value itself for a real, _type_err_num
	// ("The value <prin1> is not of type NUMBER") otherwise -- the interpreter's
	// requireReal.
	static void compileRealpart(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		pushPart(ctx, slot, 0);
		ctx.writer.write(Instruction.ELSE);
		emitTestReal(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (imagpart x): field 1 for a complex, (* 0 x) for a float, the i31 zero
	// for any other real, _type_err_num otherwise (CLHS: imagpart of a real IS
	// (* 0 number), so a negative float answers -0.0, like SBCL).
	static void compileImagpart(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		pushPart(ctx, slot, 1);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// CLHS: (imagpart x) of a real IS (* 0 x) -- multiply the unboxed value
		// by 0.0 so a negative float answers -0.0.
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.ELSE);
		emitTestExactOrRatio(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		constI32(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (conjugate x): a complex negates its imaginary part (a float part through
	// f64.neg, an exact part through _rat_sub from zero); a real answers itself; a
	// non-real lands in _type_err_num.
	static void compileConjugate(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int imSlot = ctx.allocTemp();
		pushPart(ctx, slot, 1);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imSlot);
		int reSlot = ctx.allocTemp();
		pushPart(ctx, slot, 0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reSlot);
		getLocal(ctx, imSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, imSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_NEG);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.ELSE);
		constI32(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		getLocal(ctx, imSlot);
		call(ctx, WasmLispCompiler.FUNC_RAT_SUB);
		ctx.writer.write(Instruction.END);
		int negSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(negSlot);
		emitComplexTag(ctx);
		getLocal(ctx, reSlot);
		getLocal(ctx, negSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		emitTestReal(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (phase x): atan2(im, re) for a complex (fdlibm's atan2, the interpreter's
	// Math.atan2); 0.0 for a non-negative real, pi for a negative one. A non-number
	// lands in _type_err_num through _as_f64.
	static void compilePhase(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int out = ctx.allocTemp();
		emitAtan2Into(ctx, parts[1], parts[0], out);
		getLocal(ctx, out);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		int fSlot = ctx.allocTemp();
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fSlot);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		f64Const(ctx, Math.PI);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.ELSE);
		f64Const(ctx, 0.0);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// An n-ary + - * / form carrying a syntactic complex: unary - is _cneg, unary /
	// the reciprocal through _cdiv, otherwise a left fold through the pairwise
	// helper (the interpreter's loop order, so float rounding matches).
	static void compileArith(LispCons cons, WasmLispCompiler.Ctx ctx, int complexFunc) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			if (complexFunc == WasmLispCompiler.FUNC_C_SUB) {
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				call(ctx, WasmLispCompiler.FUNC_C_NEG);
				return;
			}
			if (complexFunc == WasmLispCompiler.FUNC_C_DIV) {
				constI32(ctx, 1);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				call(ctx, WasmLispCompiler.FUNC_C_DIV);
				return;
			}
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		for (int i = 2; i < args.size(); i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			call(ctx, complexFunc);
		}
	}

	// An = form carrying a syntactic complex, any arity: every adjacent pair must
	// compare equal, each pair part-wise when a complex is present at run time
	// (real pairs fall back to _rat_cmp_bits, which handles every real tier
	// including floats). A single argument is true.
	static void compileEqual(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.DROP);
			WasmEmitHelper.emitTrue(ctx);
			return;
		}
		int count = args.size() - 1;
		int[] slots = new int[count];
		for (int i = 0; i < count; i++) {
			WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		emitEqPair(ctx, slots[0], slots[1]);
		for (int i = 2; i < count; i++) {
			emitEqPair(ctx, slots[i - 1], slots[i]);
			ctx.writer.write(Instruction.I32_AND);
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// A /= form carrying a syntactic complex, any arity: every pair must compare
	// unequal (CL's /= is over all pairs, not just adjacent ones). A single
	// argument evaluates it for effect and answers true.
	static void compileNotEqual(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.DROP);
			WasmEmitHelper.emitTrue(ctx);
			return;
		}
		int count = args.size() - 1;
		int[] slots = new int[count];
		for (int i = 0; i < count; i++) {
			WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		boolean first = true;
		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				emitEqPair(ctx, slots[i], slots[j]);
				ctx.writer.write(Instruction.I32_EQZ);
				if (!first) {
					ctx.writer.write(Instruction.I32_AND);
				}
				first = false;
			}
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// An ordering form (< > <= >=) carrying a syntactic complex, any arity: every
	// adjacent pair must satisfy the relation; a complex in any pair lands in
	// _type_err_real ("The value <prin1> is not of type REAL"). A single argument is
	// true.
	static void compileOrdering(LispCons cons, WasmLispCompiler.Ctx ctx, int mask) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.DROP);
			WasmEmitHelper.emitTrue(ctx);
			return;
		}
		int count = args.size() - 1;
		int[] slots = new int[count];
		for (int i = 0; i < count; i++) {
			WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		emitCmpPair(ctx, slots[0], slots[1], mask);
		for (int i = 2; i < count; i++) {
			emitCmpPair(ctx, slots[i - 1], slots[i], mask);
			ctx.writer.write(Instruction.I32_AND);
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// The two-real-operands guard, over the two operand slots the caller already
	// filled: a complex in either lands in _type_err_real (the interpreter's
	// "The value <prin1> is not of type REAL", caught as a simple-error on this
	// backend, like every instance-less throw). min/max and the two-argument atan
	// share it.
	static void emitRealOperandGuard(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot) {
		emitTestComplex(ctx, aSlot);
		emitTestComplex(ctx, bSlot);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, 0x40);
		emitTestComplex(ctx, aSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, aSlot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, bSlot);
		ctx.writer.write(Instruction.END);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
	}

	// (abs x) with a syntactic complex: the float modulus (a float even for exact
	// parts, like SBCL), scaled to avoid overflowing the squaring.
	static void compileAbs(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		int[] parts = emitPartsF64(ctx, slot);
		emitHypotInto(ctx, parts[0], parts[1], slot);
		getLocal(ctx, slot);
	}

	// (sqrt x), always complex-aware: a complex roots through the float formula, a
	// negative real roots into the plane as (0, sqrt(-x)) -- the interpreter's
	// shape -- and anything else takes the native f64.sqrt into a float.
	static void compileSqrt(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		emitComplexSqrtInto(ctx, parts[0], parts[1], reOut, imOut);
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		int fSlot = ctx.allocTemp();
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fSlot);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		f64Const(ctx, 0.0);
		WasmEmitHelper.boxF64(ctx);
		int zeroSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(zeroSlot);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		ctx.writer.write(Instruction.F64_NEG);
		ctx.writer.write(Instruction.F64_SQRT);
		WasmEmitHelper.boxF64(ctx);
		int rootSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rootSlot);
		emitComplexTag(ctx);
		getLocal(ctx, zeroSlot);
		getLocal(ctx, rootSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		ctx.writer.write(Instruction.F64_SQRT);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (expt base exp) with a syntactic complex: an i31 exponent over the exact
	// loop (repeated squaring through _cmul, a negative one through the exact
	// reciprocal -- an integer power over rational parts stays exact); anything
	// else through exp(w*log(z)) in floats.
	static void compileExpt(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int baseSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int expSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(expSlot);
		int rSlot = ctx.allocTemp();
		// The exact loop takes an i31 exponent over an EXACT base -- the interpreter's
		// exptComplex: a float part anywhere in the base goes through exp(w*log(z)) like
		// a float exponent does, so (expt #c(1.0 2.0) 3) answers the same bits here as
		// there rather than the squaring loop's.
		getLocal(ctx, expSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		emitTestFloatBase(ctx, baseSlot);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF, 0x40);
		emitExptExactLoop(ctx, baseSlot, expSlot, rSlot);
		ctx.writer.write(Instruction.ELSE);
		emitExptFloat(ctx, baseSlot, expSlot, rSlot);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, rSlot);
	}

	// Pushes `slot holds a float, or a complex with a float part` as an i32.
	private static void emitTestFloatBase(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		for (int field = 0; field < 2; field++) {
			pushPart(ctx, slot, field);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		}
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.ELSE);
		constI32(ctx, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.I32_OR);
	}

	// The eleven float unary functions over a syntactic complex, each formula the
	// interpreter's, in f64 over the fdlibm runtime.
	// (asinh x): the plane arm is the interpreter's formula below; a real operand
	// never leaves the real line (asinh's real domain is all of it) and takes the
	// real arm.
	static void compileAsinh(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		emitComplexAsinhInto(ctx, parts[0], parts[1], reOut, imOut);
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmInverseHypCompiler.emitAsinhRealF64(ctx);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
	}

	// (acosh x): x >= 1 (or NaN) takes the real core; x < 1 escapes into the plane
	// at (x, +0.0), like the sqrt site's negative root.
	static void compileAcosh(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		emitComplexAcoshInto(ctx, parts[0], parts[1], reOut, imOut);
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmEmitHelper.boxF64(ctx);
		int fSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fSlot);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// x < 1: the plane arm at (x, +0.0) -- building the temporary complex and
		// running it, not a bare (x, 0) pair: acosh(0) answers (0, pi/2), not (0, 0).
		emitComplexTag(ctx);
		getLocal(ctx, fSlot);
		f64Const(ctx, 0.0);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		int escSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(escSlot);
		int[] escParts = emitPartsF64(ctx, escSlot);
		int escRe = ctx.allocTemp();
		int escIm = ctx.allocTemp();
		emitComplexAcoshInto(ctx, escParts[0], escParts[1], escRe, escIm);
		emitComplexTag(ctx);
		getLocal(ctx, escRe);
		getLocal(ctx, escIm);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		WasmInverseHypCompiler.emitAcoshRealF64(ctx);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (log x): a non-negative real (a NaN too -- f64.lt answers false for one) takes
	// the software log core; a negative one escapes into the plane at (x, +0.0),
	// where it answers (log |x|, pi) instead of NaN. Zero keeps -Infinity, the core's
	// own answer.
	static void compileLog(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2 && args.size() != 3) {
			throw new UnsupportedOperationException("log expects 1 or 2 arguments, got " + (args.size() - 1));
		}
		compileLogOf(args.get(1), ctx);
	}

	/**
	 * The complex-capable {@code log} of one argument FORM, leaving the boxed result --
	 * the shape the two-argument {@code (log n base)} needs, which compiles two
	 * logarithms out of one call form.
	 * @param arg the argument form
	 * @param ctx the compile context
	 */
	static void compileLogOf(LispVal arg, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(arg, ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		emitComplexLogInto(ctx, parts[0], parts[1], reOut, imOut);
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		int fSlot = emitRealAsF64Box(ctx, slot);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		emitPlaneArmAt(ctx, fSlot, am.ik.rontolisp.LispNames.LOG);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (log n base): the quotient of the two logarithms, each taking the real-domain
	// escape on its own -- the very pair (/ (log n) (log base)) would reach. The
	// complex-capable spelling divides through _c_div; the real one is a plain
	// f64.div, so (log 8 2) pulls in no complex runtime at all.
	static void compileLogBase(LispCons cons, WasmLispCompiler.Ctx ctx, boolean complexCapable) {
		List<LispVal> args = cons.toList();
		if (complexCapable) {
			compileLogOf(args.get(1), ctx);
			compileLogOf(args.get(2), ctx);
			call(ctx, WasmLispCompiler.FUNC_C_DIV);
			return;
		}
		WasmTranscendentalCompiler.compileArg(args.get(1), ctx, Fn.LOG);
		int numSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(numSlot);
		WasmTranscendentalCompiler.compileArg(args.get(2), ctx, Fn.LOG);
		int denSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(denSlot);
		WasmEmitHelper.unboxF64Local(ctx, numSlot);
		WasmEmitHelper.unboxF64Local(ctx, denSlot);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
	}

	// (atan y x): C's atan2, the angle of the vector (x, y) over the full circle --
	// which IS the phase of x + yi, so it runs phase's own quadrant assembly rather
	// than a second one. Both arguments must be REAL (CLHS): a syntactic complex
	// lands in _type_err_real, the guard min/max signals through.
	static void compileAtan2(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int ySlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(ySlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int xSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		if (hasComplex(cons)) {
			emitRealOperandGuard(ctx, ySlot, xSlot);
		}
		int yBox = emitRealAsF64Box(ctx, ySlot);
		int xBox = emitRealAsF64Box(ctx, xSlot);
		int out = ctx.allocTemp();
		emitAtan2Into(ctx, yBox, xBox, out);
		getLocal(ctx, out);
	}

	// (asin x) / (acos x): |x| <= 1 (a NaN too -- f64.gt answers false for one) takes
	// the software real formula; beyond, x escapes into the plane at (x, +0.0), where
	// Kahan's form answers the value on the branch cut instead of NaN.
	static void compileAsinAcos(LispCons cons, WasmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(name + " expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		if (am.ik.rontolisp.LispNames.ASIN.equals(name)) {
			emitComplexAsinInto(ctx, parts[0], parts[1], reOut, imOut);
		}
		else {
			emitComplexAcosInto(ctx, parts[0], parts[1], reOut, imOut);
		}
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		int fSlot = emitRealAsF64Box(ctx, slot);
		// |x| > 1 AND finite. An infinity has no complex asin/acos either (the formula
		// would manufacture a #C(NaN Infinity)), so it keeps the real arm's NaN with
		// the NaN itself -- f64.gt and f64.lt both answer false for a NaN.
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_GT);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		emitPlaneArmAt(ctx, fSlot, name);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		WasmTranscendentalCompiler.call(ctx, am.ik.rontolisp.LispNames.ASIN.equals(name) ? Fn.ASIN : Fn.ACOS);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// Coerces the real in `slot` to an f64 and boxes it into a fresh temporary, whose
	// slot index is returned -- the shape every escape arm below reads its argument
	// from.
	private static int emitRealAsF64Box(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmEmitHelper.boxF64(ctx);
		int fSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fSlot);
		return fSlot;
	}

	// Runs one complex arm over the temporary complex (x, +0.0) built from the boxed
	// f64 in fSlot, leaving the resulting complex on the stack. Building the value and
	// running the arm is what carries the branch cut: (asin 2) is not (asin 2, 0) with
	// a zero imaginary part bolted on, it is the plane formula's answer there.
	private static void emitPlaneArmAt(WasmLispCompiler.Ctx ctx, int fSlot, String name) {
		emitComplexTag(ctx);
		getLocal(ctx, fSlot);
		f64Const(ctx, 0.0);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		int escSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(escSlot);
		int[] escParts = emitPartsF64(ctx, escSlot);
		int escRe = ctx.allocTemp();
		int escIm = ctx.allocTemp();
		switch (name) {
			case am.ik.rontolisp.LispNames.LOG -> emitComplexLogInto(ctx, escParts[0], escParts[1], escRe, escIm);
			case am.ik.rontolisp.LispNames.ASIN -> emitComplexAsinInto(ctx, escParts[0], escParts[1], escRe, escIm);
			case am.ik.rontolisp.LispNames.ACOS -> emitComplexAcosInto(ctx, escParts[0], escParts[1], escRe, escIm);
			default -> throw new IllegalArgumentException("not a real-domain escape: " + name);
		}
		emitComplexTag(ctx);
		getLocal(ctx, escRe);
		getLocal(ctx, escIm);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
	}

	/**
	 * The real-base {@code expt} escape: with the modulus {@code |x|^y} boxed in
	 * {@code modulusSlot} and the exponent {@code y} boxed in {@code ySlot}, leaves
	 * {@code |x|^y * cis(y*pi)} -- the complex {@code (expt -8d0 1/3)} -- on the stack. A
	 * negative real base has an EXACTLY known phase of pi, so the answer is one rotation
	 * of the real modulus rather than {@code exp(w*log z)} over a complex built for the
	 * purpose; the interpreter and the JVM take the same form.
	 * @param ctx the compile context
	 * @param modulusSlot the boxed f64 modulus
	 * @param ySlot the boxed f64 exponent
	 */
	static void emitNegativeBasePowInto(WasmLispCompiler.Ctx ctx, int modulusSlot, int ySlot) {
		int thetaSlot = ctx.allocTemp();
		WasmEmitHelper.unboxF64Local(ctx, ySlot);
		f64Const(ctx, Math.PI);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(thetaSlot);
		int cosSlot = ctx.allocTemp();
		callCosInto(ctx, thetaSlot, cosSlot);
		int sinSlot = ctx.allocTemp();
		callSinInto(ctx, thetaSlot, sinSlot);
		emitComplexTag(ctx);
		WasmEmitHelper.unboxF64Local(ctx, modulusSlot);
		WasmEmitHelper.unboxF64Local(ctx, cosSlot);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		WasmEmitHelper.unboxF64Local(ctx, modulusSlot);
		WasmEmitHelper.unboxF64Local(ctx, sinSlot);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
	}

	// (atanh x): |x| <= 1 (NaN included, like the interpreter's two tests) takes
	// the real core; beyond, x escapes into the plane at (x, +0.0).
	static void compileAtanh(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		emitComplexAtanhInto(ctx, parts[0], parts[1], reOut, imOut);
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmEmitHelper.boxF64(ctx);
		int fSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fSlot);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_GT);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		f64Const(ctx, -1.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// |x| > 1: the plane arm at (x, +0.0) -- the same reasoning as the acosh
		// escape: atanh(2) answers (atanh-half, +pi/2).
		emitComplexTag(ctx);
		getLocal(ctx, fSlot);
		f64Const(ctx, 0.0);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		int escSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(escSlot);
		int[] escParts = emitPartsF64(ctx, escSlot);
		int escRe = ctx.allocTemp();
		int escIm = ctx.allocTemp();
		emitComplexAtanhInto(ctx, escParts[0], escParts[1], escRe, escIm);
		emitComplexTag(ctx);
		getLocal(ctx, escRe);
		getLocal(ctx, escIm);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.unboxF64Local(ctx, fSlot);
		WasmInverseHypCompiler.emitAtanhRealF64(ctx);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (cis x): a complex operand rotates and decays through the plane arm; a real
	// one answers (cos x, sin x) -- the point on the unit circle.
	static void compileCis(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		emitComplexCisInto(ctx, parts[0], parts[1], reOut, imOut);
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		int xBox = ctx.allocTemp();
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xBox);
		int cosBox = ctx.allocTemp();
		callCosInto(ctx, xBox, cosBox);
		int sinBox = ctx.allocTemp();
		callSinInto(ctx, xBox, sinBox);
		emitComplexTag(ctx);
		getLocal(ctx, cosBox);
		getLocal(ctx, sinBox);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.END);
	}

	static void compileUnaryMath(LispCons cons, WasmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		switch (name) {
			case am.ik.rontolisp.LispNames.EXP -> emitComplexExpInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.LOG -> emitComplexLogInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.SIN -> emitComplexSinInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.COS -> emitComplexCosInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.TAN -> emitComplexTanInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.ASIN -> emitComplexAsinInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.ACOS -> emitComplexAcosInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.ATAN -> emitComplexAtanInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.SINH -> emitComplexSinhInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.COSH -> emitComplexCoshInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.TANH -> emitComplexTanhInto(ctx, parts[0], parts[1], reOut, imOut);
			default -> throw new IllegalArgumentException("not a complex unary operator: " + name);
		}
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
	}

	// Whether the form syntactically carries a certainly-complex producer -- the
	// steering gate for every site in this class.
	static boolean hasComplex(LispVal form) {
		return LispMacroExpander.containsComplex(form);
	}

	// Whether an = or /= form needs the complex-aware compilation (any operand
	// syntactically complex).
	static boolean hasComplexArgs(List<LispVal> args) {
		for (int i = 1; i < args.size(); i++) {
			if (LispMacroExpander.containsComplex(args.get(i))) {
				return true;
			}
		}
		return false;
	}

	// Pushes `slot is a TYPE_COMPLEX` as an i32.
	static void emitTestComplex(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
	}

	// Pushes `slot is a real number` (an exact integer, a ratio or a float -- never
	// a complex) as an i32.
	private static void emitTestReal(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.I32_OR);
	}

	// Pushes `slot is an exact integer or a ratio` (any real but a float or a
	// complex) as an i32.
	private static void emitTestExactOrRatio(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.I32_OR);
	}

	// Pushes part 0 (real) or 1 (imaginary) of the value in slot: field 0/1 for a
	// complex, the value itself / the i31 zero for a real (a non-number is left
	// for the caller's funnel, like the runtime builder's).
	private static void pushPart(WasmLispCompiler.Ctx ctx, int slot, int field) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		// Field 0 is the tag; part 0 (real) lives in field 1, part 1 in field 2.
		ctx.writer.writeUnsignedLeb128(field + 1);
		ctx.writer.write(Instruction.ELSE);
		if (field == 0) {
			getLocal(ctx, slot);
		}
		else {
			constI32(ctx, 0);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		ctx.writer.write(Instruction.END);
	}

	// The real and imaginary parts of the value in slot as f64 boxes in fresh
	// temps (each part through the shared _as_f64, so a non-number lands in
	// _type_err_num with itself as the culprit). Answers {reBox, imBox}.
	private static int[] emitPartsF64(WasmLispCompiler.Ctx ctx, int slot) {
		pushPart(ctx, slot, 0);
		WasmEmitHelper.castFloatGetF64(ctx);
		int reBox = boxF64Temp(ctx);
		pushPart(ctx, slot, 1);
		WasmEmitHelper.castFloatGetF64(ctx);
		int imBox = boxF64Temp(ctx);
		return new int[] { reBox, imBox };
	}

	// One = pair: part-wise _rat_cmp_bits equality when a complex is present at
	// run time, _rat_cmp_bits equality otherwise (it handles every real tier,
	// floats included). Leaves an i32.
	private static void emitEqPair(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot) {
		emitTestComplex(ctx, aSlot);
		emitTestComplex(ctx, bSlot);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		pushPart(ctx, aSlot, 0);
		pushPart(ctx, bSlot, 0);
		call(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		constI32(ctx, 2);
		ctx.writer.write(Instruction.I32_AND);
		pushPart(ctx, aSlot, 1);
		pushPart(ctx, bSlot, 1);
		call(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		constI32(ctx, 2);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, aSlot);
		getLocal(ctx, bSlot);
		call(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		constI32(ctx, 2);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.END);
	}

	// One ordering pair: a complex in either lands in _type_err_real, otherwise
	// _rat_cmp_bits masked. Leaves an i32.
	private static void emitCmpPair(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot, int mask) {
		emitTestComplex(ctx, aSlot);
		emitTestComplex(ctx, bSlot);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, 0x40);
		emitTestComplex(ctx, aSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, aSlot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, bSlot);
		ctx.writer.write(Instruction.END);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, aSlot);
		getLocal(ctx, bSlot);
		call(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		constI32(ctx, mask);
		ctx.writer.write(Instruction.I32_AND);
	}

	// The exact expt loop: power is the i31 in expSlot (always int-range, so the
	// interpreter's exact path applies); the base squares through _cmul, a
	// negative power through the exact reciprocal first. Leaves the value in
	// rSlot.
	private static void emitExptExactLoop(WasmLispCompiler.Ctx ctx, int baseSlot, int expSlot, int rSlot) {
		// Negative exponent: base = _cdiv(1, base), power = -power.
		WasmMathHelper.getI32(ctx, expSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		getLocal(ctx, baseSlot);
		call(ctx, WasmLispCompiler.FUNC_C_DIV);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmMathHelper.constI32(ctx, 0);
		WasmMathHelper.getI32(ctx, expSlot);
		ctx.writer.write(Instruction.I32_SUB);
		WasmMathHelper.setI32(ctx, expSlot);
		ctx.writer.write(Instruction.END);
		// r = 1; while (power > 0) { if (power & 1) r = _cmul(r, base);
		// base = _cmul(base, base); power >>= 1 }
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		WasmMathHelper.getI32(ctx, expSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		WasmMathHelper.getI32(ctx, expSlot);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, rSlot);
		getLocal(ctx, baseSlot);
		call(ctx, WasmLispCompiler.FUNC_C_MUL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, baseSlot);
		getLocal(ctx, baseSlot);
		call(ctx, WasmLispCompiler.FUNC_C_MUL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmMathHelper.getI32(ctx, expSlot);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.I32_SHR_S);
		WasmMathHelper.setI32(ctx, expSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// The float expt path: exp(w*log(z)) over the complex formulas below. Leaves
	// the boxed complex in rSlot.
	private static void emitExptFloat(WasmLispCompiler.Ctx ctx, int baseSlot, int expSlot, int rSlot) {
		int[] z = emitPartsF64(ctx, baseSlot);
		int[] w = emitPartsF64(ctx, expSlot);
		int lRe = ctx.allocTemp();
		int lIm = ctx.allocTemp();
		emitComplexLogInto(ctx, z[0], z[1], lRe, lIm);
		// e = (w0*l0 - w1*l1, w0*l1 + w1*l0), boxed per component.
		WasmEmitHelper.unboxF64Local(ctx, w[0]);
		WasmEmitHelper.unboxF64Local(ctx, lRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, w[1]);
		WasmEmitHelper.unboxF64Local(ctx, lIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		int eRe = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, w[0]);
		WasmEmitHelper.unboxF64Local(ctx, lIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, w[1]);
		WasmEmitHelper.unboxF64Local(ctx, lRe);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_ADD);
		int eIm = boxF64Temp(ctx);
		int oRe = ctx.allocTemp();
		int oIm = ctx.allocTemp();
		emitComplexExpInto(ctx, eRe, eIm, oRe, oIm);
		emitComplexTag(ctx);
		getLocal(ctx, oRe);
		getLocal(ctx, oIm);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
	}

	// The principal square root of the f64 pair (reBox, imBox) into the boxed
	// out-slots: (re, im) unchanged at the origin (signed zeros preserved, like
	// the interpreter), otherwise t = sqrt((|re| + hypot)/2) with the
	// sign-dependent assembly.
	private static void emitComplexSqrtInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, reBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		getLocal(ctx, imBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
		ctx.writer.write(Instruction.ELSE);
		int hBox = ctx.allocTemp();
		emitHypotInto(ctx, reBox, imBox, hBox);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_ABS);
		WasmEmitHelper.unboxF64Local(ctx, hBox);
		ctx.writer.write(Instruction.F64_ADD);
		f64Const(ctx, 0.5);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SQRT);
		int tBox = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_GE);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, tBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		WasmEmitHelper.unboxF64Local(ctx, tBox);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_ABS);
		WasmEmitHelper.unboxF64Local(ctx, tBox);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, tBox);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_COPYSIGN);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// hypot(re, im): fdlibm's, the interpreter's Math.hypot.
	private static void emitHypotInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int out) {
		WasmTranscendentalCompiler.callInto(ctx, Fn.HYPOT, reBox, imBox, out);
	}

	// log(z) = (ln(hypot), atan2(im, re)) into the boxed out-slots.
	private static void emitComplexLogInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		WasmTranscendentalCompiler.call(ctx, Fn.HYPOT);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		emitAtan2Into(ctx, imBox, reBox, imOut);
	}

	// exp(z) = (e^re*cos(im), e^re*sin(im)) into the boxed out-slots.
	private static void emitComplexExpInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int eBox = ctx.allocTemp();
		WasmTranscendentalCompiler.callInto(ctx, Fn.EXP, reBox, eBox);
		int cosBox = ctx.allocTemp();
		callCosInto(ctx, imBox, cosBox);
		int sinBox = ctx.allocTemp();
		callSinInto(ctx, imBox, sinBox);
		WasmEmitHelper.unboxF64Local(ctx, eBox);
		WasmEmitHelper.unboxF64Local(ctx, cosBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, eBox);
		WasmEmitHelper.unboxF64Local(ctx, sinBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// sin(z) = (sin(re)*cosh(im), cos(re)*sinh(im)) into the boxed out-slots.
	private static void emitComplexSinInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sinRe = ctx.allocTemp();
		callSinInto(ctx, reBox, sinRe);
		int cosRe = ctx.allocTemp();
		callCosInto(ctx, reBox, cosRe);
		int sinhIm = ctx.allocTemp();
		callSinhInto(ctx, imBox, sinhIm);
		int coshIm = ctx.allocTemp();
		callCoshInto(ctx, imBox, coshIm);
		WasmEmitHelper.unboxF64Local(ctx, sinRe);
		WasmEmitHelper.unboxF64Local(ctx, coshIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, cosRe);
		WasmEmitHelper.unboxF64Local(ctx, sinhIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// cos(z) = (cos(re)*cosh(im), -sin(re)*sinh(im)) into the boxed out-slots.
	private static void emitComplexCosInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sinRe = ctx.allocTemp();
		callSinInto(ctx, reBox, sinRe);
		int cosRe = ctx.allocTemp();
		callCosInto(ctx, reBox, cosRe);
		int sinhIm = ctx.allocTemp();
		callSinhInto(ctx, imBox, sinhIm);
		int coshIm = ctx.allocTemp();
		callCoshInto(ctx, imBox, coshIm);
		WasmEmitHelper.unboxF64Local(ctx, cosRe);
		WasmEmitHelper.unboxF64Local(ctx, coshIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, sinRe);
		WasmEmitHelper.unboxF64Local(ctx, sinhIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_NEG);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// tan(z) = sin(z)/cos(z) in f64 assembly.
	private static void emitComplexTanInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sRe = ctx.allocTemp();
		int sIm = ctx.allocTemp();
		emitComplexSinInto(ctx, reBox, imBox, sRe, sIm);
		int cRe = ctx.allocTemp();
		int cIm = ctx.allocTemp();
		emitComplexCosInto(ctx, reBox, imBox, cRe, cIm);
		emitComplexDivF64(ctx, sRe, sIm, cRe, cIm, reOut, imOut);
	}

	// The two roots Kahan's asin and acos are both assembled from: u = sqrt(1 - z)
	// into the first pair of boxed out-slots and v = sqrt(1 + z) into the second.
	// Both imaginary parts are taken against a +0.0 exactly as the interpreter's
	// 0.0 - im / 0.0 + im do -- that is what leaves an imaginary zero of EITHER sign
	// on the same sheet, so the side of the branch cut is decided by the real part
	// alone (`.kb/wasm-complex.md`).
	private static void emitAsinAcosRootsInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int uRe, int uIm, int vRe,
			int vIm) {
		f64Const(ctx, 1.0);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_SUB);
		int uArgRe = boxF64Temp(ctx);
		f64Const(ctx, 0.0);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_SUB);
		int uArgIm = boxF64Temp(ctx);
		emitComplexSqrtInto(ctx, uArgRe, uArgIm, uRe, uIm);
		f64Const(ctx, 1.0);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_ADD);
		int vArgRe = boxF64Temp(ctx);
		f64Const(ctx, 0.0);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_ADD);
		int vArgIm = boxF64Temp(ctx);
		emitComplexSqrtInto(ctx, vArgRe, vArgIm, vRe, vIm);
	}

	// asin(z) = (atan2(re, Re(u*v)), asinh(Im(conj(u)*v))) -- Kahan's form, the
	// interpreter's. A real argument inside [-1, 1] leaves both roots real, so the
	// asinh argument is a difference of zeros and the imaginary part is EXACTLY zero.
	private static void emitComplexAsinInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int uRe = ctx.allocTemp();
		int uIm = ctx.allocTemp();
		int vRe = ctx.allocTemp();
		int vIm = ctx.allocTemp();
		emitAsinAcosRootsInto(ctx, reBox, imBox, uRe, uIm, vRe, vIm);
		WasmEmitHelper.unboxF64Local(ctx, uRe);
		WasmEmitHelper.unboxF64Local(ctx, vRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, uIm);
		WasmEmitHelper.unboxF64Local(ctx, vIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		int prod = boxF64Temp(ctx);
		emitAtan2Into(ctx, reBox, prod, reOut);
		WasmEmitHelper.unboxF64Local(ctx, uRe);
		WasmEmitHelper.unboxF64Local(ctx, vIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, uIm);
		WasmEmitHelper.unboxF64Local(ctx, vRe);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		WasmInverseHypCompiler.emitAsinhRealF64(ctx);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// acos(z) = (2*atan2(Re(u), Re(v)), asinh(Im(conj(v)*u))) over the same two roots
	// -- not pi/2 - asin(z), which would carry asin's real part into a quantity that
	// is exactly 0 or pi on the cut.
	private static void emitComplexAcosInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int uRe = ctx.allocTemp();
		int uIm = ctx.allocTemp();
		int vRe = ctx.allocTemp();
		int vIm = ctx.allocTemp();
		emitAsinAcosRootsInto(ctx, reBox, imBox, uRe, uIm, vRe, vIm);
		int half = ctx.allocTemp();
		emitAtan2Into(ctx, uRe, vRe, half);
		f64Const(ctx, 2.0);
		WasmEmitHelper.unboxF64Local(ctx, half);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, vRe);
		WasmEmitHelper.unboxF64Local(ctx, uIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, vIm);
		WasmEmitHelper.unboxF64Local(ctx, uRe);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		WasmInverseHypCompiler.emitAsinhRealF64(ctx);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// atan(z) = (i/2)*(log(1-i*z) - log(1+i*z)).
	private static void emitComplexAtanInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		// l1 = log(1+im, -re).
		f64Const(ctx, 1.0);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_ADD);
		int l1ArgRe = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_NEG);
		int l1ArgIm = boxF64Temp(ctx);
		int l1Re = ctx.allocTemp();
		int l1Im = ctx.allocTemp();
		emitComplexLogInto(ctx, l1ArgRe, l1ArgIm, l1Re, l1Im);
		// l2 = log(1-im, re).
		f64Const(ctx, 1.0);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_SUB);
		int l2ArgRe = boxF64Temp(ctx);
		int l2ArgIm = reBox;
		int l2Re = ctx.allocTemp();
		int l2Im = ctx.allocTemp();
		emitComplexLogInto(ctx, l2ArgRe, l2ArgIm, l2Re, l2Im);
		// answer ((l2.1-l1.1)/2, (l1.0-l2.0)/2).
		WasmEmitHelper.unboxF64Local(ctx, l2Im);
		WasmEmitHelper.unboxF64Local(ctx, l1Im);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, l1Re);
		WasmEmitHelper.unboxF64Local(ctx, l2Re);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// asinh(z) = log(z + sqrt(z^2 + 1)) into the boxed out-slots. The +0.0 on the
	// imaginary part of z^2 normalizes the -0.0 a zero times a negative factor
	// leaves, so the cut sqrt takes its +i root; the magnitude-under-1 flip
	// evaluates -log(s - z) instead -- the same logarithm of a quantity that adds
	// without cancellation (their product is s^2 - z^2 = 1).
	private static void emitComplexAsinhInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		int z2Re = boxF64Temp(ctx);
		f64Const(ctx, 2.0);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_MUL);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_ADD);
		int z2Im = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, z2Re);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_ADD);
		int sArgRe = boxF64Temp(ctx);
		int sRe = ctx.allocTemp();
		int sIm = ctx.allocTemp();
		emitComplexSqrtInto(ctx, sArgRe, z2Im, sRe, sIm);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		WasmEmitHelper.unboxF64Local(ctx, sRe);
		ctx.writer.write(Instruction.F64_ADD);
		int wRe = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		WasmEmitHelper.unboxF64Local(ctx, sIm);
		ctx.writer.write(Instruction.F64_ADD);
		int wIm = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, wRe);
		WasmEmitHelper.unboxF64Local(ctx, wRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, wIm);
		WasmEmitHelper.unboxF64Local(ctx, wIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_ADD);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF, 0x40);
		WasmEmitHelper.unboxF64Local(ctx, sRe);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_SUB);
		int vRe = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, sIm);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_SUB);
		int vIm = boxF64Temp(ctx);
		emitComplexLogInto(ctx, vRe, vIm, reOut, imOut);
		WasmEmitHelper.unboxF64Local(ctx, reOut);
		ctx.writer.write(Instruction.F64_NEG);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, imOut);
		ctx.writer.write(Instruction.F64_NEG);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
		ctx.writer.write(Instruction.ELSE);
		emitComplexLogInto(ctx, wRe, wIm, reOut, imOut);
		ctx.writer.write(Instruction.END);
	}

	// acosh(z) = 2*log(sqrt((z+1)/2) + sqrt((z-1)/2)) -- the ANSI form; the /2 of
	// the imaginary part keeps its sign, which picks the sheet at the cut.
	private static void emitComplexAcoshInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		int im2 = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_ADD);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		int a1 = boxF64Temp(ctx);
		int s1Re = ctx.allocTemp();
		int s1Im = ctx.allocTemp();
		emitComplexSqrtInto(ctx, a1, im2, s1Re, s1Im);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		int a2 = boxF64Temp(ctx);
		int s2Re = ctx.allocTemp();
		int s2Im = ctx.allocTemp();
		emitComplexSqrtInto(ctx, a2, im2, s2Re, s2Im);
		WasmEmitHelper.unboxF64Local(ctx, s1Re);
		WasmEmitHelper.unboxF64Local(ctx, s2Re);
		ctx.writer.write(Instruction.F64_ADD);
		int sumRe = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, s1Im);
		WasmEmitHelper.unboxF64Local(ctx, s2Im);
		ctx.writer.write(Instruction.F64_ADD);
		int sumIm = boxF64Temp(ctx);
		emitComplexLogInto(ctx, sumRe, sumIm, reOut, imOut);
		WasmEmitHelper.unboxF64Local(ctx, reOut);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, imOut);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// atanh(z) = (log(1+z) - log(1-z)) / 2, the difference of the principal logs
	// (the log of the quotient would answer the other edge of the cut).
	private static void emitComplexAtanhInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		f64Const(ctx, 1.0);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_ADD);
		int a1 = boxF64Temp(ctx);
		int l1Re = ctx.allocTemp();
		int l1Im = ctx.allocTemp();
		emitComplexLogInto(ctx, a1, imBox, l1Re, l1Im);
		f64Const(ctx, 1.0);
		WasmEmitHelper.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_SUB);
		int a2 = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_NEG);
		int a2Im = boxF64Temp(ctx);
		int l2Re = ctx.allocTemp();
		int l2Im = ctx.allocTemp();
		emitComplexLogInto(ctx, a2, a2Im, l2Re, l2Im);
		WasmEmitHelper.unboxF64Local(ctx, l1Re);
		WasmEmitHelper.unboxF64Local(ctx, l2Re);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, l1Im);
		WasmEmitHelper.unboxF64Local(ctx, l2Im);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// cis(z) = (e^-im*cos(re), e^-im*sin(re)) into the boxed out-slots.
	private static void emitComplexCisInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		WasmEmitHelper.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_NEG);
		WasmTranscendentalCompiler.call(ctx, Fn.EXP);
		WasmEmitHelper.boxF64(ctx);
		int eBox = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(eBox);
		int cosBox = ctx.allocTemp();
		callCosInto(ctx, reBox, cosBox);
		int sinBox = ctx.allocTemp();
		callSinInto(ctx, reBox, sinBox);
		WasmEmitHelper.unboxF64Local(ctx, eBox);
		WasmEmitHelper.unboxF64Local(ctx, cosBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, eBox);
		WasmEmitHelper.unboxF64Local(ctx, sinBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// sinh(z) = (sinh(re)*cos(im), cosh(re)*sin(im)) into the boxed out-slots.
	private static void emitComplexSinhInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sinhRe = ctx.allocTemp();
		callSinhInto(ctx, reBox, sinhRe);
		int coshRe = ctx.allocTemp();
		callCoshInto(ctx, reBox, coshRe);
		int sinIm = ctx.allocTemp();
		callSinInto(ctx, imBox, sinIm);
		int cosIm = ctx.allocTemp();
		callCosInto(ctx, imBox, cosIm);
		WasmEmitHelper.unboxF64Local(ctx, sinhRe);
		WasmEmitHelper.unboxF64Local(ctx, cosIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, coshRe);
		WasmEmitHelper.unboxF64Local(ctx, sinIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// cosh(z) = (cosh(re)*cos(im), sinh(re)*sin(im)) into the boxed out-slots.
	private static void emitComplexCoshInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sinhRe = ctx.allocTemp();
		callSinhInto(ctx, reBox, sinhRe);
		int coshRe = ctx.allocTemp();
		callCoshInto(ctx, reBox, coshRe);
		int sinIm = ctx.allocTemp();
		callSinInto(ctx, imBox, sinIm);
		int cosIm = ctx.allocTemp();
		callCosInto(ctx, imBox, cosIm);
		WasmEmitHelper.unboxF64Local(ctx, coshRe);
		WasmEmitHelper.unboxF64Local(ctx, cosIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, sinhRe);
		WasmEmitHelper.unboxF64Local(ctx, sinIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// tanh(z) = sinh(z)/cosh(z) in f64 assembly.
	private static void emitComplexTanhInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sRe = ctx.allocTemp();
		int sIm = ctx.allocTemp();
		emitComplexSinhInto(ctx, reBox, imBox, sRe, sIm);
		int cRe = ctx.allocTemp();
		int cIm = ctx.allocTemp();
		emitComplexCoshInto(ctx, reBox, imBox, cRe, cIm);
		emitComplexDivF64(ctx, sRe, sIm, cRe, cIm, reOut, imOut);
	}

	// (a+bi)/(c+di) over boxed f64 components into the boxed out-slots.
	private static void emitComplexDivF64(WasmLispCompiler.Ctx ctx, int aRe, int aIm, int cRe, int cIm, int reOut,
			int imOut) {
		WasmEmitHelper.unboxF64Local(ctx, cRe);
		WasmEmitHelper.unboxF64Local(ctx, cRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, cIm);
		WasmEmitHelper.unboxF64Local(ctx, cIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_ADD);
		int denom = boxF64Temp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, aRe);
		WasmEmitHelper.unboxF64Local(ctx, cRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, aIm);
		WasmEmitHelper.unboxF64Local(ctx, cIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_ADD);
		WasmEmitHelper.unboxF64Local(ctx, denom);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmEmitHelper.unboxF64Local(ctx, aIm);
		WasmEmitHelper.unboxF64Local(ctx, cRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmEmitHelper.unboxF64Local(ctx, aRe);
		WasmEmitHelper.unboxF64Local(ctx, cIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		WasmEmitHelper.unboxF64Local(ctx, denom);
		ctx.writer.write(Instruction.F64_DIV);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// atan2(y, x) of the boxed components into the boxed out slot: fdlibm's atan2,
	// the interpreter's Math.atan2 (NaN in, NaN out; the x == 0 rungs tell +0 from -0).
	private static void emitAtan2Into(WasmLispCompiler.Ctx ctx, int yBox, int xBox, int out) {
		WasmTranscendentalCompiler.callInto(ctx, Fn.ATAN2, yBox, xBox, out);
	}

	// sin / cos / sinh / cosh of the boxed value into the boxed out slot: the fdlibm
	// functions, edges included.
	private static void callSinInto(WasmLispCompiler.Ctx ctx, int inBox, int outBox) {
		WasmTranscendentalCompiler.callInto(ctx, Fn.SIN, inBox, outBox);
	}

	private static void callCosInto(WasmLispCompiler.Ctx ctx, int inBox, int outBox) {
		WasmTranscendentalCompiler.callInto(ctx, Fn.COS, inBox, outBox);
	}

	private static void callSinhInto(WasmLispCompiler.Ctx ctx, int inBox, int outBox) {
		WasmTranscendentalCompiler.callInto(ctx, Fn.SINH, inBox, outBox);
	}

	private static void callCoshInto(WasmLispCompiler.Ctx ctx, int inBox, int outBox) {
		WasmTranscendentalCompiler.callInto(ctx, Fn.COSH, inBox, outBox);
	}

	private static int boxF64Temp(WasmLispCompiler.Ctx ctx) {
		int slot = ctx.allocTemp();
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		return slot;
	}

	private static void getLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void call(WasmLispCompiler.Ctx ctx, int func) {
		WasmOperandTypes.emitCall(ctx, func);
	}

	private static void constI32(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	// Pushes the TYPE_COMPLEX tag (always zero; see WasmLispCompiler.TYPE_COMPLEX).
	private static void emitComplexTag(WasmLispCompiler.Ctx ctx) {
		constI32(ctx, 0);
	}

	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

}
