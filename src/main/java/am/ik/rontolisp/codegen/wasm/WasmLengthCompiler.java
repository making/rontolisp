package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Compiles the {@code length} built-in. A string returns its character count via
 * {@code _str_char_count} (a UTF-8 walk of the byte data between the two surrounding
 * quotes, counting one per lead byte); a vector (rank-1 array) returns its element count;
 * any other argument is treated as a list and its cons cells are counted (Common Lisp
 * sequences). A symbol (which shares the string struct representation but lacks the
 * leading quote) is not a sequence and yields zero, matching the interpreter; a hash
 * table likewise yields zero. A rank-2 array is not a sequence and traps. An array and a
 * hash table are both {@code TYPE_CELL} boxes; the header's car distinguishes them (a
 * bucket array for an array, an i31 count for a hash table). A packed float vector
 * ({@code TYPE_FARRAY}) is handled first: a rank-1 one yields its {@code dims[0]}, a
 * higher-rank one traps like a general rank-n array.
 *
 * <p>
 * The dispatch is ONE shared function, {@code _seq_len}
 * ({@link WasmLispCompiler#FUNC_SEQ_LEN}, built by {@link #buildSeqLenBody}), rather than
 * the ~300-byte ladder every site used to inline -- the zlib module carried 66 copies,
 * 13.6% of its bytes ({@code .kb/wasm-shared-coercion.md} is the precedent, and the JVM
 * backend's {@code _length} was already out of line). A site whose argument's
 * representation {@code DeclaredArrayTypes} already pins to a packed integer vector keeps
 * its direct {@code array.len} and never calls.
 */
final class WasmLengthCompiler {

	private WasmLengthCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// A declared packed integer vector's length is its array.len, with the same
		// trapping ref.cast as the declared accessors (.kb/declarations-type-checks.md)
		// -- rank-1 by construction, no fill pointer possible, so no dispatch is needed.
		am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind kind = WasmArrayCompiler.arrayKindOfExpr(args.get(1), ctx);
		if (kind != null && kind.packedIntWidth() != 0) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmArrayCompiler.intArrType(kind.packedIntWidth()));
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_SEQ_LEN);
	}

	/**
	 * Builds {@code _seq_len} ({@link WasmLispCompiler#FUNC_SEQ_LEN}): the generic
	 * sequence-length dispatch over its single parameter, answering an i31-boxed count.
	 * @return the function body (signature {@code ((ref null eq)) -> (ref null eq)},
	 * {@code TYPE_CALLABLE_BASE + 0})
	 */
	static byte[] buildSeqLenBody() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// Two scratch (ref null eq) locals: slot 1 holds the dims array of whichever
		// array branch runs, slot 2 the fill pointer / the list-walk counter. The
		// parameter (slot 0) is the value, reused as the list-walk cursor.
		w.write(1);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		int valSlot = 0;
		int dimsSlot = 1;
		int fpSlot = 2;

		// A packed float array (TYPE_FARRAY): a rank-1 vector's length is dims[0]; a
		// higher-rank array is not a sequence and traps (like a general rank-n array).
		// Falls through to the general string/array/list logic otherwise.
		get(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		// dimsSlot = farray.dims (field 0, held as eq; cast to buckets on each use)
		get(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(dimsSlot);
		// rank != 1 -> trap
		get(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		// dims[0] (already an i31)
		get(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.ELSE);

		// A packed integer vector: rank-1 by construction, so its length is array.len.
		WasmArrayCompiler.testIntVector(w, valSlot);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		WasmArrayCompiler.emitPackedIntLen(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.ELSE);

		// if (val is a TYPE_STRING struct)
		get(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());

		// A string struct or a symbol struct -- distinguish by the leading '"' byte.
		get(w, valSlot);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(34); // '"'
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		// String: character count via a UTF-8 walk (byte data between the two quotes
		// carries the string's content as its UTF-8 encoding, so the byte length and
		// character count can diverge on non-ASCII input).
		get(w, valSlot);
		WasmEmitHelper.emitStrCharCountCall(w);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.ELSE);
		// Symbol: not a sequence -> 0.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);

		w.write(Instruction.ELSE);
		// An array and a hash table are both TYPE_CELL boxes holding a header cons. They
		// are told apart by the header's car: an array's car is the dims bucket array, a
		// hash table's car is an i31 count.
		get(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		// dims = car(header) where header = cell.field0
		get(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0); // header cons
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0); // car
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(dimsSlot);
		// if (car is a bucket array) it is an array
		get(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		// array: a vector (rank 1) has dims length 1; otherwise it is not a sequence.
		get(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		// rank 1: the fill pointer when the meta cons carries one (the effective
		// length), else dims[0] (both already i31s). meta = (cadr header), see
		// WasmArrayCompiler.
		get(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0); // header cons
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1); // (meta . data)
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0); // meta
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0); // fill pointer or null
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(fpSlot);
		get(w, fpSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		get(w, fpSlot);
		w.write(Instruction.ELSE);
		get(w, dimsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// rank 2+: not a sequence.
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// hash table: not a sequence -> 0 (matching the interpreter/JVM list
		// fallthrough).
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// List case: count cons cells until the value is no longer a cons. The counter
		// reuses fpSlot; the cursor is the parameter itself.
		int countSlot = fpSlot;
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(countSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1); // break to $exit
		// count = count + 1
		get(w, countSlot);
		WasmEmitHelper.castI31GetS(w);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(countSlot);
		// val = cdr(val)
		get(w, valSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1); // cdr
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(valSlot);
		w.write(Instruction.BR, 0); // continue loop
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		get(w, countSlot);

		w.write(Instruction.END); // array/hash-table-vs-list if
		w.write(Instruction.END); // outer if (string)
		w.write(Instruction.END); // packed integer vector if
		w.write(Instruction.END); // outermost if (packed farray)
		w.write(Instruction.END); // function body
		return out.toByteArray();
	}

	private static void get(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

}
