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
import java.util.function.Function;
import java.util.TreeMap;
import java.util.stream.Stream;

import am.ik.maven.Artifact;
import am.ik.maven.Dependency;
import am.ik.maven.DependencyGraph;
import am.ik.maven.MavenResolutionException;
import am.ik.maven.MavenResolver;
import am.ik.maven.MavenSettings;
import am.ik.maven.RemoteRepository;
import am.ik.maven.RepositoryPolicy;
import org.jspecify.annotations.Nullable;

/**
 * A program's Java class path: the {@code --java-classpath} entries, then the jars the
 * {@code --java-dep} coordinates resolve to, in the order Maven puts them on a project's
 * runtime class path (Maven's nearest-wins selection, {@code MavenResolver#resolve}),
 * then the jars a Clojure program's {@code deps.edn} dependencies bring, as its lowering
 * resolves them ({@link #add}). The interpreter loads classes from it
 * ({@link #classLoader()}), a JVM compile resolves {@code java:} sites against it, and a
 * compiled program jar names it in its manifest.
 *
 * <p>
 * Coordinates resolve from Maven Central, then the {@code --java-repository} ones in the
 * order given, through the local repository {@code mvn} uses -- {@code ~/.m2/repository},
 * or the one {@code settings.xml} names -- honoring that file's {@code <offline>} and the
 * mirror, proxy and server credentials it configures for each repository (the user's
 * {@code ~/.m2/settings.xml} merged over {@code $MAVEN_HOME}'s); a SNAPSHOT,
 * {@code LATEST}, {@code RELEASE} or version range resolves through the repository's
 * {@code maven-metadata.xml} as Maven's does.
 */
final class JavaClassPath implements AutoCloseable {

	private final List<Path> entries;

	private final List<Artifact> dependencies;

	private @Nullable ClassLoader loader;

	private JavaClassPath(List<Path> entries, List<Artifact> dependencies) {
		this.entries = new ArrayList<>(entries);
		this.dependencies = new ArrayList<>(dependencies);
	}

	/**
	 * A class path of the entries given, growing as a program's dependencies resolve: an
	 * embedder's, whose entries are its own, or a wasm compile's, which names none.
	 * @param entries the directories and jars, in search order
	 * @return the class path
	 */
	static JavaClassPath of(List<Path> entries) {
		List<Path> absolute = new ArrayList<>();
		for (Path entry : entries) {
			absolute.add(entry.toAbsolutePath().normalize());
		}
		return new JavaClassPath(absolute, List.of());
	}

