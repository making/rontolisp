package am.ik.rontolisp.cli;

import org.jspecify.annotations.Nullable;

/**
 * The three options that describe a JVM ARTIFACT rather than the code inside it:
 * {@code --class-name}, {@code --maven-coordinates} and {@code --emit-pom}.
 *
 * @param className the fully qualified class name to emit, or {@code null} to take it
 * from the {@code -o} path
 * @param coordinates the Maven coordinates to embed in a jar, or {@code null} for none
 * @param emitPom whether to write the generated pom next to the jar as well
 */
record JvmArtifactOptions(@Nullable String className, @Nullable MavenCoordinates coordinates, boolean emitPom) {

	/** No artifact options at all: what every non-JVM output gets. */
	static final JvmArtifactOptions NONE = new JvmArtifactOptions(null, null, false);

	/**
	 * Reads the three options off the command line.
	 * @param options the parsed command line
	 * @return the artifact options
	 */
	static JvmArtifactOptions from(CliOptions options) {
		String className = options.get("--class-name");
		String coordinates = options.get("--maven-coordinates");
		return new JvmArtifactOptions(className == null ? null : normalizeClassName(className),
				coordinates == null ? null : MavenCoordinates.parse(coordinates), options.contains("--emit-pom"));
	}

	/**
	 * The internal (slash-separated) name of the class to emit.
	 * <p>
	 * Without {@code --class-name} the {@code -o} path IS the class name -- that is how
	 * {@code -o com/acme/Kernels.class} has always produced {@code com.acme.Kernels} --
	 * and a jar output has no such path to read, so the flag is a CONSEQUENCE of jar
	 * output rather than a convenience.
	 * @param outputFile the {@code -o} path
	 * @return the internal class name
	 */
	String internalClassName(String outputFile) {
		if (this.className != null) {
			return this.className.replace('.', '/');
		}
		if (!outputFile.endsWith(".class")) {
			throw new UnsupportedOperationException("-o " + outputFile + " does not name a class, so the class name"
					+ " has to be given: add --class-name com.example.Kernels");
		}
		return outputFile.substring(0, outputFile.length() - ".class".length());
	}

	/**
	 * Where the class file and the runtime classes beside it are rooted: the {@code -o}
	 * path with the class's own package path taken off, so
	 * {@code -o out/com/acme/Kernels.class} roots them at {@code out/} exactly as the
	 * path-derived name always did. A {@code -o} path that does NOT end in the class's
	 * package path (only reachable by naming the class explicitly) roots them beside the
	 * output file instead.
	 * @param outputFile the {@code -o} path
	 * @param internalClassName the internal class name being emitted
	 * @return the root, ending in a separator or empty
	 */
	static String classRoot(String outputFile, String internalClassName) {
		String classPath = internalClassName + ".class";
		if (outputFile.endsWith(classPath)) {
			return outputFile.substring(0, outputFile.length() - classPath.length());
		}
		int separator = Math.max(outputFile.lastIndexOf('/'), outputFile.lastIndexOf(java.io.File.separatorChar));
		return separator < 0 ? "" : outputFile.substring(0, separator + 1);
	}

	private static String normalizeClassName(String className) {
		// A path spelling is what someone who has been writing -o com/acme/Kernels.class
		// will reach for first; it means the same class.
		String normalized = className.replace('/', '.');
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("--class-name requires a class name");
		}
		for (String segment : normalized.split("\\.", -1)) {
			if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))
					|| !segment.chars().skip(1).allMatch(Character::isJavaIdentifierPart)) {
				throw new IllegalArgumentException("--class-name '" + className + "' is not a Java class name:" + " '"
						+ segment + "' is not a Java identifier");
			}
		}
		return normalized;
	}

}
