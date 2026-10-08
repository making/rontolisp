package am.ik.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

import am.ik.artifact.Downloader;
import am.ik.artifact.HttpDownloader;
import org.jspecify.annotations.Nullable;

/**
 * Resolves Maven coordinates against Maven repositories: an artifact's descriptor (what
 * its POM says, after Maven's model building), the dependency graph below a set of
 * dependencies (every version seen, for a caller with its own policy, or resolved the way
 * Maven resolves a project's), and the artifact files themselves, through a local
 * repository in the layout {@code mvn} and {@code clj} share.
 *
 * <p>
 * Versions resolve through the repositories' {@code maven-metadata.xml}, as Maven's do: a
 * {@code SNAPSHOT} to the build deployed last (or installed locally), {@code LATEST} and
 * {@code RELEASE} to the version the metadata names, a version range in a dependency or a
 * parent to the versions the metadata lists. The metadata is cached in the local
 * repository and asked for again under the repository's update policy (Maven's default,
 * daily), which also governs when a repository that had no copy of a file is asked again;
 * a repository serves releases and snapshots only as far as its {@link RepositoryPolicy
 * policies} enable them. A repository is contacted as Maven contacts it under the
 * {@link MavenSettings}: through the mirror covering it, the proxy serving the URL
 * contacted, and the credentials, headers and timeouts of the {@code <server>} of the id
 * contacted. Repositories a POM declares are never consulted; the caller's list is the
 * only one.
 *
 * <p>
 * Every public method holds the instance lock: one resolver serves several threads, one
 * call at a time, and its caches (raw and effective models, descriptors) live as long as
 * it does.
 */
public final class MavenResolver {

	private final RepositoryAccess access;

	private final ModelBuilder models;

	private final Map<Artifact, ArtifactDescriptor> descriptors = new HashMap<>();

	private MavenResolver(RepositoryAccess access, Map<String, String> systemProperties) {
		this.access = access;
		this.models = new ModelBuilder(access, systemProperties);
	}

	/**
	 * Starts a resolver.
	 * @return the builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The local repository Maven uses without settings, {@code ~/.m2/repository}.
	 * @return its path
	 */
	public static Path defaultLocalRepository() {
		return Path.of(System.getProperty("user.home", "."), ".m2", "repository");
	}

	/**
	 * Returns the local repository.
	 * @return its root
	 */
	public Path localRepository() {
		return this.access.root();
	}

	/**
	 * Returns the remote repositories, in search order, as configured: a
	 * {@code settings.xml} mirror may stand for some of them when they are contacted.
	 * @return the repositories
	 */
	public List<RemoteRepository> repositories() {
		return this.access.repositories();
	}

	/**
	 * Reads an artifact's descriptor, following relocations. A POM no repository has, or
	 * one Maven would reject, answers no dependencies and a warning. A {@code SNAPSHOT},
	 * {@code LATEST} or {@code RELEASE} version reads the POM of the version it resolves
	 * to, the descriptor's artifact keeping the version asked for (as Maven's does).
	 * @param artifact the artifact
	 * @return the descriptor
	 * @throws MavenResolutionException if the version is a range or cannot be resolved, a
	 * repository fails, or a parent or imported POM cannot be resolved
	 */
	public synchronized ArtifactDescriptor descriptor(Artifact artifact) throws MavenResolutionException {
		ArtifactDescriptor cached = this.descriptors.get(artifact);
		if (cached == null) {
			cached = readDescriptor(artifact);
			this.descriptors.put(artifact, cached);
		}
		return cached;
	}

