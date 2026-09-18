package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.YamlResources;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Runs the corpus of the EXPERIMENTAL Scheme front end ({@code scheme-spec.yaml}) on the
 * interpreter, the JVM, wasm and the component, and requires the same output of all four.
 * The {@code ci-spec.yaml} idea: the cases are concatenated into ONE {@code .scm} program
 * per backend (they share global state and run in order) and the output is sliced back
 * per case, so a failure names its case and its backend.
 *
 * <p>
 * Unlike {@code CiSpecE2eTest} this needs no native binary: every leg goes through the
 * same front end the CLI runs ({@code SourceLanguage.SCHEME}, {@code JvmSourceCompiler},
 * {@code CompileFrontendAccess}), in process, so it is part of {@code ./mvnw test}. The
 * two wasm legs need a {@code wasmtime} on {@code PATH} and are skipped without one.
 */
class SchemeSpecE2eTest {

	private static final String SPEC_RESOURCE = "/scheme-spec.yaml";

	// The CLI hands every program 16 MiB (RontoLispCli.WORKER_STACK_BYTES); the
	// in-process legs measure the same ceiling rather than JUnit's.
	private static final long PROGRAM_STACK_BYTES = 16L << 20;

	record Case(String name, String source, String expected, String stdin) {

		List<String> expectedLines() {
			return splitLines(this.expected);
		}

		String stdinOrEmpty() {
			return this.stdin == null ? "" : this.stdin;
		}

	}

	record Spec(List<Case> cases) {

		String program() {
			StringBuilder program = new StringBuilder();
			for (Case c : this.cases) {
				program.append(c.source());
			}
			return program.toString();
		}

		// The concatenated standard input every leg feeds the program: each case's
		// blob already ends with a newline (a YAML block scalar does), so one case's
		// reads never bleed into the next.
		String stdin() {
			StringBuilder stdin = new StringBuilder();
			for (Case c : this.cases) {
				stdin.append(c.stdinOrEmpty());
			}
			return stdin.toString();
		}

	}

	@TempDir
	static Path workDir;

	@TestFactory
	Stream<DynamicNode> e2e() throws Exception {
		Spec spec = loadSpec();
		String program = spec.program();
		String stdin = spec.stdin();
		List<DynamicNode> backends = new ArrayList<>();
		backends.add(backend("INTERPRETER", spec, () -> interpret(program, stdin)));
		backends.add(backend("JVM", spec, () -> runOnJvm(program, stdin)));
		backends.add(wasmBackend("WASM", spec, stdin, false));
		backends.add(wasmBackend("WASM_COMPONENT", spec, stdin, true));
		return backends.stream();
	}

