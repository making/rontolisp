package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.rontolisp.compiler.WasmImportDirective;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Parses and compiles {@code (rontolisp:wasm-import 'name :from "module" :as "field"
 * :params '(...) :returns ...)} directives into Lisp-callable host functions.
 *
 * <p>
 * The directive declares a function imported from the host (e.g. JavaScript in a browser,
 * or another module preloaded into wasmtime) with a numeric / memory boundary signature,
 * and makes it callable from Lisp under {@code name} exactly like a top-level
 * {@code defun} (including {@code #'name}, {@code funcall} and {@code eval}). The
 * compiler registers a wrapper function with the internal defun calling convention whose
 * body unboxes each Lisp argument to the host ABI, calls the imported function, and boxes
 * the result back into the internal {@code (ref null eq)} representation.
 *
 * <p>
 * The type designators are shared with {@link WasmExportCompiler} ({@code :int},
 * {@code :float}, {@code :bool}, {@code :string}, {@code :s-expr}, {@code :bytes}; a
 * {@code :returns} of {@code :void}/nil returns {@code nil} to Lisp). A
 * {@code :string}/{@code :s-expr} parameter reaches the host as {@code (ptr,len)} into
 * linear memory; a {@code :string} result must be written into linear memory by the host
 * (via the exported {@code __ronto_alloc}) and returned as {@code (ptr,len)}; a
 * {@code :s-expr} result is parsed with the embedded reader. A {@code :bytes} parameter
 * is an {@code (unsigned-byte 8)} vector staged as raw {@code (ptr,len)} bytes (no UTF-8
 * encode); a {@code :bytes} RESULT follows the caller-passes-the-buffer {@code read(2)}
 * shape -- the Lisp signature gains one trailing buffer-vector parameter, the host is
 * called with a trailing {@code (ptr,cap)} pair ("write up to cap bytes at ptr") and
 * answers the value's FULL length, which the call returns; the wrapper copies
 * {@code min(n,cap)} bytes into the caller's vector and pops its staged regions, so a
 * pull loop over one reused buffer keeps linear memory flat.
 *
 * <p>
 * Because every {@code FUNC_*} function index is a fixed compile-time constant, the
 * wrapper body cannot know the imported function's final index (imports must precede all
 * defined functions in the WASM index space). It instead calls a placeholder index
 * ({@link #PLACEHOLDER_FUNC_BASE} + import ordinal); the
 * {@link am.ik.wasm.WasmImportInjector} post-pass then prepends the import entries and
 * renumbers every function reference in one sweep.
 */
final class WasmImportCompiler {

	/**
	 * Base of the placeholder function indices emitted for calls to imported functions,
	 * far beyond any real function count. The {@link am.ik.wasm.WasmImportInjector}
	 * post-pass maps {@code PLACEHOLDER_FUNC_BASE + j} to import index {@code j} and
	 * shifts every other function reference.
	 */
	static final int PLACEHOLDER_FUNC_BASE = 1 << 27;

	/**
	 * The boundary types a host import may name. Deliberately narrower than the export
	 * side's: the import wrappers marshal only the vocabulary they always have, and
	 * widening them to the rest of the {@link BoundaryType} family is its own change (an
	 * import's inbound value is a host promise, so the same "carries it exactly or traps"
	 * rule has to be worked through for every WASI import a program already makes). See
	 * {@code .kb/wit.md}. {@code :bytes} is the byte-transfer type: an
	 * {@code (unsigned-byte 8)} vector crossing as raw bytes with no UTF-8 decode -- a
	 * {@code :string} result's non-validating decoder corrupts arbitrary bytes, so binary
	 * needs its own designator, not care at the call site.
	 */
	private static final List<BoundaryType> KNOWN_PARAM_TYPES = List.of(BoundaryType.S32, BoundaryType.FLOAT,
			BoundaryType.BOOL, BoundaryType.STRING, BoundaryType.S_EXPR, BoundaryType.BYTES);

	private WasmImportCompiler() {
	}

	/**
	 * A parsed and validated {@code rontolisp:wasm-import} directive.
	 *
	 * @param name the Lisp-visible function name
	 * @param module the WASM import module name ({@code :from}, default {@code "env"})
	 * @param field the WASM import field name ({@code :as}, default the Lisp name)
	 * @param paramTypes the declared parameter type designators, in order
	 * @param returnType the declared return type designator ({@code :void} when omitted)
	 * @param async whether the host function may suspend ({@code :async t}): the wrapper
	 * then wraps the boxed result in a settled {@code TYPE_P1_FUTURE}, so the call
	 * answers a future that {@code rontolisp:await} resolves. Preview 1 has nothing that
	 * can observe a pending state -- the host call blocks the wasm stack (synchronously,
	 * or suspended through JSPI), so started == settled is the option's contract here
	 */
	record Decl(String name, String module, String field, List<BoundaryType> paramTypes, BoundaryType returnType,
			boolean async) {
	}

	/**
	 * Returns whether the given form is a {@code (rontolisp:wasm-import ...)} directive.
	 * @param form the top-level form
	 * @return {@code true} if it is a rontolisp:wasm-import directive
	 */
	static boolean isImportForm(am.ik.rontolisp.LispVal form) {
		return WasmImportDirective.isImportForm(form);
	}

	/**
	 * Parses a directive form and validates its type designators.
	 * @param form the directive form
	 * @return the parsed declaration
	 * @throws UnsupportedOperationException if the directive is malformed or names an
	 * unknown type designator
	 */
	static Decl parse(LispCons form) {
		WasmImportDirective directive = WasmImportDirective.parse(form);
		List<BoundaryType> params = new ArrayList<>();
		for (String t : directive.paramTypes()) {
			params.add(knownType(t, form, false));
		}
		BoundaryType returns = directive.returnType() == null ? BoundaryType.VOID
				: knownType(directive.returnType(), form, true);
		return new Decl(directive.name(), directive.module(), directive.field(), List.copyOf(params), returns,
				directive.async());
	}

	// One designator from the directive, restricted to the import vocabulary. The
	// designator spelling is the shared one, so :int is accepted as the alias of :s32
	// here
	// exactly as it is on the export side.
	private static BoundaryType knownType(String designator, LispCons form, boolean result) {
		BoundaryType type = BoundaryType.forDesignator(designator);
		if (type != null && (KNOWN_PARAM_TYPES.contains(type) || (result && type == BoundaryType.VOID))) {
			return type;
		}
		throw new UnsupportedOperationException("Unknown rontolisp:wasm-import type designator " + designator + " in "
				+ form.print() + " (expected one of "
				+ KNOWN_PARAM_TYPES.stream().map(BoundaryType::designator).toList() + (result ? " or :void)" : ")"));
	}

	/**
	 * Returns whether the declaration's result is host-written bytes in linear memory.
	 */
	static boolean usesStrFromMem(Decl decl) {
		return decl.returnType() == BoundaryType.STRING;
	}

	/** Returns whether the declaration's result is parsed with the embedded reader. */
	static boolean needsReader(Decl decl) {
		return decl.returnType() == BoundaryType.S_EXPR;
	}

	/** Returns whether any declared type is the {@code :bytes} boundary type. */
	static boolean usesBytes(Decl decl) {
		return decl.returnType() == BoundaryType.BYTES || decl.paramTypes().contains(BoundaryType.BYTES);
	}

	/**
	 * The arity of the Lisp-visible function the declaration defines. A {@code :bytes}
	 * RESULT adds one trailing parameter -- the {@code (unsigned-byte 8)} vector the
	 * caller passes as the receive buffer (the caller-passes-the-buffer {@code read(2)}
	 * shape); the call answers the value's full byte length, so an undersized buffer is a
	 * retry, not a truncation.
	 * @param decl the parsed declaration
	 * @return the Lisp-side parameter count
	 */
	static int lispArity(Decl decl) {
		return decl.paramTypes().size() + (decl.returnType() == BoundaryType.BYTES ? 1 : 0);
	}

	/** Returns the WASM parameter types of the imported function's host signature. */
	static Type[] hostParamTypes(Decl decl) {
		List<Type> types = new ArrayList<>();
		for (BoundaryType t : decl.paramTypes()) {
			WasmExportCompiler.appendWasmTypes(types, t);
		}
		// A :bytes RESULT is caller-buffered: the wrapper passes a trailing (ptr,cap)
		// pair -- "write up to cap bytes at ptr" -- and the host answers the full length.
		if (decl.returnType() == BoundaryType.BYTES) {
			types.add(Type.I32);
			types.add(Type.I32);
		}
		return types.toArray(new Type[0]);
	}

	/**
	 * Returns the WASM result types of the imported function's host signature (empty for
	 * a void result).
	 */
	static Type[] hostResultTypes(Decl decl) {
		if (decl.returnType() == BoundaryType.VOID) {
			return new Type[0];
		}
		if (decl.returnType() == BoundaryType.BYTES) {
			return new Type[] { Type.I32 };
		}
		List<Type> types = new ArrayList<>();
		WasmExportCompiler.appendWasmTypes(types, decl.returnType());
		return types.toArray(new Type[0]);
	}

	/**
	 * Builds the complete code entry (local declarations + body) of the Lisp-callable
	 * wrapper. The wrapper has the internal defun signature (slot 0 = unused closure env,
	 * slots 1..N = boxed arguments, result {@code (ref null eq)}): it unboxes each
	 * argument to the host ABI, calls the imported function through its placeholder
	 * index, and boxes the result.
	 * @param ctxBuilder the shared context builder (for the string table etc.)
	 * @param decl the parsed declaration
	 * @param ordinal the import's ordinal (0-based declaration order)
	 * @param strFromMemFuncIndex the function index of the {@code _str_from_mem} helper
	 * (or {@code -1} when no {@code :string} result is present)
	 * @param allocFuncIndex the function index of {@code __ronto_alloc} (or {@code -1}
	 * when no {@code :bytes} type is present)
	 * @param bytesCopyFuncIndex the function index of the {@code _bytes_copy} helper (or
	 * {@code -1} when no {@code :bytes} type is present)
	 * @param bytesFillFuncIndex the function index of the {@code _bytes_fill} helper (or
	 * {@code -1} when no {@code :bytes} type is present)
	 * @return the code entry bytes
	 */
	static byte[] buildWrapperBody(WasmLispCompiler.Ctx.Builder ctxBuilder, Decl decl, int ordinal,
			int strFromMemFuncIndex, int allocFuncIndex, int bytesCopyFuncIndex, int bytesFillFuncIndex) {
		int numParams = decl.paramTypes().size();
		int numLispParams = lispArity(decl);
		boolean bytesResult = decl.returnType() == BoundaryType.BYTES;
		int numBytesParams = (int) decl.paramTypes().stream().filter(t -> t == BoundaryType.BYTES).count();
		boolean bytesStaging = bytesResult || numBytesParams > 0;
		// i32 scratch locals, right after the env+param slots and before any
		// (ref null eq) temps handed out by allocTemp:
		// - an :s-expr result needs two, for the (ptr,len) the host returns;
		// - :bytes staging needs a heap mark (the staged regions are POPPED on return,
		// so a pull-loop caller's arena stays flat -- the finding-2 shape), a (ptr,len)
		// pair per :bytes parameter, and (ptr,cap,n) for a :bytes result.
		int sExprTemps = needsReader(decl) ? 2 : 0;
		int ptrSlot = numLispParams + 1;
		int markSlot = numLispParams + 1 + sExprTemps;
		int bytesParamBase = markSlot + 1;
		int resultPtrSlot = bytesParamBase + 2 * numBytesParams;
		int resultCapSlot = resultPtrSlot + 1;
		int resultLenSlot = resultPtrSlot + 2;
		int numI32Temps = sExprTemps + (bytesStaging ? 1 + 2 * numBytesParams + (bytesResult ? 3 : 0) : 0);
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		WasmWriter writer = new WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		ctx.nextLocal = numLispParams + 1 + numI32Temps;
		if (bytesStaging) {
			// mark = HEAP_PTR; every staged buffer below is a bump allocation popped
			// back to this mark on return (a stack discipline, like the host arena API).
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
			ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(markSlot);
			// Stage each :bytes parameter: len = array.len(arg), ptr = __ronto_alloc(len)
			// (grow-guarded), then copy the vector's raw bytes into [ptr, ptr+len). The
			// ref.cast inside the length read traps on a non-byte-vector argument --
			// exact-or-trap, like every other boundary type.
			int k = 0;
			for (int i = 0; i < numParams; i++) {
				if (decl.paramTypes().get(i) != BoundaryType.BYTES) {
					continue;
				}
				int lenSlot = bytesParamBase + 2 * k + 1;
				int bufPtrSlot = bytesParamBase + 2 * k;
				emitByteVectorLen(ctx, i + 1);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(lenSlot);
				emitAllocInto(ctx, lenSlot, bufPtrSlot, allocFuncIndex);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(i + 1);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(bufPtrSlot);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(lenSlot);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(bytesCopyFuncIndex);
				ctx.writer.write(Instruction.DROP);
				k++;
			}
			// Stage the :bytes result's receive region: cap = array.len(buffer) -- the
			// trailing Lisp argument -- and ptr = __ronto_alloc(cap). The host writes up
			// to cap bytes there and answers the full length.
			if (bytesResult) {
				emitByteVectorLen(ctx, numLispParams);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(resultCapSlot);
				emitAllocInto(ctx, resultCapSlot, resultPtrSlot, allocFuncIndex);
			}
		}
		if (decl.async()) {
			// The future's kind field goes under the boxed result: the wrapper builds the
			// settled (kind 2) TYPE_P1_FUTURE the moment the host call returns -- on this
			// backend the call blocks the wasm stack (synchronously, or suspended through
			// JSPI), so the value is ready when the wrapper resumes and started ==
			// settled
			// is exactly what the struct says.
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(2);
		}
		int k = 0;
		for (int i = 0; i < numParams; i++) {
			if (decl.paramTypes().get(i) == BoundaryType.BYTES) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(bytesParamBase + 2 * k);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(bytesParamBase + 2 * k + 1);
				k++;
			}
			else {
				emitUnboxParam(ctx, decl.paramTypes().get(i), i + 1);
			}
		}
		if (bytesResult) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultPtrSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultCapSlot);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(PLACEHOLDER_FUNC_BASE + ordinal);
		if (bytesResult) {
			// n = the host's answer (the value's FULL length); copy min(n, cap) bytes
			// out of the staged region into the caller's vector, pop the heap back to
			// the mark, and answer n exactly (through _int_new, so any i32 length
			// crosses).
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultLenSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(numLispParams);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultPtrSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultLenSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(bytesFillFuncIndex);
			ctx.writer.write(Instruction.DROP);
			emitHeapRestore(ctx, markSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultLenSlot);
			ctx.writer.write(Instruction.I64_EXTEND_S_I32);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		}
		else {
			emitBoxResult(ctx, decl.returnType(), ptrSlot, strFromMemFuncIndex);
			if (bytesStaging) {
				// The staged parameter regions are dead once the host call returned (a
				// :string/:s-expr result was already copied out of linear memory by the
				// boxing above), so pop the heap back to the mark.
				emitHeapRestore(ctx, markSlot);
			}
		}
		if (decl.async()) {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
		}
		ctx.writer.write(Instruction.END);
		// Local declarations: the i32 scratch run (when present), then the
		// (ref null eq) temps allocated by allocTemp during unboxing.
		int numEqTemps = ctx.nextLocal - (numLispParams + 1 + numI32Temps);
		ByteArrayOutputStream entry = new ByteArrayOutputStream();
		WasmWriter entryWriter = new WasmWriter(entry);
		int groups = (numI32Temps > 0 ? 1 : 0) + (numEqTemps > 0 ? 1 : 0);
		entryWriter.write(groups);
		if (numI32Temps > 0) {
			entryWriter.writeUnsignedLeb128(numI32Temps);
			entryWriter.write(Type.I32);
		}
		if (numEqTemps > 0) {
			entryWriter.writeUnsignedLeb128(numEqTemps);
			entryWriter.writeRefType(true, Type.EQ.code());
		}
		entryWriter.write((Object) bodyStream.toByteArray());
		return entry.toByteArray();
	}

	// Pushes array.len of the (unsigned-byte 8) vector in the given local slot; the
	// ref.cast traps on any other value (the boundary's exact-or-trap rule).
	private static void emitByteVectorLen(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

	// local[ptrSlot] = __ronto_alloc(local[sizeSlot]) -- a grow-guarded bump allocation.
	private static void emitAllocInto(WasmLispCompiler.Ctx ctx, int sizeSlot, int ptrSlot, int allocFuncIndex) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(sizeSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(allocFuncIndex);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(ptrSlot);
	}

	// HEAP_PTR = local[markSlot]: pops the wrapper's staged regions. A plain store is
	// safe here -- nothing between the mark and this restore can intern a symbol (the
	// only writer of the permanent low region), unlike the exported
	// __ronto_alloc_reset, which must clamp to the intern high-water mark.
	private static void emitHeapRestore(WasmLispCompiler.Ctx ctx, int markSlot) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(markSlot);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	// Pushes the host-ABI value(s) of the boxed Lisp argument in the given local slot.
	private static void emitUnboxParam(WasmLispCompiler.Ctx ctx, BoundaryType type, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		switch (type) {
			case S32 -> WasmEmitHelper.castI31GetS(ctx);
			// Accepts an int, ratio or float Lisp value (numeric contagion like the
			// arithmetic built-ins).
			case FLOAT -> WasmEmitHelper.castFloatGetF64(ctx);
			case BOOL -> {
				// nil -> 0, anything else -> 1
				ctx.writer.write(Instruction.REF_IS_NULL);
				ctx.writer.write(Instruction.I32_EQZ);
			}
			// A Lisp string -> (content ptr, content len) into linear memory.
			case STRING -> WasmExportCompiler.emitStringResult(ctx);
			// Any Lisp value -> readable s-expression text -> (ptr, len).
			case S_EXPR -> {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
				WasmExportCompiler.emitStringResult(ctx);
			}
			default -> throw new UnsupportedOperationException("Unknown rontolisp:wasm-import type: " + type);
		}
	}

	// Boxes the host call's result (already on the stack) into (ref null eq).
	private static void emitBoxResult(WasmLispCompiler.Ctx ctx, BoundaryType type, int ptrSlot,
			int strFromMemFuncIndex) {
		switch (type) {
			case S32 -> ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			case FLOAT -> {
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			}
			case BOOL -> WasmEmitHelper.emitBoolFromI32(ctx);
			case VOID -> {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			// (ptr,len) the host wrote into linear memory -> a fresh Lisp string.
			case STRING -> {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(strFromMemFuncIndex);
			}
			// (ptr,len) of s-expression text -> parse via the embedded reader.
			case S_EXPR -> {
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(ptrSlot + 1); // len
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(ptrSlot); // ptr
				WasmExportCompiler.storeWord(ctx, WasmLispCompiler.READ_CURSOR_ADDR, ptrSlot, false);
				WasmExportCompiler.storeWord(ctx, WasmLispCompiler.READ_END_ADDR, ptrSlot, true);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
			}
			default -> throw new UnsupportedOperationException("Unknown rontolisp:wasm-import type: " + type);
		}
	}

}
