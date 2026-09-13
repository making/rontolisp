package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.cli.LoadInliner;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.WasmtimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
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
 * <li>the JVM compiler ({@link JvmSourceCompiler}, which is the CLI's own
 * {@code -o out.class} path in process: the shared front end splices the system and
 * expands its defmacros, then the JVM backend emits; the class is defined from its bytes
 * and {@code main} is run),</li>
 * <li>WASM Preview 1 ({@link WasmLispCompiler} run under {@code wasmtime} in a
 * container),</li>
 * <li>the WASM component / WASI 0.3 ({@code --component}, run under {@code wasmtime
 * run}).</li>
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
@Execution(ExecutionMode.CONCURRENT)
abstract class AsdfLibraryE2eSupport {

	/** Absolute path to the vendored system directory (holds the {@code .asd}). */
	protected abstract String systemDir();

	/**
	 * Additional directories searched for a dependency's {@code .asd}, in order after
	 * {@link #systemDir()}. Override for a library whose {@code :depends-on} lists
	 * another vendored ASDF-loadable library (uax-15 depends on split-sequence and
	 * cl-ppcre); an empty list -- the default -- means the library's own directory is
	 * self-contained.
	 */
	protected List<String> extraSystemPath() {
		return List.of();
	}

	private List<String> systemPath() {
		List<String> path = new java.util.ArrayList<>();
		path.add(systemDir());
		path.addAll(extraSystemPath());
		return path;
	}

	/**
	 * The exercise program: an {@code asdf:load-system} plus prints of the public API. A
	 * subclass whose program touches the filesystem must build its path from
	 * {@link #WORK_TOKEN} (e.g. {@code "target/foo-%%WORK%%.tmp"}) rather than a fixed
	 * name -- {@link #exerciseFor(String)} replaces the token with a name unique to the
	 * leg about to run, so the four backends (and, for the two WASM legs, the one shared
	 * wasmtime container) never contend for the same path. A program with no such path
	 * needs no token at all; the replacement is then a no-op.
	 */
	protected abstract String exercise();

	/** Substituted into a subclass's {@link #exercise()} for the leg about to run. */
	protected static final String WORK_TOKEN = "%%WORK%%";

	private String exerciseFor(String leg) {
		return exercise().replace(WORK_TOKEN, leg);
	}

	/** The expected stdout, one trimmed line per element. */
	protected abstract List<String> expected();

	/**
	 * Normalization applied to each trimmed actual line before comparison. The default is
	 * identity; override for a library whose report carries values that legitimately
	 * differ per run on one machine (RoveE2eTest strips rove's {@code  (Nms)} duration
	 * suffix, printed for any assertion slower than 37 ms).
	 */
	protected String normalizeLine(String line) {
		return line;
	}

	/** A path-free name for the compiled JVM class / WASM temp files. */
	protected abstract String artifactName();

	// A single wasmtime container from the prebuilt GHCR image (see WasmtimeSupport),
	// shared across every library subclass AND with WasmLispCompilerIntegrationTest;
	// started lazily on first use and reaped by Ryuk at JVM shutdown. The JVM-only
	// backends do not pay for it: the WASM tests below guard on DOCKER_AVAILABLE, and
	// WasmtimeSupport.container() contacts Docker only when actually called.
	private static final boolean DOCKER_AVAILABLE = WasmtimeSupport.DOCKER_AVAILABLE;

	/**
	 * The stack the interpreter leg runs on. The interpreter's recursion depth is the
	 * PROGRAM's -- cl-mustache's spec suite renders its templates ~800 KiB down -- and a
	 * JUnit worker thread carries the JVM default (1 MiB on linux-x64), which is inside
	 * that program's own margin: the same leg that passes here ran out of stack on CI.
	 * The CLI hands every program 16 MiB for exactly this reason
	 * ({@code RontoLispCli.WORKER_STACK_BYTES}, on a thread of its own whatever the
	 * launcher did), so the in-process leg measures the same ceiling the product does
	 * rather than JUnit's. It must track that constant.
	 */
	private static final long INTERPRETER_STACK_BYTES = 16L << 20;

