package am.ik.rontolisp.cli;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.JvmExportDirective;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.DistClient;
import am.ik.rontolisp.reader.Features;
import org.jspecify.annotations.Nullable;

/**
 * Compiles one Lisp source text to JVM bytecode IN PROCESS: the compile path's front end
 * ({@link CompileFrontend}) plus the JVM backend, with nothing of the command line and
 * nothing written to disk.
 * <p>
 * This is the seam an embedder compiles through -- {@code rontolisp-maven-plugin}, whose
 * {@code compile} goal turns {@code src/main/lisp} into classes in
 * {@code target/classes}. Shelling out to the CLI would work, but the front end is where
 * the library splices, the {@code (load ...)} inlining and the tree-shaker live, so a
 * second entry point into it is the only way an embedder gets the same program the CLI
 * gets. The CLI's own {@code -o out.class} / {@code -o out.jar} path shares the backend
 * half through {@link #compileProgram}, so the two cannot drift.
 * <p>
 * Fluent like {@link JvmLispCompiler} itself: every setter is one flag that reaches the
 * JVM backend, and the defaults are the CLI's defaults.
 */
public final class JvmSourceCompiler {

	private final String internalClassName;

	private boolean dynamic;

	private OptimizeLevel optimize = OptimizeLevel.DEFAULT;

	private boolean simd;

	private boolean blas;

	private boolean gpu;

	private boolean parallel;

	private boolean noPrune;

	private boolean noMain;

	private @Nullable String baseDir;

	private List<String> systemPath = List.of();

	private List<String> dists = List.of();

	/**
	 * @param className the class to emit, in either the {@code com.acme.Kernels} or the
	 * {@code com/acme/Kernels} spelling
	 */
	public JvmSourceCompiler(String className) {
		this.internalClassName = className.replace('.', '/');
	}

	/**
	 * @param dynamic {@code --dynamic}: late binding, no static resolution
	 */
	public JvmSourceCompiler dynamic(boolean dynamic) {
		this.dynamic = dynamic;
		return this;
	}

	/**
	 * @param optimize the {@code --optimize} level
	 */
	public JvmSourceCompiler optimize(OptimizeLevel optimize) {
		this.optimize = optimize;
		return this;
	}

	/**
	 * @param simd {@code --simd}: the Vector API kernels
	 */
	public JvmSourceCompiler simd(boolean simd) {
		this.simd = simd;
		return this;
	}

	/**
	 * @param blas {@code --blas}: the native CBLAS kernels
	 */
	public JvmSourceCompiler blas(boolean blas) {
		this.blas = blas;
		return this;
	}

	/**
	 * @param gpu {@code --gpu}: the CUDA / Metal kernels
	 */
	public JvmSourceCompiler gpu(boolean gpu) {
		this.gpu = gpu;
		return this;
	}

	/**
	 * @param parallel {@code --parallel}: split the {@code --simd} kernels across cores
	 */
	public JvmSourceCompiler parallel(boolean parallel) {
		this.parallel = parallel;
		return this;
	}

	/**
	 * @param noPrune {@code --no-prune}: keep every spliced library definition
	 */
	public JvmSourceCompiler noPrune(boolean noPrune) {
		this.noPrune = noPrune;
		return this;
	}

	/**
	 * @param noMain {@code --no-main}: library mode, entered through its exports only
	 */
	public JvmSourceCompiler noMain(boolean noMain) {
		this.noMain = noMain;
		return this;
	}

	/**
	 * @param baseDir the directory a {@code (load "...")} resolves against
	 */
	public JvmSourceCompiler baseDir(@Nullable String baseDir) {
		this.baseDir = baseDir;
		return this;
	}

	/**
	 * @param systemPath the ASDF system search path ({@code --system-path})
	 */
	public JvmSourceCompiler systemPath(List<String> systemPath) {
		this.systemPath = List.copyOf(systemPath);
		return this;
	}

