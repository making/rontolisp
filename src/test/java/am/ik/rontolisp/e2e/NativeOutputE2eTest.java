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
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.testsupport.HostWasmtime;
import org.jspecify.annotations.Nullable;
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
 * The compiler is this JVM's {@link RontoLispCli}, or the binary
 * {@code -Drontolisp.binary=<path>} names (the native-image legs of CI: the binary's own
 * resources and FFM downcalls are what is under test there). Runs where a usable
 * {@code wasmtime} is on {@code PATH} and the compiler carries the host's precompile shim
 * and runner stub ({@code rontolisp-native/build.sh}); elsewhere it is skipped, unless
 * {@code -Drontolisp.native.required=true} makes a missing pair a failure.
 */
class NativeOutputE2eTest {

	/** The native binary that compiles, or {@code null} for this JVM. */
	private static final @Nullable String BINARY = System.getProperty("rontolisp.binary");

	/** A build that must carry the pair fails here instead of skipping. */
	private static final boolean REQUIRED = Boolean.getBoolean("rontolisp.native.required");

	private static final String NOT_AVAILABLE = "--native is not available for ";

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
	void aRelativePathClimbsAboveTheDirectoryItRunsIn() throws Exception {
		// Where `wasmtime run --dir .` refuses `../x`, the runner answers as a native
		// program does; there is no wasmtime run to diff against here. The program runs
		// in native/run, so ../up.txt is native/up.txt.
		Files.writeString(Files.createDirectories(this.tempDir.resolve("native")).resolve("up.txt"), "from above\n");
		Run run = nativeOutput("""
				(with-open-file (in "../up.txt") (print (read-line in)))
				(print (probe-file "../missing.txt"))
				""");
		assertThat(run.exit()).as("stderr: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo("\"from above\"\nNIL\n");
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
		if (!System.getProperty("os.name", "").startsWith("Mac")) {
			assertThat(new String(bytes, bytes.length - 8, 8, StandardCharsets.US_ASCII)).isEqualTo("RLNATIVE");
		}
	}

