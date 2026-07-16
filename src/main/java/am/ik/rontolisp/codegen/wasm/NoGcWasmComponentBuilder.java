/*
 * Copyright (C) 2025 Toshiaki Maki <makingx@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.ComponentWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the compact {@code --no-gc --component} output: the single MVP core module
 * emitted by {@link NoGcWasmCompiler}, wrapped as a reactor-style WASM component whose
 * only exports are the {@code rontolisp:wasm-export} functions, each lifted
 * <strong>synchronously</strong> through the canonical ABI (against an async function
 * type when the program prints; see below).
 *
 * <p>
 * Unlike the GC path's {@link WasmComponentBuilder}, a print-free program needs nothing
 * else: the core module has zero imports, so there is no import block, no WASI adapter
 * module and no shared-memory module -- the component is just
 * {@code core module 0 -> core instance 0 -> alias / type / lift / export} per export.
 * The result runs on any component-model host with <em>no</em> wasm-GC support and no
 * extra flags ({@code wasmtime run --invoke 'name(args)' out.wasm}), and there is no
 * {@code wasi:cli/run} export (a {@code --no-gc} module is a pure-compute reactor).
 *
 * <p>
 * A <strong>printing</strong> program additionally gets the print micro-adapter: the core
 * module -- still byte-identical to the plain {@code --no-gc} output, whose single import
 * is {@code wasi_snapshot_preview1.fd_write} (the {@code __write_stdout} seam) -- is
 * joined by three tiny fixed core modules implementing that import over WASI 0.3
 * ({@code wasi:cli/stdout@0.3.0}'s {@code write-via-stream} plus the async
 * {@code stream.write}/{@code future.read} canon built-ins, parking on a blocking
 * {@code waitable-set.wait} when one reports BLOCKED -- all of base
 * {@code component-model-async}, default-on in wasmtime 46+, so the zero-flag property
 * survives). Only an async-typed task may block, so every export of a printing program is
 * lifted against an <strong>async</strong> function type (the same async-typed sync-ABI
 * lift as the GC path's {@code :async t} exports; the flat core signature and the
 * post-return are unchanged). The bridge must read the iovec out of the CORE's own
 * exported memory while the core imports {@code fd_write} from the bridge, so the
 * instantiation cycle is broken with the wit-component shim/fixup adapter pattern:
 * instantiate a funcref-table shim, instantiate the core against it, alias the core's
 * memory, lower the WASI function and emit the canon built-ins with it, instantiate the
 * bridge, and let the fixup module's element segment patch the real {@code fd_write} into
 * the shim's table. A print-free program's component stays byte-identical to the
 * adapter-free shape (everything here is gated on {@code printUsed}).
 *
 * <p>
 * A {@code :string} boundary type lifts through the canonical string ABI over the
 * module's <em>own</em> exported memory: the alias section additionally projects
 * {@code memory} (already aliased when printing), the {@code cabi_realloc} shim (the host
 * lowers string arguments into the callee's memory through it) and one
 * {@code cabi_post_*} post-return function per flat-result signature (it pops the bump
 * heap back to its base once the host has copied the results out, so a resident instance
 * stays flat), and the string-involving exports are lifted with the
 * {@code (memory 0) (realloc ...) string-encoding=utf8 (post-return ...)} options. A
 * program with no {@code :string} export gets none of this -- its component is
 * byte-identical to the Release 1 scalar-only shape.
 */
final class NoGcWasmComponentBuilder {

	/** The canonical-ABI reallocation function's core export name. */
	static final String CABI_REALLOC = "cabi_realloc";

	/** Classpath location of the embedded component blobs, relative to this class. */
	private static final String RES = "component/";

	/**
	 * Pre-built component type/import sections declaring the WASI 0.3 stdout interface
	 * the print micro-adapter binds, captured from a {@code wasm-tools}-generated
	 * reference for the {@code uni-nogc-print} WIT world (see
	 * {@code src/wasm-component/uni-nogc-print.wit}). In order, component import
	 * instances 0-1 are: {@code wasi:cli/types} (dependency-hoisted first, for the
	 * {@code error-code} enum) and {@code wasi:cli/stdout}. The block defines component
	 * types 0-2 (the two instance types plus the aliased {@code error-code} at type 1),
	 * so the next free component type index is 3.
	 */
	private static final byte[] IMPORT_BLOCK_PRINT = resource("import-block-nogc-print.bin");

