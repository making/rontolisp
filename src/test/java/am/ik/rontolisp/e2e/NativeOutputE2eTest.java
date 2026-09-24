package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.testsupport.HostWasmtime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code --native -o prog} against {@code wasmtime run} of the same program's
 * {@code .wasm}: a slice of {@code ci-spec.yaml} (arithmetic to CLOS, plus the relative
 * and absolute file-system cases the stub's two preopens serve), program arguments, and
 * an uncaught error's exit status. The runner is expected to print what wasmtime prints
 * and exit as it exits (.kb/native-output.md).
 *
 * <p>
 * Runs where a usable {@code wasmtime} is on {@code PATH} and this build carries the
 * host's precompile shim and runner stub ({@code rontolisp-native/build.sh}); anywhere
 * else it is skipped.
 */
class NativeOutputE2eTest {

	/** ci-spec cases the slice is made of, in corpus order. */
	private static final List<String> CASES = List.of("arithmetic", "exact-integers-beyond-the-i64-range",
			"double-arithmetic-unboxed-and-fused", "map-family-over-eleven-lists", "reduce-sort-search-order",
			"closure-mutation-capture-by-reference", "condition-objects",
			"format-directives-conditional-iteration-jump", "hash-tables-cross-backend",
			"defstruct-constructor-accessors-predicate-copier", "clos-defgeneric-defmethod-eql-dispatch",
			"filesystem-write-create-rename-delete-and-probe", "runtime-absolute-path-open-probe-and-load");

	@TempDir
	Path tempDir;

	@Test
	void aCiSpecSliceWithArgumentsPrintsWhatWasmtimePrints() throws Exception {
		String source = slice() + "(print (uiop:command-line-arguments))\n";
		Run expected = wasmtime(source, "alpha", "beta gamma");
		Run actual = nativeOutput(source, "alpha", "beta gamma");
		assertThat(expected.exit()).as("wasmtime's exit status; stderr: %s", expected.stderr()).isZero();
		assertThat(actual.stdout()).isEqualTo(expected.stdout());
		assertThat(actual.exit()).as("stderr: %s", actual.stderr()).isEqualTo(expected.exit());
		assertThat(actual.stdout()).contains("\"beta gamma\"");
	}

	@Test
	void anUncaughtErrorExitsAsWasmtimeExits() throws Exception {
		String source = "(print \"before\")\n(error \"boom\")\n";
		Run expected = wasmtime(source);
		Run actual = nativeOutput(source);
		assertThat(expected.exit()).isNotZero();
		assertThat(actual.stdout()).isEqualTo(expected.stdout());
		assertThat(actual.exit()).as("stderr: %s", actual.stderr()).isEqualTo(expected.exit());
		// The trap is reported the way wasmtime reports it.
		assertThat(expected.stderr()).contains("wasm trap");
		assertThat(actual.stderr()).startsWith("Error: ").contains("wasm trap");
	}

	@Test
	void onlyTheExecutableIsWritten() throws Exception {
		Path out = Files.createDirectories(this.tempDir.resolve("only"));
		Path src = this.tempDir.resolve("hello.lisp");
		Files.writeString(src, "(print 'hello)\n");
		compileNative(src, out.resolve("hello"));
		try (Stream<Path> files = Files.list(out)) {
			assertThat(files.map(p -> p.getFileName().toString())).containsExactly("hello");
		}
		assertThat(Files.isExecutable(out.resolve("hello"))).isTrue();
		byte[] bytes = Files.readAllBytes(out.resolve("hello"));
		assertThat(new String(bytes, bytes.length - 8, 8, StandardCharsets.US_ASCII)).isEqualTo("RLNATIVE");
	}

	private Run wasmtime(String source, String... args) throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path dir = Files.createDirectories(this.tempDir.resolve("wasmtime"));
		Path src = dir.resolve("prog.lisp");
		Files.writeString(src, source);
		Path module = dir.resolve("prog.wasm");
		compile(src, module);
		// The flags CiSpecE2eTest runs the corpus with: the stub's own preopens are `.`
		// and `/`, which answer the same for everything the corpus opens.
		List<String> command = new ArrayList<>(List.of("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir",
				".", "--dir", "/tmp", module.toString()));
		command.addAll(List.of(args));
		return exec(Files.createDirectories(dir.resolve("run")), command);
	}

	private Run nativeOutput(String source, String... args) throws Exception {
		Path dir = Files.createDirectories(this.tempDir.resolve("native"));
		Path src = dir.resolve("prog.lisp");
		Files.writeString(src, source);
		Path exe = dir.resolve("prog");
		compileNative(src, exe);
		List<String> command = new ArrayList<>(List.of(exe.toString()));
		command.addAll(List.of(args));
		return exec(Files.createDirectories(dir.resolve("run")), command);
	}

	private static String slice() throws Exception {
		JsonNode spec;
		try (var in = NativeOutputE2eTest.class.getResourceAsStream("/ci-spec.yaml")) {
			spec = YAMLMapper.builder().build().readTree(in);
		}
		Map<String, String> sources = new HashMap<>();
		for (JsonNode c : spec.path("cases")) {
			sources.put(c.path("name").asString(), c.path("source").asString());
		}
		return CASES.stream().map(name -> {
			String s = sources.get(name);
			assertThat(s).as("ci-spec case %s", name).isNotNull();
			return s.endsWith("\n") ? s : s + "\n";
		}).collect(Collectors.joining());
	}

	/**
	 * Compiles with --native, skipping the test where this build has no shim for the
	 * host.
	 */
	private static void compileNative(Path src, Path output) {
		try {
			compile(src, output, "--native");
		}
		catch (UnsupportedOperationException ex) {
			String message = String.valueOf(ex.getMessage());
			if (message.startsWith("--native is not available for ")) {
				abort(message);
			}
			throw ex;
		}
	}

	private static void compile(Path src, Path output, String... flags) {
		List<String> args = new ArrayList<>(List.of(src.toString(), "-o", output.toString()));
		args.addAll(List.of(flags));
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			.run(args.toArray(String[]::new));
	}

	private static Run exec(Path dir, List<String> command) throws Exception {
		Path stdout = dir.resolve("stdout.log");
		Path stderr = dir.resolve("stderr.log");
		Process process = new ProcessBuilder(command).directory(dir.toFile())
			.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
			.redirectOutput(stdout.toFile())
			.redirectError(stderr.toFile())
			.start();
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("timed out: " + String.join(" ", command));
		}
		return new Run(Files.readString(stdout), Files.readString(stderr), process.exitValue());
	}

	private record Run(String stdout, String stderr, int exit) {
	}

}
