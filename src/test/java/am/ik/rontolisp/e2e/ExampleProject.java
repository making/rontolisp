package am.ik.rontolisp.e2e;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import am.ik.maven.Dependency;
import am.ik.maven.MavenProject;
import am.ik.maven.MavenResolutionException;
import am.ik.maven.MavenResolver;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * A Java example that is a Maven project ({@code examples/java/<name>/pom.xml}): the
 * libraries its pom names, handed to the command line in the place of the plugin's
 * resolution, so a test compiles the example with exactly what {@code mvn package}
 * compiles it with, and a version is written in the pom alone.
 * <p>
 * A dependency that is a module of this repository -- a directory at the root named by
 * its artifact id, whose pom has its coordinates -- is read from that directory's
 * {@code src/main/java} and {@code src/main/resources}: develop's sources, not the last
 * deployed SNAPSHOT, and no network. Its own compile dependencies follow it, as Maven
 * brings them. Every other dependency of scope {@code compile}, {@code provided} or
 * {@code system} is a coordinate for {@code --java-source-dep}. The project's
 * {@code src/main/resources} goes first on the source path, as the plugin puts
 * {@code target/classes} first.
 * <p>
 * A module's own {@code provided} dependencies (the nullness annotations) are what javac
 * needs to compile its sources -- a JDK leg does -- and on no source path: the front end
 * drops an annotation whose type it does not have, as javac does reading a class file,
 * and a project depending on the module's jar compiles without them.
 *
 * @param sources the project's {@code src/main/java}
 * @param sourcePath the source path's directories, in order: the project's resources,
 * then each module's sources and resources
 * @param coordinates the other libraries, {@code groupId:artifactId:version}
 * @param provided what the modules' sources compile against beside those, for a JDK leg's
 * javac
 */
record ExampleProject(Path sources, List<Path> sourcePath, List<String> coordinates, List<String> provided) {

	private static final Path ROOT = Path.of("").toAbsolutePath();

	private static final Set<String> DIRECT_SCOPES = Set.of("compile", "provided", "system");

	/**
	 * The project a file of a {@code src/main/java} belongs to.
	 * @param file a source file
	 * @return the project, or empty when the file is no Maven project's
	 */
	static Optional<ExampleProject> containing(Path file) {
		for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
			if (dir.endsWith(Path.of("src", "main", "java"))) {
				Path project = dir.resolve("../../..").normalize();
				return Files.isRegularFile(project.resolve("pom.xml")) ? Optional.of(read(project)) : Optional.empty();
			}
		}
		return Optional.empty();
	}

	/**
	 * Reads a project's {@code pom.xml}: a standalone project, with no parent.
	 * @param directory the project's directory
	 * @return the project
	 */
	static ExampleProject read(Path directory) {
		Path project = directory.toAbsolutePath();
		MavenResolver maven = ExamplesE2eTest.mavenResolver();
		List<Path> sourcePath = new ArrayList<>(existing(project.resolve("src/main/resources")));
		List<String> coordinates = new ArrayList<>();
		List<String> provided = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Deque<List<Dependency>> pending = new ArrayDeque<>();
		pending.add(model(maven, project).dependencies());
		boolean direct = true;
		while (!pending.isEmpty()) {
			for (Dependency dependency : pending.removeFirst()) {
				String scope = dependency.scope().isEmpty() ? "compile" : dependency.scope();
				if (direct ? !DIRECT_SCOPES.contains(scope) : !scope.equals("compile") || dependency.isOptional()) {
					continue;
				}
				String key = dependency.artifact().groupId() + ":" + dependency.artifact().artifactId();
				if (!seen.add(key)) {
					continue;
				}
				String version = dependency.artifact().version();
				Path module = ROOT.resolve(dependency.artifact().artifactId());
				Path modulePom = module.resolve("pom.xml");
				String moduleCoordinates = Files.isRegularFile(modulePom) ? coordinatesOf(modulePom) : "";
				if (moduleCoordinates.startsWith(key + ":")) {
					if (!moduleCoordinates.equals(key + ":" + version)) {
						throw new IllegalStateException(project + "/pom.xml asks " + key + ":" + version
								+ ", and this repository's module is " + moduleCoordinates);
					}
					sourcePath.add(module.resolve("src/main/java"));
					sourcePath.addAll(existing(module.resolve("src/main/resources")));
					MavenProject model = model(maven, module);
					pending.add(model.dependencies());
					provided.addAll(provided(model));
				}
				else {
					coordinates.add(key + ":" + version);
				}
			}
			direct = false;
		}
		return new ExampleProject(project.resolve("src/main/java"), sourcePath, List.copyOf(coordinates),
				List.copyOf(provided));
	}

	/**
	 * What a module of this repository compiles its sources against and does not bring:
	 * its {@code provided} dependencies.
	 * @param sources a directory of a source path: a module's {@code src/main/java}, or
	 * any other, which has none
	 * @return their coordinates
	 */
	static List<String> provided(Path sources) {
		Path directory = sources.toAbsolutePath();
		if (!directory.endsWith(Path.of("src", "main", "java"))) {
			return List.of();
		}
		Path module = directory.resolve("../../..").normalize();
		return Files.isRegularFile(module.resolve("pom.xml")) ? provided(model(ExamplesE2eTest.mavenResolver(), module))
				: List.of();
	}

	private static List<String> provided(MavenProject module) {
		List<String> provided = new ArrayList<>();
		for (Dependency dependency : module.dependencies()) {
			if (dependency.scope().equals("provided") && !dependency.isOptional()) {
				provided.add(dependency.artifact().groupId() + ":" + dependency.artifact().artifactId() + ":"
						+ dependency.artifact().version());
			}
		}
		return provided;
	}

	/**
	 * The command line's options: {@code --java-source-path} and a
	 * {@code --java-source-dep} a coordinate.
	 * @return the options
	 */
	List<String> options() {
		List<String> options = new ArrayList<>();
		if (!this.sourcePath.isEmpty()) {
			options.add("--java-source-path");
			options.add(String.join(File.pathSeparator, this.sourcePath.stream().map(Path::toString).toList()));
		}
		for (String coordinate : this.coordinates) {
			options.add("--java-source-dep");
			options.add(coordinate);
		}
		return options;
	}

	private static MavenProject model(MavenResolver maven, Path directory) {
		try {
			return maven.project(directory.resolve("pom.xml"), Map.of("project.basedir", directory.toString()));
		}
		catch (MavenResolutionException ex) {
			throw new IllegalStateException(directory + "/pom.xml cannot be read: " + ex.getMessage(), ex);
		}
	}

	// A module's own groupId:artifactId:version: its pom has no parent.
	private static String coordinatesOf(Path pom) {
		try {
			Element project = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder()
				.parse(pom.toFile())
				.getDocumentElement();
			return child(project, "groupId") + ":" + child(project, "artifactId") + ":" + child(project, "version");
		}
		catch (Exception ex) {
			throw new IllegalStateException(pom + " cannot be read: " + ex.getMessage(), ex);
		}
	}

	private static String child(Element parent, String name) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && element.getTagName().equals(name)) {
				return element.getTextContent().strip();
			}
		}
		return "";
	}

	private static List<Path> existing(Path directory) {
		return Files.isDirectory(directory) ? List.of(directory) : List.of();
	}

}
