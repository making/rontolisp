package am.ik.rontolisp.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * End-to-end test that runs the whole {@code ci-spec.yaml} program through the rontolisp
 * native binary in all four backend modes (interpreter, JVM, WASM Preview 1, and WASM as
 * a WASI 0.3 component) and compares the output of each case.
 * <p>
 * The cases share global state, so they are concatenated into a single program and each
 * backend is compiled/run once. The output is sliced back per case using each case's
 * declared expected-line count, so a failure names the exact case (and its source) rather
 * than only a line number.
 * <p>
 * The {@code WASM_COMPONENT} backend compiles with {@code --component} and runs the
 * resulting WASI 0.3 (Preview 3) component with {@code wasmtime run -W gc=y
 * -W component-model-more-async-builtins=y} (the async canonical ABI and stackful lifts
 * are on by default in wasmtime 46+; only the synchronous stream/future built-ins are
 * still feature-gated). The {@code ci-spec.yaml} cases are deterministic and do no file
 * I/O / random / time / getenv, so the component's output is identical to the Preview 1
 * WASM backend and is checked against the same {@code expected} lines.
 * <p>
 * Runs only when {@code -Drontolisp.binary=<path>} points at a built native binary;
 * otherwise the whole factory is skipped (the regular {@code mvn test} job runs on the
 * JVM before the native binary exists). The two WASM backends are additionally skipped
 * when {@code wasmtime} is not on the {@code PATH}.
 */
class CiSpecE2eTest {

	private static final String SPEC_RESOURCE = "/ci-spec.yaml";

	enum Backend {

		INTERPRETER, JVM, WASM, WASM_COMPONENT

	}

	record Case(String name, String source, @Nullable String expected,
			@Nullable Map<String, String> expectedByBackend) {

		List<String> expectedLines(Backend backend) {
			String text = null;
			if (this.expectedByBackend != null) {
				text = this.expectedByBackend.get(backend.name().toLowerCase());
				// A WASI 0.3 component mirrors the Preview 1 WASM backend's output, so
				// reuse a "wasm" override when no component-specific one is declared.
				if (text == null && backend == Backend.WASM_COMPONENT) {
					text = this.expectedByBackend.get(Backend.WASM.name().toLowerCase());
				}
			}
			if (text == null) {
				text = this.expected;
			}
			return splitLines(text == null ? "" : text);
		}
	}

	record Spec(List<Case> cases) {
	}

	@TempDir
	static Path workDir;

	@TestFactory
	Stream<DynamicNode> e2e() throws Exception {
		String binary = System.getProperty("rontolisp.binary");
		assumeTrue(binary != null, "rontolisp.binary not set; skipping native-binary E2E");
		Path bin = Path.of(binary).toAbsolutePath();
		assumeTrue(Files.isExecutable(bin), () -> "not an executable binary: " + bin);

		Spec spec = loadSpec();
		Path program = writeProgram(spec);

		List<DynamicNode> backends = new ArrayList<>();
		for (Backend backend : Backend.values()) {
			backends.add(backendNode(backend, bin, program, spec));
		}
		return backends.stream();
	}

	private static DynamicContainer backendNode(Backend backend, Path bin, Path program, Spec spec) {
		if ((backend == Backend.WASM || backend == Backend.WASM_COMPONENT) && !onPath("wasmtime")) {
			return dynamicContainer(backend.name(),
					Stream.of(dynamicTest("(skipped)", () -> abort("wasmtime not on PATH"))));
		}

		List<String> actual;
		try {
			actual = runBackend(backend, bin, program);
		}
		catch (Exception ex) {
			return dynamicContainer(backend.name(),
					Stream.of(dynamicTest("(execution failed)", () -> fail(ex.getMessage(), ex))));
		}

		List<DynamicNode> tests = new ArrayList<>();
		List<String> expectedAll = spec.cases().stream().flatMap(c -> c.expectedLines(backend).stream()).toList();
		List<String> actualSnapshot = actual;
		tests.add(dynamicTest("total-line-count",
				() -> assertThat(actualSnapshot)
					.as("%s produced a different number of output lines than the spec", backend)
					.hasSize(expectedAll.size())));

		int offset = 0;
		for (Case c : spec.cases()) {
			List<String> expected = c.expectedLines(backend);
			int start = offset;
			offset += expected.size();
			List<String> slice = sublist(actual, start, expected.size());
			tests.add(dynamicTest(c.name(),
					() -> assertThat(slice)
						.as("case '%s' on %s%n--- source ---%n%s--- end source ---", c.name(), backend, c.source())
						.containsExactlyElementsOf(expected)));
		}
		return dynamicContainer(backend.name(), tests.stream());
	}

	private static List<String> runBackend(Backend backend, Path bin, Path program) throws Exception {
		return switch (backend) {
			case INTERPRETER -> exec(List.of(bin.toString(), program.toString()));
			case JVM -> {
				exec(List.of(bin.toString(), program.toString(), "-o", "Test.class"));
				yield exec(List.of("java", "Test"));
			}
			case WASM -> {
				exec(List.of(bin.toString(), program.toString(), "-o", "test.wasm"));
				// --dir . preopens the work dir so the file-stream cases can open files;
				// exceptions=y because the concatenated program contains catching cases
				// (todo 129), which put the whole module in EH mode (harmless otherwise).
				yield exec(List.of("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y", "--dir", ".", "test.wasm"));
			}
			case WASM_COMPONENT -> {
				exec(List.of(bin.toString(), program.toString(), "-o", "test.component.wasm", "--component"));
				yield exec(List.of("wasmtime", "run", "-W", "gc=y", "-W", "component-model-more-async-builtins=y", "-W",
						"exceptions=y", "--dir", ".", "test.component.wasm"));
			}
		};
	}

	private static List<String> exec(List<String> command) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
		Process process = pb.start();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		if (exit != 0) {
			throw new IOException("command %s exited with %d%nstderr:%n%s".formatted(command, exit, stderr));
		}
		return splitLines(stdout);
	}

	private static Spec loadSpec() throws IOException {
		try (InputStream in = CiSpecE2eTest.class.getResourceAsStream(SPEC_RESOURCE)) {
			if (in == null) {
				throw new IOException("missing test resource: " + SPEC_RESOURCE);
			}
			return new YAMLMapper().readValue(in, Spec.class);
		}
	}

	private static Path writeProgram(Spec spec) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (Case c : spec.cases()) {
			sb.append(c.source());
			if (!c.source().endsWith("\n")) {
				sb.append('\n');
			}
		}
		Path program = workDir.resolve("ci-program.lisp");
		Files.writeString(program, sb.toString());
		return program;
	}

	private static List<String> splitLines(String text) {
		if (text.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		// A trailing newline yields a final empty element; drop exactly one so
		// "3\n" is one line, not two.
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

	private static List<String> sublist(List<String> lines, int start, int count) {
		int from = Math.min(start, lines.size());
		int to = Math.min(start + count, lines.size());
		return lines.subList(from, to);
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir).resolve(tool))) {
				return true;
			}
		}
		return false;
	}

}