	/**
	 * {@code exit} cannot be a corpus case: it ends the concatenated program, and the JVM
	 * leg runs {@code main} in THIS process. So one program per status, the JVM leg in a
	 * child process.
	 */
	@TestFactory
	Stream<DynamicNode> exitEndsTheProcessWithItsStatusOnEveryBackend() {
		String program = """
				(display "before") (newline)
				(dynamic-wind (lambda () #t) (lambda () (exit %s)) (lambda () (display "after")))
				(display "never")
				""";
		List<DynamicNode> legs = new ArrayList<>();
		for (String[] status : List.of(new String[] { "7", "7" }, new String[] { "#f", "1" }, new String[] { "", "0" },
				new String[] { "300", "44" })) {
			String source = program.formatted(status[0]);
			int expected = Integer.parseInt(status[1]);
			String name = "(exit " + status[0] + ")";
			legs.add(dynamicTest(name + " INTERPRETER", () -> {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
				assertThatThrownBy(() -> {
					for (LispVal form : SourceLanguage.SCHEME.read(source, Features.INTERPRETER, "exit.scm")) {
						evaluator.eval(form);
					}
				}).isInstanceOfSatisfying(LispExitSignal.class, exit -> assertThat(exit.code()).isEqualTo(expected));
				assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("before\nafter");
			}));
			legs.add(dynamicTest(name + " JVM", () -> {
				Path dir = workDir.resolve("exit-" + expected);
				Files.createDirectories(dir);
				Files.write(dir.resolve("SchemeExit.class"),
						new JvmSourceCompiler("SchemeExit").sourceLanguage("scheme")
							.compile(source, null)
							.classBytes());
				Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
						"-cp", dir.toString(), "SchemeExit")
					.redirectErrorStream(true)
					.start();
				String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertThat(process.waitFor()).isEqualTo(expected);
				assertThat(out).isEqualTo("before\nafter");
			}));
			for (boolean component : List.of(false, true)) {
				legs.add(dynamicTest(name + (component ? " WASM_COMPONENT" : " WASM"), () -> {
					if (!HostWasmtime.isAvailable()) {
						abort("no usable wasmtime on PATH");
					}
					HostWasmtime.ExecResult result = runWasmModule(source, "", component, "exit-" + expected);
					assertThat(result.exitCode()).isEqualTo(expected);
					assertThat(result.stdout()).isEqualTo("before\nafter");
				}));
			}
		}
		// emergency-exit alone may skip the afters: the same program with it prints no
		// "after" on any backend.
		String abrupt = """
				(display "before") (newline)
				(dynamic-wind (lambda () #t) (lambda () (emergency-exit 7)) (lambda () (display "after")))
				(display "never")
				""";
		legs.add(dynamicTest("(emergency-exit 7) INTERPRETER", () -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			assertThatThrownBy(() -> {
				for (LispVal form : SourceLanguage.SCHEME.read(abrupt, Features.INTERPRETER, "exit.scm")) {
					evaluator.eval(form);
				}
			}).isInstanceOfSatisfying(LispExitSignal.class, exit -> assertThat(exit.code()).isEqualTo(7));
			assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("before\n");
		}));
		legs.add(dynamicTest("(emergency-exit 7) JVM", () -> {
			Path dir = workDir.resolve("emergency-exit-7");
			Files.createDirectories(dir);
			Files.write(dir.resolve("SchemeExit.class"),
					new JvmSourceCompiler("SchemeExit").sourceLanguage("scheme").compile(abrupt, null).classBytes());
			Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-cp", dir.toString(), "SchemeExit")
				.redirectErrorStream(true)
				.start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(process.waitFor()).isEqualTo(7);
			assertThat(out).isEqualTo("before\n");
		}));
		for (boolean component : List.of(false, true)) {
			legs.add(dynamicTest("(emergency-exit 7)" + (component ? " WASM_COMPONENT" : " WASM"), () -> {
				if (!HostWasmtime.isAvailable()) {
					abort("no usable wasmtime on PATH");
				}
				HostWasmtime.ExecResult result = runWasmModule(abrupt, "", component, "emergency-exit-7");
				assertThat(result.exitCode()).isEqualTo(7);
				assertThat(result.stdout()).isEqualTo("before\n");
			}));
		}
		return legs.stream();
	}

	/**
	 * A read-eval-print loop over stdin -- the shape the book's chapter 4 evaluators
	 * share -- fed three expressions through a real stdin pipe (not
	 * {@code with-input-from-string}): what {@code .todo/832} gates for the second stage
	 * of {@code .todo/828} (feeding the {@code embedded-*} samples to the evaluator the
	 * corpus ships).
	 */
	@TestFactory
	Stream<DynamicNode> driverLoopReadsThreeExpressionsFromStdinOnEveryBackend() {
		String program = """
				;; A read-eval-print loop over stdin, the shape the book's chapter 4
				;; evaluators share: prompt, read a datum, eval it in the global
				;; environment, print the value, and loop. An end of input ends the
				;; loop; three expressions piped in answer three values.
				;; Spells the operators the piped expressions use, so a compiled
				;; program's eval table holds them (it holds only spelled names).
				(define eval-operators '(+ car *))
				(define (prompt-for-input string)
				  (newline) (newline) (display string) (newline))
				(define (announce-output string)
				  (newline) (display string) (newline))
				(define (user-print object)
				  (if (eof-object? object)
				      (display "done")
				      (begin (display object) (newline))))
				(define (driver-loop)
				  (prompt-for-input ";;; M-Eval input:")
				  (let ((input (read)))
				    (cond ((eof-object? input)
				           (announce-output ";;; M-Eval done"))
				          (else
				           (let ((output (eval input (interaction-environment))))
				             (announce-output ";;; M-Eval value:")
				             (user-print output)
				             (driver-loop))))))
				(driver-loop)
				""";
		String stdin = "(+ 1 2)\n(car '(a b))\n(* 6 7)\n";
		String expected = """


				;;; M-Eval input:

				;;; M-Eval value:
				3


				;;; M-Eval input:

				;;; M-Eval value:
				a


				;;; M-Eval input:

				;;; M-Eval value:
				42


				;;; M-Eval input:

				;;; M-Eval done
				""";
		List<DynamicNode> legs = new ArrayList<>();
		legs.add(dynamicTest("INTERPRETER", () -> assertThat(interpret(program, stdin)).isEqualTo(expected)));
		legs.add(dynamicTest("JVM", () -> assertThat(runOnJvm(program, stdin)).isEqualTo(expected)));
		for (boolean component : List.of(false, true)) {
			legs.add(dynamicTest(component ? "WASM_COMPONENT" : "WASM", () -> {
				if (!HostWasmtime.isAvailable()) {
					abort("no usable wasmtime on PATH");
				}
				HostWasmtime.ExecResult result = runWasmModule(program, stdin, component, "scheme-driver-loop");
				assertThat(result.exitCode()).as("wasmtime exit code: %s", result.stderr()).isZero();
				assertThat(result.stdout()).isEqualTo(expected);
			}));
		}
		return legs.stream();
	}

	private static DynamicContainer wasmBackend(String leg, Spec spec, String stdin, boolean component) {
		if (!HostWasmtime.isAvailable()) {
			return dynamicContainer(leg,
					Stream.of(dynamicTest("(skipped)", () -> abort("no usable wasmtime on PATH"))));
		}
		return backend(leg, spec, () -> runOnWasm(spec.program(), stdin, component));
	}

	private static DynamicContainer backend(String leg, Spec spec, Callable<String> run) {
		List<String> actual;
		try {
			actual = splitLines(run.call());
		}
		catch (Exception | StackOverflowError ex) {
			return dynamicContainer(leg, Stream.of(dynamicTest("(execution failed)", () -> fail(leg + ": " + ex, ex))));
		}
		List<DynamicTest> tests = new ArrayList<>();
		int expectedTotal = spec.cases().stream().mapToInt(c -> c.expectedLines().size()).sum();
		tests.add(dynamicTest("total-line-count",
				() -> assertThat(actual).as("%s produced a different number of output lines than the spec", leg)
					.hasSize(expectedTotal)));
		int offset = 0;
		for (Case c : spec.cases()) {
			List<String> expected = c.expectedLines();
			int start = Math.min(offset, actual.size());
			List<String> slice = actual.subList(start, Math.min(offset + expected.size(), actual.size()));
			offset += expected.size();
			tests.add(dynamicTest(c.name(),
					() -> assertThat(slice)
						.as("case '%s' on %s%n--- source ---%n%s--- end source ---", c.name(), leg, c.source())
						.containsExactlyElementsOf(expected)));
		}
		return dynamicContainer(leg, tests.stream());
	}

	private static String interpret(String program, String stdin) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] input = stdin.getBytes(StandardCharsets.UTF_8);
		onAProgramStack(() -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8),
					new java.io.ByteArrayInputStream(input));
			for (LispVal form : SourceLanguage.SCHEME.read(program, Features.INTERPRETER, "spec.scm")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program, String stdin) throws Exception {
		String name = "SchemeSpec";
		byte[] classBytes = new JvmSourceCompiler(name).sourceLanguage("scheme").compile(program, null).classBytes();
		ClassLoader loader = new ClassLoader(SchemeSpecE2eTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				if (n.equals(name)) {
					return defineClass(n, classBytes, 0, classBytes.length);
				}
				return super.findClass(n);
			}
		};
		Method main = loader.loadClass(name).getMethod("main", String[].class);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream previousOut = System.out;
		java.io.InputStream previousIn = System.in;
		System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
		System.setIn(new java.io.ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
		try {
			onAProgramStack(() -> main.invoke(null, (Object) new String[0]));
		}
		finally {
			System.setOut(previousOut);
			System.setIn(previousIn);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(String program, String stdin, boolean component) throws Exception {
		HostWasmtime.ExecResult result = runWasmModule(program, stdin, component, "scheme-spec");
		assertThat(result.exitCode()).as("wasmtime exit code (component=%s): %s", component, result.stderr()).isZero();
		return result.stdout();
	}

	private static HostWasmtime.ExecResult runWasmModule(String program, String stdin, boolean component, String name)
			throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.scheme(program, true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		String path = workDir.resolve(name + (component ? ".component.wasm" : ".wasm")).toString();
		HostWasmtime.INSTANCE.copyFileToContainer(Transferable.of(module), path);
		Path stdinFile = workDir.resolve(name + (component ? ".component.stdin" : ".stdin"));
		Files.write(stdinFile, stdin.getBytes(StandardCharsets.UTF_8));
		Path outFile = Files.createTempFile(workDir, name + "-wasm", ".out");
		Path errFile = Files.createTempFile(workDir, name + "-wasm", ".err");
		try {
			Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path)
				.redirectInput(stdinFile.toFile())
				.redirectOutput(outFile.toFile())
				.redirectError(errFile.toFile())
				.start();
			if (!process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor();
				throw new IllegalStateException("wasmtime command timed out: " + path);
			}
			String stdout = Files.readString(outFile, StandardCharsets.UTF_8);
			String stderr = Files.readString(errFile, StandardCharsets.UTF_8);
			return new HostWasmtime.ExecResult(process.exitValue(), stdout, stderr);
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
		}
	}

	// Runs the body on a thread with the CLI's program stack and rethrows what it threw.
	private static void onAProgramStack(Callable<?> body) throws Exception {
		Throwable[] thrown = new Throwable[1];
		Thread worker = new Thread(null, () -> {
			try {
				body.call();
			}
			catch (Throwable ex) {
				thrown[0] = ex;
			}
		}, "scheme-spec", PROGRAM_STACK_BYTES);
		worker.start();
		worker.join();
		if (thrown[0] instanceof Error error) {
			throw error;
		}
		if (thrown[0] instanceof Exception exception) {
			throw exception;
		}
	}

	private static Spec loadSpec() throws IOException {
		try (InputStream in = SchemeSpecE2eTest.class.getResourceAsStream(SPEC_RESOURCE)) {
			if (in == null) {
				throw new IOException("missing test resource: " + SPEC_RESOURCE);
			}
			return new YAMLMapper()
				.readValue(YamlResources.safeReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)), Spec.class);
		}
	}

	private static List<String> splitLines(String text) {
		if (text.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		if (lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

}
