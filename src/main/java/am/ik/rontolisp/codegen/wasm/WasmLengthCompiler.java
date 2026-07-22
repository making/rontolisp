package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

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
 */
final class WasmLengthCompiler {

	private WasmLengthCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);

		// A packed float array (TYPE_FARRAY): a rank-1 vector's length is dims[0]; a
		// higher-rank array is not a sequence and traps (like a general rank-n array).
		// Falls through to the general string/array/list logic otherwise.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		int fdimsSlot = ctx.allocTemp();
		// fdimsSlot = farray.dims (field 0, held as eq; cast to buckets on each use)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(fdimsSlot);
		// rank != 1 -> trap
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(fdimsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_NE);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// dims[0] (already an i31)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(fdimsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.ELSE);

		// if (val is a TYPE_STRING struct)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());

		// A string struct or a symbol struct -- distinguish by the leading '"' byte.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		WasmEmitHelper.emitStrBytesArray(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(34); // '"'
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// String: character count via a UTF-8 walk (byte data between the two quotes
		// carries the string's content as its UTF-8 encoding, so the byte length and
		// character count can diverge on non-ASCII input).
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		WasmEmitHelper.emitStrCharCountCall(ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		// Symbol: not a sequence -> 0.
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.END);

		ctx.writer.write(Instruction.ELSE);
		// An array and a hash table are both TYPE_CELL boxes holding a header cons. They
		// are
		// told apart by the header's car: an array's car is the dims bucket array, a hash
		// table's car is an i31 count.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// dims = car(header) where header = cell.field0
		int dimsSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0); // header cons
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // car
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(dimsSlot);
		// if (car is a bucket array) it is an array
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(dimsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// array: a vector (rank 1) has dims length 1; otherwise it is not a sequence.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(dimsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// rank 1: the fill pointer when the meta cons carries one (the effective
		// length), else dims[0] (both already i31s). meta = (cadr header), see
		// WasmArrayCompiler.
		int fpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0); // header cons
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1); // (meta . data)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // meta
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // fill pointer or null
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(fpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(fpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(fpSlot);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(dimsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// rank 2+: not a sequence.
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// hash table: not a sequence -> 0 (matching the interpreter/JVM list
		// fallthrough).
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// List case: count cons cells until the value is no longer a cons.
		int countSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(countSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1); // break to $exit
		// count = count + 1
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(countSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(countSlot);
		// val = cdr(val)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1); // cdr
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(valSlot);
		ctx.writer.write(Instruction.BR, 0); // continue loop
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(countSlot);

		ctx.writer.write(Instruction.END); // array/hash-table-vs-list if
		ctx.writer.write(Instruction.END); // outer if (string)
		ctx.writer.write(Instruction.END); // outermost if (packed farray)
	}

}
