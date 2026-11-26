package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispLayout;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * Funcallable instances on wasm-GC (a class descending from
 * {@code closer-mop:funcallable-standard-object}, {@link LispLayout#funcallable()}): an
 * instance struct whose layout record is one of the funcallable classes', carrying the
 * function it calls in its {@link LispLayout#FUNCALLABLE_CELL}. Every call through a
 * value reaches a dispatcher's prologue (and {@code _apply} hands a non-closure to the
 * spread dispatcher's), which replaces such an instance by that function before it
 * classifies the callee; {@code functionp} answers t for one. Built only for a module
 * whose program defines a funcallable class it can instantiate, so every other module is
 * byte-identical.
 *
 * @param instanceTypeIndex the module's instance struct type
 * @param layoutAddresses the baked layout records of the funcallable classes
 */
record WasmFuncallableInstances(int instanceTypeIndex, List<Integer> layoutAddresses) {

	/**
	 * The funcallable classes a module can build instances of, or null when it can build
	 * none: no funcallable class, or none whose layout this module baked.
	 * @param closRegistry the complete class registry
	 * @param layoutAddresses the baked layout records by instance tag
	 * @param instanceTypeIndex the instance struct type, or -1 when the module has none
	 * @return the funcallable layouts, or null
	 */
	static @Nullable WasmFuncallableInstances of(ClosRegistry closRegistry, Map<String, Integer> layoutAddresses,
			int instanceTypeIndex) {
		if (instanceTypeIndex < 0) {
			return null;
		}
		List<String> tags = closRegistry.funcallableTags();
		List<Integer> addresses = tags.stream().map(layoutAddresses::get).filter(java.util.Objects::nonNull).toList();
		return addresses.isEmpty() ? null : new WasmFuncallableInstances(instanceTypeIndex, addresses);
	}

	/**
	 * Pushes whether the value in {@code valueLocal} is a funcallable instance (i32).
	 * @param w the function body
	 * @param valueLocal the local holding the value
	 */
	void emitTest(WasmWriter w, int valueLocal) {
		getLocal(w, valueLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(this.instanceTypeIndex);
		w.write(Instruction.IF, Type.I32.code());
		emitLayoutMatch(w, valueLocal);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
	}

	/**
	 * Replaces a funcallable instance in {@code valueLocal} by the function in its
	 * {@link LispLayout#FUNCALLABLE_CELL}. With {@code closureDepth >= 0} the replacement
	 * happens only when that function is a closure, which then branches to the label
	 * {@code closureDepth} levels out of the emission point (the prologue's closure
	 * path); anything else -- an instance with no function installed -- keeps the
	 * instance, for the not-a-function report to name. With {@code closureDepth < 0} (a
	 * trapping module) the cell replaces the instance unconditionally and control falls
	 * through to the closure cast.
	 * @param w the function body
	 * @param valueLocal the local holding the callee
	 * @param scratchLocal an {@code eqref} local free at this point
	 * @param closureDepth the branch depth to the closure path, or -1
	 */
	void emitUnwrap(WasmWriter w, int valueLocal, int scratchLocal, int closureDepth) {
		getLocal(w, valueLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(this.instanceTypeIndex);
		w.write(Instruction.IF, 0x40);
		emitLayoutMatch(w, valueLocal);
		w.write(Instruction.IF, 0x40);
		// scratch = slots[array.len + FUNCALLABLE_CELL]
		getLocal(w, valueLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(this.instanceTypeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(this.instanceTypeIndex);
		w.writeUnsignedLeb128(1); // field 1: the slots array
		setLocal(w, scratchLocal);
		getLocal(w, scratchLocal);
		castBuckets(w);
		getLocal(w, scratchLocal);
		castBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(LispLayout.FUNCALLABLE_CELL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, scratchLocal);
		if (closureDepth < 0) {
			getLocal(w, scratchLocal);
			setLocal(w, valueLocal);
		}
		else {
			getLocal(w, scratchLocal);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
			w.write(Instruction.IF, 0x40);
			getLocal(w, scratchLocal);
			setLocal(w, valueLocal);
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(closureDepth + 3); // past the three ifs
			w.write(Instruction.END);
		}
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/**
	 * Over the INSTANCE in {@code valueLocal}: pushes whether its layout record is one of
	 * the funcallable classes' (i32).
	 */
	private void emitLayoutMatch(WasmWriter w, int valueLocal) {
		for (int i = 0; i < this.layoutAddresses.size(); i++) {
			getLocal(w, valueLocal);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(this.instanceTypeIndex);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeUnsignedLeb128(this.instanceTypeIndex);
			w.writeUnsignedLeb128(0); // field 0: the layout record address
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(this.layoutAddresses.get(i));
			w.write(Instruction.I32_EQ);
			if (i > 0) {
				w.write(Instruction.I32_OR);
			}
		}
	}

	private static void castBuckets(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static void getLocal(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void setLocal(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

}
