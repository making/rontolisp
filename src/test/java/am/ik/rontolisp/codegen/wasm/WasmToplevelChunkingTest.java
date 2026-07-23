package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the bound on the size of any single emitted WASM function body.
 * <p>
 * A wasmtime cold compile (Cranelift) needs memory that grows superlinearly -- measured
 * at roughly the 1.8th power -- in the size of ONE function body, and nothing else about
 * the module matters. Measured on the concatenated {@code ci-spec.yaml} corpus, cold
 * cache, {@code wasmtime 47.0.2}:
 *
 * <pre>
 * largest body    9 KB -&gt; wasmtime peaks at  284 MB
 * largest body  261 KB -&gt; wasmtime peaks at  2.7 GB
 * largest body  437 KB -&gt; wasmtime peaks at  7.4 GB
 * largest body  850 KB -&gt; wasmtime peaks at 25.8 GB
 * </pre>
 *
 * So a backend that concatenates the whole toplevel into one body makes a large program
 * un-runnable: it is not slow, it exhausts the machine. (This is what took the CI
 * {@code native-image} job down -- a 16 GB runner was OOM-killed while compiling the
 * corpus, and the wasmtime compilation cache hides it on any host that has run the module
 * once before.)
 * <p>
 * The bound below is deliberately far under the point where a 16 GB machine is at risk.
 * The async toplevel path already chunks for exactly this reason
 * ({@code WasmAsyncEmit.compileTopLevelChunk}); this test is what keeps the ordinary
 * synchronous path honest.
 */
class WasmToplevelChunkingTest {

	/**
	 * 256 KiB of body keeps a cold wasmtime compile in the low gigabytes, with a wide
	 * margin against the smallest CI runner. Raising this is a decision about how big a
	 * machine users need, not a formatting detail.
	 */
	private static final int MAX_FUNCTION_BODY_BYTES = 256 * 1024;

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler().compile(program);
	}

	@Test
	void aLongToplevelIsNotEmittedAsOneFunctionBody() {
		// Each form is small; only their number is unusual. A backend that concatenates
		// them into _start grows one body without bound.
		String source = IntStream.range(0, 12000)
			.mapToObj(i -> "(print (+ %d (* %d 3)))".formatted(i, i))
			.collect(Collectors.joining("\n"));

		int largest = WasmModuleInspector.largestFunctionBodySize(compile(source));

		assertThat(largest)
			.as("largest emitted function body; a monolithic toplevel makes a cold wasmtime "
					+ "compile need memory superlinear in this number")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

}
