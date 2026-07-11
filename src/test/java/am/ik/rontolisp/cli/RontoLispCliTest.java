package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RontoLispCliTest {

	@TempDir
	Path tempDir;

	private String runCli(String input, String... args) {
		ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(in, new PrintStream(out));
		cli.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void replEvaluatesExpression() {
		String output = runCli("(+ 1 2)\n");
		assertThat(output).contains("3");
	}

	@Test
	void replWithSimdInterceptsVecKernels() {
		// A vec.lisp defun prints as #<lambda>; the installed Vector API kernel prints
		// as #<function vec:dot>. The surefire JVM has jdk.incubator.vector on the
		// module path, so VecSimd.available() is true here.
		String output = runCli("(vec:dot #d(1.0) #d(1.0)) #'vec:dot\n", "--simd");
		assertThat(output).contains("#<function vec:dot>");
	}

	@Test
	void replWithoutSimdKeepsScalarVecKernels() {
		String output = runCli("(vec:dot #d(1.0) #d(1.0)) #'vec:dot\n");
		assertThat(output).contains("#<lambda>").doesNotContain("#<function vec:dot>");
	}

	@Test
	void interpretFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		String output = runCli("", file.toString());
		assertThat(output).contains("3");
	}

	@Test
	void compileToClassFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		Path classFile = tempDir.resolve("Test.class");
		runCli("", file.toString(), "-o", classFile.toString());
		assertThat(Files.exists(classFile)).isTrue();
		byte[] bytes = Files.readAllBytes(classFile);
		// Check class file magic number
		assertThat(bytes[0]).isEqualTo((byte) 0xCA);
		assertThat(bytes[1]).isEqualTo((byte) 0xFE);
		assertThat(bytes[2]).isEqualTo((byte) 0xBA);
		assertThat(bytes[3]).isEqualTo((byte) 0xBE);
	}

	@Test
	void compileToWasmFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		Path wasmFile = tempDir.resolve("test.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString());
		assertThat(Files.exists(wasmFile)).isTrue();
		byte[] bytes = Files.readAllBytes(wasmFile);
		// Check WASM magic number
		assertThat(bytes[0]).isEqualTo((byte) 0x00);
		assertThat(bytes[1]).isEqualTo((byte) 'a');
		assertThat(bytes[2]).isEqualTo((byte) 's');
		assertThat(bytes[3]).isEqualTo((byte) 'm');
	}

	@Test
	void helpOption() {
		String output = runCli("", "-h");
		assertThat(output).contains("Usage:");
		assertThat(output).contains("rontolisp");
	}

	@Test
	void versionOption() {
		String output = runCli("", "-v");
		assertThat(output).contains("\"version\":");
		assertThat(output).contains("\"buildTimestamp\":");
		assertThat(output).contains("\"gitCommit\":");
	}

	@Test
	void interpretFileUsingPackages() throws Exception {
		Path file = tempDir.resolve("pkg.lisp");
		Files.writeString(file, """
				(print *package*)
				(in-package :rontolisp)
				(cl:print (cl:car (version)))
				""");
		String output = runCli("", file.toString());
		assertThat(output).contains("cl-user").contains(":version");
	}

}
