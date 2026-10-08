package am.ik.rontolisp.cli;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.maven.Artifact;
import org.jspecify.annotations.Nullable;

/**
 * The options that decide which Java classes a program reaches and how its {@code java:}
 * call sites resolve: {@code --java-classpath} and {@code --java-dep}, the program's Java
 * class path (what the interpreter loads classes from, what a JVM compile resolves its
 * sites against after the JDK, what a compiled jar runs with); {@code --java-release},
 * the JDK release a JVM compile resolves against; {@code --warn-java-reflection}, which
 * reports every site left to run-time reflection (the interpreter's
 * {@code java:*warn-on-reflection*}); and {@code --java-static}, which makes every site
 * that needs the reflective bridge a compile error.
 *
 * @param release the Java release to read the JDK's {@code ct.sym} for, or {@code null}
 * for the newest the JDK holds
 * @param classpath directories and jar/zip archives searched after the JDK
 * @param dependencies the Maven coordinates of {@code --java-dep}, in the order given
 * @param warnReflection whether an unresolved site is reported
 * @param javaStatic whether a site that needs the bridge is a compile error
 */
record JavaResolutionOptions(@Nullable Integer release, List<Path> classpath, List<String> dependencies,
		boolean warnReflection, boolean javaStatic) {

	/** No option given. */
	static final JavaResolutionOptions NONE = new JavaResolutionOptions(null, List.of(), List.of(), false, false);

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
		return new JavaResolutionOptions(number, List.copyOf(classpath), List.copyOf(dependencies),
				options.contains("--warn-java-reflection"), options.contains("--java-static"));
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
