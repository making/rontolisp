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
 *
 * <p>
 * Every compile here names {@link OptimizeLevel#NONE}: the prologue is matched as a byte
 * subsequence carrying the literal {@code TYPE_STR_BYTES} type index, and the tree shaker
 * renumbers the type section, so the pattern is the UNOPTIMIZED module's spelling of it.
 * The absence assertions below would otherwise pass for that reason instead of the one
 * they name.
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
		byte[] module = WasmLispCompiler.builder().optimize(OptimizeLevel.NONE).build().compile(program);
		assertThat(containsSubsequence(module, pregrowPrologue()))
			.as("emitted module should contain the GC-heap pre-grow prologue")
			.isTrue();
	}

	@Test
	void componentCoreAlsoCarriesThePregrowPrologue() {
		List<LispVal> program = LispReader.readAllFromString("(print 1)");
		byte[] module = WasmLispCompiler.builder()
			.component(true)
			.optimize(OptimizeLevel.NONE)
			.build()
			.compile(program);
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

	/**
	 * The size follows the program. wasmtime 47's copying collector does not merely slow
	 * a program down when the heap has no headroom over the live set -- it loses a live
	 * reference when it collects during an exception unwind (.kb/wasm-gc-heap-pregrow.md)
	 * -- so a program that carries a library stack must pre-grow more than the floor a
	 * library-free program does.
	 */
	@Test
	void aProgramCarryingMuchCodePregrowsMoreThanTheFloor() {
		byte[] module = WasmLispCompiler.builder()
			.optimize(OptimizeLevel.NONE)
			.build()
			.compile(LispReader.readAllFromString(manyDefuns(900)));
		assertThat(containsSubsequence(module, pregrowPrologue(WasmLispCompiler.GC_HEAP_PREGROW_BYTES)))
			.as("a program with a library stack's worth of code must not pre-grow only the floor")
			.isFalse();
	}

	/**
	 * The formula itself: the floor for a program with no code to speak of, linear in the
	 * emitted code in between, the ceiling past that, and the per-instance constant for
	 * serve whatever the program carries.
	 */
	@Test
	void pregrowSizeIsClampedBetweenTheFloorAndTheCeiling() {
		assertThat(WasmLispCompiler.gcHeapPregrowBytes(false, 0)).isEqualTo(WasmLispCompiler.GC_HEAP_PREGROW_BYTES);
		assertThat(WasmLispCompiler.gcHeapPregrowBytes(false, 4L * 1024 * 1024))
			.isEqualTo(4 * 1024 * 1024 * WasmLispCompiler.GC_HEAP_PREGROW_CODE_FACTOR);
		assertThat(WasmLispCompiler.gcHeapPregrowBytes(false, 64L * 1024 * 1024))
			.isEqualTo(WasmLispCompiler.GC_HEAP_PREGROW_MAX_BYTES);
		assertThat(WasmLispCompiler.gcHeapPregrowBytes(true, 64L * 1024 * 1024))
			.isEqualTo(WasmLispCompiler.GC_HEAP_PREGROW_SERVE_BYTES);
	}

	/**
	 * Every size the formula can produce is a power of two, so a program's heap size is
	 * one of three reviewed values rather than a number redrawn from the emitted byte
	 * count on every build.
	 * <p>
	 * wasmtime 47.3's copying collector breaks for narrow BANDS of GC-heap size -- a few
	 * KB wide, moving with the program, reporting either "there should always be enough
	 * room in the active semi-space" as an injected trap or a panic on a zeroed object
	 * header. The ci-spec corpus landed on one such band on 2026-09-11 purely by adding
	 * corpus cases: the emitted code grew, the unquantized size followed it onto the
	 * band, and the WASM {@code --simd} leg went red with no compiler change. Quantized,
	 * the same edit cannot move the size at all, and each of the three values the
	 * compiler can emit is covered by the corpus run. See
	 * {@code .kb/wasm-gc-heap-pregrow.md}.
	 */
	@Test
	void pregrowSizeIsAlwaysAPowerOfTwo() {
		for (long codeBytes : new long[] { 0, 1, 1024, 700_000, 1_000_000, 2_500_000, 4_078_697, 4_194_304,
				40_000_000 }) {
			int size = WasmLispCompiler.gcHeapPregrowBytes(false, codeBytes);
			assertThat(Integer.bitCount(size))
				.as("pre-grow size for %d bytes of code should be a power of two, was %d", codeBytes, size)
				.isEqualTo(1);
			assertThat(size).isBetween(WasmLispCompiler.GC_HEAP_PREGROW_BYTES,
					WasmLispCompiler.GC_HEAP_PREGROW_MAX_BYTES);
		}
		// Rounded UP: the factor is chosen to leave ~2x the live set, and rounding down
		// would spend that margin. 4,078,697 bytes of code is what the ci-spec corpus
		// emitted on the day the leg broke; x16 is 62.24 MiB, which must land on the
		// 64 MiB ceiling rather than back down on 32 MiB.
		assertThat(WasmLispCompiler.gcHeapPregrowBytes(false, 4_078_697))
			.isEqualTo(WasmLispCompiler.GC_HEAP_PREGROW_MAX_BYTES);
		assertThat(WasmLispCompiler.gcHeapPregrowBytes(false, 1_500_000)).isEqualTo(32 * 1024 * 1024);
	}

	/** {@code count} defuns whose bodies are large enough to add up to a real stack. */
	private static String manyDefuns(int count) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < count; i++) {
			out.append("(defun f").append(i).append(" (a b) (list");
			for (int j = 0; j < 12; j++) {
				out.append(" (+ (* a ").append(j).append(") (- b ").append(j).append("))");
			}
			out.append("))\n");
		}
		out.append("(print (f0 1 2))\n");
		return out.toString();
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
		return WasmLispCompiler.builder()
			.component(true)
			.optimize(OptimizeLevel.NONE)
			.serve(true)
			.build()
			.compile(program);
	}

}
