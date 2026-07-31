package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import am.ik.wasm.ComponentWriter;
import am.ik.wit.WitItem;
import org.jspecify.annotations.Nullable;

/**
 * Wraps a rontolisp core module (compiled in component mode) into a WASI 0.3 (Preview 3)
 * <strong>component</strong> that prints through {@code wasi:cli/stdout@0.3.0} and is
 * runnable with {@code wasmtime run -W gc=y} (wasmtime 46+; the async canonical ABI and
 * stackful lifts are on by default there, only the synchronous stream/future built-ins
 * the adapter uses are still gated).
 *
 * <p>
 * In WASI 0.3 the {@code wasi:io} package is gone: all byte I/O flows through the
 * built-in component-model {@code stream<u8>} / {@code future<T>} types and the async
 * canonical ABI. The wrapped rontolisp core module is unchanged from Preview 1 &mdash; it
 * still imports the eight {@code wasi_snapshot_preview1} functions and prints / reads
 * through them; those imports are satisfied by an <em>adapter</em> core module that
 * implements them over WASI 0.3 ({@code wasi:cli}, {@code wasi:filesystem},
 * {@code wasi:clocks}, {@code wasi:random}) using
 * {@code stream.new}/{@code stream.read}/{@code stream.write} and {@code future.read}
 * (see {@code src/wasm-component/adapter.wat}). The component's
 * {@code wasi:cli/run@0.3.0} export &mdash; an {@code async func} &mdash; is lifted as a
 * <strong>stackful</strong> async export (an ordinary {@link ComponentWriter#canonLift}
 * against an {@link ComponentWriter#asyncFuncTypeResultType async} function type), so the
 * synchronous {@code stream.write}/{@code stream.read}/{@code future.read} built-ins the
 * adapter calls block cooperatively without any callback state machine.
 *
 * <p>
 * A lowered WASI import with a {@code list<u8>}/{@code string} parameter needs the
 * canonical memory, and that memory must exist before the importing module is
 * instantiated. To avoid the instantiate-before-memory cycle, a tiny shared "memory" core
 * module is instantiated first; it exports the memory (and a {@code cabi_realloc}) used
 * by the canonical lowering, the canonical built-ins and the adapter / rontolisp modules.
 *
 * <p>
 * Three byte blobs are loaded from classpath resources next to this class (under
 * {@code component/}) because they are fixed and independent of the compiled program:
 * {@code import-block.bin} (the unified WASI 0.3 import declarations) and the two helper
 * core modules {@code mem.wasm} / {@code adapter.wasm}. The remaining wiring is emitted
 * programmatically below; the indices and per-function canonical options were derived
 * from {@code wasm-tools dump} of a generated reference (see
 * {@code src/wasm-component/README.md}). Each artifact was validated with
 * {@code wasm-tools validate -f component-model -f cm-async} and executed with
 * {@code wasmtime run}.
 */
public final class WasmComponentBuilder {

	/** Classpath location of the embedded component blobs, relative to this class. */
	private static final String RES = "component/";

	/**
	 * Pre-built component type/import sections declaring every imported WASI 0.3
	 * interface, captured from a {@code wasm-tools}-generated reference for the
	 * {@code uni} WIT world (see {@code src/wasm-component/uni.wit}). In order, component
	 * import instances 0-10 are: {@code wasi:cli/types} (the {@code error-code} enum),
	 * {@code wasi:cli/stdout}, {@code wasi:cli/stdin}, {@code wasi:cli/environment},
	 * {@code wasi:clocks/types} (the {@code duration} alias {@code wait-for} pulls in),
	 * {@code wasi:clocks/system-clock}, {@code wasi:clocks/monotonic-clock} (declaring
	 * {@code now} and the async {@code wait-for}), {@code wasi:filesystem/types},
	 * {@code wasi:filesystem/preopens}, {@code wasi:random/random} and
	 * {@code wasi:cli/stderr} (appended last, for {@code warn} on fd&nbsp;2). The block
	 * defines component types 0-15, so the next free component type index is 16.
	 */
	private static final byte[] IMPORT_BLOCK = resource("import-block.bin");

	/**
	 * A core module exporting a linear {@code memory} (6 pages) and a bump-allocator
	 * {@code cabi_realloc}, shared by the canonical lowering, the canonical built-ins and
	 * the main modules. Source: {@code src/wasm-component/mem.wat}.
	 */
	private static final byte[] MEM_MODULE = resource("mem.wasm");

	/**
	 * The preview1-to-WASI-0.3 adapter core module: it imports the shared memory, the
	 * lowered WASI 0.3 functions and the async canonical built-ins (under {@code "w"})
	 * and exports the eight preview1-style functions rontolisp imports. Source:
	 * {@code src/wasm-component/adapter.wat}.
	 */
	private static final byte[] ADAPTER_MODULE = resource("adapter.wasm");

	// Component import-instance indices (from import-block.bin; see IMPORT_BLOCK).
	// wasi:clocks/types (instance 4) is dependency-hoisted by wait-for's `use
	// types.{duration}`, shifting everything after wasi:cli/environment by one.
	private static final int INST_CLI_TYPES = 0;

	private static final int INST_STDOUT = 1;

	private static final int INST_STDIN = 2;

	private static final int INST_ENVIRON = 3;

	private static final int INST_SYS_CLOCK = 5;

	private static final int INST_MONO_CLOCK = 6;

	private static final int INST_FS_TYPES = 7;

	private static final int INST_FS_PREOPENS = 8;

	private static final int INST_RANDOM = 9;

	private static final int INST_STDERR = 10;

	// First free component type index after the import block: the block defines types
	// 0-15 (the wasi:clocks/types instance type and the aliased `duration` joined with
	// the wait-for declaration), so the aliased resource/enum types start at 16.
	private static final int T_CLI_ERRCODE = 16;

	private static final int T_FS_ERRCODE = 17;

	private static final int T_DESCRIPTOR = 18;

	// wasi:filesystem's directory-entry record ({descriptor-type type, string name}),
	// the element of the read-directory stream behind %list-directory.
	private static final int T_DIRENT = 19;

	private static final int T_STREAM = 20;

	// stream<directory-entry>: structurally distinct from the u8 byte stream, so it
	// needs its own read / drop built-ins -- and its elements own a string, so the read
	// carries the realloc option the byte-stream read does not.
	private static final int T_DE_STREAM = 21;

	private static final int T_CLI_RESULT = 22;

	private static final int T_CLI_FUTURE = 23;

	private static final int T_FS_RESULT = 24;

	private static final int T_FS_FUTURE = 25;

	private static final int T_RUN_RESULT = 26;

	private static final int T_RUN_FUNC = 27;

	/**
	 * The interfaces of the base/sockets blocks a {@code %component-import} may bind FROM
	 * THE BLOCK (the wait.lisp shim's wasi:clocks/monotonic-clock, environment.lisp's
	 * wasi:cli/environment): they are part of the fixed WASI surface, so importing them
	 * again as user instances would be invalid. Maps each interface to the member fields
	 * the block actually declares -- binding anything else would only fail component
	 * validation with a byte offset.
	 */
	private static final java.util.Map<String, java.util.Set<String>> FIXED_BLOCK_IFACES = java.util.Map.of(
			"wasi:clocks/monotonic-clock@0.3.0", java.util.Set.of("now", "wait-for"), "wasi:cli/stdin@0.3.0",
			java.util.Set.of("read-via-stream"), "wasi:cli/environment@0.3.0", java.util.Set.of("get-environment"));

	private WasmComponentBuilder() {
	}

	/**
	 * A {@code rontolisp:wasm-export} function to expose as a component-model export. The
	 * core module core-exports a wrapper under {@code name}; the component aliases it and
	 * lifts it <strong>synchronously</strong> by default (a pure-compute export needs no
	 * async, unlike the stackful-async {@code run} lift). A scalar export lifts with no
	 * canonical options; a {@code :string}/{@code :s-expr}-involving one with the
	 * canonical string options. An {@code :async t} export instead lifts against an
	 * <strong>async</strong> function type &mdash; the same stackful-async shape as
	 * {@code run}, with an identical flat core signature &mdash; so I/O inside it blocks
	 * cooperatively instead of trapping.
	 *
	 * @param name the export name (a valid component-model label; honors {@code :as})
	 * @param paramNames the parameter labels the function type carries ({@code p0},
	 * {@code p1}, ... by default; the WIT world's own names under
	 * {@code rontolisp:wit-export}, or an explicit {@code :param-names})
	 * @param paramValTypes the {@code ComponentWriter.VT_*} code of each parameter
	 * @param resultValType the {@code ComponentWriter.VT_*} result code, or {@code null}
	 * for no result
	 * @param async whether to lift against an async function type ({@code :async t})
	 * @param iface the fully-qualified id of the WIT interface this export belongs to
	 * ({@code "docs:adder/add@0.1.0"}), or {@code null} for a flat top-level function
	 * export. Exports sharing an {@code iface} are bundled into one exported component
	 * <em>instance</em> under that id, instead of being exported as flat functions
	 */
	public record FuncExport(String name, List<String> paramNames, List<Integer> paramValTypes,
			@Nullable Integer resultValType, boolean async, @Nullable String iface) {
	}

