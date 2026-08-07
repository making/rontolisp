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
		declareOneEqrefLocal(w);
		int curSlot = 3;
		emitResolve(w, curSlot, 1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	// One local group of one (ref null eq).
	private static void declareOneEqrefLocal(WasmWriter w) {
		w.write(1);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
	}

	// Walks the displacement chain from local 0 (the header) with the flat index in
	// flatSlot (an i32 parameter, updated in place), leaving [buckets, i32 index] on the
	// stack. curSlot is a scratch (ref null eq) local. The inline original is
	// WasmArrayCompiler's emitResolveDataAndIndex, which this replaces; the only
	// difference is that the index stays a raw i32 here rather than being re-boxed as an
	// i31 each round, which a parameter lets it do.
	private static void emitResolve(WasmWriter w, int curSlot, int flatSlot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(curSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// exit when the data slot is not a cell (it is the buckets array)
		get(w, curSlot);
		consGet(w, 1);
		consGet(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// flat += the meta offset (meta.cdr.cdr)
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
		// cur = the target cell's header
		get(w, curSlot);
		consGet(w, 1);
		consGet(w, 1);
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
		get(w, curSlot);
		consGet(w, 1);
		consGet(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, flatSlot);
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
