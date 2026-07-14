package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import am.ik.wasm.ComponentWriter;

/**
 * Assembles the serve-variant WASI component for {@code rontolisp:http-handler}: it wraps
 * a rontolisp core module (compiled in serve mode, so it exports {@code %http-dispatch} +
 * {@code __ronto_alloc} + {@code run} and imports its memory) with the shared 16-page
 * memory module, the preview1 bridge ({@code adapter-http-server-p1.wasm}, instantiated
 * before the core so its exports satisfy the core's {@code wasi_snapshot_preview1}
 * imports: random / clock / stdout-stderr over the wasi:http proxy world) and the serve
 * adapter ({@code adapter-http-server.wasm}, instantiated after the core because it
 * imports {@code %http-dispatch}), and exports {@code wasi:http/incoming-handler@0.2.0}
 * so the component runs under {@code wasmtime serve} (or any {@code wasi:http} 0.2 host
 * with wasm-GC enabled, e.g. jco or wasmCloud; not Spin, whose wasmtime cannot enable
 * wasm-GC).
 *
 * <p>
 * The wiring mirrors {@link WasmComponentBuilder}'s base/http/sock variants but exports a
 * <em>synchronous</em> {@code handle(request, response-out)} (not the async {@code run}
 * lift). The import block ({@code import-block-http-server.bin}) declares import
 * instances 0&nbsp;=&nbsp;{@code wasi:clocks/monotonic-clock} (hoisted first as a
 * dependency of http/types), 1&nbsp;=&nbsp;{@code wasi:io/error},
 * 2&nbsp;=&nbsp;{@code wasi:io/streams}, 3&nbsp;=&nbsp;{@code wasi:http/types},
 * 4&nbsp;=&nbsp;{@code wasi:random/random}, 5&nbsp;=&nbsp;{@code wasi:clocks/wall-clock},
 * 6&nbsp;=&nbsp;{@code wasi:cli/stdout}, 7&nbsp;=&nbsp;{@code wasi:cli/stderr} and
 * component types 0-12 (type 4&nbsp;=&nbsp; {@code input-stream}, type
 * 5&nbsp;=&nbsp;{@code output-stream}); the next free component type index is 13. The
 * per-function canonical options were derived from a {@code wasm-tools dump} of the
 * {@code uni-http-server} reference (see {@code src/wasm-component/README.md} and
 * {@code .todo/51-...}).
 *
 * <p>
 * {@link #buildHttp} assembles the serve+fetch variant for a handler program that also
 * uses {@code rontolisp:fetch}: same shape, but the preview1 bridge is the extended
 * {@code adapter-http-server-client-p1.wasm} (which additionally exports
 * {@code fetch-start} / {@code fetch-await} and the reserved-slot tcp stubs, satisfying
 * the core's {@code http} and {@code sock} imports), over the wider import block
 * {@code import-block-http-server-client.bin} ({@code uni-http-server-client}: the serve
 * surface plus {@code wasi:io/poll} and {@code wasi:http/outgoing-handler}, still inside
 * the wasi:http proxy world -- run with {@code wasmtime serve -S http=y}).
 */
final class WasmServeComponentBuilder {

	private static final String RES = "component/";

	private static final byte[] IMPORT_BLOCK_HTTP_SERVER = resource("import-block-http-server.bin");

	/** The shared 16-page memory module (the adapter scratch reaches page 8). */
	private static final byte[] MEM_MODULE = resource("mem-http-client.wasm");

	/**
	 * The preview1 bridge: implements the core's {@code wasi_snapshot_preview1} imports
	 * (random_get / clock_time_get / fd_write to stdout-stderr; graceful stubs for the
	 * rest) over the wasi:http proxy world's wasi:random / wasi:clocks / wasi:cli.
	 */
	private static final byte[] ADAPTER_HTTP_SERVER_P1 = resource("adapter-http-server-p1.wasm");

	/**
	 * The serve adapter: reads the request, calls {@code %http-dispatch}, writes the
	 * response.
	 */
	private static final byte[] ADAPTER_HTTP_SERVER = resource("adapter-http-server.wasm");

	/**
	 * The serve+fetch variant of the import block ({@code uni-http-server-client}): the
	 * serve surface plus {@code wasi:io/poll} and {@code wasi:http/outgoing-handler}. It
	 * declares component import instances 0-9 and component types 0-19, so the next free
	 * component type index is 20.
	 */
	private static final byte[] IMPORT_BLOCK_HTTP_SERVER_CLIENT = resource("import-block-http-server-client.bin");

