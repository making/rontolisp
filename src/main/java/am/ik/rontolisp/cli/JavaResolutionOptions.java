package am.ik.rontolisp.cli;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.maven.Artifact;
import am.ik.maven.RemoteRepository;
import org.jspecify.annotations.Nullable;

/**
 * The options that decide which Java classes a program reaches and how its {@code java:}
 * call sites resolve: {@code --java-classpath}, {@code --java-dep} and
 * {@code --java-repository}, {@code --java-snapshots} and
 * {@code --java-update-snapshots}, the program's Java class path (what the interpreter
 * loads classes from, what a JVM compile resolves its sites against after the JDK, what a
 * compiled jar runs with); {@code --java-release}, the JDK release a JVM compile resolves
 * against; {@code --warn-java-reflection}, which reports every site left to run-time
 * reflection (the interpreter's {@code java:*warn-on-reflection*}); and
 * {@code --java-static}, which makes every site that needs the reflective bridge a
 * compile error.
 *
 * @param release the Java release to read the JDK's {@code ct.sym} for, or {@code null}
 * for the newest the JDK holds
 * @param classpath directories and jar/zip archives searched after the JDK
 * @param dependencies the Maven coordinates of {@code --java-dep}, in the order given
 * @param repositories the {@code --java-repository} entries, in the order given, then
 * {@code --java-snapshots}' {@value #SNAPSHOTS_ID}: searched after Maven Central (an
 * entry with the id {@code central} replaces Central's URL)
 * @param updateSnapshots whether the coordinates resolve with Maven's {@code -U}: every
 * repository is asked again for what its update policy would keep
 * @param warnReflection whether an unresolved site is reported
 * @param javaStatic whether a site that needs the bridge is a compile error
 */
record JavaResolutionOptions(@Nullable Integer release, List<Path> classpath, List<String> dependencies,
		List<RemoteRepository> repositories, boolean updateSnapshots, boolean warnReflection, boolean javaStatic) {

	// [ID=]URL: an id is a Maven repository id (no URL punctuation), so a URL whose own
	// query holds a '=' is not mistaken for one.
	/** The id {@code --java-snapshots} gives Central's snapshot repository. */
	static final String SNAPSHOTS_ID = "central-snapshots";

	/** The URL of Central's snapshot repository. */
	static final String SNAPSHOTS_URL = "https://central.sonatype.com/repository/maven-snapshots/";

	private static final Pattern REPOSITORY = Pattern.compile("([A-Za-z0-9_.-]+)=(.+)");

	/** No option given. */
	static final JavaResolutionOptions NONE = new JavaResolutionOptions(null, List.of(), List.of(), List.of(), false,
			false, false);

	/**
	 * Reads the options off the command line.
	 * @param options the parsed command line
	 * @return the options
	 * @throws IllegalArgumentException when {@code --java-release} is not a release
	 * number or a {@code --java-dep} is not Maven coordinates
	 */
	static JavaResolutionOptions from(CliOptions options) {
		String release = options.get("--java-release");
		Integer number = null;
		if (release != null) {
			try {
				number = Integer.valueOf(release.strip());
			}
			catch (NumberFormatException ex) {
				throw new IllegalArgumentException(
						"--java-release takes a Java release number, e.g. --java-release 21");
			}
		}
		List<Path> classpath = new ArrayList<>();
		String joined = options.get("--java-classpath");
		if (joined != null) {
			for (String entry : joined.split(File.pathSeparator)) {
				if (!entry.isBlank()) {
					classpath.add(Path.of(entry.strip()));
				}
			}
		}
		List<String> dependencies = new ArrayList<>();
		String coordinates = options.get("--java-dep");
		if (coordinates != null) {
			// A repeated --java-dep arrives newline-joined (CliOptions.repeatableKeys).
			for (String coordinate : coordinates.split("\n")) {
				if (!coordinate.isBlank()) {
					try {
						Artifact.parse(coordinate.strip());
					}
					catch (IllegalArgumentException ex) {
						throw new IllegalArgumentException("--java-dep: " + ex.getMessage(), ex);
					}
					dependencies.add(coordinate.strip());
				}
			}
		}
		List<RemoteRepository> repositories = repositories(options.get("--java-repository"),
				options.contains("--java-snapshots"));
		if (!repositories.isEmpty() && dependencies.isEmpty() && !options.contains("--java-source-dep")) {
			throw new IllegalArgumentException(
					"--java-repository and --java-snapshots name where --java-dep and --java-source-dep coordinates"
							+ " resolve from, so they need a --java-dep or a --java-source-dep");
		}
		return new JavaResolutionOptions(number, List.copyOf(classpath), List.copyOf(dependencies), repositories,
				options.contains("--java-update-snapshots"), options.contains("--warn-java-reflection"),
				options.contains("--java-static"));
	}

	// A repeated --java-repository arrives newline-joined (CliOptions.repeatableKeys).
	// --java-snapshots is --java-repository central-snapshots=<Central's snapshot URL>,
	// last in the order.
	private static List<RemoteRepository> repositories(@Nullable String joined, boolean snapshots) {
		List<RemoteRepository> repositories = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (String entry : joined == null ? new String[0] : joined.split("\n")) {
			if (entry.isBlank()) {
				continue;
			}
			Matcher matcher = REPOSITORY.matcher(entry.strip());
			String id = matcher.matches() ? matcher.group(1) : "java-repository-" + (repositories.size() + 1);
			String url = matcher.matches() ? matcher.group(2) : entry.strip();
			if (!ids.add(id)) {
				throw new IllegalArgumentException("--java-repository: the id '" + id + "' is given twice");
			}
			try {
				repositories.add(new RemoteRepository(id, url));
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("--java-repository: " + ex.getMessage(), ex);
			}
		}
		if (snapshots) {
			if (!ids.add(SNAPSHOTS_ID)) {
				throw new IllegalArgumentException("--java-snapshots adds the repository '" + SNAPSHOTS_ID
						+ "', which --java-repository gives too");
			}
			repositories.add(new RemoteRepository(SNAPSHOTS_ID, SNAPSHOTS_URL));
		}
		return repositories;
	}

	/**
	 * @return whether an option only a JVM compile reads was given
	 * ({@code --java-release} or {@code --java-static})
	 */
	boolean namesCompileOnly() {
		return this.release != null || this.javaStatic;
	}

	/**
	 * @return whether the program was given a Java class path ({@code --java-classpath}
	 * or {@code --java-dep})
	 */
	boolean namesClassPath() {
		return !this.classpath.isEmpty() || !this.dependencies.isEmpty();
	}

}
