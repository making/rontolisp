package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * The cells of the run-time function namespace {@code GLOBAL_FENV}, an alist of
 * {@code (name . value)} bindings a tombstone's nil value retires: a module global per
 * name a literal reference can read, null while the alist has no binding of the name and
 * the binding once it has ({@code .kb/symbol-runtime-api.md}, "A write through a computed
 * name"). A read is one {@code global.get} where it was a walk; a write that makes a
 * binding publishes it to the cell ({@code _fc_publish}), and a later write changes what
 * the binding holds. The alist holds only the names the program wrote.
 *
 * <p>
 * A function that keeps its compiled definition has a named entry, which a call by name
 * goes to in the function's place, of the function's type: the function itself while the
 * cell is null, and {@code _apply} of what the binding holds over the arguments as a call
 * passes them once it is not, through one helper per lambda list shape. A function value
 * keeps calling the function it was made of.
 *
 * <p>
 * A cell per name beat one array of cells, indexed by function id, which needs no code
 * per name to publish: {@code fib 40} on wasmtime 49 (331M calls; 1.4 s direct) took 1.9
 * s through an entry reading a global and 2.7 s through one reading an array element, the
 * array's own reference paying the collector's read barrier on every call (2026-11-05).
 *
 * <p>
 * The functions sit in one block in front of the program's defuns, which moves only
 * {@code WasmLispCompiler.userFuncBase()}: {@code _fc_restore}, the segments of
 * {@code _fc_publish}, the shape helpers, the entries.
 */
final class WasmFunctionCells {

	/** The cells of a program with no name a reference reads the namespace for. */
	static final WasmFunctionCells NONE = new WasmFunctionCells(0, Map.of(), List.of(), 0);

	/** The compares of one segment of {@code _fc_publish}: a bounded function body. */
	private static final int PUBLISH_SEGMENT_ROWS = 256;

	/**
	 * A lambda list's physical shape.
	 *
	 * @param required the required parameters
	 * @param optionals the physical optionals after them
	 * @param variadic whether a rest list follows
	 */
	private record Shape(int required, int optionals, boolean variadic) {

		int params() {
			return this.required + this.optionals + (this.variadic ? 1 : 0);
		}

	}

	/**
	 * A function's named entry.
	 *
	 * @param name the function's name
	 * @param shape its lambda list's shape
	 * @param global its cell
	 */
	private record Entry(String name, Shape shape, int global) {
	}

	private final int functionBase;

	private final Map<String, Integer> globals;

	private final List<Entry> entries;

	private final List<Shape> shapes;

	private final int publishSegments;

	private final Map<String, Integer> entryIndices = new LinkedHashMap<>();

	private WasmFunctionCells(int functionBase, Map<String, Integer> globals, List<Entry> entries,
			int publishSegments) {
		this.functionBase = functionBase;
		this.globals = globals;
		this.entries = entries;
		this.publishSegments = publishSegments;
		this.shapes = new ArrayList<>();
		for (Entry entry : entries) {
			if (!this.shapes.contains(entry.shape())) {
				this.shapes.add(entry.shape());
			}
		}
		int next = shapeBase() + this.shapes.size();
		for (Entry entry : entries) {
			this.entryIndices.put(entry.name(), next++);
		}
	}

	/**
	 * The module's cells.
	 * @param functionBase the index of the block's first function
	 * @param globals each spelling a reference or a write names a cell by, with its
	 * global
	 * @param celled the functions with a compiled definition, in defun order: name ->
	 * {required, optionals, variadic (0 or 1)}
	 * @return the cells
	 */
	static WasmFunctionCells of(int functionBase, Map<String, Integer> globals, Map<String, int[]> celled) {
		if (globals.isEmpty()) {
			return NONE;
		}
		List<Entry> entries = new ArrayList<>();
		celled.forEach((name, shape) -> entries.add(new Entry(name, new Shape(shape[0], shape[1], shape[2] != 0),
				java.util.Objects.requireNonNull(globals.get(name)))));
		// A spelling's compile-time name and the one-colon alias of an internal name.
		long rows = globals.keySet().stream().mapToLong(name -> name.contains("::") ? 2 : 1).sum();
		int segments = (int) Math.max(1, (rows + PUBLISH_SEGMENT_ROWS - 1) / PUBLISH_SEGMENT_ROWS);
		return new WasmFunctionCells(functionBase, globals, entries, segments);
	}

	/**
	 * {@return the functions of the block}
	 */
	int functionCount() {
		return this.globals.isEmpty() ? 0 : 1 + this.publishSegments + this.shapes.size() + this.entries.size();
	}

	/**
	 * {@return the spellings a cell is known by, with its global}
	 */
	Map<String, Integer> globals() {
		return this.globals;
	}

	/**
	 * The index of a function's named entry.
	 * @param name the function's name
	 * @return the index, or -1 for a function with none
	 */
	int entryIndex(String name) {
		return this.entryIndices.getOrDefault(name, -1);
	}

	/**
	 * {@return the index of {@code _fc_restore}, or -1 in a module with no cell}
	 */
	int restoreIndex() {
		return this.globals.isEmpty() ? -1 : this.functionBase;
	}

	/**
	 * {@return the index of {@code _fc_publish}, or -1 in a module with no cell}
	 */
	int publishIndex() {
		return this.globals.isEmpty() ? -1 : this.functionBase + 1;
	}

	private int shapeBase() {
		return this.functionBase + 1 + this.publishSegments;
	}

	/**
	 * Hands the type index of each function of the block, in index order, to
	 * {@code type}.
	 * @param type what takes each
	 */
	void forEachType(java.util.function.IntConsumer type) {
		if (this.globals.isEmpty()) {
			return;
		}
		// _fc_restore, and each segment of _fc_publish: (off, binding) -> nil
		for (int i = 0; i <= this.publishSegments; i++) {
			type.accept(WasmLispCompiler.TYPE_ENV_LOOKUP);
		}
		for (Shape shape : this.shapes) {
			type.accept(WasmLispCompiler.TYPE_CALLABLE_BASE + shape.params());
		}
		for (Entry entry : this.entries) {
			type.accept(WasmLispCompiler.TYPE_CALLABLE_BASE + entry.shape().params());
		}
	}

	/**
	 * Hands the body of each function of the block, in index order, to {@code body}.
	 * @param rows each spelling a run-time name can carry, with its offset, the name's
	 * cell its global
	 * @param targets a function's index, by name
	 * @param sentinelGlobal the global of the UNSUPPLIED marker
	 * @param identityHash whether a cons carries the identity-hash slot
	 * @param body what takes each
	 */
	void forEachBody(Map<Integer, Integer> rows, ToIntFunction<String> targets, int sentinelGlobal,
			boolean identityHash, java.util.function.Consumer<byte[]> body) {
		if (this.globals.isEmpty()) {
			return;
		}
		body.accept(restoreBody());
		List<Map.Entry<Integer, Integer>> pending = new ArrayList<>(rows.entrySet());
		for (int segment = 0; segment < this.publishSegments; segment++) {
			int from = Math.min(segment * PUBLISH_SEGMENT_ROWS, pending.size());
			int to = Math.min(from + PUBLISH_SEGMENT_ROWS, pending.size());
			body.accept(publishBody(pending.subList(from, to),
					segment + 1 < this.publishSegments && to < pending.size() ? publishIndex() + segment + 1 : -1));
		}
		for (Shape shape : this.shapes) {
			body.accept(shapeBody(shape, sentinelGlobal, identityHash));
		}
		for (Entry entry : this.entries) {
			body.accept(entryBody(entry, targets.applyAsInt(entry.name())));
		}
	}

	/**
	 * {@code _fc_restore(off, _)}: the name's binding is gone from the alist and from its
	 * cell, so its compiled definition answers again.
	 */
	private byte[] restoreBody() {
		ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		final int OFF = 0, NODE = 2, PREVIOUS = 3, NAME = 4;
		w.write(1);
		w.write(3);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		set(w, NODE);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, NODE);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		// name = car(car(node))
		get(w, NODE);
		consField(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		set(w, NAME);
		get(w, NAME);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		get(w, NAME);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(0);
		get(w, OFF);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		get(w, PREVIOUS);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		get(w, NODE);
		consField(w, 1);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.ELSE);
		get(w, PREVIOUS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		get(w, NODE);
		consField(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.END);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.END);
		w.write(Instruction.END);
		get(w, NODE);
		set(w, PREVIOUS);
		get(w, NODE);
		consField(w, 1);
		set(w, NODE);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		get(w, OFF);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN_CALL);
		w.writeUnsignedLeb128(publishIndex());
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * One segment of {@code _fc_publish(off, binding)}: the cell of the name at
	 * {@code off}, if it has one, holds the binding. Run only when a binding is made or
	 * dropped.
	 * @param rows the segment's offsets, each with its global
	 * @param next the next segment's index, or -1 at the last
	 */
	private static byte[] publishBody(List<Map.Entry<Integer, Integer>> rows, int next) {
		ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		final int OFF = 0, BINDING = 1;
		w.write(0);
		for (Map.Entry<Integer, Integer> row : rows) {
			get(w, OFF);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(row.getKey());
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			get(w, BINDING);
			w.write(Instruction.SET_GLOBAL);
			w.writeUnsignedLeb128(row.getValue());
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
		}
		if (next >= 0) {
			get(w, OFF);
			get(w, BINDING);
			w.write(Instruction.RETURN_CALL);
			w.writeUnsignedLeb128(next);
		}
		else {
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		}
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * A shape's helper {@code (binding, params...)}: {@code _apply} of what the binding
	 * holds over the arguments as a call passes them -- the required ones, the optionals
	 * passed (an UNSUPPLIED one ends them) and the rest list. For {@code fmakunbound}'s
	 * tombstone what it applies is the name, which {@code _apply}'s probe reports.
	 */
	private static byte[] shapeBody(Shape shape, int sentinelGlobal, boolean identityHash) {
		ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		final int BINDING = 0;
		int list = shape.params() + 1;
		int function = list + 1;
		w.write(1);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		if (shape.variadic()) {
			get(w, shape.params());
			set(w, list);
		}
		for (int param = shape.required() + shape.optionals(); param >= 1; param--) {
			boolean optional = param > shape.required();
			if (optional) {
				get(w, param);
				w.write(Instruction.GET_GLOBAL);
				w.writeUnsignedLeb128(sentinelGlobal);
				w.write(Instruction.REF_EQ);
				w.write(Instruction.I32_EQZ);
				w.write(Instruction.IF, 0x40);
			}
			get(w, param);
			get(w, list);
			WasmEmitHelper.emitNewCons(w, identityHash);
			set(w, list);
			if (optional) {
				w.write(Instruction.END);
			}
		}
		get(w, BINDING);
		consField(w, 1);
		set(w, function);
		get(w, function);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		get(w, BINDING);
		consField(w, 0);
		set(w, function);
		w.write(Instruction.END);
		get(w, function);
		get(w, list);
		w.write(Instruction.RETURN_CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_APPLY);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * A named entry: the function while its cell is null, else its shape's helper over
	 * the cell's binding.
	 * @param entry the entry
	 * @param target the function's index
	 */
	private byte[] entryBody(Entry entry, int target) {
		ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		int params = entry.shape().params();
		w.write(0);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(entry.global());
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		for (int i = 0; i <= params; i++) {
			get(w, i);
		}
		w.write(Instruction.RETURN_CALL);
		w.writeUnsignedLeb128(target);
		w.write(Instruction.END);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(entry.global());
		for (int i = 1; i <= params; i++) {
			get(w, i);
		}
		w.write(Instruction.RETURN_CALL);
		w.writeUnsignedLeb128(shapeBase() + this.shapes.indexOf(entry.shape()));
		w.write(Instruction.END);
		return out.toByteArray();
	}

	private static void get(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void set(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	/** With a cons on the stack as a {@code (ref null eq)}: the field. */
	private static void consField(WasmWriter w, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
	}

}