	/**
	 * The extended preview1 bridge for serve+fetch: {@link #ADAPTER_HTTP_SERVER_P1} plus
	 * the {@code fetch-start} / {@code fetch-await} exports of
	 * {@code adapter-http-client.wasm} (satisfying the core's {@code http} imports) and
	 * the errno-returning tcp stubs for the reserved {@code sock} slots.
	 */
	private static final byte[] ADAPTER_HTTP_SERVER_CLIENT_P1 = resource("adapter-http-server-client-p1.wasm");

	// Import-instance indices (from import-block-http-server.bin).
	private static final int INST_IO_STREAMS = 2;

	private static final int INST_HTTP_TYPES = 3;

	private static final int INST_RANDOM = 4;

	private static final int INST_WALL_CLOCK = 5;

	private static final int INST_MONO_CLOCK = 0;

	private static final int INST_CLI_STDOUT = 6;

	private static final int INST_CLI_STDERR = 7;

	// Component types pre-defined by the import block.
	private static final int T_INPUT_STREAM = 4;

	private static final int T_OUTPUT_STREAM = 5;

	// Aliased resource types (next free component type index after the import block =
	// 13).
	private static final int T_INCOMING_REQUEST = 13;

	private static final int T_INCOMING_BODY = 14;

	private static final int T_RESPONSE_OUTPARAM = 15;

	// Defined value/function types.
	private static final int T_OWN_REQUEST = 16;

	private static final int T_OWN_RESPONSE_OUTPARAM = 17;

	private static final int T_HANDLE_FUNC = 18;

	private WasmServeComponentBuilder() {
	}

