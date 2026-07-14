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
 * runnable with {@code wasmtime run -W gc=y -W component-model-more-async-builtins=y}
 * (wasmtime 46+; the async canonical ABI and stackful lifts are on by default there, only
 * the synchronous stream/future built-ins the adapter uses are still gated).
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
 * {@code wasm-tools validate -f component-model -f cm-async -f cm-async-stackful
 * -f cm-more-async-builtins} and executed with {@code wasmtime run}.
 */
public final class WasmComponentBuilder {

	/** Classpath location of the embedded component blobs, relative to this class. */
	private static final String RES = "component/";

	/**
	 * Pre-built component type/import sections declaring every imported WASI 0.3
	 * interface, captured from a {@code wasm-tools}-generated reference for the
	 * {@code uni} WIT world (see {@code src/wasm-component/uni.wit}). In order, component
	 * import instances 0-9 are: {@code wasi:cli/types} (the {@code error-code} enum),
	 * {@code wasi:cli/stdout}, {@code wasi:cli/stdin}, {@code wasi:cli/environment},
	 * {@code wasi:clocks/system-clock}, {@code wasi:clocks/monotonic-clock},
	 * {@code wasi:filesystem/types}, {@code wasi:filesystem/preopens},
	 * {@code wasi:random/random} and {@code wasi:cli/stderr} (appended last, for
	 * {@code warn} on fd&nbsp;2). The block defines component types 0-13, so the next
	 * free component type index is 14.
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

	/**
	 * The HTTP variant of the import block: the base WASI 0.3 interfaces PLUS the WASI
	 * 0.2 HTTP machinery for {@code rontolisp:fetch} ({@code wasi:io/poll},
	 * {@code wasi:io/error}, {@code wasi:io/streams}, {@code wasi:http/types},
	 * {@code wasi:http/outgoing-handler}) plus {@code wasi:cli/stderr@0.3.0} appended
	 * last (import instance 14, for {@code warn} on fd&nbsp;2). fetch stays on
	 * {@code wasi:http@0.2} because an async {@code wasi:http@0.3} does not exist
	 * upstream yet (see {@code .todo/02-upgrade-fetch-to-wasi-http-0.3.md}). It declares
	 * component import instances 0-14, so the next free component type index is 27.
	 * Source: {@code src/wasm-component/uni-http-client.wit} +
	 * {@code core-http-client.wat}.
	 */
	private static final byte[] IMPORT_BLOCK_HTTP_CLIENT = resource("import-block-http-client.bin");

	/**
	 * The shared memory module for the HTTP variant (16 pages, for the fetch
	 * response-header / body scratch). Source:
	 * {@code src/wasm-component/mem-http-client.wat}.
	 */
	private static final byte[] MEM_MODULE_HTTP_CLIENT = resource("mem-http-client.wasm");

	/**
	 * The HTTP variant of the adapter: like {@link #ADAPTER_MODULE} but with extra
	 * {@code fetch-start} / {@code fetch-await} exports driving an asynchronous outgoing
	 * request (the {@code rontolisp:fetch} promise API) over {@code wasi:http@0.2} +
	 * {@code wasi:io@0.2}. Source: {@code src/wasm-component/adapter-http-client.wat}.
	 */
	private static final byte[] ADAPTER_MODULE_HTTP_CLIENT = resource("adapter-http-client.wasm");

	/**
	 * The sockets variant of the import block: the base WASI 0.3 interfaces PLUS
	 * {@code wasi:sockets/types@0.3.0} (instance 9) for the {@code rontolisp:tcp-*}
	 * built-ins, then {@code wasi:cli/stderr@0.3.0} appended last (instance 10, for
	 * {@code warn} on fd&nbsp;2). Unlike fetch, sockets exist natively in WASI 0.3 so the
	 * variant stays pure 0.3. It declares component import instances 0-10 and component
	 * types 0-14, so the next free component type index is 15. Source:
	 * {@code src/wasm-component/uni-sockets.wit} + {@code core-sockets.wat}.
	 */
	private static final byte[] IMPORT_BLOCK_SOCKETS = resource("import-block-sockets.bin");

	/**
	 * The sockets variant of the adapter: like {@link #ADAPTER_MODULE} but with extra
	 * {@code tcp-connect} / {@code tcp-listen} / {@code tcp-accept} /
	 * {@code tcp-local-port} exports and fd&nbsp;&gt;=&nbsp;200 socket branches in
	 * {@code fd_write}/{@code fd_read}/{@code fd_close}, over {@code wasi:sockets@0.3.0}.
	 * It shares {@link #MEM_MODULE} (the socket table and scratch fit in page 5). Source:
	 * {@code src/wasm-component/adapter-sockets.wat}.
	 */
	private static final byte[] ADAPTER_MODULE_SOCKETS = resource("adapter-sockets.wasm");

	// Component import-instance indices (from import-block.bin; see IMPORT_BLOCK).
	private static final int INST_CLI_TYPES = 0;

	private static final int INST_STDOUT = 1;

	private static final int INST_STDIN = 2;

	private static final int INST_ENVIRON = 3;

	private static final int INST_SYS_CLOCK = 4;

	private static final int INST_MONO_CLOCK = 5;

	private static final int INST_FS_TYPES = 6;

	private static final int INST_FS_PREOPENS = 7;

	private static final int INST_RANDOM = 8;

	private static final int INST_STDERR = 9;

	// First free component type index after the import block. The stderr interface
	// appended
	// last to uni.wit adds two component types (an aliased cli error-code + the stderr
	// instance type), so the block now defines types 0-13 and the next free index is 14.
	private static final int T_CLI_ERRCODE = 14;

	private static final int T_FS_ERRCODE = 15;

	private static final int T_DESCRIPTOR = 16;

	private static final int T_STREAM = 17;

	private static final int T_CLI_RESULT = 18;

	private static final int T_CLI_FUTURE = 19;

	private static final int T_FS_RESULT = 20;

	private static final int T_FS_FUTURE = 21;

	private static final int T_RUN_RESULT = 22;

	private static final int T_RUN_FUNC = 23;

	private WasmComponentBuilder() {
	}

