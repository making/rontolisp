package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Shared helper methods for WASM instruction emission used across all expression
 * compilers.
 */
final class WasmEmitHelper {

	private WasmEmitHelper() {
	}

	/**
	 * Emits a "grow linear memory if it does not yet cover {@code top}" guard. The GC
	 * backend's heap is a bump allocator over {@code HEAP_PTR_ADDR}; without this guard a
	 * large allocation walks past the initial memory size and any access traps with
	 * "memory access out of bounds". This grows memory by whole pages when the
	 * about-to-be-used top address exceeds the current size.
	 *
	 * <p>
	 * It is pure-stack: it allocates no local and adds no function (so every fixed
	 * {@code FUNC_*} index, and the component byte-identical blobs that depend on them,
	 * stay valid). {@code pushTop} must emit code pushing the absolute byte address the
	 * heap is about to use (an {@code i32} computed only from locals / constants / memory
	 * loads, so it can be evaluated twice); it is called once for the test and once to
	 * size the grow. The stack is left as it was found.
	 * @param w the writer for the function body being emitted
	 * @param pushTop emits the i32 top address (idempotent, no net stack effect beyond
	 * the one value it pushes)
	 */
	static void emitGrowHeapTo(WasmWriter w, Runnable pushTop) {
		// neededPages = (top + 65535) >>> 16
		pushTop.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xffff);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(16);
		w.write(Instruction.I32_SHR_U);
		// neededPages > memory.size ?
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.IF, 0x40);
		// memory.grow(neededPages - memory.size); drop the (old size / -1) result
		pushTop.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xffff);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(16);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GROW_MEMORY, 0x00);
		w.write(Instruction.DROP);
		w.write(Instruction.END);
	}

	static void castI31GetS(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	/**
	 * Emits a list-type guard for the {@code map*} family over the value in
	 * {@code listSlot}: if the value is neither null (nil) nor a cons, the function traps
	 * ({@code unreachable}). This matches the interpreter, which signals an error rather
	 * than silently treating a non-list (e.g. a string) as the empty list. The guard is a
	 * balanced, self-contained {@code if}/{@code end} that pushes no value, so it does
	 * not affect {@code wasmCtrlDepth} bookkeeping.
	 */
	static void emitRequireListGuard(WasmLispCompiler.Ctx ctx, int listSlot) {
		WasmWriter w = ctx.writer;
		// valid = (ref.is_null list) | (ref.test $cons list)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(listSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(listSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_OR);
		// if not valid, trap.
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	/**
	 * Runtime type check: convert (ref eq) on stack to f64. If i31ref (integer), converts
	 * via f64.convert_i32_s. If a ratio struct, divides numerator by denominator as f64.
	 * If float_struct, extracts f64 field.
	 */
	static void castFloatGetF64(WasmLispCompiler.Ctx ctx) {
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// i31 path: cast to i31, get_s, convert to f64
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		ctx.writer.write(Instruction.F64_CONVERT_S_I32);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// ratio path: numerator / denominator as f64 (float contagion)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		ctx.writer.write(Instruction.F64_CONVERT_S_I32);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		ctx.writer.write(Instruction.F64_CONVERT_S_I32);
		ctx.writer.write(Instruction.F64_DIV);
		ctx.writer.write(Instruction.ELSE);
		// float_struct path: cast, extract f64 field
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Emits the Lisp boolean true. It is the symbol {@code t} (represented at runtime as
	 * a TYPE_STRING struct pointing at {@code "t"}, like any other symbol), so it prints
	 * as {@code t} and is {@code eq} to a quoted {@code 't}, matching the interpreter.
	 */
	static void emitTrue(WasmLispCompiler.Ctx ctx) {
		compileStringLiteral("t", ctx);
	}

	/**
	 * Converts an i32 (0=false, non-0=true) on the WASM stack into a Lisp boolean
	 * (ref.null eq = nil, or the symbol {@code t}).
	 */
	static void emitBoolFromI32(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		emitTrue(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	static void emitBoxLocal(WasmLispCompiler.Ctx ctx, int slot) {
		// Box: load value, create cell, store cell back
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	static void emitLoadCapture(WasmLispCompiler.Ctx ctx, int depth) {
		// Navigate env cons list to depth, get car (cell), then unbox
		emitLoadCaptureCell(ctx, depth);
		// Unbox from cell
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0);
	}

	static void emitLoadCaptureCell(WasmLispCompiler.Ctx ctx, int depth) {
		// Load env from slot 0
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(ctx.closureEnvSlot);
		// Navigate through cons list: cdr depth times
		for (int i = 0; i < depth; i++) {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeSignedLeb128(1); // cdr
		}
		// Get car (the cell)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // car = cell
		// Cast to cell
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
	}

	/**
	 * Load the cell (boxed reference) for a variable, for building closure env.
	 */
	static void emitLoadVarCell(String varName, WasmLispCompiler.Ctx ctx) {
		// First check if it's a boxed local
		Integer slot = ctx.locals.get(varName);
		if (slot != null && ctx.boxedVars.contains(varName)) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			// The local IS the cell
			return;
		}
		// Check if it's a capture (already a cell in the env)
		Integer captureIdx = ctx.captures.get(varName);
		if (captureIdx != null) {
			emitLoadCaptureCell(ctx, captureIdx);
			return;
		}
		// Unboxed local: create a new cell
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
			return;
		}
		throw new UnsupportedOperationException("Cannot find variable for closure: " + varName);
	}

	/**
	 * Compares two (ref null eq) values on the stack for {@code eq} (object identity).
	 * Produces an i32 (0=false, 1=true). Uses ref.eq for identity (so equal small
	 * integers and same-object cons cells are eq), falling back to string offset
	 * comparison for TYPE_STRING values (which also covers symbols, since the StringTable
	 * deduplicates identical symbols/strings to the same offset). Floats and ratios are
	 * distinct boxed objects and are therefore never eq.
	 */
	static void emitEqComparison(WasmLispCompiler.Ctx ctx) {
		int aSlot = ctx.allocTemp();
		int bSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		// Try ref.eq
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.REF_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.ELSE);
		// Symbols and strings: compare interned offsets
		emitStringEqOrZero(ctx, aSlot, bSlot);
		ctx.writer.write(Instruction.END); // end ref.eq if
	}

	/**
	 * Compares two (ref null eq) values on the stack for {@code eql}. Like {@code eq},
	 * but floats and ratios of the same type and value are equal. Produces an i32
	 * (0=false, 1=true).
	 */
	static void emitEqlComparison(WasmLispCompiler.Ctx ctx) {
		int aSlot = ctx.allocTemp();
		int bSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		// Try ref.eq
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.REF_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.ELSE);
		// Both characters: compare code points (char structs are value objects)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.ELSE);
		// Both floats: compare f64 fields (float structs are value objects)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.ELSE);
		// Both ratios: compare numerators and denominators
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.ELSE);
		// Symbols and strings: compare interned offsets
		emitStringEqOrZero(ctx, aSlot, bSlot);
		ctx.writer.write(Instruction.END); // end ratio if
		ctx.writer.write(Instruction.END); // end float if
		ctx.writer.write(Instruction.END); // end char if
		ctx.writer.write(Instruction.END); // end ref.eq if
	}

	// Emits an i32 result: 1 if both slots are TYPE_STRING structs with the same data
	// offset (so the StringTable has deduplicated them, i.e. they are the same
	// symbol/string), 0 otherwise.
	private static void emitStringEqOrZero(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot) {
		// Check if a is string
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		// Check if b is string
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		// Both strings: compare offset fields
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.ELSE);
		// a is string, b is not
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// a is not string
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
	}

	static void compileStringLiteral(String displayForm, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.StringTable.StringEntry entry = ctx.stringTable.addString(displayForm);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.length());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
	}

}
