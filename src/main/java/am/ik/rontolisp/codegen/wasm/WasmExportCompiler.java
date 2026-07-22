package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Parses and compiles {@code (rontolisp:wasm-export 'name :params '(...) :returns ...)}
 * directives into host-callable export wrapper functions.
 *
 * <p>
 * The directive declares the WASM-boundary types of an existing top-level {@code defun}
 * so the compiler can generate a thin wrapper with a host-friendly numeric / memory
 * signature. The wrapper boxes each argument into the internal {@code (ref null eq)}
 * representation, supplies the unused closure environment ({@code ref.null eq}), calls
 * the real function, and unboxes the result. Without such a wrapper a {@code defun}
 * cannot be invoked from a host (e.g. {@code wasmtime --invoke} or JavaScript), because
 * every argument and result is a GC reference the host cannot construct.
 *
 * <p>
 * Supported type designators and their boundary representations:
 * <ul>
 * <li>{@code :int} -- {@code i32} (boxed as {@code i31ref}; 31-bit signed range)</li>
 * <li>{@code :long} -- {@code i64} ({@code --no-gc} only; matches the scalar backend's
 * internal {@code i64} integer representation, so the full 2^63 range crosses the
 * boundary with no {@code wrap}/{@code extend})</li>
 * <li>{@code :float} -- {@code f64} (boxed as a float struct)</li>
 * <li>{@code :bool} -- {@code i32} (0 = nil, non-zero = the symbol {@code t})</li>
 * <li>{@code :string} -- {@code (ptr,len)} bytes in linear memory</li>
 * <li>{@code :s-expr} -- {@code (ptr,len)} s-expression text in linear memory</li>
 * </ul>
 *
 * <p>
 * Scalar designators ({@code :int}/{@code :float}/{@code :bool}) yield a pure numeric
 * signature callable straight from {@code wasmtime --invoke}. The memory-backed
 * {@code :string} / {@code :s-expr} designators pass {@code (ptr,len)} through linear
 * memory and need a host that can read/write it (e.g. JavaScript), using the exported
 * {@code __ronto_alloc} bump allocator to reserve input buffers.
 */
final class WasmExportCompiler {

	static final String T_INT = ":INT";

	/**
	 * A 64-bit signed integer ({@code i64}). Supported by the {@code --no-gc} scalar
	 * backend only (whose internal integer representation is {@code i64}); the GC backend
	 * rejects it, since its integers are {@code i31ref}.
	 */
	static final String T_LONG = ":LONG";

	static final String T_FLOAT = ":FLOAT";

	static final String T_BOOL = ":BOOL";

	static final String T_STRING = ":STRING";

	static final String T_S_EXPR = ":S-EXPR";

	/**
	 * Internal sentinel for a void result: the wrapper discards the Lisp return value and
	 * has no WASM result. Selected when {@code :returns} is omitted, or given as
	 * {@code nil}, {@code '()} or {@code :void} (the reader upcases every source keyword,
	 * so the internal canonical is the upcased spelling {@code :VOID}).
	 */
	static final String T_VOID = ":VOID";

	private static final List<String> KNOWN_TYPES = List.of(T_INT, T_LONG, T_FLOAT, T_BOOL, T_STRING, T_S_EXPR);

	/**
	 * The component-model {@code label} grammar (lower-kebab-case words) a component
	 * export name must match; a Lisp name outside it (e.g. one containing {@code *} or
	 * {@code %}) must be renamed with {@code :as}. Shared by the GC
	 * ({@code WasmLispCompiler}) and non-GC ({@code NoGcWasmCompiler})
	 * {@code --component} paths.
	 */
	static final java.util.regex.Pattern COMPONENT_EXPORT_NAME = java.util.regex.Pattern
		.compile("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*");

	private WasmExportCompiler() {
	}

