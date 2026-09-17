package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _ihash ((ref null eq)) -> i32}: the hash an identity-compared aggregate
 * is PLACED by ({@code FUNC_IHASH}, signature {@code TYPE_RAT_GET}, the same as
 * {@code _hash}).
 *
 * <p>
 * A wasm-GC reference has no address, so an object's identity hash has to live IN the
 * object: in a module that makes an {@code eq}/{@code eql} table
 * ({@code Ctx.usesIdentityHashTables}) every {@code TYPE_CONS}, {@code TYPE_CELL} (a
 * general array or a hash table), {@code TYPE_CLOSURE} and {@code TYPE_INSTANCE} carries
 * a trailing {@code (mut i32)} slot, {@code 0} until the object is first keyed. This
 * function reads the slot and, on {@code 0}, assigns the next value of a module-wide
 * sequence global mixed through murmur3's {@code fmix32} -- a bijection on i32, so a
 * sequence value of 1 or more never mixes to the unassigned {@code 0}, and consecutive
 * assignments spread over every bit rather than only the low ones a power-of-two bucket
 * count reads. The hash never changes once assigned, whatever the object's fields do
 * afterwards, which is exactly what an identity table's placement needs and a structural
 * hash cannot give (.kb/hash-tables.md). Any other value (a number, a symbol, a string, a
 * character) has no identity apart from its content and hashes through {@code _hash},
 * which the {@code eql}/{@code eq} comparison on those values agrees with. A packed array
 * (a wasm {@code array}, with no field to add -- {@code TYPE_FARRAY}'s own struct wrapper
 * could carry the slot, but nothing keys by one, .kb/hash-tables.md) still falls through
 * to {@code _hash}'s constant 0.
 *
 * <p>
 * A module with no identity table has no slot to read and carries the stub: nothing calls
 * it, and the fixed function index stays where every other index expects it.
 */
final class WasmIdentityHashRuntimeBuilder {

	// murmur3 fmix32: h ^= h >>> 16; h *= C1; h ^= h >>> 13; h *= C2; h ^= h >>> 16.
	private static final int FMIX_C1 = 0x85ebca6b;

	private static final int FMIX_C2 = 0xc2b2ae35;

	// The cons's identity-hash slot is its third field, the cell's its second, the
	// closure's its third, the instance's its third (the type section in
	// WasmLispCompiler declares them).
	static final int CONS_HASH_FIELD = 2;

	static final int CELL_HASH_FIELD = 1;

	static final int CLOSURE_HASH_FIELD = 2;

	static final int INSTANCE_HASH_FIELD = 2;

	private WasmIdentityHashRuntimeBuilder() {
	}

	/**
	 * The body a module without identity tables carries: a constant {@code 0}, called by
	 * nothing.
	 * @return the stub body
	 */
	static byte[] buildStub() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * The real body.
	 * @param seqGlobalIndex the {@code (mut i32)} sequence global the next hash is drawn
	 * from
	 * @param instanceTypeIndex the {@code TYPE_INSTANCE} index, or -1 when the module has
	 * no instances
	 * @return the function body
	 */
	static byte[] build(int seqGlobalIndex, int instanceTypeIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(1); // 1 local group
		w.write(1); // local 1: i32 h
		w.write(Type.I32);

		refTest(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitSlotHash(w, WasmLispCompiler.TYPE_CONS, CONS_HASH_FIELD, seqGlobalIndex);
		w.write(Instruction.ELSE);
		refTest(w, WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitSlotHash(w, WasmLispCompiler.TYPE_CELL, CELL_HASH_FIELD, seqGlobalIndex);
		w.write(Instruction.ELSE);
		refTest(w, WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitSlotHash(w, WasmLispCompiler.TYPE_CLOSURE, CLOSURE_HASH_FIELD, seqGlobalIndex);
		w.write(Instruction.ELSE);
		if (instanceTypeIndex >= 0) {
			refTest(w, instanceTypeIndex);
			w.write(Instruction.IF);
			w.write(Type.I32);
			emitSlotHash(w, instanceTypeIndex, INSTANCE_HASH_FIELD, seqGlobalIndex);
			w.write(Instruction.ELSE);
		}
		// no identity slot: the content hash, which eql/eq on such a value agrees with
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_HASH);
		if (instanceTypeIndex >= 0) {
			w.write(Instruction.END); // end instance if
		}
		w.write(Instruction.END); // end closure if
		w.write(Instruction.END); // end cell if
		w.write(Instruction.END); // end cons if
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	// i32 = 1 when the key (local 0) is of the given struct type.
	private static void refTest(WasmWriter w, int typeIndex) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(typeIndex);
	}

	// Leaves the key's slot hash on the stack, assigning it first when the slot still
	// holds the unassigned 0.
	private static void emitSlotHash(WasmWriter w, int typeIndex, int field, int seqGlobalIndex) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(typeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(typeIndex);
		w.writeUnsignedLeb128(field);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// h = fmix32(++seq)
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(seqGlobalIndex);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(seqGlobalIndex);
		emitXorShift(w, 16);
		emitMul(w, FMIX_C1);
		emitXorShift(w, 13);
		emitMul(w, FMIX_C2);
		emitXorShift(w, 16);
		// the slot remembers it
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(typeIndex);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(typeIndex);
		w.writeUnsignedLeb128(field);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.END);
	}

	// h ^= h >>> shift, on local 1
	private static void emitXorShift(WasmWriter w, int shift) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(shift);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_XOR);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
	}

	// h *= constant, on local 1
	private static void emitMul(WasmWriter w, int constant) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(constant);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
	}

}
