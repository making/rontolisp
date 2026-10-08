package am.ik.rontolisp.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import am.ik.artifact.ArtifactCache;
import am.ik.artifact.Downloader;
import am.ik.artifact.GitFetcher;
import am.ik.maven.Artifact;
import am.ik.maven.Dependency;
import am.ik.maven.Exclusion;
import am.ik.maven.MavenResolutionException;
import am.ik.maven.MavenResolver;
import am.ik.maven.MavenSettings;
import am.ik.maven.RemoteRepository;
import am.ik.rontolisp.clojure.ClojureRepositories;

/**
 * Where a Clojure program's {@code deps.edn} coordinates are fetched from, the way the
 * oracle's {@code clj} fetches them: Maven artifacts through {@code am.ik.maven} into the
 * {@code :mvn/local-repo}, else {@code ~/.m2/repository} -- never the
 * {@code settings.xml} {@code <localRepository>}, which {@code clj} ignores, as it
 * ignores {@code <offline>} (measured on {@code clj} 1.12.6, 2026-10-08) -- and git
 * commits through {@link GitFetcher} into the artifact cache. An {@code http:} repository
 * is refused unless {@code CLOJURE_CLI_ALLOW_HTTP_REPO} is set, in {@code clj}'s words.
 * One resolver per set of repositories, so a descriptor is read once per run.
 *
 * <p>
 * The command line builds the one it fetches through ({@link #createDefault()}), from the
 * environment; nothing else does, so an embedder, a test and the browser fetch nothing
 * unless they hand one in ({@link SourceStandards#withClojureRepositories}).
 */
public final class ClojureDepsRepositories implements ClojureRepositories {

	private final Path defaultLocalRepository;

	private final SettingsSource settingsSource;

	private final @Nullable Downloader downloader;

	private final GitFetcher git;

	private final boolean allowHttp;

	private final Map<MavenSource, MavenResolver> resolvers = new HashMap<>();

	private @Nullable MavenSettings settings;

	private ClojureDepsRepositories(Builder builder) {
		this.defaultLocalRepository = Objects.requireNonNull(builder.defaultLocalRepository,
				"defaultLocalRepository is required");
		this.settingsSource = builder.settings;
		this.downloader = builder.downloader;
		this.git = Objects.requireNonNull(builder.git, "git is required");
		this.allowHttp = builder.allowHttp;
	}

	/**
	 * The command line's repositories: {@code ~/.m2/repository}, the user's
	 * {@code settings.xml} (its mirrors and proxies, refused by name), the network, and
	 * the artifact cache's {@code gitlibs} area -- read from the environment here and
	 * nowhere else. Nothing is read until a coordinate is fetched.
	 * @return the repositories
	 */
	public static ClojureDepsRepositories createDefault() {
		return builder().defaultLocalRepository(MavenResolver.defaultLocalRepository())
			.settings(MavenSettings::readUserSettings)
			.git(GitFetcher.create(ArtifactCache.createDefault()))
			.allowHttp(System.getenv("CLOJURE_CLI_ALLOW_HTTP_REPO") != null)
			.build();
	}

	/**
	 * Starts repositories of the caller's choosing -- a test's fixtures.
	 * @return the builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String mavenVersion(MavenSource source, MavenArtifact artifact) {
		Artifact named = artifactOf(artifact);
		String unsupported = named.unsupportedVersion();
		if (unsupported != null) {
			throw new FetchFailure(named + ": " + unsupported);
		}
		return named.version();
	}

	@Override
	public List<MavenDependency> mavenDependencies(MavenSource source, MavenArtifact artifact) {
		try {
			return dependenciesOf(resolver(source).descriptor(artifactOf(artifact)).dependencies());
		}
		catch (MavenResolutionException ex) {
			throw new FetchFailure(String.valueOf(ex.getMessage()));
		}
	}

	@Override
	public List<MavenDependency> pomDependencies(MavenSource source, String pom) {
		try {
			return dependenciesOf(resolver(source).projectDependencies(pom.getBytes(StandardCharsets.UTF_8)));
		}
		catch (MavenResolutionException ex) {
			throw new FetchFailure(String.valueOf(ex.getMessage()));
		}
	}

	@Override
	public String mavenArtifact(MavenSource source, MavenArtifact artifact) {
		try {
			return resolver(source).artifact(artifactOf(artifact)).toString();
		}
		catch (MavenResolutionException ex) {
			throw new FetchFailure(String.valueOf(ex.getMessage()));
		}
	}

	@Override
	public @Nullable String gitCommit(String url, String revision) {
		return git(() -> this.git.resolve(url, revision));
	}

	@Override
	public boolean gitTag(String url, String tag) {
		return Boolean.TRUE.equals(git(() -> this.git.hasTag(url, tag)));
	}

	@Override
	public @Nullable String gitCheckout(String url, String sha) {
		if (!sha.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
			return null; // no commit has that name
		}
		Path checkout = git(() -> this.git.checkout(url, sha));
		return checkout == null ? null : checkout.toString();
	}

	@Override
	public @Nullable String gitDescendant(String url, String x, String y) {
		return git(() -> this.git.descendant(url, x, y));
	}

	/** One git question. */
	@FunctionalInterface
	private interface GitQuestion<T> {

