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
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.EnvironmentLibrary;
import am.ik.rontolisp.eval.LibraryDefunPruner;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.eval.UsocketLibrary;
import am.ik.rontolisp.reader.Features;
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
 * <li>the JVM compiler (CLI pipeline: {@link LoadInliner} splices the system,
 * {@link UserMacroExpander} expands its defmacros, then {@link JvmLispCompiler}; the
 * class is defined from its bytes and {@code main} is run),</li>
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
	 * The exercise program: an {@code asdf:load-system} plus prints of the public API.
	 */
	protected abstract String exercise();

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

	@Test
	void loadsAndRunsOnTheInterpreter() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(systemPath());
		for (LispVal expr : LispReader.readAllFromString(exercise())) {
			evaluator.eval(expr);
		}
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim).map(this::normalizeLine))
			.containsExactlyElementsOf(expected());
	}

	@Test
	void compilesAndRunsOnJvm() throws Exception {
		byte[] classBytes = new JvmLispCompiler(artifactName())
			.compile(compileProgram(Features.JVM, WitExportDirective.Backend.OTHER));
		assertThat(runMain(classBytes, artifactName()).lines().map(String::trim).map(this::normalizeLine))
			.containsExactlyElementsOf(expected());
	}

	@Test
	void compilesAndRunsOnWasmPreview1() throws Exception {
		assumeTrue(DOCKER_AVAILABLE, "Docker is not available");
		byte[] wasmBytes = new WasmLispCompiler()
			.compile(compileProgram(Features.WASM, WitExportDirective.Backend.WASM_GC));
		assertThat(runWasm(wasmBytes, false).lines().map(String::trim).map(this::normalizeLine))
			.containsExactlyElementsOf(expected());
	}

	@Test
	void compilesAndRunsOnWasmComponent() throws Exception {
		assumeTrue(DOCKER_AVAILABLE, "Docker is not available");
		byte[] wasmBytes = new WasmLispCompiler(false, true)
			.compile(compileProgram(Features.WASM, WitExportDirective.Backend.WASM_COMPONENT));
		assertThat(runWasm(wasmBytes, true).lines().map(String::trim).map(this::normalizeLine))
			.containsExactlyElementsOf(expected());
	}

	// The CLI compile pipeline for the given feature set: inline the system's component
	// files, expand the user macros they define, then splice the rontolisp-source prelude
	// (equalp/string<), the Gray-stream dispatch and the usocket shim when referenced.
	// Finally tree-shake -- mirroring RontoLispCli -- before the backend compiler runs.
	// The pruner belongs here, not only in the CLI: these tests are the coverage for
	// pruning a real third-party tree. Each library below exercises its own API on three
	// compile backends, so a definition the pass drops that the program still needs fails
	// here rather than in a user's build.
	private List<LispVal> compileProgram(Features features, WitExportDirective.Backend backend) {
		// LispPreludeLibrary must be handed the TARGET feature set (mirroring
		// RontoLispCli.compileToFile): uiop:featurep's definition reads *features*,
		// which the reader substitutes with the target's list per feature set, so the
		// one-argument overload would splice the INTERPRETER's answer into a compiled
		// module (.kb/uiop.md). EnvironmentLibrary mirrors the CLI too: uiop:getenv on
		// the --component path is environment.lisp over a wit-imported
		// wasi:cli/environment (a no-op on the other backends), and rove's
		// with-local-envs -- run's :env option -- reads it.
		// UnreadCharLibrary comes after the Gray splice like in the CLI, so a
		// character-read call site a Gray dispatch helper introduced reaches the
		// pushback cell too (cl-json's decoder cannot scan a number or aggregate
		// without unread-char).
		return LibraryDefunPruner.prune(
				EnvironmentLibrary
					.process(
							am.ik.rontolisp.eval.UnreadCharLibrary.process(
									UsocketLibrary.process(
											am.ik.rontolisp.eval.GrayStreamsLibrary.process(
													LispPreludeLibrary.process(
															UserMacroExpander
																.expand(LoadInliner.inline(
																		LispReader.readAllFromString(exercise(),
																				features),
																		SourceLoader.fileSystem(), null, systemPath(),
																		features)),
															features)))),
							backend));
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
