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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.YamlResources;
import org.jspecify.annotations.Nullable;
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

	/**
	 * A case that cannot join the shared corpus because running it ENDS the program: an
	 * uncaught condition takes the process down, and its report goes to standard error,
	 * which the concatenated run neither slices nor keeps. Each one is compiled and run
	 * on its own, per backend -- the ci-spec.yaml {@code standalone:} idea. A case also
	 * stands alone when it must be read against a {@code --scheme-standard} other than
	 * the corpus's default: {@code standards} lists every standard it runs under (the
	 * default alone when absent), and each one must print the same. {@code stdin} is fed
	 * to every leg, like a corpus case's. A case that {@code include}s a file or imports
	 * a library file stands alone too: {@code files} maps each such file, relative to the
	 * program, to its text, and every leg reads the program from a directory holding
	 * them.
	 */
	record Standalone(String name, String source, String stdin, String stdout, String stderr, Boolean fails,
			List<String> standards, Map<String, String> files) {

		String stdinOrEmpty() {
			return this.stdin == null ? "" : this.stdin;
		}

		List<String> standardsOrDefault() {
			return this.standards == null ? List.of("rontolisp") : this.standards;
		}

		List<String> stdoutLines() {
			return splitLines(this.stdout == null ? "" : this.stdout);
		}

		List<String> stderrLines() {
			return splitLines(this.stderr == null ? "" : this.stderr);
		}

		boolean failsExpected() {
			return Boolean.TRUE.equals(this.fails);
		}

	}

	record Spec(List<Case> cases, List<Standalone> standalone) {

		List<Standalone> standaloneCases() {
			return this.standalone == null ? List.of() : this.standalone;
		}

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
	 * The spec's {@code standalone:} list: one program per case, per backend -- a case
	 * whose program ENDS (an uncaught condition takes the process down, and its report
	 * goes to standard error, which the concatenated run neither slices nor keeps).
	 */
	@TestFactory
	Stream<DynamicNode> standalone() throws Exception {
		Spec spec = loadSpec();
		List<DynamicNode> legs = new ArrayList<>();
		for (Standalone s : spec.standaloneCases()) {
			for (String standard : s.standardsOrDefault()) {
				String name = s.name() + " [" + standard + "]";
				legs.add(dynamicTest(name + " INTERPRETER", () -> runStandaloneInterpreter(s, standard)));
				legs.add(dynamicTest(name + " JVM", () -> runStandaloneJvm(s, standard)));
				for (boolean component : List.of(false, true)) {
					legs.add(dynamicTest(name + (component ? " WASM_COMPONENT" : " WASM"), () -> {
						if (!HostWasmtime.isAvailable()) {
							abort("no usable wasmtime on PATH");
						}
						runStandaloneWasm(s, standard, component);
					}));
				}
			}
		}
		return legs.stream();
	}

	private static void runStandaloneInterpreter(Standalone s, String standard) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Throwable[] thrown = new Throwable[1];
		onAProgramStack(() -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8),
					new java.io.ByteArrayInputStream(s.stdinOrEmpty().getBytes(StandardCharsets.UTF_8)));
			SourceStandards standards = SourceStandards.parse(standard);
			evaluator.setSourceStandards(standards);
			try {
				String entry = entryFile(s, standard, "interpreter");
				for (LispVal form : SourceLanguage.SCHEME.read(s.source(), Features.INTERPRETER,
						entry != null ? entry : "standalone.scm", standards, SourceLoader.fileSystem())) {
					evaluator.eval(form);
				}
			}
			catch (Throwable ex) {
				thrown[0] = ex;
			}
			return null;
		});
		String where = "standalone case '%s' [%s] on INTERPRETER%n--- source ---%n%s--- end source ---"
			.formatted(s.name(), standard, s.source());
		assertThat(splitLines(out.toString(StandardCharsets.UTF_8))).as("%s", where)
			.containsExactlyElementsOf(s.stdoutLines());
		if (s.failsExpected()) {
			assertThat(thrown[0]).as("%s: expected a failure", where).isNotNull();
			String message = String.valueOf(thrown[0].getMessage());
			for (String line : s.stderrLines()) {
				// The interpreter throws the bare message; the compiled backends prefix
				// it with "Unhandled condition: " -- strip that for the comparison so
				// one expectation covers all four.
				String bare = line.startsWith("Unhandled condition: ")
						? line.substring("Unhandled condition: ".length()) : line;
				assertThat(message).as("%s", where).contains(bare);
			}
		}
		else {
			assertThat(thrown[0]).as("%s: unexpected failure %s", where, thrown[0]).isNull();
		}
	}

	private static void runStandaloneJvm(Standalone s, String standard) throws Exception {
		String stem = "SStandalone" + s.name().replaceAll("[^A-Za-z0-9]", "") + standard.replaceAll("[^A-Za-z0-9]", "");
		Path dir = workDir.resolve(stem);
		Files.createDirectories(dir);
		Files.write(dir.resolve(stem + ".class"),
				new JvmSourceCompiler(stem).sourceLanguage("scheme")
					.schemeStandard(standard)
					.compile(s.source(), entryFile(s, standard, "jvm"))
					.classBytes());
		Path outFile = Files.createTempFile(workDir, stem + "-jvm", ".out");
		Path errFile = Files.createTempFile(workDir, stem + "-jvm", ".err");
		Path inFile = Files.createTempFile(workDir, stem + "-jvm", ".in");
		Files.writeString(inFile, s.stdinOrEmpty(), StandardCharsets.UTF_8);
		try {
			Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-cp", dir.toString(), stem)
				.redirectInput(inFile.toFile())
				.redirectOutput(outFile.toFile())
				.redirectError(errFile.toFile())
				.start();
			if (!process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor();
				throw new IllegalStateException("java command timed out: " + stem);
			}
			String stdout = Files.readString(outFile, StandardCharsets.UTF_8);
			String stderr = Files.readString(errFile, StandardCharsets.UTF_8);
			String where = "standalone case '%s' [%s] on JVM%n--- source ---%n%s--- end source ---%n--- stderr ---%n%s"
				.formatted(s.name(), standard, s.source(), stderr);
			assertThat(splitLines(stdout)).as("%s", where).containsExactlyElementsOf(s.stdoutLines());
			for (String line : s.stderrLines()) {
				assertThat(splitLines(stderr)).as("%s", where).contains(line);
			}
			if (s.failsExpected()) {
				assertThat(process.exitValue()).as("%s: expected a non-zero exit", where).isNotZero();
			}
			else {
				assertThat(process.exitValue()).as("%s", where).isZero();
			}
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
			Files.deleteIfExists(inFile);
		}
	}

	private static void runStandaloneWasm(Standalone s, String standard, boolean component) throws Exception {
		HostWasmtime.ExecResult result = runWasmModule(s.source(),
				entryFile(s, standard, component ? "component" : "wasm"), s.stdinOrEmpty(), component,
				"standalone-" + s.name().replaceAll("[^A-Za-z0-9]", "") + "-" + standard, standard);
		String leg = component ? "WASM_COMPONENT" : "WASM";
		String where = "standalone case '%s' [%s] on %s%n--- source ---%n%s--- end source ---%n--- stderr ---%n%s"
			.formatted(s.name(), standard, leg, s.source(), result.stderr());
		assertThat(splitLines(result.stdout())).as("%s", where).containsExactlyElementsOf(s.stdoutLines());
		for (String line : s.stderrLines()) {
			assertThat(splitLines(result.stderr())).as("%s", where).contains(line);
		}
		if (s.failsExpected()) {
			assertThat(result.exitCode()).as("%s: expected a non-zero exit", where).isNotZero();
		}
		else {
			assertThat(result.exitCode()).as("%s: %s", where, result.stderr()).isZero();
		}
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
					HostWasmtime.ExecResult result = runWasmModule(source, "", component, "exit-" + expected,
							"rontolisp");
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
				HostWasmtime.ExecResult result = runWasmModule(abrupt, "", component, "emergency-exit-7", "rontolisp");
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
				HostWasmtime.ExecResult result = runWasmModule(program, stdin, component, "scheme-driver-loop",
						"rontolisp");
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
		HostWasmtime.ExecResult result = runWasmModule(program, stdin, component, "scheme-spec", "rontolisp");
		assertThat(result.exitCode()).as("wasmtime exit code (component=%s): %s", component, result.stderr()).isZero();
		return result.stdout();
	}

	private static HostWasmtime.ExecResult runWasmModule(String program, String stdin, boolean component, String name,
			String standard) throws Exception {
		return runWasmModule(program, null, stdin, component, name, standard);
	}

	private static HostWasmtime.ExecResult runWasmModule(String program, @Nullable String entryFile, String stdin,
			boolean component, String name, String standard) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.scheme(program, entryFile, true, component,
				standard);
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
			// --dir /tmp: the (scheme file) cases open files there, and an absolute path
			// resolves against the preopen that covers it (.kb/read-load-streams.md).
			Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", "/tmp",
					path)
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

	// Writes a case's files, and the program itself, into a directory of this leg's own
	// and answers the program's path there; null for a case that names no file.
	private static @Nullable String entryFile(Standalone s, String standard, String leg) throws IOException {
		if (s.files() == null) {
			return null;
		}
		Path dir = workDir.resolve("files-" + s.name().replaceAll("[^A-Za-z0-9]", "") + "-" + standard + "-" + leg);
		for (Map.Entry<String, String> file : s.files().entrySet()) {
			Path path = dir.resolve(file.getKey());
			Files.createDirectories(path.getParent());
			Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
		}
		Path entry = dir.resolve("standalone.scm");
		Files.writeString(entry, s.source(), StandardCharsets.UTF_8);
		return entry.toString();
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
