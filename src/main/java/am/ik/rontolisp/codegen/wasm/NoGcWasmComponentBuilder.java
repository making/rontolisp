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
import java.util.List;

/**
 * Assembles the compact {@code --no-gc --component} output: the single MVP core module
 * emitted by {@link NoGcWasmCompiler}, wrapped as a reactor-style WASM component whose
 * only exports are the scalar {@code rontolisp:wasm-export} functions, each lifted
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
 */
final class NoGcWasmComponentBuilder {

	private NoGcWasmComponentBuilder() {
	}

	/**
	 * Wrap the non-GC core module as a component exposing the given scalar exports.
	 * @param coreModule the plain MVP core module (zero imports; core-exports each
	 * wrapper under its export name)
	 * @param funcExports the scalar function exports (never empty; {@code --no-gc}
	 * requires at least one export directive)
	 * @return the component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmComponentBuilder.FuncExport> funcExports) {
		final ComponentWriter c = new ComponentWriter();
		// Core module 0 = the whole no-gc program; instantiate it with no arguments
		// (core instance 0) -- it has no imports.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Per export i: alias the core wrapper (core func i), declare its scalar
		// function type (type i), lift it synchronously (component func i) and export
		// it. All index spaces start empty, so the ordinals line up 1:1.
		final List<byte[]> aliases = new ArrayList<>();
		final List<byte[]> types = new ArrayList<>();
		final List<byte[]> lifts = new ArrayList<>();
		final List<byte[]> exports = new ArrayList<>();
		for (int i = 0; i < funcExports.size(); i++) {
			WasmComponentBuilder.FuncExport e = funcExports.get(i);
			aliases.add(ComponentWriter.aliasCoreFunc(0, e.name()));
			final List<String> paramNames = new ArrayList<>();
			for (int p = 0; p < e.paramValTypes().size(); p++) {
				paramNames.add("p" + p);
			}
			types.add(ComponentWriter.funcTypeScalars(paramNames, e.paramValTypes(), e.resultValType()));
			// Sync lift with no canonical options: flat scalars need no memory/realloc.
			lifts.add(ComponentWriter.canonLift(i, i));
			exports.add(ComponentWriter.exportFunc(e.name(), i));
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(types));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lifts));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(exports));
		return c.toByteArray();
	}

}