	/**
	 * The funcref-table shim whose exported {@code fd_write} forwards through table slot
	 * 0, instantiated first so the core can instantiate before the bridge exists. Source:
	 * {@code src/wasm-component/shim-nogc-print.wat}.
	 */
	private static final byte[] SHIM_MODULE_PRINT = resource("shim-nogc-print.wasm");

	/**
	 * The print micro-adapter: implements {@code fd_write} (fd 1 only) over the core's
	 * memory, the lowered {@code write-via-stream} and the async stream/future canon
	 * built-ins, parking on a blocking {@code waitable-set.wait} when one reports
	 * BLOCKED. Source: {@code src/wasm-component/bridge-nogc-print.wat}.
	 */
	private static final byte[] BRIDGE_MODULE_PRINT = resource("bridge-nogc-print.wasm");

	/**
	 * The fixup module whose element segment patches the bridge's real {@code fd_write}
	 * into the shim's table. Source: {@code src/wasm-component/fixup-nogc-print.wat}.
	 */
	private static final byte[] FIXUP_MODULE_PRINT = resource("fixup-nogc-print.wasm");

	// Component import-instance indices (from import-block-nogc-print.bin).
	private static final int INST_CLI_STDOUT = 1;

	// The cli error-code enum, aliased out of wasi:cli/types INSIDE the import block.
	private static final int T_CLI_ERRCODE = 1;

	// First free component type index after import-block-nogc-print.bin (types 0-2),
	// where the stream<u8> / result<_, error-code> / future<result> types the async
	// built-ins are typed by are defined.
	private static final int T_STREAM = 3;

	private static final int T_CLI_RESULT = 4;

	private static final int T_CLI_FUTURE = 5;

	// First free component type index after the defined async types above.
	private static final int PRINT_TYPE_BASE = 6;

	private NoGcWasmComponentBuilder() {
	}