	/**
	 * Reads the dependencies a POM given as bytes declares -- one shipped inside a jar
	 * ({@code META-INF/maven/<group>/<artifact>/pom.xml}) -- after Maven's model
	 * building, its parents and imports read from the repositories, as the MODEL holds
	 * them rather than as {@link #descriptor} converts them: each dependency's artifact
	 * carries the classifier written in the POM (none where only its type implies one, as
	 * {@code test-jar} implies {@code tests}) and its type's extension; the scope is
	 * never empty.
	 * @param pom the POM's bytes
	 * @return the dependencies, in model order
	 * @throws MavenResolutionException if Maven's model builder would reject the POM, or
	 * a parent or import cannot be resolved
	 */
	public synchronized List<Dependency> projectDependencies(byte[] pom) throws MavenResolutionException {
		PomModel model;
		try {
			model = this.models.effective(pom);
		}
		catch (InvalidPomException ex) {
			throw new MavenResolutionException("the POM is invalid: " + ex.getMessage(), ex);
		}
		List<Dependency> dependencies = new ArrayList<>();
		for (PomModel.Dep dep : model.dependencies()) {
			String type = dep.effectiveType();
			Artifact artifact = new Artifact(Objects.requireNonNullElse(dep.groupId(), ""),
					Objects.requireNonNullElse(dep.artifactId(), ""), Objects.requireNonNullElse(dep.version(), ""),
					Objects.requireNonNullElse(dep.classifier(), ""), ArtifactTypes.extension(type));
			dependencies.add(dependency(dep).withArtifact(artifact));
		}
		return dependencies;
	}

	/**
	 * Collects the dependency graph below {@code dependencies}, with
	 * {@code managedDependencies} applied below them as a project's dependency management
	 * is.
	 * @param dependencies the requested dependencies, the graph's roots
	 * @param managedDependencies the dependency management (may be empty)
	 * @return the graph, every version seen
	 * @throws MavenResolutionException if a node cannot be resolved or is refused
	 */
	public synchronized DependencyGraph collect(List<Dependency> dependencies, List<Dependency> managedDependencies)
			throws MavenResolutionException {
		return new DependencyCollector(this::descriptor, this.access::versionRange, managedDependencies)
			.collect(dependencies);
	}

	/**
	 * Resolves the dependency graph below {@code dependencies} as Maven resolves a
	 * project's: the collected graph ({@link #collect}) with one node kept per artifact
	 * by Maven Resolver's conflict resolution under Maven's session -- the nearest
	 * occurrence wins (between two children of one parent, the higher version) among
	 * those every version range met so far admits, its scope is chosen over the scopes
	 * every path derives for it, and its optional flag likewise.
	 * {@link DependencyGraph#runtimeClassPath()} reads the class path off the result.
	 * @param dependencies the requested dependencies, the graph's roots
	 * @param managedDependencies the dependency management (may be empty)
	 * @return the resolved graph
	 * @throws MavenResolutionException if a node cannot be resolved or is refused, or no
	 * version satisfies every range met for an artifact
	 */
	public synchronized DependencyGraph resolve(List<Dependency> dependencies, List<Dependency> managedDependencies)
			throws MavenResolutionException {
		return ConflictResolver.resolve(collect(dependencies, managedDependencies));
	}

	/**
	 * Returns an artifact's file in the local repository, downloading and verifying it
	 * first when it is not there. A {@code SNAPSHOT}, {@code LATEST} or {@code RELEASE}
	 * version is resolved first ({@link #version}); a snapshot deployed under a timestamp
	 * answers its copy under the {@code -SNAPSHOT} name.
	 * @param artifact the artifact
	 * @return the file
	 * @throws MavenResolutionException if no repository has it, a repository fails, or
	 * the version is a range or cannot be resolved
	 */
	public synchronized Path artifact(Artifact artifact) throws MavenResolutionException {
		refuseRange(artifact);
		Path path = this.access.fetch(artifact);
		if (path == null) {
			StringJoiner searched = new StringJoiner(", ");
			for (RepositoryRoute route : this.access.routes()) {
				searched.add(route.toString());
			}
			throw new MavenResolutionException(
					artifact + " is in neither the local repository nor any of: " + searched);
		}
		return path;
	}

	/**
	 * Resolves a version the way Maven's version resolver does: {@code RELEASE} to the
	 * release the artifact's {@code maven-metadata.xml} names, {@code LATEST} to the
	 * latest (else the release; a snapshot resolved further), a {@code -SNAPSHOT} to the
	 * build its file was deployed as -- the newest record across the local repository and
	 * the remote ones, or the version itself when none names one -- and any other version
	 * to itself.
	 * @param artifact the artifact; its classifier and extension pick a snapshot's file
	 * @return the version
	 * @throws MavenResolutionException if {@code RELEASE} or {@code LATEST} resolves to
	 * nothing, the version is a range, or a repository is refused
	 */
	public synchronized String version(Artifact artifact) throws MavenResolutionException {
		refuseRange(artifact);
		return this.access.resolveVersion(artifact).version();
	}