	/**
	 * @param dists the Quicklisp-format distributions ({@code --dist})
	 */
	public JvmSourceCompiler dists(List<String> dists) {
		this.dists = List.copyOf(dists);
		return this;
	}

	/**
	 * Compiles a source text.
	 * <p>
	 * A failure carries the frontend's {@code file:line:column:} prefix, exactly as the
	 * command line reports it, so a build tool can hand the diagnostic straight to an
	 * IDE.
	 * @param source the program text
	 * @param entryFile the path the text was read from, for diagnostics and for
	 * {@code (load ...)} provenance
	 * @return the emitted class and the runtime classes that travel with it
	 */
	public Result compile(String source, @Nullable String entryFile) {
		return run(source, entryFile, false).orElseThrow();
	}

	/**
	 * Compiles a source text only if the program it expands to declares at least one
	 * {@code rontolisp:jvm-export}, and answers empty otherwise.
	 * <p>
	 * This is what a SOURCE SET compiles through: a directory of Lisp is ordinary Lisp,
	 * of which only the files that declare an export have a Java caller at all. The
	 * question is asked of the EXPANDED program rather than of the text, so an export a
	 * {@code (load ...)}ed file or a user macro contributes counts -- it is the same list
	 * the backend collects its directives from.
	 * @param source the program text
	 * @param entryFile the path the text was read from, for diagnostics and for
	 * {@code (load ...)} provenance
	 * @return the emitted class, or empty when the program exports nothing
	 */
	public Optional<Result> compileIfExported(String source, @Nullable String entryFile) {
		return run(source, entryFile, true);
	}

	private Optional<Result> run(String source, @Nullable String entryFile, boolean onlyIfExported) {
		return CompileDiagnostics.recording(() -> {
			CompileFrontend.Result frontend = CompileFrontend.run(source, entryFile, this.baseDir, this.systemPath,
					DistClient.createDefault(this.dists), false, this.dynamic, false, false, false, false, null, false,
					this.noPrune);
			if (onlyIfExported && frontend.program().stream().noneMatch(JvmExportDirective::isExportForm)) {
				return Optional.empty();
			}
			return Optional.of(compileProgram(frontend.program(), frontend.features()));
		});
	}

	/**
	 * The backend half alone, for a caller that has already run the front end -- which
	 * the CLI has, because its front end is shared with the WASM backends.
	 * @param program the expanded program
	 * @param features the feature set it was read with
	 * @return the emitted class and the runtime classes that travel with it
	 */
	Result compileProgram(List<LispVal> program, Features features) {
		// The JVM backend cannot parse PEM in hand-assembled bytecode, so rewrite
		// rontolisp:tls-listen-pem to embed the compile-time-parsed PKCS12 keystore
		// (WASM keeps tls-listen-pem, which its compiler rejects outright).
		JvmLispCompiler compiler = new JvmLispCompiler(this.internalClassName, this.dynamic, this.optimize, this.simd,
				this.blas, this.gpu, this.parallel)
			.noMain(this.noMain)
			.runtimeFeatures(features.names());
		byte[] bytes = compiler.compile(TlsPemInliner.inline(program, this.baseDir));
		// A :float-vector / :float-matrix export hands out a handle class; it travels
		// beside the program's own class so the artifact still has no dependency
		// (.kb/jvm-export.md).
		return new Result(this.internalClassName, bytes, compiler.runtimeClassFiles());
	}

	/**
	 * A compiled class.
	 *
	 * @param internalClassName the emitted class's internal (slash-separated) name
	 * @param classBytes the class file
	 * @param runtimeClasses the classes that must travel beside it, keyed by their
	 * canonical {@code path/Name.class}; empty unless a handle-typed export declared one
	 */
	public record Result(String internalClassName, byte[] classBytes, Map<String, byte[]> runtimeClasses) {

		/**
		 * @return the emitted class's binary ({@code com.acme.Kernels}) name
		 */
		public String className() {
			return this.internalClassName.replace('/', '.');
		}

	}

}
