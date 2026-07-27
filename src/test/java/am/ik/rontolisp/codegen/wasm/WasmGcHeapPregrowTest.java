package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;

import am.ik.rontolisp.LispVal;
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

	private static byte[] pregrowPrologue() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.GC_HEAP_PREGROW_BYTES);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.DROP);
		return out.toByteArray();
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

}
