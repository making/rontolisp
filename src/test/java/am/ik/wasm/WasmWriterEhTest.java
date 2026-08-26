package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Encoding tests for the exception-handling additions: the tag section (id 13) and the
 * {@code throw} / {@code try_table} instructions. A minimal hand-assembled module throws
 * an i32-carrying exception inside a {@code try_table} whose {@code catch} clause
 * branches the payload out to an enclosing block, so the exported function returns the
 * thrown value. The byte-level assertions pin the encoding; when {@code wasm-tools} /
 * {@code wasmtime} are on the {@code PATH} the module is additionally validated and
 * executed.
 */
class WasmWriterEhTest {

	@TempDir
	Path tempDir;

	/**
	 * Builds: (module (type (func (param i32))) (type (func (result i32))) (tag 0) (func
	 * (type 1) (block (result i32) (try_table (result i32) (catch 0 0) (i32.const 7)
	 * (throw 0)))) (export "main" (func 0))).
	 */
	private static byte[] buildThrowCatchModule() {
		// Function body: block (result i32) / try_table (result i32) (catch tag=0
		// label=0) / i32.const 7 / throw 0 / end / end / end. The catch clause's label
		// is resolved without the try_table's own label, so label 0 is the enclosing
		// block; catching pushes the tag's i32 payload and branches there.
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		WasmWriter body = new WasmWriter(bodyStream);
		body.write(0); // no locals
		body.write(Instruction.BLOCK, Type.I32.code());
		body.write(Instruction.TRY_TABLE, Type.I32.code());
		body.writeUnsignedLeb128(1); // one catch clause
		body.write(Instruction.CATCH);
		body.writeUnsignedLeb128(0); // tag 0
		body.writeUnsignedLeb128(0); // label 0 = the enclosing block
		body.write(Instruction.I32_CONST);
		body.writeSignedLeb128(7);
		body.write(Instruction.THROW);
		body.writeUnsignedLeb128(0);
		body.write(Instruction.END); // try_table
		body.write(Instruction.END); // block
		body.write(Instruction.END); // function

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm")
			.writeLittleEndian4(1)
			.writeTypeSection(types -> types.addFunc(new Type[] { Type.I32 }, new Type[] {})
				.addFunc(new Type[] {}, new Type[] { Type.I32 }))
			.writeFunction(functions -> functions.addFunction(1))
			.writeTagSection(tags -> tags.addTag(0))
			.writeExport(exports -> exports.addExport("main", ExternalKind.FUNCTION, 0))
			.writeCode(code -> code.addFunction(bodyStream.toByteArray()));
		return out.toByteArray();
	}

	@Test
	void tagSectionEncoding() {
		byte[] module = buildThrowCatchModule();
		// The tag section: id 13, size 3, count 1, attribute 0x00, type index 0.
		byte[] expectedTagSection = { 13, 3, 1, 0x00, 0 };
		assertThat(indexOf(module, expectedTagSection)).as("tag section bytes present").isGreaterThanOrEqualTo(0);
	}

	@Test
	void ehOpcodeValues() {
		// Pin the opcode/immediate values against the exception-handling proposal.
		assertThat(Instruction.THROW).isEqualTo(0x08);
		assertThat(Instruction.THROW_REF).isEqualTo(0x0A);
		assertThat(Instruction.TRY_TABLE).isEqualTo(0x1F);
		assertThat(Instruction.CATCH).isEqualTo(0x00);
		assertThat(Instruction.CATCH_REF).isEqualTo(0x01);
		assertThat(Instruction.CATCH_ALL).isEqualTo(0x02);
		assertThat(Instruction.CATCH_ALL_REF).isEqualTo(0x03);
		assertThat(Type.EXNREF.code()).isEqualTo(0x69);
	}

	@Test
	void throwCatchModuleValidates() throws Exception {
		assumeTrue(onPath("wasm-tools"), "wasm-tools not on PATH; skipping validation");
		Path file = this.tempDir.resolve("eh.wasm");
		Files.write(file, buildThrowCatchModule());
		Process process = new ProcessBuilder("wasm-tools", "validate", "-f", "exceptions", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes());
		int exit = process.waitFor();
		assertThat(exit).as("wasm-tools validate failed:%n%s", output).isZero();
	}

	@Test
	void throwCatchModuleRunsUnderWasmtime() throws Exception {
		assumeTrue(onPath("wasmtime"), "wasmtime not on PATH; skipping execution");
		Path file = this.tempDir.resolve("eh.wasm");
		Files.write(file, buildThrowCatchModule());
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "exceptions=y", "--invoke", "main",
				file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes());
		int exit = process.waitFor();
		assertThat(exit).as("wasmtime run failed:%n%s", output).isZero();
		assertThat(output).contains("7");
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static boolean onPath(String command) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir, command))) {
				return true;
			}
		}
		return false;
	}

}
