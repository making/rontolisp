package am.ik.rontolisp.codegen.wasm;

/**
 * Assembles the serve-variant WASI component for {@code rontolisp:http-handler}: it wraps
 * a rontolisp core module (compiled in serve mode, so it exports {@code %http-dispatch} +
 * {@code __ronto_alloc} + {@code run} and imports its memory) with the shared memory
 * module and the serve adapter ({@code adapter-serve.wasm}), and exports
 * {@code wasi:http/incoming-handler@0.2.0} so the component runs under
 * {@code wasmtime serve} and Spin.
 *
 * <p>
 * The component-model wiring (import instances / component types / canonical lowering
 * options) is derived from a {@code wasm-tools dump} of the {@code uni-serve} reference
 * (see {@code src/wasm-component/README.md} and {@code .todo/51-...}). Not yet assembled
 * here.
 */
final class WasmServeComponentBuilder {

	private WasmServeComponentBuilder() {
	}

	static byte[] build(byte[] coreModule) {
		throw new UnsupportedOperationException(
				"rontolisp:http-handler WASI component assembly (buildServe) is not implemented yet; "
						+ "the serve-mode core module and adapter are ready (see .todo/51)");
	}

}
