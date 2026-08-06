package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.wasm.Instruction;
import am.ik.wasm.WasmWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code _start} prologue that pre-grows the engine's GC heap with one large,
 * immediately-dropped byte-array allocation. Without it, a program whose long-lived
 * environment occupies a sizable share of wasmtime's default GC heap pays a
 * whole-live-set copy every few hundred KB of allocation, so every hot loop slows down in
 * proportion to how much code is merely loaded (.kb/wasm-gc-heap-pregrow.md).
 */
class WasmGcHeapPregrowTest {

	private static byte[] pregrowPrologue(int bytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(bytes);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.DROP);
		return out.toByteArray();
	}

	private static byte[] pregrowPrologue() {
		return pregrowPrologue(WasmLispCompiler.GC_HEAP_PREGROW_BYTES);
	}

	private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	@Test
	void startBodyBeginsWithGcHeapPregrowAllocation() {
		List<LispVal> program = LispReader.readAllFromString("(print 1)");
		byte[] module = new WasmLispCompiler().compile(program);
		assertThat(containsSubsequence(module, pregrowPrologue()))
			.as("emitted module should contain the GC-heap pre-grow prologue")
			.isTrue();
	}

	@Test
	void componentCoreAlsoCarriesThePregrowPrologue() {
		List<LispVal> program = LispReader.readAllFromString("(print 1)");
		byte[] module = new WasmLispCompiler(false, true).compile(program);
		assertThat(containsSubsequence(module, pregrowPrologue()))
			.as("component core module should contain the GC-heap pre-grow prologue")
			.isTrue();
	}

	/**
	 * A served component is re-instantiated every N requests, so the pre-grow lands on
	 * request latency instead of process startup: serve mode pre-grows the smaller
	 * {@link WasmLispCompiler#GC_HEAP_PREGROW_SERVE_BYTES} heap, and must NOT carry the
	 * process-lifetime constant (.kb/wasm-gc-heap-pregrow.md).
	 */
	@Test
	void serveComponentPregrowsTheSmallerHeap() {
		byte[] module = compileServe("""
				(defun handle (env) (list 200 '(:content-type "text/plain") (list "hi")))
				(rontolisp:http-handler 'handle)
				""");
		assertThat(containsSubsequence(module, pregrowPrologue(WasmLispCompiler.GC_HEAP_PREGROW_SERVE_BYTES)))
			.as("serve core module should carry the serve-sized GC-heap pre-grow prologue")
			.isTrue();
		assertThat(containsSubsequence(module, pregrowPrologue(WasmLispCompiler.GC_HEAP_PREGROW_BYTES)))
			.as("serve core module must not pay the process-lifetime pre-grow")
			.isFalse();
	}

	// The library splices a served handler needs, in the CLI's order (the subset
	// WasmLispCompilerIntegrationTest.compileServeComponent uses; no Docker here --
	// this only inspects the emitted bytes).
	private static byte[] compileServe(String source) {
		var backend = am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT;
		List<LispVal> loaded = LispReader.readAllFromString(source);
		boolean bufferBody = am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(loaded);
		loaded = am.ik.rontolisp.eval.HttpLibrary.process(loaded, backend, true);
		loaded = am.ik.rontolisp.eval.HttpServerLibrary.process(loaded, bufferBody);
		loaded = am.ik.rontolisp.eval.WaitForLibrary.process(loaded, backend);
		List<LispVal> program = am.ik.rontolisp.eval.WitLibrary
			.process(am.ik.rontolisp.eval.GrayStreamsLibrary.process(am.ik.rontolisp.eval.LispPreludeLibrary
				.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded))));
		return new WasmLispCompiler(false, true, false, OptimizeLevel.NONE, true).compile(program);
	}

}
