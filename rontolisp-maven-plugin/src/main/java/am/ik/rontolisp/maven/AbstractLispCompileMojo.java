package am.ik.rontolisp.maven;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * What the {@code compile} and {@code testCompile} goals share: every flag that reaches
 * the rontolisp JVM backend, named exactly as the command line names it, and the run that
 * turns one source directory into classes.
 */
abstract class AbstractLispCompileMojo extends AbstractMojo {

	/** Skips the goal entirely. */
	@Parameter(property = "rontolisp.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * {@code --simd}: route the {@code vec:} / {@code linalg:} kernels at the Vector API.
	 * The consuming JVM gets the vector kernels only with
	 * {@code --add-modules jdk.incubator.vector}; without it the class degrades to the
	 * portable scalar reference.
	 */
	@Parameter(property = "rontolisp.simd", defaultValue = "false")
	private boolean simd;

	/** {@code --blas}: route the matrix kernels at a native CBLAS, probed at run time. */
	@Parameter(property = "rontolisp.blas", defaultValue = "false")
	private boolean blas;

	/** {@code --gpu}: route the matrix kernels at CUDA or Metal, probed at run time. */
	@Parameter(property = "rontolisp.gpu", defaultValue = "false")
	private boolean gpu;

	/** {@code --parallel}: split the {@code --simd} kernels across cores. Needs simd. */
	@Parameter(property = "rontolisp.parallel", defaultValue = "false")
	private boolean parallel;

	/** {@code --dynamic}: late binding, so any name may be resolved at run time. */
	@Parameter(property = "rontolisp.dynamic", defaultValue = "false")
	private boolean dynamic;

	/** {@code --optimize}: {@code default}, {@code size} or {@code off}. */
	@Parameter(property = "rontolisp.optimize", defaultValue = "default")
	private String optimize = "default";

	/** {@code --no-prune}: keep every spliced library definition. */
	@Parameter(property = "rontolisp.noPrune", defaultValue = "false")
	private boolean noPrune;

	/**
	 * {@code --no-main}: emit a library class, entered through its
	 * {@code rontolisp:jvm-export} declarations only.
	 * <p>
	 * On by default, because a Lisp SOURCE SET is a library by definition: the Java half
	 * of the project calls the exports and nothing else. It also decides WHICH files
	 * compile, because a class is worth emitting only when it has an entry point. On,
	 * that entry point is the exports, so a file that declares none is ordinary Lisp --
	 * loaded by the files that do, or run by the interpreter -- and is left alone. Off,
	 * every file has {@code main} and every file compiles, the way the command line
	 * compiles a program.
	 */
	@Parameter(property = "rontolisp.noMain", defaultValue = "true")
	private boolean noMain = true;

	/**
	 * Servlet mode ({@code -o app.war}'s mode): the class registers itself with a Servlet
	 * 6 container instead of running a {@code main}, so the goal writes the
	 * {@code jakarta.servlet.ServletContainerInitializer} service declaration beside the
	 * classes and every compiled file is treated as its own program -- {@link #noMain} is
	 * ignored (a war has no {@code main} to remove), so a file with neither a
	 * {@code rontolisp:jvm-export} nor a {@code rontolisp:http-handler} directive fails
	 * the build rather than silently staying Lisp. Shared code belongs in a file loaded
	 * with {@code (load ...)} from the handler, not as a sibling under this source
	 * directory. Requires {@code <packaging>war</packaging>} -- {@link CompileMojo}
	 * refuses the build, naming which one is missing, when this and the packaging
	 * disagree.
	 */
	@Parameter(property = "rontolisp.servlet", defaultValue = "false")
	private boolean servlet;

	/** The project's packaging, checked against {@link #servlet}. Not user-settable. */
	@Parameter(defaultValue = "${project.packaging}", readonly = true)
	private String packaging = "jar";

	/** {@code --system-path}: extra directories the ASDF system loader searches. */
	@Parameter
	private List<String> systemPath = List.of();

	/**
	 * {@code --dist}: extra Quicklisp-format distributions {@code ql:quickload} reads.
	 */
	@Parameter
	private List<String> dists = List.of();

	/** Where a war built by this plugin writes its self-registration. */
	private static final String SERVLET_SERVICE_FILE = "META-INF/services/jakarta.servlet.ServletContainerInitializer";

	/**
	 * The container hands {@code @HandlesTypes(RontoHttpServer.Handler.class)} candidates
	 * to this class; it is the same one line in every war rontolisp ever emits
	 * ({@link am.ik.rontolisp.cli.JvmSourceCompiler}'s CLI twin, {@code JvmWarWriter}, in
	 * the core module writes it into an archive -- this goal writes it into loose
	 * classes, for {@code maven-war-plugin} to pick up the same way it picks up every
	 * other file under {@code target/classes}).
	 */
	private static final String SERVLET_INITIALIZER = "am.ik.rontolisp.runtime.RontoHttpServletInitializer";

	/**
	 * @return the directory holding the {@code .lisp} tree
	 */
	protected abstract File sourceDirectory();

	/**
	 * @return the directory the {@code .class} files are written to
	 */
	protected abstract File outputDirectory();

	/**
	 * @return where the previous run's source-to-class mapping is recorded, for the
	 * staleness check
	 */
	protected abstract File statusFile();

	/**
	 * @return what this goal calls a source set, for the log line
	 */
	protected abstract String description();

	/**
	 * Checked by {@link CompileMojo} only: whether {@link #servlet} agrees with the
	 * project's packaging. {@link TestCompileMojo}'s classes are never packaged into a
	 * war, so it keeps the default no-op and places no such constraint on
	 * {@code src/test/lisp}.
	 */
	protected void validateServletPackaging() throws MojoFailureException {
	}

	/**
	 * @return the value {@link #servlet} was configured with
	 */
	protected final boolean servlet() {
		return this.servlet;
	}

	/**
	 * @return the project's packaging ({@code ${project.packaging}})
	 */
	protected final String packaging() {
		return this.packaging;
	}

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.skip) {
			getLog().info("Skipping rontolisp compilation (rontolisp.skip)");
			return;
		}
		validateServletPackaging();
		if (this.servlet) {
			writeServletServiceFile();
		}
		File sourceDirectory = sourceDirectory();
		if (!sourceDirectory.isDirectory()) {
			getLog().debug("No " + description() + " directory: " + sourceDirectory);
			return;
		}
		// A war has no main to remove: servlet mode forces every file to be its own
		// program (compiled unconditionally, not gated on jvm-export), the same shape
		// noMain(false) already gives the command line.
		LispSourceSet sourceSet = new LispSourceSet(sourceDirectory.toPath(), outputDirectory().toPath(),
				statusFile().toPath(), this.noMain && !this.servlet, this::configure);
		LispSourceSet.Result result;
		try {
			result = sourceSet.compile();
		}
		catch (LispCompilationException ex) {
			// The rontolisp diagnostic verbatim: it already carries file:line:column, so
			// an IDE can jump to it.
			throw new MojoFailureException(ex.getMessage(), ex);
		}
		catch (IllegalArgumentException ex) {
			throw new MojoFailureException(ex.getMessage(), ex);
		}
		catch (IOException | RuntimeException ex) {
			throw new MojoExecutionException("rontolisp compilation failed: " + ex.getMessage(), ex);
		}
		if (result.sources() == 0) {
			getLog().debug("No Lisp sources in " + sourceDirectory);
			return;
		}
		if (result.upToDate()) {
			getLog().info("Nothing to compile - all " + result.sources() + " Lisp source"
					+ (result.sources() == 1 ? " is" : "s are") + " up to date");
			return;
		}
		getLog().info("Compiled " + result.classes().size() + " of " + result.sources() + " Lisp source"
				+ (result.sources() == 1 ? "" : "s") + " to " + outputDirectory());
		for (String className : result.classes()) {
			getLog().debug("  " + className);
		}
		// Not a warning: an unexported file is the NORMAL case in a source set. It is
		// support code the exported files load, or a program the interpreter runs.
		if (!result.uncompiled().isEmpty()) {
			getLog().info(result.uncompiled().size() + " Lisp source" + (result.uncompiled().size() == 1 ? "" : "s")
					+ " declare no (rontolisp:jvm-export ...) and stay Lisp");
			for (String source : result.uncompiled()) {
				getLog().debug("  " + source);
			}
		}
	}

	private void configure(JvmSourceCompiler compiler) {
		compiler.simd(this.simd)
			.blas(this.blas)
			.gpu(this.gpu)
			.parallel(this.parallel)
			.dynamic(this.dynamic)
			.optimize(OptimizeLevel.parse(this.optimize))
			.noPrune(this.noPrune)
			.servlet(this.servlet)
			.systemPath(this.systemPath == null ? List.of() : this.systemPath)
			.dists(this.dists == null ? List.of() : this.dists);
	}

	// The war's only non-class file, and the same one line in every war rontolisp ever
	// emits: no program name, no web.xml, nothing for a user to wire up. Written
	// unconditionally (idempotent, one line) rather than gated on the compile actually
	// running, so it exists even on an up-to-date build that recompiled nothing.
	private void writeServletServiceFile() throws MojoExecutionException {
		Path target = outputDirectory().toPath().resolve(SERVLET_SERVICE_FILE);
		try {
			Path parent = target.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(target, SERVLET_INITIALIZER + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException | UncheckedIOException ex) {
			throw new MojoExecutionException("failed writing " + SERVLET_SERVICE_FILE, ex);
		}
	}

}