	/**
	 * On macOS the module is embedded in the image and the image signed ad hoc, so the
	 * code signature covers the whole output: {@code codesign --verify --strict} accepts
	 * it (the linker's signature of the stub alone did not), and it runs.
	 */
	@Test
	void aMacOsOutputPassesStrictCodeSignatureValidation() throws Exception {
		assumeTrue(System.getProperty("os.name", "").startsWith("Mac"), "codesign is macOS's");
		Path dir = Files.createDirectories(this.tempDir.resolve("signed"));
		Path src = dir.resolve("hello.lisp");
		Files.writeString(src, "(print 'hello)\n");
		compileNative(src, dir.resolve("hello"));
		Run verify = exec(dir, List.of("codesign", "--verify", "--strict", "--verbose=2", "./hello"));
		assertThat(verify.exit()).as("codesign: %s", verify.stderr()).isZero();
		Run run = exec(dir, List.of("./hello"));
		assertThat(run.exit()).as("stderr: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo("HELLO\n");
	}

	/**
	 * The default {@code --native-cpu} is the platform's baseline: the output runs on the
	 * oldest CPU of its architecture (qemu's SSE2-only Opteron G1 on x86_64, Armv8.0
	 * Cortex-A53 on aarch64), where a {@code --native-cpu=host} output is refused with a
	 * message rather than faulting. The program floors a double, which Cranelift compiles
	 * to an SSE4.1 instruction when it may and to a call when it may not.
	 */
	@Test
	void theDefaultOutputRunsOnTheOldestCpuOfItsPlatform() throws Exception {
		String arch = linuxArch();
		Path qemu = qemu(arch);
		String source = "(print (floor 7.5d0))\n(print (+ 1 2))\n";
		Path dir = Files.createDirectories(this.tempDir.resolve("oldest"));
		Path src = dir.resolve("prog.lisp");
		Files.writeString(src, source);
		compileNative(src, dir.resolve("baseline"));
		Run baseline = exec(dir, List.of(qemu.toString(), "-cpu", oldestCpu(arch), "./baseline"));
		abortedWhenQemuCannotRun(qemu, arch, baseline);
		assertThat(baseline.exit()).as("stderr: %s", baseline.stderr()).isZero();
		assertThat(baseline.stdout()).isEqualTo("7\n3\n");

		compileNative(src, dir.resolve("host"), "--native-cpu=host");
		Run host = exec(dir, List.of(qemu.toString(), "-cpu", oldestCpu(arch), "./host"));
		abortedWhenQemuCannotRun(qemu, arch, host);
		assertThat(host.exit()).as("stdout: %s", host.stdout()).isEqualTo(1);
		assertThat(host.stderr()).contains("is enabled, but not available on the host");
	}

	/**
	 * {@code --native-target} for the other Linux architecture: the output starts with
	 * that platform's stub and runs under qemu there. Skipped where the compiler carries
	 * no stub for it (a build from source, or a pull request's binary).
	 */
	@Test
	void aCrossTargetOutputRunsOnTheOtherLinuxArchitecture() throws Exception {
		String other = "x86_64".equals(linuxArch()) ? "aarch64" : "x86_64";
		Path qemu = qemu(other);
		Path dir = Files.createDirectories(this.tempDir.resolve("cross"));
		Path src = dir.resolve("prog.lisp");
		Files.writeString(src, "(print (uiop:command-line-arguments))\n(print (floor 7.5d0))\n");
		try {
			compileNative(src, dir.resolve("prog"), "--native-target", "linux-" + other);
		}
		catch (RuntimeException ex) {
			String message = String.valueOf(ex.getMessage());
			if (message.contains("carries no runner stub")) {
				abort(message);
			}
			throw ex;
		}
		byte[] exe = Files.readAllBytes(dir.resolve("prog"));
		// e_machine of the stub's ELF header: EM_X86_64 or EM_AARCH64.
		assertThat((exe[18] & 0xff) | (exe[19] & 0xff) << 8).isEqualTo("x86_64".equals(other) ? 62 : 183);
		Run run = exec(dir, List.of(qemu.toString(), "-cpu", oldestCpu(other), "./prog", "a b"));
		abortedWhenQemuCannotRun(qemu, other, run);
		assertThat(run.exit()).as("stderr: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo("(\"a b\")\n7\n");
	}

	/** The host's architecture on Linux; the test is skipped elsewhere. */
	private static String linuxArch() {
		assumeTrue(System.getProperty("os.name", "").startsWith("Linux"), "qemu user mode runs Linux executables");
		return switch (System.getProperty("os.arch", "")) {
			case "amd64", "x86_64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> abort("no --native build for this host");
		};
	}

	private static Path qemu(String arch) {
		for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
			for (String name : List.of("qemu-" + arch + "-static", "qemu-" + arch)) {
				Path candidate = Path.of(dir, name);
				if (Files.isExecutable(candidate)) {
					return candidate;
				}
			}
		}
		return abort("qemu-" + arch + " (user mode) is not on PATH");
	}

	/** SSE2 and nothing newer; Armv8.0 without LSE. */
	private static String oldestCpu(String arch) {
		return "x86_64".equals(arch) ? "Opteron_G1" : "cortex-a53";
	}

	/**
	 * A qemu that dies running the guest cannot run any output: qemu-x86_64 8.2.2 crashes
	 * with an internal SIGSEGV on an aarch64 host as soon as the guest opens
	 * {@code /proc/self/maps} (.kb/native-output.md). Excuse the emulated run rather than
	 * failing it; the ELF-shape assertion above already ran.
	 */
	private static void abortedWhenQemuCannotRun(Path qemu, String arch, Run run) {
		if (run.stderr().contains("QEMU internal")) {
			abort(qemu + " cannot run " + arch + " executables here (" + run.stderr().strip()
					+ "): emulated run skipped");
		}
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
	 * Compiles with --native, skipping the test where the compiler has no shim for the
	 * host (failing it under {@link #REQUIRED}).
	 */
	private static void compileNative(Path src, Path output, String... flags) throws Exception {
		List<String> all = new ArrayList<>(List.of("--native"));
		all.addAll(List.of(flags));
		try {
			compile(src, output, all.toArray(String[]::new));
		}
		catch (UnsupportedOperationException ex) {
			String message = String.valueOf(ex.getMessage());
			if (message.contains(NOT_AVAILABLE) && !REQUIRED) {
				abort(message);
			}
			throw ex;
		}
	}

	private static void compile(Path src, Path output, String... flags) throws Exception {
		List<String> args = new ArrayList<>(List.of(src.toString(), "-o", output.toString()));
		args.addAll(List.of(flags));
		if (BINARY == null) {
			new RontoLispCli(new ByteArrayInputStream(new byte[0]),
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
				.run(args.toArray(String[]::new));
			return;
		}
		args.addFirst(Path.of(BINARY).toAbsolutePath().toString());
		Run run = exec(Objects.requireNonNull(src.toAbsolutePath().getParent()), args);
		if (run.exit() != 0) {
			// The binary reports the in-process exception on stderr; raise it as one so
			// both drivers skip (or fail) alike.
			if (run.stderr().contains(NOT_AVAILABLE)) {
				throw new UnsupportedOperationException(run.stderr().strip());
			}
			throw new IllegalStateException("compile failed (" + run.exit() + "): " + run.stderr());
		}
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
