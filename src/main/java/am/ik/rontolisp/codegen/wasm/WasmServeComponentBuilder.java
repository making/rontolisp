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
 * memory module, the preview1 bridge ({@code adapter-serve-p1.wasm}, instantiated before
 * the core so its exports satisfy the core's {@code wasi_snapshot_preview1} imports:
 * random / clock / stdout-stderr over the wasi:http proxy world) and the serve adapter
 * ({@code adapter-serve.wasm}, instantiated after the core because it imports
 * {@code %http-dispatch}), and exports {@code wasi:http/incoming-handler@0.2.0} so the
 * component runs under {@code wasmtime serve} (or any {@code wasi:http} 0.2 host with
 * wasm-GC enabled, e.g. jco or wasmCloud; not Spin, whose wasmtime cannot enable
 * wasm-GC).
 *
 * <p>
 * The wiring mirrors {@link WasmComponentBuilder}'s base/http/sock variants but exports a
 * <em>synchronous</em> {@code handle(request, response-out)} (not the async {@code run}
 * lift). The import block ({@code import-block-serve.bin}) declares import instances
 * 0&nbsp;=&nbsp;{@code wasi:clocks/monotonic-clock} (hoisted first as a dependency of
 * http/types), 1&nbsp;=&nbsp;{@code wasi:io/error},
 * 2&nbsp;=&nbsp;{@code wasi:io/streams}, 3&nbsp;=&nbsp;{@code wasi:http/types},
 * 4&nbsp;=&nbsp;{@code wasi:random/random}, 5&nbsp;=&nbsp;{@code wasi:clocks/wall-clock},
 * 6&nbsp;=&nbsp;{@code wasi:cli/stdout}, 7&nbsp;=&nbsp;{@code wasi:cli/stderr} and
 * component types 0-12 (type 4&nbsp;=&nbsp; {@code input-stream}, type
 * 5&nbsp;=&nbsp;{@code output-stream}); the next free component type index is 13. The
 * per-function canonical options were derived from a {@code wasm-tools dump} of the
 * {@code uni-serve} reference (see {@code src/wasm-component/README.md} and
 * {@code .todo/51-...}).
 */
final class WasmServeComponentBuilder {

	private static final String RES = "component/";

	private static final byte[] IMPORT_BLOCK_SERVE = resource("import-block-serve.bin");

	/** The shared 16-page memory module (the adapter scratch reaches page 8). */
	private static final byte[] MEM_MODULE = resource("mem-http.wasm");

	/**
	 * The preview1 bridge: implements the core's {@code wasi_snapshot_preview1} imports
	 * (random_get / clock_time_get / fd_write to stdout-stderr; graceful stubs for the
	 * rest) over the wasi:http proxy world's wasi:random / wasi:clocks / wasi:cli.
	 */
	private static final byte[] ADAPTER_SERVE_P1 = resource("adapter-serve-p1.wasm");

	/**
	 * The serve adapter: reads the request, calls {@code %http-dispatch}, writes the
	 * response.
	 */
	private static final byte[] ADAPTER_SERVE = resource("adapter-serve.wasm");

	// Import-instance indices (from import-block-serve.bin).
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

	static byte[] build(byte[] coreModule) {
		final ComponentWriter c = new ComponentWriter();
		// Import instances 0-7, component types 0-12.
		c.writeRaw(IMPORT_BLOCK_SERVE);
		// Core modules: 0 = shared memory, 1 = preview1 bridge, 2 = rontolisp core (serve
		// mode), 3 = serve adapter.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_SERVE_P1);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_SERVE);
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
		// Instantiate the rontolisp core (core instance 3): mem = instance 0,
		// wasi_snapshot_preview1 = the bridge (instance 2). It exports run /
		// %http-dispatch / __ronto_alloc, which the serve adapter imports by name.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2)))));
		// Group the 17 lowered/drop core funcs for the serve adapter's "w" import (core
		// instance 4). Names match adapter-serve.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("req-method", "req-path", "req-consume", "body-stream", "io-read", "fields-new",
								"resp-new", "set-status", "resp-body", "body-write", "io-write", "drop-out",
								"body-finish", "resp-set", "drop-req", "drop-in", "drop-body"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 12, 13, 15, 16, 17)))));
		// Instantiate the serve adapter (core instance 5): mem = instance 0, core =
		// instance 3, w = instance 4.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(3, List.of("mem", "core", "w"), List.of(0, 3, 4)))));
		// Alias the adapter's serve function (core func 23).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(5, "serve"))));
		// Lift serve (core func 23) into a component func with the handle func type 18.
		// Component func 18 follows the 18 aliased WASI funcs (0-17).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(23, T_HANDLE_FUNC))));
		// Component instance 8 (after import instances 0-7) exporting handle, exported as
		// the wasi:http/incoming-handler@0.2.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("handle", 18))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:http/incoming-handler@0.2.0", 8))));
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
