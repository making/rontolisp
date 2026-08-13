package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

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
		w.writeUnsignedLeb128(0); // a
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1); // b
		w.write(Instruction.ELSE);

		// a.car
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0); // field 0: car

		// _append(a.cdr, b)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1); // field 1: cdr
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1); // b
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_APPEND);

		// struct.new cons(car, result)
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);

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
	 *
	 * <p>
	 * With an instance type present, two instances are equal when they carry the SAME
	 * layout record and every slot is recursively {@code _equal} -- structural, matching
	 * the interpreter's {@code LispInstance.equals} and the JVM arm, so
	 * {@code (equal p1 p2)} answers alike on all four backends. Nothing is emitted for it
	 * when the program cannot build an instance.
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1
	 * @return the function body
	 */
	static byte[] buildEqualBody(int instanceTypeIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		if (instanceTypeIndex < 0) {
			w.write(0); // 0 extra locals; params (local 0 = a, local 1 = b) suffice
		}
		else {
			// The slot walk needs a cursor, the slot count and the running answer.
			w.write(1);
			w.write(3);
			w.write(Type.I32);
		}

		// Normalize mutable character vectors into strings up front (before the ref.eq
		// fast path, so one code path serves all four combinations): two character
		// vectors with equal content compare true, and a character vector compares true
		// to a string with the same content.
		getLocal(w, 0);
		WasmEmitHelper.emitCharvecToStrCall(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		getLocal(w, 1);
		WasmEmitHelper.emitCharvecToStrCall(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);

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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		consField(w, 0, 1); // a.cdr
		consField(w, 1, 1); // b.cdr
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // end car-equal if
		w.write(Instruction.ELSE);

		emitInstanceEqual(w, instanceTypeIndex);

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

		// both boxed integers -> i64 fields equal (an in-range integer is always an
		// i31 by the _int_new normalization, so mixed i31/bignum pairs are never
		// numerically equal)
		refTest(w, 0, WasmLispCompiler.TYPE_BIGNUM);
		refTest(w, 1, WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		bignumField(w, 0);
		bignumField(w, 1);
		w.write(Instruction.I64_EQ);
		w.write(Instruction.ELSE);

		// both limb integers -> _big_eq value equality (canonical limbs)
		refTest(w, 0, WasmLispCompiler.TYPE_BIGINT);
		refTest(w, 1, WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, 0);
		getLocal(w, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_EQ);
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
		w.write(Instruction.END); // end limb-integer if
		w.write(Instruction.END); // end bignum if
		w.write(Instruction.END); // end char if
		if (instanceTypeIndex >= 0) {
			w.write(Instruction.END); // end both-instance if
		}
		w.write(Instruction.END); // end both-cons if
		w.write(Instruction.END); // end ref.eq if

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Opens the both-instances arm of {@code _equal} (nothing when there is no instance
	 * type): same layout record and every slot recursively equal. The caller closes the
	 * {@code if} after the remaining eql arms, so this leaves the ELSE open.
	 */
	private static void emitInstanceEqual(WasmWriter w, int instanceTypeIndex) {
		if (instanceTypeIndex < 0) {
			return;
		}
		refTest(w, 0, instanceTypeIndex);
		refTest(w, 1, instanceTypeIndex);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// Same layout record? One record is interned per tag, so address equality IS
		// type equality -- and it settles the slot count too.
		instanceField(w, 0, instanceTypeIndex, 0);
		instanceField(w, 1, instanceTypeIndex, 0);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2); // i = 0
		instanceSlots(w, 0, instanceTypeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3); // n = slot count
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4); // answer = 1 until a slot differs
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, 2);
		getLocal(w, 3);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		instanceSlot(w, 0, instanceTypeIndex, 2);
		instanceSlot(w, 1, instanceTypeIndex, 2);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		getLocal(w, 2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block
		getLocal(w, 4);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // end same-layout if
		w.write(Instruction.ELSE);
	}

	/** Pushes field {@code field} of the instance in {@code local}. */
	private static void instanceField(WasmWriter w, int local, int instanceTypeIndex, int field) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(instanceTypeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(instanceTypeIndex);
		w.writeUnsignedLeb128(field);
	}

	/** Pushes the slot array of the instance in {@code local}, cast to $buckets. */
	private static void instanceSlots(WasmWriter w, int local, int instanceTypeIndex) {
		instanceField(w, local, instanceTypeIndex, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	/** Pushes slot {@code local(indexLocal)} of the instance in {@code local}. */
	private static void instanceSlot(WasmWriter w, int local, int instanceTypeIndex, int indexLocal) {
		instanceSlots(w, local, instanceTypeIndex);
		getLocal(w, indexLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	/**
	 * Builds the _hash helper (structural hash). Takes one (ref null eq) arg (local 0),
	 * returns an i32 hash that agrees with {@link #buildEqualBody _equal}: equal values
	 * hash equal. It walks conses recursively and folds i31 integers, character codes,
	 * string/symbol content bytes, float bit patterns and ratio components into the
	 * result. Value types not recognised by {@code _equal}'s eql base case (e.g.
	 * closures, which {@code equal} compares by identity) hash to a constant 0, which is
	 * correct (they simply collide into one bucket). An instance folds its layout address
	 * and its slot hashes, so it agrees with {@code _equal}'s structural instance arm.
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1
	 * @return the function body
	 */
	static byte[] buildHashBody(int instanceTypeIndex) {
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
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);

		// Normalize a mutable character vector into a string up front, so a character
		// vector key hashes exactly like the string with the same content (agreeing
		// with _equal's entry normalization -- equal-table gethash/sethash with mixed
		// string/character-vector keys interoperate).
		getLocal(w, 0);
		WasmEmitHelper.emitCharvecToStrCall(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);

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

		// boxed integer -> fold the i64 halves (mirrors the float branch; equal
		// bignums hash equal, and an i31 never equals a bignum by normalization)
		refTest(w, 0, WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.IF);
		w.write(Type.I32);
		bignumField(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		getLocal(w, 1);
		w.write(Instruction.I32_WRAP_I64);
		getLocal(w, 1);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(32);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_XOR);
		w.write(Instruction.ELSE);

		// limb integer -> fold the limbs (consistent with _big_eq)
		refTest(w, 0, WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, 0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_HASH);
		w.write(Instruction.ELSE);

		// cons -> hash(car) * 31 + hash(cdr) + 1
		refTest(w, 0, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.IF);
		w.write(Type.I32);
		consField(w, 0, 0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(31);
		w.write(Instruction.I32_MUL);
		consField(w, 0, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH);
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
		w.writeUnsignedLeb128(2); // h = 0
		getLocal(w, 0);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(5); // arr = string.data
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3); // idx = 0 (array index)
		stringLength(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4); // end = length
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2); // h = h * 31 + arr[idx]
		getLocal(w, 3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3); // idx = idx + 1
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
		w.writeUnsignedLeb128(1);
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

		// instance -> fold the layout address, then every slot hash (h = h * 31 + hash)
		if (instanceTypeIndex >= 0) {
			refTest(w, 0, instanceTypeIndex);
			w.write(Instruction.IF);
			w.write(Type.I32);
			instanceField(w, 0, instanceTypeIndex, 0);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(2); // h = layout address
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(3); // idx = 0
			instanceSlots(w, 0, instanceTypeIndex);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(4); // end = slot count
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
			instanceSlot(w, 0, instanceTypeIndex, 3);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(2);
			getLocal(w, 3);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(3);
			w.write(Instruction.BR, 0);
			w.write(Instruction.END); // end loop
			w.write(Instruction.END); // end block
			getLocal(w, 2);
			w.write(Instruction.ELSE);
		}

		// anything else (e.g. a closure) -> 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);

		if (instanceTypeIndex >= 0) {
			w.write(Instruction.END); // end instance if
		}
		w.write(Instruction.END); // end ratio if
		w.write(Instruction.END); // end float if
		w.write(Instruction.END); // end string if
		w.write(Instruction.END); // end char if
		w.write(Instruction.END); // end cons if
		w.write(Instruction.END); // end limb-integer if
		w.write(Instruction.END); // end bignum if
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
		w.writeRefType(true, Type.EQ.code());
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);

		int oldArr = 1, newArr = 2, cur = 3, entry = 4, i = 5, newCap = 6, j = 7;

		// oldArr = header.cdr
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		setLocal(w, entry);
		// j = (hash(car(entry)) & 0x7fffffff) % newCap
		getLocal(w, entry);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		// cur = cdr(cur)
		getLocal(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	private static void setLocal(WasmWriter w, int idx) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.writeUnsignedLeb128(0);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STRING_EQ);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // end both-strings if
	}

	private static void getLocal(WasmWriter w, int idx) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
	}

	private static void floatField(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
	}

	private static void bignumField(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		w.writeUnsignedLeb128(0);
	}

	private static void stringOffset(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(0);
	}

	private static void stringLength(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(1);
	}

	private static void ratioComponent(WasmWriter w, int local, int func) {
		getLocal(w, local);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(func);
	}

	/**
	 * Emits the {@code TYPE_FUTURE} print branch ("#&lt;FUTURE&gt;", the tag settled and
	 * pending futures share with the degenerate P1 future) and the
	 * {@code TYPE_WASI_STREAM} one ("#&lt;STREAM&gt;", matching the interpreter/JVM
	 * opaque tag; the string is added to the table lazily, so it exists only in async
	 * modules). A no-op when the module has no async block ({@code futureTypeIndex < 0}),
	 * keeping every non-async module byte-identical.
	 */
	private static void emitPrintFuture(WasmWriter w, WasmLispCompiler.StringTable st, int futureTypeIndex) {
		if (futureTypeIndex < 0) {
			return;
		}
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(futureTypeIndex);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.futureStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.futureStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// TYPE_WASI_STREAM sits two entries after TYPE_FUTURE in the async rec group.
		WasmLispCompiler.StringTable.StringEntry streamStr = st.addString("#<STREAM>");
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(futureTypeIndex + 2);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(streamStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(streamStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	/**
	 * Emits the {@code TYPE_P1_STREAM} print branch ("#&lt;STREAM&gt;", the tag every
	 * backend's opaque stream value shares; the string is added to the table lazily, so
	 * it exists only in modules that can hold a stream). A no-op when no stream value can
	 * exist ({@code p1StreamTypeIndex < 0}), keeping every other module byte-identical.
	 *
	 * <p>
	 * Mandatory rather than cosmetic where the type DOES exist: the printer's tail
	 * assumes a cons and would trap on {@code ref.cast $cons}.
	 */
	private static void emitPrintStream(WasmWriter w, WasmLispCompiler.StringTable st, int p1StreamTypeIndex) {
		if (p1StreamTypeIndex < 0) {
			return;
		}
		WasmLispCompiler.StringTable.StringEntry streamStr = st.addString("#<STREAM>");
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(p1StreamTypeIndex);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(streamStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(streamStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	/**
	 * Emits the {@code TYPE_INSTANCE} print branch: {@code #S(NAME :SLOT value ...)} for
	 * a struct layout, {@code #<NAME :SLOT value ...>} for a class one, and for the
	 * PATHNAME layout {@code #P"namestring"} under {@code prin1} / the bare namestring
	 * under {@code princ} (CLHS 22.1.3.11, no slot syntax). The {@code #S}/{@code #<}
	 * frame and the colon on each slot key are literal syntax and so are written in BOTH
	 * escape modes (CLHS 22.1.3.12); only the slot VALUES go through {@code elementFunc},
	 * which is {@code FUNC_PRINT_VAL} for {@code prin1} and {@code FUNC_PRINC_VAL} for
	 * {@code princ} (also how this branch tells the two escape modes apart).
	 *
	 * <p>
	 * The branch is a FIXED-SIZE loop driven by the layout record in linear memory, not a
	 * per-type if-chain, so the body does not grow with the number of struct or class
	 * types the program defines.
	 *
	 * <p>
	 * It is mandatory rather than cosmetic: the printer's tail assumes a cons and would
	 * trap on {@code ref.cast $cons}. A no-op when the module has no instance type
	 * ({@code instanceTypeIndex < 0}), keeping every instance-free module byte-identical
	 * -- which is also why the four delimiter strings are interned HERE and not as
	 * StringTable constructor fields, where they would move every existing offset.
	 * @param w the writer for the printer body
	 * @param st the module string table, still open for appends
	 * @param elementFunc the per-slot-value renderer
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1
	 * @param addrSlot an i32 local holding the layout record address
	 * @param idxSlot an i32 local holding the slot loop counter
	 * @param cntSlot an i32 local holding the slot count
	 */
	private static void emitPrintInstance(WasmWriter w, WasmLispCompiler.StringTable st, int elementFunc,
			int instanceTypeIndex, int addrSlot, int idxSlot, int cntSlot) {
		if (instanceTypeIndex < 0) {
			return;
		}
		WasmLispCompiler.StringTable.StringEntry openStruct = st.addString("#S(");
		WasmLispCompiler.StringTable.StringEntry openClass = st.addString("#<");
		WasmLispCompiler.StringTable.StringEntry closeClass = st.addString(">");
		WasmLispCompiler.StringTable.StringEntry keySep = st.addString(" :");
		boolean escape = elementFunc == WasmLispCompiler.FUNC_PRINT_VAL;
		WasmLispCompiler.StringTable.StringEntry pathnamePrefix = escape ? st.addString("#P") : null;
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(instanceTypeIndex);
		w.write(Instruction.IF, 0x40);
		// addr = the layout record's linear address
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(instanceTypeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(instanceTypeIndex);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(addrSlot);
		// kind == PATHNAME: #P + the escaped namestring under prin1, the bare
		// namestring under princ (CLHS 22.1.3.11) -- slot 0 through the element
		// renderer, no slot-name loop.
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_KIND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmInstanceLayouts.KIND_PATHNAME);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		if (pathnamePrefix != null) {
			emitWriteString(w, pathnamePrefix);
		}
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(instanceTypeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(instanceTypeIndex);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// kind == CLASS ? "#<" : "#S("
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_KIND);
		w.write(Instruction.IF, 0x40);
		emitWriteString(w, openClass);
		w.write(Instruction.ELSE);
		emitWriteString(w, openStruct);
		w.write(Instruction.END);
		// the printed type name
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_NAME_OFF);
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_NAME_LEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_SLOT_COUNT);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(cntSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idxSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idxSlot);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(cntSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		emitWriteString(w, keySep);
		// the slot name: addr + OFF_SLOTS + idx * SLOT_ENTRY_BYTES
		emitSlotEntryAddress(w, addrSlot, idxSlot);
		w.write(Instruction.I32_LOAD, 0x02, WasmInstanceLayouts.OFF_SLOTS);
		emitSlotEntryAddress(w, addrSlot, idxSlot);
		w.write(Instruction.I32_LOAD, 0x02, WasmInstanceLayouts.OFF_SLOTS + 4);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		emitWriteString(w, st.space);
		// the slot value, rendered in the ambient escape mode
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(instanceTypeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(instanceTypeIndex);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idxSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idxSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idxSlot);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_KIND);
		w.write(Instruction.IF, 0x40);
		emitWriteString(w, closeClass);
		w.write(Instruction.ELSE);
		emitWriteString(w, st.rparen);
		w.write(Instruction.END);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// Pushes the i32 word at addrSlot + offset out of a layout record.
	private static void emitLoadLayoutWord(WasmWriter w, int addrSlot, int offset) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(addrSlot);
		w.write(Instruction.I32_LOAD, 0x02, offset);
	}

	// Pushes addrSlot + idxSlot * SLOT_ENTRY_BYTES: the base of one slot-name entry.
	private static void emitSlotEntryAddress(WasmWriter w, int addrSlot, int idxSlot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(addrSlot);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idxSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmInstanceLayouts.SLOT_ENTRY_BYTES);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
	}

	// (offset, length) -> _write_str: writes an interned string to the current sink.
	private static void emitWriteString(WasmWriter w, WasmLispCompiler.StringTable.StringEntry entry) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
	}

	// Pushes car (head = true) or cdr (head = false) of the cons in `slot`, answering
	// null for null so a short argument list binds the missing parameters to nil instead
	// of trapping on the ref.cast.
	private static void emitNullSafeCell(WasmWriter w, int slot, boolean head) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(head ? 0 : 1);
		w.write(Instruction.END);
	}

	static byte[] buildDispatchBody(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, int numDefuns, WasmLispCompiler.StringTable st,
			boolean usesEval, int userFuncBase) {
		return buildDispatchBody(arity, defuns, lambdaDecls, numDefuns, st, usesEval, userFuncBase, false, null);
	}

	/**
	 * As above, with {@code spread} selecting the SPREAD dispatcher: one function over
	 * EVERY callable, taking the argument list as a single cons list (the arity-1
	 * signature) rather than one parameter per argument.
	 *
	 * <p>
	 * {@code _apply} calls it. The per-arity dispatchers cannot serve {@code apply}: they
	 * take one WASM parameter per Lisp argument, so they stop at
	 * {@link WasmLispCompiler#MAX_CALLABLE_ARITY}, and an {@code apply} whose designator
	 * is COMPUTED has to go through them -- quri's
	 * {@code (apply (scheme-constructor s) :scheme s ... )} passes fourteen arguments to
	 * a {@code &key} constructor and used to trap on the ladder's fall-through. Each
	 * spread case walks its target's required parameters out of the list and hands a
	 * variadic target the remaining TAIL, which is the callee's physical rest parameter.
	 * @param arity ignored when {@code spread} is true
	 * @param spread whether to build the spread dispatcher instead of an arity one
	 * @param dispatchable the funcIds this program can reach as a function VALUE, or
	 * {@code null} for "every one of them". A funcId outside the set is called only
	 * directly, so giving it a case would only pin it for {@code --optimize}
	 * ({@code WasmLispCompiler.dispatchableFuncIds}); its {@code br_table} slot points at
	 * the default arm, which is where an unresolvable designator already went.
	 * @return the encoded function body
	 */
	static byte[] buildDispatchBody(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, int numDefuns, WasmLispCompiler.StringTable st,
			boolean usesEval, int userFuncBase, boolean spread, @Nullable Set<Integer> dispatchable) {
		// Collect all functions with matching arity. A variadic function (physical
		// params = required + rest list) matches every dispatch arity >= required; its
		// case links the surplus args into a cons list before the call. The spread
		// dispatcher takes them all: its cases read the parameters out of the list.
		record Target(int funcId, int funcIndex, int required, boolean variadic) {
		}
		int dispatchArgs = spread ? 1 : arity;
		List<Target> targets = new ArrayList<>();
		for (int i = 0; i < defuns.size(); i++) {
			WasmLispCompiler.DefunDecl defun = defuns.get(i);
			int paramCount = defun.paramNames().size();
			if (dispatchable != null && !dispatchable.contains(i)) {
				continue;
			}
			if (spread || (defun.variadic() ? arity >= paramCount - 1 : paramCount == arity)) {
				targets.add(new Target(i, userFuncBase + i, defun.variadic() ? paramCount - 1 : paramCount,
						defun.variadic()));
			}
		}
		for (int i = 0; i < lambdaDecls.size(); i++) {
			WasmLispCompiler.LambdaInfo lambda = lambdaDecls.get(i);
			int paramCount = lambda.paramNames().size();
			if (dispatchable != null && !dispatchable.contains(lambda.funcId())) {
				continue;
			}
			if (spread || (lambda.variadic() ? arity >= paramCount - 1 : paramCount == arity)) {
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
		w.writeRefType(true, Type.EQ.code());

		int funcIdLocal = dispatchArgs + 1; // after params
		int argListLocal = dispatchArgs + 2;

		// A SYMBOL funcval (a TYPE_STRING) is a function designator resolved through
		// the eval registry's _lookup by its interned offset -- the interpreter's
		// late binding (cl-postgres passes 'list-row-reader through exec-query).
		// Without the eval runtime _lookup is the always--1 stub, so the miss arm
		// traps exactly where the closure cast used to.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0); // funcval
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(0); // field 0: interned offset
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE); // undefined function
		w.write(Instruction.END);
		// funcId = record.funcId (record: {nameOffset, funcId, arity})
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		// Every case body casts the funcval to the closure struct for its env (the
		// uniform calling convention), so replace the SYMBOL with a synthesized
		// {funcId, null-env} closure -- exactly the value #'name would have produced.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.ELSE);
		// Extract funcId from closure struct
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0); // funcval
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.writeUnsignedLeb128(0); // field 0: funcId
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.END);

		// Interpreted closure (funcId == -1, created by the eval runtime's lambda):
		// delegate to _apply with the arguments collected into a cons list
		if (usesEval && !spread) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(funcIdLocal);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(-1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(argListLocal);
			for (int a = arity; a >= 1; a--) {
				w.write(Instruction.GET_LOCAL);
				w.writeUnsignedLeb128(a);
				w.write(Instruction.GET_LOCAL);
				w.writeUnsignedLeb128(argListLocal);
				w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
				w.write(Instruction.SET_LOCAL);
				w.writeUnsignedLeb128(argListLocal);
			}
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(argListLocal);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_APPLY);
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
		w.writeRefType(true, Type.EQ.code());

		// Default block (void)
		w.write(Instruction.BLOCK, 0x40);

		// Case blocks (void) - outermost case first
		for (int i = 0; i < numCases; i++) {
			w.write(Instruction.BLOCK, 0x40);
		}

		// Load funcId and br_table
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);

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
			if (spread) {
				// cursor = argList; the required parameters come off the front and a
				// variadic target takes what is left.
				w.write(Instruction.GET_LOCAL);
				w.writeUnsignedLeb128(1);
				w.write(Instruction.SET_LOCAL);
				w.writeUnsignedLeb128(argListLocal);
			}
			else if (target.variadic) {
				// Link args required+1..arity into a cons list (right to left)
				w.write(Instruction.REF_NULL);
				w.writeHeapType(Type.EQ.code());
				w.write(Instruction.SET_LOCAL);
				w.writeUnsignedLeb128(argListLocal);
				for (int a = arity; a >= target.required + 1; a--) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(a);
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
					w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
					w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
					w.write(Instruction.SET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
				}
			}
			// Extract env from closure struct
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0); // funcval
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
			w.writeUnsignedLeb128(1); // field 1: env
			if (spread) {
				// Push car(cursor) per required parameter, stepping the cursor; a short
				// argument list yields nil rather than trapping, like car/cdr do.
				for (int a = 0; a < target.required; a++) {
					emitNullSafeCell(w, argListLocal, true);
					emitNullSafeCell(w, argListLocal, false);
					w.write(Instruction.SET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
				}
				if (target.variadic) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
				}
			}
			else {
				// Push args (for a variadic target, the required ones plus the rest list)
				for (int a = 1; a <= target.required; a++) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(a);
				}
				if (target.variadic) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
				}
				else {
					for (int a = target.required + 1; a <= arity; a++) {
						w.write(Instruction.GET_LOCAL);
						w.writeUnsignedLeb128(a);
					}
				}
			}
			// Call target function
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(target.funcIndex);
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
		w.write(1);
		w.write(8);
		w.write(Type.I32); // locals 1-8, one run: every one of them is an i32

		// A negative fd is a string input stream (see WasmStringStreamRuntimeBuilder):
		// return the next line of its [cursor, end) byte range as a fresh quote-framed
		// heap string, or nil at end of input.
		emitReadLineFromStringStream(w);

		// heap_ptr = memory[HEAP_PTR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);

		// memory[heap_ptr] = 0x22 ('"' prefix)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// pos = 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		// eof_flag = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);

		// Loop: read one byte at a time
		w.write(Instruction.BLOCK, 0x40); // block $break
		w.write(Instruction.LOOP, 0x40); // loop $continue

		// Set iov: ptr = heap_ptr + pos, len = 1
		// iov_buf_ptr at IOV_OFFSET
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1); // heap_ptr
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2); // pos
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
		w.writeUnsignedLeb128(0); // fd param
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // iovs_len = 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP); // drop errno

		// nread = memory[NWRITTEN_OFFSET]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);

		// if nread == 0: set eof_flag, break
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);
		w.write(Instruction.BR, 2); // break out of loop and block
		w.write(Instruction.END);

		// byte = memory[heap_ptr + pos]
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		// if byte == 0x0A (newline): break
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x0A);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF, 1); // break out of block

		// pos++
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		w.write(Instruction.BR, 0); // continue loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		// if pos > 1 && memory[heap_ptr + pos - 1] == 0x0D: pos-- -- strip one
		// trailing carriage return for CRLF parity with BufferedReader.readLine
		// (the interpreter and JVM backends strip it the same way; without this,
		// CRLF-terminated socket lines -- e.g. HTTP -- keep a trailing \r and a
		// blank CRLF line never compares string= to "").
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
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
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.END);

		// if pos == 1 && eof_flag: return ref.null eq (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(4);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.ELSE);

		// memory[heap_ptr + pos] = 0x22 ('"' suffix)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
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
		w.writeUnsignedLeb128(1); // offset
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2); // pos
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
		w.writeUnsignedLeb128(FD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		// rec = -fd ; cursor = rec.cursor ; endp = rec.end
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(FD);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(REC);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(REC);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(CURSOR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(REC);
		w.write(Instruction.I32_LOAD, 0x02, 0x08);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(ENDP);
		// if (cursor >= endp) return nil
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(CURSOR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ENDP);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// pos = cursor ; while (pos < endp && memory[pos] != '\n') pos++
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(CURSOR);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(POS);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(POS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ENDP);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(POS);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x0A);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(POS);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(POS);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// llen = pos - cursor ; strip one trailing '\r'
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(POS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(CURSOR);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(LLEN);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(LLEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(CURSOR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(LLEN);
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
		w.writeUnsignedLeb128(LLEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(LLEN);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// heap = memory[HEAP_PTR_ADDR] ; grow(heap + llen + 2) ; bump the pointer
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(HEAP);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(HEAP);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(LLEN);
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
		w.writeUnsignedLeb128(HEAP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(I);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(I);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(LLEN);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(HEAP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(CURSOR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(I);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(I);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(HEAP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(LLEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// rec.cursor = pos < endp ? pos + 1 (skip the newline) : endp
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(REC);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(POS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ENDP);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(POS);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ENDP);
		w.write(Instruction.END);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
		// return _str_fresh(heap, llen + 2) -- the line is a runtime string
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(HEAP);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(LLEN);
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

		// Locals 1-3: is_neg, digit count, reverse cursor -- one run, not three: adjacent
		// runs of the same type are one run in the shortest legal encoding.
		w.write(1);
		w.write(3);
		w.write(Type.I32);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
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
		w.writeUnsignedLeb128(2);
		w.write(Instruction.ELSE);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
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
		w.writeUnsignedLeb128(3);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_ADD);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(2);
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
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		}
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

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
		w.writeUnsignedLeb128(2);

		// Ensure [cur, cur+len) is within linear memory before the copy loop.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(2);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.I32_ADD);
		});

		// i = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);

		// while (i < len) { memory[cur + i] = memory[ptr + i]; i++; }
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// memory[CAPTURE_CUR_ADDR] = cur + len
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		// Track whether stdout ended at a line start: LINE_START = (last byte != '\n').
		// Only stdout writes (this non-capture path) update the flag; capture mode
		// (string
		// building for format nil) returns earlier and leaves it untouched.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.LINE_START_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
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
		w.writeUnsignedLeb128(start);

		// Ensure the opening-quote byte at `start` is within linear memory.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(start);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		});

		// memory[start] = 0x22 ('"' prefix)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(start);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// memory[CAPTURE_CUR_ADDR] = start + 1
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(start);
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
			w.writeUnsignedLeb128(i);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(renderFunc);
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
		w.writeUnsignedLeb128(cur);

		// Ensure the closing-quote byte at `cur` is within linear memory.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(cur);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		});

		// memory[cur] = 0x22 ('"' suffix)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(cur);
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
		w.writeUnsignedLeb128(start);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(cur);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(start);
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
	static byte[] buildPrintValBody(WasmLispCompiler.StringTable st, boolean simd, int futureTypeIndex,
			int p1StreamTypeIndex, int instanceTypeIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: slot 1 = (ref null eq) cons cursor, slot 2 = i32 cons first-flag (both
		// used by the list printer); slots 3-4 = (ref null eq) array dims/data, slots
		// 5-11 = i32 array index/length/rank/dimension/stride/scratch/displacement-base,
		// slot 12 = i32 packed-array flag (0 general / 1 double "#d(" / 2 single "#f("),
		// slot 13 = i32 single-float-width flag -- all used by the array printer.
		w.write(4);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		w.write(1);
		w.write(Type.I32);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(9);
		w.write(Type.I32);

		// Normalize a mutable character vector into a string up front: it then falls
		// into the string branch below (the readable form keeps the quotes), and the
		// recursive cons/array element prints route back through this entry.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		WasmEmitHelper.emitCharvecToStrCall(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);

		// Check null (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check i31ref (integer)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check boxed integer (TYPE_BIGNUM) -> i64 digits
		emitPrintBignum(w);

		// Check ratio struct -> "numerator/denominator"
		emitPrintRatio(w, st);

		// Check float struct
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check character struct -> #\name
		emitPrintChar(w, st, true);

		// Check string struct
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		// Get length -> local 5 (the len field; local 5 is the array printer's i32 index
		// scratch, which this branch always returns before reaching).
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(5);
		// A leading '"' (0x22) discriminates a real string from a bare symbol name (the
		// same test _princ_val makes). A STRING prints its readable form:
		// _write_str_gc(str, 1, len - 1, esc = 1) re-frames the CONTENT in quotes and
		// escapes every embedded " / \ on the way out, so the reader can read it back
		// (todo 216). A SYMBOL has no frame and no escaping: (0, len, esc = 0).
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		WasmEmitHelper.emitWriteStrGcCall(w);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		WasmEmitHelper.emitWriteStrGcCall(w);
		w.write(Instruction.END);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print "#<function>"
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check the degenerate-future struct -> print "#<FUTURE>"
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.futureStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.futureStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// asyncMode: a first-class TYPE_FUTURE prints the same "#<FUTURE>" tag.
		emitPrintFuture(w, st, futureTypeIndex);

		// The degenerate tier's TYPE_P1_STREAM prints the same "#<STREAM>" tag as the
		// async block's TYPE_WASI_STREAM (and as the interpreter/JVM opaque value).
		emitPrintStream(w, st, p1StreamTypeIndex);

		// Check array (TYPE_CELL box with a TYPE_HASH_BUCKETS dims array as header car).
		emitPrintInstance(w, st, WasmLispCompiler.FUNC_PRINT_VAL, instanceTypeIndex, 5, 6, 7);
		emitPrintArray(w, st, WasmLispCompiler.FUNC_PRINT_VAL, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, simd);

		// Must be cons struct - print as list
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the princ_val helper function body. Same as print_val but strips quotes from
	 * strings and uses FUNC_PRINC_VAL for recursive cons printing.
	 */
	static byte[] buildPrincValBody(WasmLispCompiler.StringTable st, boolean simd, int futureTypeIndex,
			int p1StreamTypeIndex, int instanceTypeIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Local declarations: slot 1 = ref null eq, slot 2 = i32 (offset), slot 3 = i32
		// (length); slots 4-5 = ref null eq (array dims/data), slots 6-12 = i32 (array
		// index/length/rank/dimension/stride/scratch/displacement-base), slot 13 = i32
		// packed-array flag (0 general / 1 double "#d(" / 2 single "#f("), slot 14 = i32
		// single-float-width flag.
		w.write(4);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(9);
		w.write(Type.I32);

		// Normalize a mutable character vector into a string up front: it then falls
		// into the string branch below (princ strips the quotes), and the recursive
		// cons/array element prints route back through this entry.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		WasmEmitHelper.emitCharvecToStrCall(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);

		// Check null (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check i31ref (integer)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check boxed integer (TYPE_BIGNUM) -> i64 digits
		emitPrintBignum(w);

		// Check ratio struct -> "numerator/denominator"
		emitPrintRatio(w, st);

		// Check float struct
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check character struct -> bare glyph
		emitPrintChar(w, st, false);

		// Check string struct - strip quotes if leading '"'
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		// Get length -> local 3 (the len field; still stored)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		// Check if the first byte array[0] is '"' (0x22): a real string (strip the
		// surrounding quotes) vs a bare symbol name (print as-is). The bytes live on the
		// GC heap now, so the string prints straight from its $str_bytes array via
		// _write_str_gc -- with no linear pointer it can never alias the capture buffer
		// when printing inside a with-output-to-string / *-to-string capture.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		// Strip quotes: _write_str_gc(str, 1, len - 1, esc = 0) -- princ is the
		// no-escape half by definition.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		WasmEmitHelper.emitWriteStrGcCall(w);
		w.write(Instruction.ELSE);
		// Bare symbol name: the display spelling is the symbol NAME alone -- no package
		// qualifier and no keyword/gensym marker (CLHS 22.1.3.3: with *print-escape*
		// false only the characters of the name are output). All three cases are
		// "everything after the LAST colon", so one backward-free byte scan covers
		// QURI:URI -> URI, :KW -> KW and #:G1 -> G1. ':' is 0x3A, which cannot occur as
		// a UTF-8 continuation byte, so scanning bytes is safe on a non-ASCII name.
		// _print_val keeps the spelling verbatim.
		// local 6 = scan index, local 7 = start (last colon + 1); both are otherwise
		// the array branch's scratch, which this branch never reaches.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(7);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(6);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(6);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(6);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(':');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(6);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(7);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(6);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(6);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// _write_str_gc(str, start, len, esc = 0) -- the third argument is the exclusive
		// end position, not a count.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(7);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		WasmEmitHelper.emitWriteStrGcCall(w);
		w.write(Instruction.END);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print "#<function>"
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check the degenerate-future struct -> print "#<FUTURE>"
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.futureStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.futureStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// asyncMode: a first-class TYPE_FUTURE prints the same "#<FUTURE>" tag.
		emitPrintFuture(w, st, futureTypeIndex);

		// The degenerate tier's TYPE_P1_STREAM prints the same "#<STREAM>" tag as the
		// async block's TYPE_WASI_STREAM (and as the interpreter/JVM opaque value).
		emitPrintStream(w, st, p1StreamTypeIndex);

		// Check array (TYPE_CELL box with a TYPE_HASH_BUCKETS dims array as header car).
		emitPrintInstance(w, st, WasmLispCompiler.FUNC_PRINC_VAL, instanceTypeIndex, 6, 7, 8);
		emitPrintArray(w, st, WasmLispCompiler.FUNC_PRINC_VAL, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, simd);

		// Must be cons struct - print as list
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINC_VAL);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINC_VAL);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Emits the character branch shared by _print_val (readable = true, prints the
	// #\name form) and _princ_val (readable = false, prints the bare glyph). The value is
	// in param 0; i32 local slot 2 (declared in both bodies) is reused for the code
	// point.
	private static void emitPrintChar(WasmWriter w, WasmLispCompiler.StringTable st, boolean readable) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.IF, 0x40);
		// code = char.code -> slot 2
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
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
				w.writeUnsignedLeb128(2);
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

	// Writes the character in i32 local slot 2 (its Unicode code point) to stdout as
	// its 1-4 byte UTF-8 encoding via the print scratch buffer. A code point >= 0x80
	// expands to multiple bytes so that non-ASCII glyphs print correctly on a UTF-8
	// stdout, matching the interpreter's println.
	private static void emitGlyph(WasmWriter w) {
		// if (code < 0x80) 1-byte fast path
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.ELSE);
		// else if (code < 0x800) 2-byte
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x800);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(6);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0xC0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET + 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.ELSE);
		// else if (code < 0x10000) 3-byte
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x10000);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(12);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0xE0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET + 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(6);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET + 2);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(3);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.ELSE);
		// else 4-byte
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(18);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0xF0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET + 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(12);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET + 2);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(6);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET + 3);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(4);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END); // closes IF (code < 0x10000)
		w.write(Instruction.END); // closes IF (code < 0x800)
		w.write(Instruction.END); // closes IF (code < 0x80)
	}

	// Writes the string-table entry (offset/length) to stdout via _write_str.
	private static void writeStr(WasmWriter w, WasmLispCompiler.StringTable.StringEntry entry) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
	}

	private static void getBucketsLocal(WasmWriter w, int slot) {
		getLocal(w, slot);
		castBuckets(w);
	}

	// Pushes the data array (field 1) of the TYPE_FARRAY held in slot as an eqref,
	// without
	// a width cast -- the caller picks TYPE_F64ARR / TYPE_F32ARR (or the abstract array)
	// itself.
	private static void farrayDataRaw(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeUnsignedLeb128(1);
	}

	// Pushes the f64 data array (field 1) of the TYPE_FARRAY held in slot, cast to
	// TYPE_F64ARR (double-float width).
	private static void farrayData(WasmWriter w, int slot) {
		farrayDataRaw(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_F64ARR);
	}

	// Pushes the f32 data array (field 1) of the TYPE_FARRAY held in slot, cast to
	// TYPE_F32ARR (single-float width).
	private static void farrayDataF32(WasmWriter w, int slot) {
		farrayDataRaw(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
	}

	// Pushes array.len of the TYPE_FARRAY data array in slot, width-agnostically: the
	// data
	// field is cast to the abstract array type (a supertype of both TYPE_F64ARR and
	// TYPE_F32ARR) so array.len works for either width.
	private static void farrayDataLen(WasmWriter w, int slot) {
		farrayDataRaw(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.ARRAY_HT.code());
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

	// Pushes the given field of the TYPE_VBLOCK that a --simd TYPE_FARRAY (in slot) holds
	// as its data: 0 = the element count, 1 = the width tag.
	private static void vblockField(WasmWriter w, int slot, int field) {
		farrayDataRaw(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_VBLOCK);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
		w.writeUnsignedLeb128(field);
	}

	// Pushes the dims buckets (field 0, a TYPE_HASH_BUCKETS held as eq) of the
	// TYPE_FARRAY
	// in slot.
	private static void farrayDims(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeUnsignedLeb128(0);
	}

	// Pushes an i32: whether the value in slot is a packed integer vector (any width).
	private static void emitIntVectorTest(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.I32_OR);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I32ARR);
		w.write(Instruction.I32_OR);
	}

	// Pushes the i32 length of the packed integer vector in slot (width dispatch; the
	// abstract-array cast keeps array.len width-agnostic).
	private static void emitIntVectorLen(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.ARRAY_HT.code());
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

	// Pushes data[idx] of the packed integer vector in slot as an UNSIGNED i64,
	// dispatching on the width.
	private static void emitIntVectorGetU(WasmWriter w, int slot, int idxSlot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.IF);
		w.write(Type.I64);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		getLocal(w, idxSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.ELSE);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.IF);
		w.write(Type.I64);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_I16ARR);
		getLocal(w, idxSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.ELSE);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_I32ARR);
		getLocal(w, idxSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I32ARR);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Pushes the header cons (cell.field0) of the TYPE_CELL value in param 0.
	private static void cellHeader(WasmWriter w) {
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
	}

	// Pushes the given field of the cons held in slot (cast to TYPE_CONS).
	private static void innerConsGet(WasmWriter w, int slot, int field) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
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
			int dataSlot, int idxSlot, int lenSlot, int rankSlot, int jSlot, int strideSlot, int mSlot, int baseSlot,
			int packedSlot, int singleSlot, boolean simd) {
		// `simd` selects the --simd lowering: the packed data is a TYPE_VBLOCK of v128
		// lane groups, read one element at a time through the _v_get helper, not a
		// TYPE_F64ARR/TYPE_F32ARR GC array.
		// packedSlot = 0 (a general array prints "#("/"#nA("); 1 = a packed double array
		// ("#d("); 2 = a packed single array ("#f(")
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, packedSlot);
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
		// afterwards; packedSlot is set so the prefix below becomes "#d(" / "#f(".
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.IF, 0x40);
		// dataSlot = the farray
		getLocal(w, 0);
		setLocal(w, dataSlot);
		if (simd) {
			// singleSlot = the vblock's width tag
			vblockField(w, dataSlot, 1);
			setLocal(w, singleSlot);
		}
		else {
			// singleSlot = 1 when the data array is a TYPE_F32ARR (single-float), else 0
			farrayDataRaw(w, dataSlot);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_F32ARR);
			setLocal(w, singleSlot);
		}
		// packedSlot = 1 (double -> "#d(") or 2 (single -> "#f(") so the prefix picks
		// fPrefix / sfPrefix below
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		getLocal(w, singleSlot);
		w.write(Instruction.I32_ADD);
		setLocal(w, packedSlot);
		// lenSlot = the element count
		if (simd) {
			vblockField(w, dataSlot, 0);
		}
		else {
			// array.len(farray.data) -- width-agnostic (abstract-array cast)
			farrayDataLen(w, dataSlot);
		}
		setLocal(w, lenSlot);
		// dimsSlot = newBuckets = array.new $hash_buckets (null, len)
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		getLocal(w, lenSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
		// element as f64 (widen f32 -> f64 for a single-float array), then box it
		if (simd) {
			// _v_get owns the width and lane branches
			farrayDataRaw(w, dataSlot);
			getLocal(w, idxSlot);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_GET);
		}
		else {
			getLocal(w, singleSlot);
			w.write(Instruction.IF, Type.F64.code());
			farrayDataF32(w, dataSlot);
			getLocal(w, idxSlot);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32ARR);
			w.write(Instruction.F64_PROMOTE_F32);
			w.write(Instruction.ELSE);
			farrayData(w, dataSlot);
			getLocal(w, idxSlot);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
			w.write(Instruction.END);
		}
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		getBucketsLocal(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		setLocal(w, 0);
		w.write(Instruction.END); // if (farray)

		// A packed integer vector (TYPE_I8ARR/I16ARR/I32ARR) converts in place the same
		// way: boxed integer elements (through _int_new) under a fresh rank-1 dims,
		// printing as a plain #(...) vector (packedSlot stays 0).
		emitIntVectorTest(w, 0);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		setLocal(w, dataSlot);
		// lenSlot = array.len, dispatching on the width
		emitIntVectorLen(w, dataSlot);
		setLocal(w, lenSlot);
		// dimsSlot = newBuckets = array.new $hash_buckets (null, len)
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		getLocal(w, lenSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, dimsSlot);
		// for (idx = 0; idx < len; idx++) newBuckets[idx] = _int_new(data[idx])
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
		emitIntVectorGetU(w, dataSlot, idxSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, idxSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// param0 = cell(cons([len], cons(cons(null, cons(null, i31 0)), newBuckets)))
		getLocal(w, lenSlot);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		getBucketsLocal(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		setLocal(w, 0);
		w.write(Instruction.END); // if (packed integer vector)

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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.IF, 0x40);

		// dims = header.car; dataSlot temporarily holds the (meta . data) inner cons
		cellHeader(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		setLocal(w, dimsSlot);
		cellHeader(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x7F); // (result i32)
		innerConsGet(w, dataSlot, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		setLocal(w, dataSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block

		// data = inner.cdr
		innerConsGet(w, dataSlot, 1);
		setLocal(w, dataSlot);

		// prefix: a packed float array prints "#f(" (single, packedSlot==2) or "#d("
		// (double, packedSlot==1); else "#(" for rank 1, "#" + rank + "A(" for rank n
		getLocal(w, packedSlot);
		w.write(Instruction.IF, 0x40);
		getLocal(w, packedSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.sfPrefix);
		w.write(Instruction.ELSE);
		writeStr(w, st.fPrefix);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		writeStr(w, st.rankAOpen);
		w.write(Instruction.END);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);

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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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

	// Emits the boxed-integer branches shared by _print_val and _princ_val: if the
	// value in param 0 is a TYPE_BIGNUM struct, prints its i64 digits and returns; if
	// it is a TYPE_BIGINT, prints its decimal digits through _big_print and returns.
	private static void emitPrintBignum(WasmWriter w) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_PRINT);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// Emits the ratio branch shared by _print_val and _princ_val: if the value in
	// param 0 is a ratio struct, prints "numerator/denominator" and returns.
	private static void emitPrintRatio(WasmWriter w, WasmLispCompiler.StringTable st) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.slash.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.slash.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
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
		// 4=i32 digit, 5=i32 digit_count, 6=i64 int64, 7=i64 pow, 8=i32 exp,
		// 9=i32 started
		// Adjacent runs of the same type are ONE run in the shortest legal encoding, so
		// the grouping below follows the types rather than the names above.
		w.write(5);
		w.write(2);
		w.write(Type.I32); // locals 1-2: is_neg, int_part
		w.write(1);
		w.write(Type.F64); // local 3: frac
		w.write(2);
		w.write(Type.I32); // locals 4-5: digit, digit_count
		w.write(2);
		w.write(Type.I64); // locals 6-7: int64, pow
		w.write(2);
		w.write(Type.I32); // locals 8-9: exp, started

		// NaN prints as text (digit extraction would trap in i32.trunc): value != value
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nanStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nanStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.offset());
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.length());
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		}
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check if negative -- by the sign BIT, so -0.0 keeps its sign (f64.lt
		// against 0.0 is false for -0.0)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1); // is_neg

		// If negative, write '-' and negate
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.minus.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.minus.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_NEG);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);

		// Infinity prints as text, after the sign so -Infinity gets its '-'
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.infinityStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.infinityStr.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.offset());
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.length());
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		}
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// value >= 2^63 cannot go through integer digit extraction at all: divide
		// into [1, 10) and remember the decimal exponent (printed as E<exp> at the
		// end). The digits are approximate up here -- each /10 rounds -- which is
		// the best a digit-extraction printer can do; magnitudes below 2^63 are
		// exact as before.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(0x1p63);
		w.write(Instruction.F64_GE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(10.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(10.0);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(8);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(8); // exp
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.END); // if >= 2^63

		// Integer part: the historical i32 path below 2^31, i64 digits up to 2^63
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(2147483648.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.IF, 0x40);
		// int_part = i32(floor(value)); print; frac = value - f64(int_part)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_FLOOR);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2); // int_part
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3); // frac
		w.write(Instruction.ELSE);
		// int64 = i64(floor(value)); print its decimal digits MSD-first over a
		// descending power-of-ten (10^18 covers every value below 2^63)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_FLOOR);
		w.write(Instruction.I64_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(6); // int64
		// pow = 1e9 * 1e9 (10^18; the writer's LEB is 32-bit, so build it by i64.mul)
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(1000000000);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(1000000000);
		w.write(Instruction.I64_MUL);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(7); // pow
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(9); // started
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// digit = i32((int64 / pow) % 10)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(6);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(7);
		w.write(Instruction.I64_DIV_U);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I64_REM_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4); // digit
		// emit when significant: digit != 0, already started, or the last power
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(4);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(9);
		w.write(Instruction.I32_OR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(7);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I64_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(4);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48); // '0'
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(9); // started
		w.write(Instruction.END);
		// pow /= 10; continue while pow != 0
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(7);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I64_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(7);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(7);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I64_NE);
		w.write(Instruction.BR_IF, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// frac = value - f64(int64)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(6);
		w.write(Instruction.F64_CONVERT_S_I64);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3); // frac
		w.write(Instruction.END); // if int-part width

		// Print '.'
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.period.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.period.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		// digit_count = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(5);

		// Loop to extract fractional digits
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		// frac = frac * 10.0
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.F64_CONST);
		w.writeF64(10.0);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);

		// digit = i32(trunc(frac))
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);

		// Store digit char at OUT_BUF_OFFSET
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(4);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		// frac = frac - f64(digit)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(4);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);

		// digit_count++
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(5);

		// Continue if: digit_count < 1 OR (frac > epsilon AND digit_count < 6)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_LT_S);
		// OR
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0000001);
		w.write(Instruction.F64_GT);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(6);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);

		w.write(Instruction.BR_IF, 0); // loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		// Exponent suffix from the >= 2^63 normalization: "E<exp>"
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(8);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.expE.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.expE.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(8);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.END);

		// Append newline if needed
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.offset());
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.length());
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		}

		w.write(Instruction.END);
		return body.toByteArray();
	}

}