	/**
	 * A {@code rontolisp:wasm-export} function to expose as a component-model export. The
	 * core module core-exports a wrapper under {@code name}; the component aliases it and
	 * lifts it <strong>synchronously</strong> by default (a pure-compute export needs no
	 * async, unlike the stackful-async {@code run} lift). A scalar export lifts with no
	 * canonical options; a {@code :string}/{@code :s-expr}-involving one with the
	 * canonical string options (todo 92 Tier 2). An {@code :async t} export (todo 92 Tier
	 * 3) instead lifts against an <strong>async</strong> function type &mdash; the same
	 * stackful-async shape as {@code run}, with an identical flat core signature &mdash;
	 * so I/O inside it blocks cooperatively instead of trapping.
	 *
	 * @param name the export name (a valid component-model label; honors {@code :as})
	 * @param paramNames the parameter labels the function type carries ({@code p0},
	 * {@code p1}, ... by default; the WIT world's own names under
	 * {@code rontolisp:wit-export}, or an explicit {@code :param-names})
	 * @param paramValTypes the {@code ComponentWriter.VT_*} code of each parameter
	 * @param resultValType the {@code ComponentWriter.VT_*} result code, or {@code null}
	 * for no result
	 * @param async whether to lift against an async function type ({@code :async t})
	 */
	public record FuncExport(String name, List<String> paramNames, List<Integer> paramValTypes,
			@Nullable Integer resultValType, boolean async) {
	}

