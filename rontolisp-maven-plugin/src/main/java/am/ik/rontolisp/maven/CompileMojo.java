package am.ik.rontolisp.maven;

import java.io.File;
import java.util.Objects;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

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

	// Maven injects the @Parameter fields after construction, so each is @Nullable and
	// the accessor below is the one place that turns the injection into the non-null
	// contract the base class declares.
	/** The directory holding the {@code .lisp} tree. */
	@Parameter(defaultValue = "${project.basedir}/src/main/lisp")
	private @Nullable File sourceDirectory;

	/** Where the classes go: {@code target/classes}, which is what gets jarred. */
	@Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
	private @Nullable File outputDirectory;

	/**
	 * The previous run's source-to-class mapping, which is what the staleness check
	 * compares against: a source set whose files need not each produce a class cannot
	 * read its own state off the output directory alone.
	 */
	@Parameter(defaultValue = "${project.build.directory}/rontolisp/compile-status.txt", readonly = true)
	private @Nullable File statusFile;

	@Override
	protected File statusFile() {
		return Objects.requireNonNull(this.statusFile, "statusFile is required");
	}

	@Override
	protected File sourceDirectory() {
		return Objects.requireNonNull(this.sourceDirectory, "sourceDirectory is required");
	}

	@Override
	protected File outputDirectory() {
		return Objects.requireNonNull(this.outputDirectory, "outputDirectory is required");
	}

	@Override
	protected String description() {
		return "Lisp source";
	}

	// Only this goal's classes are what maven-war-plugin packages into WEB-INF/classes,
	// so only here does a mismatch mean "the war deploys and 404s" (servlet on, war
	// packaging missing) or "this project will never reach maven-war-plugin at all"
	// (war packaging declared, servlet forgotten) -- src/test/lisp is never packaged, so
	// TestCompileMojo places no such constraint on itself.
	@Override
	protected void validateServletPackaging() throws MojoFailureException {
		boolean war = "war".equals(packaging());
		if (servlet() && !war) {
			throw new MojoFailureException("rontolisp.servlet compiles for a Servlet container, but this project's"
					+ " packaging is '" + packaging() + "': add <packaging>war</packaging> to the pom, or drop"
					+ " rontolisp.servlet if this is not meant to serve rontolisp:http-handler");
		}
		if (!servlet() && war) {
			throw new MojoFailureException("<packaging>war</packaging> packages target/classes into a war, but"
					+ " rontolisp.servlet is not set: the compiled program has no"
					+ " jakarta.servlet.ServletContainerInitializer to register it, so the war deploys and every"
					+ " request 404s. Add <rontolisp.servlet>true</rontolisp.servlet> to the plugin's compile"
					+ " execution, or use a non-war packaging if this project does not serve"
					+ " rontolisp:http-handler");
		}
	}

}