	/**
	 * Resolves a version range the way Maven's version range resolver does: the versions
	 * the artifact's {@code maven-metadata.xml} lists -- the local repository's and every
	 * remote one's -- that the range contains, ascending. A plain version, or a range of
	 * one version ({@code [1.0]}), answers itself without any metadata.
	 * @param artifact the artifact, its version the range
	 * @return the versions, ascending; empty when the metadata lists none in range
	 * @throws MavenResolutionException if the version is not a valid range, or a
	 * repository is refused
	 */
	public synchronized List<String> versions(Artifact artifact) throws MavenResolutionException {
		if (artifact.version().isEmpty()) {
			throw new MavenResolutionException(artifact + ": no version");
		}
		return this.access.versionRange(artifact).versions();
	}

	private static void refuseRange(Artifact artifact) throws MavenResolutionException {
		if (artifact.version().isEmpty()) {
			throw new MavenResolutionException(artifact + ": no version");
		}
		if (artifact.isVersionRange()) {
			throw new MavenResolutionException(
					artifact + ": a version range names no single artifact (versions(..) resolves it)");
		}
	}

	/**
	 * Maven's descriptor reader: follow relocations until a POM has none. Each POM is
	 * read at its resolved version; the descriptor keeps the version asked for until a
	 * relocation names another artifact, and the warnings and relocations name the
	 * resolved one.
	 */
	private ArtifactDescriptor readDescriptor(Artifact requested) throws MavenResolutionException {
		List<Artifact> relocations = new ArrayList<>();
		Set<String> visited = new LinkedHashSet<>();
		Artifact described = requested;
		Artifact artifact = requested;
		while (true) {
			refuseRange(artifact);
			Artifact resolved = artifact.withVersion(this.access.resolveVersion(artifact).version());
			if (!visited.add(resolved.groupId() + ":" + resolved.artifactId() + ":" + resolved.baseVersion())) {
				return empty(described, relocations,
						invalid(resolved, "Artifact relocations form a cycle: " + visited));
			}
			PomModel model;
			try {
				model = this.models.effective(artifact.pom());
			}
			catch (InvalidPomException ex) {
				return empty(described, relocations, invalid(resolved, ex.getMessage()));
			}
			if (model == null) {
				return empty(described, relocations,
						"The POM for " + resolved + " is missing, no dependency information available");
			}
			PomModel.Relocation relocation = model.relocation();
			if (relocation == null) {
				return descriptorOf(described, relocations, model);
			}
			Artifact target = new Artifact(or(relocation.groupId(), resolved.groupId()),
					or(relocation.artifactId(), resolved.artifactId()), or(relocation.version(), resolved.version()),
					resolved.classifier(), resolved.extension());
			if (target.equals(resolved)) {
				return descriptorOf(described, relocations, model);
			}
			relocations.add(resolved);
			artifact = target;
			described = target;
		}
	}

	private static String invalid(Artifact artifact, @Nullable String reason) {
		return "The POM for " + artifact + " is invalid, transitive dependencies (if any) will not be available: "
				+ reason;
	}

	private static String or(@Nullable String value, String fallback) {
		return value == null || value.isEmpty() ? fallback : value;
	}

	private static ArtifactDescriptor empty(Artifact artifact, List<Artifact> relocations, String warning) {
		return new ArtifactDescriptor(artifact, relocations, List.of(), List.of(), List.of(warning));
	}

	private static ArtifactDescriptor descriptorOf(Artifact artifact, List<Artifact> relocations, PomModel model) {
		List<Dependency> dependencies = new ArrayList<>();
		for (PomModel.Dep dep : model.dependencies()) {
			dependencies.add(dependency(dep));
		}
		List<Dependency> managed = new ArrayList<>();
		List<PomModel.Dep> management = model.managedDependencies();
		if (management != null) {
			for (PomModel.Dep dep : management) {
				managed.add(dependency(dep));
			}
		}
		return new ArtifactDescriptor(artifact, relocations, dependencies, managed, List.of());
	}

