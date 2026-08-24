package am.ik.rontolisp.maven;

import java.io.File;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * The test twin of {@link CompileMojo}: {@code src/test/lisp} into
 * {@code target/test-classes}, bound to {@code process-test-sources} for the same reason
 * -- {@code src/test/java} compiles against what it writes.
 */
@Mojo(name = "testCompile", defaultPhase = LifecyclePhase.PROCESS_TEST_SOURCES, threadSafe = true)
public class TestCompileMojo extends AbstractLispCompileMojo {

	/** The directory holding the test {@code .lisp} tree. */
	@Parameter(defaultValue = "${project.basedir}/src/test/lisp")
	private File testSourceDirectory;

	/** Where the test classes go. */
	@Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
	private File testOutputDirectory;

	@Override
	protected File sourceDirectory() {
		return this.testSourceDirectory;
	}

	@Override
	protected File outputDirectory() {
		return this.testOutputDirectory;
	}

	@Override
	protected String description() {
		return "Lisp test source";
	}

}