	/**
	 * Assembles the class path the options name, resolving the coordinates.
	 * @param options the command line's Java options
	 * @param resolver builds the resolver from the repositories to search (Central, then
	 * the {@code --java-repository} ones), or {@code null} for Maven's own settings and
	 * local repository
	 * @param err where a POM's warning is reported
	 * @return the class path
	 * @throws IllegalArgumentException when an entry does not exist or a coordinate
	 * cannot be resolved
	 */
	static JavaClassPath of(JavaResolutionOptions options,
			@Nullable Function<List<RemoteRepository>, MavenResolver> resolver, PrintStream err) {
		if (!options.namesClassPath()) {
			return new JavaClassPath(List.of(), List.of());
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
			List<RemoteRepository> repositories = repositories(options.repositories());
			MavenResolver maven = resolver != null ? resolver.apply(repositories) : mavenResolver(repositories);
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

	/**
	 * Adds a jar a program's dependencies bring -- a Clojure {@code deps.edn} library's
	 * -- after the entries already there, each once: what {@link #classLoader()} loads
	 * from from now on, what a compile resolves against and a compiled jar carries. Its
	 * Maven coordinates, when a coordinate brought it, join the {@link #dependencies()} a
	 * generated pom names.
	 * @param jar the jar's path
	 * @param mavenCoordinate its coordinates
	 * ({@code groupId:artifactId[:extension[:classifier]]:version}), or {@code null}
	 */
	synchronized void add(String jar, @Nullable String mavenCoordinate) {
		Path path = Path.of(jar).toAbsolutePath().normalize();
		if (!this.entries.contains(path)) {
			this.entries.add(path);
			if (this.loader instanceof ProgramClassLoader program) {
				program.add(url(path));
			}
		}
		if (mavenCoordinate != null) {
			Artifact artifact = Artifact.parse(mavenCoordinate);
			if (!this.dependencies.contains(artifact)) {
				this.dependencies.add(artifact);
			}
		}
	}

	// Central first, then the repositories given in order; one named central takes
	// Central's place (Maven's own redefinition of the id). Their ids are what a
	// settings.xml server and mirror name. Central serves no snapshot, as in Maven's
	// super POM: a SNAPSHOT is never asked of it.
	static List<RemoteRepository> repositories(List<RemoteRepository> given) {
		List<RemoteRepository> all = new ArrayList<>();
		all.add(RemoteRepository.CENTRAL.withSnapshots(RepositoryPolicy.DISABLED));
		for (RemoteRepository repository : given) {
			if (repository.id().equals(RemoteRepository.CENTRAL.id())) {
				all.set(0, repository);
			}
			else {
				all.add(repository);
			}
		}
		return all;
	}

	// The repositories, through Maven's settings: the local repository, offline flag,
	// mirrors, proxies and servers of the global and the user's settings.xml.
	private static MavenResolver mavenResolver(List<RemoteRepository> repositories) {
		MavenSettings settings;
		try {
			settings = MavenSettings.readGlobalAndUser();
		}
		catch (MavenResolutionException ex) {
			throw new IllegalArgumentException("--java-dep: " + ex.getMessage(), ex);
		}
		Path local = settings.localRepository();
		return MavenResolver.builder()
			.settings(settings)
			.localRepository(local != null ? local : MavenResolver.defaultLocalRepository())
			.repositories(repositories)
			.build();
	}

	/**
	 * @return the directories and jars, in search order (absolute)
	 */
	synchronized List<Path> entries() {
		return List.copyOf(this.entries);
	}

	/**
	 * @return the {@code --java-dep} coordinates as given, then those of the
	 * {@code deps.edn} jars added: the dependencies a generated pom names
	 */
	synchronized List<Artifact> dependencies() {
		return List.copyOf(this.dependencies);
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
	synchronized List<String> copyBeside(Path jar) {
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
	synchronized Map<String, byte[]> warEntries() {
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
	 * runs with, made once, which loads from every entry {@link #add} adds later too. A
	 * native image defines no class at run time, so there the entries reach a JVM
	 * compile's class-file lookup and a jar's manifest, not a loader.
	 * @return the loader (rontolisp's own in a native image)
	 */
	synchronized ClassLoader classLoader() {
		ClassLoader made = this.loader;
		if (made == null) {
			ClassLoader parent = JavaClassPath.class.getClassLoader();
			if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
				made = parent;
			}
			else {
				URL[] urls = new URL[this.entries.size()];
				for (int i = 0; i < urls.length; i++) {
					urls[i] = url(this.entries.get(i));
				}
				made = new ProgramClassLoader(urls, parent);
			}
			this.loader = made;
		}
		return made;
	}

	/**
	 * Closes the loader's jars, for a caller done with the program's classes before its
	 * process ends -- an embedder's compile. The command line never closes it: the
	 * program it runs uses those classes until it exits.
	 */
	@Override
	public synchronized void close() {
		if (this.loader instanceof ProgramClassLoader program) {
			try {
				program.close();
			}
			catch (IOException ex) {
				// a jar that cannot be closed now is closed when the process ends
			}
		}
	}

	private static URL url(Path entry) {
		try {
			return entry.toUri().toURL();
		}
		catch (MalformedURLException ex) {
			throw new IllegalArgumentException("the Java class path entry " + entry + " names no URL", ex);
		}
	}

	/**
	 * The program's class loader: its class path, which grows as dependencies resolve.
	 */
	private static final class ProgramClassLoader extends URLClassLoader {

		static {
			ClassLoader.registerAsParallelCapable();
		}

		ProgramClassLoader(URL[] urls, ClassLoader parent) {
			super("rontolisp-java-classpath", urls, parent);
		}

		void add(URL url) {
			addURL(url);
		}

	}

}
