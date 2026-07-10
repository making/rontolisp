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
 * Unlike the GC path's {@link WasmComponentBuilder}, nothing else is needed: the core
 * module has zero imports (printing is rejected under {@code --component}), so there is
 * no import block, no WASI adapter module and no shared-memory module -- the component is
 * just {@code core module 0 -> core instance 0 -> alias / type / lift / export} per
 * export. The result runs on any component-model host with <em>no</em> wasm-GC support
 * and no extra flags ({@code wasmtime run --invoke 'name(args)' out.wasm}), and there is
 * no {@code wasi:cli/run} export (a {@code --no-gc} module is a pure-compute reactor).
 *
 * <p>
 * A {@code :string} boundary type (todo 93 Tier 2) lifts through the canonical string ABI
 * over the module's <em>own</em> exported memory: the alias section additionally projects
 * {@code memory}, the {@code cabi_realloc} shim (the host lowers string arguments into
 * the callee's memory through it) and one {@code cabi_post_*} post-return function per
 * flat-result signature (it pops the bump heap back to its base once the host has copied
 * the results out, so a resident instance stays flat), and the string-involving exports
 * are lifted with the
 * {@code (memory 0) (realloc ...) string-encoding=utf8 (post-return ...)} options. A
 * program with no {@code :string} export gets none of this -- its component is
 * byte-identical to the Release 1 scalar-only shape.
 */
final class NoGcWasmComponentBuilder {

	/** The canonical-ABI reallocation function's core export name. */
	static final String CABI_REALLOC = "cabi_realloc";

	private NoGcWasmComponentBuilder() {
	}

	/**
	 * Wrap the non-GC core module as a component exposing the given exports.
	 * @param coreModule the plain MVP core module (zero imports; core-exports each
	 * wrapper under its export name, plus the canonical string ABI helpers when a
	 * {@code :string} boundary is present)
	 * @param decls the parsed export directives (never empty; {@code --no-gc} requires at
	 * least one export directive)
	 * @return the component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmExportCompiler.Decl> decls) {
		final ComponentWriter c = new ComponentWriter();
		// Core module 0 = the whole no-gc program; instantiate it with no arguments
		// (core instance 0) -- it has no imports.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		final List<byte[]> aliases = new ArrayList<>();
		final List<byte[]> types = new ArrayList<>();
		final List<byte[]> lifts = new ArrayList<>();
		final List<byte[]> exports = new ArrayList<>();
		// The canonical string ABI aliases (memory, realloc, post-returns) come first in
		// the core function/memory index spaces; they exist only when a :string boundary
		// is present, so a scalar-only component keeps the Release 1 bytes.
		int coreFunc = 0;
		int realloc = -1;
		final Map<String, Integer> postFuncs = new LinkedHashMap<>();
		if (decls.stream().anyMatch(WasmExportCompiler::usesMemory)) {
			aliases.add(ComponentWriter.aliasCoreMemory(0, "memory"));
			aliases.add(ComponentWriter.aliasCoreFunc(0, CABI_REALLOC));
			realloc = coreFunc++;
			for (WasmExportCompiler.Decl d : decls) {
				if (WasmExportCompiler.usesMemory(d)) {
					String kind = postReturnKind(d);
					if (!postFuncs.containsKey(kind)) {
						aliases.add(ComponentWriter.aliasCoreFunc(0, postReturnExportName(kind)));
						postFuncs.put(kind, coreFunc++);
					}
				}
			}
		}
		// Per export i: alias the core wrapper, declare its function type (type i), lift
		// it synchronously (component func i) and export it. Types/lifts/exports line up
		// 1:1 with the ordinal; only the core function index is shifted by the ABI
		// aliases above.
		for (int i = 0; i < decls.size(); i++) {
			WasmExportCompiler.Decl decl = decls.get(i);
			WasmComponentBuilder.FuncExport e = WasmExportCompiler.componentExport(decl);
			aliases.add(ComponentWriter.aliasCoreFunc(0, e.name()));
			int func = coreFunc++;
			final List<String> paramNames = new ArrayList<>();
			for (int p = 0; p < e.paramValTypes().size(); p++) {
				paramNames.add("p" + p);
			}
			types.add(ComponentWriter.funcTypeScalars(paramNames, e.paramValTypes(), e.resultValType()));
			if (WasmExportCompiler.usesMemory(decl)) {
				// String-involving export: lift with the canonical string options.
				int postFunc = java.util.Objects.requireNonNull(postFuncs.get(postReturnKind(decl)));
				lifts.add(ComponentWriter.canonLiftMemoryReallocUtf8PostReturn(func, i, 0, realloc, postFunc));
			}
			else {
				// Sync lift with no canonical options: flat scalars need no
				// memory/realloc.
				lifts.add(ComponentWriter.canonLift(func, i));
			}
			exports.add(ComponentWriter.exportFunc(e.name(), i));
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

}
