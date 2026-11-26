package am.ik.rontolisp.cli;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.maven.Artifact;
import am.ik.maven.Dependency;
import am.ik.maven.DependencyGraph;
import am.ik.maven.MavenResolutionException;
import am.ik.maven.MavenResolver;
import am.ik.maven.RemoteRepository;
import org.jspecify.annotations.Nullable;

/**
 * The source path of a program written in the Java subset: the roots its libraries are
 * read from AS SOURCE, lowered with the program as far as it reaches them
 * ({@code .kb/java-frontend.md}, "A library from the source path"). The
 * {@code --java-source-path} entries, directories and jars, then the sources jar of each
 * {@code --java-source-dep} coordinate and of what it depends on at run time.
 *
 * <p>
 * Not the Java class path ({@link JavaClassPath}): that one is class files the host's
 * class loader runs for a {@code java:} call, which a compiled program carries beside it
 * and a {@code .wasm} cannot hold; a library on the source path is compiled into the
 * output on every backend and leaves nothing to carry.
 */
final class JavaSourcePath {

	private JavaSourcePath() {
	}

	/** The coordinates of the shared repository of reachability metadata's releases. */
	static final String METADATA_GROUP = "org.graalvm.buildtools";

	static final String METADATA_ARTIFACT = "graalvm-reachability-metadata";

	/**
	 * What the command line names a Java program's libraries by.
	 *
	 * @param roots the source path, absolute, in the order it is searched
	 * @param metadataRepository the shared repository of reachability metadata
	 * ({@code --java-metadata-repository}), a directory or a zip, or {@code null} for
	 * none
	 * @param coordinates the {@code group:artifact:version} of each library on the source
	 * path that came from a coordinate, which the repository is asked for; none where
	 * there is no repository
	 */
	record Libraries(List<String> roots, @Nullable String metadataRepository, List<String> coordinates) {
	}

	/**
	 * The roots the command line names, the coordinates resolved now, and the shared
	 * repository of reachability metadata where it names one.
	 * @param options the command line
	 * @param repositories the {@code --java-repository} repositories
	 * @param resolver builds the resolver of the coordinates from the repositories to
	 * search, or {@code null} for Maven's settings and local repository
	 * @param err where a dependency that ships no sources is told
	 * @return the libraries
	 * @throws IllegalArgumentException if an entry does not exist, a coordinate is
	 * malformed or does not resolve, a coordinate named has no sources jar, or the
	 * repository is named without a coordinate or does not resolve
	 */
	static Libraries of(CliOptions options, List<RemoteRepository> repositories,
			@Nullable JavaDependencyResolver resolver, PrintStream err) {
		String metadata = options.get("--java-metadata-repository");
		List<String> coordinates = new ArrayList<>();
		List<String> roots = new ArrayList<>();
		String joined = options.get("--java-source-path");
		if (joined != null) {
			for (String entry : joined.split(File.pathSeparator)) {
				if (entry.isBlank()) {
					continue;
				}
				Path path = Path.of(entry.strip());
				if (!Files.isDirectory(path) && !Files.isRegularFile(path)) {
					throw new IllegalArgumentException("--java-source-path: no such directory or jar: " + path);
				}
				add(roots, path);
			}
		}
		List<Dependency> requested = new ArrayList<>();
		for (String coordinate : coordinates(options)) {
			requested.add(Dependency.of(Artifact.parse(coordinate)));
		}
		if (requested.isEmpty()) {
			if (metadata != null) {
				throw new IllegalArgumentException("--java-metadata-repository is read for the libraries"
						+ " --java-source-dep resolves, so it needs a --java-source-dep");
			}
			return new Libraries(List.copyOf(roots), null, List.of());
		}
		List<RemoteRepository> searched = JavaClassPath.repositories(repositories);
		boolean update = options.contains("--java-update-snapshots");
		MavenResolver maven = resolver != null ? resolver.resolver(searched, update)
				: JavaClassPath.mavenResolver(searched, "--java-source-dep", update);
		try {
			DependencyGraph graph = maven.resolve(requested, List.of());
			for (String warning : graph.warnings()) {
				err.println("warning: --java-source-dep: " + warning);
			}
			for (Artifact artifact : graph.runtimeClassPath()) {
				Artifact sources = new Artifact(artifact.groupId(), artifact.artifactId(), artifact.version(),
						"sources", "jar");
				Path jar;
				try {
					jar = maven.artifact(sources);
				}
				catch (MavenResolutionException ex) {
					boolean named = requested.stream()
						.anyMatch(dependency -> dependency.artifact().groupId().equals(artifact.groupId())
								&& dependency.artifact().artifactId().equals(artifact.artifactId()));
					if (named) {
						throw new IllegalArgumentException("--java-source-dep: " + artifact
								+ " has no sources jar: a library of a Java program is compiled from its source ("
								+ ex.getMessage() + ")", ex);
					}
					// what a named library depends on may never be reached: a class of it
					// that is, is refused where it is
					err.println("warning: --java-source-dep: no sources jar for " + artifact
							+ ", which a library named depends on: its classes are not there to compile");
					continue;
				}
				add(roots, jar);
				coordinates.add(artifact.groupId() + ":" + artifact.artifactId() + ":" + artifact.version());
			}
		}
		catch (MavenResolutionException ex) {
			throw new IllegalArgumentException("--java-source-dep: " + ex.getMessage(), ex);
		}
		if (metadata == null) {
			return new Libraries(List.copyOf(roots), null, List.of());
		}
		return new Libraries(List.copyOf(roots), metadataRepository(metadata, maven), List.copyOf(coordinates));
	}

	/**
	 * The shared repository of reachability metadata a value of
	 * {@code --java-metadata-repository} names: a directory or a zip of its layout, or a
	 * version of its releases' {@code repository} zip, fetched as a sources jar is.
	 */
	private static String metadataRepository(String given, MavenResolver maven) {
		Path path = Path.of(given.strip());
		if (Files.isDirectory(path) || Files.isRegularFile(path)) {
			return path.toAbsolutePath().normalize().toString();
		}
		try {
			return maven.artifact(new Artifact(METADATA_GROUP, METADATA_ARTIFACT, given.strip(), "repository", "zip"))
				.toAbsolutePath()
				.toString();
		}
		catch (MavenResolutionException | IllegalArgumentException ex) {
			throw new IllegalArgumentException(
					"--java-metadata-repository: " + given + " is no directory, no zip and no version of "
							+ METADATA_GROUP + ":" + METADATA_ARTIFACT + " that resolves (" + ex.getMessage() + ")",
					ex);
		}
	}

	/**
	 * The {@code --java-source-dep} coordinates, each checked to be one.
	 * @param options the command line
	 * @return the coordinates, in the order given
	 */
	static List<String> coordinates(CliOptions options) {
		List<String> coordinates = new ArrayList<>();
		String given = options.get("--java-source-dep");
		if (given != null) {
			// A repeated --java-source-dep arrives newline-joined
			// (CliOptions.repeatableKeys).
			for (String coordinate : given.split("\n")) {
				if (!coordinate.isBlank()) {
					try {
						Artifact.parse(coordinate.strip());
					}
					catch (IllegalArgumentException ex) {
						throw new IllegalArgumentException("--java-source-dep: " + ex.getMessage(), ex);
					}
					coordinates.add(coordinate.strip());
				}
			}
		}
		return coordinates;
	}

	private static void add(List<String> roots, Path path) {
		String root = path.toAbsolutePath().normalize().toString();
		if (!roots.contains(root)) {
			roots.add(root);
		}
	}

}
