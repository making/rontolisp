package am.ik.rontolisp.maven;

import java.io.File;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Compiles {@code src/main/lisp} into {@code target/classes}, so the Lisp is just another
 * source set and {@code maven-jar-plugin} packages it with no further configuration.
 * <p>
 * <strong>Bound to {@code process-sources}, not to {@code compile}.</strong> The classes
 * this goal writes -- an exported kernel's own class, and the
 * {@code am.ik.rontolisp.runtime} handle type a {@code :float-vector} export hands out --
 * are what {@code src/main/java} compiles AGAINST, so they have to exist before javac
 * runs. Maven injects the default lifecycle bindings ahead of a POM-declared plugin, so a
 * goal bound to {@code compile} would run AFTER {@code maven-compiler-plugin:compile};
 * the earlier phase is the only ordering that cannot be broken by declaration order. It
 * is what {@code kotlin-maven-plugin} recommends for the same mixed-source reason.
 */
@Mojo(name = "compile", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public class CompileMojo extends AbstractLispCompileMojo {

	/** The directory holding the {@code .lisp} tree. */
	@Parameter(defaultValue = "${project.basedir}/src/main/lisp")
	private File sourceDirectory;

	/** Where the classes go: {@code target/classes}, which is what gets jarred. */
	@Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
	private File outputDirectory;

	/**
	 * The previous run's source-to-class mapping, which is what the staleness check
	 * compares against: a source set whose files need not each produce a class cannot
	 * read its own state off the output directory alone.
	 */
	@Parameter(defaultValue = "${project.build.directory}/rontolisp/compile-status.txt", readonly = true)
	private File statusFile;

	@Override
	protected File statusFile() {
		return this.statusFile;
	}

	@Override
	protected File sourceDirectory() {
		return this.sourceDirectory;
	}

	@Override
	protected File outputDirectory() {
		return this.outputDirectory;
	}

	@Override
	protected String description() {
		return "Lisp source";
	}

}