	/**
	 * Append the per-export alias / type / lift / export wiring for the
	 * {@code wasm-export} functions. Emits nothing when {@code decls} is empty, so an
	 * export-free program's component stays byte-identical. The rontolisp core is always
	 * core instance 3 (mem = 0, adapter = 2); each new index space entry is appended
	 * after the {@code run} wiring, whose next free indices the caller passes in.
	 *
	 * <p>
	 * A {@code :string}/{@code :s-expr} boundary type lifts through the canonical string
	 * ABI: the alias section additionally projects the core's own {@code cabi_realloc}
	 * (the host lowers string arguments into linear memory through it) and one
	 * {@code cabi_post_*} post-return per flat-result signature (it pops the core's bump
	 * heap back to the per-call snapshot once the host has copied the results out,
	 * intern-count-guarded), and the string-involving exports are lifted with the
	 * {@code (memory 0) (realloc ...) string-encoding=utf8 (post-return ...)} options.
	 * Memory 0 is the shared {@code mem.wasm} memory aliased at build start -- the same
	 * memory instance the core imports, so the wrapper's {@code (ptr,len)} values and the
	 * host's lowered argument bytes live in one address space. A program with no
	 * {@code :string}/{@code :s-expr} export gets none of this: its component is
	 * byte-identical to the Tier 1 scalar-only shape.
	 * @param c the component writer
	 * @param decls the parsed export directives
	 * @param nextCoreFunc the first free core function index (after the {@code run}
	 * alias)
	 * @param nextType the first free component type index
	 * @param nextComponentFunc the first free component function index (after the
	 * {@code run} lift)
	 * @param nextComponentInstance the first free component instance index (after the
	 * {@code wasi:cli/run} instance and the user-import instances); each exported WIT
	 * interface consumes one
	 */
	private static void appendFuncExports(ComponentWriter c, List<WasmExportCompiler.Decl> decls, int nextCoreFunc,
			int nextType, int nextComponentFunc, int rontolispInstance, int nextComponentInstance) {
		if (decls.isEmpty()) {
			return;
		}
		final List<byte[]> aliases = new java.util.ArrayList<>();
		final List<byte[]> types = new java.util.ArrayList<>();
		final List<byte[]> lifts = new java.util.ArrayList<>();
		final List<byte[]> instances = new java.util.ArrayList<>();
		final List<byte[]> exports = new java.util.ArrayList<>();
		// Exports that name a WIT interface (`export docs:adder/add;`) are bundled into
		// one
		// component instance per interface, exported under the interface's id; a flat
		// (interface-less) export is exported as a top-level function, as before.
		// Insertion
		// order is world order, so the emitted exports keep it.
		final java.util.LinkedHashMap<String, List<java.util.Map.Entry<String, Integer>>> ifaceGroups = new java.util.LinkedHashMap<>();
		// The canonical string ABI aliases (cabi_realloc, the cabi_post_* post-returns)
		// come first in the core function index space; they exist only when a
		// :string/:s-expr boundary is present, so a scalar-only component keeps the
		// Tier 1 bytes.
		int coreFunc = nextCoreFunc;
		int realloc = -1;
		final java.util.Map<String, Integer> postFuncs = new java.util.LinkedHashMap<>();
		if (decls.stream().anyMatch(WasmExportCompiler::usesMemory)) {
			aliases.add(ComponentWriter.aliasCoreFunc(rontolispInstance, WasmExportCompiler.CABI_REALLOC));
			realloc = coreFunc++;
			for (WasmExportCompiler.Decl d : decls) {
				if (WasmExportCompiler.usesMemory(d)) {
					String kind = WasmExportCompiler.componentPostReturnKind(d);
					if (!postFuncs.containsKey(kind)) {
						aliases.add(ComponentWriter.aliasCoreFunc(rontolispInstance,
								WasmExportCompiler.cabiPostExportName(kind)));
						postFuncs.put(kind, coreFunc++);
					}
				}
			}
		}
		for (int i = 0; i < decls.size(); i++) {
			WasmExportCompiler.Decl decl = decls.get(i);
			FuncExport e = WasmExportCompiler.componentExport(decl);
			// Alias the core wrapper export out of the rontolisp instance; a
			// :string/:s-expr-returning export's core export is its retptr shim.
			aliases.add(ComponentWriter.aliasCoreFunc(rontolispInstance, e.name()));
			int func = coreFunc++;
			// One function type per export (params p0, p1, ... unless the directive names
			// them): synchronous by default; an :async t export gets the async
			// counterpart (tag 0x43), which turns the lift below into a stackful
			// async export (the run shape) with the same flat core signature -- the ONLY
			// byte difference an :async export introduces.
			final List<String> paramNames = e.paramNames();
			types.add(e.async() ? ComponentWriter.asyncFuncTypeScalars(paramNames, e.paramValTypes(), e.resultValType())
					: ComponentWriter.funcTypeScalars(paramNames, e.paramValTypes(), e.resultValType()));
			if (WasmExportCompiler.usesMemory(decl)) {
				// String-involving export: lift with the canonical string options over
				// the shared memory (core memory 0).
				int postFunc = java.util.Objects
					.requireNonNull(postFuncs.get(WasmExportCompiler.componentPostReturnKind(decl)));
				lifts.add(
						ComponentWriter.canonLiftMemoryReallocUtf8PostReturn(func, nextType + i, 0, realloc, postFunc));
			}
			else {
				// Sync lift with no canonical options: flat scalars need no
				// memory/realloc.
				lifts.add(ComponentWriter.canonLift(func, nextType + i));
			}
			if (e.iface() == null) {
				// Flat export: export the lifted component func directly under its name.
				exports.add(ComponentWriter.exportFunc(e.name(), nextComponentFunc + i));
			}
			else {
				// Interface member: defer to a per-interface instance built below.
				ifaceGroups.computeIfAbsent(e.iface(), k -> new java.util.ArrayList<>())
					.add(java.util.Map.entry(e.name(), nextComponentFunc + i));
			}
		}
		// One synthesized instance per exported interface, exported under the interface's
		// fully-qualified id (`export docs:adder/add@0.1.0`). The instances come after
		// the
		// run instance and the user-import instances in the component instance index
		// space.
		int componentInstance = nextComponentInstance;
		for (java.util.Map.Entry<String, List<java.util.Map.Entry<String, Integer>>> group : ifaceGroups.entrySet()) {
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
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode (imports its
	 * memory, imports {@code wasi_snapshot_preview1}, and exports a {@code run} function
	 * returning {@code i32})
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule) {
		return build(coreModule, List.of());
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module,
	 * additionally exposing the given {@code rontolisp:wasm-export} functions as
	 * host-callable component-model exports (synchronous canonical lifts alongside the
	 * stackful-async {@code wasi:cli/run} export; {@code :string}/{@code :s-expr}
	 * boundaries lift through the canonical string ABI).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the parsed function export directives (empty for none; an empty
	 * list yields output byte-identical to {@link #build(byte[])})
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports) {
		return build(coreModule, funcExports, List.of());
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module,
	 * additionally importing the given user WIT interfaces ({@code rontolisp:wit-import}
	 * under {@code --component}): each interface becomes a component-level instance
	 * import whose functions are {@code canon lower}ed into a synthesized core instance
	 * that satisfies the core module's matching imports at instantiation. An empty import
	 * list emits nothing and shifts nothing, so an import-free component stays
	 * byte-identical. A {@code rontolisp:tcp-*} program is just this path with
	 * sockets.lisp's own {@code wasi:sockets/types@0.3.0} import in the list (the
	 * dedicated sockets blob variant and its hand-written adapter are gone).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the parsed function export directives (empty for none)
	 * @param imports the user WIT interface imports (empty for none)
	 * @return the WASI 0.3 component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports,
			List<WasmComponentImportCompiler.Import> imports) {
		// rontolisp:fetch off serve is the http.lisp library over canon-lowered
		// wasi:http@0.3 user imports (the base variant); a serving program goes through
		// buildServe instead.
		// An import of an interface the block itself declares (wait.lisp's
		// wasi:clocks/monotonic-clock) is bound FROM the block, never re-imported.
		final List<WasmComponentImportCompiler.Import> fixed = new java.util.ArrayList<>();
		final List<WasmComponentImportCompiler.Import> user = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			(FIXED_BLOCK_IFACES.containsKey(imported.ifaceId()) ? fixed : user).add(imported);
		}
		validateFixedMembers(fixed);
		rejectAdapterImportCollisions(user, WitEmitter.VARIANT_BASE);
		rejectDuplicateUserImports(user);
		return buildBase(coreModule, funcExports, fixed, user);
	}

	// Two user imports of ONE interface id (a program's own wit-import of
	// wasi:sockets/types beside the tcp built-ins' sockets.lisp import) would emit the
	// same instance import name twice -- invalid, and nothing downstream says so in
	// words.
	private static void rejectDuplicateUserImports(List<WasmComponentImportCompiler.Import> imports) {
		final java.util.Set<String> seen = new java.util.HashSet<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (!seen.add(imported.ifaceId())) {
				throw new UnsupportedOperationException("rontolisp:wit-import binds '" + imported.ifaceId()
						+ "' twice: a component cannot import the same interface twice. The rontolisp:tcp-* built-ins "
						+ "bind wasi:sockets/types@0.3.0 themselves, so either drop the built-ins and drive the "
						+ "interface through your own binding, or drop the duplicate directive");
			}
		}
	}

