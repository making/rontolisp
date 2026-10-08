package am.ik.rontolisp.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import am.ik.maven.Artifact;
import am.ik.maven.Dependency;
import am.ik.maven.DependencyGraph;
import am.ik.maven.MavenResolutionException;
import am.ik.maven.MavenResolver;
import am.ik.maven.MavenSettings;
import am.ik.maven.RemoteRepository;
import org.jspecify.annotations.Nullable;

/**
 * A program's Java class path: the {@code --java-classpath} entries, then the jars the
 * {@code --java-dep} coordinates resolve to, in the order Maven puts them on a project's
 * runtime class path (Maven's nearest-wins selection, {@code MavenResolver#resolve}). The
 * interpreter loads classes from it ({@link #classLoader()}), a JVM compile resolves
 * {@code java:} sites against it, and a compiled program jar names it in its manifest.
 *
 * <p>
 * Coordinates resolve from Maven Central through the local repository {@code mvn} uses --
 * {@code ~/.m2/repository}, or the one {@code ~/.m2/settings.xml} names -- honoring that
 * file's {@code <offline>}; what the resolver cannot do faithfully (a SNAPSHOT, a version
 * range, a mirror or proxy covering Central) is refused by name.
 */
final class JavaClassPath {

	/** No class path: the classes rontolisp runs with. */
	static final JavaClassPath NONE = new JavaClassPath(List.of(), List.of());

	private final List<Path> entries;

	private final List<Artifact> dependencies;

	private @Nullable ClassLoader loader;

	private JavaClassPath(List<Path> entries, List<Artifact> dependencies) {
		this.entries = List.copyOf(entries);
		this.dependencies = List.copyOf(dependencies);
	}

	/**
	 * Assembles the class path the options name, resolving the coordinates.
	 * @param options the command line's Java options
	 * @param resolver where coordinates resolve, or {@code null} for Maven Central
	 * through the user's local repository
	 * @param err where a POM's warning is reported
	 * @return the class path
	 * @throws IllegalArgumentException when an entry does not exist or a coordinate
	 * cannot be resolved
	 */
	static JavaClassPath of(JavaResolutionOptions options, @Nullable MavenResolver resolver, PrintStream err) {
		if (!options.namesClassPath()) {
			return NONE;
		}
		List<Path> entries = new ArrayList<>();
		for (Path entry : options.classpath()) {
			if (!Files.isDirectory(entry) && !Files.isRegularFile(entry)) {
				throw new IllegalArgumentException("--java-classpath: no such directory or jar: " + entry);
			}
			entries.add(entry.toAbsolutePath().normalize());
		}
		List<Artifact> coordinates = new ArrayList<>();
		for (String dependency : options.dependencies()) {
			coordinates.add(Artifact.parse(dependency));
		}
		if (!coordinates.isEmpty()) {
			MavenResolver maven = resolver != null ? resolver : centralResolver();
			List<Dependency> requested = new ArrayList<>();
			for (Artifact artifact : coordinates) {
				requested.add(Dependency.of(artifact));
			}
			try {
				DependencyGraph graph = maven.resolve(requested, List.of());
				for (String warning : graph.warnings()) {
					err.println("warning: --java-dep: " + warning);
				}
				for (Artifact artifact : graph.runtimeClassPath()) {
					Path jar = maven.artifact(artifact).toAbsolutePath().normalize();
					if (!entries.contains(jar)) {
						entries.add(jar);
					}
				}
			}
			catch (MavenResolutionException ex) {
				throw new IllegalArgumentException("--java-dep: " + ex.getMessage(), ex);
			}
		}
		return new JavaClassPath(entries, coordinates);
	}

	// Maven's default remote, through the local repository and offline flag of the
	// user's settings.
	private static MavenResolver centralResolver() {
		MavenSettings settings;
		try {
			settings = MavenSettings.readUserSettings();
		}
		catch (MavenResolutionException ex) {
			throw new IllegalArgumentException("--java-dep: " + ex.getMessage(), ex);
		}
		Path local = settings.localRepository();
		return MavenResolver.builder()
			.settings(settings)
			.localRepository(local != null ? local : MavenResolver.defaultLocalRepository())
			.repositories(List.of(RemoteRepository.CENTRAL))
			.build();
	}

	/**
	 * @return the directories and jars, in search order (absolute)
	 */
	List<Path> entries() {
		return this.entries;
	}

	/**
	 * @return the {@code --java-dep} coordinates as given: the dependencies a generated
	 * pom names
	 */
	List<Artifact> dependencies() {
		return this.dependencies;
	}

