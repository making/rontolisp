package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
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
	private static final List<BoundaryType> KNOWN_PARAM_TYPES = List.of(BoundaryType.S32, BoundaryType.S64,
			BoundaryType.FLOAT, BoundaryType.BOOL, BoundaryType.STRING, BoundaryType.S_EXPR, BoundaryType.BYTES);

	/**
	 * The boundary types the {@code --no-gc} backend may name -- the same directive, a
	 * different vocabulary, because the vocabulary follows the HOUSE INTEGER and that
	 * backend's is {@code i64} rather than {@code i31ref}. So the whole fixed-width
	 * integer family crosses there, guarded in the direction each end can overflow (an
	 * argument leaves the house integer, a result arrives into it; see
	 * {@code .kb/no-gc-scalar-wasm.md}), while {@code :s-expr} and {@code :bytes} -- both
	 * heap objects with no scalar representation -- do not.
	 *
	 * <p>
	 * Which makes it exactly the set of types that have a WIT spelling, and that is not a
	 * coincidence worth restating twice: a WIT world names flat values, and flat values
	 * are what an unboxed scalar model carries. Deriving it from
	 * {@link BoundaryType#witName()} keeps this vocabulary and the one
	 * {@code rontolisp:wit-import} lowers against from drifting apart.
	 */
	static final List<BoundaryType> SCALAR_PARAM_TYPES = java.util.Arrays.stream(BoundaryType.values())
		.filter(type -> type.witName() != null)
		.toList();

	private WasmImportCompiler() {
	}

	/**
	 * A parsed and validated {@code rontolisp:wasm-import} directive.
	 *
	 * @param name the Lisp-visible function name
	 * @param module the WASM import module name ({@code :from}, default {@code "env"})
	 * @param field the WASM import field name ({@code :as}, default the Lisp name)
	 * @param paramTypes the declared parameter type designators, in order
	 * @param paramNames the component-model parameter names ({@code :param-names},
	 * default {@code p0}, {@code p1}, ...): read only by the {@code --no-gc --component}
	 * wrap, whose imported instance type carries them
	 * @param returnType the declared return type designator ({@code :void} when omitted)
	 * @param async whether the host function may suspend ({@code :async t}): the wrapper
	 * then wraps the boxed result in a settled {@code TYPE_P1_FUTURE}, so the call
	 * answers a future that {@code rontolisp:await} resolves. Preview 1 has nothing that
	 * can observe a pending state -- the host call blocks the wasm stack (synchronously,
	 * or suspended through JSPI), so started == settled is the option's contract here
	 */
	record Decl(String name, String module, String field, List<BoundaryType> paramTypes, List<String> paramNames,
			BoundaryType returnType, boolean async) {
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
		return parse(form, KNOWN_PARAM_TYPES, "");
	}

	/**
	 * Parses a directive form against a backend's own import vocabulary.
	 * @param form the directive form
	 * @param accepted the boundary types this backend's wrappers marshal
	 * ({@link #KNOWN_PARAM_TYPES} on wasm-GC, {@link #SCALAR_PARAM_TYPES} under
	 * {@code --no-gc})
	 * @param backend the flag naming that backend in an error message, or {@code ""} for
	 * the default one
	 * @return the parsed declaration
	 * @throws UnsupportedOperationException if the directive is malformed or names a type
	 * designator this backend does not carry
	 */
	static Decl parse(LispCons form, List<BoundaryType> accepted, String backend) {
		WasmImportDirective directive = WasmImportDirective.parse(form);
		List<BoundaryType> params = new ArrayList<>();
		for (String t : directive.paramTypes()) {
			params.add(knownType(t, form, false, accepted, backend));
		}
		BoundaryType returns = directive.returnType() == null ? BoundaryType.VOID
				: knownType(directive.returnType(), form, true, accepted, backend);
		return new Decl(directive.name(), directive.module(), directive.field(), List.copyOf(params),
				directive.paramNames(), returns, directive.async());
	}

	// One designator from the directive, restricted to the import vocabulary. The
	// designator spelling is the shared one, so :int is accepted as the alias of :s32
	// here
	// exactly as it is on the export side. A designator that names a REAL boundary type
	// this backend does not carry is reported as unsupported-here rather than unknown:
	// the two are a different fix, and :s-expr is a valid import type one backend over.
	private static BoundaryType knownType(String designator, LispCons form, boolean result, List<BoundaryType> accepted,
			String backend) {
		BoundaryType type = BoundaryType.forDesignator(designator);
		if (type != null && (accepted.contains(type) || (result && type == BoundaryType.VOID))) {
			return type;
		}
		String takes = " (expected one of " + accepted.stream().map(BoundaryType::designator).toList()
				+ (result ? " or :void)" : ")");
		if (type != null) {
			throw new UnsupportedOperationException(
					"rontolisp:wasm-import type designator " + designator + " is not supported"
							+ (backend.isEmpty() ? "" : " with " + backend) + " in " + form.print() + takes);
		}
		throw new UnsupportedOperationException(
				"Unknown rontolisp:wasm-import type designator " + designator + " in " + form.print() + takes);
	}

	/**
	 * Returns whether the declaration's result is host-written bytes in linear memory --
	 * a {@code :string} and an {@code :s-expr} alike, since both cross as the
	 * {@code (ptr, len)} pair the HOST reserves with the exported {@code __ronto_alloc}
	 * and the wrapper reads back with {@code _str_from_mem} (the {@code :s-expr} one then
	 * hands the text to the embedded reader). Naming only {@code :string} left a module
	 * whose ONLY memory-typed boundary was an {@code :s-expr}-returning import without
	 * the allocator export, so no host could answer it at all -- found by generating the
	 * host glue for one ({@code compiler/HostGlueEmitter}).
	 * @param decl the parsed declaration
	 * @return whether the result is host-written bytes
	 */
	static boolean usesStrFromMem(Decl decl) {
		return decl.returnType() == BoundaryType.STRING || decl.returnType() == BoundaryType.S_EXPR;
	}

	/**
	 * The result types the literal call-site lowering can box without the wrapper's
	 * scratch slots: everything whose boxing reads only the value the host left on the
	 * stack. {@code :s-expr} needs the {@code (ptr,len)} locals the reader is driven
	 * from, {@code :string} the {@code _str_from_mem} index (settled only after every
	 * user body is emitted) and {@code :bytes} the whole caller-passes-the-buffer
	 * bracket, so a site returning one of those keeps the wrapper.
	 */
	private static final List<BoundaryType> LOWERABLE_RESULT_TYPES = List.of(BoundaryType.VOID, BoundaryType.S32,
			BoundaryType.FLOAT, BoundaryType.BOOL);

	/**
	 * The parameter types the literal call-site lowering can push: a {@code :string}
	 * (which is what it stages) plus the scalars, none of which touch linear memory.
	 * {@code :s-expr} would have to print its argument first and {@code :bytes} stages a
	 * runtime vector, so neither is a literal in the sense this lowering means.
	 */
	private static final List<BoundaryType> LOWERABLE_PARAM_TYPES = List.of(BoundaryType.STRING, BoundaryType.S32,
			BoundaryType.FLOAT, BoundaryType.BOOL);

	/**
	 * Whether a declaration's SHAPE admits the literal {@code :string} call-site lowering
	 * -- asked of the directive alone, so the module can reserve {@code _lit_stage}
	 * before any call site is compiled ({@code WasmLispCompiler.emitsLitStage}). Whether
	 * a given SITE takes it also depends on its arguments; see
	 * {@link #compileLiteralImportCall}.
	 * @param decl the parsed declaration
	 * @return whether a site of this import may lower
	 */
	static boolean canLowerLiteralCallSite(Decl decl) {
		return decl.paramTypes().contains(BoundaryType.STRING) && LOWERABLE_PARAM_TYPES.containsAll(decl.paramTypes())
				&& LOWERABLE_RESULT_TYPES.contains(decl.returnType());
	}

	/**
	 * The import ordinal a call to {@code name} encodes in its
	 * {@link #PLACEHOLDER_FUNC_BASE} immediate: the position of its {@code (module,
	 * field)} pair among the DISTINCT pairs the {@code rontolisp:wasm-import}
	 * declarations name, in declaration order.
	 *
	 * <p>
	 * That is the same dedup {@code WasmLispCompiler} builds its core import slots from
	 * -- two wrappers may bind one host function and the component model forbids
	 * importing a {@code (module, field)} twice -- and the wasm-import slots LEAD that
	 * list, so a call site can settle its ordinal long before the slot list exists.
	 * {@code WasmLispCompiler} checks the two answers against each other where it builds
	 * the wrapper bodies, so the two cannot drift apart.
	 * @param decls the module's declarations by Lisp name, in declaration order
	 * @param name the Lisp name called
	 * @return the ordinal, or -1 when the name is not a declared import
	 */
	static int hostImportOrdinal(java.util.Map<String, Decl> decls, String name) {
		java.util.LinkedHashMap<String, Integer> slots = new java.util.LinkedHashMap<>();
		for (Decl decl : decls.values()) {
			slots.putIfAbsent(decl.module() + "\0" + decl.field(), slots.size());
		}
		Decl decl = decls.get(name);
		return decl == null ? -1 : slots.get(decl.module() + "\0" + decl.field());
	}

	/**
	 * Compiles a call to a host import whose every {@code :string} argument is a LITERAL
	 * straight into the host call, bypassing the wrapper -- the whole point being what
	 * the bypass does NOT do.
	 *
	 * <p>
	 * A literal's bytes start in linear memory: the interned data segment put them there.
	 * The host boundary wants bytes in linear memory. The general path nevertheless walks
	 * them byte-by-byte into a GC array ({@code _str_build}) so that the wrapper can walk
	 * them byte-by-byte back out ({@code _str_to_mem}) -- a round trip no optimizer can
	 * see as one, because each half is an ordinary call to a shared helper. Here the same
	 * bytes cross as one {@code memory.copy} inside {@code _lit_stage}, and in a module
	 * whose only string work was this, BOTH helpers and the wrapper itself become
	 * unreachable.
	 *
	 * <p>
	 * It is a COPY, never the data segment's own pointer: identical spellings are
	 * deduplicated into one block that also spells interned symbol names, so handing the
	 * host a pointer INTO the segment would let a write corrupt every other use of that
	 * spelling ({@code .kb/wasm-import.md}, {@code .kb/no-gc-scalar-wasm.md}). What this
	 * saves is the two byte loops and the GC array between them, not the copy.
	 *
	 * <p>
	 * The regions land at fixed deltas off the UN-ADVANCED {@code HEAP_PTR} scratch --
	 * the staging a single memory-typed parameter has always used, under the same
	 * contract (the host reads its memory-typed arguments before it answers). Every
	 * staged length here is a compile-time constant, so the deltas replace the wrapper's
	 * mark/restore bracket, which a call site has no i32 local to hold anyway.
	 * @param name the Lisp name in call position
	 * @param cons the call form
	 * @param ctx the compilation context
	 * @return whether the site was compiled here
	 */
	static boolean compileLiteralImportCall(String name, LispCons cons, WasmLispCompiler.Ctx ctx) {
		Decl decl = ctx.importDecls.get(name);
		if (decl == null || ctx.litStageFuncIndex < 0 || ctx.reentrant || !canLowerLiteralCallSite(decl)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		int numParams = decl.paramTypes().size();
		// A wrong argument count is the generic path's to report, with its message.
		if (parts.size() - 1 != numParams) {
			return false;
		}
		String[] literals = new String[numParams];
		int firstLiteral = -1;
		for (int i = 0; i < numParams; i++) {
			if (decl.paramTypes().get(i) != BoundaryType.STRING) {
				continue;
			}
			if (!(parts.get(i + 1) instanceof am.ik.rontolisp.LispString literal)) {
				return false;
			}
			literals[i] = literal.literal();
			if (firstLiteral < 0) {
				firstLiteral = i;
			}
		}
		int ordinal = hostImportOrdinal(ctx.importDecls, name);
		// Arguments BEFORE the first staged region are pushed as they are evaluated;
		// the ones after it have to be evaluated into temps FIRST, because the regions
		// sit on un-advanced scratch and anything that allocates linear memory while
		// they are live would land on top of them. Source order is preserved either way
		// -- what is skipped over is a literal, which evaluates to nothing observable.
		int[] slots = new int[numParams];
		java.util.Arrays.fill(slots, -1);
		for (int i = 0; i < firstLiteral; i++) {
			WasmExprCompiler.compileExpr(parts.get(i + 1), ctx);
			emitUnboxTop(ctx, decl.paramTypes().get(i));
		}
		for (int i = firstLiteral + 1; i < numParams; i++) {
			if (literals[i] != null) {
				continue;
			}
			WasmExprCompiler.compileExpr(parts.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		if (decl.async()) {
			// The settled future's kind field, under the boxed result (buildWrapperBody).
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(2);
		}
		int delta = 0;
		for (int i = firstLiteral; i < numParams; i++) {
			if (literals[i] == null) {
				emitUnboxParam(ctx, decl.paramTypes().get(i), slots[i]);
				continue;
			}
			WasmLispCompiler.StringTable.StringEntry entry = WasmEmitHelper.internSpelledLiteral(literals[i], ctx);
			// The boundary is the CONTENT: the interned form is quote-framed, exactly as
			// _str_build would have copied it and _str_to_mem written it back.
			int contentOffset = entry.offset() + 1;
			int contentLength = entry.length() - 2;
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(contentOffset);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(contentLength);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(delta);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.litStageFuncIndex);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(contentLength);
			delta += contentLength;
		}
		// The reserved block has to hold this site's whole run at once; every other site
		// lays its own out from the same base, so what it must be is the WIDEST.
		ctx.litStageBytes[0] = Math.max(ctx.litStageBytes[0], delta);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(PLACEHOLDER_FUNC_BASE + ordinal);
		emitBoxResult(ctx, decl.returnType(), -1, -1, false);
		if (decl.async()) {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
		}
		return true;
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
	 * Returns whether the wrapper STAGES its memory-typed parameters
	 * ({@code :string}/{@code :s-expr}) -- an advancing allocation per parameter,
	 * released after the host call -- instead of leaving each at the un-advanced
	 * {@code HEAP_PTR} scratch. True exactly when the declaration has two or more of
	 * them: one region at the scratch has nothing to collide with, but the second write
	 * lands on the first, so the host would see the LAST argument's bytes under every
	 * pointer with the earlier argument's length. A single memory-typed parameter keeps
	 * the scratch (the rule {@code .kb/wasm-gc-strings.md} documents), so every module
	 * that was already correct stays byte-identical.
	 * @param decl the parsed declaration
	 * @return whether the wrapper stages memory-typed parameters
	 */
	static boolean stagesMemoryParams(Decl decl) {
		return memoryParamCount(decl) >= 2;
	}

	private static int memoryParamCount(Decl decl) {
		return (int) decl.paramTypes().stream().filter(WasmImportCompiler::isStagedMemoryType).count();
	}

	private static boolean isStagedMemoryType(BoundaryType type) {
		return type == BoundaryType.STRING || type == BoundaryType.S_EXPR;
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
		// Two or more :string/:s-expr parameters have to hold their linear-memory
		// regions AT THE SAME TIME, across the host call -- so they cannot share the
		// un-advanced scratch emitStringResult writes at (see stagesMemoryParams).
		boolean memStaging = stagesMemoryParams(decl);
		int numMemParams = memStaging ? memoryParamCount(decl) : 0;
		boolean staging = bytesStaging || memStaging;
		ByteArrayOutputStream bodyStream = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter writer = new WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		boolean reentrant = ctx.reentrant;
		// i32 scratch locals, right after the env+param slots and before any
		// (ref null eq) temps handed out by allocTemp:
		// - an :s-expr result needs two, for the (ptr,len) the host returns -- and so
		// does a :string result under --reentrant, whose park block the wrapper frees
		// after boxing;
		// - :bytes staging needs a heap mark (the staged regions are POPPED on return,
		// so a pull-loop caller's arena stays flat -- the finding-2 shape), a (ptr,len)
		// pair per :bytes parameter, and (ptr,cap,n) for a :bytes result. Under
		// --reentrant the regions are park blocks instead (freed, not popped: the pop
		// is an absolute store two interleaved pull loops cannot share) and the mark
		// slot goes unused.
		int sExprTemps = needsReader(decl) || (reentrant && usesStrFromMem(decl)) ? 2 : 0;
		int ptrSlot = numLispParams + 1;
		int markSlot = numLispParams + 1 + sExprTemps;
		int bytesParamBase = markSlot + 1;
		int resultPtrSlot = bytesParamBase + 2 * numBytesParams;
		int resultCapSlot = resultPtrSlot + 1;
		int resultLenSlot = resultPtrSlot + 2;
		// One (ptr,len) pair per staged memory-typed parameter, after the :bytes run so
		// no existing slot moves: the pointer is what the region is released by under
		// --reentrant, the length what the host is handed.
		int memParamBase = resultPtrSlot + (bytesResult ? 3 : 0);
		int numI32Temps = sExprTemps
				+ (staging ? 1 + 2 * numBytesParams + (bytesResult ? 3 : 0) + 2 * numMemParams : 0);
		ctx.nextLocal = numLispParams + 1 + numI32Temps;
		int stagingAllocFuncIndex = reentrant ? ctx.parkAllocFuncIndex : allocFuncIndex;
		if (staging && !reentrant) {
			// mark = HEAP_PTR; every staged buffer below is a bump allocation popped
			// back to this mark on return (a stack discipline, like the host arena
			// API).
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
			ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(markSlot);
		}
		if (bytesStaging) {
			// Stage each :bytes parameter: len = array.len(arg), ptr = alloc(len)
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
				emitAllocInto(ctx, lenSlot, bufPtrSlot, stagingAllocFuncIndex);
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
			// trailing Lisp argument -- and ptr = alloc(cap). The host writes up
			// to cap bytes there and answers the full length. Under --reentrant this is
			// THE cross-park region: the host writes into it while the call is parked,
			// so it must be a park block, which nothing pops out from under it.
			if (bytesResult) {
				emitByteVectorLen(ctx, numLispParams);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(resultCapSlot);
				emitAllocInto(ctx, resultCapSlot, resultPtrSlot, stagingAllocFuncIndex);
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
		int m = 0;
		for (int i = 0; i < numParams; i++) {
			if (decl.paramTypes().get(i) == BoundaryType.BYTES) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(bytesParamBase + 2 * k);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(bytesParamBase + 2 * k + 1);
				k++;
			}
			else if (memStaging && isStagedMemoryType(decl.paramTypes().get(i))) {
				emitStagedMemoryParam(ctx, decl.paramTypes().get(i), i + 1, memParamBase + 2 * m, reentrant);
				m++;
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
		// --reentrant: the host call is the ONE place another export call can run (a
		// suspending import parks this stack), and that call swaps the task global --
		// so save this call's task record into a local (it survives the park) and put
		// it back the moment the call returns, before anything reads a special.
		int taskSave = WasmDynVars.emitTaskSave(ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(PLACEHOLDER_FUNC_BASE + ordinal);
		WasmDynVars.emitTaskRestore(ctx, taskSave);
		if (bytesResult) {
			// n = the host's answer (the value's FULL length); copy min(n, cap) bytes
			// out of the staged region into the caller's vector, pop the heap back to
			// the mark (--reentrant: free the park blocks instead), and answer n
			// exactly (through _int_new, so any i32 length crosses).
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
			if (reentrant) {
				emitParkFrees(ctx, numBytesParams, bytesParamBase, resultPtrSlot, true, numMemParams, memParamBase);
			}
			else {
				emitHeapRestore(ctx, markSlot);
			}
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultLenSlot);
			ctx.writer.write(Instruction.I64_EXTEND_S_I32);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		}
		else {
			emitBoxResult(ctx, decl.returnType(), ptrSlot, strFromMemFuncIndex, reentrant);
			if (staging) {
				// The staged parameter regions are dead once the host call returned (a
				// :string/:s-expr result was already copied out of linear memory by the
				// boxing above), so pop the heap back to the mark (--reentrant: free
				// the park blocks).
				if (reentrant) {
					emitParkFrees(ctx, numBytesParams, bytesParamBase, resultPtrSlot, false, numMemParams,
							memParamBase);
				}
				else {
					emitHeapRestore(ctx, markSlot);
				}
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
		ByteArrayOutputStream entry = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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

	// --reentrant: return every staged park block to the free list -- the pull-loop
	// flatness the absolute pop used to buy, without the absolute store two
	// interleaved pull loops cannot share.
	private static void emitParkFrees(WasmLispCompiler.Ctx ctx, int numBytesParams, int bytesParamBase,
			int resultPtrSlot, boolean bytesResult, int numMemParams, int memParamBase) {
		for (int k = 0; k < numBytesParams; k++) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bytesParamBase + 2 * k);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.parkFreeFuncIndex);
		}
		for (int m = 0; m < numMemParams; m++) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(memParamBase + 2 * m);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.parkFreeFuncIndex);
		}
		if (bytesResult) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultPtrSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.parkFreeFuncIndex);
		}
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

	// Pushes the (ptr,len) of a STAGED :string/:s-expr parameter: the same content
	// pointer and length emitStringResult answers, but over a region that stays live
	// until the host call returns, so several of them coexist.
	//
	// Serialised: the bytes are written at HEAP_PTR as usual and HEAP_PTR is then
	// ADVANCED past them (8-aligned, like __ronto_alloc), making the scratch a stack --
	// the next parameter, and anything else reaching for scratch, starts above this
	// region. The whole run pops back to the wrapper's mark after the call.
	//
	// --reentrant: an absolute pop is what two interleaved extents cannot share, so each
	// region is a park block instead (_park_str_result, the same helper an export result
	// uses), freed by the wrapper after the call. A park block also survives the park
	// itself, which the scratch does not.
	private static void emitStagedMemoryParam(WasmLispCompiler.Ctx ctx, BoundaryType type, int argSlot, int ptrSlot,
			boolean reentrant) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(argSlot);
		if (type == BoundaryType.S_EXPR) {
			// Any Lisp value -> readable s-expression text, then the string path.
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
		}
		int lenSlot = ptrSlot + 1;
		if (reentrant) {
			// _park_str_result answers the CONTENT (ptr,len) -- quotes already stripped
			// -- and the pointer it answers is the one _park_free takes.
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.parkStrResultFuncIndex);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(lenSlot);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(ptrSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(ptrSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(lenSlot);
			return;
		}
		int tmp = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		// ptr = HEAP_PTR (the quoted spelling's base); len = _str_to_mem(str, ptr),
		// which grow-guards [ptr, ptr+len) before writing.
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(ptrSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(ptrSlot);
		WasmEmitHelper.emitStrToMemCall(ctx.writer);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(lenSlot);
		// HEAP_PTR = align8(ptr + len)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(ptrSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(lenSlot);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(7);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(-8);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
		// The boundary is the CONTENT: skip the leading quote, drop both.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(ptrSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(lenSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(2);
		ctx.writer.write(Instruction.I32_SUB);
	}

	// Pushes the host-ABI value(s) of the boxed Lisp argument in the given local slot.
	private static void emitUnboxParam(WasmLispCompiler.Ctx ctx, BoundaryType type, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitUnboxTop(ctx, type);
	}

	// The same conversion over the boxed Lisp value already on the stack: what a call
	// site pushing an argument in place (rather than out of a wrapper's parameter slot)
	// needs.
	private static void emitUnboxTop(WasmLispCompiler.Ctx ctx, BoundaryType type) {
		switch (type) {
			case S32 -> WasmEmitHelper.castI31GetS(ctx);
			// Any exact integer (an i31 or the boxed i64 lane), exactly; a float
			// truncates, as the export side's result does.
			case S64 -> WasmExportCompiler.emitWideIntResult(ctx, true);
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
	// --reentrant: a :string/:s-expr result's (ptr,len) is a park block the HOST
	// allocated (__ronto_park_alloc) -- a plain __ronto_alloc region would leak, since
	// the synchronous bracket that used to pop it closes before the host's answer
	// exists -- and the wrapper frees it here, after copying the bytes out.
	private static void emitBoxResult(WasmLispCompiler.Ctx ctx, BoundaryType type, int ptrSlot, int strFromMemFuncIndex,
			boolean reentrant) {
		switch (type) {
			case S32 -> ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			// The whole i64 exactly: an i31 when it fits, else the boxed exact integer.
			case S64 -> {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
			}
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
				if (reentrant) {
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(ptrSlot + 1); // len
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(ptrSlot); // ptr
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(ptrSlot);
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(ptrSlot + 1);
				}
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(strFromMemFuncIndex);
				if (reentrant) {
					emitParkFreeOf(ctx, ptrSlot);
				}
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
				if (reentrant) {
					emitParkFreeOf(ctx, ptrSlot);
				}
			}
			default -> throw new UnsupportedOperationException("Unknown rontolisp:wasm-import type: " + type);
		}
	}

	// _park_free(local[ptrSlot]) -- stack-neutral (the boxed value stays on top).
	private static void emitParkFreeOf(WasmLispCompiler.Ctx ctx, int ptrSlot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(ptrSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(ctx.parkFreeFuncIndex);
	}

}
