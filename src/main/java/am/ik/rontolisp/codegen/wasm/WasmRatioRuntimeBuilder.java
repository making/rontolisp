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
	static byte[] buildRatBinaryBody(int i32Opcode) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

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
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rat_div((ref null eq) a, (ref null eq) b) -> (ref null eq): exact Common Lisp
	// division _rat_new(num(a)*den(b), den(a)*num(b)); traps on division by zero.
	static byte[] buildRatDivBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

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

	private static void ifRefNullEq(WasmWriter w) {
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
	}

}
