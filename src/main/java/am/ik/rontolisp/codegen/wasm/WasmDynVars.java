package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * The {@code --reentrant} per-task dynamic-variable store: the wasm port of the JVM's
 * {@code _d$} hybrid ({@code .kb/dynamic-special-variables.md}). Shallow binding over one
 * module global cannot survive two interleaved call extents (a parked JSPI call's
 * neighbour reads its binding back), so a reentrant module gives every export call a TASK
 * RECORD -- a {@code TYPE_HASH_BUCKETS} array with one slot per dynamically-bound special
 * ({@code Ctx.dynSlots}) -- held in a module global
 * ({@code Ctx.reentrantTaskGlobalIndex}) that the export wrapper sets on entry and the
 * import wrapper saves into a local and restores around the one place another extent can
 * run: the suspending host call.
 *
 * <p>
 * A slot holds {@code null} (no binding in this task -- reads fall through to the
 * special's ordinary module global, the default) or a {@code TYPE_CELL} whose field is
 * the innermost binding. A binding pushes a fresh cell and saves the previous slot value
 * in a wrapper-local ({@code specialBindScopes}), exactly the save/restore discipline the
 * shallow path uses over the global -- so the exit-restore machinery
 * ({@code WasmReturnCompiler}/{@code WasmReturnFromCompiler}) needs only a second restore
 * spelling, and the documented unwind limitations carry over unchanged, neither widened
 * nor narrowed.
 *
 * <p>
 * Only the specials {@code SpecialVarCollector.collectDynamicallyBound} names get a slot;
 * every other special keeps its plain {@code global.get} read even under
 * {@code --reentrant}, and a non-reentrant module is byte-identical to one built before
 * this class existed. Under-collection is a compile-time throw here (the
 * {@code JvmLetCompiler} rule), never a silent process-global binding.
 */
final class WasmDynVars {

	private WasmDynVars() {
	}

	/** Whether reads/writes/bindings of {@code name} go through the per-task store. */
	static boolean handles(WasmLispCompiler.Ctx ctx, String name) {
		return ctx.reentrant && ctx.reentrantTaskGlobalIndex >= 0 && ctx.dynSlots.containsKey(name);
	}

	/**
	 * Pushes the current task record, cast to the buckets array. The record always
	 * exists: {@code _start} creates one for the load path and every export wrapper
	 * creates a fresh one on entry.
	 */
	private static void emitTask(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.reentrantTaskGlobalIndex);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static int slot(WasmLispCompiler.Ctx ctx, String name) {
		Integer slot = ctx.dynSlots.get(name);
		if (slot == null) {
			// The JvmLetCompiler rule: under-collection must be a compile-time throw,
			// never a silent process-global binding.
			throw new IllegalStateException("special variable " + name
					+ " was not collected as dynamically bound (SpecialVarCollector.collectDynamicallyBound)");
		}
		return slot;
	}

	/**
	 * DYNAMIC-FIRST read of a dynamically-bound special: the task slot's cell when a
	 * binding is active in this task, the module global (the default) otherwise. Leaves
	 * one {@code (ref null eq)} on the stack.
	 * @param ctx the compilation context
	 * @param name the special's name
	 * @param globalIndex the special's module global (the default value)
	 */
	static void emitRead(WasmLispCompiler.Ctx ctx, String name, int globalIndex) {
		int cell = ctx.allocTemp();
		emitTask(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(slot(ctx, name));
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(cell);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(globalIndex);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(cell);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Assignment of a dynamically-bound special: writes the ACTIVE binding's cell when
	 * this task has one, the module global (the CL rule -- {@code setq} of a special with
	 * no binding active assigns the global default) otherwise. Reads the value from
	 * {@code valueSlot}; stack-neutral.
	 * @param ctx the compilation context
	 * @param name the special's name
	 * @param globalIndex the special's module global
	 * @param valueSlot the local holding the assigned value
	 */
	static void emitWrite(WasmLispCompiler.Ctx ctx, String name, int globalIndex, int valueSlot) {
		int cell = ctx.allocTemp();
		emitTask(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(slot(ctx, name));
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(cell);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth++;
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(globalIndex);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(cell);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Establishes a dynamic binding: saves the previous slot value (a cell or null) into
	 * a fresh temp local and stores a new {@code TYPE_CELL} over the init value. The init
	 * value is read from {@code valueSlot}; stack-neutral.
	 * @param ctx the compilation context
	 * @param name the special's name
	 * @param valueSlot the local holding the init value
	 * @return the save slot holding the previous slot value, for the restore
	 */
	static int emitBind(WasmLispCompiler.Ctx ctx, String name, int valueSlot) {
		int save = ctx.allocTemp();
		emitTask(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(slot(ctx, name));
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(save);
		emitTask(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(slot(ctx, name));
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		return save;
	}

	/**
	 * The {@code %progv-dyn-bind} spelling of {@link #emitBind}: installs a fresh cell
	 * over the value in {@code valueSlot} and leaves the PREVIOUS slot value (a cell or
	 * null) on the stack -- the progv lowering conses it into its save list instead of
	 * parking it in a wrapper local, because the restore runs in a different loop
	 * iteration ({@code WasmProgvCompiler}).
	 * @param ctx the compilation context
	 * @param name the special's name
	 * @param valueSlot the local holding the init value
	 */
	static void emitProgvBind(WasmLispCompiler.Ctx ctx, String name, int valueSlot) {
		emitTask(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(slot(ctx, name));
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		emitTask(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(slot(ctx, name));
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	/**
	 * The {@code %progv-dyn-unbind} spelling of {@link #emitRestore}: writes the previous
	 * slot value from {@code prevSlot} back into the special's per-task slot.
	 * Stack-neutral.
	 * @param ctx the compilation context
	 * @param name the special's name
	 * @param prevSlot the local holding the previous slot value
	 */
	static void emitProgvUnbind(WasmLispCompiler.Ctx ctx, String name, int prevSlot) {
		emitTask(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(slot(ctx, name));
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(prevSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	/**
	 * Emits ONE binding restore -- the reentrant counterpart of
	 * {@code local.get save; global.set g}, shared by the let epilogue and the
	 * {@code return}/{@code return-from} exit paths. {@code bind} is a
	 * {@code specialBindScopes} entry: in a reentrant module {@code bind[0]} is the DYN
	 * SLOT, elsewhere the global index; {@code bind[1]} is the save local either way.
	 * Stack-neutral.
	 * @param ctx the compilation context
	 * @param bind the {@code specialBindScopes} entry
	 */
	static void emitRestore(WasmLispCompiler.Ctx ctx, int[] bind) {
		if (ctx.reentrant && ctx.reentrantTaskGlobalIndex >= 0) {
			emitTask(ctx);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(bind[0]);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bind[1]);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
			return;
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bind[1]);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(bind[0]);
	}

	/**
	 * Emits a fresh task record into the task global -- the export wrapper's entry
	 * prologue and the {@code _start} preamble (the load path binds against a record too,
	 * so binding sites never need a null check).
	 * @param ctx the compilation context (no-op unless the module carries the store)
	 */
	static void emitTaskBegin(WasmLispCompiler.Ctx ctx) {
		if (ctx.reentrantTaskGlobalIndex < 0) {
			return;
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.dynSlots.size());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.reentrantTaskGlobalIndex);
	}

	/**
	 * Saves the current task record into a fresh temp local -- the import wrapper's move
	 * BEFORE the host call it wraps: a suspending call parks this stack, another export
	 * call may swap the task global, and the code after the call must run against ITS OWN
	 * task again.
	 * @param ctx the compilation context
	 * @return the save slot, or -1 when the module carries no store
	 */
	static int emitTaskSave(WasmLispCompiler.Ctx ctx) {
		if (!ctx.reentrant || ctx.reentrantTaskGlobalIndex < 0) {
			return -1;
		}
		int save = ctx.allocTemp();
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.reentrantTaskGlobalIndex);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(save);
		return save;
	}

	/**
	 * Restores the task record saved by {@link #emitTaskSave} -- immediately after the
	 * wrapped host call returns (i.e. after the resume), before anything reads a special.
	 * @param ctx the compilation context
	 * @param save the save slot ({@code -1} is a no-op)
	 */
	static void emitTaskRestore(WasmLispCompiler.Ctx ctx, int save) {
		if (save < 0) {
			return;
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(save);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.reentrantTaskGlobalIndex);
	}

}
