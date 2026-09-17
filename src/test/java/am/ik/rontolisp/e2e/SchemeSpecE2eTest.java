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

	record Case(String name, String source, String expected) {

		List<String> expectedLines() {
			return splitLines(this.expected);
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

	}

	@TempDir
	static Path workDir;

	@TestFactory
	Stream<DynamicNode> e2e() throws Exception {
		Spec spec = loadSpec();
		String program = spec.program();
		List<DynamicNode> backends = new ArrayList<>();
		backends.add(backend("INTERPRETER", spec, () -> interpret(program)));
		backends.add(backend("JVM", spec, () -> runOnJvm(program)));
		backends.add(wasmBackend("WASM", spec, false));
		backends.add(wasmBackend("WASM_COMPONENT", spec, true));
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
				assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("before\n");
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
				assertThat(out).isEqualTo("before\n");
			}));
			for (boolean component : List.of(false, true)) {
				legs.add(dynamicTest(name + (component ? " WASM_COMPONENT" : " WASM"), () -> {
					if (!HostWasmtime.isAvailable()) {
						abort("no usable wasmtime on PATH");
					}
					HostWasmtime.ExecResult result = runWasmModule(source, component, "exit-" + expected);
					assertThat(result.exitCode()).isEqualTo(expected);
					assertThat(result.stdout()).isEqualTo("before\n");
				}));
			}
		}
		return legs.stream();
	}

	private static DynamicContainer wasmBackend(String leg, Spec spec, boolean component) {
		if (!HostWasmtime.isAvailable()) {
			return dynamicContainer(leg,
					Stream.of(dynamicTest("(skipped)", () -> abort("no usable wasmtime on PATH"))));
		}
		return backend(leg, spec, () -> runOnWasm(spec.program(), component));
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

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		onAProgramStack(() -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.SCHEME.read(program, Features.INTERPRETER, "spec.scm")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program) throws Exception {
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
		PrintStream previous = System.out;
		System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
		try {
			onAProgramStack(() -> main.invoke(null, (Object) new String[0]));
		}
		finally {
			System.setOut(previous);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(String program, boolean component) throws Exception {
		HostWasmtime.ExecResult result = runWasmModule(program, component, "scheme-spec");
		assertThat(result.exitCode()).as("wasmtime exit code (component=%s): %s", component, result.stderr()).isZero();
		return result.stdout();
	}

	private static HostWasmtime.ExecResult runWasmModule(String program, boolean component, String name)
			throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.scheme(program, true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		String path = workDir.resolve(name + (component ? ".component.wasm" : ".wasm")).toString();
		HostWasmtime.INSTANCE.copyFileToContainer(Transferable.of(module), path);
		return HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path);
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
