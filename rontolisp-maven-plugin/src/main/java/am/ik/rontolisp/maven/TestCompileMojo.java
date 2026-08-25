package am.ik.rontolisp.maven;

import java.io.File;
import java.util.Objects;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

/**
 * The test twin of {@link CompileMojo}: {@code src/test/lisp} into
 * {@code target/test-classes}, bound to {@code process-test-sources} for the same reason
 * -- {@code src/test/java} compiles against what it writes.
 */
@Mojo(name = "testCompile", defaultPhase = LifecyclePhase.PROCESS_TEST_SOURCES, threadSafe = true)
public class TestCompileMojo extends AbstractLispCompileMojo {

	// Maven injects the @Parameter fields after construction, so each is @Nullable and
	// the accessor below is the one place that turns the injection into the non-null
	// contract the base class declares.
	/** The directory holding the test {@code .lisp} tree. */
	@Parameter(defaultValue = "${project.basedir}/src/test/lisp")
	private @Nullable File testSourceDirectory;

	/** Where the test classes go. */
	@Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
	private @Nullable File testOutputDirectory;

	/** The test twin of {@link CompileMojo}'s status file. */
	@Parameter(defaultValue = "${project.build.directory}/rontolisp/test-compile-status.txt", readonly = true)
	private @Nullable File statusFile;

	@Override
	protected File statusFile() {
		return Objects.requireNonNull(this.statusFile, "statusFile is required");
	}

	@Override
	protected File sourceDirectory() {
		return Objects.requireNonNull(this.testSourceDirectory, "testSourceDirectory is required");
	}

	@Override
	protected File outputDirectory() {
		return Objects.requireNonNull(this.testOutputDirectory, "testOutputDirectory is required");
	}

	@Override
	protected String description() {
		return "Lisp test source";
	}

}
