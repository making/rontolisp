package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import am.ik.wasm.ComponentWriter;

/**
 * Wraps a rontolisp core module (compiled in component mode) into a WASI 0.3 (Preview 3)
 * <strong>component</strong> that prints through {@code wasi:cli/stdout@0.3.0} and is
 * runnable with {@code wasmtime run -W component-model-async=y
 * -W component-model-async-stackful=y -W component-model-more-async-builtins=y -W gc=y}.
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
	 * import instances 0-8 are: {@code wasi:cli/types} (the {@code error-code} enum),
	 * {@code wasi:cli/stdout}, {@code wasi:cli/stdin}, {@code wasi:cli/environment},
	 * {@code wasi:clocks/system-clock}, {@code wasi:clocks/monotonic-clock},
	 * {@code wasi:filesystem/types}, {@code wasi:filesystem/preopens} and
	 * {@code wasi:random/random}. The block defines component types 0-11, so the next
	 * free component type index is 12.
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
	 * {@code wasi:http/outgoing-handler}). fetch stays on {@code wasi:http@0.2} because
	 * an async {@code wasi:http@0.3} does not exist upstream yet (see
	 * {@code .todo/02-upgrade-fetch-to-wasi-http-0.3.md}). It declares component import
	 * instances 0-13 and component types 0-24, so the next free component type index is
	 * 25. Source: {@code src/wasm-component/uni-http.wit} + {@code core-http.wat}.
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
	 * {@code wasi:sockets/types@0.3.0} appended last (instance 9) for the
	 * {@code rontolisp:tcp-*} built-ins. Unlike fetch, sockets exist natively in WASI 0.3
	 * so the variant stays pure 0.3. It declares component import instances 0-9 and
	 * component types 0-12, so the next free component type index is 13. Source:
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

	// First free component type index after the import block.
	private static final int T_CLI_ERRCODE = 12;

	private static final int T_FS_ERRCODE = 13;

	private static final int T_DESCRIPTOR = 14;

	private static final int T_STREAM = 15;

	private static final int T_CLI_RESULT = 16;

	private static final int T_CLI_FUTURE = 17;

	private static final int T_FS_RESULT = 18;

	private static final int T_FS_FUTURE = 19;

	private static final int T_RUN_RESULT = 20;

	private static final int T_RUN_FUNC = 21;

	private WasmComponentBuilder() {
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
		if (usesHttp && usesSockets) {
			// The compiler rejects this combination before reaching here.
			throw new UnsupportedOperationException("fetch and tcp sockets cannot be combined in one component yet");
		}
		if (usesHttp) {
			return buildHttp(coreModule);
		}
		return usesSockets ? buildSock(coreModule) : buildBase(coreModule);
	}

	/**
	 * Assemble the base WASI 0.3 component (no {@code rontolisp:fetch}).
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @return the WASI 0.3 component binary
	 */
	private static byte[] buildBase(byte[] coreModule) {
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
		// Alias the resource/enum types we need (component types 12-14) and the WASI
		// functions (component funcs 0-9), all in one alias section.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// types 12 cli error-code, 13 fs error-code, 14 descriptor
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
				ComponentWriter.aliasInstanceFunc(INST_RANDOM, "get-random-u64"))));
		// Define the async value/function types (component types 15-21). stream<u8> is
		// structural; the futures differ by their error-code (cli vs filesystem).
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 15
						ComponentWriter.definedResultErr(T_CLI_ERRCODE), // 16
						ComponentWriter.definedFuture(T_CLI_RESULT), // 17
						ComponentWriter.definedResultErr(T_FS_ERRCODE), // 18
						ComponentWriter.definedFuture(T_FS_RESULT), // 19
						ComponentWriter.definedResultVoid(), // 20 result<_,_> (run
																// result)
						ComponentWriter.asyncFuncTypeResultType(T_RUN_RESULT)))); // 21
		// Lower the WASI functions (component funcs 0-9) to core funcs 1-10 and drop the
		// descriptor resource (core func 11); canonical options mirror wasm-tools'
		// choices.
		// Then the async built-ins: stream (core funcs 12-16), futures (core funcs
		// 17-20).
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
				ComponentWriter.canonFutureDropReadable(T_FS_FUTURE)))); // 20
																			// future-drop-fs
		// Group the 20 lowered/built-in core funcs (1-20) for the adapter's "w" import
		// (core instance 1). Names match adapter.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("stdout-write", "stdin-read", "get-environment", "sys-now", "mono-now", "file-read",
								"file-append", "open-at", "get-directories", "get-random-u64", "drop-desc",
								"stream-new", "stream-read", "stream-write", "stream-drop-r", "stream-drop-w",
								"future-read-cli", "future-drop-cli", "future-read-fs", "future-drop-fs"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// Instantiate rontolisp (core instance 3): mem = instance 0,
		// wasi_snapshot_preview1 = adapter instance 2.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2)))));
		// Alias rontolisp's run (core func 21 = cabi_realloc + 20 lowered/built-in
		// funcs).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Lift run (core func 21) into component func 10 with the async function type 21
		// (stackful async: no callback option). Component func 10 follows the 10 aliased
		// WASI funcs (0-9).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(21, T_RUN_FUNC))));
		// Component instance 9 (after import instances 0-8) exporting run, exported as
		// the
		// wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 10))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 9))));
		return c.toByteArray();
	}

	// HTTP-variant component import-instance indices (from import-block-http.bin). The
	// base
	// WASI 0.3 instances 0-8 keep the same order as buildBase; the WASI 0.2 HTTP
	// instances
	// are appended at 9-13.
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

	// First free component type index after import-block-http.bin (types 0-24 used).
	// Aliased resource/enum types (component types 25-36).
	private static final int H_T_CLI_ERRCODE = 25;

	private static final int H_T_FS_ERRCODE = 26;

	private static final int H_T_DESCRIPTOR = 27;

	private static final int H_T_OUTPUT_STREAM = 28;

	private static final int H_T_INPUT_STREAM = 29;

	private static final int H_T_POLLABLE = 30;

	private static final int H_T_FIELDS = 31;

	private static final int H_T_OUT_REQ = 32;

	private static final int H_T_OUT_BODY = 33;

	private static final int H_T_FUT_RESP = 34;

	private static final int H_T_IN_RESP = 35;

	private static final int H_T_IN_BODY = 36;

	// Defined async value/function types (component types 37-43).
	private static final int H_T_STREAM = 37;

	private static final int H_T_CLI_RESULT = 38;

	private static final int H_T_CLI_FUTURE = 39;

	private static final int H_T_FS_RESULT = 40;

	private static final int H_T_FS_FUTURE = 41;

	private static final int H_T_RUN_RESULT = 42;

	private static final int H_T_RUN_FUNC = 43;

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
	 * All wiring constants (instance indices, the next-free type index 25, the 31 lowered
	 * functions and their canonical options) were derived from {@code wasm-tools dump} of
	 * a reference generated by {@code regen.sh} from {@code uni-http.wit} +
	 * {@code core-http.wat}.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @return the WASI 0.3 (+ 0.2 http) component binary
	 */
	private static byte[] buildHttp(byte[] coreModule) {
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
		// Alias the resource/enum types to drop or reference (component types 25-36).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// 25 cli error-code, 26 fs error-code, 27 descriptor
				ComponentWriter.aliasInstanceType(H_INST_CLI_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(H_INST_FS_TYPES, "error-code"),
				ComponentWriter.aliasInstanceType(H_INST_FS_TYPES, "descriptor"),
				// 28 output-stream, 29 input-stream, 30 pollable
				ComponentWriter.aliasInstanceType(H_INST_IO_STREAMS, "output-stream"),
				ComponentWriter.aliasInstanceType(H_INST_IO_STREAMS, "input-stream"),
				ComponentWriter.aliasInstanceType(H_INST_IO_POLL, "pollable"),
				// 31-36 http resources
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "fields"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "outgoing-request"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "outgoing-body"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "future-incoming-response"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "incoming-response"),
				ComponentWriter.aliasInstanceType(H_INST_HTTP_TYPES, "incoming-body"))));
		// Alias the WASI functions to lower (component funcs 0-30): 0-9 base WASI 0.3,
		// 10-30 the WASI 0.2 http / io machinery.
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
				ComponentWriter.aliasInstanceFunc(H_INST_HTTP_HANDLER, "handle"))));
		// Define the async value/function types (component types 37-43).
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 37
						ComponentWriter.definedResultErr(H_T_CLI_ERRCODE), // 38
						ComponentWriter.definedFuture(H_T_CLI_RESULT), // 39
						ComponentWriter.definedResultErr(H_T_FS_ERRCODE), // 40
						ComponentWriter.definedFuture(H_T_FS_RESULT), // 41
						ComponentWriter.definedResultVoid(), // 42
						ComponentWriter.asyncFuncTypeResultType(H_T_RUN_RESULT)))); // 43
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
				ComponentWriter.canonFutureDropReadable(H_T_FS_FUTURE)))); // 50
																			// future-drop-fs
		// Group the 50 lowered/built-in core funcs (1-50) for the adapter's "w" import
		// (core
		// instance 1). Names match adapter-http.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(List.of("stdout-write", "stdin-read",
						"get-environment", "sys-now", "mono-now", "file-read", "file-append", "open-at",
						"get-directories", "get-random-u64", "poll-block", "io-write", "io-read", "fields-new",
						"fields-append", "fields-entries", "req-new", "set-method", "set-scheme", "set-authority",
						"set-path", "req-body", "body-write", "body-finish", "future-subscribe", "future-get",
						"resp-status", "resp-headers", "resp-consume", "body-stream", "handle", "drop-desc", "drop-out",
						"drop-in", "drop-pollable", "drop-fields", "drop-req", "drop-outgoing-body", "drop-future",
						"drop-resp", "drop-body", "stream-new", "stream-read", "stream-write", "stream-drop-r",
						"stream-drop-w", "future-read-cli", "future-drop-cli", "future-read-fs", "future-drop-fs"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
								25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,
								47, 48, 49, 50)))));
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
		// Alias rontolisp's run (core func 51 = cabi_realloc + 50 lowered/built-in
		// funcs).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Lift run (core func 51) into component func 31 with the async function type 43.
		// Component func 31 follows the 31 aliased WASI funcs (0-30).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(51, H_T_RUN_FUNC))));
		// Component instance 14 (after import instances 0-13) exporting run, exported as
		// the
		// wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 31))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 14))));
		return c.toByteArray();
	}

	// Sockets-variant component import-instance indices (from import-block-sock.bin).
	// The base WASI 0.3 instances 0-8 keep the same order as buildBase;
	// wasi:sockets/types is appended at 9.
	private static final int S_INST_SOCKETS = 9;

	// First free component type index after import-block-sock.bin (types 0-12 used).
	// Aliased resource/enum types (component types 13-17).
	private static final int S_T_CLI_ERRCODE = 13;

	private static final int S_T_FS_ERRCODE = 14;

	private static final int S_T_DESCRIPTOR = 15;

	private static final int S_T_SOCK_ERRCODE = 16;

	private static final int S_T_TCP_SOCKET = 17;

	// Defined async value/function types (component types 18-28).
	private static final int S_T_STREAM = 18;

	private static final int S_T_CLI_RESULT = 19;

	private static final int S_T_CLI_FUTURE = 20;

	private static final int S_T_FS_RESULT = 21;

	private static final int S_T_FS_FUTURE = 22;

	private static final int S_T_RUN_RESULT = 23;

	private static final int S_T_RUN_FUNC = 24;

	private static final int S_T_OWN_TCP = 25;

	private static final int S_T_ACCEPT_STREAM = 26;

	private static final int S_T_SOCK_RESULT = 27;

	private static final int S_T_SOCK_FUTURE = 28;

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
	 * All wiring constants (instance index 9, the next-free type index 13, the 17 lowered
	 * functions and their canonical options) were derived from {@code wasm-tools dump} of
	 * a reference generated by {@code regen.sh} from {@code uni-sock.wit} +
	 * {@code core-sock.wat}.
	 * @param coreModule the rontolisp core module compiled in component mode
	 * @return the WASI 0.3 component binary
	 */
	private static byte[] buildSock(byte[] coreModule) {
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
		// Alias the resource/enum types (component types 13-17) and the WASI functions
		// (component funcs 0-16), all in one alias section.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// types 13 cli error-code, 14 fs error-code, 15 descriptor,
				// 16 sockets error-code, 17 tcp-socket
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
				ComponentWriter.aliasInstanceFunc(S_INST_SOCKETS, "[method]tcp-socket.get-local-address"))));
		// Define the async value/function types (component types 18-28).
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedStream(ComponentWriter.VT_U8), // 18
						ComponentWriter.definedResultErr(S_T_CLI_ERRCODE), // 19
						ComponentWriter.definedFuture(S_T_CLI_RESULT), // 20
						ComponentWriter.definedResultErr(S_T_FS_ERRCODE), // 21
						ComponentWriter.definedFuture(S_T_FS_RESULT), // 22
						ComponentWriter.definedResultVoid(), // 23
						ComponentWriter.asyncFuncTypeResultType(S_T_RUN_RESULT), // 24
						ComponentWriter.definedOwn(S_T_TCP_SOCKET), // 25
						ComponentWriter.definedStreamOfType(S_T_OWN_TCP), // 26 accept
																			// stream
						ComponentWriter.definedResultErr(S_T_SOCK_ERRCODE), // 27
						ComponentWriter.definedFuture(S_T_SOCK_RESULT)))); // 28
		// Lower the base WASI funcs (core funcs 1-10) + descriptor drop (11) + the shared
		// stream/future built-ins (12-20) exactly as buildBase, then the sockets funcs
		// (21-27), the tcp-socket resource drop (28) and the sockets built-ins (29-31).
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
				ComponentWriter.canonFutureDropReadable(S_T_SOCK_FUTURE)))); // 31
		// Group the 31 lowered/built-in core funcs (1-31) for the adapter's "w" import
		// (core instance 1). Names match adapter-sock.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("stdout-write", "stdin-read", "get-environment", "sys-now", "mono-now", "file-read",
								"file-append", "open-at", "get-directories", "get-random-u64", "drop-desc",
								"stream-new", "stream-read", "stream-write", "stream-drop-r", "stream-drop-w",
								"future-read-cli", "future-drop-cli", "future-read-fs", "future-drop-fs", "tcp-create",
								"tcp-bind", "tcp-connect-raw", "tcp-listen-raw", "tcp-send", "tcp-receive",
								"tcp-local-addr", "drop-tcp", "accept-read", "accept-drop-r", "future-drop-sock"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
								25, 26, 27, 28, 29, 30, 31)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// Instantiate rontolisp (core instance 3): mem = instance 0, and both
		// wasi_snapshot_preview1 AND sock satisfied by the adapter instance 2 (which
		// exports the eight preview1 functions plus tcp-connect / tcp-listen /
		// tcp-accept / tcp-local-port).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(ComponentWriter
			.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1", "sock"), List.of(0, 2, 2)))));
		// Alias rontolisp's run (core func 32 = cabi_realloc + 31 lowered/built-in
		// funcs).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Lift run (core func 32) into component func 17 with the async function type 24
		// (stackful async: no callback option). Component func 17 follows the 17 aliased
		// WASI funcs (0-16).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(32, S_T_RUN_FUNC))));
		// Component instance 10 (after import instances 0-9) exporting run, exported as
		// the wasi:cli/run@0.3.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 17))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 10))));
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
