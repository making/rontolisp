package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

import org.jspecify.annotations.Nullable;

/**
 * Builds WASM bytecode for the rational (ratio) runtime helpers. A ratio is a normalized
 * {@code TYPE_RATIO} struct whose numerator and denominator are exact integers in their
 * narrowest tier -- an i31, a {@code TYPE_BIGNUM} or a limb {@code TYPE_BIGINT} --
 * coprime, with the denominator greater than one and the sign on the numerator: the
 * interpreter's and the JVM's {@code BigInteger} pair, exact at any magnitude. A rational
 * whose denominator reduces to one is the integer itself, so {@code _rat_new} performs
 * the normalization and the demotion. Ratio arithmetic composes the tier-aware
 * {@code _big_*} helpers ({@link WasmBigIntRuntimeBuilder}); {@code _rat_div} always goes
 * through {@code _rat_new}, which gives Common Lisp exact division ({@code (/ 10 2)} is
 * {@code 5}, {@code (/ 10 3)} is the ratio {@code 10/3}) and signals
 * {@code division-by-zero} on a zero denominator.
 */
final class WasmRatioRuntimeBuilder {

	private WasmRatioRuntimeBuilder() {
	}

	/**
	 * Consumes a normalized numerator and denominator (exact integers, in that order) and
	 * leaves the {@code TYPE_RATIO} holding them. The struct's third field is a tag that
	 * is always 0 and carries nothing: the natural {@code {eqref, eqref}} shape is
	 * {@code TYPE_FARRAY}'s, and wasm-GC canonicalizes two structurally identical types
	 * in different rec groups into one, so {@code ref.test} could no longer tell a ratio
	 * from a packed array ({@code .kb/wasm-complex.md} measured the same trap for the
	 * complex struct, whose tag leads). Every construction goes through here.
	 * @param w the body writer
	 */
	static void emitNewRatio(WasmWriter w) {
		constI32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_RATIO);
	}

	// _rat_new((ref null eq) num, (ref null eq) den) -> (ref null eq): two exact integers
	// at any tier. Signals division-by-zero on den == 0 (WasmRuntimeBuilder.
	// emitDivisionByZero: _div_zero with the landing, a trap without it), moves the sign
	// to the
	// numerator, reduces by the gcd and answers the integer itself for a denominator of
	// one, through _big_cmp/_big_neg/_big_gcd/_big_divrem. i31Head (every level but
	// --optimize=size, like the binary helpers' head) answers two i31 operands -- the
	// ratios most programs make -- in i32 with Euclid's loop inline first.
	static byte[] buildRatNewBody(boolean i31Head, boolean landing) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// params: 0=num, 1=den. locals: 2=g (ref null eq); with the head 3=n, 4=d, 5=a,
		// 6=b, 7=t (i32)
		w.write(i31Head ? 2 : 1);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		final int g = 2;
		if (i31Head) {
			w.write(5);
			w.write(Type.I32);
			emitRatNewI31Head(w, 3, 4, 5, 6, 7, landing);
		}

		// The tier-aware steps. A canonical zero is always the i31 0, and a canonical
		// one the i31 1.
		getLocal(w, 1);
		i31Const(w, 0);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, 0x40);
		WasmRuntimeBuilder.emitDivisionByZero(w, landing);
		w.write(Instruction.END);
		getLocal(w, 1);
		i31Const(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		constI32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_NEG);
		setLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_BIG_NEG);
		setLocal(w, 1);
		w.write(Instruction.END);
		// g = gcd(num, den) >= 1, since den != 0
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_BIG_GCD);
		setLocal(w, g);
		getLocal(w, g);
		i31Const(w, 1);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		getLocal(w, g);
		constI32(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_DIVREM);
		setLocal(w, 0);
		getLocal(w, 1);
		getLocal(w, g);
		constI32(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_DIVREM);
		setLocal(w, 1);
		w.write(Instruction.END);
		getLocal(w, 1);
		i31Const(w, 1);
		w.write(Instruction.REF_EQ);
		ifRefNullEq(w);
		getLocal(w, 0);
		w.write(Instruction.ELSE);
		getLocal(w, 0);
		getLocal(w, 1);
		emitNewRatio(w);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_new's i31 head: two i31 operands normalize in i32 (|-2^30| still fits one,
	// and _int_new boxes a component that leaves the i31 range when its sign moves) and
	// return; anything else falls through to the tier-aware steps.
	private static void emitRatNewI31Head(WasmWriter w, int n, int d, int a, int b, int t, boolean landing) {
		getLocal(w, 0);
		refTestI31(w);
		getLocal(w, 1);
		refTestI31(w);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		WasmEmitHelper.castI31GetS(w);
		setLocal(w, n);
		getLocal(w, 1);
		WasmEmitHelper.castI31GetS(w);
		setLocal(w, d);
		// if (d == 0) division-by-zero
		getLocal(w, d);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		WasmRuntimeBuilder.emitDivisionByZero(w, landing);
		w.write(Instruction.END);
		// if (d < 0) { n = -n; d = -d; }
		getLocal(w, d);
		constI32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		constI32(w, 0);
		getLocal(w, n);
		w.write(Instruction.I32_SUB);
		setLocal(w, n);
		constI32(w, 0);
		getLocal(w, d);
		w.write(Instruction.I32_SUB);
		setLocal(w, d);
		w.write(Instruction.END);
		// a = |n|; b = d; while (b != 0) { t = a % b; a = b; b = t; }
		getLocal(w, n);
		setLocal(w, a);
		getLocal(w, a);
		constI32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		constI32(w, 0);
		getLocal(w, a);
		w.write(Instruction.I32_SUB);
		setLocal(w, a);
		w.write(Instruction.END);
		getLocal(w, d);
		setLocal(w, b);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, b);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, a);
		getLocal(w, b);
		w.write(Instruction.I32_REM_U);
		setLocal(w, t);
		getLocal(w, b);
		setLocal(w, a);
		getLocal(w, t);
		setLocal(w, b);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// n /= a; d /= a (a = gcd, positive because d > 0)
		getLocal(w, n);
		getLocal(w, a);
		w.write(Instruction.I32_DIV_S);
		setLocal(w, n);
		getLocal(w, d);
		getLocal(w, a);
		w.write(Instruction.I32_DIV_S);
		setLocal(w, d);
		// d == 1 ? n : ratio(n, d)
		getLocal(w, d);
		constI32(w, 1);
		w.write(Instruction.I32_EQ);
		ifRefNullEq(w);
		getLocal(w, n);
		w.write(Instruction.I64_EXTEND_S_I32);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.ELSE);
		getLocal(w, n);
		w.write(Instruction.I64_EXTEND_S_I32);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		getLocal(w, d);
		w.write(Instruction.I64_EXTEND_S_I32);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		emitNewRatio(w);
		w.write(Instruction.END);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// _rat_num((ref null eq) x) -> (ref null eq): a ratio's numerator, or an exact
	// integer itself.
	static byte[] buildRatNumBody() {
		return buildRatGetBody(0);
	}

	// _rat_den((ref null eq) x) -> (ref null eq): a ratio's denominator, or 1 for an
	// exact integer.
	static byte[] buildRatDenBody() {
		return buildRatGetBody(1);
	}

	// Anything but an exact rational lands in _type_err_int (a catchable "not of type
	// INTEGER" in EH mode, a trap outside it), as the i32 accessors' _int_val did: the
	// arithmetic helpers reach here only for a non-float operand that is not an exact
	// integer, and numerator/denominator only for a real one.
	private static byte[] buildRatGetBody(int field) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		getLocal(w, 0);
		refTestType(w, WasmLispCompiler.TYPE_RATIO);
		ifRefNullEq(w);
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_RATIO);
		w.writeUnsignedLeb128(field);
		w.write(Instruction.ELSE);
		emitIsExactInt(w, 0);
		ifRefNullEq(w);
		if (field == 0) {
			getLocal(w, 0);
		}
		else {
			i31Const(w, 1);
		}
		w.write(Instruction.ELSE);
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_TYPE_ERR_INT);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_add/_rat_sub/_rat_mul((ref null eq) a, (ref null eq) b) -> (ref null eq):
	// float contagion when either operand is a float, the tier-aware _big_* helper when
	// both are exact integers (exact at any magnitude: an i64 fast path first, promotion
	// to the limb tier instead of wrapping), and exact rational arithmetic over the
	// components otherwise -- cross-multiplied through _big_mul and normalized by
	// _rat_new.
	//
	// i31Head (every level but --optimize=size) opens the body with the two-i31 case
	// answered inline: two i31s add, subtract or multiply exactly in i64 (|a|,|b| <=
	// 2^30), and _int_new boxes the result in its narrowest tier -- what the _big_* path
	// below answers for the same operands, minus its two _int_val calls and the
	// dispatch. This is the path of every unfused (+ a b) over plain boxed operands (a
	// recursive function's `(+ (f ...) (f ...))` tail): measured 2026-09-19, fib 30 x 20
	// on wasmtime 47 went 680-740 -> 415-480 ms (.kb/wasm-int-fusion.md). The size level
	// keeps the dispatch-only body, which the type-test fold can still reduce to a pure
	// forwarder of _big_* in an integer-only module (.kb/wasm-ref-type-fold.md).
	static byte[] buildRatBinaryBody(int i32Opcode, int f64Opcode, boolean i31Head) {
		return buildRatBinaryBody(i32Opcode, f64Opcode, i31Head, -1);
	}

	// The same, with the holder arm of a program that may observe a complex
	// (complexFunc the _c_* twin, -1 elsewhere): a TYPE_COMPLEX operand is handed to the
	// twin where the real body would reject it -- beside a float, before _as_f64's
	// landing, and once the two exact integers are ruled out, before the ratio arm's
	// _rat_num and the non-rational landing (emitComplexArm) -- so the i31 head and
	// the exact-integer path test nothing new. Two floats, which cannot be the pair the
	// arm is for, answer ahead of it (emitFloatPairArm).
	static byte[] buildRatBinaryBody(int i32Opcode, int f64Opcode, boolean i31Head, int complexFunc) {
		int i64Opcode = i32Opcode == Instruction.I32_ADD ? Instruction.I64_ADD
				: i32Opcode == Instruction.I32_SUB ? Instruction.I64_SUB : Instruction.I64_MUL;
		int bigFunc = i64Opcode == Instruction.I64_ADD ? WasmLispCompiler.FUNC_BIG_ADD
				: i64Opcode == Instruction.I64_SUB ? WasmLispCompiler.FUNC_BIG_SUB : WasmLispCompiler.FUNC_BIG_MUL;
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		if (i31Head) {
			getLocal(w, 0);
			refTestI31(w);
			getLocal(w, 1);
			refTestI31(w);
			w.write(Instruction.I32_AND);
			w.write(Instruction.IF, 0x40);
			emitI31ToI64(w, 0);
			emitI31ToI64(w, 1);
			w.write(i64Opcode);
			call(w, WasmLispCompiler.FUNC_INT_NEW);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
		}

		// Float fast path: if either operand is a float, compute in f64 (float contagion)
		// and box the result. Mirrors the JVM _add/_sub/_mul Double prologue.
		emitEitherFloat(w);
		ifRefNullEq(w);
		emitFloatPairArm(w, complexFunc, f64Opcode);
		emitComplexArm(w, complexFunc);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(f64Opcode);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);

		emitBothExactInt(w);
		ifRefNullEq(w);
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, bigFunc);
		w.write(Instruction.ELSE);
		emitComplexArm(w, complexFunc);
		emitEitherRatio(w);
		ifRefNullEq(w);
		if (i64Opcode == Instruction.I64_MUL) {
			// _rat_new(num(a)*num(b), den(a)*den(b))
			emitComponent(w, 0, WasmLispCompiler.FUNC_RAT_NUM);
			emitComponent(w, 1, WasmLispCompiler.FUNC_RAT_NUM);
			call(w, WasmLispCompiler.FUNC_BIG_MUL);
		}
		else {
			// _rat_new(num(a)*den(b) <op> num(b)*den(a), den(a)*den(b))
			emitCrossProduct(w, 0, 1);
			emitCrossProduct(w, 1, 0);
			call(w, bigFunc);
		}
		emitComponent(w, 0, WasmLispCompiler.FUNC_RAT_DEN);
		emitComponent(w, 1, WasmLispCompiler.FUNC_RAT_DEN);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_NEW);
		w.write(Instruction.ELSE);
		emitNonRationalLanding(w);
		w.write(Instruction.END); // end either-ratio if
		w.write(Instruction.END); // end exact-integer if

		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_add_f64 .. _rat_div_f64((ref null eq) a, (ref null eq) b) -> f64: the f64 of
	// the ratFunc step over two boxed operands, the step that joins a float site's exact
	// prefix to its f64 fold. A float operand makes it the f64 step the fold takes -- the
	// float read straight out of its box, the other through _as_f64, so a float pays the
	// test _as_f64's own float rung makes and nothing more -- and two exact operands the
	// conversion of the exact step (ratFunc, whose landings also report a non-number and
	// an exact zero divisor).
	static byte[] buildRatStepF64Body(int ratFunc, int f64Opcode) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no extra locals
		getLocal(w, 0);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, Type.F64.code());
		emitFloatField(w, 0);
		emitLocalToF64(w, 1);
		w.write(f64Opcode);
		w.write(Instruction.ELSE);
		getLocal(w, 1);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, Type.F64.code());
		emitLocalToF64(w, 0);
		emitFloatField(w, 1);
		w.write(f64Opcode);
		w.write(Instruction.ELSE);
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, ratFunc);
		call(w, WasmLispCompiler.FUNC_AS_F64);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// The f64 of the TYPE_FLOAT in local[slot].
	private static void emitFloatField(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
	}

	// _rat_div((ref null eq) a, (ref null eq) b) -> (ref null eq): exact Common Lisp
	// division; traps on division by zero. Two exact integers are the numerator and the
	// denominator _rat_new normalizes (an even division demotes to the quotient, so
	// (/ #x100000000 2) stays exact); otherwise _rat_new(num(a)*den(b), den(a)*num(b)).
	static byte[] buildRatDivBody() {
		return buildRatDivBody(-1);
	}

	// The same, with the holder arm buildRatBinaryBody's complexFunc describes: _c_div
	// is the twin.
	static byte[] buildRatDivBody(int complexFunc) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		// Float fast path: f64 division when either operand is a float.
		emitEitherFloat(w);
		ifRefNullEq(w);
		emitFloatPairArm(w, complexFunc, Instruction.F64_DIV);
		emitComplexArm(w, complexFunc);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);

		emitBothExactInt(w);
		ifRefNullEq(w);
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_NEW);
		w.write(Instruction.ELSE);
		emitComplexArm(w, complexFunc);
		emitEitherRatio(w);
		ifRefNullEq(w);
		emitCrossProduct(w, 0, 1);
		emitComponent(w, 0, WasmLispCompiler.FUNC_RAT_DEN);
		emitComponent(w, 1, WasmLispCompiler.FUNC_RAT_NUM);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_NEW);
		w.write(Instruction.ELSE);
		emitNonRationalLanding(w);
		w.write(Instruction.END); // end either-ratio if
		w.write(Instruction.END); // end exact-int if

		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Pushes num(local[x]) * den(local[y]) through _big_mul -- one side of a
	// cross-multiplication.
	private static void emitCrossProduct(WasmWriter w, int x, int y) {
		emitComponent(w, x, WasmLispCompiler.FUNC_RAT_NUM);
		emitComponent(w, y, WasmLispCompiler.FUNC_RAT_DEN);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
	}

	// Pushes _rat_num / _rat_den (componentFunc) of local[slot].
	private static void emitComponent(WasmWriter w, int slot, int componentFunc) {
		getLocal(w, slot);
		call(w, componentFunc);
	}

	// The float branch's head in a program that may observe a complex (complexFunc the
	// _c_* twin; nothing at all for -1): two floats answer here, read straight out of
	// their boxes, so the complex arm after it -- which only a mixed pair can need --
	// costs the float pair nothing, and the pair skips the two _as_f64 calls besides.
	// The value is the one _as_f64's float rung would have read.
	private static void emitFloatPairArm(WasmWriter w, int complexFunc, int f64Opcode) {
		if (complexFunc < 0) {
			return;
		}
		emitBothFloat(w);
		w.write(Instruction.IF, 0x40);
		for (int slot = 0; slot < 2; slot++) {
			getLocal(w, slot);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			w.writeUnsignedLeb128(0);
		}
		w.write(f64Opcode);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// The holder arm of a program that may observe a complex: when local 0 or 1 is a
	// TYPE_COMPLEX, return complexFunc(a, b); fall through otherwise. Nothing at all for
	// complexFunc -1, so every other module's body is the one it always was. A ref.test
	// the module's constructors decide folds away with its call
	// (.kb/wasm-ref-type-fold.md).
	private static void emitComplexArm(WasmWriter w, int complexFunc) {
		if (complexFunc < 0) {
			return;
		}
		getLocal(w, 0);
		refTestType(w, WasmLispCompiler.TYPE_COMPLEX);
		getLocal(w, 1);
		refTestType(w, WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, complexFunc);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// Pushes `(a is a ratio) | (b is a ratio)` over locals 0 and 1: the guard of every
	// rational arm, which the dispatch reaches only after the float and the two exact
	// integer cases, so with no ratio operand one of them is not a number at all. Testing
	// the ratio TYPE, rather than letting such an operand fall into the computation and
	// fail there, is what lets the type-test fold retire the whole ratio machinery --
	// _rat_new, the _big_* cross products and the limb division behind its gcd -- from a
	// module that never makes a ratio but does arithmetic over values it cannot type
	// (.kb/wasm-ref-type-fold.md).
	private static void emitEitherRatio(WasmWriter w) {
		getLocal(w, 0);
		refTestType(w, WasmLispCompiler.TYPE_RATIO);
		getLocal(w, 1);
		refTestType(w, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.I32_OR);
	}

	// The rational arm's landing with no ratio operand: _rat_num of each operand in
	// argument order, so the first one that is not an exact rational lands in
	// _type_err_int -- the operand the computation would have reported first. Never
	// returns.
	private static void emitNonRationalLanding(WasmWriter w) {
		emitComponent(w, 0, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.DROP);
		emitComponent(w, 1, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.DROP);
		w.write(Instruction.UNREACHABLE);
	}

	// The float remainder with no value (locals 4 = |a|, 5 = |b|): a finite dividend over
	// a zero divisor signals division-by-zero through _div_zero, anything else (a NaN or
	// infinite dividend, a NaN divisor) the non-finite rounding -- floor's and
	// truncate's conditions. nonFiniteMessage is non-null exactly where the module
	// signals a division by zero (WasmLispCompiler's divZeroLanding: EH mode, a
	// division operator reachable); elsewhere the operation traps, as an error does
	// outside EH mode.
	private static void emitUndefinedRemainder(WasmWriter w,
			WasmLispCompiler.StringTable.@Nullable StringEntry nonFiniteMessage, boolean identityHash) {
		if (nonFiniteMessage == null) {
			w.write(Instruction.UNREACHABLE);
			return;
		}
		// |a| - |a| is 0.0 for a finite dividend and NaN otherwise, so
		// (|a| - |a|) + |b| == (|a| - |a|) holds exactly when the dividend is finite and
		// the divisor a zero -- without the two 9-byte f64 constants.
		getLocal(w, 4);
		getLocal(w, 4);
		w.write(Instruction.F64_SUB);
		getLocal(w, 5);
		w.write(Instruction.F64_ADD);
		getLocal(w, 4);
		getLocal(w, 4);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, 0x40);
		WasmRuntimeBuilder.emitDivisionByZero(w, true);
		w.write(Instruction.END);
		WasmRuntimeBuilder.emitMessageThrow(w, nonFiniteMessage, identityHash);
	}

	// _rat_rem/_rat_mod((ref null eq) a, (ref null eq) b) -> (ref null eq): the Common
	// Lisp remainder (sign of the dividend) and modulo (sign of the divisor). Both are
	// a - b*q with q = trunc(a/b) for rem and q = floor(a/b) for mod. A float operand
	// (either side) takes the EXACT float remainder (WasmFmodRuntimeBuilder, shared with
	// --no-gc: evaluating the formula in f64 rounds above 2^53 and answers NaN for an
	// infinite divisor, where the interpreter and the JVM's DREM are exact); two exact
	// integers (any tier) go through _big_divrem / _big_mod, exact at any magnitude;
	// otherwise the exact rational helpers compute a - b*(trunc|floor)(a/b). Mirrors the
	// dispatch shape of buildRatBinaryBody so a float reaching mod/rem through a
	// variable is handled. A float pair with no remainder signals as the floor/truncate
	// it is the remainder of does (emitUndefinedRemainder).
	static byte[] buildRatRemBody(boolean mod, WasmLispCompiler.StringTable.@Nullable StringEntry nonFiniteMessage,
			boolean identityHash) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 2=fa (f64), 3=fb (f64), 4/5/6 = the remainder loop's scratch
		w.write(1);
		w.write(5);
		w.write(Type.F64);

		// Float path: the exact remainder, boxed as TYPE_FLOAT.
		emitEitherFloat(w);
		ifRefNullEq(w);
		emitLocalToF64(w, 0);
		setLocal(w, 2);
		emitLocalToF64(w, 1);
		setLocal(w, 3);
		WasmFmodRuntimeBuilder.emitRemainder(w, mod, 2, 3, 4, 5, 6,
				() -> emitUndefinedRemainder(w, nonFiniteMessage, identityHash));
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);

		// Non-float: fast exact-integer path at any tier -- _big_divrem / _big_mod
		// keep an i64 fast path first and stay exact on the limb tier.
		emitBothExactInt(w);
		ifRefNullEq(w);
		getLocal(w, 0);
		getLocal(w, 1);
		if (mod) {
			call(w, WasmLispCompiler.FUNC_BIG_MOD);
		}
		else {
			constI32(w, 1);
			call(w, WasmLispCompiler.FUNC_BIG_DIVREM);
		}
		w.write(Instruction.ELSE);

		// General path: a - b * (trunc|floor)(a / b) via the exact rational helpers, for
		// a ratio operand.
		emitEitherRatio(w);
		ifRefNullEq(w);
		getLocal(w, 0); // a (first arg of _rat_sub)
		getLocal(w, 1); // b (first arg of _rat_mul)
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_DIV); // a / b
		call(w, mod ? WasmLispCompiler.FUNC_RAT_FLOOR : WasmLispCompiler.FUNC_RAT_TRUNC); // q
		call(w, WasmLispCompiler.FUNC_RAT_MUL); // b * q
		call(w, WasmLispCompiler.FUNC_RAT_SUB); // a - b*q
		w.write(Instruction.ELSE);
		emitNonRationalLanding(w);
		w.write(Instruction.END); // end either-ratio if
		w.write(Instruction.END); // end i31-fast-path if

		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_cmp((ref null eq) a, (ref null eq) b) -> i32: -1/0/1 -- f64 when either
	// operand
	// is a float, _big_cmp for two exact integers, and _big_cmp of the cross products
	// num(a)*den(b) and num(b)*den(a) for a ratio operand (denominators are positive, so
	// the comparison direction is preserved).
	static byte[] buildRatCmpBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		// Float fast path: f64 comparison, (a > b) - (a < b), when either operand is a
		// float. Mirrors the JVM _cmp Double prologue.
		emitEitherFloat(w);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(Instruction.F64_GT);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(Instruction.F64_LT);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.ELSE);

		// Exact-integer fast path: _big_cmp compares at any tier.
		emitBothExactInt(w);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		w.write(Instruction.ELSE);
		emitEitherRatio(w);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitCrossProduct(w, 0, 1);
		emitCrossProduct(w, 1, 0);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		w.write(Instruction.ELSE);
		emitNonRationalLanding(w);
		w.write(Instruction.END); // end either-ratio if
		w.write(Instruction.END); // end exact-int fast-path if
		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_cmp_bits((ref null eq) a, (ref null eq) b) -> i32: the comparison as a
	// bitmask -- 1 = a<b, 2 = a=b, 4 = a>b, 0 = unordered (a NaN operand). The
	// comparison call sites AND the operator's accepted mask and test nonzero, so NaN
	// fails every one of = < > <= >= (IEEE); _rat_cmp's -1/0/1 signum against zero
	// cannot express "unordered" (it answered "equal"). Non-float operands delegate to
	// _rat_cmp (exact, never unordered). A float against a float, an i31 or a boxed
	// i64 within 2^53 compares the two f64 values, which a double holds exactly (the
	// f64 arm, read straight from the boxes). A float against any other exact integer
	// or a ratio compares EXACT values -- the float's exact binary value (as `rational`
	// answers
	// it) against the exact operand -- through the existing big-tier helpers alone
	// (`_int_new` of the decomposed mantissa, `_big_ash`, `_big_mul`, `_big_cmp`; no
	// new runtime function), so a near tie decides strictly: `(= 0.6666666666666666
	// 2/3)` is NIL here as on the interpreter and the JVM. A float
	// against anything else keeps the old f64 behavior, including its `_type_err_*`
	// traps for a complex or a non-number.
	static byte[] buildRatCmpBitsBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 2=FL, 3=EX (the float and the exact operand, eqref), 4=D (the
		// float's value, f64), 5=BITS, 6=MANT (i64), 7=EXP, 8=SGN, 9=TMP (i32),
		// 10=NF, 11=DF, 12=NE, 13=DE (eqref: the float's and the exact operand's
		// numerator/denominator pair for the cross-multiplied compare). SGN is 1
		// when the float is operand a and -1 when it is operand b: the
		// cross-multiplication reads (FL vs EX), so a float in b position has
		// the resulting signum flipped to answer (a vs b), and the infinities
		// pick their bit by the same sign. 14=FA, 15=FB (f64): the operands'
		// values on the f64 arm.
		final int fl = 2, ex = 3, d = 4, bits = 5, mant = 6, exp = 7, sgn = 8, tmp = 9, nf = 10, df = 11, ne = 12,
				de = 13, fa = 14, fb = 15;
		w.write(6);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(1);
		w.write(Type.F64);
		w.write(2);
		w.write(Type.I64);
		w.write(3);
		w.write(Type.I32);
		w.write(4);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.F64);

		emitEitherFloat(w);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// The f64 arm: when each operand is a float or an integer a double holds
		// exactly (an i31, or a boxed i64 within 2^53), comparing the two f64
		// values IS the exact comparison. Each operand is read once, straight from
		// its box (no _as_f64 call), and the mask is built branch-free: lt -> 1,
		// eq -> 2, gt -> 4, NaN -> 0. Anything else breaks out to the exact arm.
		w.write(Instruction.BLOCK);
		w.write(Type.I32);
		w.write(Instruction.BLOCK, 0x40);
		emitExactF64OrBreak(w, 0, mant);
		setLocal(w, fa);
		emitExactF64OrBreak(w, 1, mant);
		setLocal(w, fb);
		getLocal(w, fa);
		getLocal(w, fb);
		w.write(Instruction.F64_LT);
		getLocal(w, fa);
		getLocal(w, fb);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		getLocal(w, fa);
		getLocal(w, fb);
		w.write(Instruction.F64_GT);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.END); // end f64-arm block: fall into the exact arm
		// Exactly one operand is a float (two floats always take the f64 arm): split
		// the pair so FL holds it, and record its side in SGN (1 for a, -1 for b).
		getLocal(w, 0);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		setLocal(w, fl);
		getLocal(w, 1);
		setLocal(w, ex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		setLocal(w, sgn);
		w.write(Instruction.ELSE);
		getLocal(w, 1);
		setLocal(w, fl);
		getLocal(w, 0);
		setLocal(w, ex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-1);
		setLocal(w, sgn);
		w.write(Instruction.END);
		// The exact operand is an integer (any tier) or a ratio; anything else
		// keeps the old f64 behavior below.
		emitIsExactInt(w, ex);
		getLocal(w, ex);
		refTestType(w, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// D is the float's value.
		getLocal(w, fl);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		setLocal(w, d);
		// Unordered (NaN) -> 0.
		getLocal(w, d);
		getLocal(w, d);
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.ELSE);
		// +Inf is beyond every exact number: operand a's bit is gt, operand b's
		// is lt.
		getLocal(w, d);
		w.write(Instruction.F64_CONST);
		w.writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, sgn);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(4);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// -Inf is below every exact number: operand a's bit is lt, operand b's
		// is gt.
		getLocal(w, d);
		w.write(Instruction.F64_CONST);
		w.writeF64(Double.NEGATIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, sgn);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(4);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// A finite float decomposes from its raw bits exactly like `rational`
		// does (hidden bit, subnormal shape, sign on the mantissa): the value
		// is MANT * 2^EXP.
		getLocal(w, d);
		w.write(Instruction.I64_REINTERPRET_F64);
		setLocal(w, bits);
		getLocal(w, bits);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(52);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I64_AND);
		w.write(Instruction.I32_WRAP_I64);
		setLocal(w, exp);
		getLocal(w, bits);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0x000fffffffffffffL);
		w.write(Instruction.I64_AND);
		setLocal(w, mant);
		getLocal(w, exp);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		// Subnormal (and zero): the mantissa is the fraction, scaled by 2^-1074.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-1074);
		setLocal(w, exp);
		w.write(Instruction.ELSE);
		getLocal(w, mant);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0x0010000000000000L);
		w.write(Instruction.I64_OR);
		setLocal(w, mant);
		getLocal(w, exp);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1075);
		w.write(Instruction.I32_SUB);
		setLocal(w, exp);
		w.write(Instruction.END);
		getLocal(w, bits);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		getLocal(w, mant);
		w.write(Instruction.I64_SUB);
		setLocal(w, mant);
		w.write(Instruction.END);
		// The float's numerator/denominator: a zero mantissa (either zero) is
		// plain zero over one, else the signed mantissa shifted up for EXP >= 0
		// or over 2^-EXP. Shifts stay under 1075 bits, far below `_big_ash`'s
		// allocation guard.
		getLocal(w, mant);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.IF, 0x40);
		i31Const(w, 0);
		setLocal(w, nf);
		i31Const(w, 1);
		setLocal(w, df);
		w.write(Instruction.ELSE);
		getLocal(w, exp);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, mant);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		getLocal(w, exp);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		setLocal(w, nf);
		i31Const(w, 1);
		setLocal(w, df);
		w.write(Instruction.ELSE);
		getLocal(w, mant);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		setLocal(w, nf);
		i31Const(w, 1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		getLocal(w, exp);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		setLocal(w, df);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// The exact operand's numerator/denominator: an integer over one, or the
		// ratio's components.
		emitComponent(w, ex, WasmLispCompiler.FUNC_RAT_NUM);
		setLocal(w, ne);
		emitComponent(w, ex, WasmLispCompiler.FUNC_RAT_DEN);
		setLocal(w, de);
		// Cross-multiplied (FL vs EX), then 1 << (cmp + 1) maps -1/0/1 to
		// 1/2/4. A float in b position answers (a vs b), the negation of (FL
		// vs EX), so its signum is flipped.
		getLocal(w, nf);
		getLocal(w, de);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
		getLocal(w, ne);
		getLocal(w, df);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		setLocal(w, tmp);
		getLocal(w, sgn);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, tmp);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-1);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.ELSE);
		getLocal(w, tmp);
		w.write(Instruction.END);
		setLocal(w, tmp);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		getLocal(w, tmp);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// Not an exact operand: the old f64 behavior.
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(Instruction.F64_LT);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.ELSE);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(Instruction.F64_GT);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(4);
		w.write(Instruction.ELSE);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END); // end exact-operand if
		w.write(Instruction.END); // end the block the f64 arm answers through
		w.write(Instruction.ELSE);
		// exact types: 1 << (_rat_cmp(a, b) + 1) maps -1/0/1 to 1/2/4
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_CMP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Pushes local[slot] as an f64 whose value IS the operand's exact value -- a float,
	// an i31, or a boxed i64 within [-2^53, 2^53] -- or branches to the block enclosing
	// the emission (br 2 from inside the two ifs) for any other operand. scratch is an
	// i64 local the boxed-integer rung may clobber.
	private static void emitExactF64OrBreak(WasmWriter w, int slot, int scratch) {
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF);
		w.write(Type.F64);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.ELSE);
		getLocal(w, slot);
		refTestI31(w);
		w.write(Instruction.IF);
		w.write(Type.F64);
		getLocal(w, slot);
		WasmEmitHelper.castI31GetS(w);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.ELSE);
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(2);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(scratch);
		// |v| <= 2^53 exactly when v + 2^53, read unsigned, is at most 2^54.
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(1L << 53);
		w.write(Instruction.I64_ADD);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(1L << 54);
		w.write(Instruction.I64_GT_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(2);
		getLocal(w, scratch);
		w.write(Instruction.F64_CONVERT_S_I64);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Emits the test `(a is TYPE_FLOAT) & (b is TYPE_FLOAT)` over locals 0 and 1,
	// leaving an i32 on the stack.
	private static void emitBothFloat(WasmWriter w) {
		getLocal(w, 0);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		getLocal(w, 1);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.I32_AND);
	}

	// Pushes the i31 integer `value`.
	private static void i31Const(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	// _rat_trunc/_rat_floor/_rat_ceil/_rat_round((ref null eq) x) -> (ref null eq):
	// num/den rounded by mode -- 0 truncate, 1 floor, 2 ceiling, 3 nearest with ties
	// to even (Common Lisp round) -- through _big_fdiv, exact at any tier. An exact
	// integer is already its own rounding and returns unchanged.
	static byte[] buildRatRoundingBody(int mode) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		emitIntIdentityReturn(w);

		emitComponent(w, 0, WasmLispCompiler.FUNC_RAT_NUM);
		emitComponent(w, 0, WasmLispCompiler.FUNC_RAT_DEN);
		constI32(w, mode);
		call(w, WasmLispCompiler.FUNC_BIG_FDIV);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// The binary64 scale 2^-62 that brings the normal path's 63-bit quotient to [1, 2].
	private static final double TWO_TO_MINUS_62 = 0x1.0p-62;

	// _rat_to_f64((ref null eq) x) -> f64: the double nearest the exact rational x (a
	// ratio, or an integer as itself over one), ties to even -- LispRatio.ratioToDouble,
	// so float of a ratio answers the same bits on every backend. Components within 2^53
	// are exact doubles, and one division of exact operands rounds once. Past that the
	// binary exponent e = floor(log2 |x|) comes from the integer lengths (decided before
	// any division at either end of the range); a normal result divides |x| * 2^(62 - e)
	// into a quotient q in [2^62, 2^63) whose last bit absorbs a nonzero remainder (the
	// sticky bit), so f64.convert_i64_s(q) rounds exactly once and the two power-of-two
	// scalings after it are exact (the second overflows to infinity exactly when the
	// rounded value does); a subnormal result divides |x| * 2^1074 and rounds the
	// remainder half to even itself, so a carry into 2^52 is the smallest normal.
	static byte[] buildRatToF64Body() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// params: 0=x. locals: 1=n, 2=d, 3=nn, 4=dd, 5=q (ref null eq); 6=qv, 7=nv
		// (i64); 8=neg, 9=e, 10=c (i32); 11=f (f64)
		w.write(4);
		w.write(5);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.I64);
		w.write(3);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.F64);
		final int n = 1, d = 2, nn = 3, dd = 4, q = 5, qv = 6, nv = 7, neg = 8, e = 9, c = 10, f = 11;

		emitComponent(w, 0, WasmLispCompiler.FUNC_RAT_NUM);
		setLocal(w, n);
		emitComponent(w, 0, WasmLispCompiler.FUNC_RAT_DEN);
		setLocal(w, d);

		// -2^53 <= n <= 2^53 and d <= 2^53 (d is positive): both are exact doubles.
		getLocal(w, n);
		refTestType(w, WasmLispCompiler.TYPE_BIGINT);
		getLocal(w, d);
		refTestType(w, WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, n);
		call(w, WasmLispCompiler.FUNC_INT_VAL);
		setLocal(w, nv);
		getLocal(w, d);
		call(w, WasmLispCompiler.FUNC_INT_VAL);
		setLocal(w, qv);
		getLocal(w, nv);
		constI64(w, 1L << 53);
		w.write(Instruction.I64_ADD);
		constI64(w, 1L << 54);
		w.write(Instruction.I64_LE_U);
		getLocal(w, qv);
		constI64(w, 1L << 53);
		w.write(Instruction.I64_LE_U);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		getLocal(w, nv);
		w.write(Instruction.F64_CONVERT_S_I64);
		getLocal(w, qv);
		w.write(Instruction.F64_CONVERT_S_I64);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// The sign, then the magnitude over n.
		getLocal(w, n);
		i31Const(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		constI32(w, 0);
		w.write(Instruction.I32_LT_S);
		setLocal(w, neg);
		getLocal(w, neg);
		w.write(Instruction.IF, 0x40);
		getLocal(w, n);
		call(w, WasmLispCompiler.FUNC_BIG_NEG);
		setLocal(w, n);
		w.write(Instruction.END);
		// e = len(n) - len(d); floor(log2 (n/d)) is e or e - 1.
		emitBitLength(w, n);
		emitBitLength(w, d);
		w.write(Instruction.I32_SUB);
		setLocal(w, e);

		w.write(Instruction.BLOCK);
		w.write(Type.F64);
		// Past 2^1024 whatever e settles to: infinity. Below 2^-1075: zero.
		getLocal(w, e);
		constI32(w, 1024);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		constF64(w, Double.POSITIVE_INFINITY);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		getLocal(w, e);
		constI32(w, -1076);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.IF, 0x40);
		constF64(w, 0.0);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// e = e - 1 when n < d * 2^e.
		getLocal(w, e);
		constI32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, n);
		getLocal(w, d);
		getLocal(w, e);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		w.write(Instruction.ELSE);
		getLocal(w, n);
		constI32(w, 0);
		getLocal(w, e);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		getLocal(w, d);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		w.write(Instruction.END);
		constI32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, e);
		constI32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, e);
		w.write(Instruction.END);
		getLocal(w, e);
		constI32(w, 1023);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		constF64(w, Double.POSITIVE_INFINITY);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		getLocal(w, e);
		constI32(w, -1022);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF);
		w.write(Type.F64);
		// Normal: q = floor(nn / dd) with nn / dd = (n / d) * 2^(62 - e), the shift on
		// whichever side keeps it a left shift.
		constI32(w, 62);
		getLocal(w, e);
		w.write(Instruction.I32_SUB);
		setLocal(w, c);
		getLocal(w, c);
		constI32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, n);
		getLocal(w, c);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		setLocal(w, nn);
		getLocal(w, d);
		setLocal(w, dd);
		w.write(Instruction.ELSE);
		getLocal(w, n);
		setLocal(w, nn);
		getLocal(w, d);
		constI32(w, 0);
		getLocal(w, c);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		setLocal(w, dd);
		w.write(Instruction.END);
		getLocal(w, nn);
		getLocal(w, dd);
		constI32(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_DIVREM);
		setLocal(w, q);
		getLocal(w, q);
		call(w, WasmLispCompiler.FUNC_INT_VAL);
		setLocal(w, qv);
		// sticky: q * dd != nn
		getLocal(w, q);
		getLocal(w, dd);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
		getLocal(w, nn);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		w.write(Instruction.IF, 0x40);
		getLocal(w, qv);
		constI64(w, 1);
		w.write(Instruction.I64_OR);
		setLocal(w, qv);
		w.write(Instruction.END);
		getLocal(w, qv);
		w.write(Instruction.F64_CONVERT_S_I64);
		constF64(w, TWO_TO_MINUS_62);
		w.write(Instruction.F64_MUL);
		// 2^e, built from its biased exponent field
		getLocal(w, e);
		constI32(w, 1023);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I64_EXTEND_U_I32);
		constI64(w, 52);
		w.write(Instruction.I64_SHL);
		w.write(Instruction.F64_REINTERPRET_I64);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.ELSE);
		// Subnormal: q = floor(n * 2^1074 / d), plus one when the remainder is past
		// half of d, or exactly half and q is odd.
		getLocal(w, n);
		i31Const(w, 1074);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		setLocal(w, nn);
		getLocal(w, nn);
		getLocal(w, d);
		constI32(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_DIVREM);
		setLocal(w, q);
		getLocal(w, q);
		call(w, WasmLispCompiler.FUNC_INT_VAL);
		setLocal(w, qv);
		getLocal(w, nn);
		getLocal(w, q);
		getLocal(w, d);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
		call(w, WasmLispCompiler.FUNC_BIG_SUB);
		i31Const(w, 1);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		getLocal(w, d);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		setLocal(w, c);
		getLocal(w, c);
		constI32(w, 0);
		w.write(Instruction.I32_GT_S);
		getLocal(w, c);
		w.write(Instruction.I32_EQZ);
		getLocal(w, qv);
		w.write(Instruction.I32_WRAP_I64);
		constI32(w, 1);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		getLocal(w, qv);
		constI64(w, 1);
		w.write(Instruction.I64_ADD);
		setLocal(w, qv);
		w.write(Instruction.END);
		getLocal(w, qv);
		w.write(Instruction.F64_REINTERPRET_I64);
		w.write(Instruction.END); // end normal/subnormal if
		w.write(Instruction.END); // end magnitude block
		setLocal(w, f);
		getLocal(w, neg);
		w.write(Instruction.IF);
		w.write(Type.F64);
		getLocal(w, f);
		w.write(Instruction.F64_NEG);
		w.write(Instruction.ELSE);
		getLocal(w, f);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Pushes the bit length of the non-negative exact integer in local[slot] as an i32
	// (_big_intlen, an i31 for any integer a module can hold).
	private static void emitBitLength(WasmWriter w, int slot) {
		getLocal(w, slot);
		call(w, WasmLispCompiler.FUNC_BIG_INTLEN);
		call(w, WasmLispCompiler.FUNC_INT_VAL);
		w.write(Instruction.I32_WRAP_I64);
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void constI32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void constI64(WasmWriter w, long value) {
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(value);
	}

	private static void constF64(WasmWriter w, double value) {
		w.write(Instruction.F64_CONST);
		w.writeF64(value);
	}

	private static void call(WasmWriter w, int funcIndex) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(funcIndex);
	}

	// Emits local[slot], known to be an i31, as its sign-extended i64 value.
	private static void emitI31ToI64(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I64_EXTEND_S_I32);
	}

	private static void refTestI31(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
	}

	// Emits ref.test against a concrete struct type index (e.g. TYPE_FLOAT, TYPE_RATIO).
	private static void refTestType(WasmWriter w, int typeIndex) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(typeIndex);
	}

	// Converts the value held in local[slot] to an f64 through the shared _as_f64 helper.
	// This runtime used to carry its own copy of the ladder -- sixteen call sites here,
	// which was more than half of every copy in a float program's module. _as_f64's ratio
	// arm calls _rat_to_f64, which reaches only _rat_num / _rat_den and the _big_*
	// helpers, so the arithmetic bodies here can reach it without a cycle.
	private static void emitLocalToF64(WasmWriter w, int slot) {
		getLocal(w, slot);
		call(w, WasmLispCompiler.FUNC_AS_F64);
	}

	// Emits the test `(a is exact integer) & (b is exact integer)` over locals 0 and 1
	// (an exact integer is an i31, a TYPE_BIGNUM box or a limb TYPE_BIGINT), leaving an
	// i32 on the stack.
	private static void emitBothExactInt(WasmWriter w) {
		emitIsExactInt(w, 0);
		emitIsExactInt(w, 1);
		w.write(Instruction.I32_AND);
	}

	// Emits `local[slot] is (i31 | TYPE_BIGNUM | TYPE_BIGINT)` as an i32.
	private static void emitIsExactInt(WasmWriter w, int slot) {
		getLocal(w, slot);
		refTestI31(w);
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.I32_OR);
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_OR);
	}

	// Emits an early `if (x is exact integer) return x` guard over local 0: the
	// trunc/floor/ceil/round of an integer is the integer itself.
	private static void emitIntIdentityReturn(WasmWriter w) {
		emitIsExactInt(w, 0);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// Emits the test `(a is TYPE_FLOAT) | (b is TYPE_FLOAT)` over locals 0 and 1, leaving
	// an i32 on the stack (non-zero when either operand is a float).
	private static void emitEitherFloat(WasmWriter w) {
		getLocal(w, 0);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		getLocal(w, 1);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.I32_OR);
	}

	private static void ifRefNullEq(WasmWriter w) {
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
	}

}
