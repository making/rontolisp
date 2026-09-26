package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispEquality;
import am.ik.rontolisp.RenderCycleGuard;
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
	 * null eq). If a is null, returns b. Otherwise, copies a's spine iteratively and
	 * patches the last cdr to b.
	 *
	 * <p>
	 * The recursive spelling allocated its result by recursing once per element, so a
	 * long first argument exhausted the wasm stack rather than answering slowly
	 * (.todo/749). The result is unchanged (a fresh spine, the tail shared). In EH mode a
	 * first argument that is no list, or ends dotted, is {@code APPEND}'s type-error over
	 * the atom the walk met ({@code _type_err_list} under its row); outside it the body
	 * is unchanged and traps at the {@code ref.cast}.
	 * @param identityHash whether a cons carries an identity hash
	 * @param operatorGlobal the operator register, or -1 outside EH mode
	 * @param operatorId {@code APPEND}'s row, or 0 when the program cannot name it
	 * @return the function body
	 */
	static byte[] buildAppendBody(boolean identityHash, int operatorGlobal, int operatorId) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// 4 extra locals, all (ref null eq): 2=head, 3=tail, 4=cursor, 5=fresh.
		w.write(1);
		w.write(4);
		w.writeRefType(true, Type.EQ.code());

		// cursor = a; head = null; tail = null
		getLocal(w, 0);
		setLocal(w, 4);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		setLocal(w, 2);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		setLocal(w, 3);

		w.write(Instruction.BLOCK, 0x40); // $out
		w.write(Instruction.LOOP, 0x40); // $in
		// if cursor is null, break to $out
		getLocal(w, 4);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		if (operatorGlobal >= 0) {
			// EH mode: an atom other than nil is APPEND's type-error.
			getLocal(w, 4);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(operatorId);
			w.write(Instruction.SET_GLOBAL);
			w.writeUnsignedLeb128(operatorGlobal);
			getLocal(w, 4);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_TYPE_ERR_LIST);
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
		}
		// fresh = cons(cursor.car, null)
		getLocal(w, 4);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0); // field 0: car
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		WasmEmitHelper.emitNewCons(w, identityHash);
		setLocal(w, 5);
		// if head is null, head = fresh; else tail.cdr = fresh
		getLocal(w, 2);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 5);
		setLocal(w, 2);
		w.write(Instruction.ELSE);
		getLocal(w, 3);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		getLocal(w, 5);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1); // field 1: cdr
		w.write(Instruction.END);
		// tail = fresh; cursor = cursor.cdr
		getLocal(w, 5);
		setLocal(w, 3);
		getLocal(w, 4);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1); // field 1: cdr
		setLocal(w, 4);
		w.write(Instruction.BR, 0); // continue $in
		w.write(Instruction.END); // end loop $in
		w.write(Instruction.END); // end block $out

		// if head is null, return b; else tail.cdr = b and return head
		getLocal(w, 2);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1); // b
		w.write(Instruction.ELSE);
		getLocal(w, 3);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1); // b
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1); // field 1: cdr
		getLocal(w, 2);
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
	 * when the program cannot build an instance. An instance of the address-keyed layout
	 * compares its first slot only (see {@link #buildEqlTailBody}).
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1
	 * @param keyedLayout the address-keyed layout record, or -1
	 * @return the function body
	 */
	static byte[] buildEqualBody(int instanceTypeIndex, int keyedLayout, boolean charvecPossible) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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
		WasmEmitHelper.emitCharvecToStrCall(w, charvecPossible);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		getLocal(w, 1);
		WasmEmitHelper.emitCharvecToStrCall(w, charvecPossible);
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

		emitInstanceEqual(w, instanceTypeIndex, keyedLayout);

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

		// both floats -> f64 fields equal AS BITS, not numerically
		refTest(w, 0, WasmLispCompiler.TYPE_FLOAT);
		refTest(w, 1, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitFloatBitsEqual(w);
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

		// both complexes -> _equal(re, re) && _equal(im, im) (parts are always
		// real, so the recursion bottoms out in the value arms above)
		refTest(w, 0, WasmLispCompiler.TYPE_COMPLEX);
		refTest(w, 1, WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		complexField(w, 0, 0);
		complexField(w, 1, 0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		complexField(w, 0, 1);
		complexField(w, 1, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);

		// symbols and strings -> byte-wise same content (via _string_eq), so a
		// runtime-built string is equal to a literal with the same content
		emitStringContentEq(w);
		w.write(Instruction.END); // end complex if
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
	 * Builds the {@code _eql_tail} body ({@code FUNC_EQL_TAIL}): what {@code eql} -- and
	 * so {@code eq}, the same predicate ({@code .kb/eq-numbers.md}) -- answers for two
	 * values the call site has already found NOT {@code ref.eq}, with a first operand
	 * that is neither a symbol/string nor an i31 (the site settles those two inline,
	 * {@code WasmEmitHelper.emitEqlComparison}). Takes two (ref null eq) args (locals 0
	 * and 1), returns i32. Floats compare by bit pattern (or both NaN), characters by
	 * code point, boxed or limb integers and ratios by value, complexes part-wise through
	 * {@code _equal}'s eql base case; anything else is 0. Floats come first, as the boxed
	 * value a loop most often compares. One function rather than the value chain inlined
	 * at each site; in a module with none of these types the fold leaves it a constant,
	 * and {@link am.ik.wasm.WasmPeephole} then removes its calls.
	 *
	 * <p>
	 * Two instances of the address-keyed layout ({@code LispNames.OBJC_OBJECT_TYPE}, a
	 * {@code --native} program's Objective-C wrapper) are eql when their first slots --
	 * the object's address -- are: the interpreter's record and the JVM's handle compare
	 * by address, and a wrapper cannot be interned, since the table would keep every
	 * wrapper, and so every reference, alive ({@code .kb/objc.md}, "--native").
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1
	 * @param keyedLayout the address-keyed layout record, or -1 (nothing is emitted for
	 * it)
	 * @return the function body
	 */
	static byte[] buildEqlTailBody(int instanceTypeIndex, int keyedLayout) {
		boolean keyed = instanceTypeIndex >= 0 && keyedLayout >= 0;
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // 0 extra locals
		// both floats -> equal bit patterns or both NaN (-0.0 and 0.0 are not eql)
		refTest(w, 0, WasmLispCompiler.TYPE_FLOAT);
		refTest(w, 1, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitFloatBitsEqual(w);
		w.write(Instruction.ELSE);
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
		// both boxed integers -> i64 fields equal
		refTest(w, 0, WasmLispCompiler.TYPE_BIGNUM);
		refTest(w, 1, WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		bignumField(w, 0);
		bignumField(w, 1);
		w.write(Instruction.I64_EQ);
		w.write(Instruction.ELSE);
		// both limb integers -> _big_eq (canonical limbs)
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
		// both complexes -> _equal(re, re) && _equal(im, im)
		refTest(w, 0, WasmLispCompiler.TYPE_COMPLEX);
		refTest(w, 1, WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.write(Type.I32);
		complexField(w, 0, 0);
		complexField(w, 1, 0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		complexField(w, 0, 1);
		complexField(w, 1, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		if (keyed) {
			// both address-keyed instances -> their address slots eql
			refTest(w, 0, instanceTypeIndex);
			refTest(w, 1, instanceTypeIndex);
			w.write(Instruction.I32_AND);
			w.write(Instruction.IF);
			w.write(Type.I32);
			emitLayoutIs(w, 0, instanceTypeIndex, keyedLayout);
			emitLayoutIs(w, 1, instanceTypeIndex, keyedLayout);
			w.write(Instruction.I32_AND);
			w.write(Instruction.IF);
			w.write(Type.I32);
			firstInstanceSlot(w, 0, instanceTypeIndex);
			firstInstanceSlot(w, 1, instanceTypeIndex);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
			w.write(Instruction.ELSE);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.END);
			w.write(Instruction.ELSE);
		}
		// anything else (a cons, an instance, a closure, nil) is identity-only
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		if (keyed) {
			w.write(Instruction.END); // end both-instance if
		}
		w.write(Instruction.END); // end complex if
		w.write(Instruction.END); // end ratio if
		w.write(Instruction.END); // end limb-integer if
		w.write(Instruction.END); // end bignum if
		w.write(Instruction.END); // end char if
		w.write(Instruction.END); // end float if
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Opens the both-instances arm of {@code _equal} (nothing when there is no instance
	 * type): same layout record and every slot recursively equal. The caller closes the
	 * {@code if} after the remaining eql arms, so this leaves the ELSE open.
	 */
	private static void emitInstanceEqual(WasmWriter w, int instanceTypeIndex, int keyedLayout) {
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
		emitKeyedSlotCount(w, 0, instanceTypeIndex, keyedLayout);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3); // n = slot count (1 for the address-keyed layout)
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

	/**
	 * Replaces the slot count on the stack by {@code 1} when the instance in
	 * {@code local} carries the address-keyed layout: its identity is its first slot, and
	 * the rest (the handle that owns the reference) is not part of it. Nothing is emitted
	 * without such a layout.
	 */
	private static void emitKeyedSlotCount(WasmWriter w, int local, int instanceTypeIndex, int keyedLayout) {
		if (keyedLayout < 0) {
			return;
		}
		// select(count, 1, layout != keyed)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		emitLayoutIs(w, local, instanceTypeIndex, keyedLayout);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.SELECT);
	}

	/**
	 * Pushes whether the instance in {@code local} carries layout record {@code layout}.
	 */
	private static void emitLayoutIs(WasmWriter w, int local, int instanceTypeIndex, int layout) {
		instanceField(w, local, instanceTypeIndex, 0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(layout);
		w.write(Instruction.I32_EQ);
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

	/** Pushes the first slot of the instance in {@code local}. */
	private static void firstInstanceSlot(WasmWriter w, int local, int instanceTypeIndex) {
		instanceSlots(w, local, instanceTypeIndex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
	 *
	 * <p>
	 * The walk is capped at {@link LispEquality#HASH_DEPTH_CAP} levels: {@code _hash}
	 * counts its own live recursion depth in {@code depthGlobalIndex} and folds a
	 * constant 0 instead of descending past it. That is what lets a CYCLIC key be hashed
	 * at all -- unbounded recursion here is a {@code call stack exhausted} trap -- and it
	 * costs nothing in agreement with {@code _equal}, because a hash need not be
	 * injective and the cap is by DEPTH alone: two {@code equal} keys have the same shape
	 * and so fold identically. Retrieving under such a key terminates for the matching
	 * reason on {@code _equal}'s side: its {@code ref.eq} fast path answers before it
	 * recurses.
	 *
	 * <p>
	 * The depth cap bounds the walk's HEIGHT and nothing about its SIZE, so a second
	 * counter in {@code gasGlobalIndex} bounds the whole traversal at
	 * {@link LispEquality#HASH_WORK_CAP} node visits: a key whose substructure is SHARED
	 * has exponentially many root-to-leaf paths, and 64 levels of them is astronomical.
	 * The gas is REFILLED by the outermost entry -- the one that finds the depth counter
	 * at zero -- so what a key hashes to is a function of that key alone and not of what
	 * the table hashed before it, which is the rule the depth cap obeys too and the
	 * reason two {@code equal} keys still hash equal: same shape, same traversal order,
	 * same place to run out.
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1
	 * @param keyedLayout the address-keyed layout record, whose instances fold their
	 * first slot only (as {@code _equal} compares them), or -1
	 * @param depthGlobalIndex the {@code (mut i32)} recursion-depth global, or -1 to emit
	 * the uncapped body (a program with no hash table never calls {@code _hash}, and
	 * carries neither the globals nor the guard)
	 * @param gasGlobalIndex the {@code (mut i32)} work-budget global, present exactly
	 * when {@code depthGlobalIndex} is
	 * @return the function body
	 */
	static byte[] buildHashBody(int instanceTypeIndex, int keyedLayout, int depthGlobalIndex, int gasGlobalIndex,
			boolean charvecPossible, boolean identityHash) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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

		// The OUTERMOST entry -- the one that finds the depth counter at zero -- refills
		// the work budget, so one placement's gas never depends on the last one's.
		if (depthGlobalIndex >= 0) {
			w.write(Instruction.GET_GLOBAL);
			w.writeUnsignedLeb128(depthGlobalIndex);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.IF);
			w.write(0x40); // void block type
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(LispEquality.HASH_WORK_CAP);
			w.write(Instruction.SET_GLOBAL);
			w.writeUnsignedLeb128(gasGlobalIndex);
			w.write(Instruction.END);
		}

		// Depth cap AND work budget: at either limit answer 0 without descending (and
		// without counting a level or spending gas, so both counters stay exact);
		// otherwise spend one node, count this level, fold, and restore the DEPTH counter
		// on the way out. The fold's i32 result sits under the restore. The gas is not
		// restored -- it belongs to the whole traversal, which is the point: a per-branch
		// count bounds nothing when the branches share their substructure.
		if (depthGlobalIndex >= 0) {
			w.write(Instruction.GET_GLOBAL);
			w.writeUnsignedLeb128(depthGlobalIndex);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(LispEquality.HASH_DEPTH_CAP);
			w.write(Instruction.I32_GE_S);
			w.write(Instruction.GET_GLOBAL);
			w.writeUnsignedLeb128(gasGlobalIndex);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.I32_LE_S);
			w.write(Instruction.I32_OR);
			w.write(Instruction.IF);
			w.write(Type.I32);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.ELSE);
			emitDepthAdjust(w, gasGlobalIndex, Instruction.I32_SUB);
			emitDepthAdjust(w, depthGlobalIndex, Instruction.I32_ADD);
		}

		// Normalize a mutable character vector into a string up front, so a character
		// vector key hashes exactly like the string with the same content (agreeing
		// with _equal's entry normalization -- equal-table gethash/sethash with mixed
		// string/character-vector keys interoperate).
		getLocal(w, 0);
		WasmEmitHelper.emitCharvecToStrCall(w, charvecPossible);
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
			emitKeyedSlotCount(w, 0, instanceTypeIndex, keyedLayout);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(4); // end = slot count (1 for the address-keyed layout)
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

		// a cell (a general array, a hash table) -> its identity-hash slot when the
		// module's cells carry one: equal on a cell IS identity, so this is the one
		// hash that agrees with it and survives the array being written
		if (identityHash) {
			refTest(w, 0, WasmLispCompiler.TYPE_CELL);
			w.write(Instruction.IF);
			w.write(Type.I32);
			getLocal(w, 0);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_IHASH);
			w.write(Instruction.ELSE);
		}

		// anything else (e.g. a closure) -> 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);

		if (identityHash) {
			w.write(Instruction.END); // end cell if
		}
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

		if (depthGlobalIndex >= 0) {
			emitDepthAdjust(w, depthGlobalIndex, Instruction.I32_SUB);
			w.write(Instruction.END); // end depth-cap if
		}

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Emits {@code g = g +/- 1} for one of {@code _hash}'s two counters -- the recursion
	 * depth, restored on the way out, or the work budget, which is not.
	 */
	private static void emitDepthAdjust(WasmWriter w, int depthGlobalIndex, int op) {
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(op);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
	}

	/**
	 * Builds the _hash_resize helper. Takes the table's header cons (local 0 =
	 * {@code (count . buckets)}), doubles the bucket array and rehashes every entry into
	 * it, then stores the new array back into the header's cdr. Returns nothing.
	 * @param identityTables whether the module can hold an eql/eq table: the tag is read
	 * off the header and a key of such a table rehashes by {@code _ihash}, exactly where
	 * the table primitives place it -- and, the same flag, whether the module's conses
	 * carry the identity-hash slot the bucket chain's fresh conses must then declare
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1
	 * @return the function body
	 */
	static byte[] buildHashResizeBody(boolean identityTables, int instanceTypeIndex) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 4 x (ref null eq) [1=oldArr 2=newArr 3=cur 4=entry] + the entry key
		// [5] when identity tables can exist, 3 x i32 [6=i 7=newCap 8=j] + the test
		// tag [9] then. Ref locals precede i32 locals, so the key takes the next ref
		// slot and the tag the last i32 one.
		int refCount = identityTables ? 5 : 4;
		int i32Count = identityTables ? 4 : 3;
		w.write(2); // 2 local groups
		w.writeUnsignedLeb128(refCount);
		w.writeRefType(true, Type.EQ.code());
		w.writeUnsignedLeb128(i32Count);
		w.write(Type.I32);

		int oldArr = 1, newArr = 2, cur = 3, entry = 4, i = 5, newCap = 6, j = 7;
		int key = 5, tag = 9;
		if (identityTables) {
			i = 6;
			newCap = 7;
			j = 8;
		}

		if (identityTables) {
			// tag = the header count's low two bits: what the entries were placed by.
			getLocal(w, 0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(Type.I31.code());
			w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(3);
			w.write(Instruction.I32_AND);
			setLocal(w, tag);
		}

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
		// j = (hash(car(entry)) & 0x7fffffff) % newCap -- the identity hash for a key
		// of an eql/eq table, exactly as the table primitives placed it.
		getLocal(w, entry);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		if (identityTables) {
			setLocal(w, key);
			getLocal(w, tag);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_GE_S);
			w.write(Instruction.IF);
			w.write(Type.I32);
			getLocal(w, key);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_IHASH);
			w.write(Instruction.ELSE);
			getLocal(w, key);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH);
			w.write(Instruction.END);
		}
		else {
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH);
		}
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
		// the module's conses carry the identity-hash slot exactly when it can hold an
		// identity table: one flag, one answer
		WasmEmitHelper.emitNewCons(w, identityTables);
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

	// Field 0 is the tag; part 0 (real) lives in field 1, part 1 in field 2.
	private static void complexField(WasmWriter w, int local, int field) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		w.writeUnsignedLeb128(field + 1);
	}

	// Pushes 1 (i32) when the (ref null eq) held in `local` is a bare symbol name --
	// a TYPE_STRING whose bytes are NOT quote-framed, exactly what a symbol value is
	// (WasmLispCompiler.TYPE_STRING's javadoc / .kb/wasm-gc-strings.md) -- equal to
	// `literal` (ASCII only; QUOTE/FUNCTION are the only two callers, todo 626), 0
	// otherwise. `literal`'s bytes are compared unrolled since both callers pass a
	// short fixed string, which is cheaper here than building a throwaway TYPE_STRING
	// and calling FUNC_STRING_EQ.
	private static void emitBareSymbolNameEquals(WasmWriter w, int local, String literal) {
		refTest(w, local, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, Type.I32.code());
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(literal.length());
		w.write(Instruction.I32_EQ);
		// i32.and does not short-circuit, so the per-byte checks below are only safe
		// to unroll once the length is CONFIRMED equal (nested inside this IF) -- an
		// eager AND against a shorter string's array would index out of bounds.
		w.write(Instruction.IF, Type.I32.code());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		for (int idx = 0; idx < literal.length(); idx++) {
			getLocal(w, local);
			WasmEmitHelper.emitStrBytesArray(w);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(idx);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(literal.charAt(idx));
			w.write(Instruction.I32_EQ);
			w.write(Instruction.I32_AND);
		}
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
	}

	/**
	 * Emits the quote/function abbreviation check ahead of {@link #emitPrintConsList}'s
	 * general loop (todo 626, CLHS 22.1.3.7): a cons cell {@code {car, cdr}} whose
	 * {@code car} is the bare symbol name {@code "QUOTE"} / {@code "FUNCTION"} and whose
	 * {@code cdr} is itself a cons cell {@code {x, null}} (a proper 2-element list --
	 * {@code (QUOTE A B)} still prints in full) writes {@code '}/{@code #'} then
	 * {@code x}'s rendering and returns through the SAME exit the function's own tail
	 * uses ({@code emitRenderGuardExit}), before the caller's Floyd/loop code ever runs.
	 * Runs INSIDE the guarded section {@code emitRenderGuardEnter} already opened, so a
	 * self-referential {@code (quote x)} with {@code x} reaching back to this very cell
	 * still hits the guard on the recursive render of {@code x} rather than looping
	 * forever. {@code carSlot}/{@code cdrSlot} borrow {@code emitPrintConsList}'s
	 * {@code stopSlot}/{@code fastSlot} (both {@code (ref null eq)}, unclaimed until the
	 * Floyd setup right after this), {@code shapeOkSlot} borrows its {@code seenSlot}
	 * (i32, likewise unclaimed). Applies identically under {@code FUNC_PRINT_VAL} and
	 * {@code FUNC_PRINC_VAL} -- SBCL-verified, {@code princ} abbreviates too.
	 */
	private static void emitQuoteAbbrevCheck(WasmWriter w, WasmLispCompiler.StringTable st, int headSlot,
			int elementFunc, int pathGlobalIndex, int depthGlobalIndex, int carSlot, int cdrSlot, int shapeOkSlot) {
		consField(w, headSlot, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(carSlot);
		consField(w, headSlot, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(cdrSlot);
		// shapeOk = (cdr is a cons) && (cdr.cdr is null)
		refTest(w, cdrSlot, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.IF, Type.I32.code());
		consField(w, cdrSlot, 1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(shapeOkSlot);
		getLocal(w, shapeOkSlot);
		w.write(Instruction.IF, 0x40);
		emitBareSymbolNameEquals(w, carSlot, "QUOTE");
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.quoteMark.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.quoteMark.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		consField(w, cdrSlot, 0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);
		emitRenderGuardExit(w, pathGlobalIndex, depthGlobalIndex);
		w.write(Instruction.RETURN);
		w.write(Instruction.ELSE);
		emitBareSymbolNameEquals(w, carSlot, "FUNCTION");
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.functionMark.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.functionMark.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		consField(w, cdrSlot, 0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);
		emitRenderGuardExit(w, pathGlobalIndex, depthGlobalIndex);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	private static void floatField(WasmWriter w, int local) {
		getLocal(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
	}

	/**
	 * {@code eql}/{@code equal} on two floats held in locals 0 and 1: equal BIT PATTERNS,
	 * or both NaN.
	 * <p>
	 * Not {@code f64.eq}, which is the numeric comparison and so calls {@code -0.0} and
	 * {@code 0.0} equal. CLHS makes those two {@code =} but NOT {@code eql}, and both
	 * other backends answer {@code NIL} for them -- the interpreter through
	 * {@code Double.equals} and the JVM backend through {@code _eqv} -- as does upstream
	 * Common Lisp.
	 * <p>
	 * The both-NaN arm is what keeps this identical to {@code Double.equals} rather than
	 * merely bitwise: {@code Double.doubleToLongBits} folds every NaN onto one canonical
	 * pattern, so Java calls two NaNs {@code equals} however they were produced, while
	 * raw bits would separate a NaN from that same NaN negated (the sign bit differs, and
	 * {@code f64.neg} flips it without otherwise disturbing the payload).
	 */
	private static void emitFloatBitsEqual(WasmWriter w) {
		floatField(w, 0);
		w.write(Instruction.I64_REINTERPRET_F64);
		floatField(w, 1);
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.I64_EQ);
		floatField(w, 0);
		floatField(w, 0);
		w.write(Instruction.F64_NE);
		floatField(w, 1);
		floatField(w, 1);
		w.write(Instruction.F64_NE);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
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
	 * <p>
	 * It carries the cycle guard, the wasm twin of {@code LispInstance.render}'s (kept in
	 * step by {@code WasmLispCompilerIntegrationTest.printOfACyclicInstanceGraphIsFinite}
	 * on both wasm paths): an instance already on the current rendering path -- a scene
	 * graph's parent/children pair is the everyday case -- or the frame past the
	 * 256-frame depth cap writes {@code #}, the {@code *print-level*} cutoff marker,
	 * instead of exhausting the wasm stack mid-write. Identity ({@code ref.eq}), not
	 * equality, so the same instance REACHABLE twice on a finite path still renders
	 * twice. The path array and its depth live in the two module globals; the two escape
	 * modes share them, since a nested render may switch modes. Placed AFTER the pathname
	 * arm, whose one slot is a string and cannot recurse, so only the one return at the
	 * end needs the pop.
	 * @param w the writer for the printer body
	 * @param st the module string table, still open for appends
	 * @param elementFunc the per-slot-value renderer
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1
	 * @param renderPathGlobalIndex the rendering-path global (a lazily allocated
	 * {@code TYPE_HASH_BUCKETS} array behind a {@code (mut (ref null eq))})
	 * @param renderDepthGlobalIndex the rendering-depth global (a {@code (mut i32)})
	 * @param addrSlot an i32 local holding the layout record address
	 * @param idxSlot an i32 local holding the slot loop counter (also the guard's scan
	 * counter -- the guard runs before the slot loop initializes it)
	 * @param cntSlot an i32 local holding the slot count
	 */
	private static void emitPrintInstance(WasmWriter w, WasmLispCompiler.StringTable st, int elementFunc,
			int instanceTypeIndex, int renderPathGlobalIndex, int renderDepthGlobalIndex, int addrSlot, int idxSlot,
			int cntSlot) {
		if (instanceTypeIndex < 0) {
			return;
		}
		WasmLispCompiler.StringTable.StringEntry openStruct = st.addString("#S(");
		WasmLispCompiler.StringTable.StringEntry openClass = st.addString("#<");
		WasmLispCompiler.StringTable.StringEntry closeClass = st.addString(">");
		WasmLispCompiler.StringTable.StringEntry keySep = st.addString(" :");
		WasmLispCompiler.StringTable.StringEntry depthMarker = st.addString("#");
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
		// kind == OPAQUE (the %STREAM value): "#<NAME>" with NO slots in either escape
		// mode. The HANDLE slot is backend-local (a WASI fd, a table index, a wasm
		// linear-memory address) and must never reach the output
		// (.kb/emitted-output-determinism.md); same text as the async #<STREAM> tag and
		// the other two backends. Before the cycle guard, which its slot-free rendering
		// cannot need.
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_KIND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmInstanceLayouts.KIND_OPAQUE);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		emitWriteString(w, openClass);
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_NAME_OFF);
		emitLoadLayoutWord(w, addrSlot, WasmInstanceLayouts.OFF_NAME_LEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		emitWriteString(w, closeClass);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// The cycle guard (see the method comment).
		emitRenderGuardEnter(w, depthMarker, renderPathGlobalIndex, renderDepthGlobalIndex, idxSlot);
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
		// The guard's pop: path[--depth] = null. No unwind protection is needed --
		// nothing on this path throws.
		emitRenderGuardExit(w, renderPathGlobalIndex, renderDepthGlobalIndex);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	/**
	 * Emits the shared cycle guard's ENTER (the wasm twin of {@code RenderCycleGuard}):
	 * lazily allocates the rendering-path array behind {@code pathGlobal}, scans it for
	 * the value in local 0 (identity, {@code ref.eq} -- the same value REACHABLE twice on
	 * a finite path still renders twice), checks the depth cap, and pushes the value. A
	 * value already on the path, or the frame past
	 * {@code RenderCycleGuard.MAX_RENDER_DEPTH}, writes {@code #} (the
	 * {@code *print-level*} cutoff marker) and RETURNS instead of entering. Shared by the
	 * instance, cons and array arms of both printers -- one path, one mechanism.
	 * @param scanSlot an i32 local free for the scan counter
	 */
	private static void emitRenderGuardEnter(WasmWriter w, WasmLispCompiler.StringTable.StringEntry depthMarker,
			int pathGlobalIndex, int depthGlobalIndex, int scanSlot) {
		// if (path == null) path = array.new_default $buckets CAP
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(pathGlobalIndex);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(RenderCycleGuard.MAX_RENDER_DEPTH);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(pathGlobalIndex);
		w.write(Instruction.END);
		// for (i = 0; i < depth; i++) if (path[i] == arg) { write "#"; return; }
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(scanSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(scanSlot);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(pathGlobalIndex);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(scanSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, 0x40);
		emitWriteString(w, depthMarker);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(scanSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(scanSlot);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// if (depth >= CAP) { write "#"; return; }
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(RenderCycleGuard.MAX_RENDER_DEPTH);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		emitWriteString(w, depthMarker);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// path[depth] = arg; depth++
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(pathGlobalIndex);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
	}

	/**
	 * Emits the printers' cons arm -- the tail of {@code print_val}/{@code princ_val},
	 * shared so the two escape modes cannot drift: the value in local 0 is rendered as a
	 * list, elements through {@code elementFunc}. Two cycle defenses, the same discipline
	 * as the interpreter's {@code LispCons} renderer and the JVM's emitted
	 * {@code _consToString} (kept in step by
	 * {@code WasmLispCompilerIntegrationTest.printOfACyclicConsIsFinite}): the chain HEAD
	 * opens one frame on the shared rendering path ({@link #emitRenderGuardEnter}), so a
	 * car reaching back to a list still being rendered -- or the frame past the depth cap
	 * -- writes {@code #}; and the cdr chain, walked iteratively, is pre-scanned with
	 * Floyd's cycle detection, the second arrival at the cycle-start cell writing the
	 * improper tail {@code " . #"} -- every element exactly once, then the marker. Emits
	 * no trailing {@code Instruction.END}: the caller closes the function body.
	 * @param elementFunc the per-element printer ({@code FUNC_PRINT_VAL} /
	 * {@code FUNC_PRINC_VAL})
	 * @param stopSlot a (ref null eq) local for the chain's cycle-start cell (local 1
	 * doubles as Floyd's slow cursor before the render loop claims it as the chain
	 * cursor)
	 * @param fastSlot a (ref null eq) local for Floyd's fast cursor
	 * @param seenSlot an i32 local for the cycle-start cell's seen flag
	 * @param scanSlot an i32 local for the guard's path scan
	 */
	private static void emitPrintConsList(WasmWriter w, WasmLispCompiler.StringTable st, int elementFunc,
			int pathGlobalIndex, int depthGlobalIndex, int stopSlot, int fastSlot, int seenSlot, int scanSlot) {
		WasmLispCompiler.StringTable.StringEntry depthMarker = st.addString("#");
		emitRenderGuardEnter(w, depthMarker, pathGlobalIndex, depthGlobalIndex, scanSlot);
		// The quote/function abbreviation (todo 626), checked before the general loop
		// below claims stopSlot/fastSlot/seenSlot for their own purpose.
		emitQuoteAbbrevCheck(w, st, 0, elementFunc, pathGlobalIndex, depthGlobalIndex, stopSlot, fastSlot, seenSlot);
		// Floyd's cycle detection over the cdr chain: stop = the cell where the cycle
		// begins, or null for a terminating chain. slow rides local 1 (the render
		// loop's cursor, still unclaimed), fast rides fastSlot.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(stopSlot);
		getLocal(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		getLocal(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(fastSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		refTest(w, fastSlot, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		consField(w, fastSlot, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(fastSlot);
		refTest(w, fastSlot, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		consField(w, fastSlot, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(fastSlot);
		consField(w, 1, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		getLocal(w, 1);
		getLocal(w, fastSlot);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		// The cursors met: walk head and meeting point in step to the cycle start.
		getLocal(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, 1);
		getLocal(w, fastSlot);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		consField(w, 1, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		consField(w, fastSlot, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(fastSlot);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(stopSlot);
		w.write(Instruction.END);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(seenSlot);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		getLocal(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		getLocal(w, 1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		refTest(w, 1, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		getLocal(w, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		// The cycle-start cell: rendered once, its second arrival closes the list as
		// the improper tail " . #".
		getLocal(w, 1);
		getLocal(w, stopSlot);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, seenSlot);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		emitWriteString(w, depthMarker);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.END);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(seenSlot);
		w.write(Instruction.END);

		getLocal(w, 2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		consField(w, 1, 0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);

		consField(w, 1, 1);
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
		emitRenderGuardExit(w, pathGlobalIndex, depthGlobalIndex);
	}

	/** Emits the shared cycle guard's EXIT: {@code path[--depth] = null}. */
	private static void emitRenderGuardExit(WasmWriter w, int pathGlobalIndex, int depthGlobalIndex) {
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(pathGlobalIndex);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
	// The nil-passing car/cdr of the list in `slot`: the shared inline shape, or under
	// --optimize=size a call of the shared _car/_cdr body, exactly as a compiled
	// (car x)/(cdr x) site chooses (.kb/cons-access-runtime.md).
	private static void emitNullSafeCell(WasmWriter w, int slot, boolean head, boolean sharedConsReaders) {
		if (sharedConsReaders) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(slot);
			WasmConsRuntimeBuilder.emitCall(w, head ? 0 : 1);
			return;
		}
		WasmEmitHelper.emitInlineConsField(w, slot, head ? 0 : 1);
	}

	/**
	 * One dispatcher as it is emitted: the body of the fixed-index dispatch function,
	 * plus the PAGE functions it calls when the ladder was too large to be one body.
	 *
	 * @param body the fixed-index dispatcher's own body
	 * @param pages the page bodies, in the module index order they are emitted in -- the
	 * first sits at the {@code pageFuncBase} handed to
	 * {@link #buildDispatch(int, List, List, int, WasmLispCompiler.StringTable, boolean, int, boolean, Set, int)}
	 * . Empty for a dispatcher that fits in one body, which is every dispatcher of every
	 * program under the budget below.
	 */
	record DispatchFunctions(byte[] body, List<byte[]> pages) {
	}

	/**
	 * The radix of the dispatch tree: a paged dispatcher reads one 8-bit DIGIT of the
	 * funcId per level, so no node carries more than 256 {@code br_table} cases however
	 * many callables the program has. A case is ~110 bytes, and ~410 at its widest (ten
	 * required parameters walked out of the spread dispatcher's argument list), so a leaf
	 * page stays under ~105 KB -- inside the 256 KiB body bound
	 * ({@code .kb/wasm-function-body-size.md}) with a factor of two to spare.
	 */
	private static final int DISPATCH_PAGE_BITS = 8;

	private static final int DISPATCH_PAGE_SIZE = 1 << DISPATCH_PAGE_BITS;

	/**
	 * A dispatcher whose single body passes this is paged; every dispatcher under it is
	 * emitted exactly as it was before paging existed, so a program that is in no danger
	 * is byte-identical AND keeps its indirect calls one call deep. Half the 256 KiB body
	 * bound -- the paging is a safety valve, not a layout, and the widest dispatcher any
	 * shipped example builds is the clack/ningle stack's 72 KB spread one, which stays
	 * whole. A paged ladder lands at ~105 KB in the worst case, so crossing this gate
	 * always brings the body back under it.
	 */
	private static final int DISPATCH_PAGE_BUDGET_BYTES = 128 * 1024;

	/**
	 * One callable a dispatcher can reach: which {@code br_table} slot selects it, which
	 * module function it is, and how its parameters are filled.
	 */
	private record DispatchTarget(int funcId, int funcIndex, int required, boolean variadic) {
	}

	/**
	 * As {@link #buildDispatch}, for a dispatcher that is known to fit one body (the
	 * arity ladders of a program with no {@code apply}, and every test that builds one
	 * dispatcher on its own). Fails rather than silently emitting a body that calls pages
	 * nobody placed.
	 */
	static byte[] buildDispatchBody(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, int numDefuns, WasmLispCompiler.StringTable st,
			boolean usesEval, int userFuncBase, boolean identityHash) {
		DispatchFunctions built = buildDispatch(arity, defuns, lambdaDecls, numDefuns, st, usesEval, userFuncBase,
				false, null, 0, null, -1, false, null, identityHash);
		if (!built.pages().isEmpty()) {
			throw new IllegalStateException("dispatcher for arity " + arity + " needs pages; use buildDispatch");
		}
		return built.body();
	}

	/**
	 * Builds one dispatcher: the {@code br_table} over every callable a function VALUE of
	 * this shape can name, with {@code spread} selecting the SPREAD dispatcher -- one
	 * function over EVERY callable, taking the argument list as a single cons list (the
	 * arity-1 signature) rather than one parameter per argument.
	 *
	 * <p>
	 * {@code _apply} calls the spread one. The per-arity dispatchers cannot serve
	 * {@code apply}: they take one WASM parameter per Lisp argument, so they stop at
	 * {@link WasmLispCompiler#MAX_CALLABLE_ARITY}, and an {@code apply} whose designator
	 * is COMPUTED has to go through them -- quri's
	 * {@code (apply (scheme-constructor s) :scheme s ... )} passes fourteen arguments to
	 * a {@code &key} constructor and used to trap on the ladder's fall-through. Each
	 * spread case walks its target's required parameters out of the list and hands a
	 * variadic target the remaining TAIL, which is the callee's physical rest parameter.
	 *
	 * <p>
	 * <b>Paging.</b> The spread dispatcher is one case per callable in the program, so
	 * its body grows with the program's function count and with nothing a test author can
	 * see: the {@code ci-spec.yaml} corpus reached 258 KB of it against a 256 KiB bound
	 * that exists because a wasmtime cold compile needs memory superlinear in ONE body
	 * ({@code .kb/wasm-function-body-size.md}). Past {@link #DISPATCH_PAGE_BUDGET_BYTES}
	 * the ladder therefore becomes a TREE: the fixed-index dispatcher reads the top 8-bit
	 * digit of the funcId and calls a page, which reads the next digit, until a leaf
	 * holds the ~256 cases of one funcId page. The body of every node is bounded by the
	 * radix, so the emitted body size no longer depends on how many functions the program
	 * has -- one extra call per {@code apply} per level buys it, and only for a program
	 * that was near the bound anyway.
	 * @param arity ignored when {@code spread} is true
	 * @param spread whether to build the spread dispatcher instead of an arity one
	 * @param dispatchable the funcIds this program can reach as a function VALUE, or
	 * {@code null} for "every one of them". A funcId outside the set is called only
	 * directly, so giving it a case would only pin it for {@code --optimize}
	 * ({@code WasmLispCompiler.dispatchableFuncIds}); its {@code br_table} slot points at
	 * the default arm, which is where an unresolvable designator already went.
	 * @param pageFuncBase the module function index the first page would take, when this
	 * dispatcher needs pages
	 * @param notFunction what applying a non-function throws, or {@code null} outside EH
	 * mode, where it traps
	 * @return the dispatcher body and its pages
	 */
	static DispatchFunctions buildDispatch(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, int numDefuns, WasmLispCompiler.StringTable st,
			boolean usesEval, int userFuncBase, boolean spread, @Nullable Set<Integer> dispatchable, int pageFuncBase,
			@Nullable ArityReport report, int arityChkIndex, boolean sharedConsReaders,
			@Nullable NotFunctionReport notFunction, boolean identityHash) {
		int dispatchArgs = spread ? 1 : arity;
		List<DispatchTarget> targets = dispatchTargets(arity, defuns, lambdaDecls, spread, dispatchable, userFuncBase);
		// The callables this dispatcher CANNOT serve: their funcId reaching it is a call
		// with the wrong number of arguments, not an unknown function, and the arm
		// reports it (ArityReport). The spread dispatcher serves every callable, so it
		// has no such ids -- it checks the LIST LENGTH inside each case instead.
		SortedMap<Integer, Integer> missShapes = report == null || spread ? new TreeMap<>()
				: missShapes(arity, defuns, lambdaDecls, dispatchable);

		int maxFuncId = 0;
		for (DispatchTarget t : targets) {
			maxFuncId = Math.max(maxFuncId, t.funcId());
		}
		if (!missShapes.isEmpty()) {
			maxFuncId = Math.max(maxFuncId, missShapes.lastKey());
		}
		int levels = dispatchLevels(maxFuncId);

		// The unpaged shape first: it is what all but a handful of programs emit, and
		// its SIZE is what decides whether this one needs pages at all -- but only when
		// it CAN be the answer. `emitDispatchCases` writes one br_table label per id
		// from 0 up to the largest, at least a byte each, so a maxFuncId past the budget
		// is an over-budget body by itself and the paging decision is already made;
		// `levels == 1` cannot rescue it either, since that means maxFuncId < 256.
		// Measuring such a body would cost the array to build it -- 16 MB at a funcId of
		// 2^24, the value one full `./mvnw test` once reached
		// (`.kb/wasm-function-body-size.md`).
		if (maxFuncId < DISPATCH_PAGE_BUDGET_BYTES) {
			ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
			WasmWriter w = new WasmWriter(body);
			emitDispatchPrologue(w, arity, dispatchArgs, spread, usesEval, report != null, notFunction, identityHash);
			emitDispatchCases(w, targets, missShapes, arity, dispatchArgs, spread, 0, report, arityChkIndex,
					sharedConsReaders, identityHash);
			emitDispatchEpilogue(w, dispatchArgs, notFunction);
			w.write(Instruction.END); // end function
			byte[] single = body.toByteArray();
			// levels == 1: every callable is inside one page already, so the body is
			// large because its CASES are (a spread dispatcher over ten-parameter
			// targets), not because there are many. Splitting the one page would need a
			// second radix, and the worst case here is still ~105 KB, well inside the
			// bound.
			if (single.length <= DISPATCH_PAGE_BUDGET_BYTES || levels == 1) {
				return new DispatchFunctions(single, List.of());
			}
		}

		List<byte[]> pages = new ArrayList<>();
		// prefix (funcId >>> 8*level) -> the module index of the node covering it.
		Map<Integer, Integer> childIndex = new TreeMap<>();
		Map<Integer, List<DispatchTarget>> leaves = new TreeMap<>();
		for (DispatchTarget t : targets) {
			leaves.computeIfAbsent(t.funcId() >>> DISPATCH_PAGE_BITS, k -> new ArrayList<>()).add(t);
		}
		for (Integer missId : missShapes.keySet()) {
			leaves.computeIfAbsent(missId >>> DISPATCH_PAGE_BITS, k -> new ArrayList<>());
		}
		for (Map.Entry<Integer, List<DispatchTarget>> leaf : leaves.entrySet()) {
			childIndex.put(leaf.getKey(), pageFuncBase + pages.size());
			SortedMap<Integer, Integer> pageMisses = missShapes.subMap(leaf.getKey() << DISPATCH_PAGE_BITS,
					((leaf.getKey() + 1) << DISPATCH_PAGE_BITS));
			pages.add(buildDispatchLeafPage(leaf.getValue(), pageMisses, leaf.getKey(), arity, dispatchArgs, spread,
					report, arityChkIndex, sharedConsReaders, identityHash));
		}
		for (int level = 1; level <= levels - 2; level++) {
			Map<Integer, Map<Integer, Integer>> parents = new TreeMap<>();
			for (Map.Entry<Integer, Integer> child : childIndex.entrySet()) {
				parents.computeIfAbsent(child.getKey() >>> DISPATCH_PAGE_BITS, k -> new TreeMap<>())
					.put(child.getKey() & (DISPATCH_PAGE_SIZE - 1), child.getValue());
			}
			Map<Integer, Integer> next = new TreeMap<>();
			for (Map.Entry<Integer, Map<Integer, Integer>> parent : parents.entrySet()) {
				next.put(parent.getKey(), pageFuncBase + pages.size());
				pages.add(buildDispatchNodePage(parent.getValue(), dispatchArgs, level));
			}
			childIndex = next;
		}

		// The root keeps the fixed index every call site already wrote: the prologue as
		// before, then the top digit selects a page instead of a case.
		ByteArrayOutputStream rootBody = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter rw = new WasmWriter(rootBody);
		emitDispatchPrologue(rw, arity, dispatchArgs, spread, usesEval, report != null, notFunction, identityHash);
		int funcIdLocal = dispatchArgs + 1;
		rw.write(Instruction.GET_LOCAL);
		rw.writeUnsignedLeb128(funcIdLocal);
		rw.write(Instruction.I32_CONST);
		rw.writeSignedLeb128(DISPATCH_PAGE_BITS * (levels - 1));
		rw.write(Instruction.I32_SHR_U);
		rw.write(Instruction.SET_LOCAL);
		rw.writeUnsignedLeb128(funcIdLocal);
		emitPageTable(rw, childIndex, dispatchArgs, funcIdLocal);
		emitDispatchEpilogue(rw, dispatchArgs, notFunction);
		rw.write(Instruction.END); // end function
		return new DispatchFunctions(rootBody.toByteArray(), pages);
	}

	/**
	 * Where a callee shape carries the funcId a report names the operator of:
	 * {@code funcId + 1} from this bit up, 0 for a callee reported as {@code Function}.
	 * The required count doubled, plus one for a {@code &rest} tail, stays below it.
	 */
	static final int ARITY_FUNC_ID_SHIFT = 16;

	/**
	 * The funcIds a shape can carry: {@code funcId + 1} has to fit the sixteen bits above
	 * {@link #ARITY_FUNC_ID_SHIFT}, or it would wrap onto a smaller id.
	 */
	static final int ARITY_MAX_NAMED_FUNC_ID = (1 << (Integer.SIZE - ARITY_FUNC_ID_SHIFT)) - 1;

	/**
	 * The callee shape a wrong-count report is spelled from: the required count doubled,
	 * plus one for a {@code &rest} tail, and -- for a callee whose report names its
	 * operator -- {@code funcId + 1} above {@link #ARITY_FUNC_ID_SHIFT}.
	 * @param required the callee's required parameter count
	 * @param variadic whether it takes a {@code &rest} tail
	 * @param namedFuncId the callee's funcId when its report names an operator, else -1
	 * @return the shape
	 */
	static int arityShape(int required, boolean variadic, int namedFuncId) {
		int shape = required * 2 + (variadic ? 1 : 0);
		return namedFuncId >= 0 && namedFuncId < ARITY_MAX_NAMED_FUNC_ID && shape < 1 << (ARITY_FUNC_ID_SHIFT - 1)
				? shape | (namedFuncId + 1) << ARITY_FUNC_ID_SHIFT : shape;
	}

	/**
	 * What a per-arity dispatcher needs to report a wrong argument COUNT: the message
	 * pieces it assembles at run time and the {@code program-error} instance it hands the
	 * throw.
	 *
	 * <p>
	 * The no-match arm used to be the same {@code unreachable} an unknown designator
	 * reaches -- an uncatchable trap for what the interpreter signals as a catchable
	 * {@code program-error}. It now throws one, with {@link ClosRegistry#arityMessage}'s
	 * text assembled from five interned pieces: whole messages cannot be interned because
	 * the string table is fixed before the dispatchers are built and a message is one per
	 * (callee shape, dispatcher arity) pair. Two of the pieces are a decimal rendered
	 * through {@code _prin1_to_str} over an {@code i31}, which is how the interpreter
	 * prints the same counts.
	 *
	 * <p>
	 * The pieces are interned on FIRST USE, not on construction: a module can have
	 * dispatchers and still report nothing (every callable matches every arity it
	 * dispatches), and eagerly interned strings nothing reads are shaken back out --
	 * leaving a HOLE that splits the data segment in two and costs the module a segment
	 * header it did not need (6 B, measured on
	 * {@code (print (handler-case (car 1) (error (c) :e)))}).
	 *
	 * <p>
	 * A built-in operator's function value names the operator in place of
	 * {@code Function} ({@code CONS expects 2 arguments, got 1}), as the interpreter's
	 * does. Where the module has a defun under such a name, the report opens its message
	 * through one shared function, {@code _arity_opening}
	 * ({@link #buildArityOpeningBody}), which reads the callee's funcId out of the shape
	 * ({@link #arityShape}) and answers {@code "NAME expects "} from the operator's
	 * interned name -- or {@code "Function expects "} for any other callee. Shared rather
	 * than inlined: a selection over every named operator in each dispatcher cost an
	 * eval-carrying module, which makes every wrapper dispatchable, one copy per
	 * dispatcher arity (+36 KB on a 367 KB module, measured 2026-09-26).
	 *
	 * <p>
	 * Built only in EH mode behind a handler landing pad -- outside that nothing could
	 * catch the throw and the instance representation may not exist, so the arm stays the
	 * {@code unreachable} it was, byte for byte.
	 */
	static final class ArityReport {

		private final WasmLispCompiler.StringTable stringTable;

		/** The baked layout record of {@code %class-PROGRAM-ERROR}. */
		private final int layoutAddress;

		/** The module's instance struct type. */
		private final int instanceTypeIndex;

		/** The layout's reserved slot count. */
		private final int slotCapacity;

		/** The index of the class's {@code format-control} slot. */
		private final int formatControlSlot;

		/** Whether the module's instances and conses carry the identity-hash slot. */
		private final boolean identityHash;

		private WasmLispCompiler.StringTable.@Nullable StringEntry prefix;

		private WasmLispCompiler.StringTable.@Nullable StringEntry atLeast;

		private WasmLispCompiler.StringTable.@Nullable StringEntry argument;

		private WasmLispCompiler.StringTable.@Nullable StringEntry plural;

		private WasmLispCompiler.StringTable.@Nullable StringEntry infix;

		/** The funcIds whose report names an operator, and the operator each names. */
		private final SortedMap<Integer, String> namedFuncIds;

		/**
		 * The operators a report names with no callee behind them (the eval runtime's own
		 * count checks), by the id past every funcId above that each was given. Only
		 * {@code _arity_opening} reads them: {@link #names} stays false for these ids, so
		 * no dispatcher names a callee by one.
		 */
		private final SortedMap<Integer, String> unbackedOperators;

		/** {@code _arity_opening}'s module index, or -1 when the module has none. */
		private final int openingIndex;

		ArityReport(WasmLispCompiler.StringTable stringTable, int layoutAddress, int instanceTypeIndex,
				int slotCapacity, int formatControlSlot, boolean identityHash, SortedMap<Integer, String> namedFuncIds,
				SortedMap<Integer, String> unbackedOperators, int openingIndex) {
			this.stringTable = stringTable;
			this.layoutAddress = layoutAddress;
			this.instanceTypeIndex = instanceTypeIndex;
			this.slotCapacity = slotCapacity;
			this.formatControlSlot = formatControlSlot;
			this.identityHash = identityHash;
			this.namedFuncIds = openingIndex >= 0 ? namedFuncIds : new TreeMap<>();
			this.unbackedOperators = openingIndex >= 0 ? unbackedOperators : new TreeMap<>();
			this.openingIndex = openingIndex;
		}

		/** Whether a shape may carry a funcId the report names an operator for. */
		boolean namesOperators() {
			return !this.namedFuncIds.isEmpty() || !this.unbackedOperators.isEmpty();
		}

		/** Whether the report names the operator of this callee. */
		boolean names(int funcId) {
			return this.namedFuncIds.containsKey(funcId);
		}

		private void intern() {
			if (this.prefix != null) {
				return;
			}
			this.prefix = quoted(ClosRegistry.ARITY_MESSAGE_PREFIX);
			this.atLeast = quoted(ClosRegistry.ARITY_AT_LEAST);
			this.argument = quoted(ClosRegistry.ARITY_ARGUMENT);
			this.plural = quoted(ClosRegistry.ARITY_PLURAL);
			this.infix = quoted(ClosRegistry.ARITY_MESSAGE_INFIX);
		}

		private WasmLispCompiler.StringTable.StringEntry quoted(String text) {
			return this.stringTable.addBodyString("\"" + text + "\"");
		}

		/** An operator's name as a string piece, interned on first use. */
		private WasmLispCompiler.StringTable.StringEntry operatorPiece(String operator) {
			return quoted(operator);
		}

	}

	/**
	 * A seeded condition class a dispatcher can construct: its baked layout and slot
	 * shape, with the message in {@code format-control} and every other slot nil.
	 *
	 * @param layoutAddress the baked layout record
	 * @param instanceTypeIndex the module's instance struct type
	 * @param slotCapacity the layout's reserved slot count
	 * @param formatControlSlot the index of the class's {@code format-control} slot
	 * @param identityHash whether the module's instances and conses carry the
	 * identity-hash slot
	 */
	record ConditionInstance(int layoutAddress, int instanceTypeIndex, int slotCapacity, int formatControlSlot,
			boolean identityHash) {
	}

	/**
	 * What a dispatcher throws for a value that names no function, in EH mode: the
	 * interpreter's text -- {@code The function NAME is undefined} for a symbol, NIL
	 * included, and {@link ClosRegistry#NOT_A_FUNCTION_MESSAGE_PREFIX} plus the value
	 * printed for anything else.
	 *
	 * <p>
	 * The class rides on a layout the module already has. A program that NAMES
	 * {@code type-error} (or {@code undefined-function}) has that layout baked, and so
	 * does one with a handler landing pad ({@code type-error}, for the operand landings,
	 * {@code WasmOperandTypes}); it gets the typed instance a clause can match. Any other
	 * program gets the message-only payload and bakes nothing new. The pieces are
	 * interned on first use, as {@link ArityReport}'s are.
	 */
	static final class NotFunctionReport {

		private final WasmLispCompiler.StringTable stringTable;

		private final @Nullable ConditionInstance typeError;

		private final @Nullable ConditionInstance undefinedFunction;

		private final boolean identityHash;

		private WasmLispCompiler.StringTable.@Nullable StringEntry notFunctionPrefix;

		private WasmLispCompiler.StringTable.@Nullable StringEntry undefinedPrefix;

		private WasmLispCompiler.StringTable.@Nullable StringEntry undefinedSuffix;

		NotFunctionReport(WasmLispCompiler.StringTable stringTable, @Nullable ConditionInstance typeError,
				@Nullable ConditionInstance undefinedFunction, boolean identityHash) {
			this.stringTable = stringTable;
			this.typeError = typeError;
			this.undefinedFunction = undefinedFunction;
			this.identityHash = identityHash;
		}

		private void intern() {
			if (this.notFunctionPrefix == null) {
				this.notFunctionPrefix = quoted(ClosRegistry.NOT_A_FUNCTION_MESSAGE_PREFIX);
				this.undefinedPrefix = quoted(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX);
				this.undefinedSuffix = quoted(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX);
			}
		}

		/**
		 * The {@code $undefined} arm, reached with local 0 holding a {@code TYPE_STRING}
		 * or nil. A quote-framed string shares the struct with a symbol and missed the
		 * registry the same way, so it branches on to the {@code $notFunction} arm that
		 * directly follows this one. A symbol reports its name as the interpreter's
		 * {@code symbol.name()} spells it: the {@code princ} text, except that a keyword
		 * keeps its colon, which only {@code prin1} writes.
		 * @param w the writer
		 * @param msgLocal a spare {@code (ref null eq)} local
		 * @param byteLocal a spare {@code i32} local
		 */
		void emitUndefinedThrow(WasmWriter w, int msgLocal, int byteLocal) {
			intern();
			// the name's first byte, 0 for nil
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.IF, Type.I32.code());
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.ELSE);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			WasmEmitHelper.emitStrBytesArray(w);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
			w.write(Instruction.END);
			w.write(Instruction.TEE_LOCAL);
			w.writeUnsignedLeb128(byteLocal);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128('"');
			w.write(Instruction.I32_EQ);
			w.write(Instruction.BR_IF);
			w.writeUnsignedLeb128(0); // $notFunction
			emitStrConst(w, Objects.requireNonNull(this.undefinedPrefix));
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(byteLocal);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(':');
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF);
			w.writeRefType(true, Type.EQ.code());
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
			w.write(Instruction.ELSE);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINC_TO_STR);
			w.write(Instruction.END);
			emitConcat(w);
			emitStrConst(w, Objects.requireNonNull(this.undefinedSuffix));
			emitConcat(w);
			emitThrow(w, this.undefinedFunction, msgLocal, this.identityHash);
		}

		/**
		 * The {@code $notFunction} arm: the value in local 0 printed after
		 * {@link ClosRegistry#NOT_A_FUNCTION_MESSAGE_PREFIX}.
		 * @param w the writer
		 * @param msgLocal a spare {@code (ref null eq)} local
		 */
		void emitNotFunctionThrow(WasmWriter w, int msgLocal) {
			intern();
			emitStrConst(w, Objects.requireNonNull(this.notFunctionPrefix));
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
			emitConcat(w);
			emitThrow(w, this.typeError, msgLocal, this.identityHash);
		}

		/**
		 * Throws the message on the stack: as the class's instance when the module baked
		 * its layout, as a message-only payload otherwise. Overwrites local 0, whose
		 * value is dead once its text is built.
		 */
		private static void emitThrow(WasmWriter w, @Nullable ConditionInstance instance, int msgLocal,
				boolean identityHash) {
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(msgLocal);
			if (instance != null) {
				emitConditionThrow(w, instance, 0, msgLocal);
				return;
			}
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(msgLocal);
			WasmEmitHelper.emitNewCons(w, identityHash);
			w.write(Instruction.THROW);
			w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		}

		private WasmLispCompiler.StringTable.StringEntry quoted(String text) {
			return this.stringTable.addBodyString("\"" + text + "\"");
		}

	}

	/**
	 * The dispatchable callables this arity CANNOT serve, as {@code funcId -> shape}
	 * (required count doubled, plus one for a {@code &rest} tail). Their funcId reaching
	 * the dispatcher is a call with the wrong number of arguments.
	 */
	private static SortedMap<Integer, Integer> missShapes(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, @Nullable Set<Integer> dispatchable) {
		SortedMap<Integer, Integer> misses = new TreeMap<>();
		for (int i = 0; i < defuns.size(); i++) {
			WasmLispCompiler.DefunDecl defun = defuns.get(i);
			if (dispatchable != null && !dispatchable.contains(i)) {
				continue;
			}
			int params = defun.paramNames().size();
			int required = defun.variadic() ? params - 1 : params;
			if (!(defun.variadic() ? arity >= required : required == arity)) {
				misses.put(i, required * 2 + (defun.variadic() ? 1 : 0));
			}
		}
		for (WasmLispCompiler.LambdaInfo lambda : lambdaDecls) {
			if (dispatchable != null && !dispatchable.contains(lambda.funcId())) {
				continue;
			}
			int params = lambda.paramNames().size();
			int required = lambda.variadic() ? params - 1 : params;
			if (!(lambda.variadic() ? arity >= required : required == arity)) {
				misses.put(lambda.funcId(), required * 2 + (lambda.variadic() ? 1 : 0));
			}
		}
		return misses;
	}

	/**
	 * {@code _arity_chk(argList, shape) -> i32}: throw unless the list is a count the
	 * callee shape can take. The two sites that call it -- a SPREAD dispatcher case and
	 * the physical direct call a literal {@code (apply #'f list)} compiles to -- both
	 * hold the shape as a compile-time constant and the count only as the LENGTH of a
	 * list, which is why neither is a dispatch miss the {@code br_table}'s report arms
	 * could catch, and why one shared function serves both. Seven bytes at a call site
	 * instead of the ~100 an inline throw would cost, over a spread dispatcher that is
	 * one case per callable in the program.
	 *
	 * <p>
	 * The walk stops at the first non-cons, so an IMPROPER tail ends the count instead of
	 * trapping on the {@code ref.cast}: {@code (apply #'f '(1 . 2))} is undefined in CL
	 * and answered {@code (f 1)} before this guard existed, and a guard is no place to
	 * start trapping on it. It reuses the {@code ((ref null eq), i32) -> i32} signature
	 * ({@code TYPE_STR_TO_MEM}), so no module gains a type entry; the result is always 0
	 * and every call site drops it.
	 * @param report the message pieces and the {@code program-error} layout
	 * @return the function body
	 */
	static byte[] buildArityChkBody(ArityReport report) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Params: 0 = argList, 1 = shape. Locals: 2 = got (i32), 3 = cursor, 4 = slots,
		// 5 = msg.
		int got = 2, cursor = 3, slots = 4, msg = 5;
		w.write(2); // 2 local groups
		w.write(1);
		w.write(Type.I32);
		w.write(3);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(got);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(cursor);
		w.write(Instruction.BLOCK, 0x40); // $counted
		w.write(Instruction.LOOP, 0x40); // $walk
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(cursor);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1); // $counted
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(got);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(got);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(cursor);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1); // cdr
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(cursor);
		w.write(Instruction.BR, 0); // $walk
		w.write(Instruction.END); // $walk
		w.write(Instruction.END); // $counted
		// The count fits when it is the required one, or larger with a &rest tail to
		// take the surplus.
		emitArityFits(w, got, report.namesOperators());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitArityThrow(w, report, 1, report.namesOperators(), slots, msg, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(got);
		});
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * The body a module that reserved {@code _arity_chk}'s slot emits when it turns out
	 * to have no {@code program-error} representation to throw: answer 0 and check
	 * nothing. The slot is decided in the pre-pass (it shifts every user function index),
	 * the representation only once the class layouts are baked, so the two can disagree;
	 * a stub keeps the index space honest without inventing a throw the module cannot
	 * catch.
	 * @return the function body
	 */
	static byte[] buildArityChkStubBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // 0 locals
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Pushes 1 when {@code gotLocal} is a count the shape in local 1 can take:
	 * {@code got == required}, or {@code got > required} with a {@code &rest} tail.
	 * {@code named}: the shape may carry a funcId ({@link #arityShape}).
	 */
	private static void emitArityFits(WasmWriter w, int gotLocal, boolean named) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(gotLocal);
		emitShapeRequired(w, 1, named);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(gotLocal);
		emitShapeRequired(w, 1, named);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
	}

	/**
	 * The wrong-argument-count landing: assemble the message from the report's pieces
	 * (the callee shape is in {@code shapeLocal}, the count pushed by {@code pushGot} --
	 * a constant for a per-arity dispatcher, a local for the shared count guard, whose
	 * count is the LENGTH of an argument list), build the {@code program-error} instance
	 * the way {@code %obj-new} does, and throw the {@code (instance . message)} payload
	 * on {@code $lisp-cond} -- the channel {@code %error-cond} uses, so a
	 * {@code program-error} clause matches and the entry landing pad reports it.
	 * {@code named}: the shape may carry a funcId, and the message opens through
	 * {@code _arity_opening}; otherwise the assembly is the one a module that named
	 * nothing emitted, byte for byte.
	 */
	private static void emitArityThrow(WasmWriter w, ArityReport report, int shapeLocal, boolean named, int slotsLocal,
			int msgLocal, Runnable pushGot) {
		report.intern();
		if (named) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(shapeLocal);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(report.openingIndex);
		}
		else {
			emitStrConst(w, Objects.requireNonNull(report.prefix));
		}
		// a &rest tail makes the count a lower bound
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(shapeLocal);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		// blocktype (eqref) -> eqref: the message so far passes through the arm that
		// does not append. TYPE_CALLABLE_BASE + n takes n + 1 values, so + 0 is the one.
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CALLABLE_BASE);
		emitStrConst(w, Objects.requireNonNull(report.atLeast));
		emitConcat(w);
		w.write(Instruction.END);
		emitShapeRequired(w, shapeLocal, named);
		emitDecimal(w);
		emitConcat(w);
		emitStrConst(w, Objects.requireNonNull(report.argument));
		emitConcat(w);
		emitShapeRequired(w, shapeLocal, named);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF);
		// blocktype (eqref) -> eqref: the message so far passes through the arm that
		// does not append. TYPE_CALLABLE_BASE + n takes n + 1 values, so + 0 is the one.
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CALLABLE_BASE);
		emitStrConst(w, Objects.requireNonNull(report.plural));
		emitConcat(w);
		w.write(Instruction.END);
		emitStrConst(w, Objects.requireNonNull(report.infix));
		emitConcat(w);
		pushGot.run();
		emitDecimal(w);
		emitConcat(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(msgLocal);
		emitConditionThrow(w, new ConditionInstance(report.layoutAddress, report.instanceTypeIndex, report.slotCapacity,
				report.formatControlSlot, report.identityHash), slotsLocal, msgLocal);
	}

	/**
	 * Builds the condition instance the way {@code %obj-new} does -- every slot nil but
	 * {@code format-control}, which holds the message in {@code msgLocal} as its text
	 * control ({@link #emitTildeCall}, what {@code %text-control} compiles to) -- and
	 * throws the {@code (instance . message)} payload on {@code $lisp-cond}, the channel
	 * {@code %error-cond} uses, so a clause naming the class matches and the entry
	 * landing pad reports it. {@code slotsLocal} is a spare {@code (ref null eq)}.
	 */
	private static void emitConditionThrow(WasmWriter w, ConditionInstance instance, int slotsLocal, int msgLocal) {
		emitConditionThrow(w, instance, slotsLocal, msgLocal, Map.of());
	}

	/**
	 * {@link #emitConditionThrow(WasmWriter, ConditionInstance, int, int)} with more
	 * slots filled: each entry's emitter pushes that slot's value.
	 * @param w the writer
	 * @param instance the condition class's shape
	 * @param slotsLocal a spare {@code (ref null eq)} local
	 * @param msgLocal the local holding the message
	 * @param slots slot index to the emission pushing its value
	 */
	static void emitConditionThrow(WasmWriter w, ConditionInstance instance, int slotsLocal, int msgLocal,
			Map<Integer, Runnable> slots) {
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(instance.slotCapacity());
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slotsLocal);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slotsLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(instance.formatControlSlot());
		// the message as its text control: the report renders the slot as a control
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(msgLocal);
		emitTildeCall(w, false);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		for (Map.Entry<Integer, Runnable> slot : new TreeMap<>(slots).entrySet()) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(slotsLocal);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(slot.getKey());
			slot.getValue().run();
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		}
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(instance.layoutAddress());
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slotsLocal);
		WasmEmitHelper.emitNewInstance(w, instance.instanceTypeIndex(), instance.identityHash());
		// the payload: (condition-instance . message)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(msgLocal);
		WasmEmitHelper.emitNewCons(w, instance.identityHash());
		w.write(Instruction.THROW);
		w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
	}

	/**
	 * Calls {@code _tilde} ({@code FUNC_TILDE}) on the string on the stack: its text
	 * control ({@code %text-control}), or with {@code undo} the text of a control whose
	 * only directive is {@code ~~} ({@code %control-text}).
	 * @param w the writer
	 * @param undo whether to undouble rather than double
	 */
	static void emitTildeCall(WasmWriter w, boolean undo) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(undo ? 1 : 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_TILDE);
	}

	/**
	 * Pushes the required count out of a callee shape; {@code named}: the shape may carry
	 * a funcId above {@link #ARITY_FUNC_ID_SHIFT}, masked off.
	 */
	private static void emitShapeRequired(WasmWriter w, int shapeLocal, boolean named) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(shapeLocal);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SHR_U);
		if (named) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128((1 << (ARITY_FUNC_ID_SHIFT - 1)) - 1);
			w.write(Instruction.I32_AND);
		}
	}

	/**
	 * {@code _arity_opening(shape, _) -> string}: the opening of a wrong-count message
	 * for the callee shape -- {@code "NAME expects "} when the shape carries the funcId
	 * of a callee the report names ({@link #arityShape}), {@code "Function expects "} for
	 * any other. The funcId is selected over exactly as a dispatcher selects its cases
	 * ({@link #emitCaseSelector}: a plain or biased {@code br_table}, or a comparison
	 * chain when the named ids are sparse), one case per named operator:
	 *
	 * <pre>
	 * block (result eqref) $done
	 *   block $function
	 *     block $op_0 ... block $op_{n-1}
	 *       (shape >>> 16) - 1  select
	 *     end $op_{n-1}: "NAME" br $done
	 *     ...
	 *   end $function: "Function"
	 * end
	 * " expects " concat
	 * </pre>
	 *
	 * A shape carrying no funcId selects {@code -1}, which no case claims. The second
	 * parameter is the selector's scratch local: the function reuses
	 * {@code TYPE_RAT_NEW}'s {@code (i32, i32) -> (ref null eq)} signature, so no module
	 * gains a type entry, and its callers pass 0.
	 * @param report the report whose named funcIds and pieces the body reads
	 * @return the function body
	 */
	static byte[] buildArityOpeningBody(ArityReport report) {
		report.intern();
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // 0 locals
		SortedMap<Integer, String> opening = new TreeMap<>(report.namedFuncIds);
		opening.putAll(report.unbackedOperators);
		List<Map.Entry<Integer, String>> ops = new ArrayList<>(opening.entrySet());
		int n = ops.size();
		int funcIdLocal = 1;
		w.write(Instruction.BLOCK);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.BLOCK, 0x40); // $function
		for (int j = 0; j < n; j++) {
			w.write(Instruction.BLOCK, 0x40);
		}
		if (n > 0) {
			Map<Integer, Integer> funcIdToCase = new HashMap<>();
			for (int j = 0; j < n; j++) {
				funcIdToCase.put(ops.get(j).getKey(), j);
			}
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(ARITY_FUNC_ID_SHIFT);
			w.write(Instruction.I32_SHR_U);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_SUB);
			w.write(Instruction.TEE_LOCAL);
			w.writeUnsignedLeb128(funcIdLocal);
			emitCaseSelector(w, funcIdToCase, ops.get(n - 1).getKey(), n, funcIdLocal);
		}
		else {
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(0); // $function
		}
		for (int k = 0; k < n; k++) {
			w.write(Instruction.END); // $op_{n-1-k}
			emitStrConst(w, report.operatorPiece(ops.get(n - 1 - k).getValue()));
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(n - k); // $done
		}
		w.write(Instruction.END); // $function
		emitStrConst(w, report.quoted(ClosRegistry.ARITY_ANONYMOUS_OPERATOR));
		w.write(Instruction.END); // $done
		// one " expects " for every case, rather than a copy inside each operator's piece
		emitStrConst(w, report.quoted(ClosRegistry.ARITY_VERB));
		emitConcat(w);
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * The body a module that reserved {@code _arity_opening}'s slot emits when it builds
	 * no report after all: nothing calls it, and a null keeps the index space honest.
	 * @return the function body
	 */
	static byte[] buildArityOpeningStubBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // 0 locals
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** Renders the i32 on the stack as its decimal string, through the shared printer. */
	private static void emitDecimal(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
	}

	/** Pushes an interned string as a runtime string value. */
	private static void emitStrConst(WasmWriter w, WasmLispCompiler.StringTable.StringEntry entry) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_BUILD);
	}

	private static void emitConcat(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STRING_CONCAT);
	}

	/** The callables one dispatcher carries a case for, in funcId order. */
	/**
	 * How many 8-bit digits of {@code maxFuncId} a paged dispatcher has to read: the
	 * depth of the radix tree {@link #buildDispatch} builds. One for a program whose
	 * largest funcId fits a single page, four at the ceiling -- a funcId is an
	 * {@code i32}, so it has no fifth digit.
	 *
	 * <p>
	 * Counted from the bit length rather than by shifting {@code maxFuncId} right 8 more
	 * bits per round: Java takes a shift distance mod 32, so the fourth round of such a
	 * loop shifts by 0, reads the id back unshifted and spins forever on any id of
	 * {@code 2^24} or more. An unreachable bound must not be a hang -- one full
	 * {@code ./mvnw test} lost two workers to exactly that (2026-09-11), each burning
	 * 2223 s of CPU inside the count.
	 * @param maxFuncId the largest funcId the dispatcher must select on, never negative
	 * @return the tree depth, in {@code [1, 4]}
	 */
	static int dispatchLevels(int maxFuncId) {
		if (maxFuncId < 0) {
			throw new IllegalStateException("Cannot page a dispatcher on a negative funcId: " + maxFuncId);
		}
		int bits = 32 - Integer.numberOfLeadingZeros(maxFuncId);
		return Math.max(1, (bits + DISPATCH_PAGE_BITS - 1) / DISPATCH_PAGE_BITS);
	}

	private static List<DispatchTarget> dispatchTargets(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, boolean spread, @Nullable Set<Integer> dispatchable,
			int userFuncBase) {
		// A variadic function (physical params = required + rest list) matches every
		// dispatch arity >= required; its case links the surplus args into a cons list
		// before the call. The spread dispatcher takes them all: its cases read the
		// parameters out of the list.
		List<DispatchTarget> targets = new ArrayList<>();
		for (int i = 0; i < defuns.size(); i++) {
			WasmLispCompiler.DefunDecl defun = defuns.get(i);
			int paramCount = defun.paramNames().size();
			if (dispatchable != null && !dispatchable.contains(i)) {
				continue;
			}
			if (spread || (defun.variadic() ? arity >= paramCount - 1 : paramCount == arity)) {
				targets.add(new DispatchTarget(i, userFuncBase + i, defun.variadic() ? paramCount - 1 : paramCount,
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
				targets.add(new DispatchTarget(lambda.funcId(), lambda.funcIndex(),
						lambda.variadic() ? paramCount - 1 : paramCount, lambda.variadic()));
			}
		}
		// One counter hands out every funcId -- the defuns first (index == funcId), then
		// exactly one per lambda declaration -- so a funcId is always in
		// [0, defuns + lambdas). Outside it the compile is already corrupt, and both
		// dispatcher shapes would turn the value into nonsense rather than an error:
		// the flat one writes ONE br_table label per id from 0 up to the largest
		// (a 2^24 id alone is a 16 MB body), and the paged one reads the id one 8-bit
		// digit at a time. Fail here instead, naming the value.
		int funcIdBound = defuns.size() + lambdaDecls.size();
		for (DispatchTarget t : targets) {
			if (t.funcId() < 0 || t.funcId() >= funcIdBound) {
				throw new IllegalStateException("Dispatch target funcId " + t.funcId() + " is outside [0, "
						+ funcIdBound + ") (" + defuns.size() + " defuns, " + lambdaDecls.size() + " lambdas, arity "
						+ arity + (spread ? ", spread" : "") + "): the compile assigned it from no counter");
			}
		}
		return targets;
	}

	/**
	 * The dispatcher preamble, identical for a one-body dispatcher and for the root of a
	 * paged one: the locals, the SYMBOL designator's late resolution, and the interpreted
	 * closure's hand-over to {@code _apply}. Leaves {@code funcId} in the local after the
	 * parameters, and local 0 holding a CLOSURE struct whatever the caller passed.
	 */
	private static void emitDispatchPrologue(WasmWriter w, int arity, int dispatchArgs, boolean spread,
			boolean usesEval, boolean reporting, @Nullable NotFunctionReport notFunction, boolean identityHash) {
		// Locals: param 0 = funcval, params 1..arity = args
		// Extra locals: funcId (i32) and the arg list for the _apply fallback (ref); a
		// reporting dispatcher adds ONE more ref for the message it assembles, so a
		// module that reports nothing declares exactly the locals it always did.
		w.write(2); // 2 local groups
		w.write(1); // 1 local of type i32
		w.write(Type.I32);
		w.write(reporting ? 2 : 1); // locals of type (ref null eq)
		w.writeRefType(true, Type.EQ.code());

		int funcIdLocal = dispatchArgs + 1; // after params
		int argListLocal = dispatchArgs + 2;

		if (notFunction == null) {
			// A SYMBOL funcval (a TYPE_STRING) is a function designator resolved through
			// the eval registry's _lookup by its interned offset -- the interpreter's
			// late binding (cl-postgres passes 'list-row-reader through exec-query).
			// Without the eval runtime _lookup is the always--1 stub, so the miss arm
			// traps exactly where the closure cast used to -- as does anything that is
			// neither, on the cast. Outside EH mode nothing could catch a report, so
			// both stay the traps they always were, byte for byte.
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0); // funcval
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_STRING);
			w.write(Instruction.IF, 0x40);
			emitSymbolLookup(w, funcIdLocal);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.UNREACHABLE); // undefined function
			w.write(Instruction.END);
			emitSymbolClosure(w, funcIdLocal, identityHash);
			w.write(Instruction.ELSE);
			emitClosureFuncId(w, funcIdLocal);
			w.write(Instruction.END);
		}
		else {
			// In EH mode a value that names no function THROWS. A closure is tested FIRST
			// and branches straight to the cast, so a function value pays the two type
			// checks and the one branch the symbol-first order cost it. What is not one
			// branches OUT to the arms emitDispatchEpilogue closes the function with --
			// $undefined for a symbol, NIL included, no function answers, $notFunction
			// for anything else -- so no cold code sits between the prologue and the
			// dispatch. A resolved symbol joins the closure path as the closure it was
			// replaced with.
			w.write(Instruction.BLOCK, 0x40); // $notFunction
			w.write(Instruction.BLOCK, 0x40); // $undefined
			w.write(Instruction.BLOCK, 0x40); // $closure
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
			w.write(Instruction.BR_IF);
			w.writeUnsignedLeb128(0); // $closure
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_STRING);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.BR_IF);
			w.writeUnsignedLeb128(2); // $undefined: NIL is a symbol
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(3); // $notFunction
			w.write(Instruction.END);
			emitSymbolLookup(w, funcIdLocal);
			w.write(Instruction.BR_IF);
			w.writeUnsignedLeb128(1); // $undefined
			emitSymbolClosure(w, funcIdLocal, identityHash);
			w.write(Instruction.END); // $closure
			emitClosureFuncId(w, funcIdLocal);
		}

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
				WasmEmitHelper.emitNewCons(w, identityHash);
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
	}

	/**
	 * Closes a dispatcher the prologue opened in EH mode, ahead of the function's own
	 * {@code end}: the dispatch's value is returned, and the two arms its non-function
	 * branches land in follow it. Nothing outside EH mode.
	 */
	private static void emitDispatchEpilogue(WasmWriter w, int dispatchArgs, @Nullable NotFunctionReport notFunction) {
		if (notFunction == null) {
			return;
		}
		int funcIdLocal = dispatchArgs + 1;
		int argListLocal = dispatchArgs + 2;
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // $undefined
		notFunction.emitUndefinedThrow(w, argListLocal, funcIdLocal);
		w.write(Instruction.END); // $notFunction
		notFunction.emitNotFunctionThrow(w, argListLocal);
	}

	/**
	 * Resolves the SYMBOL in local 0 through {@code _lookup}, leaving the record address
	 * in the funcId local and pushing 1 when no function answers.
	 */
	private static void emitSymbolLookup(WasmWriter w, int funcIdLocal) {
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
	}

	/**
	 * Reads the funcId out of a resolved lookup record and replaces the SYMBOL in local 0
	 * with a synthesized {@code {funcId, null-env}} closure -- exactly the value
	 * {@code #'name} would have produced, since every case body casts the funcval to the
	 * closure struct for its env (the uniform calling convention).
	 */
	private static void emitSymbolClosure(WasmWriter w, int funcIdLocal, boolean identityHash) {
		// funcId = record.funcId (record: {nameOffset, funcId, arity})
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		WasmEmitHelper.emitNewClosure(w, identityHash);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
	}

	/** Extracts the funcId from the closure struct in local 0. */
	private static void emitClosureFuncId(WasmWriter w, int funcIdLocal) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0); // funcval
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.writeUnsignedLeb128(0); // field 0: funcId
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
	}

	/**
	 * The {@code br_table} over {@code targets} and their case bodies, ending the
	 * function. {@code funcIdBias} is subtracted from every funcId first, so a leaf page
	 * tables over its own 256 slots rather than over the program's whole funcId space.
	 */
	private static void emitDispatchCases(WasmWriter w, List<DispatchTarget> targets,
			SortedMap<Integer, Integer> missShapes, int arity, int dispatchArgs, boolean spread, int funcIdBias,
			@Nullable ArityReport report, int arityChkIndex, boolean sharedConsReaders, boolean identityHash) {
		int funcIdLocal = dispatchArgs + 1;
		int argListLocal = dispatchArgs + 2;
		if (targets.isEmpty() && missShapes.isEmpty()) {
			w.write(Instruction.UNREACHABLE);
			return;
		}

		// One report ARM per distinct callee shape, shared by every funcId of that shape:
		// the message depends on the shape and on this dispatcher's arity, not on which
		// function was named.
		List<Integer> armShapes = new ArrayList<>(new java.util.TreeSet<>(missShapes.values()));
		int numCases = targets.size() + armShapes.size();
		int maxFuncId = 0;
		for (DispatchTarget t : targets) {
			maxFuncId = Math.max(maxFuncId, t.funcId() - funcIdBias);
		}
		for (Integer missId : missShapes.keySet()) {
			maxFuncId = Math.max(maxFuncId, missId - funcIdBias);
		}

		// Result block (typed)
		w.write(Instruction.BLOCK);
		w.writeRefType(true, Type.EQ.code());

		// Wrong-argument-count block (void): the report arms branch here with the callee
		// shape in the funcId local, and the assembly below it throws. Absent entirely
		// when nothing reports, so every other module is byte-identical.
		if (!armShapes.isEmpty()) {
			w.write(Instruction.BLOCK, 0x40);
		}

		// Default block (void)
		w.write(Instruction.BLOCK, 0x40);

		// Case blocks (void) - outermost case first
		for (int i = 0; i < numCases; i++) {
			w.write(Instruction.BLOCK, 0x40);
		}

		// Load funcId
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);

		// Build funcId -> br_table depth mapping; the report arms take the slots after
		// the real cases, so a dispatcher that reports nothing numbers exactly as before.
		Map<Integer, Integer> funcIdToCase = new HashMap<>();
		for (int j = 0; j < targets.size(); j++) {
			funcIdToCase.put(targets.get(j).funcId() - funcIdBias, j);
		}
		for (Map.Entry<Integer, Integer> miss : missShapes.entrySet()) {
			funcIdToCase.put(miss.getKey() - funcIdBias, targets.size() + armShapes.indexOf(miss.getValue()));
		}
		emitCaseSelector(w, funcIdToCase, maxFuncId, numCases, funcIdLocal);
		// Whether a report arm hands the assembly the funcId too, for a report that may
		// name a built-in's operator (ArityReport): only where one of this dispatcher's
		// misses is such a callee, so every other dispatcher is byte-identical.
		boolean namedArms = false;
		if (report != null && report.namesOperators() && !missShapes.isEmpty()
				&& missShapes.lastKey() < ARITY_MAX_NAMED_FUNC_ID) {
			for (Integer missId : missShapes.keySet()) {
				namedArms |= report.names(missId);
			}
		}

		// Case bodies: close blocks from innermost to outermost
		for (int k = 0; k < numCases; k++) {
			w.write(Instruction.END); // end of $case_{numCases-1-k}
			int targetIdx = numCases - 1 - k;
			if (targetIdx >= targets.size()) {
				// A report arm: hand the shape to the assembly below and branch out. The
				// funcId local is dead from here (the br_table has already read it), so
				// it carries the shape rather than costing a local of its own -- with the
				// funcId folded in above it when the report may name an operator:
				// (funcId + 1) << 16 | shape, the funcId being this page's bias plus the
				// digit the local holds.
				if (namedArms) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(funcIdLocal);
					w.write(Instruction.I32_CONST);
					w.writeSignedLeb128(ARITY_FUNC_ID_SHIFT);
					w.write(Instruction.I32_SHL);
					w.write(Instruction.I32_CONST);
					w.writeSignedLeb128(
							((funcIdBias + 1) << ARITY_FUNC_ID_SHIFT) + armShapes.get(targetIdx - targets.size()));
					w.write(Instruction.I32_ADD);
				}
				else {
					w.write(Instruction.I32_CONST);
					w.writeSignedLeb128(armShapes.get(targetIdx - targets.size()));
				}
				w.write(Instruction.SET_LOCAL);
				w.writeUnsignedLeb128(funcIdLocal);
				w.write(Instruction.BR);
				w.writeUnsignedLeb128(targetIdx + 1);
				continue;
			}
			DispatchTarget target = targets.get(targetIdx);
			if (spread) {
				// The count guard. A short list would otherwise BIND nil for the
				// parameters it does not reach and a long one would drop its tail,
				// because the walk below is car/cdr and both answer nil past the end --
				// neither of which is a dispatch MISS, so the report arms above never
				// see it (buildArityChkBody).
				if (arityChkIndex >= 0) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(1);
					w.write(Instruction.I32_CONST);
					w.writeSignedLeb128(arityShape(target.required(), target.variadic(),
							Objects.requireNonNull(report).names(target.funcId()) ? target.funcId() : -1));
					w.write(Instruction.CALL);
					w.writeUnsignedLeb128(arityChkIndex);
					w.write(Instruction.DROP);
				}
				// cursor = argList; the required parameters come off the front and a
				// variadic target takes what is left.
				w.write(Instruction.GET_LOCAL);
				w.writeUnsignedLeb128(1);
				w.write(Instruction.SET_LOCAL);
				w.writeUnsignedLeb128(argListLocal);
			}
			else if (target.variadic()) {
				// Link args required+1..arity into a cons list (right to left)
				w.write(Instruction.REF_NULL);
				w.writeHeapType(Type.EQ.code());
				w.write(Instruction.SET_LOCAL);
				w.writeUnsignedLeb128(argListLocal);
				for (int a = arity; a >= target.required() + 1; a--) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(a);
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
					WasmEmitHelper.emitNewCons(w, identityHash);
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
				for (int a = 0; a < target.required(); a++) {
					emitNullSafeCell(w, argListLocal, true, sharedConsReaders);
					emitNullSafeCell(w, argListLocal, false, sharedConsReaders);
					w.write(Instruction.SET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
				}
				if (target.variadic()) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
				}
			}
			else {
				// Push args (for a variadic target, the required ones plus the rest list)
				for (int a = 1; a <= target.required(); a++) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(a);
				}
				if (target.variadic()) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(argListLocal);
				}
				else {
					for (int a = target.required() + 1; a <= arity; a++) {
						w.write(Instruction.GET_LOCAL);
						w.writeUnsignedLeb128(a);
					}
				}
			}
			// Tail-call the target: what it answers is the dispatcher's answer, and the
			// dispatcher's frame is gone while it runs -- a call through a function
			// value costs the caller's frame alone, so a tail call through one
			// (return_call at the call site) runs in constant stack.
			w.write(Instruction.RETURN_CALL);
			w.writeUnsignedLeb128(target.funcIndex());
		}

		// End default block
		w.write(Instruction.END); // $default
		w.write(Instruction.UNREACHABLE);

		if (!armShapes.isEmpty()) {
			w.write(Instruction.END); // $arityerr
			emitArityThrow(w, Objects.requireNonNull(report), funcIdLocal, namedArms, argListLocal, dispatchArgs + 3,
					() -> {
						w.write(Instruction.I32_CONST);
						w.writeSignedLeb128(arity);
					});
		}

		// End result block
		w.write(Instruction.END); // $result
	}

	/**
	 * A LEAF page: the cases of one 256-funcId page, selected by the funcId's lowest
	 * digit. Its funcval parameter is already a closure struct -- the root normalised a
	 * symbol designator into one -- so it only has to read the funcId back out.
	 */
	private static byte[] buildDispatchLeafPage(List<DispatchTarget> targets, SortedMap<Integer, Integer> missShapes,
			int page, int arity, int dispatchArgs, boolean spread, @Nullable ArityReport report, int arityChkIndex,
			boolean sharedConsReaders, boolean identityHash) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		emitPageLocals(w, report != null);
		emitPageFuncIdDigit(w, dispatchArgs, 0);
		emitDispatchCases(w, targets, missShapes, arity, dispatchArgs, spread, page << DISPATCH_PAGE_BITS, report,
				arityChkIndex, sharedConsReaders, identityHash);
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * An INTERNAL page: one digit further up the funcId, each case calling the page below
	 * it with the arguments untouched.
	 */
	private static byte[] buildDispatchNodePage(Map<Integer, Integer> children, int dispatchArgs, int level) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		emitPageLocals(w, false);
		emitPageFuncIdDigit(w, dispatchArgs, level);
		emitPageTable(w, children, dispatchArgs, dispatchArgs + 1);
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/** The two locals every dispatcher node declares, page or root. */
	private static void emitPageLocals(WasmWriter w, boolean reporting) {
		w.write(2); // 2 local groups
		w.write(1); // 1 local of type i32
		w.write(Type.I32);
		w.write(reporting ? 2 : 1); // locals of type (ref null eq)
		w.writeRefType(true, Type.EQ.code());
	}

	/**
	 * Puts the funcId's {@code level}-th 8-bit digit in the funcId local: the page's
	 * caller passed the closure through unchanged, so the digit is read back off it
	 * rather than threaded through a wider signature (which would cost a type entry in
	 * every module).
	 */
	private static void emitPageFuncIdDigit(WasmWriter w, int dispatchArgs, int level) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0); // funcval, already a closure
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.writeUnsignedLeb128(0); // field 0: funcId
		if (level > 0) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(DISPATCH_PAGE_BITS * level);
			w.write(Instruction.I32_SHR_U);
		}
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(DISPATCH_PAGE_SIZE - 1);
		w.write(Instruction.I32_AND);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(dispatchArgs + 1);
	}

	/**
	 * The selector over the case blocks, the funcId already on the stack: a
	 * {@code br_table} over {@code [0, max]} (one label per id, the holes naming the
	 * default), the same table BIASED to the smallest live id (the id less {@code min}
	 * indexes it; a smaller id wraps to a huge unsigned index, which is the default), or
	 * -- when the live ids are sparse enough for it to be under HALF the table -- a
	 * comparison chain, one {@code i64.eq; br_if} per live id and a {@code br} to the
	 * default. Exact byte counts decide, and a tie keeps the plainer shape, so a dense
	 * ladder (every Worker's) is byte-identical to what it was. What it was:
	 * {@code zlib}'s arity-0 ladder tabled 419 labels for one callable at 418, and its
	 * six ladders together carried 3,799 default labels
	 * ({@code .kb/optimize-dead-code-elimination.md}, "Sparse arity ladders").
	 * <p>
	 * The half rule: a chain's bytes are incompressible (a distinct id per case) where a
	 * table's holes are a run of one byte gzip folds to nothing, so a mid-density ladder
	 * pays more compressed than it saves raw ({@code zlib}'s 57-of-558 ladder as a chain:
	 * -106 B raw, +300 B gzip); a truly sparse one wins both ways. And the ids are
	 * compared (and the bias subtracted) as I64 constants: the tree shaker keeps a
	 * string-blob range that any surviving {@code i32.const} lands in, because a
	 * linear-memory address is an indistinguishable {@code i32.const} -- an i64 is never
	 * an address, so a funcId spelled that way cannot pin a string. Spelled as i32, the
	 * first cut kept 770 bytes of {@code zlib}'s blob alive.
	 */
	private static void emitCaseSelector(WasmWriter w, Map<Integer, Integer> funcIdToCase, int maxFuncId, int numCases,
			int funcIdLocal) {
		List<Integer> ids = new ArrayList<>(funcIdToCase.keySet());
		java.util.Collections.sort(ids);
		int minFuncId = ids.get(0);
		int plain = tableBytes(funcIdToCase, 0, maxFuncId, numCases);
		int biased = minFuncId == 0 ? plain
				: 1 + 1 + signedLebBytes(minFuncId) + 1 + 1 + tableBytes(funcIdToCase, minFuncId, maxFuncId, numCases);
		int chain = 1 + unsignedLebBytes(numCases);
		for (int i = 0; i < ids.size(); i++) {
			int id = ids.get(i);
			chain += (i == 0 ? 0 : 1 + unsignedLebBytes(funcIdLocal)) + 1 + 1 + signedLebBytes(id) + 1 + 1
					+ unsignedLebBytes(caseLabel(funcIdToCase, id, numCases));
		}
		if (chain * 2 < biased) {
			for (int i = 0; i < ids.size(); i++) {
				int id = ids.get(i);
				if (i > 0) {
					w.write(Instruction.GET_LOCAL);
					w.writeUnsignedLeb128(funcIdLocal);
				}
				w.write(Instruction.I64_EXTEND_U_I32);
				w.write(Instruction.I64_CONST);
				w.writeSignedLeb128(id);
				w.write(Instruction.I64_EQ);
				w.write(Instruction.BR_IF);
				w.writeUnsignedLeb128(caseLabel(funcIdToCase, id, numCases));
			}
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(numCases); // default
			return;
		}
		int from = 0;
		if (biased < plain) {
			w.write(Instruction.I64_EXTEND_U_I32);
			w.write(Instruction.I64_CONST);
			w.writeSignedLeb128(minFuncId);
			w.write(Instruction.I64_SUB);
			w.write(Instruction.I32_WRAP_I64);
			from = minFuncId;
		}
		w.write(Instruction.BR_TABLE);
		w.writeUnsignedLeb128(maxFuncId - from + 1); // label count
		for (int fid = from; fid <= maxFuncId; fid++) {
			w.writeUnsignedLeb128(caseLabel(funcIdToCase, fid, numCases));
		}
		w.writeUnsignedLeb128(numCases); // default label
	}

	// The br_table's label for an id: its case's depth, or the default's for a hole.
	private static int caseLabel(Map<Integer, Integer> funcIdToCase, int fid, int numCases) {
		Integer caseJ = funcIdToCase.get(fid);
		return caseJ != null ? numCases - 1 - caseJ : numCases;
	}

	private static int tableBytes(Map<Integer, Integer> funcIdToCase, int from, int maxFuncId, int numCases) {
		int bytes = 1 + unsignedLebBytes(maxFuncId - from + 1) + unsignedLebBytes(numCases);
		for (int fid = from; fid <= maxFuncId; fid++) {
			bytes += unsignedLebBytes(caseLabel(funcIdToCase, fid, numCases));
		}
		return bytes;
	}

	private static int unsignedLebBytes(int value) {
		int bytes = 1;
		while ((value >>>= 7) != 0) {
			bytes++;
		}
		return bytes;
	}

	private static int signedLebBytes(int value) {
		int bytes = 1;
		while (value < -64 || value > 63) {
			value >>= 7;
			bytes++;
		}
		return bytes;
	}

	/**
	 * The {@code br_table} of a node over its child pages: each case forwards the
	 * dispatcher's own parameters and returns what the page answered, so a page is
	 * invisible to every caller.
	 */
	private static void emitPageTable(WasmWriter w, Map<Integer, Integer> children, int dispatchArgs, int funcIdLocal) {
		int numCases = children.size();
		int maxDigit = 0;
		for (Integer digit : children.keySet()) {
			maxDigit = Math.max(maxDigit, digit);
		}
		List<Integer> pageIndices = new ArrayList<>(children.values());
		Map<Integer, Integer> digitToCase = new HashMap<>();
		int caseJ = 0;
		for (Integer digit : children.keySet()) {
			digitToCase.put(digit, caseJ++);
		}

		// Default block (void), then one void block per case: a case RETURNS what its
		// page answered, so no result block is needed.
		w.write(Instruction.BLOCK, 0x40);
		for (int i = 0; i < numCases; i++) {
			w.write(Instruction.BLOCK, 0x40);
		}
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(funcIdLocal);
		w.write(Instruction.BR_TABLE);
		w.writeUnsignedLeb128(maxDigit + 1);
		for (int digit = 0; digit <= maxDigit; digit++) {
			Integer c = digitToCase.get(digit);
			w.writeUnsignedLeb128(c != null ? numCases - 1 - c : numCases);
		}
		w.writeUnsignedLeb128(numCases); // default label
		for (int k = 0; k < numCases; k++) {
			w.write(Instruction.END); // end of $case_{numCases-1-k}
			int pageIndex = pageIndices.get(numCases - 1 - k);
			for (int a = 0; a <= dispatchArgs; a++) {
				w.write(Instruction.GET_LOCAL);
				w.writeUnsignedLeb128(a);
			}
			// A page answers for the node: tail-call it, so a paged dispatch is as
			// deep as an unpaged one while the target runs.
			w.write(Instruction.RETURN_CALL);
			w.writeUnsignedLeb128(pageIndex);
		}
		w.write(Instruction.END); // $default
		w.write(Instruction.UNREACHABLE);
	}

	/**
	 * Builds the _read_line helper function body. Reads one line from the given file
	 * descriptor (0 = stdin) using fd_read, byte by byte. Returns a string struct with
	 * '"' prefix/suffix (internal string format), or ref.null eq on EOF.
	 */
	static byte[] buildReadLineBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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

		// A peek on this fd may have parked a whole code point in the one-slot
		// pushback (a fd cannot be un-read): it opens the line, exactly as _read_char
		// drains it (and _read_seq_chars does, .todo/936). The parked code point is
		// UTF-8-encoded into the staging area ahead of the fd bytes; a parked newline
		// ends the (empty) line here instead, the way the loop's own newline break
		// leaves the terminator out of the answer. Local 5 is the string-stream
		// record cursor, reused here as the parked code point: the fd path never
		// touches it.
		w.write(Instruction.BLOCK, 0x40); // block $drained
		// if (memory[PEEK_FD_ADDR] == fd + 1)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PEEK_FD_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		// memory[PEEK_FD_ADDR] = 0: the character is consumed whatever follows
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PEEK_FD_ADDR);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// cp = memory[PEEK_CP_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PEEK_CP_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(5);
		// if (cp == 0x0A): past $drained, where pos == 1 with eof_flag == 0 answers ""
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x0A);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		// if (cp < 0x80): memory[heap_ptr + pos] = cp; pos += 1
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.ELSE);
		// else if (cp < 0x800): the two-byte sequence; pos += 2
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x800);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(6);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0xC0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.ELSE);
		// else if (cp < 0x10000): the three-byte sequence; pos += 3
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x10000);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(12);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0xE0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
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
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.ELSE);
		// else: the four-byte sequence; pos += 4
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(18);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0xF0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
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
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
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
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.END); // end four-byte else
		w.write(Instruction.END); // end three-byte if/else
		w.write(Instruction.END); // end two-byte if/else
		w.write(Instruction.END); // end drain if
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
		w.write(Instruction.END); // end $drained: a drained pushback rejoins here

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
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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
	 * Builds the body of a string-runtime function ({@code _princ_to_str} or
	 * {@code _prin1_to_str}). It renders the argument value between two quote bytes into
	 * the heap by turning on the capture mode of {@code _write_str}, bumps the heap
	 * pointer and returns a new string struct over the captured bytes.
	 * ({@code _string_concat} used to share this shape and render both operands through
	 * the value printer; it is now a byte copy in
	 * {@code WasmStringRuntimeBuilder.buildStringConcatBody}.)
	 * @param renderFunc the rendering function ({@code FUNC_PRINC_VAL} for display text,
	 * {@code FUNC_PRINT_VAL} for the readable form)
	 * @param argCount 1 (to-string)
	 * @return the function body
	 */
	static byte[] buildToStringBody(int renderFunc, int argCount) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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
	 * Builds the {@code _fun_name} body: the closure-value printer both escape modes
	 * call. It binary-searches the compiler-appended {@code {funcId, nameOff, nameLen}}
	 * table (sorted by funcId, one 12-byte row per NAMED defun the gate kept) and writes
	 * the interpreter's tag: {@code "#<function " + NAME + ">"} for an id with a row,
	 * {@code "#<lambda>"} for one without (anonymous lambdas, and the interpreted
	 * sentinel -1). The whole tag lives here rather than in the two printer arms so prin1
	 * and princ answer a function value identically and the printers need no scratch
	 * local for the entry address.
	 * @param st the string table holding the tag pieces
	 * @param tableBase absolute linear address of the table, meaningless when count is 0
	 * @param count the number of rows (0 degenerates the body to the lambda constant)
	 * @return the function body
	 */
	static byte[] buildFunNameBody(WasmLispCompiler.StringTable st, int tableBase, int count) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals: slot 0 = funcId (parameter), slots 1-2 = the scan bounds, slot 3 =
		// the midpoint, slot 4 = the address of the row under test.
		w.write(1);
		w.write(4);
		w.write(Type.I32);
		if (count > 0) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(count - 1);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(2);
			w.write(Instruction.BLOCK);
			w.write(0x40);
			w.write(Instruction.LOOP);
			w.write(0x40);
			// lo > hi -> miss
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(2);
			w.write(Instruction.I32_GT_S);
			w.write(Instruction.BR_IF);
			w.writeUnsignedLeb128(1);
			// mid = (lo + hi) >>> 1; entry = tableBase + mid * 12
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(2);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_SHR_U);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(3);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(tableBase);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(3);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(12);
			w.write(Instruction.I32_MUL);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(4);
			// id < entry.funcId -> hi = mid - 1, continue
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(4);
			w.write(Instruction.I32_LOAD, 0x02, 0);
			w.write(Instruction.I32_LT_S);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(3);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_SUB);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(2);
			w.write(Instruction.ELSE);
			// id > entry.funcId -> lo = mid + 1; otherwise the hit: write the named tag
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(4);
			w.write(Instruction.I32_LOAD, 0x02, 0);
			w.write(Instruction.I32_GT_S);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(3);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.ELSE);
			emitWriteString(w, st.funcPrefix);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(4);
			w.write(Instruction.I32_LOAD, 0x02, 4);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(4);
			w.write(Instruction.I32_LOAD, 0x02, 8);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
			emitWriteString(w, st.hashTableEnd);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
			w.write(Instruction.END);
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.END); // loop
			w.write(Instruction.END); // block
		}
		// Miss -- or no table at all: the value is anonymous.
		emitWriteString(w, st.lambdaStr);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _print_val helper function that prints any Lisp value without a trailing
	 * newline. Handles null (nil), i31ref (integer), string struct, closure struct, and
	 * cons struct (list).
	 */
	static byte[] buildPrintValBody(WasmLispCompiler.StringTable st, boolean simd, int futureTypeIndex,
			int p1StreamTypeIndex, int instanceTypeIndex, int renderPathGlobalIndex, int renderDepthGlobalIndex,
			boolean charvecPossible, boolean identityHash) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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
		WasmEmitHelper.emitCharvecToStrCall(w, charvecPossible);
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

		// Check complex struct -> "#C(re im)"
		emitPrintComplex(w, st, WasmLispCompiler.FUNC_PRINT_VAL);

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

		// Check the transient f32 print box (a packed single-float array element):
		// print at the f32 width so #f(0.1) round-trips.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_F32BOX);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_F32BOX);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32BOX);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_F32_NO_NL);
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
		// A bare symbol name (todo 626): |...|-escape when CLHS 22.1.3.3 says the bare
		// bytes would not read back as themselves, verbatim otherwise -- see
		// buildSymEscGcBody's Javadoc. princ (buildPrincValBody, below) never escapes,
		// so it keeps calling _write_str_gc directly.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		WasmEmitHelper.emitSymEscGcCall(w);
		w.write(Instruction.END);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print its name tag: _fun_name writes
		// "#<function NAME>" for a funcId the table has a row for and "#<lambda>" for
		// one it has not -- the interpreter's LispLambda.print() answer. Both escape
		// modes call the same helper, so prin1 and princ cannot drift on a function
		// value.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.writeUnsignedLeb128(0); // field 0: funcId
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FUN_NAME);
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
		emitPrintInstance(w, st, WasmLispCompiler.FUNC_PRINT_VAL, instanceTypeIndex, renderPathGlobalIndex,
				renderDepthGlobalIndex, 5, 6, 7);
		emitPrintArray(w, st, WasmLispCompiler.FUNC_PRINT_VAL, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, simd,
				renderPathGlobalIndex, renderDepthGlobalIndex, identityHash);

		// Must be cons struct - print as list (the shared cons arm, with the cycle
		// guard and the cdr-chain cycle detection; ref locals 3-4 and i32 locals 5-6
		// are the array printer's, free while the cons arm runs).
		emitPrintConsList(w, st, WasmLispCompiler.FUNC_PRINT_VAL, renderPathGlobalIndex, renderDepthGlobalIndex, 3, 4,
				5, 6);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the princ_val helper function body. Same as print_val but strips quotes from
	 * strings and uses FUNC_PRINC_VAL for recursive cons printing.
	 */
	static byte[] buildPrincValBody(WasmLispCompiler.StringTable st, boolean simd, int futureTypeIndex,
			int p1StreamTypeIndex, int instanceTypeIndex, int renderPathGlobalIndex, int renderDepthGlobalIndex,
			boolean charvecPossible, boolean identityHash) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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
		WasmEmitHelper.emitCharvecToStrCall(w, charvecPossible);
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

		// Check complex struct -> "#C(re im)"
		emitPrintComplex(w, st, WasmLispCompiler.FUNC_PRINC_VAL);

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

		// Check the transient f32 print box (a packed single-float array element):
		// print at the f32 width so #f(0.1) round-trips.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_F32BOX);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_F32BOX);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32BOX);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_F32_NO_NL);
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

		// Check closure struct -> print its name tag: _fun_name writes
		// "#<function NAME>" for a funcId the table has a row for and "#<lambda>" for
		// one it has not -- the interpreter's LispLambda.print() answer. Both escape
		// modes call the same helper, so prin1 and princ cannot drift on a function
		// value.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.writeUnsignedLeb128(0); // field 0: funcId
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FUN_NAME);
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
		emitPrintInstance(w, st, WasmLispCompiler.FUNC_PRINC_VAL, instanceTypeIndex, renderPathGlobalIndex,
				renderDepthGlobalIndex, 6, 7, 8);
		emitPrintArray(w, st, WasmLispCompiler.FUNC_PRINC_VAL, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, simd,
				renderPathGlobalIndex, renderDepthGlobalIndex, identityHash);

		// Must be cons struct - print as list (the shared cons arm; ref locals 4-5 and
		// i32 locals 6-7 are the array printer's, free while the cons arm runs).
		emitPrintConsList(w, st, WasmLispCompiler.FUNC_PRINC_VAL, renderPathGlobalIndex, renderDepthGlobalIndex, 4, 5,
				6, 7);

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

	// A rank-1 bit-stamped array prints #* when every element is 0/1 (.todo/820).
	// Emitted as a BLOCK the general path follows: any check failing branches to
	// its end (falling through to the displacement walk), while success writes
	// "#*" + bits, exits the render guard and returns. The marker is read off
	// param 0's own header (a displaced view carries its offset there, not a
	// type, so it correctly falls through); the elements are read off its data
	// buckets with a zero base for the same reason. idxSlot counts, strideSlot
	// is the still-valid flag (1), mSlot the element value scratch.
	private static void emitPrintBitVectorFastPath(WasmWriter w, WasmLispCompiler.StringTable st, int idxSlot,
			int lenSlot, int rankSlot, int strideSlot, int mSlot, int renderPathGlobalIndex,
			int renderDepthGlobalIndex) {
		int bitMarker = WasmArrayCompiler.elementTypeMarker(am.ik.rontolisp.ArrayElementTypes.BIT);
		w.write(Instruction.BLOCK, 0x40);
		// rank == 1 else fall through.
		getLocal(w, rankSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0);
		// marker == bitMarker else fall through. Marker is header.cdr.car.cdr.cdr,
		// boxed i31; anything else (a displaced offset, a non-array shape the outer
		// branch already excluded) fails the i31 test or the value compare.
		getLocal(w, 0);
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
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
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
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(am.ik.wasm.Type.I31.code());
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		getLocal(w, 0);
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
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
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
		w.writeHeapType(am.ik.wasm.Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(bitMarker);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0);
		// data buckets else fall through (a displaced view's data is its target
		// cell, not buckets).
		pushArrayDataBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		// flag = 1; for (idx = 0; idx < len; idx++) if buckets[idx] is not i31
		// 0/1 { flag = 0; break; }
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		setLocal(w, strideSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, idxSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, idxSlot);
		getLocal(w, lenSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		pushArrayDataBucketsCasted(w);
		getLocal(w, idxSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(am.ik.wasm.Type.I31.code());
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, strideSlot);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		pushArrayDataBucketsCasted(w);
		getLocal(w, idxSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(am.ik.wasm.Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, mSlot);
		getLocal(w, mSlot);
		w.write(Instruction.IF, 0x40);
		getLocal(w, mSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, strideSlot);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, idxSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, strideSlot);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		// write "#*" then each bit.
		writeStr(w, st.bitPrefix);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, idxSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, idxSlot);
		getLocal(w, lenSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		pushArrayDataBucketsCasted(w);
		getLocal(w, idxSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(am.ik.wasm.Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.bitOne);
		w.write(Instruction.ELSE);
		writeStr(w, st.bitZero);
		w.write(Instruction.END);
		getLocal(w, idxSlot);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		setLocal(w, idxSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		emitRenderGuardExit(w, renderPathGlobalIndex, renderDepthGlobalIndex);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	// Pushes param 0's array data (header.cdr.cdr) as an eqref for the bit-vector
	// print fast path above: header then two cdr steps, no casts beyond the cell
	// and cons shapes the outer array branch already verified.
	private static void pushArrayDataBuckets(WasmWriter w) {
		getLocal(w, 0);
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
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
	}

	// Like {@link #pushArrayDataBuckets}, but cast to the buckets array type for
	// an immediately following {@code array.get}: the validator only ever
	// {@code ref.test}s (valid on any reference), while {@code array.get} needs
	// the concrete array type and the validator rejects a bare eqref
	// ("expected (ref null $type), found eqref"). Sound wherever the buckets
	// test just passed: same immutable value, re-read from param 0.
	private static void pushArrayDataBucketsCasted(WasmWriter w) {
		pushArrayDataBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// Emits the TYPE_CELL branch shared by _print_val and _princ_val, i.e. every value
	// carried in the box: if param 0 is a TYPE_CELL whose header car is a
	// TYPE_HASH_BUCKETS array (an array), prints it as #(...) (rank 1), #nA((...) ...)
	// (rank n) or #0A<datum> (rank 0, no parens) and returns; any other cell is a hash
	// table and prints #<HASH-TABLE>.
	// The branch RETURNS for every cell -- nothing may reach the cons tail of the caller,
	// which would re-enter the printer on the same value (see the tail of this method).
	// elementFunc is the per-element printer (FUNC_PRINT_VAL for prin1, FUNC_PRINC_VAL
	// for princ). A nested group paren opens where the flat index is a multiple of that
	// dimension's stride (the product of the trailing dimension sizes) and closes where
	// the next index is. Slots: dims/data = (ref null eq) locals; idx/len/rank/j/stride/m
	// = i32 locals.
	private static void emitPrintArray(WasmWriter w, WasmLispCompiler.StringTable st, int elementFunc, int dimsSlot,
			int dataSlot, int idxSlot, int lenSlot, int rankSlot, int jSlot, int strideSlot, int mSlot, int baseSlot,
			int packedSlot, int singleSlot, boolean simd, int renderPathGlobalIndex, int renderDepthGlobalIndex,
			boolean identityHash) {
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
		// element boxed at its own width: a single-float element goes into the
		// transient TYPE_F32BOX so the generic printer spells it as the shortest f32
		// decimal (#f(0.1) round-trips); a double element keeps the TYPE_FLOAT box.
		getLocal(w, singleSlot);
		w.write(Instruction.IF, 0x40);
		getBucketsLocal(w, dimsSlot);
		getLocal(w, idxSlot);
		if (simd) {
			// _v_get owns the lane branches and widens; demote back (exact for a
			// value that came from an f32 lane)
			farrayDataRaw(w, dataSlot);
			getLocal(w, idxSlot);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_GET);
			w.write(Instruction.F32_DEMOTE_F64);
		}
		else {
			farrayDataF32(w, dataSlot);
			getLocal(w, idxSlot);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32ARR);
		}
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F32BOX);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.ELSE);
		getBucketsLocal(w, dimsSlot);
		getLocal(w, idxSlot);
		if (simd) {
			farrayDataRaw(w, dataSlot);
			getLocal(w, idxSlot);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_GET);
		}
		else {
			farrayData(w, dataSlot);
			getLocal(w, idxSlot);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_F64ARR);
		}
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.END);
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
		WasmEmitHelper.emitNewCons(w, identityHash);
		WasmEmitHelper.emitNewCons(w, identityHash);
		getBucketsLocal(w, dimsSlot);
		WasmEmitHelper.emitNewCons(w, identityHash);
		WasmEmitHelper.emitNewCons(w, identityHash);
		WasmEmitHelper.emitNewCell(w, identityHash);
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
		WasmEmitHelper.emitNewCons(w, identityHash);
		WasmEmitHelper.emitNewCons(w, identityHash);
		getBucketsLocal(w, dimsSlot);
		WasmEmitHelper.emitNewCons(w, identityHash);
		WasmEmitHelper.emitNewCons(w, identityHash);
		WasmEmitHelper.emitNewCell(w, identityHash);
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

		// The cycle guard (the shared discipline, see emitRenderGuardEnter): an array
		// already on the current rendering path -- one holding itself, directly or
		// through a list -- or the frame past the depth cap writes "#" instead of
		// recursing without end. A packed array arrives here converted to a FRESH
		// general cell in param 0, so it opens one frame and never matches the path --
		// the same one frame the interpreter's packed renderers and the JVM's
		// _arrayToString open. idxSlot is free until the render loop below claims it.
		emitRenderGuardEnter(w, st.addString("#"), renderPathGlobalIndex, renderDepthGlobalIndex, idxSlot);

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

		// A rank-1 bit-stamped array prints #* when every element is 0/1, so a
		// printed bit vector reads back as one (.todo/820). make-array never
		// validates stores, so a non-bit element falls back to the general #()
		// vector below. idxSlot/strideSlot/mSlot are free here (the len product
		// loop is done, the element loop has not started); dataSlot still holds
		// the inner cons the walk below expects, so the buckets are re-read from
		// param 0 per element rather than cached.
		emitPrintBitVectorFastPath(w, st, idxSlot, lenSlot, rankSlot, strideSlot, mSlot, renderPathGlobalIndex,
				renderDepthGlobalIndex);

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

		// prefix: a RANK-0 array prints "#0A" and opens no paren at all (the whole
		// rank-0 syntax is #0A<datum>, at every representation -- the packed prefixes do
		// not apply to it); else a packed float array prints "#f(" (single,
		// packedSlot==2) or "#d(" (double, packedSlot==1), and a general one "#(" for
		// rank 1, "#" + rank + "A(" for rank n.
		getLocal(w, rankSlot);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.hashPrefix);
		getLocal(w, rankSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		writeStr(w, st.rankA);
		w.write(Instruction.ELSE);
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
		writeStr(w, st.rankA);
		writeStr(w, st.lparen);
		w.write(Instruction.END);
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

		// element: elementFunc(data[base + idx]) -- unless the chain ended on a PACKED
		// target, whose elements live unboxed in it: that read is the shared _arr_get's
		// (it walks the chain itself, so it takes the ORIGINAL header and index).
		getLocal(w, dataSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getBucketsLocal(w, dataSlot);
		getLocal(w, baseSlot);
		getLocal(w, idxSlot);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.ELSE);
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		getLocal(w, idxSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ARR_GET);
		w.write(Instruction.END);
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

		// A rank-0 array opened no paren, so it closes none.
		getLocal(w, rankSlot);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.rparen);
		w.write(Instruction.END);
		emitRenderGuardExit(w, renderPathGlobalIndex, renderDepthGlobalIndex);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // is-array if

		// The other TYPE_CELL shape is a hash table (header car = the i31 entry count),
		// and it prints as #<HASH-TABLE :TEST EQUAL :COUNT n> -- the interpreter's
		// answer, so all four backends agree. Without this arm the value left the cell
		// branch unhandled and fell into the cons tail below, which prints " . " and
		// re-enters the printer on the SAME value: unbounded recursion, i.e. an
		// unrecoverable "call stack exhausted" trap that also lost the stdout buffered
		// before it. Any other cell (an unexposed internal box) survives here for the
		// same reason rather than trapping: the count is emitted only when the header
		// car really is an i31, and such a box prints the tag with a 0 count instead of
		// trapping on the cast.
		// The tagged count carries the table's two-bit TEST TAG in its low two bits
		// when the module can build a non-equal table (WasmHashTableCompiler); the tag
		// says which test lookup implements, and the count is shifted past it. A module
		// that builds none writes the EQUAL tag and the count as they always were.
		WasmLispCompiler.StringTable.StringEntry equalpStr = st.hashTableEqualpStr;
		WasmLispCompiler.StringTable.StringEntry eqlStr = st.hashTableEqlStr;
		WasmLispCompiler.StringTable.StringEntry eqStr = st.hashTableEqStr;
		if (equalpStr != null && eqStr == null) {
			emitHashTableTag(w);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			writeStr(w, equalpStr);
			w.write(Instruction.ELSE);
			writeStr(w, st.hashTableStr);
			w.write(Instruction.END);
		}
		else if (eqStr != null && eqlStr != null) {
			// An identity-only module interns no fold tag: tag 1 is unreachable there
			// and reads as the plain tag.
			WasmLispCompiler.StringTable.StringEntry equalpOrEqual = (equalpStr != null) ? equalpStr : st.hashTableStr;
			emitHashTableTag(w);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			writeStr(w, equalpOrEqual);
			w.write(Instruction.ELSE);
			emitHashTableTag(w);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			writeStr(w, eqlStr);
			w.write(Instruction.ELSE);
			emitHashTableTag(w);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(3);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			writeStr(w, eqStr);
			w.write(Instruction.ELSE);
			writeStr(w, st.hashTableStr);
			w.write(Instruction.END);
			w.write(Instruction.END);
			w.write(Instruction.END);
		}
		else {
			writeStr(w, st.hashTableStr);
		}
		emitHashTableCount(w);
		if (equalpStr != null || eqStr != null) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_SHR_S);
		}
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		writeStr(w, st.hashTableEnd);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // is-cell if
	}

	// Pushes the cell's header test tag as an i32 -- 0 for a cell that is not a table,
	// so an unexposed internal box takes the EQUAL tag like it takes the zero count
	// below (the arm must answer for EVERY cell; see the comment above).
	private static void emitHashTableTag(WasmWriter w) {
		emitHashTableCount(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_AND);
	}

	// Pushes the cell's header count as an i32 -- 0 for a cell that is not a table, so an
	// unexposed internal box prints the tag with a zero count instead of trapping on the
	// i31 cast (the arm must answer for EVERY cell; see the comment above).
	private static void emitHashTableCount(WasmWriter w) {
		cellHeader(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x7F); // (result i32)
		cellHeader(w);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
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

	// Emits the complex branch shared by _print_val and _princ_val: if the value in
	// param 0 is a complex struct, prints "#C(" + <re> + " " + <im> + ")" and
	// returns. Each part renders through elementFunc, so print and princ spell a
	// part exactly as they would alone (a ratio part prints as "1/2" either way; a
	// string part could never occur -- parts are always real numbers). No cycle
	// guard: parts are never aggregates, let alone cyclic ones.
	private static void emitPrintComplex(WasmWriter w, WasmLispCompiler.StringTable st, int elementFunc) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.IF, 0x40);
		writeStr(w, st.complexPrefix);
		complexField(w, 0, 0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);
		writeStr(w, st.space);
		complexField(w, 0, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(elementFunc);
		writeStr(w, st.rparen);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
	}

	/**
	 * Builds the print_f64 helper function body: the IEEE specials and the sign, then the
	 * Schubfach shortest decimal (_f64_dec) rendered by _write_dec -- the same text
	 * FloatText.doubleText answers on the interpreter and the JVM.
	 */
	static byte[] buildPrintF64Core(boolean appendNewline, WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.writeUnsignedLeb128(0); // no locals

		// NaN prints as text, unsigned (Java's spelling): value != value
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF, 0x40);
		emitWriteEntry(w, st.nanStr);
		if (appendNewline) {
			emitWriteEntry(w, st.newline);
		}
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Sign by the sign BIT, so -0.0 keeps its '-'
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		emitWriteEntry(w, st.minus);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_NEG);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);

		// Infinity, after the sign so -Infinity gets its '-'
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, 0x40);
		emitWriteEntry(w, st.infinityStr);
		if (appendNewline) {
			emitWriteEntry(w, st.newline);
		}
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Zero renders through the formatter (digits 0 -> "0.0")
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_DEC);
		if (appendNewline) {
			emitWriteEntry(w, st.newline);
		}
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Finite positive nonzero: shortest decimal
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_F64_DEC);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_DEC);
		if (appendNewline) {
			emitWriteEntry(w, st.newline);
		}
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _print_f32_no_nl body: prints a single-float (a packed #f array element)
	 * at its f32 width. The IEEE specials and zeros delegate to the f64 printer
	 * (identical text); everything else selects the shortest f32 decimal.
	 */
	static byte[] buildPrintF32Core(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.writeUnsignedLeb128(0); // no locals

		// NaN, +-Infinity and +-0.0 print exactly as the f64 printer prints them
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F32_NE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F32_ABS);
		w.write(Instruction.F32_CONST);
		w.writeF32(Float.POSITIVE_INFINITY);
		w.write(Instruction.F32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F32_CONST);
		w.writeF32(0.0f);
		w.write(Instruction.F32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F64_PROMOTE_F32);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Sign
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_REINTERPRET_F32);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		emitWriteEntry(w, st.minus);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.F32_NEG);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_F32_DEC);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_DEC);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// write_str of one string-table entry
	private static void emitWriteEntry(WasmWriter w, WasmLispCompiler.StringTable.StringEntry entry) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(entry.length());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
	}

}
