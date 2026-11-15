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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.ThreadStdio;
import am.ik.rontolisp.testsupport.YamlResources;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Runs the corpus of the EXPERIMENTAL Starlark front end ({@code starlark-spec.yaml}) on
 * the interpreter, the JVM, wasm and the component, and requires the same output of all
 * four. The {@code ci-spec.yaml} idea: the cases are concatenated into ONE {@code .star}
 * program per backend (they share its globals and run in order) and the output is sliced
 * back per case, so a failure names its case and its backend. A {@code standalone:} case
 * ENDS its program with an uncaught error, so each one is compiled and run on its own,
 * per backend: it prints {@code stdout}, then fails with {@code error}, which the
 * interpreter throws and a compiled program reports first on standard error.
 *
 * <p>
 * Unlike {@code CiSpecE2eTest} this needs no native binary: every leg goes through the
 * same front end the CLI runs ({@code SourceLanguage.STARLARK},
 * {@code JvmSourceCompiler}, {@code CompileFrontendAccess}), in process, so it is part of
 * {@code ./mvnw test}. The two wasm legs need a {@code wasmtime} on {@code PATH} and are
 * skipped without one.
 */
@Execution(ExecutionMode.CONCURRENT)
class StarlarkSpecE2eTest {

	private static final String SPEC_RESOURCE = "/starlark-spec.yaml";

	private static final String UNHANDLED = "Unhandled condition: ";

	record Case(String name, String source, String expected) {

		List<String> expectedLines() {
			return splitLines(this.expected);
		}

	}

	record Standalone(String name, String source, String stdout, String error) {

		String stem() {
			return "Standalone" + this.name.replaceAll("[^A-Za-z0-9]", "");
		}

	}

	record Spec(List<Case> cases, @Nullable List<Standalone> standalone) {

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
		boolean wasm = HostWasmtime.isAvailable();
		Callable<String> interpreter = started(() -> interpret(program));
		Callable<String> jvm = started(() -> runOnJvm(program));
		Callable<String> wasmPreview1 = wasm ? started(() -> runOnWasm(program, false)) : null;
		Callable<String> wasmComponent = wasm ? started(() -> runOnWasm(program, true)) : null;
		return Stream.of(backend("INTERPRETER", spec.cases(), interpreter), backend("JVM", spec.cases(), jvm),
				backend("WASM", spec.cases(), wasmPreview1), backend("WASM_COMPONENT", spec.cases(), wasmComponent));
	}