	/**
	 * Assemble the serve component, importing the given user WIT interfaces
	 * ({@code rontolisp:wit-import}) alongside the fixed wasi:http surface.
	 * @param coreModule the rontolisp core module compiled in serve mode
	 * @param imports the user WIT interface imports (empty for none, which emits nothing
	 * and shifts nothing: the component then stays byte-identical)
	 * @return the WASI 0.2 (http/incoming-handler) component binary
	 */
	static byte[] build(byte[] coreModule, List<WasmComponentImportCompiler.Import> imports) {
		final int userIfaces = imports.size();
		final int userFuncs = WasmComponentBuilder.userImportFuncs(imports);
		// The other function count: a resource drop is a CORE function with no component
		// function behind it (canon resource.drop), so an index into the core function
		// space must skip the drops too. Confusing the two yields a component that
		// VALIDATES while lifting the wrong function.
		final int userCoreFuncs = WasmComponentBuilder.userImportCoreFuncs(imports);
		final ComponentWriter c = new ComponentWriter();
		// Import instances 0-7, component types 0-12.
		c.writeRaw(IMPORT_BLOCK_HTTP_SERVER);
		// Core modules: 0 = shared memory, 1 = preview1 bridge, 2 = rontolisp core (serve
		// mode), 3 = serve adapter.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_HTTP_SERVER_P1);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_HTTP_SERVER);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Alias the resource types we drop / reference (component types 13-15).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceType(INST_HTTP_TYPES, "incoming-request"),
						ComponentWriter.aliasInstanceType(INST_HTTP_TYPES, "incoming-body"),
						ComponentWriter.aliasInstanceType(INST_HTTP_TYPES, "response-outparam"))));
		// Alias the 18 lowered WASI functions (component funcs 0-17): the 13 the serve
		// adapter's "w" import group expects, then the 5 the preview1 bridge's "w" group
		// expects.
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[method]incoming-request.method"), // 0
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[method]incoming-request.path-with-query"), // 1
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[method]incoming-request.consume"), // 2
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[method]incoming-body.stream"), // 3
						ComponentWriter.aliasInstanceFunc(INST_IO_STREAMS, "[method]input-stream.blocking-read"), // 4
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[constructor]fields"), // 5
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[constructor]outgoing-response"), // 6
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[method]outgoing-response.set-status-code"), // 7
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[method]outgoing-response.body"), // 8
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[method]outgoing-body.write"), // 9
						ComponentWriter.aliasInstanceFunc(INST_IO_STREAMS,
								"[method]output-stream.blocking-write-and-flush"), // 10
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[static]outgoing-body.finish"), // 11
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[static]response-outparam.set"), // 12
						ComponentWriter.aliasInstanceFunc(INST_RANDOM, "get-random-u64"), // 13
						ComponentWriter.aliasInstanceFunc(INST_WALL_CLOCK, "now"), // 14
						ComponentWriter.aliasInstanceFunc(INST_MONO_CLOCK, "now"), // 15
						ComponentWriter.aliasInstanceFunc(INST_CLI_STDOUT, "get-stdout"), // 16
						ComponentWriter.aliasInstanceFunc(INST_CLI_STDERR, "get-stderr")))); // 17
		// Define own<incoming-request>, own<response-outparam> and the handle func type.
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedOwn(T_INCOMING_REQUEST), // 16
						ComponentWriter.definedOwn(T_RESPONSE_OUTPARAM), // 17
						ComponentWriter.funcTypeParamsNoResult(List.of("request", "response-out"),
								List.of(T_OWN_REQUEST, T_OWN_RESPONSE_OUTPARAM))))); // 18
		// Lower the 18 functions (core funcs 1-13 and 18-22; core func 0 = cabi_realloc)
		// with the canonical options wasm-tools chose, plus the four resource drops (core
		// funcs 14-17).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLowerMemoryReallocUtf8(0, 0, 0), // 1
																									// method
						ComponentWriter.canonLowerMemoryReallocUtf8(1, 0, 0), // 2
																				// path-with-query
						ComponentWriter.canonLowerMemory(2, 0), // 3 consume
						ComponentWriter.canonLowerMemory(3, 0), // 4 incoming-body.stream
						ComponentWriter.canonLower(4, 0, 0), // 5
																// input-stream.blocking-read
																// (mem+realloc)
						ComponentWriter.canonLower(5), // 6 fields ctor
						ComponentWriter.canonLower(6), // 7 outgoing-response ctor
						ComponentWriter.canonLower(7), // 8 set-status-code
						ComponentWriter.canonLowerMemory(8, 0), // 9
																// outgoing-response.body
						ComponentWriter.canonLowerMemory(9, 0), // 10 outgoing-body.write
						ComponentWriter.canonLowerMemory(10, 0), // 11
																	// blocking-write-and-flush
						ComponentWriter.canonLowerMemoryReallocUtf8(11, 0, 0), // 12
																				// outgoing-body.finish
						ComponentWriter.canonLowerMemoryUtf8(12, 0), // 13
																		// response-outparam.set
						ComponentWriter.canonResourceDrop(T_OUTPUT_STREAM), // 14 drop
																			// output-stream
						ComponentWriter.canonResourceDrop(T_INCOMING_REQUEST), // 15 drop
																				// incoming-request
						ComponentWriter.canonResourceDrop(T_INPUT_STREAM), // 16 drop
																			// input-stream
						ComponentWriter.canonResourceDrop(T_INCOMING_BODY), // 17 drop
																			// incoming-body
						ComponentWriter.canonLower(13), // 18 get-random-u64
						ComponentWriter.canonLowerMemory(14, 0), // 19 wall-clock now
						ComponentWriter.canonLower(15), // 20 monotonic-clock now
						ComponentWriter.canonLower(16), // 21 get-stdout
						ComponentWriter.canonLower(17)))); // 22 get-stderr
		// Group the 6 core funcs of the preview1 bridge's "w" import (core instance 1;
		// io-write is the same lowered blocking-write-and-flush the serve adapter uses).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("rand-u64", "wall-now", "mono-now", "get-stdout", "get-stderr", "io-write"),
						List.of(18, 19, 20, 21, 22, 11)))));
		// Instantiate the preview1 bridge (core instance 2): mem = instance 0, w =
		// instance 1. It must precede the rontolisp core, whose
		// wasi_snapshot_preview1 imports bind its exports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// User WIT-interface imports (rontolisp:wit-import): instance types from
		// component type 19, import instances from 8, function aliases from component
		// func 18, lowered core funcs from 23, and one synthesized core instance each
		// from 3 -- right after the bridge, so the core below can bind them. Emits
		// nothing when there are none, so every index below shifts by zero.
		WasmComponentBuilder.appendUserImports(c, imports, T_HANDLE_FUNC + 1, 8, 18, 23);
		// Instantiate the rontolisp core (core instance 3 + one per user interface):
		// mem = instance 0, wasi_snapshot_preview1 = the bridge (instance 2), plus each
		// user interface's canon-lowered core instance under its canonical id. The core
		// exports run / %http-dispatch / __ronto_alloc, which the serve adapter imports
		// by name.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(WasmComponentBuilder
			.rontolispInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2), imports, 3))));
		final int rontolisp = 3 + userIfaces;
		// Group the 17 lowered/drop core funcs for the serve adapter's "w" import (core
		// instance 4). Names match adapter-http-server.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("req-method", "req-path", "req-consume", "body-stream", "io-read", "fields-new",
								"resp-new", "set-status", "resp-body", "body-write", "io-write", "drop-out",
								"body-finish", "resp-set", "drop-req", "drop-in", "drop-body"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 12, 13, 15, 16, 17)))));
		// Instantiate the serve adapter (core instance 5): mem = instance 0, core = the
		// rontolisp instance, w = its "w" group (both shifted by the user interfaces).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(ComponentWriter
			.coreInstanceInstantiate(3, List.of("mem", "core", "w"), List.of(0, rontolisp, rontolisp + 1)))));
		// Alias the adapter's serve function (core func 23 + the user-import lowers).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(rontolisp + 2, "serve"))));
		// Lift serve into a component func with the handle func type 18. Component
		// func 18 follows the 18 aliased WASI funcs (0-17) + the user-import aliases.
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(23 + userCoreFuncs, T_HANDLE_FUNC))));
		// Component instance 8 (after import instances 0-7 + the user imports) exporting
		// handle, exported as the wasi:http/incoming-handler@0.2.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("handle", 18 + userFuncs))));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter
			.vec(List.of(ComponentWriter.exportInstance("wasi:http/incoming-handler@0.2.0", 8 + userIfaces))));
		return c.toByteArray();
	}

	// Serve+fetch variant import-instance indices (from
	// import-block-http-server-client.bin).
	// wasi:io/poll is hoisted first (a dependency of http/types' pollable-returning
	// members, now used), shifting every instance after it by one relative to the plain
	// serve block; wasi:http/outgoing-handler is appended last.
	private static final int HS_INST_IO_POLL = 0;

	private static final int HS_INST_MONO_CLOCK = 1;

	// instance 2 = wasi:io/error (no functions aliased)
	private static final int HS_INST_IO_STREAMS = 3;

	private static final int HS_INST_HTTP_TYPES = 4;

	private static final int HS_INST_RANDOM = 5;

	private static final int HS_INST_WALL_CLOCK = 6;

	private static final int HS_INST_CLI_STDOUT = 7;

	private static final int HS_INST_CLI_STDERR = 8;

	private static final int HS_INST_HTTP_HANDLER = 9;

	// Component types pre-defined by the import block (types 0-19): the resource types
	// the block itself aliases while declaring the interfaces.
	private static final int HS_T_INPUT_STREAM = 5;

	private static final int HS_T_OUTPUT_STREAM = 6;

	private static final int HS_T_POLLABLE = 7;

	private static final int HS_T_OUT_REQ = 15;

	private static final int HS_T_FUT_RESP = 17;

	// Aliased resource types (next free component type index after the import block =
	// 20).
	private static final int HS_T_INCOMING_REQUEST = 20;

	private static final int HS_T_INCOMING_BODY = 21;

	private static final int HS_T_RESPONSE_OUTPARAM = 22;

	private static final int HS_T_FIELDS = 23;

	private static final int HS_T_OUT_BODY = 24;

	private static final int HS_T_IN_RESP = 25;

	// Defined value/function types.
	private static final int HS_T_OWN_REQUEST = 26;

	private static final int HS_T_OWN_RESPONSE_OUTPARAM = 27;

	private static final int HS_T_HANDLE_FUNC = 28;

	/**
	 * Assemble the serve+fetch variant: a {@code rontolisp:http-handler} program that
	 * also uses {@code rontolisp:fetch}. Identical to {@link #build} except that the
	 * extended bridge {@code adapter-http-server-client-p1.wasm} also satisfies the
	 * core's {@code http} (fetch-start / fetch-await) and {@code sock} (stub) imports,
	 * wired over the wider {@code uni-http-server-client} import block. All wiring
	 * constants were derived from a {@code wasm-tools dump} of the
	 * {@code uni-http-server-client} reference generated by {@code regen.sh}.
	 * @param coreModule the rontolisp core module compiled in serve mode with fetch
	 * @param imports the user WIT interface imports (empty for none, which emits nothing
	 * and shifts nothing)
	 * @return the WASI 0.2 (http/incoming-handler) component binary
	 */
	static byte[] buildHttp(byte[] coreModule, List<WasmComponentImportCompiler.Import> imports) {
		final int userIfaces = imports.size();
		final int userFuncs = WasmComponentBuilder.userImportFuncs(imports);
		// The other function count: a resource drop is a CORE function with no component
		// function behind it (canon resource.drop), so an index into the core function
		// space must skip the drops too. Confusing the two yields a component that
		// VALIDATES while lifting the wrong function.
		final int userCoreFuncs = WasmComponentBuilder.userImportCoreFuncs(imports);
		final ComponentWriter c = new ComponentWriter();
		// Import instances 0-9, component types 0-19.
		c.writeRaw(IMPORT_BLOCK_HTTP_SERVER_CLIENT);
		// Core modules: 0 = shared memory, 1 = extended preview1 bridge (+fetch),
		// 2 = rontolisp core (serve mode with fetch), 3 = serve adapter.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_HTTP_SERVER_CLIENT_P1);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_HTTP_SERVER);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Alias the resource types to drop / reference (component types 20-25);
		// input-stream (5), output-stream (6), pollable (7), outgoing-request (15) and
		// future-incoming-response (17) are already aliased by the import block.
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceType(HS_INST_HTTP_TYPES, "incoming-request"), // 20
						ComponentWriter.aliasInstanceType(HS_INST_HTTP_TYPES, "incoming-body"), // 21
						ComponentWriter.aliasInstanceType(HS_INST_HTTP_TYPES, "response-outparam"), // 22
						ComponentWriter.aliasInstanceType(HS_INST_HTTP_TYPES, "fields"), // 23
						ComponentWriter.aliasInstanceType(HS_INST_HTTP_TYPES, "outgoing-body"), // 24
						ComponentWriter.aliasInstanceType(HS_INST_HTTP_TYPES, "incoming-response")))); // 25
		// Alias the 33 lowered WASI functions (component funcs 0-32): the 13 the serve
		// adapter's "w" import group expects, the 5 preview1-bridge functions, then the
		// 15 fetch functions the extended bridge additionally expects.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]incoming-request.method"), // 0
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]incoming-request.path-with-query"), // 1
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]incoming-request.consume"), // 2
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]incoming-body.stream"), // 3
				ComponentWriter.aliasInstanceFunc(HS_INST_IO_STREAMS, "[method]input-stream.blocking-read"), // 4
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[constructor]fields"), // 5
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[constructor]outgoing-response"), // 6
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]outgoing-response.set-status-code"), // 7
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]outgoing-response.body"), // 8
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]outgoing-body.write"), // 9
				ComponentWriter.aliasInstanceFunc(HS_INST_IO_STREAMS, "[method]output-stream.blocking-write-and-flush"), // 10
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[static]outgoing-body.finish"), // 11
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[static]response-outparam.set"), // 12
				ComponentWriter.aliasInstanceFunc(HS_INST_RANDOM, "get-random-u64"), // 13
				ComponentWriter.aliasInstanceFunc(HS_INST_WALL_CLOCK, "now"), // 14
				ComponentWriter.aliasInstanceFunc(HS_INST_MONO_CLOCK, "now"), // 15
				ComponentWriter.aliasInstanceFunc(HS_INST_CLI_STDOUT, "get-stdout"), // 16
				ComponentWriter.aliasInstanceFunc(HS_INST_CLI_STDERR, "get-stderr"), // 17
				ComponentWriter.aliasInstanceFunc(HS_INST_IO_POLL, "[method]pollable.block"), // 18
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]fields.append"), // 19
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]fields.entries"), // 20
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[constructor]outgoing-request"), // 21
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]outgoing-request.set-method"), // 22
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]outgoing-request.set-scheme"), // 23
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]outgoing-request.set-authority"), // 24
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]outgoing-request.set-path-with-query"), // 25
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]outgoing-request.body"), // 26
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]future-incoming-response.subscribe"), // 27
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]future-incoming-response.get"), // 28
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]incoming-response.status"), // 29
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]incoming-response.headers"), // 30
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_TYPES, "[method]incoming-response.consume"), // 31
				ComponentWriter.aliasInstanceFunc(HS_INST_HTTP_HANDLER, "handle")))); // 32
		// Define own<incoming-request>, own<response-outparam> and the handle func type.
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedOwn(HS_T_INCOMING_REQUEST), // 26
						ComponentWriter.definedOwn(HS_T_RESPONSE_OUTPARAM), // 27
						ComponentWriter.funcTypeParamsNoResult(List.of("request", "response-out"),
								List.of(HS_T_OWN_REQUEST, HS_T_OWN_RESPONSE_OUTPARAM))))); // 28
		// Lower the 33 functions (core funcs 1-33; core func 0 = cabi_realloc) with the
		// canonical options wasm-tools chose, then the ten resource drops (core funcs
		// 34-43).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLowerMemoryReallocUtf8(0, 0, 0), // 1
																									// method
						ComponentWriter.canonLowerMemoryReallocUtf8(1, 0, 0), // 2
																				// path-with-query
						ComponentWriter.canonLowerMemory(2, 0), // 3 consume
						ComponentWriter.canonLowerMemory(3, 0), // 4 incoming-body.stream
						ComponentWriter.canonLower(4, 0, 0), // 5
																// input-stream.blocking-read
																// (mem+realloc)
						ComponentWriter.canonLower(5), // 6 fields ctor
						ComponentWriter.canonLower(6), // 7 outgoing-response ctor
						ComponentWriter.canonLower(7), // 8 set-status-code
						ComponentWriter.canonLowerMemory(8, 0), // 9
																// outgoing-response.body
						ComponentWriter.canonLowerMemory(9, 0), // 10 outgoing-body.write
						ComponentWriter.canonLowerMemory(10, 0), // 11
																	// blocking-write-and-flush
						ComponentWriter.canonLowerMemoryReallocUtf8(11, 0, 0), // 12
																				// outgoing-body.finish
						ComponentWriter.canonLowerMemoryUtf8(12, 0), // 13
																		// response-outparam.set
						ComponentWriter.canonLower(13), // 14 get-random-u64
						ComponentWriter.canonLowerMemory(14, 0), // 15 wall-clock now
						ComponentWriter.canonLower(15), // 16 monotonic-clock now
						ComponentWriter.canonLower(16), // 17 get-stdout
						ComponentWriter.canonLower(17), // 18 get-stderr
						ComponentWriter.canonLower(18), // 19 pollable.block
						ComponentWriter.canonLowerMemoryUtf8(19, 0), // 20 fields.append
						ComponentWriter.canonLowerMemoryReallocUtf8(20, 0, 0), // 21
																				// fields.entries
						ComponentWriter.canonLower(21), // 22 outgoing-request ctor
						ComponentWriter.canonLowerMemoryUtf8(22, 0), // 23 set-method
						ComponentWriter.canonLowerMemoryUtf8(23, 0), // 24 set-scheme
						ComponentWriter.canonLowerMemoryUtf8(24, 0), // 25 set-authority
						ComponentWriter.canonLowerMemoryUtf8(25, 0), // 26
																		// set-path-with-query
						ComponentWriter.canonLowerMemory(26, 0), // 27
																	// outgoing-request.body
						ComponentWriter.canonLower(27), // 28 future.subscribe
						ComponentWriter.canonLowerMemoryReallocUtf8(28, 0, 0), // 29
																				// future.get
						ComponentWriter.canonLower(29), // 30 incoming-response.status
						ComponentWriter.canonLower(30), // 31 incoming-response.headers
						ComponentWriter.canonLowerMemory(31, 0), // 32
																	// incoming-response.consume
						ComponentWriter.canonLowerMemoryReallocUtf8(32, 0, 0), // 33
																				// handle
						ComponentWriter.canonResourceDrop(HS_T_OUTPUT_STREAM), // 34 drop
																				// output-stream
						ComponentWriter.canonResourceDrop(HS_T_INCOMING_REQUEST), // 35
																					// drop
																					// incoming-request
						ComponentWriter.canonResourceDrop(HS_T_INPUT_STREAM), // 36 drop
																				// input-stream
						ComponentWriter.canonResourceDrop(HS_T_INCOMING_BODY), // 37 drop
																				// incoming-body
						ComponentWriter.canonResourceDrop(HS_T_POLLABLE), // 38 drop
																			// pollable
						ComponentWriter.canonResourceDrop(HS_T_FIELDS), // 39 drop fields
						ComponentWriter.canonResourceDrop(HS_T_OUT_REQ), // 40 drop
																			// outgoing-request
						ComponentWriter.canonResourceDrop(HS_T_OUT_BODY), // 41 drop
																			// outgoing-body
						ComponentWriter.canonResourceDrop(HS_T_FUT_RESP), // 42 drop
																			// future-incoming-response
						ComponentWriter.canonResourceDrop(HS_T_IN_RESP)))); // 43 drop
																			// incoming-response
		// Group the 35 core funcs of the extended bridge's "w" import (core instance 1):
		// the six preview1-bridge funcs, then the fetch machinery (names match
		// adapter-http-server-client-p1.wat's imports; "drop-req" here is the
		// OUTGOING-request
		// drop -- the serve adapter's same-named import below is the incoming one).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("rand-u64", "wall-now", "mono-now", "get-stdout", "get-stderr", "io-write", "io-read",
								"drop-out", "drop-in", "poll-block", "drop-pollable", "fields-new", "fields-append",
								"fields-entries", "req-new", "set-method", "set-scheme", "set-authority", "set-path",
								"req-body", "body-write", "body-finish", "future-subscribe", "future-get",
								"resp-status", "resp-headers", "resp-consume", "body-stream", "handle", "drop-fields",
								"drop-req", "drop-outgoing-body", "drop-future", "drop-resp", "drop-body"),
						List.of(14, 15, 16, 17, 18, 11, 5, 34, 36, 19, 38, 6, 20, 21, 22, 23, 24, 25, 26, 27, 10, 12,
								28, 29, 30, 31, 32, 4, 33, 39, 40, 41, 42, 43, 37)))));
		// Instantiate the extended bridge (core instance 2): mem = instance 0, w =
		// instance 1. It must precede the rontolisp core, whose wasi_snapshot_preview1 /
		// sock / http imports bind its exports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// User WIT-interface imports (rontolisp:wit-import): instance types from
		// component type 29, import instances from 10, function aliases from component
		// func 33, lowered core funcs from 44, and one synthesized core instance each
		// from 3. Emits nothing when there are none.
		WasmComponentBuilder.appendUserImports(c, imports, HS_T_HANDLE_FUNC + 1, 10, 33, 44);
		// Instantiate the rontolisp core (core instance 3 + one per user interface):
		// mem = instance 0, and wasi_snapshot_preview1, sock AND http all satisfied by
		// the bridge (instance 2; it exports the eight preview1 functions, the four
		// errno-returning tcp stubs and fetch-start / fetch-await), plus each user
		// interface's canon-lowered core instance. The core exports run / %http-dispatch
		// / __ronto_alloc, which the serve adapter imports by name.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(WasmComponentBuilder.rontolispInstantiate(2,
						List.of("mem", "wasi_snapshot_preview1", "sock", "http"), List.of(0, 2, 2, 2), imports, 3))));
		final int rontolisp = 3 + userIfaces;
		// Group the 17 lowered/drop core funcs for the serve adapter's "w" import (core
		// instance 4). Names match adapter-http-server.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("req-method", "req-path", "req-consume", "body-stream", "io-read", "fields-new",
								"resp-new", "set-status", "resp-body", "body-write", "io-write", "drop-out",
								"body-finish", "resp-set", "drop-req", "drop-in", "drop-body"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 34, 12, 13, 35, 36, 37)))));
		// Instantiate the serve adapter (core instance 5): mem = instance 0, core = the
		// rontolisp instance, w = its "w" group (both shifted by the user interfaces).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(ComponentWriter
			.coreInstanceInstantiate(3, List.of("mem", "core", "w"), List.of(0, rontolisp, rontolisp + 1)))));
		// Alias the adapter's serve function (core func 44 + the user-import lowers).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(rontolisp + 2, "serve"))));
		// Lift serve into a component func with the handle func type 28. Component
		// func 33 follows the 33 aliased WASI funcs (0-32) + the user-import aliases.
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(44 + userCoreFuncs, HS_T_HANDLE_FUNC))));
		// Component instance 10 (after import instances 0-9 + the user imports) exporting
		// handle, exported as the wasi:http/incoming-handler@0.2.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("handle", 33 + userFuncs))));
		c.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter
			.vec(List.of(ComponentWriter.exportInstance("wasi:http/incoming-handler@0.2.0", 10 + userIfaces))));
		return c.toByteArray();
	}

	private static byte[] resource(String name) {
		try (InputStream in = WasmServeComponentBuilder.class.getResourceAsStream(RES + name)) {
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
