package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds WASM bytecode for the rational (ratio) runtime helpers. A ratio is a normalized
 * {@code TYPE_RATIO} struct of two i32 fields (numerator, denominator): coprime,
 * denominator greater than one, sign on the numerator. A rational whose denominator
 * reduces to one is represented as a plain i31 integer, so {@code _rat_new} performs the
 * normalization and demotion. The binary arithmetic helpers keep an i31 fast path (with
 * the same wrapping i32 semantics as the inline integer arithmetic they replace) and fall
 * back to exact cross-multiplication; {@code _rat_div} always goes through
 * {@code _rat_new}, which gives Common Lisp exact division ({@code (/ 10 2)} is
 * {@code 5}, {@code (/ 10 3)} is the ratio {@code 10/3}) and traps on a zero denominator.
 * Ratio components are i31-range with no overflow promotion; the exact-integer fast
 * paths, by contrast, run through the tier-aware {@code _big_*} helpers
 * ({@link WasmBigIntRuntimeBuilder}) and stay exact at any magnitude.
 */
final class WasmRatioRuntimeBuilder {

	private WasmRatioRuntimeBuilder() {
	}

	// _rat_new(i32 num, i32 den) -> (ref null eq): traps on den == 0, moves the sign to
	// the numerator, reduces by gcd, demotes a denominator-one result to i31.
	static byte[] buildRatNewBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 0=num (param), 1=den (param), 2=a, 3=b, 4=t (all i32)
		w.write(1);
		w.write(3);
		w.write(Type.I32);