	@Test
	void loadsAndRunsOnTheInterpreter() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		runOnAnInterpreterStack(() -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			evaluator.setSystemPath(systemPath());
			for (LispVal expr : LispReader.readAllFromString(exerciseFor("interpreter"))) {
				evaluator.eval(expr);
			}
		});
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim).map(this::normalizeLine))
			.containsExactlyElementsOf(expected());
	}

	// Runs the body on a thread with the CLI's interpreter stack and rethrows whatever
	// it threw, so a failure still reports as this test's own.
	private static void runOnAnInterpreterStack(Runnable body) throws Exception {
		Throwable[] thrown = new Throwable[1];
		Thread worker = new Thread(null, () -> {
			try {
				body.run();
			}
			catch (Throwable ex) {
				thrown[0] = ex;
			}
		}, "interpreter", INTERPRETER_STACK_BYTES);
		worker.start();
		worker.join();
		if (thrown[0] instanceof Error error) {
			throw error;
		}
		if (thrown[0] instanceof Exception exception) {
			throw exception;
		}
	}

	@Test
	void compilesAndRunsOnJvm() throws Exception {
		// JvmSourceCompiler is the same shared front end the WASM legs below run,
		// plus the JVM backend half -- which is exactly what the CLI's -o out.class is,
		// so this leg cannot compile a program the command line would not.
		byte[] classBytes = new JvmSourceCompiler(artifactName()).systemPath(systemPath())
			.compile(exerciseFor("jvm"), null)
			.classBytes();
		assertThat(runMain(classBytes, artifactName()).lines().map(String::trim).map(this::normalizeLine))
			.containsExactlyElementsOf(expected());
	}

	@Test
	void compilesAndRunsOnWasmPreview1() throws Exception {
		assumeTrue(DOCKER_AVAILABLE, "Docker is not available");
		CompileFrontendAccess.Program program = wasmProgram(false, "wasm-p1");
		byte[] wasmBytes = new WasmLispCompiler().runtimeFeatures(program.features().names()).compile(program.forms());
		assertThat(runWasm(wasmBytes, false).lines().map(String::trim).map(this::normalizeLine))
			.containsExactlyElementsOf(expected());
	}

	@Test
	void compilesAndRunsOnWasmComponent() throws Exception {
		assumeTrue(DOCKER_AVAILABLE, "Docker is not available");
		CompileFrontendAccess.Program program = wasmProgram(true, "wasm-component");
		byte[] wasmBytes = new WasmLispCompiler(false, true).runtimeFeatures(program.features().names())
			.compile(program.forms());
		assertThat(runWasm(wasmBytes, true).lines().map(String::trim).map(this::normalizeLine))
			.containsExactlyElementsOf(expected());
	}

	// The compile path's whole front end for a WASM target: the read with the target's
	// own feature set, the (load ...) and ASDF inlining that splices the system's
	// component files off systemPath(), user macro expansion, the library splice chain
	// and the tree-shaker. It RUNS the CLI's pipeline rather than restating it
	// (CompileFrontendAccess -> CompileFrontend), because these tests are the coverage
	// for that pipeline over a real third-party tree: a pass the CLI applies and a copy
	// here skipped would mean compiling a program no user can build, and it would surface
	// as a library failure rather than as a missing pass. The pruning is part of it for
	// the same reason -- each library below exercises its own API on three compile
	// backends, so a definition the pass drops that the program still needs fails here
	// rather than in a user's build.
	private CompileFrontendAccess.Program wasmProgram(boolean component, String leg) {
		return CompileFrontendAccess.withSystemPath(exerciseFor(leg), systemPath(), true, component);
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
	// The run gets /tmp as its preopened working directory (with a target/ subdirectory,
	// so an exercise can write "target/..." like the in-process interpreter/JVM runs do
	// from the project root): a file round-trip after a library load is part of what
	// these tests pin (the close methods).
	private String runWasm(byte[] wasmBytes, boolean component) throws Exception {
		String path = "/tmp/" + artifactName() + (component ? "-component.wasm" : "-p1.wasm");
		GenericContainer<?> wasmtime = WasmtimeSupport.container();
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path);
		String flags = component ? "run -W gc=y -W exceptions=y" : "--wasm gc --wasm exceptions=y";
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"mkdir -p /tmp/target && cd /tmp && wasmtime " + flags + " --dir . " + path);
		assertThat(result.getExitCode()).as("exit code (component=%s): stderr: %s", component, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

}
