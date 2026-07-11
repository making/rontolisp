package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import am.ik.wasm.ComponentWriter;
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
	 * Source: {@code src/wasm-component/uni-http.wit} + {@code core-http.wat}.
	 */
	private static final byte[] IMPORT_BLOCK_HTTP = resource("import-block-http.bin");

	/**
	 * The shared memory module for the HTTP variant (16 pages, for the fetch
	 * response-header / body scratch). Source: {@code src/wasm-component/mem-http.wat}.
	 */
	private static final byte[] MEM_MODULE_HTTP = resource("mem-http.wasm");

	/**
	 * The HTTP variant of the adapter: like {@link #ADAPTER_MODULE} but with extra
	 * {@code fetch-start} / {@code fetch-await} exports driving an asynchronous outgoing
	 * request (the {@code rontolisp:fetch} promise API) over {@code wasi:http@0.2} +
	 * {@code wasi:io@0.2}. Source: {@code src/wasm-component/adapter-http.wat}.
	 */
	private static final byte[] ADAPTER_MODULE_HTTP = resource("adapter-http.wasm");

	/**
	 * The sockets variant of the import block: the base WASI 0.3 interfaces PLUS
	 * {@code wasi:sockets/types@0.3.0} (instance 9) for the {@code rontolisp:tcp-*}
	 * built-ins, then {@code wasi:cli/stderr@0.3.0} appended last (instance 10, for
	 * {@code warn} on fd&nbsp;2). Unlike fetch, sockets exist natively in WASI 0.3 so the
	 * variant stays pure 0.3. It declares component import instances 0-10 and component
	 * types 0-14, so the next free component type index is 15. Source:
	 * {@code src/wasm-component/uni-sock.wit} + {@code core-sock.wat}.
	 */
	private static final byte[] IMPORT_BLOCK_SOCK = resource("import-block-sock.bin");

	/**
	 * The sockets variant of the adapter: like {@link #ADAPTER_MODULE} but with extra
	 * {@code tcp-connect} / {@code tcp-listen} / {@code tcp-accept} /
	 * {@code tcp-local-port} exports and fd&nbsp;&gt;=&nbsp;200 socket branches in
	 * {@code fd_write}/{@code fd_read}/{@code fd_close}, over {@code wasi:sockets@0.3.0}.
	 * It shares {@link #MEM_MODULE} (the socket table and scratch fit in page 5). Source:
	 * {@code src/wasm-component/adapter-sock.wat}.
	 */
	private static final byte[] ADAPTER_MODULE_SOCK = resource("adapter-sock.wasm");

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
	 * @param paramValTypes the {@code ComponentWriter.VT_*} code of each parameter
	 * @param resultValType the {@code ComponentWriter.VT_*} result code, or {@code null}
	 * for no result
	 * @param async whether to lift against an async function type ({@code :async t})
	 */
	public record FuncExport(String name, List<Integer> paramValTypes, @Nullable Integer resultValType, boolean async) {
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
			int nextType, int nextComponentFunc) {
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
			aliases.add(ComponentWriter.aliasCoreFunc(3, WasmExportCompiler.CABI_REALLOC));
			realloc = coreFunc++;
			for (WasmExportCompiler.Decl d : decls) {
				if (WasmExportCompiler.usesMemory(d)) {
					String kind = WasmExportCompiler.componentPostReturnKind(d);
					if (!postFuncs.containsKey(kind)) {
						aliases.add(ComponentWriter.aliasCoreFunc(3, WasmExportCompiler.cabiPostExportName(kind)));
						postFuncs.put(kind, coreFunc++);
					}
				}
			}
		}
		for (int i = 0; i < decls.size(); i++) {
			WasmExportCompiler.Decl decl = decls.get(i);
			FuncExport e = WasmExportCompiler.componentExport(decl);
			// Alias the core wrapper export out of the rontolisp instance (3); a
			// :string/:s-expr-returning export's core export is its retptr shim.
			aliases.add(ComponentWriter.aliasCoreFunc(3, e.name()));
			int func = coreFunc++;
			// One function type per export (params p0, p1, ...): synchronous by
			// default; an :async t export (todo 92 Tier 3) gets the async counterpart
			// (tag 0x43), which turns the lift below into a stackful async export (the
			// run shape) with the same flat core signature -- the ONLY byte difference
			// an :async export introduces.
			final List<String> paramNames = new java.util.ArrayList<>();
			for (int p = 0; p < e.paramValTypes().size(); p++) {
				paramNames.add("p" + p);
			}
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
		if (usesHttp && usesSockets) {
			// The compiler rejects this combination before reaching here.
			throw new UnsupportedOperationException("fetch and tcp sockets cannot be combined in one component yet");
		}
		if (usesHttp) {
			return buildHttp(coreModule, funcExports);
		}
		return usesSockets ? buildSock(coreModule, funcExports) : buildBase(coreModule, funcExports);
	}

	/**
	 * Assemble the serve-variant component for a {@code rontolisp:http-handler} program:
	 * wrap the rontolisp core (which exports {@code %http-dispatch},
	 * {@code __ronto_alloc} and {@code run}) with the preview1 bridge
	 * ({@code adapter-serve-p1.wasm}: random / clocks / stdout-stderr for the core's
	 * {@code wasi_snapshot_preview1} imports) and the serve adapter
	 * ({@code adapter-serve.wasm}) so the component exports
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
	 * {@code adapter-serve-p1-http.wasm}, which additionally satisfies the core's
	 * {@code http} (fetch-start / fetch-await) imports so a served handler can make
	 * outgoing requests (run with {@code wasmtime serve -S http=y}).
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @param usesHttp whether the program uses {@code rontolisp:fetch}
	 * @return the WASI 0.2 (http/incoming-handler) component binary
	 */
	public static byte[] buildServe(byte[] coreModule, boolean usesHttp) {
		return usesHttp ? WasmServeComponentBuilder.buildHttp(coreModule) : WasmServeComponentBuilder.build(coreModule);
	}

	/**
	 * Assemble the base WASI 0.3 component (no {@code rontolisp:fetch}).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @return the WASI 0.3 component binary
	 */
	private static byte[] buildBase(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports) {
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
		// Instantiate rontolisp (core instance 3): mem = instance 0,
		// wasi_snapshot_preview1 = adapter instance 2.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2)))));
		// Alias rontolisp's run (core func 22 = cabi_realloc + 21 lowered/built-in
		// funcs).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Lift run (core func 22) into component func 11 with the async function type 23
		// (stackful async: no callback option). Component func 11 follows the 11 aliased
		// WASI funcs (0-10).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(22, T_RUN_FUNC))));
		// Component instance 10 (after import instances 0-9) exporting run, exported as
		// the
		// wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 11))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 10))));
		// Scalar wasm-export functions: next free indices after the run wiring are core
		// func 23 (run = 22), component type 24 (T_RUN_FUNC = 23) and component func 12
		// (the run lift = 11).
		appendFuncExports(c, funcExports, 23, T_RUN_FUNC + 1, 12);
		return c.toByteArray();
	}

	// HTTP-variant component import-instance indices (from import-block-http.bin). The
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

	// First free component type index after import-block-http.bin. The stderr interface
	// appended last to uni-http.wit adds two component types (an aliased cli error-code +
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
	 * (synchronous {@code pollable.block}) in {@code adapter-http.wat}. async
	 * {@code wasi:http@0.3} does not exist upstream yet; see
	 * {@code .todo/02-upgrade-fetch-to-wasi-http-0.3.md}.
	 *
	 * <p>
	 * All wiring constants (instance indices, the next-free type index 27, the 32 lowered
	 * functions and their canonical options) were derived from {@code wasm-tools dump} of
	 * a reference generated by {@code regen.sh} from {@code uni-http.wit} +
	 * {@code core-http.wat}.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @return the WASI 0.3 (+ 0.2 http) component binary
	 */
	private static byte[] buildHttp(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports) {
		final ComponentWriter c = new ComponentWriter();
		// Base WASI 0.3 + WASI 0.2 http import instances 0-13, component types 0-24.
		c.writeRaw(IMPORT_BLOCK_HTTP);
		// Core modules: 0 = shared 16-page memory, 1 = http adapter, 2 = rontolisp.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE_HTTP);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_MODULE_HTTP);
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
		// instance 1). Names match adapter-http.wat's imports.
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
		// Instantiate rontolisp (core instance 3): mem = instance 0, and
		// wasi_snapshot_preview1, sock AND http all satisfied by the adapter instance 2
		// (which exports the eight preview1 functions, the four errno-returning tcp-*
		// stubs for the reserved sock slots, and fetch-start / fetch-await).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(2,
						List.of("mem", "wasi_snapshot_preview1", "sock", "http"), List.of(0, 2, 2, 2)))));
		// Alias rontolisp's run (core func 52 = cabi_realloc + 51 lowered/built-in
		// funcs).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Lift run (core func 52) into component func 32 with the async function type 45.
		// Component func 32 follows the 32 aliased WASI funcs (0-31).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(52, H_T_RUN_FUNC))));
		// Component instance 15 (after import instances 0-14) exporting run, exported as
		// the
		// wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 32))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 15))));
		// Scalar wasm-export functions: next free indices after the run wiring are core
		// func 53 (run = 52), component type 46 (H_T_RUN_FUNC = 45) and component func 33
		// (the run lift = 32).
		appendFuncExports(c, funcExports, 53, H_T_RUN_FUNC + 1, 33);
		return c.toByteArray();
	}

	// Sockets-variant component import-instance indices (from import-block-sock.bin).
	// The base WASI 0.3 instances 0-8 keep the same order as buildBase;
	// wasi:sockets/types is appended at 9 and wasi:cli/stderr (for warn) last at 10.
	private static final int S_INST_SOCKETS = 9;

	private static final int S_INST_STDERR = 10;

	// First free component type index after import-block-sock.bin (types 0-14 used; the
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
	 * dump} of a reference generated by {@code regen.sh} from {@code uni-sock.wit} +
	 * {@code core-sock.wat}.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @param funcExports the {@code wasm-export} directives to lift and export
	 * @return the WASI 0.3 component binary
	 */
	private static byte[] buildSock(byte[] coreModule, List<WasmExportCompiler.Decl> funcExports) {
		final ComponentWriter c = new ComponentWriter();
		// Base WASI 0.3 + wasi:sockets import instances 0-9, component types 0-12.
		c.writeRaw(IMPORT_BLOCK_SOCK);
		// Core modules: 0 = shared memory (base 6-page module), 1 = sockets adapter,
		// 2 = rontolisp.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_MODULE_SOCK);
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
		// (core instance 1). Names match adapter-sock.wat's imports.
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
		// Instantiate rontolisp (core instance 3): mem = instance 0, and both
		// wasi_snapshot_preview1 AND sock satisfied by the adapter instance 2 (which
		// exports the eight preview1 functions plus tcp-connect / tcp-listen /
		// tcp-accept / tcp-local-port).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(ComponentWriter
			.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1", "sock"), List.of(0, 2, 2)))));
		// Alias rontolisp's run (core func 33 = cabi_realloc + 32 lowered/built-in
		// funcs).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Lift run (core func 33) into component func 18 with the async function type 26
		// (stackful async: no callback option). Component func 18 follows the 18 aliased
		// WASI funcs (0-17).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(33, S_T_RUN_FUNC))));
		// Component instance 11 (after import instances 0-10) exporting run, exported as
		// the wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 18))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 11))));
		// Scalar wasm-export functions: next free indices after the run wiring are core
		// func 34 (run = 33), component type 31 (S_T_SOCK_FUTURE = 30) and component func
		// 19 (the run lift = 18).
		appendFuncExports(c, funcExports, 34, S_T_SOCK_FUTURE + 1, 19);
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