	/**
	 * The directory beside a program jar its class path is copied into: {@code app.jar}'s
	 * is {@code app-lib/}, a name of the jar's own so that it never meets a directory of
	 * the project's.
	 * @param jar the jar
	 * @return the directory's name
	 */
	static String libraryDirectory(Path jar) {
		String name = jar.getFileName().toString();
		return name.substring(0, name.length() - ".jar".length()) + "-lib";
	}

	/**
	 * Copies the entries beside a program jar, into {@link #libraryDirectory}: a jar
	 * under its own file name, a directory as a directory of its name. What was there
	 * under those names is overwritten; nothing else is touched.
	 * @param jar the program jar about to be written
	 * @return the manifest's {@code Class-Path} values, URLs relative to the jar, in
	 * search order
	 * @throws IllegalArgumentException when two entries have one file name
	 */
	List<String> copyBeside(Path jar) {
		String directory = libraryDirectory(jar);
		Path library = jar.toAbsolutePath().resolveSibling(directory);
		List<String> classPath = new ArrayList<>();
		for (Map.Entry<String, Path> named : named().entrySet()) {
			Path entry = named.getValue();
			Path target = library.resolve(named.getKey());
			try {
				if (Files.isDirectory(entry)) {
					copyTree(entry, target);
					classPath.add(relativeUrl(directory + "/" + named.getKey() + "/"));
				}
				else {
					Files.createDirectories(library);
					Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
					classPath.add(relativeUrl(directory + "/" + named.getKey()));
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException("cannot copy " + entry + " to " + target + ": " + ex.getMessage(), ex);
			}
		}
		return classPath;
	}

	/**
	 * The entries of a war that carry the class path, as a servlet container reads a web
	 * application's: a jar at {@code WEB-INF/lib/<name>}, a directory's files under
	 * {@code WEB-INF/classes/}.
	 * @return the entries by path, sorted
	 * @throws IllegalArgumentException when two entries have one file name, or a
	 * directory holds a file at a path another entry takes
	 */
	Map<String, byte[]> warEntries() {
		Map<String, byte[]> entries = new TreeMap<>();
		for (Map.Entry<String, Path> named : named().entrySet()) {
			Path entry = named.getValue();
			try {
				if (Files.isDirectory(entry)) {
					try (Stream<Path> files = Files.walk(entry)) {
						for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
							String name = "WEB-INF/classes/" + entry.relativize(file).toString().replace('\\', '/');
							if (entries.put(name, Files.readAllBytes(file)) != null) {
								throw new IllegalArgumentException("--java-classpath: two directories hold " + name);
							}
						}
					}
				}
				else {
					entries.put("WEB-INF/lib/" + named.getKey(), Files.readAllBytes(entry));
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException("cannot read " + entry + ": " + ex.getMessage(), ex);
			}
		}
		return entries;
	}

	// The entries by the file name they are copied under, in search order.
	private Map<String, Path> named() {
		Map<String, Path> named = new java.util.LinkedHashMap<>();
		Map<String, Path> seen = new HashMap<>();
		for (Path entry : this.entries) {
			String name = entry.getFileName().toString();
			Path other = seen.put(name, entry);
			if (other != null) {
				throw new IllegalArgumentException("the Java class path holds two entries named " + name + " (" + other
						+ " and " + entry + "): a jar or war carries each under its file name, so rename one");
			}
			named.put(name, entry);
		}
		return named;
	}

	private static void copyTree(Path source, Path target) throws IOException {
		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.sorted().toList()) {
				Path copy = target.resolve(source.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(copy);
				}
				else {
					Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	// A manifest Class-Path entry is a relative URL: a space or a '%' in a file name is
	// escaped.
	private static String relativeUrl(String path) {
		try {
			return new URI(null, null, path, null).getRawPath();
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException("cannot name " + path + " in a jar manifest", ex);
		}
	}

	/**
	 * The loader of the program's Java classes: the entries over the classes rontolisp
	 * runs with, made once. A native image defines no class at run time, so there the
	 * entries reach a JVM compile's class-file lookup and a jar's manifest, not a loader.
	 * @return the loader (rontolisp's own when there are no entries, or in a native
	 * image)
	 */
	synchronized ClassLoader classLoader() {
		ClassLoader made = this.loader;
		if (made == null) {
			ClassLoader parent = JavaClassPath.class.getClassLoader();
			if (this.entries.isEmpty() || System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
				made = parent;
			}
			else {
				URL[] urls = new URL[this.entries.size()];
				for (int i = 0; i < urls.length; i++) {
					try {
						urls[i] = this.entries.get(i).toUri().toURL();
					}
					catch (MalformedURLException ex) {
						throw new IllegalArgumentException("--java-classpath: " + this.entries.get(i), ex);
					}
				}
				made = new URLClassLoader("rontolisp-java-classpath", urls, parent);
			}
			this.loader = made;
		}
		return made;
	}

}
