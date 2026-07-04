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
 * memory module and the serve adapter ({@code adapter-serve.wasm}), and exports
 * {@code wasi:http/incoming-handler@0.2.0} so the component runs under
 * {@code wasmtime serve} (or any {@code wasi:http} 0.2 host with wasm-GC enabled, e.g.
 * jco or wasmCloud; not Spin, whose wasmtime cannot enable wasm-GC).
 *
 * <p>
 * The wiring mirrors {@link WasmComponentBuilder}'s base/http/sock variants but exports a
 * <em>synchronous</em> {@code handle(request, response-out)} (not the async {@code run}
 * lift). The import block ({@code import-block-serve.bin}) declares import instances
 * 0&nbsp;=&nbsp;{@code wasi:io/error}, 1&nbsp;=&nbsp;{@code wasi:io/streams},
 * 2&nbsp;=&nbsp;{@code wasi:http/types} and component types 0-5 (type 3&nbsp;=&nbsp;
 * {@code input-stream}, type 4&nbsp;=&nbsp;{@code output-stream}); the next free
 * component type index is 6. The per-function canonical options were derived from a
 * {@code wasm-tools dump} of the {@code uni-serve} reference (see
 * {@code src/wasm-component/README.md} and {@code .todo/51-...}).
 */
final class WasmServeComponentBuilder {

	private static final String RES = "component/";

	private static final byte[] IMPORT_BLOCK_SERVE = resource("import-block-serve.bin");

	/** The shared 16-page memory module (the adapter scratch reaches page 8). */
	private static final byte[] MEM_MODULE = resource("mem-http.wasm");

	/**
	 * The serve adapter: reads the request, calls {@code %http-dispatch}, writes the
	 * response.
	 */
	private static final byte[] ADAPTER_SERVE = resource("adapter-serve.wasm");

	// Import-instance indices (from import-block-serve.bin).
	private static final int INST_IO_STREAMS = 1;

	private static final int INST_HTTP_TYPES = 2;

	// Component types pre-defined by the import block.
	private static final int T_INPUT_STREAM = 3;

	private static final int T_OUTPUT_STREAM = 4;

	// Aliased resource types (next free component type index after the import block = 6).
	private static final int T_INCOMING_REQUEST = 6;

	private static final int T_INCOMING_BODY = 7;

	private static final int T_RESPONSE_OUTPARAM = 8;

	// Defined value/function types.
	private static final int T_OWN_REQUEST = 9;

	private static final int T_OWN_RESPONSE_OUTPARAM = 10;

	private static final int T_HANDLE_FUNC = 11;

	private WasmServeComponentBuilder() {
	}

	static byte[] build(byte[] coreModule) {
		final ComponentWriter c = new ComponentWriter();
		// Import instances 0-2, component types 0-5.
		c.writeRaw(IMPORT_BLOCK_SERVE);
		// Core modules: 0 = shared memory, 1 = rontolisp core (serve mode), 2 = serve
		// adapter.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_SERVE);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Instantiate the rontolisp core (core instance 1): mem = instance 0. It exports
		// run / %http-dispatch / __ronto_alloc, which the adapter imports by name.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem"), List.of(0)))));
		// Alias the resource types we drop / reference (component types 6-8).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceType(INST_HTTP_TYPES, "incoming-request"),
						ComponentWriter.aliasInstanceType(INST_HTTP_TYPES, "incoming-body"),
						ComponentWriter.aliasInstanceType(INST_HTTP_TYPES, "response-outparam"))));
		// Alias the 13 lowered WASI functions (component funcs 0-12), in the order the
		// adapter's "w" import group expects.
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
						ComponentWriter.aliasInstanceFunc(INST_HTTP_TYPES, "[static]response-outparam.set")))); // 12
		// Define own<incoming-request>, own<response-outparam> and the handle func type.
		c.rawSection(ComponentWriter.SEC_TYPE,
				ComponentWriter.vec(List.of(ComponentWriter.definedOwn(T_INCOMING_REQUEST), // 9
						ComponentWriter.definedOwn(T_RESPONSE_OUTPARAM), // 10
						ComponentWriter.funcTypeParamsNoResult(List.of("request", "response-out"),
								List.of(T_OWN_REQUEST, T_OWN_RESPONSE_OUTPARAM))))); // 11
		// Lower the 13 functions (core funcs 1-13; core func 0 = cabi_realloc) with the
		// canonical options wasm-tools chose, then the four resource drops (core funcs
		// 14-17).
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
						ComponentWriter.canonResourceDrop(T_INCOMING_BODY)))); // 17 drop
																				// incoming-body
		// Group the 17 lowered/drop core funcs for the adapter's "w" import (core
		// instance
		// 2). Names match adapter-serve.wat's imports.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("req-method", "req-path", "req-consume", "body-stream", "io-read", "fields-new",
								"resp-new", "set-status", "resp-body", "body-write", "io-write", "drop-out",
								"body-finish", "resp-set", "drop-req", "drop-in", "drop-body"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 12, 13, 15, 16, 17)))));
		// Instantiate the adapter (core instance 3): mem = instance 0, core = instance 1,
		// w = instance 2.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "core", "w"), List.of(0, 1, 2)))));
		// Alias the adapter's serve function (core func 18).
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "serve"))));
		// Lift serve (core func 18) into a component func with the handle func type 11.
		// Component func 13 follows the 13 aliased WASI funcs (0-12).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLift(18, T_HANDLE_FUNC))));
		// Component instance 3 (after import instances 0-2) exporting handle, exported as
		// the wasi:http/incoming-handler@0.2.0 interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("handle", 13))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:http/incoming-handler@0.2.0", 3))));
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
