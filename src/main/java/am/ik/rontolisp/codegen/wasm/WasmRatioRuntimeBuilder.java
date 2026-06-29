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
 * Like all WASM integer arithmetic, ratio components are i31-range with no overflow
 * promotion.
 */
final class WasmRatioRuntimeBuilder {

	private WasmRatioRuntimeBuilder() {
	}

	// _rat_new(i32 num, i32 den) -> (ref null eq): traps on den == 0, moves the sign to
	// the numerator, reduces by gcd, demotes a denominator-one result to i31.
	static byte[] buildRatNewBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
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
		w.writeSignedLeb128(WasmLispCompiler.TYPE_RATIO);
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
		ByteArrayOutputStream body = new ByteArrayOutputStream();
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
		w.writeSignedLeb128(WasmLispCompiler.TYPE_RATIO);
		w.writeSignedLeb128(field);
		w.write(Instruction.ELSE);
		if (field == 0) {
			getLocal(w, 0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(Type.I31.code());
			w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		}
		else {
			constI32(w, 1);
		}
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_add/_rat_sub/_rat_mul((ref null eq) a, (ref null eq) b) -> (ref null eq):
	// i31 fast path with plain i32 arithmetic, exact rational path otherwise.
	static byte[] buildRatBinaryBody(int i32Opcode, int f64Opcode) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		// Float fast path: if either operand is a float, compute in f64 (float contagion)
		// and box the result. Mirrors the JVM _add/_sub/_mul Double prologue.
		emitEitherFloat(w);
		ifRefNullEq(w);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(f64Opcode);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);

		getLocal(w, 0);
		refTestI31(w);
		getLocal(w, 1);
		refTestI31(w);
		w.write(Instruction.I32_AND);
		ifRefNullEq(w);
		// fast path: both i31
		getLocal(w, 0);
		castI31GetS(w);
		getLocal(w, 1);
		castI31GetS(w);
		w.write(i32Opcode);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
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
	// division _rat_new(num(a)*den(b), den(a)*num(b)); traps on division by zero.
	static byte[] buildRatDivBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		// Float fast path: f64 division when either operand is a float.
		emitEitherFloat(w);
		ifRefNullEq(w);
		emitLocalToF64(w, 0);
		emitLocalToF64(w, 1);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);

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
		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_rem/_rat_mod((ref null eq) a, (ref null eq) b) -> (ref null eq): the Common
	// Lisp remainder (sign of the dividend) and modulo (sign of the divisor). Both are
	// a - b*q with q = trunc(a/b) for rem and q = floor(a/b) for mod. A float operand
	// (either side) computes q in f64 (f64.trunc / f64.floor); two i31 integers take a
	// fast i32 path (i32.rem_s, plus the divisor-sign correction for mod); otherwise the
	// exact rational helpers compute a - b*(trunc|floor)(a/b). Mirrors the dispatch shape
	// of buildRatBinaryBody so a float reaching mod/rem through a variable is handled.
	static byte[] buildRatRemBody(boolean mod) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 2=fa (f64), 3=fb (f64), 4=ri (i32, the i31 fast-path remainder)
		w.write(2);
		w.write(2);
		w.write(Type.F64);
		w.write(1);
		w.write(Type.I32);

		// Float path: a - b * (floor|trunc)(a / b), all in f64, boxed as TYPE_FLOAT.
		emitEitherFloat(w);
		ifRefNullEq(w);
		emitLocalToF64(w, 0);
		setLocal(w, 2);
		emitLocalToF64(w, 1);
		setLocal(w, 3);
		getLocal(w, 2); // fa
		getLocal(w, 3); // fb
		getLocal(w, 2);
		getLocal(w, 3);
		w.write(Instruction.F64_DIV);
		w.write(mod ? Instruction.F64_FLOOR : Instruction.F64_TRUNC); // q
		w.write(Instruction.F64_MUL); // fb*q
		w.write(Instruction.F64_SUB); // fa - fb*q
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.ELSE);

		// Non-float: fast i32 path when both operands are i31 integers.
		getLocal(w, 0);
		refTestI31(w);
		getLocal(w, 1);
		refTestI31(w);
		w.write(Instruction.I32_AND);
		ifRefNullEq(w);
		// r = a rem_s b (truncated remainder, sign of the dividend = Common Lisp rem)
		getLocal(w, 0);
		castI31GetS(w);
		getLocal(w, 1);
		castI31GetS(w);
		w.write(Instruction.I32_REM_S);
		setLocal(w, 4);
		if (mod) {
			// mod = r + ((r != 0 && sign(r) != sign(b)) ? b : 0). The sign test is
			// (r ^ b) < 0, which avoids the r*b multiplication (and its i31 overflow).
			getLocal(w, 4);
			getLocal(w, 4);
			constI32(w, 0);
			w.write(Instruction.I32_NE);
			getLocal(w, 4);
			getLocal(w, 1);
			castI31GetS(w);
			w.write(Instruction.I32_XOR);
			constI32(w, 0);
			w.write(Instruction.I32_LT_S);
			w.write(Instruction.I32_AND);
			w.write(Instruction.IF);
			w.write(Type.I32);
			getLocal(w, 1);
			castI31GetS(w);
			w.write(Instruction.ELSE);
			constI32(w, 0);
			w.write(Instruction.END);
			w.write(Instruction.I32_ADD);
		}
		else {
			getLocal(w, 4);
		}
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
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
		ByteArrayOutputStream body = new ByteArrayOutputStream();
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
		w.write(Instruction.END); // end float-fast-path if

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_trunc((ref null eq) x) -> (ref null eq): num/den truncating toward zero.
	static byte[] buildRatTruncBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

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
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 1=num, 2=den, 3=q (all i32)
		w.write(1);
		w.write(3);
		w.write(Type.I32);

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
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 1=num, 2=den, 3=floor, 4=remainder, 5=twice (all i32)
		w.write(1);
		w.write(5);
		w.write(Type.I32);

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
		w.writeSignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(slot);
	}

	private static void constI32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void call(WasmWriter w, int funcIndex) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(funcIndex);
	}

	private static void refTestI31(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
	}

	private static void castI31GetS(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	// Emits ref.test against a concrete struct type index (e.g. TYPE_FLOAT, TYPE_RATIO).
	private static void refTestType(WasmWriter w, int typeIndex) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(typeIndex);
	}

	// Converts the value held in local[slot] to an f64, dispatching on its runtime type:
	// an i31 integer converts directly, a ratio divides numerator by denominator, and a
	// TYPE_FLOAT struct yields its field. Mirrors WasmEmitHelper.castFloatGetF64 but
	// works
	// on a value already stored in a local (no temp allocation), so it is usable from the
	// raw-WasmWriter ratio runtime.
	private static void emitLocalToF64(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.F64);
		getLocal(w, slot);
		castI31GetS(w);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.ELSE);
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF);
		w.write(Type.F64);
		getLocal(w, slot);
		call(w, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.F64_CONVERT_S_I32);
		getLocal(w, slot);
		call(w, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.ELSE);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
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
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
	}

}
