package am.ik.rontolisp.cli;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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

	/**
	 * The roots the command line names, the coordinates resolved now.
	 * @param options the command line
	 * @param repositories the {@code --java-repository} repositories
	 * @param resolver builds the resolver of the coordinates from the repositories to
	 * search, or {@code null} for Maven's settings and local repository
	 * @param err where a dependency that ships no sources is told
	 * @return the roots, absolute, in the order they are searched
	 * @throws IllegalArgumentException if an entry does not exist, a coordinate is
	 * malformed or does not resolve, or a coordinate named has no sources jar
	 */
	static List<String> of(CliOptions options, List<RemoteRepository> repositories,
			@Nullable Function<List<RemoteRepository>, MavenResolver> resolver, PrintStream err) {
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
			return List.copyOf(roots);
		}
		List<RemoteRepository> searched = JavaClassPath.repositories(repositories);
		MavenResolver maven = resolver != null ? resolver.apply(searched)
				: JavaClassPath.mavenResolver(searched, "--java-source-dep");
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
			}
		}
		catch (MavenResolutionException ex) {
			throw new IllegalArgumentException("--java-source-dep: " + ex.getMessage(), ex);
		}
		return List.copyOf(roots);
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
