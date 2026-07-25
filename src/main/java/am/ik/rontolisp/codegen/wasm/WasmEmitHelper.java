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

	/**
	 * Opens the EH-mode catch_all wrapper of an entry function ({@code _start}/
	 * {@code run}/an export wrapper): {@code block} + {@code try_table (catch_all 0)},
	 * the catch label being that block. The body compiled next runs inside the try_table;
	 * {@link #emitCatchAllEpilogue} closes the structure. The normal path exits with a
	 * {@code return} from inside the try_table (see the epilogue), so no result blocktype
	 * is needed whatever the function's signature; the catch_all landing pad falls out of
	 * the block into an {@code unreachable}, preserving the trap shape of an uncaught
	 * condition.
	 * @param ctx the compilation context (its wasmCtrlDepth is raised by the two opened
	 * control structures)
	 */
	static void emitCatchAllPrologue(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.write(Instruction.TRY_TABLE, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.CATCH_ALL);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.wasmCtrlDepth += 2;
	}

	/**
	 * Closes the structure opened by {@link #emitCatchAllPrologue}: {@code return} (the
	 * normal exit, carrying whatever the function's result values are on the stack), the
	 * try_table's and the block's {@code end}, then the {@code unreachable} landing pad.
	 * The caller still writes the function's own final {@code end}.
	 * @param ctx the compilation context
	 */
	static void emitCatchAllEpilogue(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.RETURN);
		ctx.writer.write(Instruction.END); // try_table
		ctx.writer.write(Instruction.END); // block
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.wasmCtrlDepth -= 2;
	}

	/**
	 * Emits an exact-integer literal: an i31 for the fixnum range, else an i64 constant
	 * boxed into a {@code TYPE_BIGNUM} struct (e.g. the {@code #xEFCDAB89} md5 magic
	 * constants). Mirrors the runtime {@code _int_new} normalization, so a literal and a
	 * computed value of the same magnitude share one representation.
	 * @param value the integer value
	 * @param ctx the compilation context
	 */
	static void compileIntegerLiteral(long value, WasmLispCompiler.Ctx ctx) {
		if (value >= WasmBignumRuntimeBuilder.I31_MIN && value <= WasmBignumRuntimeBuilder.I31_MAX) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128((int) value);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			return;
		}
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(value);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
	}

	static void castI31GetS(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	/**
	 * Like {@link #castI31GetS} but zero-extends the 31 payload bits. Used for a
	 * linear-memory pointer stored in an i31ref (the {@code --simd} packed float-array
	 * block, see {@link WasmVecSimdRuntimeBuilder}): any address below 2 GiB then
	 * round-trips exactly, where {@code i31.get_s} would sign-extend everything above 1
	 * GiB.
	 */
	static void castI31GetU(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_U);
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
	 * via f64.convert_i32_s. If a boxed integer ({@code TYPE_BIGNUM}), converts its i64
	 * field. If a ratio struct, divides numerator by denominator as f64. If float_struct,
	 * extracts f64 field.
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
		// boxed-integer path: convert the i64 field
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.F64_CONVERT_S_I64);
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
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Emits the Lisp boolean true. It is the symbol {@code t} (represented at runtime as
	 * a TYPE_STRING struct pointing at {@code "t"}, like any other symbol), so it prints
	 * as {@code t} and is {@code eq} to a quoted {@code 't}, matching the interpreter.
	 */
	static void emitTrue(WasmLispCompiler.Ctx ctx) {
		compileStringLiteral("T", ctx);
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
	 * integers and same-object cons cells are eq); when ref.eq is false, two TYPE_CHAR
	 * structs with the same code point still compare equal (matching the interpreter's
	 * value-based {@code LispChar.equals} and the JVM's {@code _eqv} int[] fast-path, so
	 * {@code (eq #\A #\A)} agrees across every backend -- CL permits {@code eq} to return
	 * {@code T} for characters that {@code char=}); otherwise falls back to string offset
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
		// Both characters: compare code points; both boxed integers: compare i64 fields
		// (the interpreter's eq compares value types by equals, so (eq #x100000000
		// #x100000000) answers T on every backend); else fall back to symbol/string
		// offset
		emitCharCodePointEqOrElse(ctx, aSlot, bSlot,
				() -> emitBignumEqOrElse(ctx, aSlot, bSlot, () -> emitStringEqOrZero(ctx, aSlot, bSlot)));
		ctx.writer.write(Instruction.END); // end ref.eq if
	}

	/**
	 * Emits {@code if both operands are TYPE_BIGNUM then i64 field == i64 field else
	 * <fallback>}, leaving an i32 (0=false, 1=true) on the stack.
	 */
	private static void emitBignumEqOrElse(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot, Runnable elseBranch) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		emitBignumField(ctx, aSlot);
		emitBignumField(ctx, bSlot);
		ctx.writer.write(Instruction.I64_EQ);
		ctx.writer.write(Instruction.ELSE);
		elseBranch.run();
		ctx.writer.write(Instruction.END); // end bignum if
	}

	/**
	 * Emits {@code if both operands are TYPE_CHAR then code point == code point else
	 * <fallback>}, leaving an i32 (0=false, 1=true) on the stack. Shared between
	 * {@link #emitEqComparison eq} and {@link #emitEqlComparison eql} so both give
	 * TYPE_CHAR value equality (two separately-allocated char structs holding the same
	 * code point compare true, mirroring the JVM {@code _eqv} int[] branch and the
	 * interpreter's value-based {@code LispChar.equals}).
	 */
	private static void emitCharCodePointEqOrElse(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot, Runnable elseBranch) {
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
		elseBranch.run();
		ctx.writer.write(Instruction.END); // end char if
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
		// Both characters: compare code points (char structs are value objects); if not
		// both chars, fall through to float / ratio / string comparisons below.
		emitCharCodePointEqOrElse(ctx, aSlot, bSlot, () -> emitEqlNonCharTail(ctx, aSlot, bSlot));
		ctx.writer.write(Instruction.END); // end ref.eq if
	}

	// Emits eql's non-char, non-ref.eq tail: TYPE_BIGNUM value comparison, TYPE_FLOAT
	// value comparison, then TYPE_RATIO numerator+denominator comparison, then
	// symbol/string offset compare. Leaves an i32 (0/1) on the stack. Extracted so the
	// shared TYPE_CHAR helper can be used from both emitEqComparison and
	// emitEqlComparison without duplicating the char-compare shape.
	private static void emitEqlNonCharTail(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot) {
		// Both boxed integers: compare i64 fields (the _int_new normalization keeps
		// every in-range integer an i31, so a boxed value only ever equals another
		// boxed value)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		emitBignumField(ctx, aSlot);
		emitBignumField(ctx, bSlot);
		ctx.writer.write(Instruction.I64_EQ);
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
		ctx.writer.write(Instruction.END); // end bignum if
		// end char if and end ref.eq if are emitted by the caller
		// (emitCharCodePointEqOrElse and emitEqlComparison respectively).
	}

	// Pushes the i64 field of the TYPE_BIGNUM held in the given local.
	private static void emitBignumField(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.writeSignedLeb128(0);
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
		emitStrBuildCall(ctx.writer);
	}

	/**
	 * Emits {@code call FUNC_STR_BUILD}: consumes an {@code (off, len)} pair on the stack
	 * (a linear byte offset + byte length) and pushes the {@code TYPE_STRING} built by
	 * copying {@code linear[off..off+len)} into a fresh {@code $str_bytes} GC array (id =
	 * off). A drop-in replacement for the old two-field {@code struct.new TYPE_STRING}
	 * (which popped the same {@code (offset, length)} pair), so every string/symbol build
	 * moves its bytes onto the GC heap. See
	 * {@link WasmStringRuntimeBuilder#buildStrBuildBody()}.
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrBuildCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_STR_BUILD);
	}

	/**
	 * Emits {@code call FUNC_STR_FRESH}: the same {@code (off, len) -> TYPE_STRING} shape
	 * as {@link #emitStrBuildCall(WasmWriter)}, but the resulting string's id is a fresh
	 * monotonic counter value rather than {@code off}. Every RUNTIME string build uses
	 * this so that reusing the assembly scratch offset (the heap is a stack now) cannot
	 * make two distinct runtime strings / uninterned symbols compare {@code eq}. Interned
	 * names (literals, {@code _intern}'d symbols, t) keep using {@code emitStrBuildCall}.
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrFreshCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_STR_FRESH);
	}

	/**
	 * Emits {@code call FUNC_STR_TO_MEM}: consumes a {@code (str, ptr)} pair (a
	 * {@code TYPE_STRING} ref and a linear byte offset) and pushes the byte count after
	 * copying the string's {@code $str_bytes} array (quotes included) into
	 * {@code linear[ptr..)}. Used where a linear pointer is still required (WASI iovecs,
	 * the reader input scratch, the fetch wire, the host {@code :string} boundary,
	 * intern).
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrToMemCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_STR_TO_MEM);
	}

	/**
	 * Emits {@code call FUNC_WRITE_STR_GC}: consumes a {@code (str, from, to)} triple and
	 * writes bytes {@code [from, to)} of the string value to the current print sink from
	 * its GC array (capture buffer append or stdout). The print path for string values.
	 * @param w the writer for the function body being emitted
	 */
	static void emitWriteStrGcCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR_GC);
	}

	/**
	 * Emits {@code call FUNC_CHARVEC_TO_STR}: replaces a mutable character vector on the
	 * stack with the equivalent quote-framed runtime string; any other value passes
	 * through unchanged. Inserted after the string operand of every string consumer
	 * (char, subseq, string=/-equal, case/trim/concat, write-string, read-from-string,
	 * intern, make-symbol) so a fill-pointered/adjustable character vector behaves as a
	 * string there. See {@link WasmStringRuntimeBuilder#buildCharvecToStrBody()}.
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	static void emitCharvecToStrCall(WasmLispCompiler.Ctx ctx) {
		emitCharvecToStrCall(ctx.writer);
	}

	/**
	 * The raw-{@link WasmWriter} counterpart of
	 * {@link #emitCharvecToStrCall(WasmLispCompiler.Ctx)}, for the runtime builders
	 * ({@code _equal}/{@code _hash}/{@code _print_val}/{@code _princ_val} normalize their
	 * argument at entry through it).
	 * @param w the writer for the function body being emitted
	 */
	static void emitCharvecToStrCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_CHARVEC_TO_STR);
	}

	/**
	 * Emits {@code call FUNC_STR_CHAR_COUNT}: replaces a {@code TYPE_STRING} value on the
	 * stack with the i32 character count of its UTF-8 encoded content (byte count minus
	 * two surrounding quotes, walking one increment per UTF-8 lead byte). Every
	 * character-based length reads through it.
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	static void emitStrCharCountCall(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_STR_CHAR_COUNT);
	}

	/**
	 * Emits {@code call FUNC_STR_CHAR_AT}: consumes a {@code (str, i)} pair (a
	 * {@code TYPE_STRING} and an i32 character index) and pushes the i32 code point of
	 * the {@code i}-th character, walking UTF-8 to find the character then decoding its
	 * 1-4 byte sequence. Every {@code (char s i)}/{@code (schar s i)} lowering routes
	 * through it.
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	static void emitStrCharAtCall(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_STR_CHAR_AT);
	}

	/**
	 * Given a value on the stack that is a {@code TYPE_STRING}, replaces it with its
	 * {@code $str_bytes} data array (field 2), cast to the concrete array type so callers
	 * can {@code array.get_u} / {@code array.len} it. The array holds the same
	 * quote-framed bytes the string's linear representation held, so a byte at old
	 * {@code linear[id+i]} is now {@code array[i]}. Emits
	 * {@code ref.cast TYPE_STRING; struct.get 2; ref.cast
	 * $str_bytes} (the cast to TYPE_STRING is redundant when the value is already a
	 * TYPE_STRING ref, but harmless).
	 * @param ctx the compilation context (its writer receives the instructions)
	 */
	static void emitStrBytesArray(WasmLispCompiler.Ctx ctx) {
		emitStrBytesArray(ctx.writer);
	}

	/**
	 * The raw-{@link WasmWriter} counterpart of
	 * {@link #emitStrBytesArray(WasmLispCompiler.Ctx)}, for the runtime builders that
	 * emit into a {@code WasmWriter} directly. Consumes a {@code TYPE_STRING} ref on the
	 * stack and leaves its {@code $str_bytes} data array (field 2), cast to the concrete
	 * array type so callers can {@code array.get_u} / {@code array.len} it.
	 * @param w the writer for the function body being emitted
	 */
	static void emitStrBytesArray(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(2);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STR_BYTES);
	}

}
