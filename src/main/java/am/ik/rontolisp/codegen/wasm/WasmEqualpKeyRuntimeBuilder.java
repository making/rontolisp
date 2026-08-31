package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.rontolisp.LispEquality;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _equalp_key ((ref null eq)) -> (ref null eq)}: the key fold an
 * {@code equalp} hash table places its keys by.
 *
 * <p>
 * {@code equalp} on two values is the structural {@code equal} on their folds, so ONE
 * table carries both tests and the {@code _hash}/{@code _equal} pair is untouched --
 * {@code gethash}/{@code puthash}/{@code remhash} simply run the key through this
 * function first when the table's header says it folds ({@link WasmHashTableCompiler}).
 * The specification is {@code LispEquality.equalpKey}; this reproduces it over the WASM
 * value model:
 *
 * <ul>
 * <li>a quote-framed {@code TYPE_STRING} folds to upper case through
 * {@code _string_upcase} -- an unframed one is a SYMBOL and is its own key;</li>
 * <li>a {@code TYPE_CHAR} folds through {@code _char_upcase}, the same range table the
 * string fold and the interpreter use;</li>
 * <li>a {@code TYPE_FLOAT} whose value is an INTEGER folds to that integer, read out of
 * its bits as {@code mantissa * 2^exponent} and built through {@code _int_new} /
 * {@code _big_ash}, so it is exact at every magnitude. A float with a fraction does NOT
 * fold to the ratio it equals: {@code TYPE_RATIO} holds two i32 components and cannot
 * represent a power-of-two denominator, so folding it here would make this backend
 * disagree with the others rather than agree;</li>
 * <li>a {@code TYPE_CONS} folds element-wise;</li>
 * <li>everything else -- an array included -- is its own key.</li>
 * </ul>
 *
 * <p>
 * The walk is capped at {@link LispEquality#HASH_DEPTH_CAP} levels the way {@code _hash}
 * is, counting the live recursion depth in a {@code (mut i32)} global, so a CYCLIC key
 * folds to a finite structure instead of exhausting the stack; and at
 * {@link LispEquality#HASH_WORK_CAP} node visits across the whole fold, in a second
 * global refilled by the outermost entry, so a key whose substructure is SHARED does not
 * fold to the exponentially many root-to-leaf paths through it -- which this function
 * would not merely walk but ALLOCATE.
 */
final class WasmEqualpKeyRuntimeBuilder {

	// The quote byte that frames a string; without it the TYPE_STRING is a symbol name.
	private static final int QUOTE = 34;

	private WasmEqualpKeyRuntimeBuilder() {
	}

	/**
	 * The identity body a program with no {@code equalp} table carries: nothing calls it,
	 * and the fixed function index stays where every other index expects it.
	 * @return the stub body
	 */
	static byte[] buildStub() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		getLocal(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * The real fold.
	 * @param depthGlobalIndex the {@code (mut i32)} recursion-depth global
	 * @param gasGlobalIndex the {@code (mut i32)} work-budget global
	 * @return the function body
	 */
	static byte[] build(int depthGlobalIndex, int gasGlobalIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 1 = the float's bit pattern, 2 = its mantissa (both i64); 3 = its
		// exponent (i32); 4 = the integer being built out of the two.
		w.write(3); // 3 local groups
		w.write(2);
		w.write(Type.I64);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());

		// The OUTERMOST entry -- the one that finds the depth counter at zero -- refills
		// the work budget, so one fold's gas never depends on the last one's and a key's
		// fold stays a function of that key alone.
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

		// Depth cap AND work budget: at either limit the value is its own fold, and
		// neither a level nor a node is counted so both counters stay exact. Otherwise
		// spend one node, count this level, and restore the DEPTH counter on the way out,
		// under the folded value. The gas is not restored -- it belongs to the whole
		// fold, which is the point: a per-branch count bounds nothing when the branches
		// share their substructure.
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
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		w.write(Instruction.ELSE);
		adjustDepth(w, gasGlobalIndex, Instruction.I32_SUB);
		adjustDepth(w, depthGlobalIndex, Instruction.I32_ADD);

		// A mutable character vector renders to its quote-framed string FIRST, so a
		// producer-built key (concatenate, format nil) takes the framed-string fold
		// below and two equal-content keys collide -- _hash already folds them alike,
		// and without this the vector fell to the "its own key" arm and never matched.
		getLocal(w, 0);
		WasmEmitHelper.emitCharvecToStrCall(w);
		setLocal(w, 0);

		// A framed string -> _string_upcase
		refTest(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		emitFirstByte(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(QUOTE);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_STRING_UPCASE);
		w.write(Instruction.ELSE);
		getLocal(w, 0);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);

		// A character -> _char_upcase, re-boxed
		refTest(w, WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.writeUnsignedLeb128(0);
		call(w, WasmLispCompiler.FUNC_CHAR_UPCASE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.ELSE);

		// A float -> the integer it equals, or itself
		refTest(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		emitFloatFold(w);
		w.write(Instruction.ELSE);

		// A cons -> fold both halves
		refTest(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		consField(w, 0);
		call(w, WasmLispCompiler.FUNC_EQUALP_KEY);
		consField(w, 1);
		call(w, WasmLispCompiler.FUNC_EQUALP_KEY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.ELSE);

		// Everything else is its own key
		getLocal(w, 0);

		w.write(Instruction.END); // end cons if
		w.write(Instruction.END); // end float if
		w.write(Instruction.END); // end char if
		w.write(Instruction.END); // end string if
		adjustDepth(w, depthGlobalIndex, Instruction.I32_SUB);
		w.write(Instruction.END); // end depth-cap if
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	// A finite double is exactly mantissa * 2^exponent. Strip the trailing zero bits the
	// exponent still owes: what is left is an integer exactly when the exponent has
	// reached zero, and then _int_new / _big_ash build it at any magnitude.
	private static void emitFloatFold(WasmWriter w) {
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I64_REINTERPRET_F64);
		setLocal(w, 1);

		// NaN or infinity (biased exponent 0x7ff): no integer value, its own key.
		biasedExponent(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		w.write(Instruction.ELSE);

		// Either zero (the sign bit is all that is set): the integer 0, which is what
		// (= -0.0 0) answers.
		getLocal(w, 1);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I64_SHL);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.ELSE);

		// mantissa = fraction, plus the implicit bit unless the value is subnormal
		getLocal(w, 1);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0x000fffffffffffffL);
		w.write(Instruction.I64_AND);
		setLocal(w, 2);
		biasedExponent(w);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 2);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0x0010000000000000L);
		w.write(Instruction.I64_OR);
		setLocal(w, 2);
		biasedExponent(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1075);
		w.write(Instruction.I32_SUB);
		setLocal(w, 3);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-1074);
		setLocal(w, 3);
		w.write(Instruction.END);

		// A negative exponent is a division by 2^-exponent the mantissa's trailing zero
		// bits may or may not pay for: when they do not, the value has a fraction.
		getLocal(w, 3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 2);
		w.write(Instruction.I64_CTZ);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		getLocal(w, 3);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		w.write(Instruction.ELSE);
		getLocal(w, 2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		getLocal(w, 3);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I64_EXTEND_S_I32);
		w.write(Instruction.I64_SHR_U);
		setLocal(w, 2);
		emitIntegerOf(w, false);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		emitIntegerOf(w, true);
		w.write(Instruction.END);

		w.write(Instruction.END); // end zero if
		w.write(Instruction.END); // end non-finite if
	}

	// Builds the exact integer for the mantissa in local 2, signed by the bit pattern in
	// local 1 and (when the exponent in local 3 has not already been brought down to
	// zero) multiplied by 2^exponent -- through _big_ash, so the result is exact at any
	// magnitude rather than only up to the i64 tier.
	private static void emitIntegerOf(WasmWriter w, boolean shiftLeft) {
		getLocal(w, 1);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		getLocal(w, 2);
		w.write(Instruction.I64_SUB);
		setLocal(w, 2);
		w.write(Instruction.END);
		getLocal(w, 2);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		setLocal(w, 4);
		if (shiftLeft) {
			getLocal(w, 3);
			w.write(Instruction.IF, 0x40);
			getLocal(w, 4);
			getLocal(w, 3);
			w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			call(w, WasmLispCompiler.FUNC_BIG_ASH);
			setLocal(w, 4);
			w.write(Instruction.END);
		}
		getLocal(w, 4);
	}

	// Pushes the float's biased exponent field ((bits >> 52) & 0x7ff) as an i32.
	private static void biasedExponent(WasmWriter w) {
		getLocal(w, 1);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(52);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I32_AND);
	}

	// Pushes the first content byte of the TYPE_STRING in local 0.
	private static void emitFirstByte(WasmWriter w) {
		getLocal(w, 0);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
	}

	// Pushes car (field 0) or cdr (field 1) of the cons in local 0.
	private static void consField(WasmWriter w, int field) {
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
	}

	private static void refTest(WasmWriter w, int typeIndex) {
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(typeIndex);
	}

	private static void adjustDepth(WasmWriter w, int depthGlobalIndex, int op) {
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(op);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(depthGlobalIndex);
	}

	private static void call(WasmWriter w, int funcIndex) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(funcIndex);
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

}