		@Nullable T ask() throws IOException;

	}

	/** A git question, its failure a fetch failure in git's words. */
	private static <T> @Nullable T git(GitQuestion<T> question) {
		try {
			return question.ask();
		}
		catch (IOException | IllegalArgumentException ex) {
			throw new FetchFailure(String.valueOf(ex.getMessage()));
		}
	}

	private static Artifact artifactOf(MavenArtifact artifact) {
		return new Artifact(artifact.groupId(), artifact.artifactId(), artifact.version(), artifact.classifier(),
				artifact.extension());
	}

	private static List<MavenDependency> dependenciesOf(List<Dependency> dependencies) {
		List<MavenDependency> out = new ArrayList<>();
		for (Dependency dependency : dependencies) {
			Artifact artifact = dependency.artifact();
			List<String> exclusions = new ArrayList<>();
			for (Exclusion exclusion : dependency.exclusions()) {
				exclusions.add(exclusion.groupId() + "/" + exclusion.artifactId());
			}
			out.add(new MavenDependency(
					new MavenArtifact(artifact.groupId(), artifact.artifactId(), artifact.classifier(),
							artifact.extension(), artifact.version()),
					dependency.scope(), dependency.isOptional(), exclusions));
		}
		return out;
	}

	/**
	 * The resolver of one set of repositories, made on its first use: the source's
	 * repositories in order, its local repository or the default one, the user's settings
	 * without their local repository and offline flag.
	 */
	private synchronized MavenResolver resolver(MavenSource source) {
		MavenResolver known = this.resolvers.get(source);
		if (known != null) {
			return known;
		}
		List<RemoteRepository> repositories = new ArrayList<>();
		for (MavenRepository repository : source.repositories()) {
			if (repository.url().startsWith("http:") && !this.allowHttp) {
				throw new FetchFailure("Invalid repo url (http not supported): " + repository.url());
			}
			try {
				repositories.add(new RemoteRepository(repository.id(), repository.url()));
			}
			catch (IllegalArgumentException ex) {
				throw new FetchFailure(String.valueOf(ex.getMessage()));
			}
		}
		String local = source.localRepository();
		MavenResolver.Builder builder = MavenResolver.builder()
			.localRepository(local == null ? this.defaultLocalRepository : Path.of(local))
			.repositories(repositories)
			.settings(settings());
		if (this.downloader != null) {
			builder.downloader(this.downloader);
		}
		MavenResolver made = builder.build();
		this.resolvers.put(source, made);
		return made;
	}

	private MavenSettings settings() {
		MavenSettings known = this.settings;
		if (known == null) {
			MavenSettings read;
			try {
				read = this.settingsSource.read();
			}
			catch (MavenResolutionException ex) {
				throw new FetchFailure(String.valueOf(ex.getMessage()));
			}
			// clj routes through settings.xml's mirrors and proxies, but reads neither
			// its local repository nor its offline flag
			known = new MavenSettings(null, false, read.mirrors(), read.proxies(), read.servers());
			this.settings = known;
		}
		return known;
	}

	/** Where the user's {@code settings.xml} comes from. */
	@FunctionalInterface
	public interface SettingsSource {

		/**
		 * Reads the settings.
		 * @return the settings
		 * @throws MavenResolutionException if the file cannot be read or parsed
		 */
		MavenSettings read() throws MavenResolutionException;

	}

	/** Builds the repositories. */
	public static final class Builder {

		private @Nullable Path defaultLocalRepository;

		private SettingsSource settings = MavenSettings::none;

		private @Nullable Downloader downloader;

		private @Nullable GitFetcher git;

		private boolean allowHttp;

		private Builder() {
		}

		/**
		 * @param path the local repository where a {@code deps.edn} names no
		 * {@code :mvn/local-repo}
		 * @return this builder
		 */
		public Builder defaultLocalRepository(Path path) {
			this.defaultLocalRepository = path;
			return this;
		}

		/**
		 * @param source where the user's settings come from (default: none)
		 * @return this builder
		 */
		public Builder settings(SettingsSource source) {
			this.settings = source;
			return this;
		}

		/**
		 * @param value the downloader for {@code http(s)} repositories (default: an HTTP
		 * client)
		 * @return this builder
		 */
		public Builder downloader(Downloader value) {
			this.downloader = value;
			return this;
		}

		/**
		 * @param value the git fetcher
		 * @return this builder
		 */
		public Builder git(GitFetcher value) {
			this.git = value;
			return this;
		}

		/**
		 * @param value whether an {@code http:} repository is allowed
		 * ({@code CLOJURE_CLI_ALLOW_HTTP_REPO})
		 * @return this builder
		 */
		public Builder allowHttp(boolean value) {
			this.allowHttp = value;
			return this;
		}

		/**
		 * @return the repositories
		 */
		public ClojureDepsRepositories build() {
			return new ClojureDepsRepositories(this);
		}

	}

}
