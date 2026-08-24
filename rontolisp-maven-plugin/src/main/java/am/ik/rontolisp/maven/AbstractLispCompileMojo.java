package am.ik.rontolisp.maven;

import java.io.File;
import java.io.IOException;
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
	 * On by default, because a Lisp SOURCE SET is a library by definition: a class with
	 * no exports has no Java caller, and under the default {@code --optimize} its
	 * functions are unreachable from {@code main} and shaken away. A source file with no
	 * export therefore fails the build rather than producing an empty class. Turn it off
	 * to keep a {@code main} beside the exports.
	 */
	@Parameter(property = "rontolisp.noMain", defaultValue = "true")
	private boolean noMain = true;

	/** {@code --system-path}: extra directories the ASDF system loader searches. */
	@Parameter
	private List<String> systemPath = List.of();

	/**
	 * {@code --dist}: extra Quicklisp-format distributions {@code ql:quickload} reads.
	 */
	@Parameter
	private List<String> dists = List.of();

	/**
	 * @return the directory holding the {@code .lisp} tree
	 */
	protected abstract File sourceDirectory();

	/**
	 * @return the directory the {@code .class} files are written to
	 */
	protected abstract File outputDirectory();

	/**
	 * @return what this goal calls a source set, for the log line
	 */
	protected abstract String description();

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.skip) {
			getLog().info("Skipping rontolisp compilation (rontolisp.skip)");
			return;
		}
		File sourceDirectory = sourceDirectory();
		if (!sourceDirectory.isDirectory()) {
			getLog().debug("No " + description() + " directory: " + sourceDirectory);
			return;
		}
		LispSourceSet sourceSet = new LispSourceSet(sourceDirectory.toPath(), outputDirectory().toPath(),
				this::configure);
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
			getLog().info("Nothing to compile - all " + result.sources() + " Lisp class"
					+ (result.sources() == 1 ? "" : "es") + " are up to date");
			return;
		}
		getLog().info("Compiling " + result.sources() + " Lisp source" + (result.sources() == 1 ? "" : "s") + " to "
				+ outputDirectory());
		for (String className : result.classes()) {
			getLog().debug("  " + className);
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
			.noMain(this.noMain)
			.systemPath(this.systemPath == null ? List.of() : this.systemPath)
			.dists(this.dists == null ? List.of() : this.dists);
	}

}