	/**
	 * The imports that are NOT bound from the base/sockets blocks' own fixed WASI surface
	 * -- the genuine user instance imports, which are the only ones the emitted WIT
	 * describes on top of the fixed world (the counterpart of
	 * {@link WasmServeComponentBuilder#additionalImports}).
	 * @param imports the full import list
	 * @return the additional user imports
	 */
	static List<WasmComponentImportCompiler.Import> additionalImports(
			List<WasmComponentImportCompiler.Import> imports) {
		final List<WasmComponentImportCompiler.Import> out = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (!FIXED_BLOCK_IFACES.containsKey(imported.ifaceId())) {
				out.add(imported);
			}
		}
		return out;
	}

	// A block-bound interface can only alias the member fields its instance type
	// actually declares; anything else would only fail component validation downstream
	// with a byte offset, so say it in words here.
	static void validateFixedMembers(List<WasmComponentImportCompiler.Import> fixed) {
		for (WasmComponentImportCompiler.Import imported : fixed) {
			java.util.Set<String> allowed = FIXED_BLOCK_IFACES.get(imported.ifaceId());
			if (allowed == null) {
				continue;
			}
			final List<String> bound = new java.util.ArrayList<>();
			imported.decls().forEach(d -> bound.add(d.field()));
			imported.calls().forEach(call -> bound.add(call.field()));
			// Async ALIAS built-ins (stdin.lisp's stdin-stream-read &c) are fine on a
			// block-bound interface: each is typed by a component-level stream/future
			// type derived from the WIT and aliases nothing out of the block instance.
			// Drops and task-returns would project the block instance's own types, so
			// they stay rejected.
			if (!imported.drops().isEmpty() || !imported.taskReturns().isEmpty()) {
				throw new UnsupportedOperationException("rontolisp:wit-import of '" + imported.ifaceId()
						+ "': this interface is part of the component's fixed WASI surface and only its functions "
						+ allowed + " (and async type-alias built-ins) can be bound from it");
			}
			for (String field : bound) {
				if (!allowed.contains(field)) {
					throw new UnsupportedOperationException("rontolisp:wit-import of '" + imported.ifaceId()
							+ "' cannot bind '" + field + "': this interface is part of the component's fixed WASI "
							+ "surface, which declares only " + allowed);
				}
			}
		}
	}

	// A user import must not name an interface the component's own WASI surface already
	// imports: the component would carry that instance import name TWICE, which is
	// invalid
	// -- and nothing downstream says so in words (wasmtime and wasm-tools reject the
	// module
	// at load time with a byte offset). Which interfaces those are depends on what the
	// program uses, so the check is here, where the blob variant is finally known.
	private static void rejectAdapterImportCollisions(List<WasmComponentImportCompiler.Import> imports,
			String variant) {
		if (imports.isEmpty()) {
			return;
		}
		final java.util.Set<String> wasiSurface = new java.util.LinkedHashSet<>();
		for (WitItem item : WasiWitDefinitions.document(variant).world().items()) {
			if (item instanceof WitItem.ImportRef importRef) {
				wasiSurface.add(importRef.target().toString());
			}
		}
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (wasiSurface.contains(imported.ifaceId())) {
				throw new UnsupportedOperationException("rontolisp:wit-import cannot bind '" + imported.ifaceId()
						+ "': this component already imports that interface as part of its own WASI surface, and a "
						+ "component cannot import the same interface twice. Either drop the built-in that carries it "
						+ "and drive the interface through the WIT binding, or bind a different interface");
			}
		}
	}

	/**
	 * Emits the user WIT-interface import wiring: one component <strong>type</strong>
	 * (the imported instance's type) + <strong>import</strong> per interface, an
	 * <strong>alias</strong> per bound function, a {@code canon lower} per function
	 * (memory 0 / realloc = the shared memory module's {@code cabi_realloc} = core func 0
	 * / UTF-8, exactly when the call touches linear memory), and one synthesized core
	 * instance per interface whose export names match the core module's import fields.
	 * Emits nothing when {@code imports} is empty.
	 * @param c the component writer
	 * @param imports the user interface imports
	 * @param nextType the first free component type index
	 * @param firstImportInstance the first free component instance index (right after the
	 * variant's fixed import instances)
	 * @param nextComponentFunc the first free component function index
	 * @param nextCoreFunc the first free core function index
	 */
	/**
	 * What {@link #appendUserImports} actually consumed of each index space -- the SINGLE
	 * source of truth every downstream fixed index shifts by. Asyncs make the type count
	 * derivation-dependent (a future's payload chain declares a data-dependent number of
	 * component types), so a static pre-count would be the "validates while lifting the
	 * wrong function" hazard incarnate; the emission reports what it did instead.
	 *
	 * @param types component type indices consumed (instance types + projections +
	 * async-type declaration chains)
	 * @param componentFuncs component function indices consumed (one alias per bound
	 * function; drops and asyncs consume none)
	 * @param coreFuncs core function indices consumed (one {@code canon lower} per bound
	 * function + one {@code canon resource.drop} per drop + one async built-in per bound
	 * async op)
	 */
	record Appended(int types, int componentFuncs, int coreFuncs) {

		static final Appended NONE = new Appended(0, 0, 0);

	}

	static Appended appendUserImports(ComponentWriter c, List<WasmComponentImportCompiler.Import> imports, int nextType,
			int firstImportInstance, int nextComponentFunc, int nextCoreFunc) {
		if (imports.isEmpty()) {
			return Appended.NONE;
		}
		// The imports are already in dependency order (WasmComponentImportCompiler
		// .inDependencyOrder, applied once where they are collected), so an interface is
		// imported before any interface that uses its resources -- which is what lets the
		// projection below name an instance that already exists.
		final java.util.Map<String, Integer> instanceOf = new java.util.LinkedHashMap<>();
		int instIdx = firstImportInstance;
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (instanceOf.put(imported.ifaceId(), instIdx++) != null) {
				throw new UnsupportedOperationException(
						"the component already imports the WIT interface '" + imported.ifaceId() + "'");
			}
		}
		// Which resources each interface must EXPORT because another one uses them: a
		// type
		// can only be projected out of an instance that exports it. This is why an
		// interface may be imported with no bound functions at all (wasi:io/error exists
		// in a fetch component purely to own the `error` resource wasi:io/streams' own
		// `stream-error` carries), and it is knowable only here, with every import in
		// hand.
		final java.util.Map<String, java.util.Set<String>> provides = new java.util.LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			for (WitComponentTypeEncoder.ForeignResource foreign : WitComponentTypeEncoder
				.foreignResourcesOf(imported)) {
				provides.computeIfAbsent(foreign.ownerIfaceId(), id -> new java.util.LinkedHashSet<>())
					.add(foreign.resource());
			}
			// A DROPPED resource must be declared too: `canon resource.drop` projects it
			// out
			// of this instance, and the encoder would otherwise never declare it -- it
			// declares a resource only when a bound function's signature reaches one, and
			// a
			// program may bind nothing but the drop.
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				provides.computeIfAbsent(imported.ifaceId(), id -> new java.util.LinkedHashSet<>())
					.add(drop.resource());
			}
			// A resource an ASYNC type's payload chain reaches must be declared too: the
			// component-level future/stream type points at the projected resource, and
			// the projection can only link against a name the instance type exports.
			for (WasmComponentImportCompiler.Async async : distinctAsyncTypes(imported)) {
				final List<WitComponentTypeEncoder.ForeignResource> reached = new java.util.ArrayList<>();
				WitComponentLevelTypes.collectResources(imported.resolver(), async.abi(), async.type(), reached);
				for (WitComponentTypeEncoder.ForeignResource resource : reached) {
					provides.computeIfAbsent(resource.ownerIfaceId(), id -> new java.util.LinkedHashSet<>())
						.add(resource.resource());
				}
			}
			// A task-return's declared result type reaches resources the same way (its
			// component-level type is derived by the same machinery).
			for (WasmComponentImportCompiler.TaskReturn tr : imported.taskReturns()) {
				final List<WitComponentTypeEncoder.ForeignResource> reached = new java.util.ArrayList<>();
				WitComponentLevelTypes.collectResources(imported.resolver(), tr.abi(), tr.type(), reached);
				for (WitComponentTypeEncoder.ForeignResource resource : reached) {
					provides.computeIfAbsent(resource.ownerIfaceId(), id -> new java.util.LinkedHashSet<>())
						.add(resource.resource());
				}
			}
		}
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (imported.decls().isEmpty() && imported.calls().isEmpty() && !provides.containsKey(imported.ifaceId())) {
				throw new UnsupportedOperationException("rontolisp:wit-import of '" + imported.ifaceId()
						+ "': the program calls none of its functions, and no other imported interface uses its types");
			}
		}
		// "<owner iface id>#<resource>" -> the component type index it was projected to.
		final java.util.Map<String, Integer> outerOf = new java.util.LinkedHashMap<>();
		final List<byte[]> funcAliases = new java.util.ArrayList<>();
		final List<byte[]> lowers = new java.util.ArrayList<>();
		final List<byte[]> coreInstances = new java.util.ArrayList<>();
		final java.util.Map<String, CoreExports> exports = new java.util.LinkedHashMap<>();
		int typeIdx = nextType;
		int compFunc = nextComponentFunc;
		int coreFunc = nextCoreFunc;
		for (WasmComponentImportCompiler.Import imported : imports) {
			// Project every resource this interface USES from another one out of that
			// interface's instance, into this component's type index space. The instance
			// type below points at these with an `alias outer` instead of re-declaring
			// the resource -- a resource is nominal (WitComponentTypeEncoder's comment).
			final List<byte[]> typeAliases = new java.util.ArrayList<>();
			for (WitComponentTypeEncoder.ForeignResource foreign : WitComponentTypeEncoder
				.foreignResourcesOf(imported)) {
				String key = foreign.ownerIfaceId() + "#" + foreign.resource();
				if (outerOf.containsKey(key)) {
					continue;
				}
				Integer ownerInstance = instanceOf.get(foreign.ownerIfaceId());
				if (ownerInstance == null) {
					throw new UnsupportedOperationException("rontolisp:wit-import of '" + imported.ifaceId()
							+ "' cannot bind the resource '" + foreign.resource() + "', which it uses from '"
							+ foreign.ownerIfaceId()
							+ "': a component-model resource is its defining interface's type, so that interface must "
							+ "be imported too. Add (rontolisp:wit-import ... :interface \"" + foreign.ownerIfaceId()
							+ "\")");
				}
				typeAliases.add(ComponentWriter.aliasInstanceType(ownerInstance, foreign.resource()));
				outerOf.put(key, typeIdx++);
			}
			if (!typeAliases.isEmpty()) {
				c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(typeAliases));
			}
			byte[] instanceType = WitComponentTypeEncoder.encode(imported,
					(ownerId, resource) -> Objects.requireNonNull(outerOf.get(ownerId + "#" + resource),
							() -> ownerId + "#" + resource),
					provides.getOrDefault(imported.ifaceId(), java.util.Set.of()));
			c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(instanceType)));
			c.rawSection(ComponentWriter.SEC_IMPORT,
					ComponentWriter.vec(List.of(ComponentWriter.importInstance(imported.ifaceId(), typeIdx++))));
			final int instance = Objects.requireNonNull(instanceOf.get(imported.ifaceId()));
			final List<String> fields = new java.util.ArrayList<>();
			final List<Integer> coreIndices = new java.util.ArrayList<>();
			for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
				funcAliases.add(ComponentWriter.aliasInstanceFunc(instance, decl.field()));
				lowers.add(WasmComponentImportCompiler.needsMemory(decl)
						? ComponentWriter.canonLowerMemoryReallocUtf8(compFunc, 0, 0)
						: ComponentWriter.canonLower(compFunc));
				fields.add(decl.field());
				coreIndices.add(coreFunc);
				compFunc++;
				coreFunc++;
			}
			// Async func members follow the sync ones: aliased like any bound function,
			// but canon-lowered with the `async` option -- the core call returns the
			// packed (subtask << 4) | status immediately, and the memory options cover
			// the always-indirect result.
			for (WasmComponentImportCompiler.AsyncCall call : imported.calls()) {
				funcAliases.add(ComponentWriter.aliasInstanceFunc(instance, call.field()));
				lowers.add(ComponentWriter.canonLowerAsyncMemoryReallocUtf8(compFunc, 0, 0));
				fields.add(call.field());
				coreIndices.add(coreFunc);
				compFunc++;
				coreFunc++;
			}
			exports.put(imported.ifaceId(), new CoreExports(fields, coreIndices));
		}
		// Resource drops. `canon resource.drop` takes the resource's type in THIS
		// component's index space and produces a CORE function directly -- no instance
		// function to alias, no `canon lower`. So it is a second emission kind, and the
		// two
		// counts the user imports cost (component functions, core functions) stop being
		// the
		// same number. It needs the resource projected out of the instance that owns it,
		// which the loop above may already have done for a `use`d resource: reuse that
		// projection rather than making a second, identical one.
		final List<byte[]> dropAliases = new java.util.ArrayList<>();
		final List<byte[]> dropCanons = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			CoreExports core = Objects.requireNonNull(exports.get(imported.ifaceId()));
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				String key = imported.ifaceId() + "#" + drop.resource();
				Integer outer = outerOf.get(key);
				if (outer == null) {
					dropAliases.add(ComponentWriter.aliasInstanceType(
							Objects.requireNonNull(instanceOf.get(imported.ifaceId())), drop.resource()));
					outer = typeIdx++;
					outerOf.put(key, outer);
				}
				dropCanons.add(ComponentWriter.canonResourceDrop(outer));
				core.fields().add(drop.field());
				core.coreIndices().add(coreFunc++);
			}
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(funcAliases));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lowers));
		if (!dropAliases.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(dropAliases));
		}
		if (!dropCanons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(dropCanons));
		}
		// Async built-ins. A `canon stream.*`/`future.*` is the THIRD emission kind: a
		// CORE function (no component-func alias, like a drop), but typed by a
		// COMPONENT-LEVEL stream/future type derived from the WIT here -- the
		// data-driven counterpart of the hand-assembled base-adapter async block. The
		// derivation declares the type's whole payload chain (structural types fresh,
		// resources projected through the shared outerOf map), so the type count
		// becomes data-dependent -- which is why this method reports its counts instead
		// of trusting a static pre-count.
		final WitComponentLevelTypes componentTypes = new WitComponentLevelTypes(typeIdx, outerOf,
				ifaceId -> Objects.requireNonNull(instanceOf.get(ifaceId),
						() -> "the WIT interface '" + ifaceId + "' owning an async type's resource is not imported"));
		final List<byte[]> asyncCanons = new java.util.ArrayList<>();
		final java.util.Map<String, java.util.List<Integer>> asyncCoreOf = new java.util.LinkedHashMap<>();
		final java.util.Map<String, java.util.List<String>> asyncFieldsOf = new java.util.LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (imported.asyncs().isEmpty()) {
				continue;
			}
			final java.util.Map<String, Integer> typeOfAlias = new java.util.LinkedHashMap<>();
			for (WasmComponentImportCompiler.Async async : distinctAsyncTypes(imported)) {
				typeOfAlias.put(async.alias(), componentTypes.indexOf(imported.resolver(), async.abi(), async.type()));
			}
			// Two splices of one interface (mergeByIface) repeat the same ops: one canon
			// per FIELD, exactly like the deduplicated (module, field) core imports both
			// wrappers call through.
			final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
			final List<Integer> coreIndices = asyncCoreOf.computeIfAbsent(imported.ifaceId(),
					id -> new java.util.ArrayList<>());
			final List<String> fields = asyncFieldsOf.computeIfAbsent(imported.ifaceId(),
					id -> new java.util.ArrayList<>());
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				if (!seen.add(async.field())) {
					continue;
				}
				int type = Objects.requireNonNull(typeOfAlias.get(async.alias()));
				asyncCanons.add(asyncCanon(async, type));
				fields.add(async.field());
				coreIndices.add(coreFunc++);
			}
		}
		// Task-return built-ins (typed by their alias's derived component-level type)
		// and the waitable-set builtins each async-calling interface shares. Emitted
		// into the same canon run as the async built-ins; the type derivation must
		// precede the flush below.
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (imported.taskReturns().isEmpty() && imported.calls().isEmpty() && imported.asyncs().isEmpty()) {
				continue;
			}
			final List<Integer> coreIndices = asyncCoreOf.computeIfAbsent(imported.ifaceId(),
					id -> new java.util.ArrayList<>());
			final List<String> fields = asyncFieldsOf.computeIfAbsent(imported.ifaceId(),
					id -> new java.util.ArrayList<>());
			final java.util.Set<String> seen = new java.util.LinkedHashSet<>(fields);
			for (WasmComponentImportCompiler.TaskReturn tr : imported.taskReturns()) {
				if (!seen.add(tr.field())) {
					continue;
				}
				int type = componentTypes.indexOf(imported.resolver(), tr.abi(), tr.type());
				asyncCanons.add(ComponentWriter.canonTaskReturnTypeMemoryUtf8(type, 0));
				fields.add(tr.field());
				coreIndices.add(coreFunc++);
			}
			if (!imported.calls().isEmpty() || !imported.asyncs().isEmpty()) {
				// async calls await through the waitable-set; the async (non-blocking)
				// stream/future built-in wrappers park on it when BLOCKED
				for (String field : WasmComponentImportCompiler.WAITABLE_FIELDS) {
					if (!seen.add(field)) {
						continue;
					}
					asyncCanons.add(waitableCanon(field));
					fields.add(field);
					coreIndices.add(coreFunc++);
				}
			}
		}
		typeIdx = componentTypes.nextType();
		componentTypes.flush(c);
		if (!asyncCanons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(asyncCanons));
		}
		for (WasmComponentImportCompiler.Import imported : imports) {
			CoreExports core = Objects.requireNonNull(exports.get(imported.ifaceId()));
			List<Integer> asyncCores = asyncCoreOf.get(imported.ifaceId());
			if (asyncCores != null) {
				core.fields().addAll(Objects.requireNonNull(asyncFieldsOf.get(imported.ifaceId())));
				core.coreIndices().addAll(asyncCores);
			}
			coreInstances.add(ComponentWriter.coreInstanceFromFuncs(core.fields(), core.coreIndices()));
		}
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(coreInstances));
		return new Appended(typeIdx - nextType, compFunc - nextComponentFunc, coreFunc - nextCoreFunc);
	}

	/**
	 * The outcome of lowering a set of block-declared interfaces: the synthesized core
	 * instances are already emitted; {@code coreInstanceOf} maps each interface to its
	 * core instance index (feeding the core module's instantiation arguments by name).
	 * The {@code next*} cursors let the user imports and the export wiring continue
	 * without a hardcoded count.
	 */
	record FixedIo(java.util.Map<String, Integer> coreInstanceOf, int nextComponentFunc, int nextCoreFunc, int nextType,
			int nextCoreInstance) {
	}

	/**
	 * Lowers interfaces the import block ALREADY DECLARES by aliasing each bound function
	 * out of the block's import instance and canon-lowering it (async funcs with the
	 * async option), then the resource drops, the stream/future built-ins bound off the
	 * type aliases, the task-return built-ins and the waitable-set builtins -- the
	 * {@link #appendUserImports} emission kinds, against the block's instances
	 * (appendUserImports MINUS the instance-type / importInstance emission). Bound
	 * functions are deduplicated by canonical field name (two splices of one interface
	 * may repeat a function). Emits nothing when {@code fixed} is empty.
	 * @param c the component writer
	 * @param fixed the block-declared interfaces to lower
	 * @param instanceOf the block import-instance index of each fixed interface
	 * @param projected resource projections the block pre-declares, keyed
	 * {@code "<iface>#<resource>"} (shared with, and extended by, the drop / async-type
	 * machinery)
	 * @param firstComponentFunc the first free component function index
	 * @param firstCoreFunc the first free core function index
	 * @param firstType the first free component type index
	 * @param firstCoreInstance the first free core instance index
	 */
	static FixedIo lowerFixedFromBlock(ComponentWriter c, List<WasmComponentImportCompiler.Import> fixed,
			java.util.Map<String, Integer> instanceOf, java.util.Map<String, Integer> projected, int firstComponentFunc,
			int firstCoreFunc, int firstType, int firstCoreInstance) {
		if (fixed.isEmpty()) {
			// Emit nothing and shift nothing, so a component without a block-bound
			// interface stays byte-identical.
			return new FixedIo(new java.util.LinkedHashMap<>(), firstComponentFunc, firstCoreFunc, firstType,
					firstCoreInstance);
		}
		final List<byte[]> funcAliases = new java.util.ArrayList<>();
		final List<byte[]> lowers = new java.util.ArrayList<>();
		final List<byte[]> dropAliases = new java.util.ArrayList<>();
		final List<byte[]> dropCanons = new java.util.ArrayList<>();
		final java.util.Map<String, List<String>> names = new java.util.LinkedHashMap<>();
		final java.util.Map<String, List<Integer>> indices = new java.util.LinkedHashMap<>();
		int compFunc = firstComponentFunc;
		int coreFunc = firstCoreFunc;
		int typeIdx = firstType;
		// Pass A: the bound functions (sync then async), deduplicated by field within
		// each interface.
		for (WasmComponentImportCompiler.Import imported : fixed) {
			final int instance = Objects.requireNonNull(instanceOf.get(imported.ifaceId()));
			final List<String> fieldNames = new java.util.ArrayList<>();
			final List<Integer> coreIndices = new java.util.ArrayList<>();
			final java.util.Set<String> seen = new java.util.HashSet<>();
			for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
				if (!seen.add(decl.field())) {
					continue;
				}
				funcAliases.add(ComponentWriter.aliasInstanceFunc(instance, decl.field()));
				lowers.add(WasmComponentImportCompiler.needsMemory(decl)
						? ComponentWriter.canonLowerMemoryReallocUtf8(compFunc, 0, 0)
						: ComponentWriter.canonLower(compFunc));
				fieldNames.add(decl.field());
				coreIndices.add(coreFunc);
				compFunc++;
				coreFunc++;
			}
			for (WasmComponentImportCompiler.AsyncCall call : imported.calls()) {
				if (!seen.add(call.field())) {
					continue;
				}
				funcAliases.add(ComponentWriter.aliasInstanceFunc(instance, call.field()));
				lowers.add(ComponentWriter.canonLowerAsyncMemoryReallocUtf8(compFunc, 0, 0));
				fieldNames.add(call.field());
				coreIndices.add(coreFunc);
				compFunc++;
				coreFunc++;
			}
			names.put(imported.ifaceId(), fieldNames);
			indices.put(imported.ifaceId(), coreIndices);
		}
		// Pass B: the resource drops, deduplicated by resource, reusing an
		// already-projected resource type.
		for (WasmComponentImportCompiler.Import imported : fixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			final List<Integer> coreIndices = Objects.requireNonNull(indices.get(imported.ifaceId()));
			final java.util.Set<String> seenDrops = new java.util.HashSet<>(fieldNames);
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				if (!seenDrops.add(drop.field())) {
					continue;
				}
				final String key = imported.ifaceId() + "#" + drop.resource();
				Integer type = projected.get(key);
				if (type == null) {
					dropAliases.add(ComponentWriter.aliasInstanceType(
							Objects.requireNonNull(instanceOf.get(imported.ifaceId())), drop.resource()));
					type = typeIdx++;
					projected.put(key, type);
				}
				dropCanons.add(ComponentWriter.canonResourceDrop(type));
				fieldNames.add(drop.field());
				coreIndices.add(coreFunc++);
			}
		}
		if (!funcAliases.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(funcAliases));
		}
		if (!lowers.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lowers));
		}
		if (!dropAliases.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(dropAliases));
		}
		if (!dropCanons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(dropCanons));
		}
		// Pass C: the async built-ins (typed by component-level types derived from the
		// WIT, resources projected through the shared map), the task-return built-ins
		// and the waitable-set builtins of each async-calling interface.
		final WitComponentLevelTypes componentTypes = new WitComponentLevelTypes(typeIdx, projected,
				ifaceId -> Objects.requireNonNull(instanceOf.get(ifaceId),
						() -> "the WIT interface '" + ifaceId + "' owning an async type's resource is not imported"));
		final List<byte[]> asyncCanons = new java.util.ArrayList<>();
		for (WasmComponentImportCompiler.Import imported : fixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			final List<Integer> coreIndices = Objects.requireNonNull(indices.get(imported.ifaceId()));
			final java.util.Set<String> seen = new java.util.LinkedHashSet<>(fieldNames);
			final java.util.Map<String, Integer> typeOfAlias = new java.util.LinkedHashMap<>();
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				typeOfAlias.computeIfAbsent(async.alias(),
						a -> componentTypes.indexOf(imported.resolver(), async.abi(), async.type()));
			}
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				if (!seen.add(async.field())) {
					continue;
				}
				int type = Objects.requireNonNull(typeOfAlias.get(async.alias()));
				asyncCanons.add(asyncCanon(async, type));
				fieldNames.add(async.field());
				coreIndices.add(coreFunc++);
			}
			for (WasmComponentImportCompiler.TaskReturn tr : imported.taskReturns()) {
				if (!seen.add(tr.field())) {
					continue;
				}
				int type = componentTypes.indexOf(imported.resolver(), tr.abi(), tr.type());
				asyncCanons.add(ComponentWriter.canonTaskReturnTypeMemoryUtf8(type, 0));
				fieldNames.add(tr.field());
				coreIndices.add(coreFunc++);
			}
			if (!imported.calls().isEmpty() || !imported.asyncs().isEmpty()) {
				// async calls await through the waitable-set; the async (non-blocking)
				// stream/future built-in wrappers park on it when BLOCKED
				for (String field : WasmComponentImportCompiler.WAITABLE_FIELDS) {
					if (!seen.add(field)) {
						continue;
					}
					asyncCanons.add(waitableCanon(field));
					fieldNames.add(field);
					coreIndices.add(coreFunc++);
				}
			}
		}
		typeIdx = componentTypes.nextType();
		componentTypes.flush(c);
		if (!asyncCanons.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(asyncCanons));
		}
		final List<byte[]> coreInstanceDefs = new java.util.ArrayList<>();
		final java.util.Map<String, Integer> coreInstanceOf = new java.util.LinkedHashMap<>();
		int coreInstance = firstCoreInstance;
		for (WasmComponentImportCompiler.Import imported : fixed) {
			final List<String> fieldNames = Objects.requireNonNull(names.get(imported.ifaceId()));
			if (fieldNames.isEmpty()) {
				continue;
			}
			coreInstanceDefs.add(ComponentWriter.coreInstanceFromFuncs(fieldNames,
					Objects.requireNonNull(indices.get(imported.ifaceId()))));
			coreInstanceOf.put(imported.ifaceId(), coreInstance++);
		}
		if (!coreInstanceDefs.isEmpty()) {
			c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(coreInstanceDefs));
		}
		return new FixedIo(coreInstanceOf, compFunc, coreFunc, typeIdx, coreInstance);
	}

	// The first bound async op per ALIAS: the ops of one alias share one stream/future
	// type, so the type derivation runs once per alias.
	private static List<WasmComponentImportCompiler.Async> distinctAsyncTypes(
			WasmComponentImportCompiler.Import imported) {
		final java.util.Map<String, WasmComponentImportCompiler.Async> byAlias = new java.util.LinkedHashMap<>();
		for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
			byAlias.putIfAbsent(async.alias(), async);
		}
		return new java.util.ArrayList<>(byAlias.values());
	}

	// The canon entry of one async built-in, typed by the derived component-level type.
	// Memory options mirror the hand-assembled base block: reads and writes stage
	// through the shared memory (core memory 0); a future.read whose payload carries a
	// string/list<u8> additionally needs realloc (the shared memory's cabi_realloc =
	// core func 0) for the host-staged bytes.
	static byte[] asyncCanon(WasmComponentImportCompiler.Async async, int type) {
		return switch (async.op()) {
			case NEW -> async.stream() ? ComponentWriter.canonStreamNew(type) : ComponentWriter.canonFutureNew(type);
			// reads/writes are the ASYNC (non-blocking) built-in variants of base
			// component-model-async: the generated wrapper parks on the interface's
			// waitable-set when one reports BLOCKED (no gated feature involved)
			case READ -> async.stream() ? ComponentWriter.canonStreamReadAsync(type, 0)
					: (WasmComponentImportCompiler.asyncReadNeedsRealloc(async)
							? ComponentWriter.canonFutureReadAsync(type, 0, 0)
							: ComponentWriter.canonFutureReadAsync(type, 0));
			case WRITE -> async.stream() ? ComponentWriter.canonStreamWriteAsync(type, 0)
					: ComponentWriter.canonFutureWriteAsync(type, 0);
			case DROP_READABLE -> async.stream() ? ComponentWriter.canonStreamDropReadable(type)
					: ComponentWriter.canonFutureDropReadable(type);
			case DROP_WRITABLE -> async.stream() ? ComponentWriter.canonStreamDropWritable(type)
					: ComponentWriter.canonFutureDropWritable(type);
		};
	}

	// The canon entry of one waitable-set builtin (host-verified core signatures; the
	// wait's event payload is written through the shared memory, core memory 0).
	static byte[] waitableCanon(String field) {
		return switch (field) {
			case WasmComponentImportCompiler.FIELD_WAITABLE_SET_NEW -> ComponentWriter.canonWaitableSetNew();
			case WasmComponentImportCompiler.FIELD_WAITABLE_SET_WAIT -> ComponentWriter.canonWaitableSetWait(0);
			case WasmComponentImportCompiler.FIELD_WAITABLE_SET_DROP -> ComponentWriter.canonWaitableSetDrop();
			case WasmComponentImportCompiler.FIELD_WAITABLE_JOIN -> ComponentWriter.canonWaitableJoin();
			case WasmComponentImportCompiler.FIELD_SUBTASK_DROP -> ComponentWriter.canonSubtaskDrop();
			default -> throw new IllegalStateException("not a waitable builtin field: " + field);
		};
	}

	// The core functions an interface's synthesized core instance exports, by the field
	// name the core module imports them under. Filled in two passes (the lowered
	// functions,
	// then the drops), so it outlives the loop that starts it.
	private record CoreExports(List<String> fields, List<Integer> coreIndices) {
	}

	// The rontolisp core instance's instantiation arguments: mem + the fixed adapter
	// names, then one argument per user interface (module name = the interface's
	// canonical id, satisfied by its synthesized core instance starting right after the
	// adapter instance).
	static byte[] rontolispInstantiate(int moduleIndex, List<String> fixedNames, List<Integer> fixedInstances,
			List<WasmComponentImportCompiler.Import> imports, int firstUserCoreInstance) {
		final List<String> names = new java.util.ArrayList<>(fixedNames);
		final List<Integer> instances = new java.util.ArrayList<>(fixedInstances);
		int idx = firstUserCoreInstance;
		for (WasmComponentImportCompiler.Import imported : imports) {
			names.add(imported.ifaceId());
			instances.add(idx++);
		}
		return ComponentWriter.coreInstanceInstantiate(moduleIndex, names, instances);
	}

	/**
	 * Assemble the serve-variant component for a {@code rontolisp:http-handler} program:
	 * wrap the rontolisp core (compiled in serve mode, exporting http.lisp's
	 * {@code handle} and its real callback {@code async_cb}) with the preview1 bridge
	 * ({@code adapter-http-server-p1.wasm}: random / clocks / stdout-stderr over the 0.3
	 * service-world interfaces) and lift {@code handle} as the callback-async
	 * {@code wasi:http/handler@0.3.0} export -- a pending handler returns the packed WAIT
	 * code and the host drives it through the callback. The HTTP glue is Lisp
	 * (http.lisp), so there is no hand-written serve adapter. Runs under
	 * {@code wasmtime serve -W gc=y -W exceptions=y} (wasmtime 46+; everything is base
	 * component-model-async).
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @return the wasi:http@0.3.0 handler component binary
	 */
	public static byte[] buildServe(byte[] coreModule) {
		return buildServe(coreModule, List.of());
	}

	/**
	 * Assemble the serve-variant component, additionally importing the given interfaces.
	 * ONE shape serves plain serve AND serve+fetch (the 0.3 service world always imports
	 * {@code client}); a served handler whose state lives in a real store adds a
	 * {@code rontolisp:wit-import} (e.g. wasi:keyvalue), wired by
	 * {@link #appendUserImports}.
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @param imports the interface imports (fixed wasi:http from http.lisp, plus any user
	 * {@code rontolisp:wit-import}; empty for none)
	 * @return the wasi:http@0.3.0 handler component binary
	 */
	static byte[] buildServe(byte[] coreModule, List<WasmComponentImportCompiler.Import> imports) {
		// Only the ADDITIONAL rontolisp:wit-import interfaces are checked for a
		// double-import collision -- the fixed wasi:http surface is declared by the
		// import block and lowered from it, not re-imported. ONE world serves plain
		// serve and serve+fetch (the 0.3 service world always imports client).
		rejectAdapterImportCollisions(WasmServeComponentBuilder.additionalImports(imports),
				WitEmitter.VARIANT_HTTP_SERVER);
		return WasmServeComponentBuilder.build(coreModule, imports);
	}

	/**
	 * Assemble the base WASI 0.3 component (no {@code rontolisp:fetch}).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @param fixed the block-declared interfaces to bind FROM the block (wait.lisp's
	 * wasi:clocks/monotonic-clock; empty for none)
	 * @param imports the genuine user interface imports
	 * @return the WASI 0.3 component binary
	 */
	private static byte[] buildBase(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports,
			List<WasmComponentImportCompiler.Import> fixed, List<WasmComponentImportCompiler.Import> imports) {
		final int userIfaces = imports.size();
		final ComponentWriter c = new ComponentWriter();
		// All imported WASI 0.3 interfaces in one block: import instances 0-8, types
		// 0-11.
		c.writeRaw(IMPORT_BLOCK);
		// Core modules: 0 = shared memory, 1 = adapter, 2 = rontolisp. The shared
		// memory module is sized to fit the rontolisp module's static data / intern
		// pool (its memory-import min-pages declaration), so a program with a large
		// data segment does not trap on the very first data-segment write when the
		// mem module is instantiated with only its default six pages.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, memModuleFor(coreModule));
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Alias the resource/record types we need (component types 16-19) and the WASI
		// functions (component funcs 0-11), all in one alias section.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// types 14 cli error-code, 15 fs error-code, 16 descriptor
				ComponentWriter.aliasInstanceType(INST_CLI_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "descriptor"),
				// type 19 directory-entry (the read-directory stream's element)
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "directory-entry"),
				// funcs 0 write-via-stream, 1 read-via-stream(stdin), 2 get-environment,
				// 3 system-clock.now, 4 monotonic.now
				ComponentWriter.aliasInstanceFunc(INST_STDOUT, "write-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_STDIN, "read-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_ENVIRON, "get-environment"),
				ComponentWriter.aliasInstanceFunc(INST_SYS_CLOCK, "now"),
				ComponentWriter.aliasInstanceFunc(INST_MONO_CLOCK, "now"),
				// funcs 5 read-via-stream(file), 6 append-via-stream, 7 open-at,
				// 8 get-directories, 9 get-random-u64
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.read-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.append-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.open-at"),
				ComponentWriter.aliasInstanceFunc(INST_FS_PREOPENS, "get-directories"),
				ComponentWriter.aliasInstanceFunc(INST_RANDOM, "get-random-u64"),
				// func 10 stderr write-via-stream, func 11 descriptor.read-directory
				// (both appended last, so every index above keeps its value)
				ComponentWriter.aliasInstanceFunc(INST_STDERR, "write-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.read-directory"))));
		// Define the async value/function types (component types 20-27). The two streams
		// are structural; the futures differ by their error-code (cli vs filesystem).
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 20
						ComponentWriter.definedStreamOfType(T_DIRENT), // 21
						ComponentWriter.definedResultErr(T_CLI_ERRCODE), // 22
						ComponentWriter.definedFuture(T_CLI_RESULT), // 23
						ComponentWriter.definedResultErr(T_FS_ERRCODE), // 24
						ComponentWriter.definedFuture(T_FS_RESULT), // 25
						ComponentWriter.definedResultVoid(), // 26 result<_,_> (run
																// result)
						ComponentWriter.asyncFuncTypeResultType(T_RUN_RESULT)))); // 27
		// Lower the WASI functions (component funcs 0-9) to core funcs 1-10 and drop the
		// descriptor resource (core func 11); canonical options mirror wasm-tools'
		// choices.
		// Then the async built-ins: stream (core funcs 12-16), futures (core funcs
		// 17-20), stderr write-via-stream (component func 10 -> core func 21), the
		// waitable trio (22-24) and finally the directory-listing trio (25-27).
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLower(0), // 1
																											// write-via-stream
				ComponentWriter.canonLowerMemory(1, 0), // 2 read-via-stream (stdin)
				ComponentWriter.canonLowerMemoryReallocUtf8(2, 0, 0), // 3 get-environment
				ComponentWriter.canonLowerMemory(3, 0), // 4 system-clock.now
				ComponentWriter.canonLower(4), // 5 monotonic.now
				ComponentWriter.canonLowerMemory(5, 0), // 6 descriptor.read-via-stream
				ComponentWriter.canonLower(6), // 7 descriptor.append-via-stream
				ComponentWriter.canonLowerMemoryReallocUtf8(7, 0, 0), // 8 open-at
				ComponentWriter.canonLowerMemoryReallocUtf8(8, 0, 0), // 9 get-directories
				ComponentWriter.canonLower(9), // 10 get-random-u64
				ComponentWriter.canonResourceDrop(T_DESCRIPTOR), // 11 drop descriptor
				ComponentWriter.canonStreamNew(T_STREAM), // 12
				// The ASYNC (non-blocking) built-in variants of base
				// component-model-async: a BLOCKED result completes through the
				// waitable-set trio below (the adapter's blocking wrappers), so no
				// gated feature is needed (neither more-async-builtins nor stackful).
				ComponentWriter.canonStreamReadAsync(T_STREAM, 0), // 13
				ComponentWriter.canonStreamWriteAsync(T_STREAM, 0), // 14
				ComponentWriter.canonStreamDropReadable(T_STREAM), // 15
				ComponentWriter.canonStreamDropWritable(T_STREAM), // 16
				ComponentWriter.canonFutureReadAsync(T_CLI_FUTURE, 0), // 17
																		// future-read-cli
				ComponentWriter.canonFutureDropReadable(T_CLI_FUTURE), // 18
																		// future-drop-cli
				// the filesystem error-code is a variant with a string-bearing case, so
				// its
				// future payload needs realloc (cabi_realloc = core func 0)
				ComponentWriter.canonFutureReadAsync(T_FS_FUTURE, 0, 0), // 19
																			// future-read-fs
				ComponentWriter.canonFutureDropReadable(T_FS_FUTURE), // 20
																		// future-drop-fs
				ComponentWriter.canonLower(10), // 21 stderr write-via-stream
				ComponentWriter.canonWaitableSetNew(), // 22
				ComponentWriter.canonWaitableJoin(), // 23
				ComponentWriter.canonWaitableSetWait(0), // 24
				// 25 descriptor.read-directory: the result is a (stream, future) handle
				// pair, two flat values, so it returns through a memory retptr.
				ComponentWriter.canonLowerMemory(11, 0), // 25 read-directory
				// 26/27 the directory-entry stream's own read / drop. The read needs
				// realloc: each element owns its name string.
				ComponentWriter.canonStreamReadAsync(T_DE_STREAM, 0, 0), // 26
				ComponentWriter.canonStreamDropReadable(T_DE_STREAM)))); // 27
		// Group the 27 lowered/built-in core funcs (1-27) for the adapter's "w" import
		// (core instance 1). Names match adapter.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
					List.of("stdout-write", "stdin-read", "get-environment", "sys-now", "mono-now", "file-read",
							"file-append", "open-at", "get-directories", "get-random-u64", "drop-desc", "stream-new",
							"stream-read", "stream-write", "stream-drop-r", "stream-drop-w", "future-read-cli",
							"future-drop-cli", "future-read-fs", "future-drop-fs", "stderr-write", "waitable-set-new",
							"waitable-join", "waitable-set-wait", "read-dir", "stream-read-de", "stream-drop-r-de"),
					List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
							26, 27)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// Block-declared interfaces the program binds (wait.lisp's monotonic-clock,
		// stdin.lisp's wasi:cli/stdin, environment.lisp's wasi:cli/environment): lowered
		// FROM the block's own import instances (component funcs 12.., core funcs 28..,
		// core instances 3..). Emits nothing when there are none.
		final FixedIo io = lowerFixedFromBlock(c, fixed,
				java.util.Map.of("wasi:clocks/monotonic-clock@0.3.0", INST_MONO_CLOCK, "wasi:cli/stdin@0.3.0",
						INST_STDIN, "wasi:cli/environment@0.3.0", INST_ENVIRON),
				new java.util.LinkedHashMap<>(), 12, 28, T_RUN_FUNC + 1, 3);
		// User WIT-interface imports (rontolisp:wit-import): instance types, import
		// instances (from 11, right after the block's 0-10), function aliases and
		// lowered core funcs continue after the fixed surface. Emits nothing when there
		// are none, so every fixed index below shifts by zero -- and what it DID consume
		// of each index space comes back as `user`, the single source every downstream
		// fixed index shifts by.
		final Appended user = appendUserImports(c, imports, io.nextType(), 11, io.nextComponentFunc(),
				io.nextCoreFunc());
		// Instantiate rontolisp (core instance 3 + one per fixed/user interface): mem =
		// instance 0, wasi_snapshot_preview1 = adapter instance 2, plus each fixed
		// interface's block-lowered core instance and each user interface's
		// canon-lowered core instance under its canonical id.
		final List<String> coreNames = new java.util.ArrayList<>(List.of("mem", "wasi_snapshot_preview1"));
		final List<Integer> coreInstances = new java.util.ArrayList<>(List.of(0, 2));
		io.coreInstanceOf().forEach((ifaceId, instance) -> {
			coreNames.add(ifaceId);
			coreInstances.add(instance);
		});
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(rontolispInstantiate(2, coreNames, coreInstances, imports, io.nextCoreInstance()))));
		final int rontolisp = io.nextCoreInstance() + userIfaces;
		// Alias rontolisp's run (core func 28 = cabi_realloc + 27 lowered/built-in
		// funcs, + the fixed and user-import lowers).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(rontolisp, "run"))));
		// Lift run into component func 12 (+ the fixed/user-import aliases) with the
		// async function type 27 (an async-typed sync-ABI lift: the task may block, no
		// gated feature involved). Component func 12 follows the 12 aliased WASI funcs
		// (0-11).
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter
			.vec(List.of(ComponentWriter.canonLift(io.nextCoreFunc() + user.coreFuncs(), T_RUN_FUNC))));
		// Component instance 11 (after import instances 0-10 + the user imports)
		// exporting run, exported as the wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.componentInstanceFromFunc("run", io.nextComponentFunc() + user.componentFuncs()))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 11 + userIfaces))));
		// Scalar wasm-export functions: next free indices after the run wiring, each
		// shifted by the fixed and user-import counts. The component instance index space
		// at this point holds the run from-exports instance (11 + userIfaces) AND the
		// instance its `export wasi:cli/run` statement itself introduces (12 +
		// userIfaces)
		// -- an instance export consumes an index -- so the next free instance is
		// 13 + userIfaces.
		appendFuncExports(c, funcExports, io.nextCoreFunc() + user.coreFuncs() + 1, io.nextType() + user.types(),
				io.nextComponentFunc() + user.componentFuncs() + 1, rontolisp, 13 + userIfaces);
		return c.toByteArray();
	}

	/**
	 * Load a fixed component blob bundled as a classpath resource next to this class.
	 * @param name the file name under {@code component/}
	 * @return the raw bytes
	 */
	private static byte[] resource(String name) {
		try (InputStream in = WasmComponentBuilder.class.getResourceAsStream(RES + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing component resource: " + RES + name);
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to read component resource: " + RES + name, ex);
		}
	}

	/**
	 * Return {@link #MEM_MODULE} bytes with the exported memory sized to at least the
	 * rontolisp core module's memory-import minimum. The default {@code mem.wasm} exports
	 * six pages, which fits every small program but traps at instantiation on a program
	 * whose static data / intern pool alone exceeds 384KB (uax-15's ~2.7MB UnicodeData
	 * tables, for instance). This walks the core module's import section to find its
	 * {@code "mem"/"memory"} declaration and rewrites the mem module's memory section so
	 * its exported memory starts with at least that many pages -- because active data
	 * segments are copied to memory at instantiation, growing on demand from
	 * {@code _start} is too late.
	 * @param coreModule the rontolisp core module whose memory-import minimum drives the
	 * mem module's initial size
	 * @return a fresh copy of {@link #MEM_MODULE} with the memory section rewritten, or
	 * the unchanged resource when the core module asks for six pages or fewer
	 */
	static byte[] memModuleFor(byte[] coreModule) {
		int needed = requiredMemPagesFromCore(coreModule);
		if (needed <= 6) {
			return MEM_MODULE;
		}
		return patchMemModuleMinPages(MEM_MODULE, needed);
	}

	/**
	 * Extract the minimum-pages value from the {@code "mem"/"memory"} import of the given
	 * core module. Returns six (the mem module's default) when the import is not present
	 * so any embedder that reuses the mem module beside a non-rontolisp core module keeps
	 * working.
	 * @param coreModule the core module bytes
	 * @return the requested min pages, or six when the import is absent
	 */
	private static int requiredMemPagesFromCore(byte[] coreModule) {
		// Walk the sections: skip \0asm + version, then each (id, size, body).
		int pos = 8;
		while (pos < coreModule.length) {
			int id = coreModule[pos++] & 0xFF;
			long[] sz = readLeb128(coreModule, pos);
			pos = (int) sz[1];
			int bodyEnd = pos + (int) sz[0];
			if (id != 2) { // 2 = import section
				pos = bodyEnd;
				continue;
			}
			// Import section body: vec of imports. Each import is
			// module-name (leb len + utf-8), field-name (leb len + utf-8), kind byte,
			// then kind-specific descriptor.
			long[] count = readLeb128(coreModule, pos);
			pos = (int) count[1];
			for (int i = 0; i < count[0]; i++) {
				long[] modLen = readLeb128(coreModule, pos);
				pos = (int) modLen[1];
				String modName = new String(coreModule, pos, (int) modLen[0]);
				pos += (int) modLen[0];
				long[] fldLen = readLeb128(coreModule, pos);
				pos = (int) fldLen[1];
				String fldName = new String(coreModule, pos, (int) fldLen[0]);
				pos += (int) fldLen[0];
				int kind = coreModule[pos++] & 0xFF;
				// kind 0 = func (type index leb), 1 = table (elemtype + limits),
				// 2 = memory (limits), 3 = global (valtype + mutability byte).
				if (kind == 2 && "mem".equals(modName) && "memory".equals(fldName)) {
					int flags = coreModule[pos++] & 0xFF;
					long[] min = readLeb128(coreModule, pos);
					return (int) min[0];
				}
				// Skip the descriptor of imports we don't care about.
				switch (kind) {
					case 0 -> {
						long[] typeIdx = readLeb128(coreModule, pos);
						pos = (int) typeIdx[1];
					}
					case 1 -> {
						pos++; // elem type
						int lim = coreModule[pos++] & 0xFF;
						long[] mn = readLeb128(coreModule, pos);
						pos = (int) mn[1];
						if ((lim & 0x01) != 0) {
							long[] mx = readLeb128(coreModule, pos);
							pos = (int) mx[1];
						}
					}
					case 2 -> {
						int lim = coreModule[pos++] & 0xFF;
						long[] mn = readLeb128(coreModule, pos);
						pos = (int) mn[1];
						if ((lim & 0x01) != 0) {
							long[] mx = readLeb128(coreModule, pos);
							pos = (int) mx[1];
						}
					}
					case 3 -> {
						pos++; // valtype
						pos++; // mutability
					}
					default -> throw new IllegalStateException("Unknown import kind " + kind);
				}
			}
			pos = bodyEnd;
		}
		return 6;
	}

	/**
	 * Patch the given mem module bytes so its memory section declares the given min
	 * pages. Assumes the memory section is the first section with id 5 and rewrites only
	 * its size prefix and min-pages LEB128. Bytes after the memory section are shifted by
	 * the size delta.
	 * @param original the original mem module bytes
	 * @param minPages the new min-pages value
	 * @return a fresh byte array with the memory section rewritten
	 */
	private static byte[] patchMemModuleMinPages(byte[] original, int minPages) {
		int pos = 8; // \0asm + version
		while (pos < original.length) {
			int id = original[pos] & 0xFF;
			long[] sz = readLeb128(original, pos + 1);
			int sizePos = pos + 1;
			int bodyPos = (int) sz[1];
			int bodyLen = (int) sz[0];
			if (id != 5) {
				pos = bodyPos + bodyLen;
				continue;
			}
			// memory section body: vec of memories. Each memory is (limits).
			// Rewrite the whole body: count 01, flags 00, minPages LEB128.
			byte[] newLeb = encodeLeb128(minPages);
			byte[] newBody = new byte[2 + newLeb.length];
			newBody[0] = 0x01; // count
			newBody[1] = 0x00; // flags: min only
			System.arraycopy(newLeb, 0, newBody, 2, newLeb.length);
			byte[] newSize = encodeLeb128(newBody.length);
			byte[] result = new byte[pos + 1 + newSize.length + newBody.length
					+ (original.length - (bodyPos + bodyLen))];
			System.arraycopy(original, 0, result, 0, pos + 1);
			int cur = pos + 1;
			System.arraycopy(newSize, 0, result, cur, newSize.length);
			cur += newSize.length;
			System.arraycopy(newBody, 0, result, cur, newBody.length);
			cur += newBody.length;
			System.arraycopy(original, bodyPos + bodyLen, result, cur, original.length - (bodyPos + bodyLen));
			return result;
		}
		return original;
	}

	// Reads an unsigned LEB128 integer starting at position and returns [value, nextPos].
	private static long[] readLeb128(byte[] buf, int pos) {
		long value = 0;
		int shift = 0;
		while (true) {
			int b = buf[pos++] & 0xFF;
			value |= ((long) (b & 0x7F)) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		return new long[] { value, pos };
	}

	// Encodes an unsigned integer as LEB128.
	private static byte[] encodeLeb128(int value) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int v = value;
		while (true) {
			int b = v & 0x7F;
			v >>>= 7;
			if (v == 0) {
				out.write(b);
				break;
			}
			out.write(b | 0x80);
		}
		return out.toByteArray();
	}

}
