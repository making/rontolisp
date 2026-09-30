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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
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
 * Runs the corpus of the EXPERIMENTAL Clojure front end ({@code clojure-spec.yaml}) on
 * the interpreter, the JVM, wasm and the component, and requires the same output of all
 * four. The {@code ci-spec.yaml} idea: the cases are concatenated into ONE {@code .clj}
 * program per backend (they share global state and run in order) and the output is sliced
 * back per case, so a failure names its case and its backend.
 *
 * <p>
 * Unlike {@code CiSpecE2eTest} this needs no native binary: every leg goes through the
 * same front end the CLI runs ({@code SourceLanguage.CLOJURE}, {@code JvmSourceCompiler},
 * {@code CompileFrontendAccess}), in process, so it is part of {@code ./mvnw test}. The
 * two wasm legs need a {@code wasmtime} on {@code PATH} and are skipped without one.
 */
@Execution(ExecutionMode.CONCURRENT)
class ClojureSpecE2eTest {

	private static final String SPEC_RESOURCE = "/clojure-spec.yaml";

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
		boolean wasm = HostWasmtime.isAvailable();
		Callable<String> interpreter = started(() -> interpret(program));
		Callable<String> jvm = started(() -> runOnJvm(program));
		Callable<String> wasmPreview1 = wasm ? started(() -> runOnWasm(program, false)) : null;
		Callable<String> wasmComponent = wasm ? started(() -> runOnWasm(program, true)) : null;
		List<DynamicNode> backends = new ArrayList<>();
		backends.add(backend("INTERPRETER", spec, interpreter));
		backends.add(backend("JVM", spec, jvm));
		backends.add(wasmBackend("WASM", spec, wasmPreview1));
		backends.add(wasmBackend("WASM_COMPONENT", spec, wasmComponent));
		return backends.stream();
	}

	// run is null when there is no wasmtime to run it on.
	private static DynamicContainer wasmBackend(String leg, Spec spec, @Nullable Callable<String> run) {
		if (run == null) {
			return dynamicContainer(leg,
					Stream.of(dynamicTest("(skipped)", () -> abort("no usable wasmtime on PATH"))));
		}
		return backend(leg, spec, run);
	}

	// Starts run on a thread of its own and answers its result, rethrowing what it threw.
	private static Callable<String> started(Callable<String> run) {
		FutureTask<String> task = new FutureTask<>(run);
		Thread.ofPlatform().name("clojure-spec-corpus").start(task);
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
		CliStack.call("clojure-spec", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "spec.clj")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program) throws Exception {
		String name = "ClojureSpec";
		byte[] classBytes = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null).classBytes();
		ClassLoader loader = new ClassLoader(ClojureSpecE2eTest.class.getClassLoader()) {
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
		try (var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-spec", () -> main.invoke(null, (Object) new String[0]));
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(String program, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, false, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		String name = "clojure-spec" + (component ? "-component" : "");
		Path path = workDir.resolve(name + ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, name, ".out");
		Path errFile = Files.createTempFile(workDir, name, ".err");
		try {
			Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", "/tmp",
					path.toString())
				.redirectOutput(outFile.toFile())
				.redirectError(errFile.toFile())
				.start();
			if (!process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor();
				throw new IllegalStateException("wasmtime command timed out: " + path);
			}
			String stderr = Files.readString(errFile, StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as("wasmtime exit code: %s", stderr).isZero();
			return Files.readString(outFile, StandardCharsets.UTF_8);
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
		}
	}

	private static Spec loadSpec() throws IOException {
		try (InputStream in = ClojureSpecE2eTest.class.getResourceAsStream(SPEC_RESOURCE)) {
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
