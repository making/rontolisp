package am.ik.rontolisp.cli;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The options that decide how {@code java:} call sites resolve: {@code --java-release}
 * and {@code --java-classpath}, the classes a compiled JVM program resolves its sites
 * against; {@code --warn-java-reflection}, which reports every site left to run-time
 * reflection (the interpreter's {@code java:*warn-on-reflection*}); and
 * {@code --java-static}, which makes every site that needs the reflective bridge a
 * compile error.
 *
 * @param release the Java release to read the JDK's {@code ct.sym} for, or {@code null}
 * for the newest the JDK holds
 * @param classpath directories and jar/zip archives searched after the JDK
 * @param warnReflection whether an unresolved site is reported
 * @param javaStatic whether a site that needs the bridge is a compile error
 */
record JavaResolutionOptions(@Nullable Integer release, List<Path> classpath, boolean warnReflection,
		boolean javaStatic) {

	/** No option given. */
	static final JavaResolutionOptions NONE = new JavaResolutionOptions(null, List.of(), false, false);

	/**
	 * Reads the options off the command line.
	 * @param options the parsed command line
	 * @return the options
	 * @throws IllegalArgumentException when {@code --java-release} is not a release
	 * number
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
		return new JavaResolutionOptions(number, List.copyOf(classpath), options.contains("--warn-java-reflection"),
				options.contains("--java-static"));
	}

	/**
	 * @return whether an option only a JVM compile reads was given
	 * ({@code --java-release}, {@code --java-classpath} or {@code --java-static})
	 */
	boolean namesClassFiles() {
		return this.release != null || !this.classpath.isEmpty() || this.javaStatic;
	}

}