	@TestFactory
	Stream<DynamicNode> standalone() throws Exception {
		List<DynamicNode> legs = new ArrayList<>();
		List<Standalone> cases = loadSpec().standalone();
		for (Standalone s : cases == null ? List.<Standalone>of() : cases) {
			legs.add(dynamicTest(s.name() + " INTERPRETER", () -> standaloneOnInterpreter(s)));
			legs.add(dynamicTest(s.name() + " JVM", () -> standaloneOnJvm(s)));
			for (boolean component : List.of(false, true)) {
				legs.add(dynamicTest(s.name() + (component ? " WASM_COMPONENT" : " WASM"), () -> {
					if (!HostWasmtime.isAvailable()) {
						abort("no usable wasmtime on PATH");
					}
					Path module = compileWasm(s.source(), component, s.stem());
					Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
							module.toString())
						.start();
					assertEnded(s, component ? "WASM_COMPONENT" : "WASM", process);
				}));
			}
		}
		return legs.stream();
	}

	private static void standaloneOnInterpreter(Standalone s) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Throwable[] thrown = new Throwable[1];
		CliStack.call("starlark-spec", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			try {
				for (LispVal form : SourceLanguage.STARLARK.read(s.source(), Features.INTERPRETER, null)) {
					evaluator.eval(form);
				}
			}
			catch (Throwable ex) {
				thrown[0] = ex;
			}
			return null;
		});
		String where = where(s, "INTERPRETER", "");
		assertThat(out.toString(StandardCharsets.UTF_8)).as("%s", where).isEqualTo(s.stdout());
		assertThat(thrown[0]).as("%s: expected a failure", where).isNotNull().hasMessage(s.error());
	}

	// The program dies, so it runs in a process of its own: with the class and the
	// runtime classes travelling beside it in its class path directory, as the CLI
	// writes them.
	private static void standaloneOnJvm(Standalone s) throws Exception {
		Path dir = workDir.resolve(s.stem());
		Files.createDirectories(dir);
		JvmSourceCompiler.Result compiled = new JvmSourceCompiler(s.stem()).sourceLanguage("starlark")
			.compile(s.source(), null);
		Files.write(dir.resolve(s.stem() + ".class"), compiled.classBytes());
		for (Map.Entry<String, byte[]> runtime : compiled.runtimeClasses().entrySet()) {
			Path file = dir.resolve(runtime.getKey());
			Files.createDirectories(file.getParent());
			Files.write(file, runtime.getValue());
		}
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				dir.toString(), s.stem())
			.start();
		assertEnded(s, "JVM", process);
	}

	private static void assertEnded(Standalone s, String leg, Process process) throws Exception {
		process.getOutputStream().close();
		Path errors = Files.createTempFile(workDir, s.stem(), ".err");
		Thread drain = Thread.ofPlatform().start(() -> {
			try (InputStream err = process.getErrorStream()) {
				Files.write(errors, err.readAllBytes());
			}
			catch (IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
		});
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(300, TimeUnit.SECONDS)).as("%s on %s timed out", s.name(), leg).isTrue();
		drain.join();
		String stderr = Files.readString(errors, StandardCharsets.UTF_8);
		String where = where(s, leg, stderr);
		assertThat(stdout).as("%s", where).isEqualTo(s.stdout());
		// the report first; what the host adds about the dead process follows
		assertThat(splitLines(stderr)).as("%s", where).first().isEqualTo(UNHANDLED + s.error());
		assertThat(process.exitValue()).as("%s: expected a non-zero exit", where).isNotZero();
	}

	private static String where(Standalone s, String leg, String stderr) {
		return "standalone case '%s' on %s%n--- source ---%n%s--- end source ---%n--- stderr ---%n%s"
			.formatted(s.name(), leg, s.source(), stderr);
	}

	// Starts run on a thread of its own and answers its result, rethrowing what it threw.
	private static Callable<String> started(Callable<String> run) {
		FutureTask<String> task = new FutureTask<>(run);
		Thread.ofPlatform().name("starlark-spec-corpus").start(task);
		return () -> {
			try {
				return task.get();
			}
			catch (ExecutionException ex) {
				if (ex.getCause() instanceof Exception cause) {
					throw cause;
				}
				if (ex.getCause() instanceof Error cause) {
					throw cause;
				}
				throw ex;
			}
		};
	}

	// run is null when there is no wasmtime to run it on.
	private static DynamicContainer backend(String leg, List<Case> cases, @Nullable Callable<String> run) {
		if (run == null) {
			return dynamicContainer(leg,
					Stream.of(dynamicTest("(skipped)", () -> abort("no usable wasmtime on PATH"))));
		}
		List<String> actual;
		try {
			actual = splitLines(run.call());
		}
		catch (Exception | StackOverflowError ex) {
			return dynamicContainer(leg, Stream.of(dynamicTest("(execution failed)", () -> fail(leg + ": " + ex, ex))));
		}
		List<DynamicTest> tests = new ArrayList<>();
		int expectedTotal = cases.stream().mapToInt(c -> c.expectedLines().size()).sum();
		tests.add(dynamicTest("total-line-count",
				() -> assertThat(actual).as("%s produced a different number of output lines than the spec", leg)
					.hasSize(expectedTotal)));
		int offset = 0;
		for (Case c : cases) {
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
		CliStack.call("starlark-spec", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.STARLARK.read(program, Features.INTERPRETER, "spec.star")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program) throws Exception {
		String name = "StarlarkSpec";
		byte[] classBytes = new JvmSourceCompiler(name).sourceLanguage("starlark").compile(program, null).classBytes();
		ClassLoader loader = new ClassLoader(StarlarkSpecE2eTest.class.getClassLoader()) {
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
		try (ThreadStdio.Scope _ = ThreadStdio.out(out)) {
			CliStack.call("starlark-spec", () -> main.invoke(null, (Object) new String[0]));
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(String program, boolean component) throws Exception {
		Path module = compileWasm(program, component, "starlark-spec");
		Path outFile = Files.createTempFile(workDir, "starlark-spec", ".out");
		Path errFile = Files.createTempFile(workDir, "starlark-spec", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", module.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("wasmtime command timed out: " + module);
		}
		assertThat(process.exitValue())
			.as("wasmtime exit code (component=%s): %s", component, Files.readString(errFile, StandardCharsets.UTF_8))
			.isZero();
		return Files.readString(outFile, StandardCharsets.UTF_8);
	}

	private static Path compileWasm(String program, boolean component, String name) throws IOException {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.starlark(program, true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = workDir.resolve(name + (component ? ".component.wasm" : ".wasm"));
		Files.write(path, module);
		return path;
	}

	private static Spec loadSpec() throws IOException {
		try (InputStream in = StarlarkSpecE2eTest.class.getResourceAsStream(SPEC_RESOURCE)) {
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
