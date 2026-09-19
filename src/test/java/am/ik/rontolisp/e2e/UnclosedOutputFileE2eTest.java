package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.testsupport.HostWasmtime;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * An output file stream the program never closes keeps what it wrote when the program
 * ends -- normally, through {@code uiop:quit} / Scheme {@code exit}, or on an uncaught
 * condition -- on all four backends, as C stdio and Gauche do. The spec corpora compare
 * standard output only, so each leg runs the program to its end and then reads the file.
 */
class UnclosedOutputFileE2eTest {

	@TempDir
	static Path workDir;

	/**
	 * One program: its extension picks the language, {@code FILE} stands for the absolute
	 * path it writes, and the expected file content and exit status follow.
	 */
	private record Case(String name, String extension, String source, String content, int exit) {

		void assertExit(int actual) {
			if (this.exit == FAILS) {
				assertThat(actual).as(this.name).isNotZero();
			}
			else {
				assertThat(actual).as(this.name).isEqualTo(this.exit);
			}
		}

	}

	/**
	 * The exit status of an uncaught condition: 1 on the JVM, the trap's 134 under
	 * wasmtime, so "not zero" is what the backends share.
	 */
	private static final int FAILS = -1;

	private static final List<Case> CASES = List.of(new Case("cl-end", "lisp", """
			(defvar *s* (open "FILE" :direction :output :if-exists :supersede))
			(write-string "hello" *s*)
			""", "hello", 0), new Case("cl-quit", "lisp", """
			(defvar *s* (open "FILE" :direction :output :if-exists :supersede))
			(write-string "hello" *s*)
			(uiop:quit 3)
			""", "hello", 3), new Case("cl-error", "lisp", """
			(defvar *s* (open "FILE" :direction :output :if-exists :supersede))
			(write-string "hello" *s*)
			(error "boom")
			""", "hello", FAILS), new Case("cl-with-open-file-quit", "lisp", """
			(with-open-file (s "FILE" :direction :output :if-exists :supersede)
			  (write-string "hello" s)
			  (uiop:quit 3))
			""", "hello", 3), new Case("cl-binary", "lisp", """
			(defvar *s* (open "FILE" :direction :output :if-exists :supersede
			                  :element-type '(unsigned-byte 8)))
			(write-byte 65 *s*)
			(write-byte 66 *s*)
			""", "AB", 0), new Case("scheme-end", "scm", """
			(define p (open-output-file "FILE"))
			(write-string "hello" p)
			""", "hello", 0), new Case("scheme-exit", "scm", """
			(define p (open-output-file "FILE"))
			(write-string "hello" p)
			(exit 3)
			""", "hello", 3), new Case("scheme-error", "scm", """
			(define p (open-output-file "FILE"))
			(write-string "hello" p)
			(error "boom")
			""", "hello", FAILS));

	@TestFactory
	Stream<DynamicNode> anUnclosedOutputFileKeepsItsContentOnEveryBackend() {
		List<DynamicNode> legs = new ArrayList<>();
		for (Case c : CASES) {
			legs.add(dynamicTest(c.name() + " INTERPRETER", () -> runInterpreter(c)));
			legs.add(dynamicTest(c.name() + " JVM", () -> runJvm(c)));
			for (boolean component : List.of(false, true)) {
				legs.add(dynamicTest(c.name() + (component ? " WASM_COMPONENT" : " WASM"), () -> {
					if (!HostWasmtime.isAvailable()) {
						abort("no usable wasmtime on PATH");
					}
					runWasm(c, component);
				}));
			}
		}
		return legs.stream();
	}

	private static void runInterpreter(Case c) throws Exception {
		Path dir = Files.createDirectories(workDir.resolve(c.name() + "-interpreter"));
		Path out = dir.resolve("out.txt");
		Path src = writeSource(c, dir, out);
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		int exit = 0;
		try {
			cli.run(new String[] { src.toString() });
		}
		catch (LispExitSignal signal) {
			exit = signal.code();
		}
		catch (RuntimeException ex) {
			exit = 1;
		}
		c.assertExit(exit);
		assertThat(Files.readString(out, StandardCharsets.UTF_8)).as(c.name()).isEqualTo(c.content());
	}

	private static void runJvm(Case c) throws Exception {
		Path dir = Files.createDirectories(workDir.resolve(c.name() + "-jvm"));
		Path out = dir.resolve("out.txt");
		Path src = writeSource(c, dir, out);
		compile(src, dir.resolve("Prog.class"));
		int exit = exec(dir, Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", dir.toString(),
				"Prog");
		c.assertExit(exit);
		assertThat(Files.readString(out, StandardCharsets.UTF_8)).as(c.name()).isEqualTo(c.content());
	}

	private static void runWasm(Case c, boolean component) throws Exception {
		Path dir = Files.createDirectories(workDir.resolve(c.name() + (component ? "-component" : "-wasm")));
		Path out = dir.resolve("out.txt");
		Path src = writeSource(c, dir, out);
		Path module = dir.resolve("prog.wasm");
		if (component) {
			compile(src, module, "--component");
		}
		else {
			compile(src, module);
		}
		int exit = exec(dir, "wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", dir.toString(),
				module.toString());
		c.assertExit(exit);
		assertThat(Files.readString(out, StandardCharsets.UTF_8)).as(c.name()).isEqualTo(c.content());
	}

	private static Path writeSource(Case c, Path dir, Path out) throws Exception {
		Path src = dir.resolve("prog." + c.extension());
		Files.writeString(src, c.source().replace("FILE", out.toString()), StandardCharsets.UTF_8);
		return src;
	}

	private static void compile(Path src, Path output, String... flags) {
		List<String> args = new ArrayList<>(List.of(src.toString(), "-o", output.toString()));
		args.addAll(List.of(flags));
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			.run(args.toArray(String[]::new));
	}

	private static int exec(Path dir, String... command) throws Exception {
		Process process = new ProcessBuilder(command).directory(dir.toFile())
			.redirectOutput(dir.resolve("stdout.log").toFile())
			.redirectError(dir.resolve("stderr.log").toFile())
			.start();
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("timed out: " + String.join(" ", command));
		}
		return process.exitValue();
	}

}