		// if (den == 0) trap
		getLocal(w, 1);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);

		// if (den < 0) { num = -num; den = -den; }
		getLocal(w, 1);
		constI32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		constI32(w, 0);
		getLocal(w, 0);
		w.write(Instruction.I32_SUB);
		setLocal(w, 0);
		constI32(w, 0);
		getLocal(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, 1);
		w.write(Instruction.END);

		// a = abs(num)
		getLocal(w, 0);
		setLocal(w, 2);
		getLocal(w, 2);
		constI32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		constI32(w, 0);
		getLocal(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, 2);
		w.write(Instruction.END);

		// b = den; Euclid: while (b != 0) { t = a % b; a = b; b = t; }
		getLocal(w, 1);
		setLocal(w, 3);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, 3);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, 2);
		getLocal(w, 3);
		w.write(Instruction.I32_REM_S);
		setLocal(w, 4);
		getLocal(w, 3);
		setLocal(w, 2);
		getLocal(w, 4);
		setLocal(w, 3);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// num /= a; den /= a (a = gcd, positive because den > 0)
		getLocal(w, 0);
		getLocal(w, 2);
		w.write(Instruction.I32_DIV_S);
		setLocal(w, 0);
		getLocal(w, 1);
		getLocal(w, 2);
		w.write(Instruction.I32_DIV_S);
		setLocal(w, 1);

		// den == 1 ? ref.i31(num) : struct.new ratio(num, den)
		getLocal(w, 1);
		constI32(w, 1);
		w.write(Instruction.I32_EQ);
		ifRefNullEq(w);
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.ELSE);
		getLocal(w, 0);
		getLocal(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_num((ref null eq) x) -> i32: a ratio's numerator, or the i31 value itself.
	static byte[] buildRatNumBody() {
		return buildRatGetBody(0);
	}

	// _rat_den((ref null eq) x) -> i32: a ratio's denominator, or 1 for an i31 integer.
	static byte[] buildRatDenBody() {
		return buildRatGetBody(1);
	}

	private static byte[] buildRatGetBody(int field) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_RATIO);
		w.writeUnsignedLeb128(field);
		w.write(Instruction.ELSE);
		if (field == 0) {
			// An i31 is its own numerator; a TYPE_BIGNUM wraps to i32 (ratio
			// components are i31-range, so mixed bignum-ratio arithmetic keeps the
			// pre-bignum truncating semantics instead of trapping).
			getLocal(w, 0);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
			w.write(Instruction.I32_WRAP_I64);
		}
		else {
			constI32(w, 1);
		}
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_add/_rat_sub/_rat_mul((ref null eq) a, (ref null eq) b) -> (ref null eq):
	// exact-integer fast path in i64 (i31 or TYPE_BIGNUM operands; the result
	// re-normalizes through _int_new, so an i31 overflow promotes to a bignum box and
	// a bignum result that fits demotes back), exact rational path otherwise.
	// Arithmetic past the i64 range wraps.
	//
	// i31Head (every level but --optimize=size) opens the body with the two-i31 case
	// answered inline: two i31s add, subtract or multiply exactly in i64 (|a|,|b| <=
	// 2^30),
	// and _int_new boxes the result in its narrowest tier -- what the _big_* path below
	// answers for the same operands, minus its two _int_val calls and the dispatch. This
	// is the path of every unfused (+ a b) over plain boxed operands (a recursive
	// function's `(+ (f ...) (f ...))` tail): measured 2026-09-19, fib 30 x 20 on
	// wasmtime 47 went 680-740 -> 415-480 ms (.kb/wasm-int-fusion.md). The size level
	// keeps the dispatch-only body, which the type-test fold can still reduce to a pure
	// forwarder of _big_* in an integer-only module (.kb/wasm-ref-type-fold.md).
	static byte[] buildRatBinaryBody(int i32Opcode, int f64Opcode, boolean i31Head) {
		int i64Opcode = i32Opcode == Instruction.I32_ADD ? Instruction.I64_ADD
				: i32Opcode == Instruction.I32_SUB ? Instruction.I64_SUB : Instruction.I64_MUL;
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
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(f64Opcode);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);

		emitBothExactInt(w);
		ifRefNullEq(w);
		// fast path: both exact integers at any tier -- _big_add/_sub/_mul keep an
		// i64 fast path first and promote to the limb tier instead of wrapping
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, i64Opcode == Instruction.I64_ADD ? WasmLispCompiler.FUNC_BIG_ADD
				: i64Opcode == Instruction.I64_SUB ? WasmLispCompiler.FUNC_BIG_SUB : WasmLispCompiler.FUNC_BIG_MUL);
		w.write(Instruction.ELSE);
		if (i32Opcode == Instruction.I32_MUL) {
			// _rat_new(num(a)*num(b), den(a)*den(b))
			getLocal(w, 0);
			call(w, WasmLispCompiler.FUNC_RAT_NUM);
			getLocal(w, 1);
			call(w, WasmLispCompiler.FUNC_RAT_NUM);
			w.write(Instruction.I32_MUL);
		}
		else {
			// _rat_new(num(a)*den(b) <op> num(b)*den(a), den(a)*den(b))
			getLocal(w, 0);
			call(w, WasmLispCompiler.FUNC_RAT_NUM);
			getLocal(w, 1);
			call(w, WasmLispCompiler.FUNC_RAT_DEN);
			w.write(Instruction.I32_MUL);
			getLocal(w, 1);
			call(w, WasmLispCompiler.FUNC_RAT_NUM);
			getLocal(w, 0);
			call(w, WasmLispCompiler.FUNC_RAT_DEN);
			w.write(Instruction.I32_MUL);
			w.write(i32Opcode);
		}
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I32_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_NEW);
		w.write(Instruction.END); // end i31-fast-path if

		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_div((ref null eq) a, (ref null eq) b) -> (ref null eq): exact Common Lisp
	// division _rat_new(num(a)*den(b), den(a)*num(b)); traps on division by zero. Two
	// exact-integer operands that divide evenly take an i64 fast path (so a bignum
	// divided exactly stays exact, e.g. (/ #x100000000 2)); an uneven bignum division
	// falls through to the i32 ratio path, where the components wrap (ratio components
	// stay i31-range).
	static byte[] buildRatDivBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 2=a64, 3=b64 (both i64), 4=r (ref null eq, the tier-aware remainder)
		w.write(2);
		w.write(2);
		w.write(Type.I64);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());

		// Float fast path: f64 division when either operand is a float.
		emitEitherFloat(w);
		ifRefNullEq(w);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);

		emitBothExactInt(w);
		ifRefNullEq(w);
		// both exact integers: even division stays exact at any tier (_big_divrem
		// traps on b == 0, preserving the divide-by-zero trap)
		getLocal(w, 0);
		getLocal(w, 1);
		constI32(w, 1);
		call(w, WasmLispCompiler.FUNC_BIG_DIVREM);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);
		getLocal(w, 4);
		constI32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.REF_EQ);
		ifRefNullEq(w);
		getLocal(w, 0);
		getLocal(w, 1);
		constI32(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_DIVREM);
		w.write(Instruction.ELSE);
		emitRatDivRatioPath(w);
		w.write(Instruction.END); // end even-division if
		w.write(Instruction.ELSE);
		emitRatDivRatioPath(w);
		w.write(Instruction.END); // end exact-int if

		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// The exact rational division tail: _rat_new(num(a)*den(b), den(a)*num(b)) over the
	// i32 ratio components.
	private static void emitRatDivRatioPath(WasmWriter w) {
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I32_MUL);
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.I32_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_NEW);
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
	// variable is handled.
	static byte[] buildRatRemBody(boolean mod) {
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
		WasmFmodRuntimeBuilder.emitRemainder(w, mod, 2, 3, 4, 5, 6);
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

		// General path: a - b * (trunc|floor)(a / b) via the exact rational helpers, so
		// a ratio operand also works (and an i31 still reduces exactly).
		getLocal(w, 0); // a (first arg of _rat_sub)
		getLocal(w, 1); // b (first arg of _rat_mul)
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_DIV); // a / b
		call(w, mod ? WasmLispCompiler.FUNC_RAT_FLOOR : WasmLispCompiler.FUNC_RAT_TRUNC); // q
		call(w, WasmLispCompiler.FUNC_RAT_MUL); // b * q
		call(w, WasmLispCompiler.FUNC_RAT_SUB); // a - b*q
		w.write(Instruction.END); // end i31-fast-path if

		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_cmp((ref null eq) a, (ref null eq) b) -> i32: -1/0/1 by cross-multiplication
	// in i64 (denominators are positive, so the comparison direction is preserved).
	static byte[] buildRatCmpBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 2=left (i64), 3=right (i64)
		w.write(1);
		w.write(2);
		w.write(Type.I64);

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

		// Exact-integer fast path: _big_cmp compares at any tier (a bignum or limb
		// operand must not go through the i32 ratio components).
		emitBothExactInt(w);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		w.write(Instruction.ELSE);

		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.I64_EXTEND_S_I32);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I64_EXTEND_S_I32);
		w.write(Instruction.I64_MUL);
		setLocal(w, 2);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.I64_EXTEND_S_I32);
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I64_EXTEND_S_I32);
		w.write(Instruction.I64_MUL);
		setLocal(w, 3);
		// (left > right) - (left < right)
		getLocal(w, 2);
		getLocal(w, 3);
		w.write(Instruction.I64_GT_S);
		getLocal(w, 2);
		getLocal(w, 3);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.I32_SUB);
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
	// _rat_cmp (exact, never unordered). A float against an exact integer or ratio
	// compares EXACT values -- the float's exact binary value (as `rational` answers
	// it) against the exact operand -- through the existing big-tier helpers alone
	// (`_int_new` of the decomposed mantissa, `_big_ash`, `_big_mul`, `_big_cmp`; no
	// new runtime function), so a near tie decides strictly: `(= 0.6666666666666666
	// 2/3)` is NIL here as on the interpreter and the JVM (.todo/037). A float
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
		// pick their bit by the same sign.
		final int fl = 2, ex = 3, d = 4, bits = 5, mant = 6, exp = 7, sgn = 8, tmp = 9, nf = 10, df = 11, ne = 12,
				de = 13;
		w.write(5);
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

		emitEitherFloat(w);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitBothFloat(w);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// lt -> 1, gt -> 4, eq -> 2, else (NaN) -> 0
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
		w.write(Instruction.ELSE);
		// Exactly one operand is a float: split the pair so FL holds it, and
		// record its side in SGN (1 for a, -1 for b).
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
		// The exact operand's numerator/denominator: itself over one, or the
		// i32 ratio components (which never leave i31 range) lifted through
		// `_int_new`.
		getLocal(w, ex);
		refTestType(w, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF, 0x40);
		getLocal(w, ex);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.I64_EXTEND_S_I32);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		setLocal(w, ne);
		getLocal(w, ex);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I64_EXTEND_S_I32);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		setLocal(w, de);
		w.write(Instruction.ELSE);
		getLocal(w, ex);
		setLocal(w, ne);
		i31Const(w, 1);
		setLocal(w, de);
		w.write(Instruction.END);
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
		w.write(Instruction.END); // end both-float if
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

	// _rat_trunc((ref null eq) x) -> (ref null eq): num/den truncating toward zero.
	// An exact integer (i31 or TYPE_BIGNUM) is already its own truncation and returns
	// unchanged (the i32 component path would wrap a bignum).
	static byte[] buildRatTruncBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		emitIntIdentityReturn(w);

		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I32_DIV_S);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_floor/_rat_ceil((ref null eq) x) -> (ref null eq): truncating division
	// adjusted by one when there is a remainder and the value is negative (floor) or
	// positive (ceiling). The denominator is always positive.
	static byte[] buildRatFloorBody(boolean ceiling) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 1=num, 2=den, 3=q (all i32)
		w.write(1);
		w.write(3);
		w.write(Type.I32);

		emitIntIdentityReturn(w);

		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		setLocal(w, 1);
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		setLocal(w, 2);
		getLocal(w, 1);
		getLocal(w, 2);
		w.write(Instruction.I32_DIV_S);
		setLocal(w, 3);
		// floor: if (num % den != 0 && num < 0) q -= 1
		// ceiling: if (num % den != 0 && num > 0) q += 1
		getLocal(w, 1);
		getLocal(w, 2);
		w.write(Instruction.I32_REM_S);
		constI32(w, 0);
		w.write(Instruction.I32_NE);
		getLocal(w, 1);
		constI32(w, 0);
		w.write(ceiling ? Instruction.I32_GT_S : Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 3);
		constI32(w, 1);
		w.write(ceiling ? Instruction.I32_ADD : Instruction.I32_SUB);
		setLocal(w, 3);
		w.write(Instruction.END);
		getLocal(w, 3);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_round((ref null eq) x) -> (ref null eq): nearest integer, ties to even
	// (Common Lisp round semantics).
	static byte[] buildRatRoundBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 1=num, 2=den, 3=floor, 4=remainder, 5=twice (all i32)
		w.write(1);
		w.write(5);
		w.write(Type.I32);

		emitIntIdentityReturn(w);

		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		setLocal(w, 1);
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		setLocal(w, 2);
		// floor = floorDiv(num, den)
		getLocal(w, 1);
		getLocal(w, 2);
		w.write(Instruction.I32_DIV_S);
		setLocal(w, 3);
		getLocal(w, 1);
		getLocal(w, 2);
		w.write(Instruction.I32_REM_S);
		constI32(w, 0);
		w.write(Instruction.I32_NE);
		getLocal(w, 1);
		constI32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 3);
		constI32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, 3);
		w.write(Instruction.END);
		// remainder = num - floor * den (0 <= remainder < den)
		getLocal(w, 1);
		getLocal(w, 3);
		getLocal(w, 2);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_SUB);
		setLocal(w, 4);
		// twice = remainder * 2
		getLocal(w, 4);
		constI32(w, 1);
		w.write(Instruction.I32_SHL);
		setLocal(w, 5);
		// twice < den -> floor; twice > den -> floor + 1; tie -> floor + (floor & 1)
		getLocal(w, 5);
		getLocal(w, 2);
		w.write(Instruction.I32_LT_S);
		ifRefNullEq(w);
		getLocal(w, 3);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.ELSE);
		getLocal(w, 5);
		getLocal(w, 2);
		w.write(Instruction.I32_GT_S);
		ifRefNullEq(w);
		getLocal(w, 3);
		constI32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.ELSE);
		getLocal(w, 3);
		getLocal(w, 3);
		constI32(w, 1);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
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
	// which was more than half of every copy in a float program's module. _as_f64 calls
	// _rat_num / _rat_den, which read struct fields and call nothing, so the arithmetic
	// bodies below can reach it without a cycle.
	private static void emitLocalToF64(WasmWriter w, int slot) {
		getLocal(w, slot);
		call(w, WasmLispCompiler.FUNC_AS_F64);
	}

	// Emits the test `(a is exact integer) & (b is exact integer)` over locals 0 and 1
	// (an exact integer is an i31 or a TYPE_BIGNUM box), leaving an i32 on the stack.
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
	// trunc/floor/ceil/round of an integer is the integer itself, and the i32
	// component path below would wrap a TYPE_BIGNUM.
	private static void emitIntIdentityReturn(WasmWriter w) {
		emitIsExactInt(w, 0);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// Emits the test `(a is TYPE_FLOAT) | (b is TYPE_FLOAT)` over locals 0 and 1, leaving
	// an
	// i32 on the stack (non-zero when either operand is a float).
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
