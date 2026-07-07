package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds WASM bytecode for runtime helper functions: dispatch, print_i32, write_str,
 * print_val, and print_f64.
 */
final class WasmRuntimeBuilder {

	private WasmRuntimeBuilder() {
	}

	/**
	 * Builds the _append helper function body. Takes two (ref null eq) args, returns (ref
	 * null eq). If a is null, returns b. Otherwise, creates struct.new cons(a.car,
	 * _append(a.cdr, b)).
	 */
	static byte[] buildAppendBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // 0 extra locals

		// if a is null, return b
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // a
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // b
		w.write(Instruction.ELSE);

		// a.car
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0); // field 0: car

		// _append(a.cdr, b)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1); // field 1: cdr
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // b
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_APPEND);

		// struct.new cons(car, result)
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);

		w.write(Instruction.END); // end if

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the _equal helper function body (structural equality). Takes two (ref null
	 * eq) args (locals 0 and 1), returns i32 (1=equal, 0=not). Identical references
	 * (ref.eq, which also covers i31 integers) are equal; two cons cells are equal when
	 * their cars and cdrs are recursively _equal; otherwise it reproduces eql semantics
	 * for the remaining value types (floats by value, ratios by numerator/denominator,
	 * symbols and strings by interned offset).
	 */
	static byte[] buildEqualBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // 0 extra locals; params (local 0 = a, local 1 = b) suffice

		// if (ref.eq a b) -> 1
		getLocal(w, 0);
		getLocal(w, 1);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.ELSE);

		// else if both cons -> _equal(car, car) && _equal(cdr, cdr)
		refTest(w, 0, WasmLispCompiler.TYPE_CONS);
		refTest(w, 1, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		consField(w, 0, 0); // a.car
		consField(w, 1, 0); // b.car
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		consField(w, 0, 1); // a.cdr
		consField(w, 1, 1); // b.cdr
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // end car-equal if
		w.write(Instruction.ELSE);

		// else (ref.eq already false): eql base case for value types.
		// both characters -> code points equal
		refTest(w, 0, WasmLispCompiler.TYPE_CHAR);
		refTest(w, 1, WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		charField(w, 0);
		charField(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.ELSE);

		// both floats -> f64 fields equal
		refTest(w, 0, WasmLispCompiler.TYPE_FLOAT);
		refTest(w, 1, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		floatField(w, 0);
		floatField(w, 1);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.ELSE);

		// both ratios -> numerators and denominators equal
		refTest(w, 0, WasmLispCompiler.TYPE_RATIO);
		refTest(w, 1, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		ratioComponent(w, 0, WasmLispCompiler.FUNC_RAT_NUM);
		ratioComponent(w, 1, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.I32_EQ);
		ratioComponent(w, 0, WasmLispCompiler.FUNC_RAT_DEN);
		ratioComponent(w, 1, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.ELSE);

		// symbols and strings -> byte-wise same content (via _string_eq), so a
		// runtime-built string is equal to a literal with the same content
		emitStringContentEq(w);
		w.write(Instruction.END); // end ratio if
		w.write(Instruction.END); // end float if
		w.write(Instruction.END); // end char if
		w.write(Instruction.END); // end both-cons if
		w.write(Instruction.END); // end ref.eq if

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the _hash helper (structural hash). Takes one (ref null eq) arg (local 0),
	 * returns an i32 hash that agrees with {@link #buildEqualBody _equal}: equal values
	 * hash equal. It walks conses recursively and folds i31 integers, character codes,
	 * string/symbol content bytes, float bit patterns and ratio components into the
	 * result. Value types not recognised by {@code _equal}'s eql base case (e.g.
	 * closures, which {@code equal} compares by identity) hash to a constant 0, which is
	 * correct (they simply collide into one bucket).
	 */
	static byte[] buildHashBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// one i64 local (index 1) used to fold a float's 64-bit pattern into i32,
		// three i32 locals (2 = h, 3 = idx, 4 = end) for the string byte fold, and one
		// $str_bytes ref (5 = arr) holding the string's data array.
		w.write(3); // 3 local groups
		w.write(1); // 1 local
		w.write(Type.I64);
		w.write(3); // 3 locals
		w.write(Type.I32);
		w.write(1); // 1 local
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_STR_BYTES);

		// if v is null -> 0
		getLocal(w, 0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.ELSE);

		// i31 integer -> its signed value
		refTest(w, 0, Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);

		// cons -> hash(car) * 31 + hash(cdr) + 1
		refTest(w, 0, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.IF);
		w.write(Type.I32);
		consField(w, 0, 0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_HASH);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(31);
		w.write(Instruction.I32_MUL);
		consField(w, 0, 1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_HASH);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.ELSE);

		// character -> code point
		refTest(w, 0, WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.IF);
		w.write(Type.I32);
		charField(w, 0);
		w.write(Instruction.ELSE);

		// symbol or string -> fold the content bytes (h = h * 31 + byte), so the
		// hash agrees with _equal's byte-wise string comparison
		refTest(w, 0, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2); // h = 0
		getLocal(w, 0);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(5); // arr = string.data
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // idx = 0 (array index)
		stringLength(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(4); // end = length
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, 3);
		getLocal(w, 4);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, 2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(31);
		w.write(Instruction.I32_MUL);
		getLocal(w, 5);
		getLocal(w, 3);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2); // h = h * 31 + arr[idx]
		getLocal(w, 3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // idx = idx + 1
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block
		getLocal(w, 2);
		w.write(Instruction.ELSE);

		// float -> fold the 64-bit pattern's halves
		refTest(w, 0, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF);
		w.write(Type.I32);
		floatField(w, 0);
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);
		getLocal(w, 1);
		w.write(Instruction.I32_WRAP_I64);
		getLocal(w, 1);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(32);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_XOR);
		w.write(Instruction.ELSE);

		// ratio -> numerator * 31 + denominator
		refTest(w, 0, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF);
		w.write(Type.I32);
		ratioComponent(w, 0, WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(31);
		w.write(Instruction.I32_MUL);
		ratioComponent(w, 0, WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.ELSE);

		// anything else (e.g. a closure) -> 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);

		w.write(Instruction.END); // end ratio if
		w.write(Instruction.END); // end float if
		w.write(Instruction.END); // end string if
		w.write(Instruction.END); // end char if
		w.write(Instruction.END); // end cons if
		w.write(Instruction.END); // end i31 if
		w.write(Instruction.END); // end null if

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the _hash_resize helper. Takes the table's header cons (local 0 =
	 * {@code (count . buckets)}), doubles the bucket array and rehashes every entry into
	 * it, then stores the new array back into the header's cdr. Returns nothing.
	 */
	static byte[] buildHashResizeBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 4 x (ref null eq) [1=oldArr 2=newArr 3=cur 4=entry], 3 x i32 [5=i
		// 6=newCap 7=j]
		w.write(2); // 2 local groups
		w.writeUnsignedLeb128(4);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);

		int oldArr = 1, newArr = 2, cur = 3, entry = 4, i = 5, newCap = 6, j = 7;

		// oldArr = header.cdr
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		setLocal(w, oldArr);

		// newCap = len(oldArr) * 2
		getLocal(w, oldArr);
		castBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_MUL);
		setLocal(w, newCap);

		// newArr = array.new buckets (null, newCap)
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		getLocal(w, newCap);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, newArr);

		// i = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, i);

		w.write(Instruction.BLOCK, 0x40); // $outer
		w.write(Instruction.LOOP, 0x40); // $o
		// if i >= len(oldArr) break $outer
		getLocal(w, i);
		getLocal(w, oldArr);
		castBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		// cur = oldArr[i]
		getLocal(w, oldArr);
		castBuckets(w);
		getLocal(w, i);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, cur);

		w.write(Instruction.BLOCK, 0x40); // $inner
		w.write(Instruction.LOOP, 0x40); // $in
		// if cur not cons break $inner
		getLocal(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// entry = car(cur)
		getLocal(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		setLocal(w, entry);
		// j = (hash(car(entry)) & 0x7fffffff) % newCap
		getLocal(w, entry);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_HASH);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7fffffff);
		w.write(Instruction.I32_AND);
		getLocal(w, newCap);
		w.write(Instruction.I32_REM_U);
		setLocal(w, j);
		// newArr[j] = cons(entry, newArr[j])
		getLocal(w, newArr);
		castBuckets(w);
		getLocal(w, j);
		getLocal(w, entry);
		getLocal(w, newArr);
		castBuckets(w);
		getLocal(w, j);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		// cur = cdr(cur)
		getLocal(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		setLocal(w, cur);
		w.write(Instruction.BR, 0); // loop $in
		w.write(Instruction.END); // end loop $in
		w.write(Instruction.END); // end block $inner
		// i = i + 1
		getLocal(w, i);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, i);
		w.write(Instruction.BR, 0); // loop $o
		w.write(Instruction.END); // end loop $o
		w.write(Instruction.END); // end block $outer

		// header.cdr = newArr
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		getLocal(w, newArr);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	private static void setLocal(WasmWriter w, int idx) {
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(idx);
	}

	private static void castBuckets(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static void charField(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.writeSignedLeb128(0);
	}

	// 1 if both locals are TYPE_STRING structs with byte-wise equal content, else 0.
	// Content comparison (not offset comparison) so runtime-built strings compare
	// equal to interned literals; _hash folds the same bytes so the invariant
	// "equal keys hash equal" holds for hash-table keys.
	private static void emitStringContentEq(WasmWriter w) {
		refTest(w, 0, WasmLispCompiler.TYPE_STRING);
		refTest(w, 1, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, 0);
		getLocal(w, 1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_STRING_EQ);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // end both-strings if
	}

	private static void getLocal(WasmWriter w, int idx) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(idx);
	}

	private static void refTest(WasmWriter w, int local, int typeIndex) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(typeIndex);
	}

	private static void consField(WasmWriter w, int local, int field) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(field);
	}

	private static void floatField(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
	}

	private static void stringOffset(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
	}

	private static void stringLength(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
	}

	private static void ratioComponent(WasmWriter w, int local, int func) {
		getLocal(w, local);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(func);
	}

	static byte[] buildDispatchBody(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, int numDefuns, WasmLispCompiler.StringTable st,
			boolean usesEval) {
		// Collect all functions with matching arity. A variadic function (physical
		// params = required + rest list) matches every dispatch arity >= required; its
		// case links the surplus args into a cons list before the call.
		record Target(int funcId, int funcIndex, int required, boolean variadic) {
		}
		List<Target> targets = new ArrayList<>();
		for (int i = 0; i < defuns.size(); i++) {
			WasmLispCompiler.DefunDecl defun = defuns.get(i);
			int paramCount = defun.paramNames().size();
			if (defun.variadic() ? arity >= paramCount - 1 : paramCount == arity) {
				targets.add(new Target(i, WasmLispCompiler.FUNC_USER_BASE + i,
						defun.variadic() ? paramCount - 1 : paramCount, defun.variadic()));
			}
		}
		for (int i = 0; i < lambdaDecls.size(); i++) {
			WasmLispCompiler.LambdaInfo lambda = lambdaDecls.get(i);
			int paramCount = lambda.paramNames().size();
			if (lambda.variadic() ? arity >= paramCount - 1 : paramCount == arity) {
				targets.add(new Target(lambda.funcId(), lambda.funcIndex(),
						lambda.variadic() ? paramCount - 1 : paramCount, lambda.variadic()));
			}
		}

		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: param 0 = funcval, params 1..arity = args
		// Extra locals: funcId (i32) and the arg list for the _apply fallback (ref)
		w.write(2); // 2 local groups
		w.write(1); // 1 local of type i32
		w.write(Type.I32);
		w.write(1); // 1 local of type (ref null eq)
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		int funcIdLocal = arity + 1; // after params
		int argListLocal = arity + 2;

		// Extract funcId from closure struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // funcval
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.writeSignedLeb128(0); // field 0: funcId
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(funcIdLocal);

		// Interpreted closure (funcId == -1, created by the eval runtime's lambda):
		// delegate to _apply with the arguments collected into a cons list
		if (usesEval) {
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(funcIdLocal);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(-1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			w.write(Instruction.SET_LOCAL);
			w.writeSignedLeb128(argListLocal);
			for (int a = arity; a >= 1; a--) {
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(a);
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(argListLocal);
				w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
				w.write(Instruction.SET_LOCAL);
				w.writeSignedLeb128(argListLocal);
			}
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(0);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(argListLocal);
			w.write(Instruction.CALL);
			w.writeSignedLeb128(WasmLispCompiler.FUNC_APPLY);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
		}

		if (targets.isEmpty()) {
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			return body.toByteArray();
		}

		int numCases = targets.size();
		int maxFuncId = 0;
		for (Target t : targets) {
			maxFuncId = Math.max(maxFuncId, t.funcId);
		}

		// Result block (typed)
		w.write(Instruction.BLOCK);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		// Default block (void)
		w.write(Instruction.BLOCK, 0x40);

		// Case blocks (void) - outermost case first
		for (int i = 0; i < numCases; i++) {
			w.write(Instruction.BLOCK, 0x40);
		}

		// Load funcId and br_table
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(funcIdLocal);

		// Build funcId -> br_table depth mapping
		Map<Integer, Integer> funcIdToCase = new HashMap<>();
		for (int j = 0; j < targets.size(); j++) {
			funcIdToCase.put(targets.get(j).funcId, j);
		}

		w.write(Instruction.BR_TABLE);
		w.writeUnsignedLeb128(maxFuncId + 1); // label count
		for (int fid = 0; fid <= maxFuncId; fid++) {
			Integer caseJ = funcIdToCase.get(fid);
			if (caseJ != null) {
				w.writeUnsignedLeb128(numCases - 1 - caseJ);
			}
			else {
				w.writeUnsignedLeb128(numCases); // default
			}
		}
		w.writeUnsignedLeb128(numCases); // default label

		// Case bodies: close blocks from innermost to outermost
		for (int k = 0; k < numCases; k++) {
			w.write(Instruction.END); // end of $case_{numCases-1-k}
			int targetIdx = numCases - 1 - k;
			Target target = targets.get(targetIdx);
			if (target.variadic) {
				// Link args required+1..arity into a cons list (right to left)
				w.write(Instruction.REF_NULL);
				w.writeHeapType(Type.EQ.code());
				w.write(Instruction.SET_LOCAL);
				w.writeSignedLeb128(argListLocal);
				for (int a = arity; a >= target.required + 1; a--) {
					w.write(Instruction.GET_LOCAL);
					w.writeSignedLeb128(a);
					w.write(Instruction.GET_LOCAL);
					w.writeSignedLeb128(argListLocal);
					w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
					w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
					w.write(Instruction.SET_LOCAL);
					w.writeSignedLeb128(argListLocal);
				}
			}
			// Extract env from closure struct
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(0); // funcval
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
			w.writeSignedLeb128(1); // field 1: env
			// Push args (for a variadic target, the required ones plus the rest list)
			for (int a = 1; a <= target.required; a++) {
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(a);
			}
			if (target.variadic) {
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(argListLocal);
			}
			else {
				for (int a = target.required + 1; a <= arity; a++) {
					w.write(Instruction.GET_LOCAL);
					w.writeSignedLeb128(a);
				}
			}
			// Call target function
			w.write(Instruction.CALL);
			w.writeSignedLeb128(target.funcIndex);
			// Break to $result block
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(numCases - k);
		}

		// End default block
		w.write(Instruction.END); // $default
		w.write(Instruction.UNREACHABLE);

		// End result block
		w.write(Instruction.END); // $result

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the _read_line helper function body. Reads one line from the given file
	 * descriptor (0 = stdin) using fd_read, byte by byte. Returns a string struct with
	 * '"' prefix/suffix (internal string format), or ref.null eq on EOF.
	 */
	static byte[] buildReadLineBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Param: 0=fd (i32)
		// Locals: 1=heap_ptr (i32), 2=pos (i32), 3=nread (i32), 4=eof_flag (i32),
		// 5=rec (i32), 6=cursor (i32), 7=endp (i32), 8=llen (i32) (5-8 only for the
		// string-stream branch; 2 and 3 are reused there as scan/copy cursors)
		w.write(5);
		w.write(1);
		w.write(Type.I32); // local 1: heap_ptr
		w.write(1);
		w.write(Type.I32); // local 2: pos
		w.write(1);
		w.write(Type.I32); // local 3: nread
		w.write(1);
		w.write(Type.I32); // local 4: eof_flag
		w.write(4);
		w.write(Type.I32); // locals 5-8: rec, cursor, endp, llen

		// A negative fd is a string input stream (see WasmStringStreamRuntimeBuilder):
		// return the next line of its [cursor, end) byte range as a fresh quote-framed
		// heap string, or nil at end of input.
		emitReadLineFromStringStream(w);

		// heap_ptr = memory[HEAP_PTR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		// memory[heap_ptr] = 0x22 ('"' prefix)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// pos = 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		// eof_flag = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(4);

		// Loop: read one byte at a time
		w.write(Instruction.BLOCK, 0x40); // block $break
		w.write(Instruction.LOOP, 0x40); // loop $continue

		// Set iov: ptr = heap_ptr + pos, len = 1
		// iov_buf_ptr at IOV_OFFSET
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // heap_ptr
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // pos
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// iov_buf_len at IOV_OFFSET + 4
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET + 4);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// nread = fd_read(fd, iov, 1, nwritten_addr)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // fd param
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // iovs_len = 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP); // drop errno

		// nread = memory[NWRITTEN_OFFSET]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// if nread == 0: set eof_flag, break
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.BR, 2); // break out of loop and block
		w.write(Instruction.END);

		// byte = memory[heap_ptr + pos]
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		// if byte == 0x0A (newline): break
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x0A);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF, 1); // break out of block

		// pos++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0); // continue loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		// if pos > 1 && memory[heap_ptr + pos - 1] == 0x0D: pos-- -- strip one
		// trailing carriage return for CRLF parity with BufferedReader.readLine
		// (the interpreter and JVM backends strip it the same way; without this,
		// CRLF-terminated socket lines -- e.g. HTTP -- keep a trailing \r and a
		// blank CRLF line never compares string= to "").
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x0D);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.END);

		// if pos == 1 && eof_flag: return ref.null eq (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.ELSE);

		// memory[heap_ptr + pos] = 0x22 ('"' suffix)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// HEAP_PTR is NOT advanced: _str_fresh copies the line into a fresh GC array, so
		// leaving HEAP_PTR at heap_ptr (a stack pop) reuses the scratch for the next
		// build.
		// return _str_fresh(heap_ptr, pos + 1) -- a runtime string gets a fresh counter
		// id
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // offset
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // pos
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD); // length = pos + 1
		WasmEmitHelper.emitStrFreshCall(w);

		w.write(Instruction.END); // end if/else

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Emits the string-input-stream branch of {@code _read_line}: when the fd is
	 * negative, its absolute value is an input record {@code [kind=0][cursor][end]} in
	 * linear memory. Returns the next line (up to a newline or the end) as a fresh
	 * quote-framed heap string with one trailing carriage return stripped (CRLF parity
	 * with the file path), advances the cursor, and returns nil once the range is
	 * exhausted. Locals: 0=fd, 1=heap_ptr, 2=scan pos (reused), 3=copy index (reused),
	 * 5=rec, 6=cursor, 7=endp, 8=llen.
	 */
	private static void emitReadLineFromStringStream(WasmWriter w) {
		final int FD = 0, HEAP = 1, POS = 2, I = 3, REC = 5, CURSOR = 6, ENDP = 7, LLEN = 8;
		// if (fd < 0) { ... }
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(FD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		// rec = -fd ; cursor = rec.cursor ; endp = rec.end
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(FD);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(REC);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(REC);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(CURSOR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(REC);
		w.write(Instruction.I32_LOAD, 0x02, 0x08);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(ENDP);
		// if (cursor >= endp) return nil
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(CURSOR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(ENDP);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// pos = cursor ; while (pos < endp && memory[pos] != '\n') pos++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(CURSOR);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(POS);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(POS);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(ENDP);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(POS);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x0A);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(POS);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(POS);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// llen = pos - cursor ; strip one trailing '\r'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(POS);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(CURSOR);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(LLEN);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(LLEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(CURSOR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(LLEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x0D);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(LLEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(LLEN);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// heap = memory[HEAP_PTR_ADDR] ; grow(heap + llen + 2) ; bump the pointer
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(HEAP);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(HEAP);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(LLEN);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_ADD);
		});
		// HEAP_PTR is NOT advanced (a stack pop): _str_fresh copies the assembled line
		// into
		// a fresh GC array, so the scratch at `heap` is reused for the next read.
		// memory[heap] = '"' ; copy the line bytes ; memory[heap + 1 + llen] = '"'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(HEAP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(I);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(I);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(LLEN);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(HEAP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(CURSOR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(I);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(I);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(HEAP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(LLEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// rec.cursor = pos < endp ? pos + 1 (skip the newline) : endp
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(REC);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(POS);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(ENDP);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(POS);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(ENDP);
		w.write(Instruction.END);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
		// return _str_fresh(heap, llen + 2) -- the line is a runtime string
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(HEAP);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(LLEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		WasmEmitHelper.emitStrFreshCall(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	/**
	 * Builds the print_i32 helper function body.
	 */
	static byte[] buildPrintI32Core(boolean appendNewline) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(3);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.ELSE);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(45);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(10);
			w.write(Instruction.I32_STORE8, 0x00, 0x00);
		}

		// Emit the rendered digits through _write_str so the capture mode of the
		// string runtime also sees integer output.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		}
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	static byte[] buildWriteStrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 2=capture cursor (i32), 3=copy index (i32); params: 0=ptr, 1=len
		w.write(1);
		w.write(2);
		w.write(Type.I32);

		// Capture mode (string runtime): append the bytes at the capture cursor
		// instead of writing to stdout.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.IF, 0x40);

		// cur = memory[CAPTURE_CUR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		// Ensure [cur, cur+len) is within linear memory before the copy loop.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(2);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		});

		// i = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// while (i < len) { memory[cur + i] = memory[ptr + i]; i++; }
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// memory[CAPTURE_CUR_ADDR] = cur + len
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		// Track whether stdout ended at a line start: LINE_START = (last byte != '\n').
		// Only stdout writes (this non-capture path) update the flag; capture mode
		// (string
		// building for format nil) returns earlier and leaves it untouched.
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.LINE_START_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the body of a string-runtime function (_princ_to_str, _prin1_to_str or
	 * _string_concat). It renders the argument value(s) between two quote bytes into the
	 * heap by turning on the capture mode of {@code _write_str}, bumps the heap pointer
	 * and returns a new string struct over the captured bytes.
	 * @param renderFunc the rendering function ({@code FUNC_PRINC_VAL} for display text,
	 * {@code FUNC_PRINT_VAL} for the readable form)
	 * @param argCount 1 (to-string) or 2 (concatenation: both rendered back to back)
	 * @return the function body
	 */
	static byte[] buildToStringBody(int renderFunc, int argCount) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: argCount=start (i32), argCount+1=cur (i32)
		int start = argCount;
		int cur = argCount + 1;
		w.write(1);
		w.write(2);
		w.write(Type.I32);

		// start = memory[HEAP_PTR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(start);

		// Ensure the opening-quote byte at `start` is within linear memory.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(start);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		});

		// memory[start] = 0x22 ('"' prefix)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(start);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// memory[CAPTURE_CUR_ADDR] = start + 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(start);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// memory[CAPTURE_FLAG_ADDR] = 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// Render each argument; _write_str appends the bytes at the capture cursor.
		for (int i = 0; i < argCount; i++) {
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(i);
			w.write(Instruction.CALL);
			w.writeSignedLeb128(renderFunc);
		}

		// memory[CAPTURE_FLAG_ADDR] = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// cur = memory[CAPTURE_CUR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(cur);

		// Ensure the closing-quote byte at `cur` is within linear memory.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(cur);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		});

		// memory[cur] = 0x22 ('"' suffix)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(cur);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// HEAP_PTR is NOT advanced past the capture: _str_fresh copies the captured bytes
		// into a fresh GC array, so leaving HEAP_PTR at `start` (a stack pop) reuses the
		// scratch. Nothing sub-allocates at HEAP_PTR during the capture (numbers render
		// through OUT_BUF, strings through _write_str_gc which appends to CAPTURE_CUR),
		// so
		// start stays the scratch base throughout.
		// return _str_fresh(start, cur + 1 - start) -- a runtime string, fresh counter id
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(start);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(cur);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(start);
		w.write(Instruction.I32_SUB);
		WasmEmitHelper.emitStrFreshCall(w);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _print_val helper function that prints any Lisp value without a trailing
	 * newline. Handles null (nil), i31ref (integer), string struct, closure struct, and
	 * cons struct (list).
	 */
	static byte[] buildPrintValBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: slot 1 = (ref null eq) cons cursor, slot 2 = i32 cons first-flag (both
		// used by the list printer); slots 3-4 = (ref null eq) array dims/data, slots
		// 5-10 = i32 array index/length/rank/dimension/stride/scratch (used by the array
		// printer).
		w.write(4);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(1);
		w.write(Type.I32);
		w.write(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(7);
		w.write(Type.I32);

		// Check null (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check i31ref (integer)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check ratio struct -> "numerator/denominator"
		emitPrintRatio(w, st);

		// Check float struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check character struct -> #\name
		emitPrintChar(w, st, true);

		// Check string struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		// _write_str_gc(str, 0, len): the readable form keeps the surrounding quotes, so
		// the whole array [0, len) is printed straight from the GC heap.
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
		WasmEmitHelper.emitWriteStrGcCall(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print "#<function>"
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check promise struct -> print "#<PROMISE>"
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_PROMISE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.promiseStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.promiseStr.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check array (TYPE_CELL box with a TYPE_HASH_BUCKETS dims array as header car).
		emitPrintArray(w, st, WasmLispCompiler.FUNC_PRINT_VAL, 3, 4, 5, 6, 7, 8, 9, 10, 11);

		// Must be cons struct - print as list
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the princ_val helper function body. Same as print_val but strips quotes from
	 * strings and uses FUNC_PRINC_VAL for recursive cons printing.
	 */
	static byte[] buildPrincValBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Local declarations: slot 1 = ref null eq, slot 2 = i32 (offset), slot 3 = i32
		// (length); slots 4-5 = ref null eq (array dims/data), slots 6-12 = i32 (array
		// index/length/rank/dimension/stride/scratch/displacement-base).
		w.write(5);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);
		w.write(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(7);
		w.write(Type.I32);

		// Check null (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check i31ref (integer)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check ratio struct -> "numerator/denominator"
		emitPrintRatio(w, st);

		// Check float struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check character struct -> bare glyph
		emitPrintChar(w, st, false);

		// Check string struct - strip quotes if leading '"'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		// Get length -> local 3 (the len field; still stored)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);
		// Check if the first byte array[0] is '"' (0x22): a real string (strip the
		// surrounding quotes) vs a bare symbol name (print as-is). The bytes live on the
		// GC heap now, so the string prints straight from its $str_bytes array via
		// _write_str_gc -- with no linear pointer it can never alias the capture buffer
		// when printing inside a with-output-to-string / *-to-string capture.
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		// Strip quotes: _write_str_gc(str, 1, len - 1)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		WasmEmitHelper.emitWriteStrGcCall(w);
		w.write(Instruction.ELSE);
		// Bare symbol name: _write_str_gc(str, 0, len)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		WasmEmitHelper.emitWriteStrGcCall(w);
		w.write(Instruction.END);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print "#<function>"
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check promise struct -> print "#<PROMISE>"
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_PROMISE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.promiseStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.promiseStr.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check array (TYPE_CELL box with a TYPE_HASH_BUCKETS dims array as header car).
		emitPrintArray(w, st, WasmLispCompiler.FUNC_PRINC_VAL, 4, 5, 6, 7, 8, 9, 10, 11, 12);

		// Must be cons struct - print as list
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINC_VAL);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINC_VAL);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Emits the character branch shared by _print_val (readable = true, prints the
	// #\name form) and _princ_val (readable = false, prints the bare glyph). The value is
	// in param 0; i32 local slot 2 (declared in both bodies) is reused for the code
	// point.
	private static void emitPrintChar(WasmWriter w, WasmLispCompiler.StringTable st, boolean readable) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.IF, 0x40);
		// code = char.code -> slot 2
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);
		if (readable) {
			// "#\" prefix, then the standard name (for the non-graphic characters) or the
			// bare glyph.
			writeStr(w, st.charPrefix);
			int[][] names = { { ' ', 0 }, { '\n', 1 }, { '\t', 2 }, { '\r', 3 }, { '\f', 4 }, { '\b', 5 }, { 0, 6 },
					{ 127, 7 } };
			WasmLispCompiler.StringTable.StringEntry[] entries = { st.charSpace, st.charNewline, st.charTab,
					st.charReturn, st.charPage, st.charBackspace, st.charNul, st.charRubout };
			for (int[] n : names) {
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(2);
				w.write(Instruction.I32_CONST);
				w.writeSignedLeb128(n[0]);
				w.write(Instruction.I32_EQ);
				w.write(Instruction.IF, 0x40);
				writeStr(w, entries[n[1]]);
				w.write(Instruction.ELSE);
			}
			emitGlyph(w);
			for (int i = 0; i < names.length; i++) {
				w.write(Instruction.END);
			}
		}
		else {
			emitGlyph(w);
		}
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// Writes the single byte in i32 local slot 2 to stdout (via the print scratch
	// buffer).
	private static void emitGlyph(WasmWriter w) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
	}

	// Writes the string-table entry (offset/length) to stdout via _write_str.
	private static void writeStr(WasmWriter w, WasmLispCompiler.StringTable.StringEntry entry) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
	}

	private static void getBucketsLocal(WasmWriter w, int slot) {
		getLocal(w, slot);
		castBuckets(w);
	}

	// Pushes the f64 data array (field 1) of the TYPE_FARRAY held in slot, cast to
	// TYPE_F64ARR.
	private static void farrayData(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_F64ARR);
	}

	// Pushes the dims buckets (field 0, a TYPE_HASH_BUCKETS held as eq) of the
	// TYPE_FARRAY
	// in slot.
	private static void farrayDims(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeSignedLeb128(0);
	}

	// Pushes the header cons (cell.field0) of the TYPE_CELL value in param 0.
	private static void cellHeader(WasmWriter w) {
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeSignedLeb128(0);
	}

	// Pushes the given field of the cons held in slot (cast to TYPE_CONS).
	private static void innerConsGet(WasmWriter w, int slot, int field) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(field);
	}

	// Emits the array branch shared by _print_val and _princ_val: if param 0 is a
	// TYPE_CELL box whose header car is a TYPE_HASH_BUCKETS array (i.e. an array, not a
	// hash table), prints it as #(...) (rank 1) or #nA((...) ...) (rank n) and returns.
	// elementFunc is the per-element printer (FUNC_PRINT_VAL for prin1, FUNC_PRINC_VAL
	// for princ). A nested group paren opens where the flat index is a multiple of that
	// dimension's stride (the product of the trailing dimension sizes) and closes where
	// the next index is. Slots: dims/data = (ref null eq) locals; idx/len/rank/j/stride/m
	// = i32 locals.
	private static void emitPrintArray(WasmWriter w, WasmLispCompiler.StringTable st, int elementFunc, int dimsSlot,
			int dataSlot, int idxSlot, int lenSlot, int rankSlot, int jSlot, int strideSlot, int mSlot, int baseSlot) {
		// A packed float array (TYPE_FARRAY) is rendered by converting it in place to an
		// equivalent general array (a TYPE_CELL with boxed TYPE_FLOAT elements) stored
		// back
		// into param 0, then reusing the general-array printer below. The farray's dims
		// buckets are reused directly; only its unboxed f64 data is boxed per element.
		// This
		// reuses the whole renderer with no dedicated packed print path and no new
		// function
		// index (the fixed FUNC_* indices the component blobs depend on stay put). The
		// dims/data/idx/len slots used here are all re-set by the general logic
		// afterwards.
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.IF, 0x40);
		// dataSlot = the farray; lenSlot = array.len(farray.data)
		getLocal(w, 0);
		setLocal(w, dataSlot);
		farrayData(w, dataSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		setLocal(w, lenSlot);
		// dimsSlot = newBuckets = array.new $hash_buckets (null, len)
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		getLocal(w, lenSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, dimsSlot);
		// for (idx = 0; idx < len; idx++) newBuckets[idx] = float(farray.data[idx])
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, idxSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, idxSlot);
		getLocal(w, lenSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		getBucketsLocal(w, dimsSlot);
		getLocal(w, idxSlot);
		farrayData(w, dataSlot);
		getLocal(w, idxSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_F64ARR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, idxSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// param0 = cell(cons(dims, cons(cons(null, cons(null, i31 0)), newBuckets)))
		farrayDims(w, dataSlot);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		getBucketsLocal(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		setLocal(w, 0);
		w.write(Instruction.END); // if (farray)

		// if (param0 is TYPE_CELL)
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.IF, 0x40);

		// header car as the array-vs-hash-table discriminator
		cellHeader(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.IF, 0x40);

		// dims = header.car; dataSlot temporarily holds the (meta . data) inner cons
		cellHeader(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		setLocal(w, dimsSlot);
		cellHeader(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		setLocal(w, dataSlot);

		// rank = len(dims)
		getBucketsLocal(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		setLocal(w, rankSlot);

		// len = the fill pointer (meta.car) when present -- a fill-pointer vector
		// prints only up to it -- else len(data)
		innerConsGet(w, dataSlot, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x7F); // (result i32)
		innerConsGet(w, dataSlot, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);
		// no fill pointer: the total element count is the product of the dims (a
		// displaced array's data slot is the target CELL, so the buckets length is
		// not available here; for an ordinary array the product is the same value)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		setLocal(w, strideSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, mSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, mSlot);
		getBucketsLocal(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, strideSlot);
		getBucketsLocal(w, dimsSlot);
		getLocal(w, mSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_MUL);
		setLocal(w, strideSlot);
		getLocal(w, mSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, mSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		getLocal(w, strideSlot);
		w.write(Instruction.END);
		setLocal(w, lenSlot);

		// resolve the displacement chain: base accumulates each hop's meta offset;
		// dataSlot walks from this array's inner (meta . data) cons to the base
		// array's, whose data slot holds the actual buckets
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, baseSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		innerConsGet(w, dataSlot, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// base += meta.cdr.cdr (the offset i31)
		getLocal(w, baseSlot);
		innerConsGet(w, dataSlot, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_ADD);
		setLocal(w, baseSlot);
		// hop to the target cell's inner (meta . data) cons
		innerConsGet(w, dataSlot, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		setLocal(w, dataSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block

		// data = inner.cdr
		innerConsGet(w, dataSlot, 1);
		setLocal(w, dataSlot);

		// prefix: "#(" for rank 1, "#" + rank + "A(" for rank n
		getLocal(w, rankSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.vecPrefix);
		w.write(Instruction.ELSE);
		writeStr(w, st.hashPrefix);
		getLocal(w, rankSlot);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		writeStr(w, st.rankAOpen);
		w.write(Instruction.END);

		// idx = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, idxSlot);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// if idx >= len break
		getLocal(w, idxSlot);
		getLocal(w, lenSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);

		// a space before every element except the first (group closes/opens sit
		// around it)
		getLocal(w, idxSlot);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.space);
		w.write(Instruction.END);

		// opens (outermost first): for j in 1..rank-1: if (idx % stride(j) == 0) "("
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		setLocal(w, jSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, jSlot);
		getLocal(w, rankSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		emitPrintArrayStride(w, dimsSlot, jSlot, strideSlot, mSlot, rankSlot);
		getLocal(w, idxSlot);
		getLocal(w, strideSlot);
		w.write(Instruction.I32_REM_S);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.lparen);
		w.write(Instruction.END);
		getLocal(w, jSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, jSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block

		// element: elementFunc(data[base + idx])
		getBucketsLocal(w, dataSlot);
		getLocal(w, baseSlot);
		getLocal(w, idxSlot);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(elementFunc);

		// closes (innermost first): for j in rank-1..1: if ((idx+1) % stride(j) == 0)
		// ")"
		getLocal(w, rankSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		setLocal(w, jSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, jSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 1);
		emitPrintArrayStride(w, dimsSlot, jSlot, strideSlot, mSlot, rankSlot);
		getLocal(w, idxSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		getLocal(w, strideSlot);
		w.write(Instruction.I32_REM_S);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.rparen);
		w.write(Instruction.END);
		getLocal(w, jSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		setLocal(w, jSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block

		// idx++
		getLocal(w, idxSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block

		writeStr(w, st.rparen);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // is-array if
		w.write(Instruction.END); // is-cell if
	}

	// stride = the product of the i31 dimension sizes dims[j..rank-1] (the flat-index
	// span of one step of dimension j-1), computed into strideSlot with mSlot as the
	// scratch index.
	private static void emitPrintArrayStride(WasmWriter w, int dimsSlot, int jSlot, int strideSlot, int mSlot,
			int rankSlot) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		setLocal(w, strideSlot);
		getLocal(w, jSlot);
		setLocal(w, mSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, mSlot);
		getLocal(w, rankSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, strideSlot);
		getBucketsLocal(w, dimsSlot);
		getLocal(w, mSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_MUL);
		setLocal(w, strideSlot);
		getLocal(w, mSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, mSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	// Emits the ratio branch shared by _print_val and _princ_val: if the value in
	// param 0 is a ratio struct, prints "numerator/denominator" and returns.
	private static void emitPrintRatio(WasmWriter w, WasmLispCompiler.StringTable st) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.slash.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.slash.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	/**
	 * Builds the print_f64 helper function body. Prints integer part via print_i32_no_nl,
	 * then '.' and fractional digits.
	 */
	static byte[] buildPrintF64Core(boolean appendNewline, WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=f64 value (param), 1=i32 is_neg, 2=i32 int_part, 3=f64 frac,
		// 4=i32 digit, 5=i32 digit_count
		w.write(5);
		w.write(1);
		w.write(Type.I32); // local 1: is_neg
		w.write(1);
		w.write(Type.I32); // local 2: int_part
		w.write(1);
		w.write(Type.F64); // local 3: frac
		w.write(1);
		w.write(Type.I32); // local 4: digit
		w.write(1);
		w.write(Type.I32); // local 5: digit_count

		// Check if negative
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1); // is_neg

		// If negative, negate
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_NEG);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);

		// Get integer part: int_part = i32(floor(value))
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_FLOOR);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2); // int_part

		// If negative, write '-'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.minus.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.minus.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		// Print integer part
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);

		// Print '.'
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.period.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.period.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		// Compute fractional part: frac = value - f64(int_part)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // frac

		// digit_count = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(5);

		// Loop to extract fractional digits
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		// frac = frac * 10.0
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.F64_CONST);
		w.writeF64(10.0);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// digit = i32(trunc(frac))
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(4);

		// Store digit char at OUT_BUF_OFFSET
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48); // '0'
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// Write the single digit
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		// frac = frac - f64(digit)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// digit_count++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(5);

		// Continue if: digit_count < 1 OR (frac > epsilon AND digit_count < 6)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_LT_S);
		// OR
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0000001);
		w.write(Instruction.F64_GT);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(6);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);

		w.write(Instruction.BR_IF, 0); // loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		// Append newline if needed
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.offset());
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.length());
			w.write(Instruction.CALL);
			w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		}

		w.write(Instruction.END);
		return body.toByteArray();
	}

}
