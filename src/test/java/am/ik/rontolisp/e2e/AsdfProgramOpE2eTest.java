package am.ik.rontolisp.e2e;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import am.ik.rontolisp.testsupport.WasmtimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code asdf:make} of a system that says it is an executable ({@code .kb/asdf.md},
 * "Building an executable"): the interpreter is the image the build runs in, and its dump
 * compiles that image -- the systems it loaded, then {@code uiop:restore-image} of the
 * entry point -- into the artifact the build pathname names. Fixture:
 * {@code src/test/resources/asdf-program-demo}, copied to a work directory because an
 * artifact is written beside its system.
 *
 * <p>
 * {@code program-demo} is clingon.intro's {@code program-op} trio: its executable runs
 * the entry point after the load and exits with its first value (0 for true, 1 for nil),
 * on the JVM, as a module and natively. {@code deploy-demo} is a deploy application over
 * the vendored deploy (tier 4, {@code eval/DeployForms}): its {@code :deploy} hook writes
 * next to the executable while the image builds, its {@code :boot} hook runs when the
 * executable starts, and {@code deploy:data-directory} finds the hook's file beside the
 * executable -- a jar's included, through its argv0.
 *
 * <p>
 * Every build and run is a SUBPROCESS: the command line is what holds the compilers, and
 * an artifact's exit is a real process exit. The CLI runs from the test class path; the
 * module leg needs Docker, the native leg the host's precompile shim and runner, and each
 * skips without them ({@code -Drontolisp.native.required=true} makes a missing pair a
 * failure).
 */
@Execution(ExecutionMode.CONCURRENT)
class AsdfProgramOpE2eTest {

	private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	private static final String CLASSPATH = System.getProperty("java.class.path");

	private static final Path RESOURCES = Path.of("src", "test", "resources").toAbsolutePath();

	/**
	 * deploy and what it depends on, vendored; babel and trivial-features are built in.
	 */
	private static final String DEPLOY_PATH = String.join(File.pathSeparator,
			Stream.of("deploy", "sha3", "pathname-utils", "cffi", "alexandria", "documentation-utils", "trivial-indent")
				.map(name -> RESOURCES.resolve(name).toString())
				.toList());

	private static final boolean REQUIRED = Boolean.getBoolean("rontolisp.native.required");

	private static final long TIMEOUT_MINUTES = 10;

	@TempDir
	Path workDir;

	@Test
	void aSystemBuildsAJarThatRunsItsEntryPoint() throws Exception {
		stage();
		Result build = rontolisp("build", "program-demo", "-o", "out/program-demo.jar");
		assertThat(build.exit()).as("%s", build).isZero();
		Result run = run(List.of(JAVA, "-jar", "out/program-demo.jar", "alpha"));
		assertThat(run.exit()).as("%s", run).isZero();
		assertThat(run.out()).isEqualTo("arguments (\"alpha\")\ndumped :EXECUTABLE, at load NIL\n");
		assertThat(run(List.of(JAVA, "-jar", "out/program-demo.jar", "fail")).exit()).isEqualTo(1);
		// A system whose :build-operation is load-op builds nothing.
		Result library = rontolisp("build", "program-demo/library");
		assertThat(library.exit()).as("%s", library).isEqualTo(1);
		assertThat(library.err()).contains("program-demo/library builds no executable");

		Result deploy = rontolisp("build", "deploy-demo", "-o", "out/deploy-demo.jar", "--system-path", DEPLOY_PATH);
		assertThat(deploy.exit()).as("%s", deploy).isZero();
		assertThat(this.workDir.resolve("out/greeting.txt")).exists();
		assertDeployed(run(List.of(JAVA, "-jar", "out/deploy-demo.jar")), this.workDir.resolve("out"));
	}

