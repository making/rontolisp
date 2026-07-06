package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.LoadInliner;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared driver for the real-library {@code asdf:load-system} integration tests
 * (split-sequence, parse-number, cl-utilities, cl-who). Each concrete subclass supplies a
 * vendored system directory, an exercise program and its expected per-line output; this
 * base class then runs that same program on ALL FOUR backends and asserts the output
 * matches:
 *
 * <ol>
 * <li>the interpreter ({@link LispEvaluator} driven directly),</li>
 * <li>the JVM compiler (CLI pipeline: {@link LoadInliner} splices the system,
 * {@link UserMacroExpander} expands its defmacros, then {@link JvmLispCompiler}; the
 * class is defined from its bytes and {@code main} is run),</li>
 * <li>WASM Preview 1 ({@link WasmLispCompiler} run under {@code wasmtime} in a
 * container),</li>
 * <li>the WASM component / WASI 0.3 ({@code --component}, run under {@code wasmtime
 * run -W component-model-async}).</li>
 * </ol>
 *
 * <p>
 * The {@code .asd} file only has to be on disk at COMPILE time: {@link LoadInliner}
 * resolves the system against {@link #systemDir()} on the host and splices the component
 * files inline, so the emitted {@code .class}/{@code .wasm} is self-contained and needs
 * no source registry at run time. That is why the concatenated {@code ci-spec} driver
 * (which cannot provide the {@code .asd}) cannot cover these but a standalone per-library
 * test can.
 *
 * <p>
 * The two WASM backends need Docker; when Docker is unavailable those two tests are
 * skipped (via {@link org.junit.jupiter.api.Assumptions}) while the interpreter and JVM
 * tests still run. A single {@code wasmtime} container is shared across every subclass.
 */
abstract class AsdfLibraryE2eSupport {

	/** Absolute path to the vendored system directory (holds the {@code .asd}). */
	protected abstract String systemDir();

	/**
	 * The exercise program: an {@code asdf:load-system} plus prints of the public API.
	 */
	protected abstract String exercise();

	/** The expected stdout, one trimmed line per element. */
	protected abstract List<String> expected();

	/** A path-free name for the compiled JVM class / WASM temp files. */
	protected abstract String artifactName();

	private static final boolean DOCKER_AVAILABLE = DockerClientFactory.instance().isDockerAvailable();

	// A single wasmtime container shared across every library subclass. It is started
	// eagerly (not via @Testcontainers) so the JVM-only backends do not pay for it and so
	// the four subclasses reuse one container; Ryuk reaps it at JVM shutdown. The install
	// mirrors WasmLispCompilerIntegrationTest so the built image is cached and reused.
	private static final GenericContainer<?> WASMTIME = new GenericContainer<>(
			new ImageFromDockerfile().withDockerfileFromBuilder(builder -> builder.from("debian:bookworm-slim")
				.run("apt-get update && apt-get install -y --no-install-recommends curl ca-certificates xz-utils"
						+ " && curl https://wasmtime.dev/install.sh -sSf -o /tmp/install-wasmtime.sh"
						+ " && bash /tmp/install-wasmtime.sh && rm /tmp/install-wasmtime.sh"
						+ " && ln -s /root/.wasmtime/bin/wasmtime /usr/local/bin/wasmtime" + " && wasmtime --version"
						+ " && rm -rf /var/lib/apt/lists/*")
				.build()))
		.withCommand("sleep", "infinity");

	static {
		if (DOCKER_AVAILABLE) {
			WASMTIME.start();
		}
	}

	@Test
	void loadsAndRunsOnTheInterpreter() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(List.of(systemDir()));
		for (LispVal expr : LispReader.readAllFromString(exercise())) {
			evaluator.eval(expr);
		}
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim))
			.containsExactlyElementsOf(expected());
	}

	@Test
	void compilesAndRunsOnJvm() throws Exception {
		byte[] classBytes = new JvmLispCompiler(artifactName()).compile(compileProgram(Features.JVM));
		assertThat(runMain(classBytes, artifactName()).lines().map(String::trim)).containsExactlyElementsOf(expected());
	}

	@Test
	void compilesAndRunsOnWasmPreview1() throws Exception {
		assumeTrue(DOCKER_AVAILABLE, "Docker is not available");
		byte[] wasmBytes = new WasmLispCompiler().compile(compileProgram(Features.WASM));
		assertThat(runWasm(wasmBytes, false).lines().map(String::trim)).containsExactlyElementsOf(expected());
	}

	@Test
	void compilesAndRunsOnWasmComponent() throws Exception {
		assumeTrue(DOCKER_AVAILABLE, "Docker is not available");
		byte[] wasmBytes = new WasmLispCompiler(false, true).compile(compileProgram(Features.WASM));
		assertThat(runWasm(wasmBytes, true).lines().map(String::trim)).containsExactlyElementsOf(expected());
	}

	// The CLI compile pipeline for the given feature set: inline the system's component
	// files, then expand the user macros they define before the backend compiler runs.
	private List<LispVal> compileProgram(Features features) {
		return UserMacroExpander.expand(LoadInliner.inline(LispReader.readAllFromString(exercise(), features),
				SourceLoader.fileSystem(), null, List.of(systemDir()), features));
	}

	// Defines the compiled class from its bytes and runs main, capturing UTF-8 stdout.
	private static String runMain(byte[] classBytes, String name) throws Exception {
		ClassLoader loader = new ClassLoader(AsdfLibraryE2eSupport.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				if (n.equals(name)) {
					return defineClass(n, classBytes, 0, classBytes.length);
				}
				return super.findClass(n);
			}
		};
		Method main = loader.loadClass(name).getMethod("main", String[].class);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintStream oldOut = System.out;
		System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
		try {
			main.invoke(null, (Object) new String[0]);
		}
		finally {
			System.setOut(oldOut);
		}
		return baos.toString(StandardCharsets.UTF_8).trim();
	}

	// Copies the module into the container and runs it under wasmtime, returning stdout.
	private String runWasm(byte[] wasmBytes, boolean component) throws Exception {
		String path = "/tmp/" + artifactName() + (component ? "-component.wasm" : "-p1.wasm");
		WASMTIME.copyFileToContainer(Transferable.of(wasmBytes), path);
		ExecResult result = component
				? WASMTIME.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y", "-W",
						"component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y", path)
				: WASMTIME.execInContainer("wasmtime", "--wasm", "gc", path);
		assertThat(result.getExitCode()).as("exit code (component=%s): stderr: %s", component, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

}