	/**
	 * A parsed {@code rontolisp:wasm-export} directive.
	 *
	 * @param name the Lisp function name (an existing top-level defun)
	 * @param exportName the WASM export name ({@code :as} alias, default the Lisp name)
	 * @param paramTypes the declared parameter type designators, in order
	 * @param paramNames the component-model parameter names, in order
	 * ({@code :param-names}, default {@code p0}, {@code p1}, ...): the labels the
	 * {@code --component} lift encodes into the export's function type, and therefore the
	 * names {@code --emit-wit} and any binding generator show. Ignored on the core-export
	 * (Preview 1 / {@code --no-wasi}) path, where a WASM parameter has no name.
	 * {@code rontolisp:wit-export} fills these in from the WIT world, which is why an
	 * implemented world round-trips with its own parameter names intact
	 * @param returnType the declared return type designator
	 * @param async whether the {@code --component} lift uses an <strong>async</strong>
	 * function type ({@code :async t}): the export runs as a stackful async task, so I/O
	 * inside it (print, fetch, ...) blocks cooperatively instead of trapping. Only
	 * meaningful on the GC {@code --component} (non-serve) path; ignored on Preview 1 /
	 * {@code --no-wasi} (core exports whose I/O the host provides directly) and rejected
	 * under {@code --no-gc --component} (the adapter-free reactor has no async machinery)
	 */
	record Decl(String name, String exportName, List<String> paramTypes, List<String> paramNames, String returnType,
			boolean async) {
	}

