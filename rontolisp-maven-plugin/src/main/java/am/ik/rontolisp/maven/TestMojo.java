package am.ik.rontolisp.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.jspecify.annotations.Nullable;

/**
 * Runs the project's tests with {@code rontolisp test}, on the program rontolisp makes,
 * as surefire runs them on a JDK: a JDK's green run is no proof of the compiled program.
 * <p>
 * A project with a {@code src/test/lisp} tree is a Lisp project: each of its files that
 * defines a rove test is a run. Any other project's tests are its Java ones, the files of
 * {@code src/test/java} surefire's includes and excludes select, run as one program
 * against JUnit Jupiter compiled from the sources jars of the project's test
 * dependencies. Each run is a process of its own in the project's directory, on each
 * backend {@link #backends} names.
 * <p>
 * <strong>Bound to {@code test}</strong>, after surefire. A failed test fails the build
 * with its name; {@code -DskipTests}, {@code -Dmaven.test.skip=true} and
 * {@code -Drontolisp.skip=true} skip the goal.
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.TEST, threadSafe = true,
		requiresDependencyResolution = ResolutionScope.TEST)
public class TestMojo extends AbstractMojo {

	/** The scopes javac compiles {@code src/test/java} against. */
	private static final Set<String> SOURCE_SCOPES = Set.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_PROVIDED,
			Artifact.SCOPE_SYSTEM, Artifact.SCOPE_TEST);

	/** Skips the goal. */
	@Parameter(property = "rontolisp.skip", defaultValue = "false")
	private boolean skip;

	/** Skips the goal, as it skips surefire. */
	@Parameter(property = "skipTests", defaultValue = "false")
	private boolean skipTests;

	/** Skips the goal, as it skips the tests' compile and surefire. */
	@Parameter(property = "maven.test.skip", defaultValue = "false")
	private boolean mavenTestSkip;

	/**
	 * The backends the tests run on, each a run: {@code interpreter}, {@code jvm},
	 * {@code wasm} (WASI Preview 1) and {@code component} (WASI 0.3); the two last need
	 * {@code wasmtime} on {@code PATH}. The interpreter by default, the quickest: it has
	 * no compile.
	 */
	@Parameter(property = "rontolisp.backends", defaultValue = "interpreter")
	private List<String> backends = List.of("interpreter");

	/** The Java test tree: the source tree of the program the tests are. */
	@Parameter(defaultValue = "${project.build.testSourceDirectory}", required = true)
	private @Nullable File testSourceDirectory;

	/** The Lisp test tree, whose presence makes the project a Lisp one. */
	@Parameter(defaultValue = "${project.basedir}/src/test/lisp", required = true)
	private @Nullable File lispTestSourceDirectory;

	/** The Lisp source set, searched for a system's {@code .asd} with the test tree. */
	@Parameter(defaultValue = "${project.basedir}/src/main/lisp", required = true)
	private @Nullable File lispSourceDirectory;

	/**
	 * The test files of the tree, as surefire's: Ant-style patterns of their paths below
	 * it. surefire's default for Java ({@code **}{@code /Test*.java},
	 * {@code **}{@code /*Test.java}, {@code **}{@code /*Tests.java},
	 * {@code **}{@code /*TestCase.java}), every {@code .lisp} for Lisp.
	 */
	@Parameter
	private List<String> includes = new ArrayList<>();

	/**
	 * The files of the tree left out, as surefire's: {@code **}{@code /*$*} for Java by
	 * default, none for Lisp.
	 */
	@Parameter
	private List<String> excludes = new ArrayList<>();

	/**
	 * {@code --java-source-path}: directories and sources jars searched before the
	 * dependencies' sources jars.
	 */
	@Parameter
	private List<File> javaSourcePath = new ArrayList<>();

	/** {@code --system-path}: further directories a Lisp test's systems are found in. */
	@Parameter
	private List<String> systemPath = new ArrayList<>();

	/** {@code --dist}: the dists {@code ql:quickload} may download from. */
	@Parameter
	private List<String> dists = new ArrayList<>();

	@Parameter(defaultValue = "${project.build.directory}/rontolisp/test", readonly = true, required = true)
	private @Nullable File outputDirectory;

	@Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
	private @Nullable File classesDirectory;

	@Parameter(defaultValue = "${project.build.testOutputDirectory}", readonly = true, required = true)
	private @Nullable File testClassesDirectory;

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private @Nullable MavenProject project;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
	private @Nullable RepositorySystemSession repositorySession;

	@Component
	private @Nullable RepositorySystem repositorySystem;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.skip || this.skipTests || this.mavenTestSkip) {
			getLog().info("Tests are skipped (rontolisp test)");
			return;
		}
		List<TestRuns.Backend> chosen = new ArrayList<>();
		try {
			for (String names : this.backends) {
				for (String name : names.split(",")) {
					if (!name.isBlank()) {
						chosen.add(TestRuns.Backend.parse(name));
					}
				}
			}
		}
		catch (IllegalArgumentException ex) {
			throw new MojoFailureException(ex.getMessage(), ex);
		}
		MavenProject project = required(this.project, "project");
		TestRuns runs = new TestRuns(project.getBasedir().toPath(),
				required(this.outputDirectory, "outputDirectory").toPath(), getLog()::info);
		Path lispTree = required(this.lispTestSourceDirectory, "lispTestSourceDirectory").toPath();
		List<String> failures = new ArrayList<>();
		try {
			if (Files.isDirectory(lispTree)) {
				lisp(runs, lispTree, chosen, failures);
			}
			else {
				java(runs, chosen, failures);
			}
		}
		catch (IOException ex) {
			throw new MojoExecutionException("rontolisp test: " + ex.getMessage(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new MojoExecutionException("rontolisp test was interrupted", ex);
		}
		if (!failures.isEmpty()) {
			throw new MojoFailureException(String.join("\n", failures));
		}
	}

	private void java(TestRuns runs, List<TestRuns.Backend> backends, List<String> failures)
			throws IOException, InterruptedException {
		Path tree = required(this.testSourceDirectory, "testSourceDirectory").toPath();
		List<Path> files = TestRuns.select(tree, this.includes.isEmpty() ? TestRuns.JAVA_INCLUDES : this.includes,
				this.excludes.isEmpty() ? TestRuns.JAVA_EXCLUDES : this.excludes);
		if (files.isEmpty()) {
			getLog().info("No tests to run (rontolisp test).");
			return;
		}
		List<Path> sourcePath = sourcePath(tree);
		for (TestRuns.Backend backend : backends) {
			getLog()
				.info("rontolisp test on " + backend + ": " + files.size() + " file" + (files.size() == 1 ? "" : "s")
						+ " of " + required(this.project, "project").getBasedir().toPath().relativize(tree));
			String failure = runs.java(files, sourcePath, backend);
			if (failure != null) {
				failures.add(failure);
			}
		}
	}

	private void lisp(TestRuns runs, Path tree, List<TestRuns.Backend> backends, List<String> failures)
			throws IOException, InterruptedException {
		List<Path> files = TestRuns.roveFiles(
				TestRuns.select(tree, this.includes.isEmpty() ? TestRuns.LISP_INCLUDES : this.includes, this.excludes));
		if (files.isEmpty()) {
			getLog().info("No tests to run (rontolisp test).");
			return;
		}
		List<Path> systemPath = new ArrayList<>();
		Path sources = required(this.lispSourceDirectory, "lispSourceDirectory").toPath();
		if (Files.isDirectory(sources)) {
			systemPath.add(sources);
		}
		systemPath.add(tree);
		for (String directory : this.systemPath) {
			systemPath.add(Path.of(directory));
		}
		for (TestRuns.Backend backend : backends) {
			for (Path file : files) {
				getLog().info("rontolisp test on " + backend + ": "
						+ required(this.project, "project").getBasedir().toPath().relativize(file));
				String failure = runs.lisp(file, tree, systemPath, this.dists, backend);
				if (failure != null) {
					failures.add(failure);
				}
			}
		}
	}

	/**
	 * The source path of the Java tests, in the order of surefire's class path: the
	 * project's test resources and resources, its sources, {@link #javaSourcePath}, then
	 * the sources jar of each dependency javac compiles the tests against.
	 */
	private List<Path> sourcePath(Path tree) {
		List<Path> roots = new ArrayList<>();
		for (File directory : List.of(required(this.testClassesDirectory, "testClassesDirectory"),
				required(this.classesDirectory, "classesDirectory"))) {
			if (directory.isDirectory()) {
				roots.add(directory.toPath());
			}
		}
		MavenProject project = required(this.project, "project");
		List<String> sourceRoots = new ArrayList<>(project.getCompileSourceRoots());
		sourceRoots.addAll(project.getTestCompileSourceRoots());
		for (String root : sourceRoots) {
			Path path = Path.of(root);
			if (Files.isDirectory(path) && !path.toAbsolutePath().normalize().equals(tree.toAbsolutePath().normalize())
					&& !roots.contains(path)) {
				roots.add(path);
			}
		}
		for (File entry : this.javaSourcePath) {
			roots.add(entry.toPath());
		}
		for (Artifact artifact : project.getArtifacts()) {
			if (!SOURCE_SCOPES.contains(artifact.getScope()) || !artifact.getArtifactHandler().isAddedToClasspath()) {
				continue;
			}
			ArtifactRequest request = new ArtifactRequest(new DefaultArtifact(artifact.getGroupId(),
					artifact.getArtifactId(), "sources", "jar", artifact.getVersion()),
					project.getRemoteProjectRepositories(), null);
			try {
				roots.add(required(this.repositorySystem, "repositorySystem")
					.resolveArtifact(required(this.repositorySession, "repositorySession"), request)
					.getArtifact()
					.getFile()
					.toPath());
			}
			catch (ArtifactResolutionException ex) {
				// javac needs only the class file, and the tests may never reach a class
				// of it: one they do is refused there, by name
				getLog().warn("no sources jar for " + artifact.getId()
						+ ": its classes are not there for the tests to compile (" + ex.getMessage() + ")");
			}
		}
		return roots;
	}

	private static <T> T required(@Nullable T value, String name) {
		return Objects.requireNonNull(value, name + " is required");
	}

}
