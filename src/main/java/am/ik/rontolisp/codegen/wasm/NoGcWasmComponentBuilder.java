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

import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.wasm.ComponentWriter;
import am.ik.wasm.Type;
import am.ik.wasm.WasmShimModules;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Assembles the compact {@code --no-gc --component} output: the single MVP core module
 * emitted by {@link NoGcWasmCompiler}, wrapped as a reactor-style WASM component whose
 * only exports are the {@code rontolisp:wasm-export} functions, each lifted
 * <strong>synchronously</strong> through the canonical ABI (against an async function
 * type when the program prints; see below), and whose only imports are the
 * {@code rontolisp:wasm-import} host functions the exports reach, each a component-model
 * instance import {@code canon lower}ed into the core.
 *
 * <p>
 * Unlike the GC path's {@link WasmComponentBuilder}, a print-free, import-free program
 * needs nothing else: the core module has zero imports, so there is no import block, no
 * WASI adapter module and no shared-memory module -- the component is just
 * {@code core module 0 -> core instance 0 -> alias / type / lift / export} per export.
 * The result runs on any component-model host with <em>no</em> wasm-GC support and no
 * extra flags ({@code wasmtime run --invoke 'name(args)' out.wasm}), and there is no
 * {@code wasi:cli/run} export (a {@code --no-gc} module is a pure-compute reactor).
 *
 * <p>
 * <strong>Host imports</strong> ({@code rontolisp:wasm-import}, and the
 * {@code rontolisp:wit-import} lowering that carries an interface id) become one imported
 * component instance per import module, typed by the functions the core reaches (the
 * import's {@code :param-names} are the labels of the instance type, so a WIT world's
 * names must be carried for a composed provider to type-check). A scalar-only import is
 * {@code alias -> canon lower -> core instance} ahead of the core's instantiation, like
 * wit-component's direct lowerings. A {@code :string} argument or result makes the lower
 * name the core's OWN memory (and, for a result, its {@code cabi_realloc}), which cannot
 * exist before the core does -- the instantiation cycle wit-component breaks with a
 * funcref-table shim and a fixup module, generated here from the import signatures
 * ({@link WasmShimModules}): the core instantiates against the shim's trampolines, and
 * once its memory is aliased the real lowered functions are patched into the table. A
 * {@code :string} RESULT crosses the canonical way, a trailing return pointer the host
 * fills, so the core's import signature differs from the Preview 1 module's two-value one
 * -- the one place the core inside the component is not byte-identical to the plain
 * output.
 *
 * <p>
 * A <strong>printing</strong> program additionally gets the print micro-adapter: the core
 * module -- still byte-identical to the plain {@code --no-gc} output, whose single WASI
 * import is {@code wasi_snapshot_preview1.fd_write} (the {@code __write_stdout} seam) --
 * is joined by three tiny fixed core modules implementing that import over WASI 0.3
 * ({@code wasi:cli/stdout@0.3.0}'s {@code write-via-stream} plus the async
 * {@code stream.write}/{@code future.read} canon built-ins, parking on a blocking
 * {@code waitable-set.wait} when one reports BLOCKED -- all of base
 * {@code component-model-async}, default-on in wasmtime 46+, so the zero-flag property
 * survives). Only an async-typed task may block, so every export of a printing program is
 * lifted against an <strong>async</strong> function type (the same async-typed sync-ABI
 * lift as the GC path's {@code :async t} exports; the flat core signature and the
 * post-return are unchanged). The bridge must read the iovec out of the CORE's own
 * exported memory while the core imports {@code fd_write} from the bridge, so this
 * instantiation cycle too is broken with the shim/fixup pattern -- the print pair is
 * fixed ({@code src/wasm-component/*-nogc-print.wat}) and composes with the generated
 * user pair: two tables, two fixups, one core instantiated against both. A print-free
 * program's component stays byte-identical to the adapter-free shape (everything here is
 * gated on {@code printUsed}).
 *
 * <p>
 * A {@code :string} boundary type lifts through the canonical string ABI over the
 * module's <em>own</em> exported memory: the alias section additionally projects
 * {@code memory} (already aliased when printing or when a string import exists), the
 * {@code cabi_realloc} shim (the host lowers string arguments into the callee's memory
 * through it) and one {@code cabi_post_*} post-return function per flat-result signature
 * (it pops the bump heap back to its base once the host has copied the results out, so a
 * resident instance stays flat), and the string-involving exports are lifted with the
 * {@code (memory 0) (realloc ...) string-encoding=utf8 (post-return ...)} options. A
 * program with no {@code :string} export gets none of this -- its component is
 * byte-identical to the Release 1 scalar-only shape.
 *
 * <p>
 * Every index is a cursor advanced in emission order, never a constant: the print wiring,
 * the user import block and the export wiring each consume from the same component type /
 * function / instance and core function / instance / table / memory spaces, and which of
 * them exist depends on the program. With none of the optional parts present each cursor
 * starts where the fixed shape's indices used to be, which is what keeps every
 * pre-existing output byte-identical.
 */
final class NoGcWasmComponentBuilder {

	/** The canonical-ABI reallocation function's core export name. */
	static final String CABI_REALLOC = "cabi_realloc";

	/**
	 * A fully-qualified WIT interface id as a component import name
	 * ({@code ns:pkg/iface}, optionally {@code @version}, nested namespaces allowed) --
	 * the only import-name shape besides a plain kebab label the wrap emits. Which of the
	 * two a module name is decides how {@code --emit-wit} prints it: an id is a
	 * {@code import ns:pkg/iface;} with a package block, a label an inline {@code import
	 * label: interface {...}}.
	 */
	static final Pattern INTERFACE_ID = Pattern.compile(
			"[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*" + "(:[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*)+/[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*"
					+ "(@[0-9]+\\.[0-9]+\\.[0-9]+([-+][0-9A-Za-z.-]+)?)?");

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

	// What the print import block consumes of the component instance and type spaces:
	// two imported instances (wasi:cli/types = 0, wasi:cli/stdout = 1) and three types
	// (the two instance types plus the aliased error-code at type 1).
	private static final int PRINT_BLOCK_INSTANCES = 2;

	private static final int PRINT_BLOCK_TYPES = 3;

	// The cli error-code enum, aliased out of wasi:cli/types INSIDE the import block.
	private static final int T_CLI_ERRCODE = 1;

	// The core module the print shim / bridge / fixup blobs take, right after the core.
	private static final int MODULE_PRINT_SHIM = 1;

	private static final int MODULE_PRINT_BRIDGE = 2;

	private static final int MODULE_PRINT_FIXUP = 3;

	private NoGcWasmComponentBuilder() {
	}

	/**
	 * Wrap the non-GC core module as a component exposing the given exports.
	 * @param coreModule the plain MVP core module (core-exports each wrapper under its
	 * export name, plus the canonical string ABI helpers when a {@code :string} boundary
	 * is present; imports the reached host functions and, when the program prints,
	 * {@code wasi_snapshot_preview1.fd_write})
	 * @param decls the parsed export directives (never empty; {@code --no-gc} requires at
	 * least one export directive)
	 * @param printUsed whether the core module prints (imports {@code fd_write}); when
	 * {@code true} the print micro-adapter modules are wired in
	 * @return the component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmExportCompiler.Decl> decls, boolean printUsed) {
		return build(coreModule, decls, printUsed, List.of());
	}

	/**
	 * Wrap the non-GC core module as a component exposing the given exports and importing
	 * the given host functions.
	 * @param coreModule the plain MVP core module (see the three-argument form)
	 * @param decls the parsed export directives (never empty)
	 * @param printUsed whether the core module prints (imports {@code fd_write})
	 * @param imports the REACHED host imports in the core's import order (the ones its
	 * import section names; a declared-but-uncalled import is not among them)
	 * @return the component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmExportCompiler.Decl> decls, boolean printUsed,
			List<WasmImportCompiler.Decl> imports) {
		final ComponentWriter c = new ComponentWriter();
		// The cursors: the next free index of every space the wiring below consumes.
		int nextType = 0;
		int nextComponentFunc = 0;
		int nextComponentInstance = 0;
		int nextCoreModule = 0;
		int nextCoreFunc = 0;
		int nextCoreInstance = 0;
		int nextCoreTable = 0;
		int coreMemory = -1; // the core's memory once aliased (core memory 0)
		int realloc = -1; // the core's cabi_realloc once aliased
		if (printUsed) {
			c.writeRaw(IMPORT_BLOCK_PRINT);
			nextType = PRINT_BLOCK_TYPES;
			nextComponentInstance = PRINT_BLOCK_INSTANCES;
		}
		// Core module 0 = the whole no-gc program (byte-identical to the non-component
		// output, except for the import signature of a :string-returning import).
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		nextCoreModule++;
		if (printUsed) {
			// Core modules 1-3 = shim / bridge / fixup (fixed blobs).
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, SHIM_MODULE_PRINT);
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, BRIDGE_MODULE_PRINT);
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, FIXUP_MODULE_PRINT);
			nextCoreModule += 3;
		}
		// The user import block. Imports are grouped by module (the component instance
		// they come from) in first-appearance order; the string-involving ones are
		// routed through a generated shim/fixup pair, the scalar ones lowered directly.
		final LinkedHashMap<String, List<WasmImportCompiler.Decl>> groups = new LinkedHashMap<>();
		for (WasmImportCompiler.Decl decl : imports) {
			groups.computeIfAbsent(decl.module(), k -> new ArrayList<>()).add(decl);
		}
		final List<WasmImportCompiler.Decl> viaShim = new ArrayList<>();
		for (WasmImportCompiler.Decl decl : imports) {
			if (touchesMemory(decl)) {
				viaShim.add(decl);
			}
		}
		int userShimModule = -1;
		int userFixupModule = -1;
		if (!viaShim.isEmpty()) {
			final List<Type[]> params = new ArrayList<>();
			final List<Type[]> results = new ArrayList<>();
			for (WasmImportCompiler.Decl decl : viaShim) {
				params.add(coreParamTypes(decl));
				results.add(coreResultTypes(decl));
			}
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, WasmShimModules.shim(params, results));
			userShimModule = nextCoreModule++;
			c.rawSection(ComponentWriter.SEC_CORE_MODULE, WasmShimModules.fixup(params, results));
			userFixupModule = nextCoreModule++;
		}
		// One instance type + one instance import per module.
		final Map<String, Integer> instanceOf = new LinkedHashMap<>();
		if (!groups.isEmpty()) {
			final List<byte[]> instanceTypes = new ArrayList<>();
			final List<byte[]> instanceImports = new ArrayList<>();
			for (Map.Entry<String, List<WasmImportCompiler.Decl>> group : groups.entrySet()) {
				instanceTypes.add(instanceType(group.getValue()));
				instanceImports.add(ComponentWriter.importInstance(group.getKey(), nextType++));
				instanceOf.put(group.getKey(), nextComponentInstance++);
			}
			c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(instanceTypes));
			c.rawSection(ComponentWriter.SEC_IMPORT, ComponentWriter.vec(instanceImports));
		}
		// Scalar imports: alias the instance's function and canon-lower it with no
		// options, ahead of the core's instantiation.
		final Map<WasmImportCompiler.Decl, Integer> coreFuncOf = new LinkedHashMap<>();
		{
			final List<byte[]> funcAliases = new ArrayList<>();
			final List<byte[]> lowers = new ArrayList<>();
			for (WasmImportCompiler.Decl decl : imports) {
				if (touchesMemory(decl)) {
					continue;
				}
				funcAliases.add(ComponentWriter.aliasInstanceFunc(Objects.requireNonNull(instanceOf.get(decl.module())),
						decl.field()));
				lowers.add(ComponentWriter.canonLower(nextComponentFunc++));
				coreFuncOf.put(decl, nextCoreFunc++);
			}
			if (!funcAliases.isEmpty()) {
				c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(funcAliases));
				c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lowers));
			}
		}
		// Core instances, in dependency order: the shims first (the print shim, then the
		// user shim), the user shim's trampolines aliased, the per-module import
		// instances grouped, then the core against all of them. The pending list is
		// flushed only where an alias section must intervene, so a program without a
		// user shim emits the print shim and the core in ONE section, as it always did.
		final List<byte[]> pendingInstances = new ArrayList<>();
		final List<String> coreArgNames = new ArrayList<>();
		final List<Integer> coreArgInstances = new ArrayList<>();
		if (printUsed) {
			pendingInstances.add(ComponentWriter.coreInstanceInstantiate(MODULE_PRINT_SHIM, List.of(), List.of()));
			coreArgNames.add("wasi_snapshot_preview1");
			coreArgInstances.add(nextCoreInstance++);
		}
		int userShimInstance = -1;
		if (!viaShim.isEmpty()) {
			pendingInstances.add(ComponentWriter.coreInstanceInstantiate(userShimModule, List.of(), List.of()));
			userShimInstance = nextCoreInstance++;
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(pendingInstances));
			pendingInstances.clear();
			final List<byte[]> trampolines = new ArrayList<>();
			for (int k = 0; k < viaShim.size(); k++) {
				trampolines.add(ComponentWriter.aliasCoreFunc(userShimInstance, WasmShimModules.slotName(k)));
				coreFuncOf.put(viaShim.get(k), nextCoreFunc++);
			}
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(trampolines));
		}
		for (Map.Entry<String, List<WasmImportCompiler.Decl>> group : groups.entrySet()) {
			final List<String> fields = new ArrayList<>();
			final List<Integer> funcs = new ArrayList<>();
			for (WasmImportCompiler.Decl decl : group.getValue()) {
				fields.add(decl.field());
				funcs.add(Objects.requireNonNull(coreFuncOf.get(decl)));
			}
			pendingInstances.add(ComponentWriter.coreInstanceFromFuncs(fields, funcs));
			coreArgNames.add(group.getKey());
			coreArgInstances.add(nextCoreInstance++);
		}
		pendingInstances.add(ComponentWriter.coreInstanceInstantiate(0, coreArgNames, coreArgInstances));
		final int coreInstance = nextCoreInstance++;
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(pendingInstances));
		if (printUsed) {
			// Alias the core's own memory and write-via-stream.
			c.rawSection(ComponentWriter.SEC_ALIAS,
					ComponentWriter.vec(List.of(ComponentWriter.aliasCoreMemory(coreInstance, "memory"),
							ComponentWriter.aliasInstanceFunc(INST_CLI_STDOUT, "write-via-stream"))));
			coreMemory = 0;
			final int writeViaStream = nextComponentFunc++;
			// Define the async value types the built-ins are typed by: stream<u8>,
			// result<_, cli error-code>, future<result>.
			final int tStream = nextType++;
			final int tCliResult = nextType++;
			final int tCliFuture = nextType++;
			c.rawSection(ComponentWriter.SEC_TYPE,
					ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8),
							ComponentWriter.definedResultErr(T_CLI_ERRCODE),
							ComponentWriter.definedFuture(tCliResult))));
			// Lower write-via-stream (stream/future handles are flat i32s, no canonical
			// options) and emit the async built-ins over the core's memory. stream-write
			// / future-read-cli are the ASYNC (non-blocking) variants of base
			// component-model-async: the bridge parks on the waitable-set trio when one
			// reports BLOCKED, so no gated wasmtime feature is involved.
			c.rawSection(ComponentWriter.SEC_CANON,
					ComponentWriter.vec(List.of(ComponentWriter.canonLower(writeViaStream), // stdout-write
							ComponentWriter.canonStreamNew(tStream),
							ComponentWriter.canonStreamWriteAsync(tStream, coreMemory),
							ComponentWriter.canonStreamDropWritable(tStream),
							ComponentWriter.canonFutureReadAsync(tCliFuture, coreMemory),
							ComponentWriter.canonFutureDropReadable(tCliFuture), ComponentWriter.canonWaitableSetNew(),
							ComponentWriter.canonWaitableJoin(), ComponentWriter.canonWaitableSetWait(coreMemory))));
			final List<Integer> builtins = new ArrayList<>();
			for (int i = 0; i < 9; i++) {
				builtins.add(nextCoreFunc++);
			}
			// Group them for the bridge's "w" import and instantiate the bridge against
			// the core's memory.
			final int wInstance = nextCoreInstance++;
			final int bridgeInstance = nextCoreInstance++;
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(
					ComponentWriter.coreInstanceFromFuncs(
							List.of("stdout-write", "stream-new", "stream-write", "stream-drop-w", "future-read-cli",
									"future-drop-cli", "waitable-set-new", "waitable-join", "waitable-set-wait"),
							builtins),
					ComponentWriter.coreInstanceInstantiate(MODULE_PRINT_BRIDGE, List.of("mem", "w"),
							List.of(coreInstance, wInstance)))));
			// Alias the shim's table and the bridge's real fd_write, group them and
			// instantiate the fixup, whose element segment patches table slot 0.
			final int printShimInstance = coreArgInstances.get(0);
			c.rawSection(ComponentWriter.SEC_ALIAS,
					ComponentWriter.vec(List.of(ComponentWriter.aliasCoreTable(printShimInstance, "$imports"),
							ComponentWriter.aliasCoreFunc(bridgeInstance, "fd_write"))));
			final int printTable = nextCoreTable++;
			final int fdWrite = nextCoreFunc++;
			final int fixupArgs = nextCoreInstance++;
			nextCoreInstance++; // the fixup instance itself
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
					ComponentWriter.vec(List.of(
							ComponentWriter.coreInstanceFromExports(List.of("$imports", "fd_write"),
									List.of(ComponentWriter.CORE_SORT_TABLE, ComponentWriter.CORE_SORT_FUNC),
									List.of(printTable, fdWrite)),
							ComponentWriter.coreInstanceInstantiate(MODULE_PRINT_FIXUP, List.of(""),
									List.of(fixupArgs)))));
		}
		if (!viaShim.isEmpty()) {
			// The real lowered functions of the string-involving imports, over the
			// core's memory (and realloc, for a :string result), then the fixup that
			// patches them into the user shim's table.
			final List<byte[]> aliases = new ArrayList<>();
			if (coreMemory < 0) {
				aliases.add(ComponentWriter.aliasCoreMemory(coreInstance, "memory"));
				coreMemory = 0;
			}
			if (viaShim.stream().anyMatch(NoGcWasmComponentBuilder::returnsString)) {
				aliases.add(ComponentWriter.aliasCoreFunc(coreInstance, CABI_REALLOC));
				realloc = nextCoreFunc++;
			}
			final List<byte[]> lowers = new ArrayList<>();
			final List<Integer> lowered = new ArrayList<>();
			for (WasmImportCompiler.Decl decl : viaShim) {
				aliases.add(ComponentWriter.aliasInstanceFunc(Objects.requireNonNull(instanceOf.get(decl.module())),
						decl.field()));
				lowers.add(returnsString(decl)
						? ComponentWriter.canonLowerMemoryReallocUtf8(nextComponentFunc++, coreMemory, realloc)
						: ComponentWriter.canonLowerMemoryUtf8(nextComponentFunc++, coreMemory));
				lowered.add(nextCoreFunc++);
			}
			aliases.add(ComponentWriter.aliasCoreTable(userShimInstance, WasmShimModules.TABLE_EXPORT));
			final int userTable = nextCoreTable++;
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lowers));
			final List<String> names = new ArrayList<>();
			final List<Integer> sorts = new ArrayList<>();
			final List<Integer> indices = new ArrayList<>();
			names.add(WasmShimModules.TABLE_EXPORT);
			sorts.add(ComponentWriter.CORE_SORT_TABLE);
			indices.add(userTable);
			for (int k = 0; k < viaShim.size(); k++) {
				names.add(WasmShimModules.slotName(k));
				sorts.add(ComponentWriter.CORE_SORT_FUNC);
				indices.add(lowered.get(k));
			}
			final int fixupArgs = nextCoreInstance++;
			nextCoreInstance++; // the fixup instance itself
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
				.vec(List.of(ComponentWriter.coreInstanceFromExports(names, sorts, indices),
						ComponentWriter.coreInstanceInstantiate(userFixupModule, List.of(""), List.of(fixupArgs)))));
		}
		final List<byte[]> aliases = new ArrayList<>();
		final List<byte[]> types = new ArrayList<>();
		final List<byte[]> lifts = new ArrayList<>();
		final List<byte[]> instances = new ArrayList<>();
		final List<byte[]> exports = new ArrayList<>();
		// Exports naming a WIT interface (`export docs:adder/add;`) are bundled into one
		// exported component instance per interface; a flat export stays a top-level
		// function export. Insertion order is world order.
		final LinkedHashMap<String, List<Map.Entry<String, Integer>>> ifaceGroups = new LinkedHashMap<>();
		// The canonical string ABI aliases (memory, realloc, post-returns) come first in
		// this block's core function/memory index spaces; they exist only when a :string
		// boundary is present, so a scalar-only component keeps the Release 1 bytes (or
		// the plain print wiring above).
		final Map<String, Integer> postFuncs = new LinkedHashMap<>();
		if (decls.stream().anyMatch(WasmExportCompiler::usesMemory)) {
			if (coreMemory < 0) {
				aliases.add(ComponentWriter.aliasCoreMemory(coreInstance, "memory"));
				coreMemory = 0;
			}
			if (realloc < 0) {
				aliases.add(ComponentWriter.aliasCoreFunc(coreInstance, CABI_REALLOC));
				realloc = nextCoreFunc++;
			}
			for (WasmExportCompiler.Decl d : decls) {
				if (WasmExportCompiler.usesMemory(d)) {
					String kind = postReturnKind(d);
					if (!postFuncs.containsKey(kind)) {
						aliases.add(ComponentWriter.aliasCoreFunc(coreInstance, postReturnExportName(kind)));
						postFuncs.put(kind, nextCoreFunc++);
					}
				}
			}
		}
		// Per export i: alias the core wrapper, declare its function type, lift it and
		// export it. Types/lifts/exports line up 1:1 with the ordinal; only the core
		// function index is shifted by the wiring above. A printing program's exports
		// are lifted against ASYNC function types -- the bridge's blocking waitable-set
		// park is legal only inside an async-typed task -- with the same flat core
		// signature (the lift below is unchanged, and the post-return survives the async
		// type); a print-free program's exports stay sync lifts.
		final int typeBase = nextType;
		final int componentFuncBase = nextComponentFunc;
		for (int i = 0; i < decls.size(); i++) {
			WasmExportCompiler.Decl decl = decls.get(i);
			WasmComponentBuilder.FuncExport e = WasmExportCompiler.componentExport(decl);
			aliases.add(ComponentWriter.aliasCoreFunc(coreInstance, e.name()));
			int func = nextCoreFunc++;
			// p0, p1, ... unless the directive names the parameters (:param-names, or the
			// WIT world's own names under rontolisp:wit-export).
			types.add(printUsed
					? ComponentWriter.asyncFuncTypeScalars(e.paramNames(), e.paramValTypes(), e.resultValType())
					: ComponentWriter.funcTypeScalars(e.paramNames(), e.paramValTypes(), e.resultValType()));
			if (WasmExportCompiler.usesMemory(decl)) {
				// String-involving export: lift with the canonical string options.
				int postFunc = Objects.requireNonNull(postFuncs.get(postReturnKind(decl)));
				lifts.add(ComponentWriter.canonLiftMemoryReallocUtf8PostReturn(func, typeBase + i, coreMemory, realloc,
						postFunc));
			}
			else {
				// Sync lift with no canonical options: flat scalars need no
				// memory/realloc.
				lifts.add(ComponentWriter.canonLift(func, typeBase + i));
			}
			if (e.iface() == null) {
				exports.add(ComponentWriter.exportFunc(e.name(), componentFuncBase + i));
			}
			else {
				ifaceGroups.computeIfAbsent(e.iface(), k -> new ArrayList<>())
					.add(Map.entry(e.name(), componentFuncBase + i));
			}
		}
		// One synthesized instance per exported interface, exported under its id, after
		// the imported instances (the print block's and the user imports').
		int componentInstance = nextComponentInstance;
		for (Map.Entry<String, List<Map.Entry<String, Integer>>> group : ifaceGroups.entrySet()) {
			instances.add(ComponentWriter.componentInstanceFromFuncs(group.getValue()));
			exports.add(ComponentWriter.exportInstance(group.getKey(), componentInstance++));
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(types));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lifts));
		if (!instances.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(instances));
		}
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(exports));
		return c.toByteArray();
	}

	/**
	 * The imported instance type of one import module: one function type declaration per
	 * bound function, each exported under the import field. Function types are
	 * scalar/string component function types over the import's parameter names.
	 * @param decls the module's imports, in core import order
	 * @return the encoded instance type
	 */
	private static byte[] instanceType(List<WasmImportCompiler.Decl> decls) {
		final List<byte[]> items = new ArrayList<>();
		int localType = 0;
		for (WasmImportCompiler.Decl decl : decls) {
			List<Integer> params = new ArrayList<>();
			for (BoundaryType t : decl.paramTypes()) {
				params.add(WasmExportCompiler.componentValType(t));
			}
			items.add(ComponentWriter.instanceDeclType(ComponentWriter.funcTypeScalars(decl.paramNames(), params,
					WasmExportCompiler.componentValType(decl.returnType()))));
			items.add(ComponentWriter.instanceDeclExportFunc(decl.field(), localType++));
		}
		return ComponentWriter.instanceTypeOf(items);
	}

	/**
	 * Whether the import's lowered call touches linear memory -- a {@code :string}
	 * argument (the host reads it out of the core's memory) or result (the host writes it
	 * in through {@code cabi_realloc}) -- and so must be wired through the shim.
	 * @param decl the import
	 * @return whether the canonical lower needs the core's memory
	 */
	static boolean touchesMemory(WasmImportCompiler.Decl decl) {
		return decl.paramTypes().contains(BoundaryType.STRING) || returnsString(decl);
	}

	/**
	 * Whether the import answers a {@code :string}, which the canonical ABI returns
	 * through a trailing return pointer into host-reallocated memory.
	 * @param decl the import
	 * @return whether the result is a string
	 */
	static boolean returnsString(WasmImportCompiler.Decl decl) {
		return decl.returnType() == BoundaryType.STRING;
	}

	/**
	 * Whether the core module must export {@code cabi_realloc}: a {@code :string} export
	 * (the host lowers its arguments through it) or a {@code :string}-returning import
	 * (the host lowers the result through it). The compiler appends the helper on exactly
	 * this condition, and the wrap aliases it on the same one.
	 * @param exportDecls the export directives
	 * @param imports the reached imports
	 * @return whether the core carries {@code cabi_realloc}
	 */
	static boolean needsRealloc(List<WasmExportCompiler.Decl> exportDecls, List<WasmImportCompiler.Decl> imports) {
		return exportDecls.stream().anyMatch(WasmExportCompiler::usesMemory)
				|| imports.stream().anyMatch(NoGcWasmComponentBuilder::returnsString);
	}

	/**
	 * The core parameter types of an import's lowered function in component mode: the
	 * flat host parameters, plus the trailing {@code i32} return pointer of a
	 * {@code :string} result ({@code MAX_FLAT_RESULTS} = 1, and a string flattens to
	 * two).
	 * @param decl the import
	 * @return the core parameter types
	 */
	static Type[] coreParamTypes(WasmImportCompiler.Decl decl) {
		Type[] flat = WasmImportCompiler.hostParamTypes(decl);
		if (!returnsString(decl)) {
			return flat;
		}
		Type[] withRetptr = new Type[flat.length + 1];
		System.arraycopy(flat, 0, withRetptr, 0, flat.length);
		withRetptr[flat.length] = Type.I32;
		return withRetptr;
	}

	/**
	 * The core result types of an import's lowered function in component mode: none for a
	 * {@code :string} result (it arrives through the return pointer), the flat host
	 * result otherwise.
	 * @param decl the import
	 * @return the core result types
	 */
	static Type[] coreResultTypes(WasmImportCompiler.Decl decl) {
		return returnsString(decl) ? new Type[0] : WasmImportCompiler.hostResultTypes(decl);
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
			case STRING, S8, S16, S32, U8, U16, U32, BOOL -> "i32";
			case S64, U64 -> "i64";
			case FLOAT -> "f64";
			case VOID -> "void";
			// --no-gc has no cons/reader/printer runtime (no :s-expr) and no arrays (no
			// :bytes), so it rejects both long before a component lift is planned
			// (NoGcWasmCompiler.requireSupported).
			case S_EXPR, BYTES -> throw new UnsupportedOperationException("rontolisp:wasm-export type "
					+ decl.returnType().designator() + " has no component post-return signature");
			// Unreachable: typeDesignator refuses the JVM-only handle types by name.
			case FLOAT_VECTOR, FLOAT_MATRIX -> throw new IllegalStateException(
					"a JVM-only boundary type reached the WASM component lift: " + decl.returnType().designator());
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
