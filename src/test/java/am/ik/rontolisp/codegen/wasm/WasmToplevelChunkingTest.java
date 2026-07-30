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
 * <p>
 * There is a case per top-level SHAPE, because they are emitted by different paths that
 * have been out of step before: the synchronous top level chunks by size
 * ({@code WasmToplevelEmit}), while the {@code --component} async top level cuts its
 * resume at the awaits and outlines each await-free run -- which bounds nothing by
 * itself, since a run is as long as the program. Measuring only one of the two is how a
 * 650 KB component body shipped while the same program's synchronous build stayed at 214
 * KB.
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

	private static byte[] compileComponent(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, true).compile(program);
	}

	/** A long top level whose forms are individually small. */
	private static String longToplevel() {
		return IntStream.range(0, 12000)
			.mapToObj(i -> "(print (+ %d (* %d 3)))".formatted(i, i))
			.collect(Collectors.joining("\n"));
	}

	@Test
	void aLongToplevelIsNotEmittedAsOneFunctionBody() {
		// Each form is small; only their number is unusual. A backend that concatenates
		// them into _start grows one body without bound.
		int largest = WasmModuleInspector.largestFunctionBodySize(compile(longToplevel()));

		assertThat(largest)
			.as("largest emitted function body; a monolithic toplevel makes a cold wasmtime "
					+ "compile need memory superlinear in this number")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

	@Test
	void aLongAsyncToplevelIsNotEmittedAsOneFunctionBody() {
		// One top-level await puts the WHOLE top level on the async resume path, which
		// outlines each await-free RUN of statements -- a run whose length is the
		// program's, not a bounded number of bytes. The size bound has to hold there
		// too: this is the shape every fetch/serve component and the --component leg of
		// the ci-spec corpus compiles as.
		String source = longToplevel() + "\n(print (rontolisp:await 42))\n";

		int largest = WasmModuleInspector.largestFunctionBodySize(compileComponent(source));

		assertThat(largest)
			.as("largest emitted function body of the ASYNC top level; a run outlined whole "
					+ "makes a cold wasmtime compile need memory superlinear in this number")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

}