	/**
	 * Wrap the non-GC core module as a component exposing the given exports.
	 * @param coreModule the plain MVP core module (zero imports, or exactly the one
	 * {@code wasi_snapshot_preview1.fd_write} import when the program prints;
	 * core-exports each wrapper under its export name, plus the canonical string ABI
	 * helpers when a {@code :string} boundary is present)
	 * @param decls the parsed export directives (never empty; {@code --no-gc} requires at
	 * least one export directive)
	 * @param printUsed whether the core module prints (imports {@code fd_write}); when
	 * {@code true} the print micro-adapter modules are wired in
	 * @return the component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmExportCompiler.Decl> decls, boolean printUsed) {
		final ComponentWriter c = new ComponentWriter();
		if (printUsed) {
			c.writeRaw(IMPORT_BLOCK_PRINT);
		}
		// Core module 0 = the whole no-gc program (byte-identical to the non-component
		// output either way).
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Index bases the print micro-adapter wiring shifts: the rontolisp core
		// instance, the first free core function / component type / component function
		// index, and whether the core's memory is already aliased as core memory 0.
		final int coreInstance;
		int coreFunc = 0;
		int typeBase = 0;
		int componentFuncBase = 0;
		boolean memoryAliased = false;
		if (printUsed) {
			// Core modules 1-3 = shim / bridge / fixup (fixed blobs).
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, SHIM_MODULE_PRINT);
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, BRIDGE_MODULE_PRINT);
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, FIXUP_MODULE_PRINT);
			// Instantiate the shim (core instance 0), then the core against it (core
			// instance 1): the shim's fd_write forwards through table slot 0, filled by
			// the fixup below only after the bridge exists.
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
				.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of(), List.of()),
						ComponentWriter.coreInstanceInstantiate(0, List.of("wasi_snapshot_preview1"), List.of(0)))));
			coreInstance = 1;
			// Alias the core's own memory (core memory 0) and write-via-stream
			// (component func 0).
			c.rawSection(ComponentWriter.SEC_ALIAS,
					ComponentWriter.vec(List.of(ComponentWriter.aliasCoreMemory(coreInstance, "memory"),
							ComponentWriter.aliasInstanceFunc(INST_CLI_STDOUT, "write-via-stream"))));
			memoryAliased = true;
			// Define the async value types the built-ins are typed by (component types
			// 3-5): stream<u8>, result<_, cli error-code>, future<result>.
			c.rawSection(ComponentWriter.SEC_TYPE,
					ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 3
							ComponentWriter.definedResultErr(T_CLI_ERRCODE), // 4
							ComponentWriter.definedFuture(T_CLI_RESULT)))); // 5
			// Lower write-via-stream (core func 0; stream/future handles are flat i32s,
			// no canonical options) and emit the async built-ins (core funcs 1-8) over
			// the core's memory. stream-write / future-read-cli are the ASYNC
			// (non-blocking) variants of base component-model-async: the bridge parks on
			// the waitable-set trio when one reports BLOCKED, so no gated wasmtime
			// feature is involved.
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLower(0), // 0
																												// stdout-write
					ComponentWriter.canonStreamNew(T_STREAM), // 1
					ComponentWriter.canonStreamWriteAsync(T_STREAM, 0), // 2
					ComponentWriter.canonStreamDropWritable(T_STREAM), // 3
					ComponentWriter.canonFutureReadAsync(T_CLI_FUTURE, 0), // 4
					ComponentWriter.canonFutureDropReadable(T_CLI_FUTURE), // 5
					ComponentWriter.canonWaitableSetNew(), // 6
					ComponentWriter.canonWaitableJoin(), // 7
					ComponentWriter.canonWaitableSetWait(0)))); // 8
			// Group them for the bridge's "w" import (core instance 2) and instantiate
			// the bridge (core instance 3) against the core's memory.
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(
					ComponentWriter.coreInstanceFromFuncs(
							List.of("stdout-write", "stream-new", "stream-write", "stream-drop-w", "future-read-cli",
									"future-drop-cli", "waitable-set-new", "waitable-join", "waitable-set-wait"),
							List.of(0, 1, 2, 3, 4, 5, 6, 7, 8)),
					ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "w"), List.of(coreInstance, 2)))));
			// Alias the shim's table (core table 0) and the bridge's real fd_write (core
			// func 9), group them (core instance 4) and instantiate the fixup (core
			// instance 5), whose element segment patches table slot 0.
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
				.of(ComponentWriter.aliasCoreTable(0, "$imports"), ComponentWriter.aliasCoreFunc(3, "fd_write"))));
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
					ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromExports(List.of("$imports", "fd_write"),
							List.of(ComponentWriter.CORE_SORT_TABLE, ComponentWriter.CORE_SORT_FUNC), List.of(0, 9)),
							ComponentWriter.coreInstanceInstantiate(3, List.of(""), List.of(4)))));
			coreFunc = 10;
			typeBase = PRINT_TYPE_BASE;
			componentFuncBase = 1;
		}
		else {
			// Print-free: instantiate the core with no arguments (core instance 0) -- it
			// has no imports.
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
					ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
			coreInstance = 0;
		}
		final List<byte[]> aliases = new ArrayList<>();
		final List<byte[]> types = new ArrayList<>();
		final List<byte[]> lifts = new ArrayList<>();
		final List<byte[]> exports = new ArrayList<>();
		// The canonical string ABI aliases (memory, realloc, post-returns) come first in
		// this block's core function/memory index spaces; they exist only when a :string
		// boundary is present, so a scalar-only component keeps the Release 1 bytes (or
		// the plain print wiring above).
		int realloc = -1;
		final Map<String, Integer> postFuncs = new LinkedHashMap<>();
		if (decls.stream().anyMatch(WasmExportCompiler::usesMemory)) {
			if (!memoryAliased) {
				aliases.add(ComponentWriter.aliasCoreMemory(coreInstance, "memory"));
			}
			aliases.add(ComponentWriter.aliasCoreFunc(coreInstance, CABI_REALLOC));
			realloc = coreFunc++;
			for (WasmExportCompiler.Decl d : decls) {
				if (WasmExportCompiler.usesMemory(d)) {
					String kind = postReturnKind(d);
					if (!postFuncs.containsKey(kind)) {
						aliases.add(ComponentWriter.aliasCoreFunc(coreInstance, postReturnExportName(kind)));
						postFuncs.put(kind, coreFunc++);
					}
				}
			}
		}
		// Per export i: alias the core wrapper, declare its function type (type
		// typeBase + i), lift it (component func componentFuncBase + i) and export it.
		// Types/lifts/exports line up 1:1 with the ordinal; only the core function index
		// is shifted by the print/ABI wiring above. A printing program's exports are
		// lifted against ASYNC function types -- the bridge's blocking waitable-set park
		// is legal only inside an async-typed task -- with the same flat core signature
		// (the lift below is unchanged, and the post-return survives the async type); a
		// print-free program's exports stay sync lifts.
		for (int i = 0; i < decls.size(); i++) {
			WasmExportCompiler.Decl decl = decls.get(i);
			WasmComponentBuilder.FuncExport e = WasmExportCompiler.componentExport(decl);
			aliases.add(ComponentWriter.aliasCoreFunc(coreInstance, e.name()));
			int func = coreFunc++;
			// p0, p1, ... unless the directive names the parameters (:param-names, or the
			// WIT world's own names under rontolisp:wit-export).
			types.add(printUsed
					? ComponentWriter.asyncFuncTypeScalars(e.paramNames(), e.paramValTypes(), e.resultValType())
					: ComponentWriter.funcTypeScalars(e.paramNames(), e.paramValTypes(), e.resultValType()));
			if (WasmExportCompiler.usesMemory(decl)) {
				// String-involving export: lift with the canonical string options.
				int postFunc = java.util.Objects.requireNonNull(postFuncs.get(postReturnKind(decl)));
				lifts.add(
						ComponentWriter.canonLiftMemoryReallocUtf8PostReturn(func, typeBase + i, 0, realloc, postFunc));
			}
			else {
				// Sync lift with no canonical options: flat scalars need no
				// memory/realloc.
				lifts.add(ComponentWriter.canonLift(func, typeBase + i));
			}
			exports.add(ComponentWriter.exportFunc(e.name(), componentFuncBase + i));
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(types));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lifts));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(exports));
		return c.toByteArray();
	}

	/**
	 * The flat-result signature of a string-involving export's core function, naming
	 * which shared {@code cabi_post_*} post-return function its lift uses: a
	 * {@code :string} result flattens to a single i32 return pointer
	 * ({@code MAX_FLAT_RESULTS} = 1), every scalar result keeps its own flat type.
	 * @param decl the parsed export directive
	 * @return the signature key ({@code "i32"}/{@code "i64"}/{@code "f64"}/
	 * {@code "void"})
	 */
	static String postReturnKind(WasmExportCompiler.Decl decl) {
		return switch (decl.returnType()) {
			case WasmExportCompiler.T_STRING, WasmExportCompiler.T_INT, WasmExportCompiler.T_BOOL -> "i32";
			case WasmExportCompiler.T_LONG -> "i64";
			case WasmExportCompiler.T_FLOAT -> "f64";
			case WasmExportCompiler.T_VOID -> "void";
			default -> throw new UnsupportedOperationException(
					"rontolisp:wasm-export type " + decl.returnType() + " has no component post-return signature");
		};
	}

	/**
	 * The core export name of the shared post-return function for a flat-result
	 * signature.
	 * @param kind the signature key from {@link #postReturnKind}
	 * @return the core export name (e.g. {@code "cabi_post_i32"})
	 */
	static String postReturnExportName(String kind) {
		return "cabi_post_" + kind;
	}

	/**
	 * Load a fixed component blob bundled as a classpath resource next to this class.
	 * @param name the file name under {@code component/}
	 * @return the raw bytes
	 */
	private static byte[] resource(String name) {
		try (InputStream in = NoGcWasmComponentBuilder.class.getResourceAsStream(RES + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing component resource: " + RES + name);
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to read component resource: " + RES + name, ex);
		}
	}

}
