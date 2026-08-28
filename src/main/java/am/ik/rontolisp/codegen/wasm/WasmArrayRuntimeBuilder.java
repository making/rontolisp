package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * The GENERAL-array arm of every element access, emitted once per module instead of at
 * each of the five accessor sites.
 *
 * <p>
 * A general array is a cell whose field 0 is the header cons
 * {@code (dims . (meta . data))}. When {@code data} is itself a cell the array is a
 * DISPLACED VIEW: the read has to add that view's offset (the cddr of its meta) to the
 * index and continue at the target's header, repeatedly, until a header whose data slot
 * is the buckets array is reached. That walk is a loop of about forty-five instructions,
 * and it used to be spelled at every {@code aref} / {@code row-major-aref} /
 * {@code %aset} / {@code %row-major-aset} -- together with the two never-released temps
 * it needs. Here it is two functions:
 *
 * <ul>
 * <li>{@code _arr_get (header, flat) -> value} ({@link WasmLispCompiler#FUNC_ARR_GET},
 * reusing {@code TYPE_BIG_SHIFT}),</li>
 * <li>{@code _arr_set (header, flat, value) -> value}
 * ({@link WasmLispCompiler#FUNC_ARR_SET}, {@code TYPE_ARR_SET}) -- answering the value it
 * stored, which is what the accessors leave on the stack.</li>
 * </ul>
 *
 * <p>
 * The packed float and packed integer arms deliberately stay inline at the site: the
 * integer one is the fused raw-{@code i64} store (`.kb/packed-integer-vectors.md`), which
 * a call would give up. Same lesson as {@code .kb/wasm-shared-coercion.md} -- when a
 * per-site expansion grows past a few hundred bytes, it becomes a callee.
 */
final class WasmArrayRuntimeBuilder {

	private WasmArrayRuntimeBuilder() {
	}

	/**
	 * Builds {@code _arr_get (header, flat) -> value}: the displacement walk, then
	 * {@code buckets[index]}.
	 * @return the function body (signature {@code ((ref null eq), i32) -> (ref null eq)},
	 * {@code TYPE_BIG_SHIFT})
	 */
	static byte[] buildArrGetBody() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// One extra local: the walk cursor (the header currently being examined).
		declareOneEqrefLocal(w);
		int curSlot = 2;
		emitResolve(w, curSlot, 1);
		// A STRING VIEW's data slot holds the string it aliases: read character `flat`
		// through the shared UTF-8 accessor and box it as the runtime CHARACTER.
		w.write(Instruction.BLOCK, 0x40);
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		emitDataSlot(w, curSlot);
		get(w, 1);
		WasmEmitHelper.emitStrCharAtCall(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // block
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * Builds {@code _arr_set (header, flat, value) -> value}: the displacement walk, then
	 * {@code buckets[index] = value}, answering the value.
	 * @return the function body (signature
	 * {@code ((ref null eq), i32, (ref null eq)) -> (ref null eq)},
	 * {@link WasmLispCompiler#TYPE_ARR_SET})
	 */
	static byte[] buildArrSetBody() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		// locals: 3 = cur, 4 = promoted, 5 = str, 6 = buckets (ref null eq); 7 = n,
		// 8 = i (i32).
		w.write(2);
		w.write(4);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		int curSlot = 3, promotedSlot = 4, strSlot = 5, bucketsSlot = 6, nSlot = 7, iSlot = 8;
		emitResolve(w, curSlot, 1);
		// A STRING VIEW over an IMMUTABLE string cannot be written through: promote the
		// target ONCE into a mutable character vector, hang it in the view's data slot,
		// and store into that. Every later access through this view -- and
		// array-displacement's answer -- sees the promoted vector, so the view behaves
		// as a mutable string from here on, exactly as the JVM's _strToCharVec does.
		w.write(Instruction.BLOCK, 0x40);
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		emitDataSlot(w, curSlot);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(strSlot);
		emitStringToCharVecCell(w, strSlot, bucketsSlot, nSlot, iSlot);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(promotedSlot);
		// (meta . data) is cur.cdr: replace its cdr with the promoted cell.
		get(w, curSlot);
		consGet(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(promotedSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		// cur = the promoted cell's header, so the store below lands in its buckets.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(promotedSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(curSlot);
		w.write(Instruction.END); // block
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	// One local group of count (ref null eq).
	private static void declareEqrefLocals(WasmWriter w, int count) {
		w.write(1);
		w.write(count);
		w.writeRefType(true, Type.EQ.code());
	}

	// One local group of one (ref null eq).
	private static void declareOneEqrefLocal(WasmWriter w) {
		declareEqrefLocals(w, 1);
	}

	// Pushes the data slot (cur.cdr.cdr) of the header in curSlot: the element buckets
	// of an ordinary array, the target CELL of an array view, or the STRING a string
	// view aliases.
	private static void emitDataSlot(WasmWriter w, int curSlot) {
		get(w, curSlot);
		consGet(w, 1);
		consGet(w, 1);
	}

	// Walks the displacement chain from local 0 (the header) with the flat index in
	// flatSlot (an i32 parameter, updated in place). curSlot is a scratch (ref null eq)
	// local and holds the FINAL header on exit; the stack is left empty, so the caller
	// reads the data slot itself (it is the buckets array, or the string a string view
	// aliases). The inline original is WasmArrayCompiler's emitResolveDataAndIndex,
	// which this replaces; the only difference is that the index stays a raw i32 here
	// rather than being re-boxed as an i31 each round, which a parameter lets it do.
	private static void emitResolve(WasmWriter w, int curSlot, int flatSlot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(curSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// exit when the data slot is not a cell (it is the buckets array, or a string)
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		emitAddMetaOffset(w, curSlot, flatSlot);
		// cur = the target cell's header
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(curSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// A string target ends the walk WITHOUT a hop, so this view's own offset has
		// not been folded in yet. (An ordinary array's offset word is 0, and a
		// character vector's is the marker 1, so only this arm may add it.)
		emitDataSlot(w, curSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		emitAddMetaOffset(w, curSlot, flatSlot);
		w.write(Instruction.END);
	}

	// flat += the meta offset (cur.cdr.car.cdr.cdr) of the header in curSlot.
	private static void emitAddMetaOffset(WasmWriter w, int curSlot, int flatSlot) {
		get(w, flatSlot);
		get(w, curSlot);
		consGet(w, 1);
		consGet(w, 0);
		consGet(w, 1);
		consGet(w, 1);
		WasmEmitHelper.castI31GetS(w);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(flatSlot);
	}

	// Builds a mutable character vector CELL holding the characters of the string in
	// strSlot, and leaves it on the stack: the general array shape whose meta offset is
	// the character-vector marker i31 1, its dimension the character count, and its
	// buckets one TYPE_CHAR per character. This is what a write through a string view
	// over an IMMUTABLE string promotes the target to (the JVM twin is
	// JvmArrayRuntimeBuilder's _strToCharVec).
	private static void emitStringToCharVecCell(WasmWriter w, int strSlot, int bucketsSlot, int nSlot, int iSlot) {
		get(w, strSlot);
		WasmEmitHelper.emitStrCharCountCall(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(nSlot);
		get(w, nSlot);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(bucketsSlot);
		i32(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(iSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, iSlot);
		get(w, nSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, bucketsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, iSlot);
		get(w, strSlot);
		get(w, iSlot);
		WasmEmitHelper.emitStrCharAtCall(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, iSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(iSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// dims = [n]
		get(w, nSlot);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		i32(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		// meta = (null . (null . i31 1)) -- no fill pointer, not adjustable, and the
		// character-vector marker in the offset word.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		i32(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		// (meta . buckets), then (dims . that), then the cell
		get(w, bucketsSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void get(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	// Casts the (ref null eq) on the stack to TYPE_CONS and reads car (0) or cdr (1).
	private static void consGet(WasmWriter w, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
	}

}