	/**
	 * Append the per-export alias / type / lift / export wiring for the
	 * {@code wasm-export} functions. Emits nothing when {@code decls} is empty, so an
	 * export-free program's component stays byte-identical. The rontolisp core is always
	 * core instance 3 (mem = 0, adapter = 2); each new index space entry is appended
	 * after the {@code run} wiring, whose next free indices the caller passes in.
	 *
	 * <p>
	 * A {@code :string}/{@code :s-expr} boundary type (todo 92 Tier 2) lifts through the
	 * canonical string ABI: the alias section additionally projects the core's own
	 * {@code cabi_realloc} (the host lowers string arguments into linear memory through
	 * it) and one {@code cabi_post_*} post-return per flat-result signature (it pops the
	 * core's bump heap back to the per-call snapshot once the host has copied the results
	 * out, intern-count-guarded), and the string-involving exports are lifted with the
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
	 */
	private static void appendFuncExports(ComponentWriter c, List<WasmExportCompiler.Decl> decls, int nextCoreFunc,
			int nextType, int nextComponentFunc, int rontolispInstance) {
		if (decls.isEmpty()) {
			return;
		}
		final List<byte[]> aliases = new java.util.ArrayList<>();
		final List<byte[]> types = new java.util.ArrayList<>();
		final List<byte[]> lifts = new java.util.ArrayList<>();
		final List<byte[]> exports = new java.util.ArrayList<>();
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
			// them): synchronous by default; an :async t export (todo 92 Tier 3) gets the
			// async counterpart (tag 0x43), which turns the lift below into a stackful
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
			// Export the lifted component func directly under the export name.
			exports.add(ComponentWriter.exportFunc(e.name(), nextComponentFunc + i));
		}
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(types));
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lifts));
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
		return build(coreModule, false);
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param usesHttp whether the program uses {@code rontolisp:fetch}
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule, boolean usesHttp) {
		return build(coreModule, usesHttp, false);
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param usesHttp whether the program uses {@code rontolisp:fetch}
	 * @param usesSockets whether the program uses a {@code rontolisp:tcp-*} built-in
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule, boolean usesHttp, boolean usesSockets) {
		return build(coreModule, usesHttp, usesSockets, List.of());
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module,
	 * additionally exposing the given {@code rontolisp:wasm-export} functions as
	 * host-callable component-model exports (synchronous canonical lifts alongside the
	 * stackful-async {@code wasi:cli/run} export; {@code :string}/{@code :s-expr}
	 * boundaries lift through the canonical string ABI, todo 92 Tier 2).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param usesHttp whether the program uses {@code rontolisp:fetch}
	 * @param usesSockets whether the program uses a {@code rontolisp:tcp-*} built-in
	 * @param funcExports the parsed function export directives (empty for none; an empty
	 * list yields output byte-identical to {@link #build(byte[], boolean, boolean)})
	 * @return the WASI 0.3 component binary
	 */
	public static byte[] build(byte[] coreModule, boolean usesHttp, boolean usesSockets,
			List<WasmExportCompiler.Decl> funcExports) {
		return build(coreModule, usesHttp, usesSockets, funcExports, List.of());
	}

	/**
	 * Assemble a runnable WASI 0.3 component around the given rontolisp core module,
	 * additionally importing the given user WIT interfaces ({@code rontolisp:wit-import}
	 * under {@code --component}): each interface becomes a component-level instance
	 * import whose functions are {@code canon lower}ed into a synthesized core instance
	 * that satisfies the core module's matching imports at instantiation. An empty import
	 * list emits nothing and shifts nothing, so an import-free component stays
	 * byte-identical.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param usesHttp whether the program uses {@code rontolisp:fetch}
	 * @param usesSockets whether the program uses a {@code rontolisp:tcp-*} built-in
	 * @param funcExports the parsed function export directives (empty for none)
	 * @param imports the user WIT interface imports (empty for none)
	 * @return the WASI 0.3 component binary
	 */
	static byte[] build(byte[] coreModule, boolean usesHttp, boolean usesSockets,
			List<WasmExportCompiler.Decl> funcExports, List<WasmComponentImportCompiler.Import> imports) {
		if (usesHttp && usesSockets) {
			// The compiler rejects this combination before reaching here.
			throw new UnsupportedOperationException("fetch and tcp sockets cannot be combined in one component yet");
		}
		String variant = usesHttp ? WitEmitter.VARIANT_HTTP_CLIENT
				: usesSockets ? WitEmitter.VARIANT_SOCKETS : WitEmitter.VARIANT_BASE;
		rejectAdapterImportCollisions(imports, variant);
		if (usesHttp) {
			return buildHttp(coreModule, funcExports, imports);
		}
		return usesSockets ? buildSock(coreModule, funcExports, imports) : buildBase(coreModule, funcExports, imports);
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
						+ "component cannot import the same interface twice. The surface grows with what the program "
						+ "uses -- rontolisp:fetch adds the wasi:http and wasi:io interfaces, the rontolisp:tcp-* "
						+ "built-ins add wasi:sockets/types -- so either drop the built-in and drive the interface "
						+ "through the WIT binding, or bind a different interface");
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
	static void appendUserImports(ComponentWriter c, List<WasmComponentImportCompiler.Import> imports, int nextType,
			int firstImportInstance, int nextComponentFunc, int nextCoreFunc) {
		if (imports.isEmpty()) {
			return;
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
		}
		for (WasmComponentImportCompiler.Import imported : imports) {
			if (imported.decls().isEmpty() && !provides.containsKey(imported.ifaceId())) {
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
		for (WasmComponentImportCompiler.Import imported : imports) {
			CoreExports core = Objects.requireNonNull(exports.get(imported.ifaceId()));
			coreInstances.add(ComponentWriter.coreInstanceFromFuncs(core.fields(), core.coreIndices()));
		}
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(coreInstances));
	}

	// The core functions an interface's synthesized core instance exports, by the field
	// name the core module imports them under. Filled in two passes (the lowered
	// functions,
	// then the drops), so it outlives the loop that starts it.
	private record CoreExports(List<String> fields, List<Integer> coreIndices) {
	}

	// The COMPONENT function indices the user imports consume: one alias per bound
	// function. A resource drop consumes NONE -- `canon resource.drop` produces a core
	// function directly -- which is exactly why this and userImportCoreFuncs are two
	// numbers. Feed this one to a component-function index (componentInstanceFromFunc,
	// the
	// nextComponentFunc of appendFuncExports) and the other to a core-function index
	// (aliasCoreFunc, canonLift's operand, the nextCoreFunc of appendFuncExports);
	// confuse
	// them and you get a component that VALIDATES while lifting the wrong function.
	static int userImportFuncs(List<WasmComponentImportCompiler.Import> imports) {
		int n = 0;
		for (WasmComponentImportCompiler.Import imported : imports) {
			n += imported.decls().size();
		}
		return n;
	}

	// The CORE function indices the user imports consume: one `canon lower` per bound
	// function, plus one `canon resource.drop` per bound drop.
	static int userImportCoreFuncs(List<WasmComponentImportCompiler.Import> imports) {
		int n = 0;
		for (WasmComponentImportCompiler.Import imported : imports) {
			n += imported.decls().size() + imported.drops().size();
		}
		return n;
	}

	// The component TYPE indices the user imports consume: one instance type each, plus
	// one projected type per resource an interface uses from another one. Downstream
	// hardcoded type indices (appendFuncExports) shift by this, NOT by the interface
	// count -- an import with no `use`d resources costs exactly one, which is why every
	// pre-existing artifact stays byte-identical.
	static int userImportTypes(List<WasmComponentImportCompiler.Import> imports) {
		final java.util.Set<String> projected = new java.util.LinkedHashSet<>();
		for (WasmComponentImportCompiler.Import imported : imports) {
			for (WitComponentTypeEncoder.ForeignResource foreign : WitComponentTypeEncoder
				.foreignResourcesOf(imported)) {
				projected.add(foreign.ownerIfaceId() + "#" + foreign.resource());
			}
		}
		// A dropped resource is projected too (canon resource.drop needs its type in this
		// component's index space) -- unless a `use` clause already projected it, in
		// which
		// case appendUserImports reuses the one projection and so must this count.
		for (WasmComponentImportCompiler.Import imported : imports) {
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				projected.add(imported.ifaceId() + "#" + drop.resource());
			}
		}
		return imports.size() + projected.size();
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
	 * wrap the rontolisp core (which exports {@code %http-dispatch},
	 * {@code __ronto_alloc} and {@code run}) with the preview1 bridge
	 * ({@code adapter-http-server-p1.wasm}: random / clocks / stdout-stderr for the
	 * core's {@code wasi_snapshot_preview1} imports) and the serve adapter
	 * ({@code adapter-http-server.wasm}) so the component exports
	 * {@code wasi:http/incoming-handler@0.2.0} and runs under {@code wasmtime serve} (or
	 * any {@code wasi:http} 0.2 host with wasm-GC enabled, e.g. jco or wasmCloud).
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @return the WASI 0.2 (http/incoming-handler) component binary
	 */
	public static byte[] buildServe(byte[] coreModule) {
		return buildServe(coreModule, false);
	}

	/**
	 * Assemble the serve-variant component for a {@code rontolisp:http-handler} program.
	 * When the program also uses {@code rontolisp:fetch}, the serve+fetch variant is
	 * assembled instead: the preview1 bridge is the extended
	 * {@code adapter-http-server-client-p1.wasm}, which additionally satisfies the core's
	 * {@code http} (fetch-start / fetch-await) imports so a served handler can make
	 * outgoing requests (run with {@code wasmtime serve -S http=y}).
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @param usesHttp whether the program uses {@code rontolisp:fetch}
	 * @return the WASI 0.2 (http/incoming-handler) component binary
	 */
	public static byte[] buildServe(byte[] coreModule, boolean usesHttp) {
		return buildServe(coreModule, usesHttp, List.of());
	}

	/**
	 * Assemble the serve-variant component for a {@code rontolisp:http-handler} program,
	 * additionally importing the given user WIT interfaces
	 * ({@code rontolisp:wit-import}): a served handler whose state lives in a real store
	 * is exactly this combination. The wiring is {@link #appendUserImports}, as on the
	 * three non-serve variants; an empty import list emits nothing and shifts nothing, so
	 * an import-free serve component stays byte-identical.
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @param usesHttp whether the program uses {@code rontolisp:fetch}
	 * @param imports the user WIT interface imports (empty for none)
	 * @return the WASI 0.2 (http/incoming-handler) component binary
	 */
	static byte[] buildServe(byte[] coreModule, boolean usesHttp, List<WasmComponentImportCompiler.Import> imports) {
		rejectAdapterImportCollisions(imports,
				usesHttp ? WitEmitter.VARIANT_HTTP_SERVER_CLIENT : WitEmitter.VARIANT_HTTP_SERVER);
		return usesHttp ? WasmServeComponentBuilder.buildHttp(coreModule, imports)
				: WasmServeComponentBuilder.build(coreModule, imports);
	}

	/**
	 * Assemble the base WASI 0.3 component (no {@code rontolisp:fetch}).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @return the WASI 0.3 component binary
	 */
	private static byte[] buildBase(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports,
			List<WasmComponentImportCompiler.Import> imports) {
		final int userIfaces = imports.size();
		// The type index space grows by the instance types AND the resources projected
		// out of them for a `use`d-across-interfaces reference; the instance index space
		// only by the interfaces. They are equal until an interface uses another's
		// resource, which is why every pre-existing component is byte-identical.
		final int userTypes = userImportTypes(imports);
		final int userFuncs = userImportFuncs(imports);
		// The other function count: a resource drop is a CORE function with no component
		// function behind it (canon resource.drop), so an index into the core function
		// space must skip the drops too. Confusing the two yields a component that
		// VALIDATES while lifting the wrong function.
		final int userCoreFuncs = userImportCoreFuncs(imports);
		final ComponentWriter c = new ComponentWriter();
		// All imported WASI 0.3 interfaces in one block: import instances 0-8, types
		// 0-11.
		c.writeRaw(IMPORT_BLOCK);
		// Core modules: 0 = shared memory, 1 = adapter, 2 = rontolisp.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Alias the resource/enum types we need (component types 14-16) and the WASI
		// functions (component funcs 0-10), all in one alias section.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// types 14 cli error-code, 15 fs error-code, 16 descriptor
				ComponentWriter.aliasInstanceType(INST_CLI_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "descriptor"),
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
				// func 10 stderr write-via-stream (appended last, for warn on fd 2)
				ComponentWriter.aliasInstanceFunc(INST_STDERR, "write-via-stream"))));
		// Define the async value/function types (component types 17-23). stream<u8> is
		// structural; the futures differ by their error-code (cli vs filesystem).
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 17
						ComponentWriter.definedResultErr(T_CLI_ERRCODE), // 18
						ComponentWriter.definedFuture(T_CLI_RESULT), // 19
						ComponentWriter.definedResultErr(T_FS_ERRCODE), // 20
						ComponentWriter.definedFuture(T_FS_RESULT), // 21
						ComponentWriter.definedResultVoid(), // 22 result<_,_> (run
																// result)
						ComponentWriter.asyncFuncTypeResultType(T_RUN_RESULT)))); // 23
		// Lower the WASI functions (component funcs 0-9) to core funcs 1-10 and drop the
		// descriptor resource (core func 11); canonical options mirror wasm-tools'
		// choices.
		// Then the async built-ins: stream (core funcs 12-16), futures (core funcs
		// 17-20), and finally stderr write-via-stream (component func 10 -> core func
		// 21).
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
				ComponentWriter.canonStreamRead(T_STREAM, 0), // 13
				ComponentWriter.canonStreamWrite(T_STREAM, 0), // 14
				ComponentWriter.canonStreamDropReadable(T_STREAM), // 15
				ComponentWriter.canonStreamDropWritable(T_STREAM), // 16
				ComponentWriter.canonFutureRead(T_CLI_FUTURE, 0), // 17 future-read-cli
				ComponentWriter.canonFutureDropReadable(T_CLI_FUTURE), // 18
																		// future-drop-cli
				// the filesystem error-code is a variant with a string-bearing case, so
				// its
				// future payload needs realloc (cabi_realloc = core func 0)
				ComponentWriter.canonFutureRead(T_FS_FUTURE, 0, 0), // 19 future-read-fs
				ComponentWriter.canonFutureDropReadable(T_FS_FUTURE), // 20
																		// future-drop-fs
				ComponentWriter.canonLower(10)))); // 21 stderr write-via-stream
		// Group the 21 lowered/built-in core funcs (1-21) for the adapter's "w" import
		// (core instance 1). Names match adapter.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
					List.of("stdout-write", "stdin-read", "get-environment", "sys-now", "mono-now", "file-read",
							"file-append", "open-at", "get-directories", "get-random-u64", "drop-desc", "stream-new",
							"stream-read", "stream-write", "stream-drop-r", "stream-drop-w", "future-read-cli",
							"future-drop-cli", "future-read-fs", "future-drop-fs", "stderr-write"),
					List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// User WIT-interface imports (rontolisp:wit-import): instance types from
		// component type 24, import instances from 10, function aliases from component
		// func 11, lowered core funcs from 22. Emits nothing when there are none, so
		// every fixed index below shifts by zero.
		appendUserImports(c, imports, T_RUN_FUNC + 1, 10, 11, 22);
		// Instantiate rontolisp (core instance 3 + one per user interface): mem =
		// instance 0, wasi_snapshot_preview1 = adapter instance 2, plus each user
		// interface's canon-lowered core instance (3..) under its canonical id.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(
				List.of(rontolispInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2), imports, 3))));
		final int rontolisp = 3 + userIfaces;
		// Alias rontolisp's run (core func 22 = cabi_realloc + 21 lowered/built-in
		// funcs, + the user-import lowers).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(rontolisp, "run"))));
		// Lift run into component func 11 (+ the user-import aliases) with the async
		// function type 23 (stackful async: no callback option). Component func 11
		// follows the 11 aliased WASI funcs (0-10).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(22 + userCoreFuncs, T_RUN_FUNC))));
		// Component instance 10 (after import instances 0-9 + the user imports)
		// exporting run, exported as the wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 11 + userFuncs))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 10 + userIfaces))));
		// Scalar wasm-export functions: next free indices after the run wiring are core
		// func 23 (run = 22), component type 24 (T_RUN_FUNC = 23) and component func 12
		// (the run lift = 11), each shifted by the user-import counts.
		appendFuncExports(c, funcExports, 23 + userCoreFuncs, T_RUN_FUNC + 1 + userTypes, 12 + userFuncs, rontolisp);
		return c.toByteArray();
	}

	// HTTP-variant component import-instance indices (from import-block-http-client.bin).
	// The
	// base
	// WASI 0.3 instances 0-8 keep the same order as buildBase; the WASI 0.2 HTTP
	// instances
	// are appended at 9-13, and wasi:cli/stderr (for warn) last at 14.
	private static final int H_INST_CLI_TYPES = 0;

	private static final int H_INST_STDOUT = 1;

	private static final int H_INST_STDIN = 2;

	private static final int H_INST_ENVIRON = 3;

	private static final int H_INST_SYS_CLOCK = 4;

	private static final int H_INST_MONO_CLOCK = 5;

	private static final int H_INST_FS_TYPES = 6;

	private static final int H_INST_FS_PREOPENS = 7;

	private static final int H_INST_RANDOM = 8;

	private static final int H_INST_IO_POLL = 9;

	// instance 10 = wasi:io/error (no functions aliased)
	private static final int H_INST_IO_STREAMS = 11;

	private static final int H_INST_HTTP_TYPES = 12;

	private static final int H_INST_HTTP_HANDLER = 13;

	private static final int H_INST_STDERR = 14;

	// First free component type index after import-block-http-client.bin. The stderr
	// interface
	// appended last to uni-http-client.wit adds two component types (an aliased cli
	// error-code +
	// the stderr instance type), so the next free index moves from 25 to 27.
	// Aliased resource/enum types (component types 27-38).
	private static final int H_T_CLI_ERRCODE = 27;

	private static final int H_T_FS_ERRCODE = 28;

	private static final int H_T_DESCRIPTOR = 29;

	private static final int H_T_OUTPUT_STREAM = 30;

	private static final int H_T_INPUT_STREAM = 31;

	private static final int H_T_POLLABLE = 32;

	private static final int H_T_FIELDS = 33;

	private static final int H_T_OUT_REQ = 34;

	private static final int H_T_OUT_BODY = 35;

	private static final int H_T_FUT_RESP = 36;

	private static final int H_T_IN_RESP = 37;

	private static final int H_T_IN_BODY = 38;

	// Defined async value/function types (component types 39-45).
	private static final int H_T_STREAM = 39;

	private static final int H_T_CLI_RESULT = 40;

	private static final int H_T_CLI_FUTURE = 41;

	private static final int H_T_FS_RESULT = 42;

	private static final int H_T_FS_FUTURE = 43;

	private static final int H_T_RUN_RESULT = 44;

	private static final int H_T_RUN_FUNC = 45;

	/**
	 * Assemble the HTTP-variant component for a {@code rontolisp:fetch} program. It is
	 * the base WASI 0.3 component plus the WASI 0.2 HTTP / io machinery: the base I/O
	 * still flows through the 0.3 {@code stream}/{@code future} built-ins, while fetch
	 * drives an outgoing request over {@code wasi:http@0.2} + {@code wasi:io@0.2}
	 * (synchronous {@code pollable.block}) in {@code adapter-http-client.wat}. async
	 * {@code wasi:http@0.3} does not exist upstream yet; see
	 * {@code .todo/02-upgrade-fetch-to-wasi-http-0.3.md}.
	 *
	 * <p>
	 * All wiring constants (instance indices, the next-free type index 27, the 32 lowered
	 * functions and their canonical options) were derived from {@code wasm-tools dump} of
	 * a reference generated by {@code regen.sh} from {@code uni-http-client.wit} +
	 * {@code core-http-client.wat}.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @return the WASI 0.3 (+ 0.2 http) component binary
	 */
	private static byte[] buildHttp(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports,
			List<WasmComponentImportCompiler.Import> imports) {
		final int userIfaces = imports.size();
		// The type index space grows by the instance types AND the resources projected
		// out of them for a `use`d-across-interfaces reference; the instance index space
		// only by the interfaces. They are equal until an interface uses another's
		// resource, which is why every pre-existing component is byte-identical.
		final int userTypes = userImportTypes(imports);
		final int userFuncs = userImportFuncs(imports);
		// The other function count: a resource drop is a CORE function with no component
		// function behind it (canon resource.drop), so an index into the core function
		// space must skip the drops too. Confusing the two yields a component that
		// VALIDATES while lifting the wrong function.
		final int userCoreFuncs = userImportCoreFuncs(imports);
		final ComponentWriter c = new ComponentWriter();
		// Base WASI 0.3 + WASI 0.2 http import instances 0-13, component types 0-24.
		c.writeRaw(IMPORT_BLOCK_HTTP_CLIENT);
		// Core modules: 0 = shared 16-page memory, 1 = http adapter, 2 = rontolisp.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE_HTTP_CLIENT);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_MODULE_HTTP_CLIENT);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Alias the resource/enum types to drop or reference (component types 27-38).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// 27 cli error-code, 28 fs error-code, 29 descriptor
				ComponentWriter.aliasInstanceType(H_INST_CLI_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(H_INST_FS_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(H_INST_FS_TYPES, "descriptor"),
				// 30 output-stream, 31 input-stream, 32 pollable
				ComponentWriter.aliasInstanceType(H_INST_IO_STREAMS, "output-stream"),
				ComponentWriter.aliasInstanceType(H_INST_IO_STREAMS, "input-stream"),
				ComponentWriter.aliasInstanceType(H_INST_IO_POLL, "pollable"),
				// 33-38 http resources
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "fields"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "outgoing-request"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "outgoing-body"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "future-incoming-response"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "incoming-response"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "incoming-body"))));
		// Alias the WASI functions to lower (component funcs 0-31): 0-9 base WASI 0.3,
		// 10-30 the WASI 0.2 http / io machinery, 31 stderr write-via-stream (for warn).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// base 0-9 (same set/order as buildBase)
				ComponentWriter.aliasInstanceFunc(H_INST_STDOUT, "write-via-stream"),
				ComponentWriter.aliasInstanceFunc(H_INST_STDIN, "read-via-stream"),
				ComponentWriter.aliasInstanceFunc(H_INST_ENVIRON, "get-environment"),
				ComponentWriter.aliasInstanceFunc(H_INST_SYS_CLOCK, "now"),
				ComponentWriter.aliasInstanceFunc(H_INST_MONO_CLOCK, "now"),
				ComponentWriter.aliasInstanceFunc(H_INST_FS_TYPES, "[method]descriptor.read-via-stream"),
				ComponentWriter.aliasInstanceFunc(H_INST_FS_TYPES, "[method]descriptor.append-via-stream"),
				ComponentWriter.aliasInstanceFunc(H_INST_FS_TYPES, "[method]descriptor.open-at"),
				ComponentWriter.aliasInstanceFunc(H_INST_FS_PREOPENS, "get-directories"),
				ComponentWriter.aliasInstanceFunc(H_INST_RANDOM, "get-random-u64"),
				// http 10-30
				ComponentWriter.aliasInstanceFunc(H_INST_IO_POLL, "[method]pollable.block"),
				ComponentWriter.aliasInstanceFunc(H_INST_IO_STREAMS, "[method]output-stream.blocking-write-and-flush"),
				ComponentWriter.aliasInstanceFunc(H_INST_IO_STREAMS, "[method]input-stream.blocking-read"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[constructor]fields"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]fields.append"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]fields.entries"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[constructor]outgoing-request"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]outgoing-request.set-method"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]outgoing-request.set-scheme"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]outgoing-request.set-authority"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]outgoing-request.set-path-with-query"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]outgoing-request.body"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]outgoing-body.write"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[static]outgoing-body.finish"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]future-incoming-response.subscribe"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]future-incoming-response.get"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]incoming-response.status"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]incoming-response.headers"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]incoming-response.consume"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_TYPES, "[method]incoming-body.stream"),
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_HANDLER, "handle"),
				// func 31 stderr write-via-stream (appended last, for warn on fd 2)
				ComponentWriter.aliasInstanceFunc(H_INST_STDERR, "write-via-stream"))));
		// Define the async value/function types (component types 39-45).
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 39
						ComponentWriter.definedResultErr(H_T_CLI_ERRCODE), // 40
						ComponentWriter.definedFuture(H_T_CLI_RESULT), // 41
						ComponentWriter.definedResultErr(H_T_FS_ERRCODE), // 42
						ComponentWriter.definedFuture(H_T_FS_RESULT), // 43
						ComponentWriter.definedResultVoid(), // 44
						ComponentWriter.asyncFuncTypeResultType(H_T_RUN_RESULT)))); // 45
		// Lower the 31 WASI funcs (core funcs 1-31), drop the 10 resources (32-41), then
		// the
		// async built-ins: stream (42-46), futures (47-50). Canonical options match what
		// wasm-tools chose for each function (memory 0, realloc = core func 0). core func
		// 0
		// = cabi_realloc.
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(
				// base lowered 1-10 (same options as buildBase)
				ComponentWriter.canonLower(0), // 1 write-via-stream
				ComponentWriter.canonLowerMemory(1, 0), // 2 stdin read-via-stream
				ComponentWriter.canonLowerMemoryReallocUtf8(2, 0, 0), // 3 get-environment
				ComponentWriter.canonLowerMemory(3, 0), // 4 system-clock.now
				ComponentWriter.canonLower(4), // 5 monotonic.now
				ComponentWriter.canonLowerMemory(5, 0), // 6 descriptor.read-via-stream
				ComponentWriter.canonLower(6), // 7 descriptor.append-via-stream
				ComponentWriter.canonLowerMemoryReallocUtf8(7, 0, 0), // 8 open-at
				ComponentWriter.canonLowerMemoryReallocUtf8(8, 0, 0), // 9 get-directories
				ComponentWriter.canonLower(9), // 10 get-random-u64
				// http lowered 11-31
				ComponentWriter.canonLower(10), // 11 pollable.block
				ComponentWriter.canonLowerMemory(11, 0), // 12
															// output-stream.blocking-write-and-flush
				ComponentWriter.canonLower(12, 0, 0), // 13 input-stream.blocking-read
														// (mem+realloc)
				ComponentWriter.canonLower(13), // 14 fields constructor
				ComponentWriter.canonLowerMemoryUtf8(14, 0), // 15 fields.append
				ComponentWriter.canonLowerMemoryReallocUtf8(15, 0, 0), // 16
																		// fields.entries
				ComponentWriter.canonLower(16), // 17 outgoing-request constructor
				ComponentWriter.canonLowerMemoryUtf8(17, 0), // 18 set-method
				ComponentWriter.canonLowerMemoryUtf8(18, 0), // 19 set-scheme
				ComponentWriter.canonLowerMemoryUtf8(19, 0), // 20 set-authority
				ComponentWriter.canonLowerMemoryUtf8(20, 0), // 21 set-path-with-query
				ComponentWriter.canonLowerMemory(21, 0), // 22 outgoing-request.body
				ComponentWriter.canonLowerMemory(22, 0), // 23 outgoing-body.write
				ComponentWriter.canonLowerMemoryReallocUtf8(23, 0, 0), // 24
																		// outgoing-body.finish
				ComponentWriter.canonLower(24), // 25 future.subscribe
				ComponentWriter.canonLowerMemoryReallocUtf8(25, 0, 0), // 26 future.get
				ComponentWriter.canonLower(26), // 27 incoming-response.status
				ComponentWriter.canonLower(27), // 28 incoming-response.headers
				ComponentWriter.canonLowerMemory(28, 0), // 29 incoming-response.consume
				ComponentWriter.canonLowerMemory(29, 0), // 30 incoming-body.stream
				ComponentWriter.canonLowerMemoryReallocUtf8(30, 0, 0), // 31 handle
				// resource drops 32-41
				ComponentWriter.canonResourceDrop(H_T_DESCRIPTOR), // 32
				ComponentWriter.canonResourceDrop(H_T_OUTPUT_STREAM), // 33
				ComponentWriter.canonResourceDrop(H_T_INPUT_STREAM), // 34
				ComponentWriter.canonResourceDrop(H_T_POLLABLE), // 35
				ComponentWriter.canonResourceDrop(H_T_FIELDS), // 36
				ComponentWriter.canonResourceDrop(H_T_OUT_REQ), // 37
				ComponentWriter.canonResourceDrop(H_T_OUT_BODY), // 38
				ComponentWriter.canonResourceDrop(H_T_FUT_RESP), // 39
				ComponentWriter.canonResourceDrop(H_T_IN_RESP), // 40
				ComponentWriter.canonResourceDrop(H_T_IN_BODY), // 41
				// stream built-ins 42-46
				ComponentWriter.canonStreamNew(H_T_STREAM), // 42
				ComponentWriter.canonStreamRead(H_T_STREAM, 0), // 43
				ComponentWriter.canonStreamWrite(H_T_STREAM, 0), // 44
				ComponentWriter.canonStreamDropReadable(H_T_STREAM), // 45
				ComponentWriter.canonStreamDropWritable(H_T_STREAM), // 46
				// future built-ins 47-50
				ComponentWriter.canonFutureRead(H_T_CLI_FUTURE, 0), // 47 future-read-cli
				ComponentWriter.canonFutureDropReadable(H_T_CLI_FUTURE), // 48
																			// future-drop-cli
				ComponentWriter.canonFutureRead(H_T_FS_FUTURE, 0, 0), // 49 future-read-fs
																		// (mem+realloc)
				ComponentWriter.canonFutureDropReadable(H_T_FS_FUTURE), // 50
																		// future-drop-fs
				ComponentWriter.canonLower(31)))); // 51 stderr write-via-stream
		// Group the 51 lowered/built-in core funcs (1-51) for the adapter's "w" import
		// (core
		// instance 1). Names match adapter-http-client.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
					List.of("stdout-write", "stdin-read", "get-environment", "sys-now", "mono-now", "file-read",
							"file-append", "open-at", "get-directories", "get-random-u64", "poll-block", "io-write",
							"io-read", "fields-new", "fields-append", "fields-entries", "req-new", "set-method",
							"set-scheme", "set-authority", "set-path", "req-body", "body-write", "body-finish",
							"future-subscribe", "future-get", "resp-status", "resp-headers", "resp-consume",
							"body-stream", "handle", "drop-desc", "drop-out", "drop-in", "drop-pollable", "drop-fields",
							"drop-req", "drop-outgoing-body", "drop-future", "drop-resp", "drop-body", "stream-new",
							"stream-read", "stream-write", "stream-drop-r", "stream-drop-w", "future-read-cli",
							"future-drop-cli", "future-read-fs", "future-drop-fs", "stderr-write"),
					List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
							26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48,
							49, 50, 51)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// User WIT-interface imports (rontolisp:wit-import): instance types from
		// component type 46, import instances from 15, function aliases from component
		// func 32, lowered core funcs from 52. Emits nothing when there are none.
		appendUserImports(c, imports, H_T_RUN_FUNC + 1, 15, 32, 52);
		// Instantiate rontolisp (core instance 3 + one per user interface): mem =
		// instance 0, and wasi_snapshot_preview1, sock AND http all satisfied by the
		// adapter instance 2 (which exports the eight preview1 functions, the four
		// errno-returning tcp-* stubs for the reserved sock slots, and fetch-start /
		// fetch-await), plus each user interface's canon-lowered core instance.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(rontolispInstantiate(2,
				List.of("mem", "wasi_snapshot_preview1", "sock", "http"), List.of(0, 2, 2, 2), imports, 3))));
		final int rontolisp = 3 + userIfaces;
		// Alias rontolisp's run (core func 52 = cabi_realloc + 51 lowered/built-in
		// funcs, + the user-import lowers).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(rontolisp, "run"))));
		// Lift run into component func 32 (+ the user-import aliases) with the async
		// function type 45. Component func 32 follows the 32 aliased WASI funcs (0-31).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(52 + userCoreFuncs, H_T_RUN_FUNC))));
		// Component instance 15 (after import instances 0-14 + the user imports)
		// exporting run, exported as the wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 32 + userFuncs))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 15 + userIfaces))));
		// Scalar wasm-export functions: next free indices after the run wiring are core
		// func 53 (run = 52), component type 46 (H_T_RUN_FUNC = 45) and component func 33
		// (the run lift = 32), each shifted by the user-import counts.
		appendFuncExports(c, funcExports, 53 + userCoreFuncs, H_T_RUN_FUNC + 1 + userTypes, 33 + userFuncs, rontolisp);
		return c.toByteArray();
	}

	// Sockets-variant component import-instance indices (from import-block-sockets.bin).
	// The base WASI 0.3 instances 0-8 keep the same order as buildBase;
	// wasi:sockets/types is appended at 9 and wasi:cli/stderr (for warn) last at 10.
	private static final int S_INST_SOCKETS = 9;

	private static final int S_INST_STDERR = 10;

	// First free component type index after import-block-sockets.bin (types 0-14 used;
	// the
	// appended stderr interface adds two: an aliased cli error-code + the stderr instance
	// type). Aliased resource/enum types (component types 15-19).
	private static final int S_T_CLI_ERRCODE = 15;

	private static final int S_T_FS_ERRCODE = 16;

	private static final int S_T_DESCRIPTOR = 17;

	private static final int S_T_SOCK_ERRCODE = 18;

	private static final int S_T_TCP_SOCKET = 19;

	// Defined async value/function types (component types 20-30).
	private static final int S_T_STREAM = 20;

	private static final int S_T_CLI_RESULT = 21;

	private static final int S_T_CLI_FUTURE = 22;

	private static final int S_T_FS_RESULT = 23;

	private static final int S_T_FS_FUTURE = 24;

	private static final int S_T_RUN_RESULT = 25;

	private static final int S_T_RUN_FUNC = 26;

	private static final int S_T_OWN_TCP = 27;

	private static final int S_T_ACCEPT_STREAM = 28;

	private static final int S_T_SOCK_RESULT = 29;

	private static final int S_T_SOCK_FUTURE = 30;

	/**
	 * Assemble the sockets-variant component for a {@code rontolisp:tcp-*} program. It is
	 * the base WASI 0.3 component plus {@code wasi:sockets/types@0.3.0} (import instance
	 * 9): the tcp-socket send/receive plumbing flows through the same built-in
	 * {@code stream<u8>} machinery as the base I/O, plus a {@code stream<own tcp-socket>}
	 * accept stream (its own element-typed {@code stream.read}/{@code drop-readable}
	 * built-ins) and a sockets-error-code {@code future} drop built-in. Run with
	 * {@code -S tcp=y -S inherit-network=y} on top of the async flags.
	 *
	 * <p>
	 * All wiring constants (instance indices 9/10, the next-free type index 15, the 18
	 * lowered functions and their canonical options) were derived from {@code wasm-tools
	 * dump} of a reference generated by {@code regen.sh} from {@code uni-sockets.wit} +
	 * {@code core-sockets.wat}.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @return the WASI 0.3 component binary
	 */
	private static byte[] buildSock(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports,
			List<WasmComponentImportCompiler.Import> imports) {
		final int userIfaces = imports.size();
		// The type index space grows by the instance types AND the resources projected
		// out of them for a `use`d-across-interfaces reference; the instance index space
		// only by the interfaces. They are equal until an interface uses another's
		// resource, which is why every pre-existing component is byte-identical.
		final int userTypes = userImportTypes(imports);
		final int userFuncs = userImportFuncs(imports);
		// The other function count: a resource drop is a CORE function with no component
		// function behind it (canon resource.drop), so an index into the core function
		// space must skip the drops too. Confusing the two yields a component that
		// VALIDATES while lifting the wrong function.
		final int userCoreFuncs = userImportCoreFuncs(imports);
		final ComponentWriter c = new ComponentWriter();
		// Base WASI 0.3 + wasi:sockets import instances 0-9, component types 0-12.
		c.writeRaw(IMPORT_BLOCK_SOCKETS);
		// Core modules: 0 = shared memory (base 6-page module), 1 = sockets adapter,
		// 2 = rontolisp.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_MODULE_SOCKETS);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Alias the resource/enum types (component types 15-19) and the WASI functions
		// (component funcs 0-17), all in one alias section.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// types 15 cli error-code, 16 fs error-code, 17 descriptor,
				// 18 sockets error-code, 19 tcp-socket
				ComponentWriter.aliasInstanceType(INST_CLI_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(INST_FS_TYPES, "descriptor"),
				ComponentWriter.aliasInstanceType(S_INST_SOCKETS, "error-code"),
				ComponentWriter.aliasInstanceType(S_INST_SOCKETS, "tcp-socket"),
				// funcs 0-9: the base WASI functions (same set/order as buildBase)
				ComponentWriter.aliasInstanceFunc(INST_STDOUT, "write-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_STDIN, "read-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_ENVIRON, "get-environment"),
				ComponentWriter.aliasInstanceFunc(INST_SYS_CLOCK, "now"),
				ComponentWriter.aliasInstanceFunc(INST_MONO_CLOCK, "now"),
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.read-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.append-via-stream"),
				ComponentWriter.aliasInstanceFunc(INST_FS_TYPES, "[method]descriptor.open-at"),
				ComponentWriter.aliasInstanceFunc(INST_FS_PREOPENS, "get-directories"),
				ComponentWriter.aliasInstanceFunc(INST_RANDOM, "get-random-u64"),
				// funcs 10-16: the tcp-socket functions
				ComponentWriter.aliasInstanceFunc(S_INST_SOCKETS, "[static]tcp-socket.create"),
				ComponentWriter.aliasInstanceFunc(S_INST_SOCKETS, "[method]tcp-socket.bind"),
				ComponentWriter.aliasInstanceFunc(S_INST_SOCKETS, "[method]tcp-socket.connect"),
				ComponentWriter.aliasInstanceFunc(S_INST_SOCKETS, "[method]tcp-socket.listen"),
				ComponentWriter.aliasInstanceFunc(S_INST_SOCKETS, "[method]tcp-socket.send"),
				ComponentWriter.aliasInstanceFunc(S_INST_SOCKETS, "[method]tcp-socket.receive"),
				ComponentWriter.aliasInstanceFunc(S_INST_SOCKETS, "[method]tcp-socket.get-local-address"),
				// func 17: stderr write-via-stream (appended last, for warn on fd 2)
				ComponentWriter.aliasInstanceFunc(S_INST_STDERR, "write-via-stream"))));
		// Define the async value/function types (component types 20-30).
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 20
						ComponentWriter.definedResultErr(S_T_CLI_ERRCODE), // 21
						ComponentWriter.definedFuture(S_T_CLI_RESULT), // 22
						ComponentWriter.definedResultErr(S_T_FS_ERRCODE), // 23
						ComponentWriter.definedFuture(S_T_FS_RESULT), // 24
						ComponentWriter.definedResultVoid(), // 25
						ComponentWriter.asyncFuncTypeResultType(S_T_RUN_RESULT), // 26
						ComponentWriter.definedOwn(S_T_TCP_SOCKET), // 27
						ComponentWriter.definedStreamOfType(S_T_OWN_TCP), // 28 accept
																			// stream
						ComponentWriter.definedResultErr(S_T_SOCK_ERRCODE), // 29
						ComponentWriter.definedFuture(S_T_SOCK_RESULT)))); // 30
		// Lower the base WASI funcs (core funcs 1-10) + descriptor drop (11) + the shared
		// stream/future built-ins (12-20) exactly as buildBase, then the sockets funcs
		// (21-27), the tcp-socket resource drop (28), the sockets built-ins (29-31) and
		// finally stderr write-via-stream (component func 17 -> core func 32).
		// Canonical options mirror wasm-tools' choices for each function.
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLower(0), // 1
				ComponentWriter.canonLowerMemory(1, 0), // 2 read-via-stream (stdin)
				ComponentWriter.canonLowerMemoryReallocUtf8(2, 0, 0), // 3 get-environment
				ComponentWriter.canonLowerMemory(3, 0), // 4 system-clock.now
				ComponentWriter.canonLower(4), // 5 monotonic.now
				ComponentWriter.canonLowerMemory(5, 0), // 6 descriptor.read-via-stream
				ComponentWriter.canonLower(6), // 7 descriptor.append-via-stream
				ComponentWriter.canonLowerMemoryReallocUtf8(7, 0, 0), // 8 open-at
				ComponentWriter.canonLowerMemoryReallocUtf8(8, 0, 0), // 9 get-directories
				ComponentWriter.canonLower(9), // 10 get-random-u64
				ComponentWriter.canonResourceDrop(S_T_DESCRIPTOR), // 11 drop descriptor
				ComponentWriter.canonStreamNew(S_T_STREAM), // 12
				ComponentWriter.canonStreamRead(S_T_STREAM, 0), // 13
				ComponentWriter.canonStreamWrite(S_T_STREAM, 0), // 14
				ComponentWriter.canonStreamDropReadable(S_T_STREAM), // 15
				ComponentWriter.canonStreamDropWritable(S_T_STREAM), // 16
				ComponentWriter.canonFutureRead(S_T_CLI_FUTURE, 0), // 17 future-read-cli
				ComponentWriter.canonFutureDropReadable(S_T_CLI_FUTURE), // 18
				ComponentWriter.canonFutureRead(S_T_FS_FUTURE, 0, 0), // 19 future-read-fs
				ComponentWriter.canonFutureDropReadable(S_T_FS_FUTURE), // 20
				// sockets lowered 21-27: the ip-socket-address / error-code carry
				// strings,
				// so everything except send (plain handles) and receive (memory only)
				// lowers with memory + realloc + utf8, matching the reference dump.
				ComponentWriter.canonLowerMemoryReallocUtf8(10, 0, 0), // 21 tcp-create
				ComponentWriter.canonLowerMemoryReallocUtf8(11, 0, 0), // 22 tcp-bind
				ComponentWriter.canonLowerMemoryReallocUtf8(12, 0, 0), // 23
																		// tcp-connect-raw
				ComponentWriter.canonLowerMemoryReallocUtf8(13, 0, 0), // 24
																		// tcp-listen-raw
				ComponentWriter.canonLower(14), // 25 tcp-send
				ComponentWriter.canonLowerMemory(15, 0), // 26 tcp-receive
				ComponentWriter.canonLowerMemoryReallocUtf8(16, 0, 0), // 27
																		// tcp-local-addr
				ComponentWriter.canonResourceDrop(S_T_TCP_SOCKET), // 28 drop-tcp
				ComponentWriter.canonStreamRead(S_T_ACCEPT_STREAM, 0), // 29 accept-read
				ComponentWriter.canonStreamDropReadable(S_T_ACCEPT_STREAM), // 30
				ComponentWriter.canonFutureDropReadable(S_T_SOCK_FUTURE), // 31
				ComponentWriter.canonLower(17)))); // 32 stderr write-via-stream
		// Group the 32 lowered/built-in core funcs (1-32) for the adapter's "w" import
		// (core instance 1). Names match adapter-sockets.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
					List.of("stdout-write", "stdin-read", "get-environment", "sys-now", "mono-now", "file-read",
							"file-append", "open-at", "get-directories", "get-random-u64", "drop-desc", "stream-new",
							"stream-read", "stream-write", "stream-drop-r", "stream-drop-w", "future-read-cli",
							"future-drop-cli", "future-read-fs", "future-drop-fs", "tcp-create", "tcp-bind",
							"tcp-connect-raw", "tcp-listen-raw", "tcp-send", "tcp-receive", "tcp-local-addr",
							"drop-tcp", "accept-read", "accept-drop-r", "future-drop-sock", "stderr-write"),
					List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
							26, 27, 28, 29, 30, 31, 32)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// User WIT-interface imports (rontolisp:wit-import): instance types from
		// component type 31, import instances from 11, function aliases from component
		// func 18, lowered core funcs from 33. Emits nothing when there are none.
		appendUserImports(c, imports, S_T_SOCK_FUTURE + 1, 11, 18, 33);
		// Instantiate rontolisp (core instance 3 + one per user interface): mem =
		// instance 0, and both wasi_snapshot_preview1 AND sock satisfied by the adapter
		// instance 2 (which exports the eight preview1 functions plus tcp-connect /
		// tcp-listen / tcp-accept / tcp-local-port), plus each user interface's
		// canon-lowered core instance.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(rontolispInstantiate(2,
				List.of("mem", "wasi_snapshot_preview1", "sock"), List.of(0, 2, 2), imports, 3))));
		final int rontolisp = 3 + userIfaces;
		// Alias rontolisp's run (core func 33 = cabi_realloc + 32 lowered/built-in
		// funcs, + the user-import lowers).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(rontolisp, "run"))));
		// Lift run into component func 18 (+ the user-import aliases) with the async
		// function type 26 (stackful async: no callback option). Component func 18
		// follows the 18 aliased WASI funcs (0-17).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(33 + userCoreFuncs, S_T_RUN_FUNC))));
		// Component instance 11 (after import instances 0-10 + the user imports)
		// exporting run, exported as the wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 18 + userFuncs))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 11 + userIfaces))));
		// Scalar wasm-export functions: next free indices after the run wiring are core
		// func 34 (run = 33), component type 31 (S_T_SOCK_FUTURE = 30) and component func
		// 19 (the run lift = 18), each shifted by the user-import counts.
		appendFuncExports(c, funcExports, 34 + userCoreFuncs, S_T_SOCK_FUTURE + 1 + userTypes, 19 + userFuncs,
				rontolisp);
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

}
