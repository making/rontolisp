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
 * <strong>synchronously</strong> through the canonical ABI.
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
 * A <strong>printing</strong> program (todo 93 remaining task 1) additionally gets the
 * print micro-adapter: the core module -- still byte-identical to the plain
 * {@code --no-gc} output, whose single import is {@code wasi_snapshot_preview1.fd_write}
 * (the todo-110 {@code __write_stdout} seam) -- is joined by three tiny fixed core
 * modules implementing that import over WASI 0.2 stdio ({@code wasi:cli/stdout@0.2.0} +
 * {@code wasi:io/streams@0.2.0}'s synchronous {@code blocking-write-and-flush}, both
 * default-provided by wasmtime with zero flags; the exports stay sync lifts). The bridge
 * must read the iovec out of the CORE's own exported memory while the core imports
 * {@code fd_write} from the bridge, so the instantiation cycle is broken with the
 * wit-component shim/fixup adapter pattern: instantiate a funcref-table shim, instantiate
 * the core against it, alias the core's memory, lower the two WASI functions with it,
 * instantiate the bridge, and let the fixup module's element segment patch the real
 * {@code fd_write} into the shim's table. A print-free program's component stays
 * byte-identical to the adapter-free shape (everything here is gated on
 * {@code printUsed}).
 *
 * <p>
 * A {@code :string} boundary type (todo 93 Tier 2) lifts through the canonical string ABI
 * over the module's <em>own</em> exported memory: the alias section additionally projects
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
	 * Pre-built component type/import sections declaring the two WASI 0.2 stdio
	 * interfaces the print micro-adapter binds, captured from a
	 * {@code wasm-tools}-generated reference for the {@code uni-nogc-print} WIT world
	 * (see {@code src/wasm-component/uni-nogc-print.wit}). In order, component import
	 * instances 0-2 are: {@code wasi:io/error} (dependency-hoisted first),
	 * {@code wasi:io/streams} and {@code wasi:cli/stdout}. The block defines component
	 * types 0-4, so the next free component type index is 5.
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
	 * memory and the two lowered WASI 0.2 functions, chunking through
	 * {@code blocking-write-and-flush}. Source:
	 * {@code src/wasm-component/bridge-nogc-print.wat}.
	 */
	private static final byte[] BRIDGE_MODULE_PRINT = resource("bridge-nogc-print.wasm");

	/**
	 * The fixup module whose element segment patches the bridge's real {@code fd_write}
	 * into the shim's table. Source: {@code src/wasm-component/fixup-nogc-print.wat}.
	 */
	private static final byte[] FIXUP_MODULE_PRINT = resource("fixup-nogc-print.wasm");

	// Component import-instance indices (from import-block-nogc-print.bin).
	private static final int INST_IO_STREAMS = 1;

	private static final int INST_STDOUT = 2;

	// First free component type index after import-block-nogc-print.bin (types 0-4).
	private static final int PRINT_TYPE_BASE = 5;

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
			// Alias the core's own memory (core memory 0) and the two WASI functions
			// (component funcs 0-1).
			c.rawSection(ComponentWriter.SEC_ALIAS,
					ComponentWriter.vec(List.of(ComponentWriter.aliasCoreMemory(coreInstance, "memory"),
							ComponentWriter.aliasInstanceFunc(INST_STDOUT, "get-stdout"),
							ComponentWriter.aliasInstanceFunc(INST_IO_STREAMS,
									"[method]output-stream.blocking-write-and-flush"))));
			memoryAliased = true;
			// Lower them (core funcs 0-1): blocking-write-and-flush marshals its
			// list<u8> out of the core's memory, hence the memory option.
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter
				.vec(List.of(ComponentWriter.canonLower(0), ComponentWriter.canonLowerMemory(1, 0))));
			// Group them for the bridge's "w" import (core instance 2) and instantiate
			// the bridge (core instance 3) against the core's memory.
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
				.vec(List.of(ComponentWriter.coreInstanceFromFuncs(List.of("get-stdout", "io-write"), List.of(0, 1)),
						ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "w"), List.of(coreInstance, 2)))));
			// Alias the shim's table (core table 0) and the bridge's real fd_write (core
			// func 2), group them (core instance 4) and instantiate the fixup (core
			// instance 5), whose element segment patches table slot 0.
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
				.of(ComponentWriter.aliasCoreTable(0, "$imports"), ComponentWriter.aliasCoreFunc(3, "fd_write"))));
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
					ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromExports(List.of("$imports", "fd_write"),
							List.of(ComponentWriter.CORE_SORT_TABLE, ComponentWriter.CORE_SORT_FUNC), List.of(0, 2)),
							ComponentWriter.coreInstanceInstantiate(3, List.of(""), List.of(4)))));
			coreFunc = 3;
			typeBase = PRINT_TYPE_BASE;
			componentFuncBase = 2;
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
		// typeBase + i), lift it synchronously (component func componentFuncBase + i)
		// and export it. Types/lifts/exports line up 1:1 with the ordinal; only the core
		// function index is shifted by the print/ABI wiring above.
		for (int i = 0; i < decls.size(); i++) {
			WasmExportCompiler.Decl decl = decls.get(i);
			WasmComponentBuilder.FuncExport e = WasmExportCompiler.componentExport(decl);
			aliases.add(ComponentWriter.aliasCoreFunc(coreInstance, e.name()));
			int func = coreFunc++;
			final List<String> paramNames = new ArrayList<>();
			for (int p = 0; p < e.paramValTypes().size(); p++) {
				paramNames.add("p" + p);
			}
			types.add(ComponentWriter.funcTypeScalars(paramNames, e.paramValTypes(), e.resultValType()));
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
