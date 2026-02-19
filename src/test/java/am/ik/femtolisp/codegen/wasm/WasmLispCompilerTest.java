package am.ik.femtolisp.codegen.wasm;

import java.util.List;

import am.ik.femtolisp.LispVal;
import am.ik.femtolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WasmLispCompilerTest {

	private byte[] compile(String lispCode) {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		return new WasmLispCompiler().compile(program);
	}

	@Test
	void wasmMagicNumber() {
		byte[] wasm = compile("(print 1)");
		assertThat(wasm[0]).isEqualTo((byte) 0x00); // \0
		assertThat(wasm[1]).isEqualTo((byte) 'a');
		assertThat(wasm[2]).isEqualTo((byte) 's');
		assertThat(wasm[3]).isEqualTo((byte) 'm');
	}

	@Test
	void wasmVersion() {
		byte[] wasm = compile("(print 1)");
		assertThat(wasm[4]).isEqualTo((byte) 0x01);
		assertThat(wasm[5]).isEqualTo((byte) 0x00);
		assertThat(wasm[6]).isEqualTo((byte) 0x00);
		assertThat(wasm[7]).isEqualTo((byte) 0x00);
	}

	@Test
	void wasmContainsTypeSection() {
		byte[] wasm = compile("(print 1)");
		assertThat(containsSectionId(wasm, 0x01)).isTrue();
	}

	@Test
	void wasmContainsRecTypeGroup() {
		byte[] wasm = compile("(print 1)");
		// rec group marker 0x4E should be present in the type section
		assertThat(containsByte(wasm, (byte) 0x4E)).isTrue();
	}

	@Test
	void wasmContainsExportSection() {
		byte[] wasm = compile("(print 1)");
		String wasmStr = new String(wasm);
		assertThat(wasmStr).contains("memory");
		assertThat(wasmStr).contains("_start");
	}

	@Test
	void wasmContainsImportSection() {
		byte[] wasm = compile("(print 1)");
		String wasmStr = new String(wasm);
		assertThat(wasmStr).contains("wasi_snapshot_preview1");
	}

	@Test
	void wasmContainsGcInstructions() {
		byte[] wasm = compile("(+ 1 2)");
		// GC prefix byte 0xFB should be present (for i31.get_s, ref.i31, etc.)
		assertThat(containsByte(wasm, (byte) 0xFB)).isTrue();
	}

	@Test
	void wasmArithmeticProducesValidBinary() {
		byte[] wasm = compile("(print (+ 1 2))");
		assertThat(wasm).isNotEmpty();
		assertThat(wasm.length).isGreaterThan(8);
	}

	@Test
	void wasmNestedArithmeticProducesValidBinary() {
		byte[] wasm = compile("(print (* 3 (+ 1 2)))");
		assertThat(wasm).isNotEmpty();
		assertThat(wasm.length).isGreaterThan(8);
	}

	@Test
	void wasmIfProducesValidBinary() {
		byte[] wasm = compile("(print (if t 1 2))");
		assertThat(wasm).isNotEmpty();
		assertThat(wasm.length).isGreaterThan(8);
	}

	private boolean containsSectionId(byte[] wasm, int sectionId) {
		// Section IDs appear after the 8-byte header
		for (int i = 8; i < wasm.length; i++) {
			if ((wasm[i] & 0xFF) == sectionId) {
				return true;
			}
		}
		return false;
	}

	private boolean containsByte(byte[] wasm, byte target) {
		for (byte b : wasm) {
			if (b == target) {
				return true;
			}
		}
		return false;
	}

}