	/** A model dependency as Maven's descriptor reader converts it. */
	private static Dependency dependency(PomModel.Dep dep) {
		String type = dep.effectiveType();
		String classifier = dep.classifier() != null ? dep.classifier() : ArtifactTypes.classifier(type);
		Artifact artifact = new Artifact(Objects.requireNonNullElse(dep.groupId(), ""),
				Objects.requireNonNullElse(dep.artifactId(), ""), Objects.requireNonNullElse(dep.version(), ""),
				classifier, ArtifactTypes.extension(type));
		List<Exclusion> exclusions = new ArrayList<>();
		for (PomModel.Excl exclusion : dep.exclusions()) {
			exclusions.add(new Exclusion(Objects.requireNonNullElse(exclusion.groupId(), ""),
					Objects.requireNonNullElse(exclusion.artifactId(), "")));
		}
		String optional = dep.optional();
		return new Dependency(artifact, type, Objects.requireNonNullElse(dep.scope(), ""),
				optional == null ? null : Boolean.valueOf(optional), exclusions);
	}

	/**
	 * Configures a {@link MavenResolver}. The local repository is never guessed: it is
	 * the one set here, else the one the settings name; {@link #defaultLocalRepository()}
	 * is there for a caller that wants Maven's.
	 */
	public static final class Builder {

		private @Nullable Path localRepository;

		private List<RemoteRepository> repositories = List.of(RemoteRepository.CENTRAL, RemoteRepository.CLOJARS);

		private @Nullable Downloader downloader;

		private MavenSettings settings = MavenSettings.none();

		private @Nullable Map<String, String> systemProperties;

		private @Nullable UpdatePolicy updatePolicy;

		private Builder() {
		}

		/**
		 * Sets one update policy for every repository -- when it is asked again for a
		 * {@code maven-metadata.xml} the local repository caches, and for a file it did
		 * not have -- in Maven's spellings, replacing the repositories' own
		 * ({@link RepositoryPolicy}; Maven's session update policy, {@code mvn -U} being
		 * {@code always}). Without it each repository's policy for the kind asked
		 * applies, {@code daily} unless the repository says otherwise.
		 * @param policy {@code always}, {@code daily}, {@code never} or
		 * {@code interval:MINUTES}
		 * @return this builder
		 * @throws IllegalArgumentException for any other spelling
		 */
		public Builder updatePolicy(String policy) {
			this.updatePolicy = UpdatePolicy.parse(policy);
			return this;
		}

		/**
		 * Sets the local repository.
		 * @param path its root
		 * @return this builder
		 */
		public Builder localRepository(Path path) {
			this.localRepository = path;
			return this;
		}

		/**
		 * Sets the remote repositories, in search order (default: Maven Central, then
		 * Clojars).
		 * @param list the repositories
		 * @return this builder
		 */
		public Builder repositories(List<RemoteRepository> list) {
			this.repositories = List.copyOf(list);
			return this;
		}

		/**
		 * Sets the downloader for {@code http(s)} repositories (default: an
		 * {@link HttpDownloader}).
		 * @param value the downloader
		 * @return this builder
		 */
		public Builder downloader(Downloader value) {
			this.downloader = value;
			return this;
		}

		/**
		 * Sets the settings (default: {@link MavenSettings#none()}).
		 * @param value the settings
		 * @return this builder
		 */
		public Builder settings(MavenSettings value) {
			this.settings = value;
			return this;
		}

		/**
		 * Sets the system properties profile activation reads and expressions fall back
		 * to (default: this JVM's).
		 * @param properties the properties
		 * @return this builder
		 */
		public Builder systemProperties(Map<String, String> properties) {
			this.systemProperties = Map.copyOf(properties);
			return this;
		}

		/**
		 * Builds the resolver.
		 * @return the resolver
		 * @throws IllegalStateException if no local repository is set or named by the
		 * settings
		 */
		public MavenResolver build() {
			Path local = this.localRepository != null ? this.localRepository : this.settings.localRepository();
			if (local == null) {
				throw new IllegalStateException(
						"no local repository: set one (MavenResolver.defaultLocalRepository() is Maven's)");
			}
			Map<String, String> properties = this.systemProperties;
			if (properties == null) {
				Map<String, String> snapshot = new HashMap<>();
				for (String name : System.getProperties().stringPropertyNames()) {
					snapshot.put(name, Objects.requireNonNullElse(System.getProperty(name), ""));
				}
				properties = snapshot;
			}
			Downloader chosen = this.downloader != null ? this.downloader : new HttpDownloader();
			return new MavenResolver(
					new RepositoryAccess(local, this.repositories, chosen, this.settings, this.updatePolicy),
					properties);
		}

	}

}