	@Test
	void aSystemBuildsAModuleThatRunsItsEntryPoint() throws Exception {
		assumeTrue(WasmtimeSupport.DOCKER_AVAILABLE, "Docker is not available");
		stage();
		Result build = rontolisp("build", "program-demo", "-o", "out/program-demo.wasm");
		assertThat(build.exit()).as("%s", build).isZero();
		GenericContainer<?> wasmtime = WasmtimeSupport.container();
		String path = "/tmp/" + this.workDir.getFileName() + "-program-demo.wasm";
		wasmtime.copyFileToContainer(Transferable.of(Files.readAllBytes(this.workDir.resolve("out/program-demo.wasm"))),
				path);
		// The restore is in a fatal-condition handler, which puts the module in EH mode.
		ExecResult run = wasmtime.execInContainer("bash", "-c",
				"wasmtime --wasm gc --wasm exceptions=y " + path + " alpha");
		assertThat(run.getExitCode()).as("stderr: %s", run.getStderr()).isZero();
		assertThat(run.getStdout()).isEqualTo("arguments (\"alpha\")\ndumped :EXECUTABLE, at load NIL\n");
		assertThat(wasmtime.execInContainer("bash", "-c", "wasmtime --wasm gc --wasm exceptions=y " + path + " fail")
			.getExitCode()).isEqualTo(1);
	}

	@Test
	void aSystemBuildsANativeExecutableAtItsBuildPathname() throws Exception {
		stage();
		// asdf:make in a program rontolisp runs: the artifact is the system's own
		// :build-pathname, which names no type.
		Files.writeString(this.workDir.resolve("build.lisp"), """
				(asdf:load-system "program-demo")
				(asdf:make "program-demo")
				""");
		skipWithoutNativeOutput(rontolisp("build.lisp"));
		Result run = run(List.of(this.workDir.resolve("bin/program-demo").toString(), "alpha"));
		assertThat(run.exit()).as("%s", run).isZero();
		assertThat(run.out()).isEqualTo("arguments (\"alpha\")\ndumped :EXECUTABLE, at load NIL\n");
		assertThat(run(List.of(this.workDir.resolve("bin/program-demo").toString(), "fail")).exit()).isEqualTo(1);

		// deploy's executable goes to bin/ below the system, with its data beside it.
		skipWithoutNativeOutput(rontolisp("build", "deploy-demo", "--system-path", DEPLOY_PATH));
		assertDeployed(run(List.of(this.workDir.resolve("bin/deploy-demo").toString())), this.workDir.resolve("bin"));
	}

	/** What deploy's boot and the application print: hooks, then the data they left. */
	private static void assertDeployed(Result run, Path dataDirectory) throws IOException {
		assertThat(run.exit()).as("%s", run).isZero();
		assertThat(run.out()).isEqualTo("booted\ndeployed T\nhello from the data directory\n");
		assertThat(run.err()).contains(" ==> Performing warm boot.\n")
			.contains("   -> Runtime directory is " + dataDirectory.toRealPath() + "/\n")
			.contains(" ==> Launching application.\n")
			.endsWith(" ==> Epilogue.\n ==> Running quit hooks.\n");
	}

	/** Copies the fixture to the work dir, where its artifacts are written. */
	private void stage() throws IOException {
		Path source = RESOURCES.resolve("asdf-program-demo");
		try (Stream<Path> files = Files.list(source)) {
			for (Path file : files.toList()) {
				Files.copy(file, this.workDir.resolve(file.getFileName()));
			}
		}
	}

	/**
	 * A build's result, or a skip where this build carries no native shim for the host.
	 */
	private static void skipWithoutNativeOutput(Result build) {
		if (build.exit() != 0 && build.err().contains("--native is not available for ") && !REQUIRED) {
			abort(build.err());
		}
		assertThat(build.exit()).as("%s", build).isZero();
	}

	/** {@code rontolisp ARGS...}, from the work dir. */
	private Result rontolisp(String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of(JAVA, "-cp", CLASSPATH, "am.ik.rontolisp.cli.RontoLispCli"));
		command.addAll(List.of(args));
		return run(command);
	}

	/** One finished subprocess: its exit code and its two streams, kept apart. */
	private record Result(List<String> command, int exit, String out, String err) {
		@Override
		public String toString() {
			return "command: %s%nstdout: %s%nstderr: %s".formatted(String.join(" ", this.command), this.out, this.err);
		}
	}

	private Result run(List<String> command) throws Exception {
		Path errFile = Files.createTempFile(this.workDir, "stderr", ".log");
		Process process = new ProcessBuilder(command).directory(this.workDir.toFile())
			.redirectError(errFile.toFile())
			.start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
			process.destroyForcibly();
			throw new AssertionError("timed out: " + String.join(" ", command));
		}
		return new Result(command, process.exitValue(), out, Files.readString(errFile));
	}

}