	/**
	 * The default component-model parameter names of an export: {@code p0}, {@code p1},
	 * ...
	 */
	static List<String> defaultParamNames(int count) {
		List<String> names = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			names.add("p" + i);
		}
		return List.copyOf(names);
	}

	/**
	 * Returns whether the given form is a {@code (rontolisp:wasm-export ...)} directive.
	 * @param form the top-level form
	 * @return {@code true} if it is a rontolisp:wasm-export directive
	 */
	static boolean isExportForm(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			var qn = am.ik.rontolisp.PackageRegistry.splitQualified(sym.name());
			return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.WASM_EXPORT.equals(qn.member());
		}
		return false;
	}

	/**
	 * Parses a {@code (rontolisp:wasm-export 'name :as "alias" :params '(...) :returns
	 * ...)} directive (in the canonical post-resolution shape
	 * {@code (rontolisp:wasm-export (quote name) :as "alias" :params (quote (...))
	 * :returns :type)}).
	 * @param form the directive form
	 * @return the parsed declaration
	 * @throws UnsupportedOperationException if the directive is malformed or names an
	 * unknown type designator
	 */
	static Decl parse(LispCons form) {
		List<LispVal> items = form.toList();
		if (items.size() < 2) {
			throw new UnsupportedOperationException("Malformed rontolisp:wasm-export: " + form.print());
		}
		String name = quotedSymbolName(items.get(1));
		String exportName = null;
		List<String> params = null;
		List<String> paramNames = null;
		String returns = null;
		boolean async = false;
		int i = 2;
		while (i < items.size()) {
			String keyword = keywordName(items.get(i), form);
			if (i + 1 >= items.size()) {
				throw new UnsupportedOperationException("Missing value for " + keyword + " in " + form.print());
			}
			LispVal value = items.get(i + 1);
			switch (keyword) {
				case ":AS" -> exportName = exportAlias(value, form);
				case ":PARAMS" -> params = quotedTypeList(value, form);
				case ":PARAM-NAMES" -> paramNames = quotedNameList(value, form);
				case ":RETURNS" -> returns = returnDesignator(value, form);
				case ":ASYNC" -> async = booleanOption(value, form);
				default -> throw new UnsupportedOperationException(
						"Unknown rontolisp:wasm-export option " + keyword + " in " + form.print());
			}
			i += 2;
		}
		List<String> types = params == null ? List.of() : params;
		if (paramNames != null && paramNames.size() != types.size()) {
			throw new UnsupportedOperationException("rontolisp:wasm-export :param-names has " + paramNames.size()
					+ " name(s) but :params has " + types.size() + " type(s) in " + form.print());
		}
		// Omitted :returns (like nil / '() / :void) means a void result.
		return new Decl(name, exportName == null ? unqualifiedMember(name) : exportName, types,
				paramNames == null ? defaultParamNames(types.size()) : paramNames, returns == null ? T_VOID : returns,
				async);
	}

	// A :param-names value is a quoted list of names (symbols or strings), each a valid
	// component-model label -- they become the parameter labels of the lifted component
	// function type.
	private static List<String> quotedNameList(LispVal value, LispCons form) {
		if (value instanceof am.ik.rontolisp.LispNil) {
			return List.of();
		}
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest) {
			List<String> result = new ArrayList<>();
			if (rest.car() instanceof LispCons list) {
				for (LispVal element : list.toList()) {
					result.add(paramName(element, form));
				}
			}
			else if (!(rest.car() instanceof am.ik.rontolisp.LispNil)) {
				throw new UnsupportedOperationException(
						"rontolisp:wasm-export :param-names expects a list in " + form.print());
			}
			return List.copyOf(result);
		}
		throw new UnsupportedOperationException(
				"rontolisp:wasm-export :param-names expects a quoted list in " + form.print());
	}

	private static String paramName(LispVal value, LispCons form) {
		String name = switch (value) {
			case am.ik.rontolisp.LispString str -> str.value();
			case LispSymbol sym when !sym.isKeyword() -> unqualifiedMember(sym.name());
			default -> throw new UnsupportedOperationException(
					"rontolisp:wasm-export :param-names expects symbols or strings in " + form.print() + ", got: "
							+ value.print());
		};
		if (!COMPONENT_EXPORT_NAME.matcher(name).matches()) {
			throw new UnsupportedOperationException("rontolisp:wasm-export :param-names entry '" + name
					+ "' is not a valid component-model parameter name (lower-kebab-case words) in " + form.print());
		}
		return name;
	}

	// The host-facing default export name is the symbol's bare member name, lowercased:
	// the reader upcases Lisp symbols while component-model labels are lower-kebab, so
	// the derivation maps DRAW-LINE back to "draw-line" (a package qualifier -- pkg:name
	// from a directive inside a user package -- is Lisp-side spelling only).
	private static String unqualifiedMember(String name) {
		var qn = am.ik.rontolisp.PackageRegistry.splitQualified(name);
		return (qn == null ? name : qn.member()).toLowerCase(java.util.Locale.ROOT);
	}

	// An :async value is the literal t or nil (leniently also a quoted one).
	private static boolean booleanOption(LispVal value, LispCons form) {
		LispVal v = value;
		if (v instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest) {
			v = rest.car();
		}
		if (v instanceof am.ik.rontolisp.LispNil) {
			return false;
		}
		if (v instanceof am.ik.rontolisp.LispTrue || (v instanceof LispSymbol sym && "t".equals(sym.name()))) {
			return true;
		}
		throw new UnsupportedOperationException(
				"rontolisp:wasm-export :async expects t or nil in " + form.print() + ", got: " + value.print());
	}

	// An :as value is a string literal (or, leniently, a quoted symbol) naming the WASM
	// export.
	private static String exportAlias(LispVal value, LispCons form) {
		if (value instanceof am.ik.rontolisp.LispString str) {
			return str.value();
		}
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			// A quoted-symbol alias lowercases like the default derivation (the reader
			// upcases symbols; host-facing names are lowercase); a string is verbatim.
			return name.name().toLowerCase(java.util.Locale.ROOT);
		}
		throw new UnsupportedOperationException(
				"rontolisp:wasm-export :as expects a string in " + form.print() + ", got: " + value.print());
	}

	/** Returns the number of WASM parameter slots a declaration occupies. */
	static int paramSlotCount(Decl decl) {
		int slots = 0;
		for (String t : decl.paramTypes()) {
			slots += slotsForType(t);
		}
		return slots;
	}

	/**
	 * Returns whether any declared type is memory-backed
	 * ({@code :string}/{@code :s-expr}).
	 */
	static boolean usesMemory(Decl decl) {
		if (isMemoryType(decl.returnType())) {
			return true;
		}
		for (String t : decl.paramTypes()) {
			if (isMemoryType(t)) {
				return true;
			}
		}
		return false;
	}

	/** Returns the WASM parameter types for the wrapper signature. */
	static Type[] paramWasmTypes(Decl decl) {
		List<Type> types = new ArrayList<>();
		for (String t : decl.paramTypes()) {
			appendWasmTypes(types, t);
		}
		return types.toArray(new Type[0]);
	}

	/**
	 * Returns the WASM result types for the wrapper signature (empty for a void result).
	 */
	static Type[] resultWasmTypes(Decl decl) {
		if (T_VOID.equals(decl.returnType())) {
			return new Type[0];
		}
		List<Type> types = new ArrayList<>();
		appendWasmTypes(types, decl.returnType());
		return types.toArray(new Type[0]);
	}

	/**
	 * Returns whether this export is the serve-mode {@code handle} entry, whose
	 * CALLBACK-lifted core signature carries a trailing {@code i32} packed callback code
	 * ({@code EXIT} = 0; the response itself is delivered by {@code canon task.return}
	 * mid-task).
	 * @param serve whether the compilation targets a serve component
	 * @param decl the parsed declaration
	 * @return true for the callback-lifted handle wrapper
	 */
	static boolean isServeHandle(boolean serve, Decl decl) {
		return serve && "handle".equals(decl.exportName());
	}

	/**
	 * Whether the export is CALLBACK-lifted: the serve-mode {@code handle}, or a
	 * test-designated export. Its wrapper returns the packed callback code (EXIT when the
	 * target completed -- the result went out through {@code task.return} -- or
	 * {@code WAIT | (set << 4)} when it suspended, turning the call into a live callback
	 * task whose events arrive through {@code _async_cb}).
	 * @param ctx the compilation context
	 * @param decl the parsed declaration
	 * @return true for a callback-lifted export wrapper
	 */
	static boolean isCallbackExport(WasmLispCompiler.Ctx ctx, Decl decl) {
		return isServeHandle(ctx.serve, decl) || ctx.callbackExports.contains(decl.exportName());
	}

	/**
	 * Emits the wrapper body (terminated by {@code end}) into {@code ctx.writer}. The
	 * body boxes each parameter, calls the target function and unboxes the result.
	 * @param ctx the compilation context (its writer receives the instructions)
	 * @param decl the parsed declaration
	 * @param targetFuncIndex the WASM function index of the exported defun
	 * @param strFromMemFuncIndex the function index of the {@code _str_from_mem} helper
	 * (or {@code -1} if no {@code :string} parameter is present)
	 */
	static void emitBody(WasmLispCompiler.Ctx ctx, Decl decl, int targetFuncIndex, int strFromMemFuncIndex) {
		// Serve mode: `handle` is the wasi:http/incoming-handler export, called once per
		// request on a possibly REUSED instance (jco / wasmCloud). The canonical-ABI
		// allocator (mem-http-client's cabi_realloc) is where the host writes a request's
		// result buffers -- the incoming path / headers / body -- and it only grows, so
		// reset its bump-pointer cell to the base here, before http.lisp reads anything.
		// Without it linear memory grows by ~one request per call; wasmtime serve
		// re-instantiates per request, so it never showed. Only the cell needs resetting
		// --
		// the core HEAP_PTR stays put because every %component-import wrapper already
		// pops it
		// back -- and no init-once snapshot is needed: the base is a constant and nothing
		// allocates through cabi_realloc before the first handle call. See
		// WasmLispCompiler.CABI_HP_CELL_ADDR / mem-http-client.wat.
		if (ctx.serve && "handle".equals(decl.exportName())) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.CABI_HP_CELL_ADDR);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.CABI_HP_BASE);
			ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
			// Run the program's top level once per instance before the first request. A
			// serve component never lifts `run`, so nothing else would execute the
			// top-level initializers (defvar/defparameter globals, spliced library
			// state, user top-level forms) -- a handler reading one would see null. The
			// old serve adapter ran `run` once as init; the callback shape does it
			// here, inside the handle call's task context, so a top-level suspension
			// drives through the blocking event loop exactly as under `wasmtime run`.
			// The flag is set BEFORE the call so a handler reached from the top level
			// itself cannot re-enter the init.
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeSignedLeb128(ctx.serveInitGlobalIndex);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeSignedLeb128(ctx.serveInitGlobalIndex);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_START);
			// _start returns i32 (0 = ok) in component mode.
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.END);
		}
		// EH mode: an uncaught $lisp-cond throw escaping a host-callable entry must
		// keep the trap shape (the host sees a trap, not a wasm exception), so the
		// whole wrapper runs inside a catch_all -> unreachable, the same wrap as
		// _start. Covers the serve-mode %http-dispatch wrapper too.
		if (ctx.ehMode) {
			WasmEmitHelper.emitCatchAllPrologue(ctx);
		}
		final boolean asyncTarget = ctx.asyncFuncBase >= 0 && ctx.asyncDefunNames.contains(decl.name());
		final boolean callbackDriven = asyncTarget && isCallbackExport(ctx, decl);
		// asyncMode: every host entry re-establishes the CURRENT task. A callback
		// export begins a fresh task record (frames created while the target runs
		// eagerly are owned by it, and its waitable-set starts empty); every other
		// wrapper runs as a synchronous boundary, so a stale record from an earlier
		// task on a reused instance must not leak in.
		if (ctx.asyncFuncBase >= 0 && ctx.currentTaskGlobalIndex >= 0) {
			if (callbackDriven) {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_TASK_BEGIN);
				ctx.writer.write(Instruction.DROP);
			}
			else {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				ctx.writer.write(Instruction.SET_GLOBAL);
				ctx.writer.writeSignedLeb128(ctx.currentTaskGlobalIndex);
			}
		}
		// env (defuns ignore it)
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		// Box each parameter from its wasm slot(s) into the internal (ref null eq) value.
		int slot = 0;
		for (String t : decl.paramTypes()) {
			emitBoxParam(ctx, t, slot, strFromMemFuncIndex);
			slot += slotsForType(t);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(targetFuncIndex);
		if (callbackDriven) {
			// The CALLBACK driver: a settled target already delivered its result
			// through task.return, so the wrapper answers EXIT; a still-pending one
			// turns this call into a live callback task -- _task_suspend arms the
			// doorbell, registers the record and the context slots, and returns the
			// packed WAIT code the host parks the task on (events then arrive through
			// _async_cb). A rejected future's re-signal (the poll) reaches the
			// catch-all above -- the trap an error in an exported function produces.
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_POLL);
			int polled = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(polled);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(polled);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(ctx.futureTypeIndex);
			ctx.writer.write(Instruction.IF);
			ctx.writer.write(0x7f); // blocktype: one i32 result
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(polled);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_TASK_SUSPEND);
			ctx.writer.write(Instruction.ELSE);
			// Completed synchronously: the record was never registered; just clear
			// the CURRENT task and answer EXIT.
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeSignedLeb128(ctx.currentTaskGlobalIndex);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.END);
			if (ctx.ehMode) {
				WasmEmitHelper.emitCatchAllEpilogue(ctx);
			}
			ctx.writer.write(Instruction.END);
			return;
		}
		// asyncMode: an async-defun target returns a TYPE_FUTURE; poll it so the host
		// sees the settled value, and drive a still-pending one through the blocking
		// event loop (_sched_loop) -- an async target that awaited a host-backed
		// future suspended, and this export boundary is synchronous, so the loop runs
		// it to completion here. A rejected future's re-signal reaches the catch-all
		// above (the trap an error in an exported function produces today).
		if (asyncTarget) {
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_POLL);
			int polled = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(polled);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(polled);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(ctx.futureTypeIndex);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(polled);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SCHED_LOOP);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(polled);
			ctx.writer.write(Instruction.END);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(polled);
		}
		emitUnboxResult(ctx, decl.returnType());
		if (isCallbackExport(ctx, decl)) {
			// a callback-lifted export whose target is not an async-defun completed
			// within the call: the packed EXIT code (the response went out through
			// task.return inside the target)
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
		}
		if (ctx.ehMode) {
			WasmEmitHelper.emitCatchAllEpilogue(ctx);
		}
		ctx.writer.write(Instruction.END);
	}

	private static void emitBoxParam(WasmLispCompiler.Ctx ctx, String type, int slot, int strFromMemFuncIndex) {
		switch (type) {
			case T_INT -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case T_FLOAT -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			}
			case T_BOOL -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(slot);
				WasmEmitHelper.emitBoolFromI32(ctx);
			}
			case T_STRING -> {
				// (ptr,len) -> a Lisp string copied out of linear memory.
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(slot);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(slot + 1);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(strFromMemFuncIndex);
			}
			case T_S_EXPR -> {
				// (ptr,len) of s-expression text -> parse via the embedded reader.
				storeWord(ctx, WasmLispCompiler.READ_CURSOR_ADDR, slot, false);
				storeWord(ctx, WasmLispCompiler.READ_END_ADDR, slot, true);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
			}
			default -> throw new UnsupportedOperationException("Unknown rontolisp:wasm-export type: " + type);
		}
	}

	private static void emitUnboxResult(WasmLispCompiler.Ctx ctx, String type) {
		switch (type) {
			case T_INT -> WasmEmitHelper.castI31GetS(ctx);
			case T_FLOAT -> {
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
				ctx.writer.writeSignedLeb128(0);
			}
			case T_BOOL -> {
				// nil -> 0, anything else -> 1
				ctx.writer.write(Instruction.REF_IS_NULL);
				ctx.writer.write(Instruction.I32_EQZ);
			}
			case T_STRING -> emitStringResult(ctx);
			case T_S_EXPR -> {
				// Serialize any value to readable s-expression text, then return its
				// bytes.
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
				emitStringResult(ctx);
			}
			case T_VOID -> ctx.writer.write(Instruction.DROP); // discard the Lisp return
																// value
			default -> throw new UnsupportedOperationException("Unknown rontolisp:wasm-export type: " + type);
		}
	}

	// Returns a Lisp string value (a TYPE_STRING on the stack) to the host as two i32
	// results (content pointer, content length), stripping the internal surrounding
	// quotes.
	static void emitStringResult(WasmLispCompiler.Ctx ctx) {
		int tmp = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmp);
		// The string's bytes live on the GC heap; copy them into linear scratch at
		// HEAP_PTR (not advanced -- the host reads the returned (ptr,len) right after
		// this
		// call returns, before the next string op reuses the scratch) and return the
		// content pointer/length. ptr = HEAP_PTR + 1 (skip the leading quote); len =
		// _str_to_mem(str, HEAP_PTR) - 2 (strip both quotes). All i32 intermediates stay
		// on the stack (ctx temps are ref-typed).
		loadHeapPtr(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmp);
		loadHeapPtr(ctx);
		WasmEmitHelper.emitStrToMemCall(ctx.writer);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(2);
		ctx.writer.write(Instruction.I32_SUB);
	}

	// Pushes mem[HEAP_PTR_ADDR] (the transient host-result scratch base).
	private static void loadHeapPtr(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	// Stores ptr (addEnd=false) or ptr+len (addEnd=true) for the s-expression parameter
	// at
	// the given slot pair into a fixed reader-control word in linear memory.
	static void storeWord(WasmLispCompiler.Ctx ctx, int address, int slot, boolean addEnd) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(address);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		if (addEnd) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot + 1);
			ctx.writer.write(Instruction.I32_ADD);
		}
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	static int slotsForType(String type) {
		return isMemoryType(type) ? 2 : 1;
	}

	private static boolean isMemoryType(String type) {
		return T_STRING.equals(type) || T_S_EXPR.equals(type);
	}

	/**
	 * Maps a type designator to its component-model primitive value type code for the
	 * {@code --component} export path ({@code null} = no result). {@code :long} (s64) is
	 * reachable only from the {@code --no-gc} component path (the GC backend rejects it
	 * outright); {@code :string} lifts as {@code string} on both component paths, and
	 * {@code :s-expr} (GC only) lifts as {@code string} too -- the s-expression crosses
	 * the boundary as its printed text.
	 * @param type the type designator
	 * @return the {@code ComponentWriter.VT_*} code, or {@code null} for {@code :void}
	 */
	static @Nullable Integer componentValType(String type) {
		return switch (type) {
			case T_INT -> am.ik.wasm.ComponentWriter.VT_S32;
			case T_LONG -> am.ik.wasm.ComponentWriter.VT_S64;
			case T_FLOAT -> am.ik.wasm.ComponentWriter.VT_F64;
			case T_BOOL -> am.ik.wasm.ComponentWriter.VT_BOOL;
			case T_STRING, T_S_EXPR -> am.ik.wasm.ComponentWriter.VT_STRING;
			case T_VOID -> null;
			default -> throw new UnsupportedOperationException(
					"rontolisp:wasm-export type " + type + " has no component-model scalar mapping");
		};
	}

	/**
	 * The canonical-ABI reallocation function's core export name on the GC
	 * {@code --component} path (mirrors {@code NoGcWasmComponentBuilder.CABI_REALLOC} on
	 * the {@code --no-gc} path).
	 */
	static final String CABI_REALLOC = "cabi_realloc";

	/**
	 * Returns whether the declared return type is memory-backed
	 * ({@code :string}/{@code :s-expr}), i.e. whether the {@code --component} lift needs
	 * a return-pointer shim (the canonical ABI caps flat results at one, so a
	 * {@code (ptr,len)} result must be spilled to a return-area record).
	 * @param decl the parsed export directive
	 * @return {@code true} for a {@code :string}/{@code :s-expr} return
	 */
	static boolean returnsMemory(Decl decl) {
		return isMemoryType(decl.returnType());
	}

	/**
	 * The flat-result signature of an export's lifted core function on the GC
	 * {@code --component} path, naming which shared {@code cabi_post_*} post-return
	 * function its lift uses: a {@code :string}/{@code :s-expr} result flattens to a
	 * single i32 return pointer, every scalar result keeps its own flat type. Mirrors
	 * {@code NoGcWasmComponentBuilder.postReturnKind} on the {@code --no-gc} path, plus
	 * {@code :s-expr} which only the GC backend supports.
	 * @param decl the parsed export directive
	 * @return the signature key ({@code "i32"}/{@code "f64"}/{@code "void"})
	 */
	static String componentPostReturnKind(Decl decl) {
		return switch (decl.returnType()) {
			case T_STRING, T_S_EXPR, T_INT, T_BOOL -> "i32";
			case T_FLOAT -> "f64";
			case T_VOID -> "void";
			default -> throw new UnsupportedOperationException(
					"rontolisp:wasm-export type " + decl.returnType() + " has no component post-return signature");
		};
	}

	/**
	 * The core export name of the shared post-return function for a flat-result
	 * signature.
	 * @param kind the signature key from {@link #componentPostReturnKind}
	 * @return the core export name (e.g. {@code "cabi_post_i32"})
	 */
	static String cabiPostExportName(String kind) {
		return "cabi_post_" + kind;
	}

	/**
	 * Builds the component-model export description for a declaration.
	 * @param decl the parsed declaration
	 * @return the export description consumed by {@code WasmComponentBuilder}
	 */
	static WasmComponentBuilder.FuncExport componentExport(Decl decl) {
		List<Integer> params = new ArrayList<>();
		for (String t : decl.paramTypes()) {
			params.add(componentValType(t));
		}
		return new WasmComponentBuilder.FuncExport(decl.exportName(), decl.paramNames(), params,
				componentValType(decl.returnType()), decl.async());
	}

	static void appendWasmTypes(List<Type> types, String type) {
		switch (type) {
			case T_INT, T_BOOL -> types.add(Type.I32);
			case T_LONG -> types.add(Type.I64);
			case T_FLOAT -> types.add(Type.F64);
			case T_STRING, T_S_EXPR -> {
				types.add(Type.I32);
				types.add(Type.I32);
			}
			default -> throw new UnsupportedOperationException("Unknown rontolisp:wasm-export type: " + type);
		}
	}

	private static String quotedSymbolName(LispVal value) {
		// (quote name) -> name
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		if (value instanceof LispSymbol sym) {
			return sym.name();
		}
		throw new UnsupportedOperationException(
				"rontolisp:wasm-export expects a quoted function name, got: " + value.print());
	}

	private static String keywordName(LispVal value, LispCons form) {
		if (value instanceof LispSymbol sym && sym.isKeyword()) {
			return sym.name();
		}
		throw new UnsupportedOperationException(
				"Expected a keyword option in " + form.print() + ", got: " + value.print());
	}

	static String typeDesignator(LispVal value, LispCons form) {
		if (value instanceof LispSymbol sym && sym.isKeyword() && KNOWN_TYPES.contains(sym.name())) {
			return sym.name();
		}
		throw new UnsupportedOperationException("Unknown rontolisp:wasm-export type designator " + value.print()
				+ " in " + form.print() + " (expected one of " + KNOWN_TYPES + ")");
	}

	// A return designator is a known scalar/memory type, or a void marker: :void, nil,
	// '()
	// or (quote nil) -> the wrapper has no result and discards the Lisp return value.
	private static String returnDesignator(LispVal value, LispCons form) {
		if (isVoidMarker(value)) {
			return T_VOID;
		}
		if (value instanceof LispSymbol sym && sym.isKeyword() && T_VOID.equals(sym.name())) {
			return T_VOID;
		}
		return typeDesignator(value, form);
	}

	// nil, '() and (quote nil) all read as a quoted-or-bare LispNil; treat them as void.
	private static boolean isVoidMarker(LispVal value) {
		if (value instanceof am.ik.rontolisp.LispNil) {
			return true;
		}
		return value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof am.ik.rontolisp.LispNil;
	}

	private static List<String> quotedTypeList(LispVal value, LispCons form) {
		// Bare nil (an omitted / empty parameter list) -> no parameters.
		if (value instanceof am.ik.rontolisp.LispNil) {
			return List.of();
		}
		// (quote (:t1 :t2 ...)) -> [:t1, :t2, ...]
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest) {
			List<String> result = new ArrayList<>();
			if (rest.car() instanceof LispCons list) {
				for (LispVal element : list.toList()) {
					result.add(typeDesignator(element, form));
				}
			}
			else if (!(rest.car() instanceof am.ik.rontolisp.LispNil)) {
				throw new UnsupportedOperationException(
						"rontolisp:wasm-export :params expects a list in " + form.print());
			}
			return result;
		}
		throw new UnsupportedOperationException(
				"rontolisp:wasm-export :params expects a quoted list in " + form.print());
	}

}
