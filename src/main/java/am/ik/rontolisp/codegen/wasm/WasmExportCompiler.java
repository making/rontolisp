package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.BoundaryType;
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
 * The type designators are {@link BoundaryType}'s: every WIT fixed-width integer
 * ({@code :s8} … {@code :u64}, with {@code :int} / {@code :long} as the aliases of
 * {@code :s32} / {@code :s64}), plus {@code :float}, {@code :bool}, {@code :string} and
 * the rontolisp-only {@code :s-expr}. Their boundary representations:
 * <ul>
 * <li>an integer up to 32 bits -- {@code i32}. Boxed as an {@code i31ref} when the value
 * fits it, and as a float otherwise (the wasm-GC backend's settled representation of an
 * integer beyond the {@code i31} range, {@code .kb/time-environment-builtins.md}), so the
 * whole declared range crosses exactly</li>
 * <li>{@code :s64} / {@code :u64} -- {@code i64} ({@code --no-gc} only; matches the
 * scalar backend's internal {@code i64} integer representation, so the full 2^63 range
 * crosses the boundary with no {@code wrap}/{@code extend})</li>
 * <li>{@code :float} -- {@code f64} (boxed as a float struct)</li>
 * <li>{@code :bool} -- {@code i32} (0 = nil, non-zero = the symbol {@code t})</li>
 * <li>{@code :string} -- {@code (ptr,len)} bytes in linear memory</li>
 * <li>{@code :s-expr} -- {@code (ptr,len)} s-expression text in linear memory</li>
 * <li>{@code :bytes} -- an {@code (unsigned-byte 8)} vector as raw bytes, no UTF-8 decode
 * in either direction. A parameter is {@code (ptr,len)} like a string; a RESULT follows
 * the caller-passes-the-buffer {@code read(2)} shape: the export signature gains a
 * trailing {@code (ptr,cap)} pair, the wrapper copies {@code min(len,cap)} bytes there,
 * and the single {@code i32} result is the vector's FULL length -- an undersized buffer
 * is a retry, not a truncation. Core-module (Preview&nbsp;1 / {@code --no-wasi}) wasm-GC
 * paths only</li>
 * </ul>
 *
 * <p>
 * A returned Lisp value is normalized the way every other numeric boundary on this
 * backend normalizes one (through {@link WasmEmitHelper#castFloatGetF64}, so an integer,
 * a ratio or a float are all accepted) and then converted with a trapping {@code trunc}:
 * <strong>the boundary carries the value exactly, or the wrapper traps</strong>. Nothing
 * is silently wrapped or masked, which also keeps the component callable from generators
 * stricter than the canonical ABI (jco throws on an out-of-range narrow result where
 * wasmtime masks).
 *
 * <p>
 * Scalar designators yield a pure numeric signature callable straight from
 * {@code wasmtime --invoke}. The memory-backed {@code :string} / {@code :s-expr}
 * designators pass {@code (ptr,len)} through linear memory and need a host that can
 * read/write it (e.g. JavaScript), using the exported {@code __ronto_alloc} bump
 * allocator to reserve input buffers.
 */
final class WasmExportCompiler {

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
	 * @param iface the fully-qualified id of the WIT interface this export belongs to
	 * ({@code :interface "docs:adder/add@0.1.0"}, or {@code null} for a freestanding
	 * world-level function export). {@code rontolisp:wit-export} fills this in when a
	 * world exports an interface ({@code export docs:adder/add;}): every export sharing
	 * an {@code iface} is bundled into one component <em>instance</em> exported under
	 * that id, instead of a flat top-level function export. Only meaningful on the
	 * {@code --component} paths; ignored on Preview 1 / {@code --no-wasi} (a core module
	 * has no instances)
	 */
	record Decl(String name, String exportName, List<BoundaryType> paramTypes, List<String> paramNames,
			BoundaryType returnType, boolean async, @Nullable String iface) {
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
		List<BoundaryType> params = null;
		List<String> paramNames = null;
		BoundaryType returns = null;
		boolean async = false;
		String iface = null;
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
				case ":INTERFACE" -> iface = interfaceId(value, form);
				default -> throw new UnsupportedOperationException(
						"Unknown rontolisp:wasm-export option " + keyword + " in " + form.print());
			}
			i += 2;
		}
		List<BoundaryType> types = params == null ? List.of() : params;
		if (paramNames != null && paramNames.size() != types.size()) {
			throw new UnsupportedOperationException("rontolisp:wasm-export :param-names has " + paramNames.size()
					+ " name(s) but :params has " + types.size() + " type(s) in " + form.print());
		}
		// Omitted :returns (like nil / '() / :void) means a void result.
		return new Decl(name, exportName == null ? unqualifiedMember(name) : exportName, types,
				paramNames == null ? defaultParamNames(types.size()) : paramNames,
				returns == null ? BoundaryType.VOID : returns, async, iface);
	}

	// The :interface value is a string naming the WIT interface the export belongs to
	// (its fully-qualified id, e.g. "docs:adder/add@0.1.0", or a bare label for an inline
	// interface). It is component-facing metadata (rontolisp:wit-export fills it in), so
	// a
	// bare string is all the directive carries.
	private static String interfaceId(LispVal value, LispCons form) {
		if (value instanceof am.ik.rontolisp.LispString str) {
			return str.value();
		}
		throw new UnsupportedOperationException(
				"rontolisp:wasm-export :interface expects an interface-id string in " + form.print());
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
		for (BoundaryType t : decl.paramTypes()) {
			slots += slotsForType(t);
		}
		// A :bytes result adds the trailing caller-passed (ptr,cap) pair to the wrapper's
		// parameter list (see paramWasmTypes), so the scratch locals sit after it.
		if (decl.returnType() == BoundaryType.BYTES) {
			slots += 2;
		}
		return slots;
	}

	/** Returns whether any declared type is the {@code :bytes} boundary type. */
	static boolean usesBytes(Decl decl) {
		return decl.returnType() == BoundaryType.BYTES || decl.paramTypes().contains(BoundaryType.BYTES);
	}

	/**
	 * The extra typed locals the wrapper body needs beyond its parameter slots and the
	 * {@code (ref null eq)} temps {@code Ctx.allocTemp} hands out — they occupy the slots
	 * right after the parameters, so the boxing/unboxing code can address them by a base
	 * known before emission. Only a narrow integer result needs one (an {@code i32} to
	 * hold the truncated value while its range is checked); every other boundary type
	 * keeps the declaration exactly as it was, so an export that uses none stays
	 * byte-identical.
	 * @param decl the parsed export directive
	 * @return the scratch local types, in slot order
	 */
	static List<Type> scratchTypes(Decl decl) {
		return needsNarrowGuard(decl.returnType()) ? List.of(Type.I32) : List.of();
	}

	/**
	 * Returns whether any declared type is memory-backed
	 * ({@code :string}/{@code :s-expr}).
	 */
	static boolean usesMemory(Decl decl) {
		if (isMemoryType(decl.returnType())) {
			return true;
		}
		for (BoundaryType t : decl.paramTypes()) {
			if (isMemoryType(t)) {
				return true;
			}
		}
		return false;
	}

	/** Returns the WASM parameter types for the wrapper signature. */
	static Type[] paramWasmTypes(Decl decl) {
		List<Type> types = new ArrayList<>();
		for (BoundaryType t : decl.paramTypes()) {
			appendWasmTypes(types, t);
		}
		// A :bytes RESULT is caller-buffered: the host passes (ptr,cap) as two trailing
		// parameters and the wrapper answers the full length (the read(2) shape), so no
		// per-call __ronto_alloc is left behind for a byte transfer.
		if (decl.returnType() == BoundaryType.BYTES) {
			types.add(Type.I32);
			types.add(Type.I32);
		}
		return types.toArray(new Type[0]);
	}

	/**
	 * Returns the WASM result types for the wrapper signature (empty for a void result).
	 */
	static Type[] resultWasmTypes(Decl decl) {
		if (decl.returnType() == BoundaryType.VOID) {
			return new Type[0];
		}
		// A :bytes result is the value's FULL byte length; the bytes went out through the
		// caller-passed (ptr,cap) pair appended in paramWasmTypes.
		if (decl.returnType() == BoundaryType.BYTES) {
			return new Type[] { Type.I32 };
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
	 * @param bytesFromMemFuncIndex the function index of the {@code _bytes_from_mem}
	 * helper (or {@code -1} when no {@code :bytes} type is present)
	 * @param bytesCopyFuncIndex the function index of the {@code _bytes_copy} helper (or
	 * {@code -1} when no {@code :bytes} type is present)
	 */
	static void emitBody(WasmLispCompiler.Ctx ctx, Decl decl, int targetFuncIndex, int strFromMemFuncIndex,
			int bytesFromMemFuncIndex, int bytesCopyFuncIndex) {
		// A module with a suspending host import (wasm-import :async t / --host-fetch)
		// can be RE-ENTERED while a call is parked: JSPI returns control to the host's
		// event loop mid-call. Nothing per call owns the allocator bracket (its marks
		// interleave, they do not nest) or a shallowly-bound special (one module-global
		// cell), so a second entry would silently corrupt BOTH calls -- refuse it with
		// a trap at the boundary instead (the build warning names the serialise
		// obligation this trap enforces). Set on entry, cleared on every return below.
		if (ctx.reentryGuardGlobalIndex >= 0) {
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(ctx.reentryGuardGlobalIndex);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.writer.write(Instruction.UNREACHABLE);
			ctx.writer.write(Instruction.END);
			emitReentryGuardStore(ctx, 1);
		}
		// --reentrant: no guard -- instead every call gets a fresh task record, the
		// per-call home of its dynamic bindings (WasmDynVars). Nothing clears it on
		// return: the next entry overwrites it, and between calls it only delays the
		// GC of a few cells.
		if (ctx.reentrant) {
			WasmDynVars.emitTaskBegin(ctx);
		}
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
			ctx.writer.writeUnsignedLeb128(ctx.serveInitGlobalIndex);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(ctx.serveInitGlobalIndex);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_START);
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
		// asyncMode: the boundary decision is DYNAMIC, like Preview 1's resolve below
		// -- a plain defun handing back someone else's future has exactly the same
		// boundary problem as an async-defun target, so the poll runs on every
		// asyncMode export (it passes a non-future straight through) instead of being
		// keyed on the target's name. Unlike P1, the component's guarantee costs the
		// poll + ref.test + _sched_loop branch per export wrapper; a component with no
		// async surface has no asyncFuncBase and stays byte-identical.
		final boolean asyncBoundary = ctx.asyncFuncBase >= 0;
		final boolean callbackDriven = asyncBoundary && isCallbackExport(ctx, decl);
		// asyncMode: every host entry re-establishes the CURRENT task. A callback
		// export begins a fresh task record (frames created while the target runs
		// eagerly are owned by it, and its waitable-set starts empty); every other
		// wrapper runs as a synchronous boundary, so a stale record from an earlier
		// task on a reused instance must not leak in.
		if (ctx.asyncFuncBase >= 0 && ctx.currentTaskGlobalIndex >= 0) {
			if (callbackDriven) {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_TASK_BEGIN);
				ctx.writer.write(Instruction.DROP);
			}
			else {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				ctx.writer.write(Instruction.SET_GLOBAL);
				ctx.writer.writeUnsignedLeb128(ctx.currentTaskGlobalIndex);
			}
		}
		// env (defuns ignore it)
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		// Box each parameter from its wasm slot(s) into the internal (ref null eq) value.
		int slot = 0;
		for (BoundaryType t : decl.paramTypes()) {
			emitBoxParam(ctx, t, slot, strFromMemFuncIndex, bytesFromMemFuncIndex);
			slot += slotsForType(t);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(targetFuncIndex);
		// Outside asyncMode (Preview 1, --no-wasi, the reactor component) an async body
		// is degenerate synchronous: calling one runs it to completion and hands back a
		// SETTLED future, and so does a `wasm-import ... :async t`. The boundary declares
		// a scalar/string, so the wrapper resolves the future here -- what the
		// asyncBoundary
		// branch below already does for --component, and what the reactor transport and
		// every worked example had to spell as an explicit %future-force. The resolve is
		// DYNAMIC rather than keyed on the target being an async-defun, because a plain
		// defun handing back someone else's future has exactly the same boundary problem;
		// _p1_future_await passes a non-future straight through, so one unconditional
		// call covers both and costs no branch. A module with no future producer at all
		// keeps byte-identical wrappers (Ctx.p1Futures), and a :void export skips it --
		// the body has already run, and the value is dropped.
		if (ctx.p1Futures && decl.returnType() != BoundaryType.VOID) {
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
		}
		if (callbackDriven) {
			// The CALLBACK driver: a settled target already delivered its result
			// through task.return, so the wrapper answers EXIT; a still-pending one
			// turns this call into a live callback task -- _task_suspend arms the
			// doorbell, registers the record and the context slots, and returns the
			// packed WAIT code the host parks the task on (events then arrive through
			// _async_cb). A rejected future's re-signal (the poll) reaches the
			// catch-all above -- the trap an error in an exported function produces.
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_POLL);
			int polled = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(polled);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(polled);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(ctx.futureTypeIndex);
			ctx.writer.write(Instruction.IF);
			ctx.writer.write(0x7f); // blocktype: one i32 result
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(polled);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_TASK_SUSPEND);
			ctx.writer.write(Instruction.ELSE);
			// Completed synchronously: the record was never registered; just clear
			// the CURRENT task and answer EXIT.
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(ctx.currentTaskGlobalIndex);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.END);
			if (ctx.ehMode) {
				WasmEmitHelper.emitCatchAllEpilogue(ctx);
			}
			ctx.writer.write(Instruction.END);
			return;
		}
		// asyncMode: a target that answers a TYPE_FUTURE -- an async-defun's own, or
		// one a plain defun passes through -- is polled so the host sees the settled
		// value, and a still-pending one is driven through the blocking event loop
		// (_sched_loop): an async body that awaited a host-backed future suspended,
		// and this export boundary is synchronous, so the loop runs it to completion
		// here. A non-future answer passes the poll untouched. A rejected future's
		// re-signal reaches the catch-all above (the trap an error in an exported
		// function produces today).
		if (asyncBoundary) {
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_POLL);
			int polled = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(polled);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(polled);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(ctx.futureTypeIndex);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(polled);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SCHED_LOOP);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(polled);
			ctx.writer.write(Instruction.END);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(polled);
		}
		// The scratch locals sit right after the parameter slots (see scratchTypes).
		// (A callback-lifted export cannot reach here: it exists only under asyncMode,
		// where the callbackDriven branch above returned.)
		emitUnboxResult(ctx, decl.returnType(), paramSlotCount(decl), bytesCopyFuncIndex);
		if (ctx.reentryGuardGlobalIndex >= 0) {
			emitReentryGuardStore(ctx, 0);
		}
		if (ctx.ehMode && ctx.reentryGuardGlobalIndex >= 0) {
			// The catch_all epilogue by hand: its landing pad re-raises the caught
			// condition as a trap, and the guard must be cleared THERE too -- a host
			// that catches the trap and then calls sequentially must not be refused
			// as a re-entry it never made.
			ctx.writer.write(Instruction.RETURN);
			ctx.writer.write(Instruction.END); // try_table
			ctx.writer.write(Instruction.END); // block
			emitReentryGuardStore(ctx, 0);
			ctx.writer.write(Instruction.UNREACHABLE);
			ctx.wasmCtrlDepth -= 2;
		}
		else if (ctx.ehMode) {
			WasmEmitHelper.emitCatchAllEpilogue(ctx);
		}
		ctx.writer.write(Instruction.END);
	}

	// global.set of the re-entry guard flag (1 = a call is inside the module).
	private static void emitReentryGuardStore(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.reentryGuardGlobalIndex);
	}

	private static void emitBoxParam(WasmLispCompiler.Ctx ctx, BoundaryType type, int slot, int strFromMemFuncIndex,
			int bytesFromMemFuncIndex) {
		switch (type) {
			// Every value of these types fits the i31 house integer exactly (the widest
			// is
			// u16's 65535), so they box with the plain i31 boxing and need no check: the
			// canonical ABI guarantees an in-range core value, and a core-module host is
			// held to the same declared type.
			case S8, S16, U8, U16 -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			// s32/u32 exceed the i31 house integer, so they box the way every other wide
			// integer on this backend does: through _int_new, which answers an i31 when
			// the value fits and the boxed exact integer (TYPE_BIGNUM) otherwise.
			case S32 -> emitBoxWideInt(ctx, slot, true);
			case U32 -> emitBoxWideInt(ctx, slot, false);
			// s64 rides the i64 house lane of the exact-integer box directly. u64 is
			// exact through 2^63-1; a value with the top bit set has no exact
			// representation here, so the wrapper traps rather than reporting a
			// different number (the boundary's exact-or-trap rule).
			case S64 -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
			}
			case U64 -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(Instruction.I64_LT_S);
				ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
				ctx.writer.write(Instruction.UNREACHABLE);
				ctx.writer.write(Instruction.END);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
			}
			case FLOAT -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			}
			case BOOL -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				WasmEmitHelper.emitBoolFromI32(ctx);
			}
			case STRING -> {
				// (ptr,len) -> a Lisp string copied out of linear memory.
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot + 1);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(strFromMemFuncIndex);
			}
			case S_EXPR -> {
				// (ptr,len) of s-expression text -> parse via the embedded reader.
				storeWord(ctx, WasmLispCompiler.READ_CURSOR_ADDR, slot, false);
				storeWord(ctx, WasmLispCompiler.READ_END_ADDR, slot, true);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
			}
			case BYTES -> {
				// (ptr,len) -> a fresh (unsigned-byte 8) vector copied out of linear
				// memory, raw bytes, no UTF-8 decode.
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot + 1);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(bytesFromMemFuncIndex);
			}
			// :void is never a parameter.
			case VOID -> throw new UnsupportedOperationException(
					"rontolisp:wasm-export type " + type.designator() + " is not a wasm-GC parameter type");
		}
	}

	// s32/u32 -> a boxed Lisp integer, exactly: widen to i64 and normalize through
	// _int_new, which answers an i31 when the value fits one and the boxed exact
	// integer (TYPE_BIGNUM) otherwise -- the representation this backend gives every
	// integer beyond the i31 range (the clock built-ins, and every wide integer a
	// component import lifts).
	private static void emitBoxWideInt(WasmLispCompiler.Ctx ctx, int slot, boolean signed) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(signed ? Instruction.I64_EXTEND_S_I32 : Instruction.I64_EXTEND_U_I32);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
	}

	// Whether an integer result needs an explicit range check after the trapping trunc:
	// i32.trunc_s/u_f64 already rejects everything outside the full 32-bit range, so only
	// the sub-32-bit types have a range of their own left to enforce.
	private static boolean needsNarrowGuard(BoundaryType type) {
		return type.isInteger() && type.bits() < 32;
	}

	private static void emitUnboxResult(WasmLispCompiler.Ctx ctx, BoundaryType type, int scratchSlot,
			int bytesCopyFuncIndex) {
		switch (type) {
			// Every integer result normalizes through the backend's own number-to-f64
			// conversion (an i31, a ratio or a float all cross), then converts with a
			// TRAPPING trunc: a value the declared type cannot state stops the call
			// instead
			// of arriving silently wrapped. i32.trunc_u_f64 also rejects a negative,
			// which
			// is what a negative returned from an unsigned export must do.
			case S8, S16, S32, U8, U16, U32 -> {
				WasmEmitHelper.castFloatGetF64(ctx);
				ctx.writer.write(type.signed() ? Instruction.I32_TRUNC_S_F64 : Instruction.I32_TRUNC_U_F64);
				if (needsNarrowGuard(type)) {
					emitNarrowGuard(ctx, type, scratchSlot);
				}
			}
			case FLOAT -> {
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
				ctx.writer.writeUnsignedLeb128(0);
			}
			case BOOL -> {
				// nil -> 0, anything else -> 1
				ctx.writer.write(Instruction.REF_IS_NULL);
				ctx.writer.write(Instruction.I32_EQZ);
			}
			// --reentrant: a memory-typed result cannot sit at the un-advanced HEAP_PTR
			// scratch -- the promising promise settles a microtask AFTER the wrapper
			// returns, and another overlapped call's wasm can run in between and trample
			// it -- the second corruption this boundary was measured to have. It goes
			// out in a park block
			// the host frees after decoding (__ronto_park_free(ptr)).
			case STRING -> {
				if (ctx.reentrant) {
					ctx.writer.write(Instruction.CALL);
					ctx.writer.writeUnsignedLeb128(ctx.parkStrResultFuncIndex);
				}
				else {
					emitStringResult(ctx);
				}
			}
			case S_EXPR -> {
				// Serialize any value to readable s-expression text, then return its
				// bytes.
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
				if (ctx.reentrant) {
					ctx.writer.write(Instruction.CALL);
					ctx.writer.writeUnsignedLeb128(ctx.parkStrResultFuncIndex);
				}
				else {
					emitStringResult(ctx);
				}
			}
			case BYTES -> {
				// The returned (unsigned-byte 8) vector goes out through the CALLER's
				// buffer: _bytes_copy writes min(len,cap) raw bytes at the trailing
				// (ptr,cap) parameter pair and answers the vector's FULL length, so an
				// undersized buffer is a retry, not a truncation -- and no __ronto_alloc
				// is spent on the transfer (the finding that motivated the shape: a
				// per-chunk allocation grows the arena by the whole body). A non-vector
				// return value fails _bytes_copy's ref.cast: exact-or-trap, like every
				// other boundary type.
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(scratchSlot - 2); // ptr
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(scratchSlot - 1); // cap
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(bytesCopyFuncIndex);
			}
			case VOID -> ctx.writer.write(Instruction.DROP); // discard the Lisp return
																// value
			// A 64-bit result crosses exactly from the exact-integer representations
			// (i31 or TYPE_BIGNUM, via _int_val); a float or ratio result normalizes
			// through f64 with a TRAPPING trunc, like the 32-bit family. A negative
			// returned from a u64 export traps on both paths.
			case S64, U64 -> emitWideIntResult(ctx, type.signed());
		}
	}

	// Traps unless the truncated i32 on the stack lies inside a sub-32-bit type's range,
	// and leaves it there. The unsigned types need no lower check: the i32.trunc_u_f64
	// that
	// produced the value already trapped on a negative.
	private static void emitNarrowGuard(WasmLispCompiler.Ctx ctx, BoundaryType type, int scratchSlot) {
		BoundaryType.Range range = java.util.Objects.requireNonNull(type.range());
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(scratchSlot);
		if (type.signed()) {
			emitTrapUnless(ctx, scratchSlot, Instruction.I32_LT_S, range.min().intValueExact());
		}
		emitTrapUnless(ctx, scratchSlot, type.signed() ? Instruction.I32_GT_S : Instruction.I32_GT_U,
				range.max().intValueExact());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(scratchSlot);
	}

	// A 64-bit integer result. The exact-integer representations (i31 and TYPE_BIGNUM)
	// unbox through _int_val, carrying the full signed 64-bit range exactly; any other
	// numeric representation (float, ratio) normalizes through the backend's
	// number-to-f64 conversion and a TRAPPING trunc, like the 32-bit family. For u64 the
	// exact path refuses a negative explicitly (_int_val is called twice on that path --
	// once for the check, once for the value -- it is pure and cheap); the float path's
	// i64.trunc_u_f64 already rejects a negative.
	private static void emitWideIntResult(WasmLispCompiler.Ctx ctx, boolean signed) {
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I64);
		if (!signed) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
			ctx.writer.write(Instruction.I64_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.I64_LT_S);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.writer.write(Instruction.UNREACHABLE);
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(signed ? Instruction.I64_TRUNC_S_F64 : Instruction.I64_TRUNC_U_F64);
		ctx.writer.write(Instruction.END);
	}

	// `if (local[slot] <op> bound) unreachable` -- the boundary's way of refusing a value
	// it cannot state. A trap is what the host already sees for an error inside an
	// exported
	// function (the catch_all landing pad above), so the failure shape is unchanged.
	private static void emitTrapUnless(WasmLispCompiler.Ctx ctx, int slot, int comparison, int bound) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(bound);
		ctx.writer.write(comparison);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
	}

	// Returns a Lisp string value (a TYPE_STRING on the stack) to the host as two i32
	// results (content pointer, content length), stripping the internal surrounding
	// quotes.
	static void emitStringResult(WasmLispCompiler.Ctx ctx) {
		int tmp = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
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
		ctx.writer.writeUnsignedLeb128(tmp);
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
		ctx.writer.writeUnsignedLeb128(slot);
		if (addEnd) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot + 1);
			ctx.writer.write(Instruction.I32_ADD);
		}
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	static int slotsForType(BoundaryType type) {
		return isMemoryType(type) ? 2 : 1;
	}

	private static boolean isMemoryType(BoundaryType type) {
		return type == BoundaryType.STRING || type == BoundaryType.S_EXPR || type == BoundaryType.BYTES;
	}

	/**
	 * Maps a type designator to its component-model primitive value type code for the
	 * {@code --component} export path ({@code null} = no result). The 64-bit types are
	 * reachable only from the {@code --no-gc} component path (the GC backend rejects them
	 * outright); {@code :string} lifts as {@code string} on both component paths, and
	 * {@code :s-expr} (GC only) lifts as {@code string} too -- the s-expression crosses
	 * the boundary as its printed text.
	 * @param type the type designator
	 * @return the {@code ComponentWriter.VT_*} code, or {@code null} for {@code :void}
	 */
	static @Nullable Integer componentValType(BoundaryType type) {
		return switch (type) {
			case S8 -> am.ik.wasm.ComponentWriter.VT_S8;
			case S16 -> am.ik.wasm.ComponentWriter.VT_S16;
			case S32 -> am.ik.wasm.ComponentWriter.VT_S32;
			case S64 -> am.ik.wasm.ComponentWriter.VT_S64;
			case U8 -> am.ik.wasm.ComponentWriter.VT_U8;
			case U16 -> am.ik.wasm.ComponentWriter.VT_U16;
			case U32 -> am.ik.wasm.ComponentWriter.VT_U32;
			case U64 -> am.ik.wasm.ComponentWriter.VT_U64;
			case FLOAT -> am.ik.wasm.ComponentWriter.VT_F64;
			case BOOL -> am.ik.wasm.ComponentWriter.VT_BOOL;
			case STRING, S_EXPR -> am.ik.wasm.ComponentWriter.VT_STRING;
			// Lifting :bytes as a component-model list<u8> is its own change (for now it
			// is a core-module transfer); refusing here is what makes the --component
			// export path reject the designator with a clear message.
			case BYTES -> throw new UnsupportedOperationException(
					"rontolisp:wasm-export :bytes is a core-module (Preview 1 / --no-wasi) boundary type; the"
							+ " --component path does not lift it yet");
			// Unreachable: typeDesignator refuses the JVM-only handle types by name.
			case FLOAT_VECTOR, FLOAT_MATRIX -> throw new IllegalStateException(
					"a JVM-only boundary type reached the WASM component lift: " + type.designator());
			case VOID -> null;
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
			case STRING, S_EXPR, S8, S16, S32, U8, U16, U32, BOOL -> "i32";
			case S64, U64 -> "i64";
			case FLOAT -> "f64";
			// Unreachable: componentValType already refused the declaration.
			case BYTES ->
				throw new UnsupportedOperationException("rontolisp:wasm-export :bytes has no component-model lift");
			// Unreachable: typeDesignator refuses the JVM-only handle types by name.
			case FLOAT_VECTOR, FLOAT_MATRIX -> throw new IllegalStateException(
					"a JVM-only boundary type reached the WASM component lift: " + decl.returnType().designator());
			case VOID -> "void";
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
		for (BoundaryType t : decl.paramTypes()) {
			params.add(componentValType(t));
		}
		return new WasmComponentBuilder.FuncExport(decl.exportName(), decl.paramNames(), params,
				componentValType(decl.returnType()), decl.async(), decl.iface());
	}

	static void appendWasmTypes(List<Type> types, BoundaryType type) {
		switch (type) {
			// The canonical ABI flattens every integer up to 32 bits, signed or not, to
			// one
			// i32, and both 64-bit ones to one i64: the core signature of a u32 export is
			// the same as an s32 export's, and only the component type section tells them
			// apart.
			case S8, S16, S32, U8, U16, U32, BOOL -> types.add(Type.I32);
			case S64, U64 -> types.add(Type.I64);
			case FLOAT -> types.add(Type.F64);
			// A :bytes PARAMETER flattens like a string: (ptr,len) raw bytes. (A :bytes
			// RESULT never reaches here -- paramWasmTypes/resultWasmTypes special-case
			// its
			// caller-buffered (ptr,cap)+length shape.)
			case STRING, S_EXPR, BYTES -> {
				types.add(Type.I32);
				types.add(Type.I32);
			}
			case VOID -> throw new UnsupportedOperationException("rontolisp:wasm-export :void has no WASM value type");
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

	static BoundaryType typeDesignator(LispVal value, LispCons form) {
		if (value instanceof LispSymbol sym && sym.isKeyword()) {
			BoundaryType type = BoundaryType.forDesignator(sym.name());
			if (type != null && type.jvmOnly()) {
				// The packed float-array handle is a Java class; no WASM carrier states
				// one, so refuse the designator by name instead of failing in a lift.
				throw new UnsupportedOperationException("rontolisp:wasm-export type designator "
						+ type.designator().toLowerCase(java.util.Locale.ROOT)
						+ " is a JVM boundary type (rontolisp:jvm-export); the WASM boundary has no carrier for a"
						+ " packed float array, in " + form.print());
			}
			if (type != null && type != BoundaryType.VOID) {
				return type;
			}
		}
		throw new UnsupportedOperationException("Unknown rontolisp:wasm-export type designator " + value.print()
				+ " in " + form.print() + " (expected one of " + BoundaryType.wasmValueDesignators() + ")");
	}

	// A return designator is a known scalar/memory type, or a void marker: :void, nil,
	// '()
	// or (quote nil) -> the wrapper has no result and discards the Lisp return value.
	private static BoundaryType returnDesignator(LispVal value, LispCons form) {
		if (isVoidMarker(value)) {
			return BoundaryType.VOID;
		}
		if (value instanceof LispSymbol sym && BoundaryType.forDesignator(sym.name()) == BoundaryType.VOID) {
			return BoundaryType.VOID;
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

	private static List<BoundaryType> quotedTypeList(LispVal value, LispCons form) {
		// Bare nil (an omitted / empty parameter list) -> no parameters.
		if (value instanceof am.ik.rontolisp.LispNil) {
			return List.of();
		}
		// (quote (:t1 :t2 ...)) -> [:t1, :t2, ...]
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest) {
			List<BoundaryType> result = new ArrayList<>();
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
