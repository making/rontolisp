package am.ik.rontolisp.clojure;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Where a {@code deps.edn}'s Maven and git coordinates are fetched from: the descriptors
 * and jars of Maven repositories, the commits of git repositories. The host chooses it --
 * the command line, over the network and the caches it reads from the environment the
 * oracle's {@code clj} reads -- and hands it in through
 * {@link ClojureFiles#repositories()}; an embedder, a test and the browser playground
 * fetch none, and every Maven or git coordinate stays unfetched there, named when a
 * lookup misses. What is fetched, and when, is this front end's decision
 * ({@code ClojureDepsProcurer}, the oracle's tools.deps extensions); this only answers.
 *
 * <p>
 * Every method fails with a {@link FetchFailure} naming what could not be fetched and
 * why.
 */
public interface ClojureRepositories {

	/**
	 * The Maven artifacts one {@code deps.edn} reaches: its {@code :mvn/repos} in the
	 * oracle's search order and its {@code :mvn/local-repo}.
	 *
	 * @param repositories each repository's id and URL, in search order
	 * @param localRepository the local repository's directory, or {@code null} for the
	 * oracle's default ({@code ~/.m2/repository})
	 */
	record MavenSource(List<MavenRepository> repositories, @Nullable String localRepository) {

		/**
		 * Copies the list.
		 * @param repositories each repository's id and URL, in search order
		 * @param localRepository the local repository's directory, or {@code null}
		 */
		public MavenSource {
			repositories = List.copyOf(repositories);
		}

	}

	/**
	 * One Maven repository.
	 *
	 * @param id its id, what a {@code settings.xml} mirror or server names
	 * @param url its base URL
	 * @param releases what it serves of releases
	 * @param snapshots what it serves of snapshots
	 */
	record MavenRepository(String id, String url, MavenPolicy releases, MavenPolicy snapshots) {

		/**
		 * A repository with the default policies.
		 * @param id its id
		 * @param url its base URL
		 */
		public MavenRepository(String id, String url) {
			this(id, url, MavenPolicy.DEFAULT, MavenPolicy.DEFAULT);
		}

	}

	/**
	 * What a repository serves of one kind, a {@code :releases} or {@code :snapshots}
	 * map.
	 *
	 * @param enabled whether the repository is asked for this kind
	 * @param update Maven's spelling of the update policy: {@code daily}, {@code always}
	 * or {@code never}
	 */
	record MavenPolicy(boolean enabled, String update) {

		/** Enabled and daily: what a repository with no map serves. */
		public static final MavenPolicy DEFAULT = new MavenPolicy(true, "daily");

	}

	/**
	 * One Maven artifact.
	 *
	 * @param groupId the group id
	 * @param artifactId the artifact id
	 * @param classifier the classifier, or the empty string
	 * @param extension the file extension ({@code jar}, {@code pom})
	 * @param version the version
	 */
	record MavenArtifact(String groupId, String artifactId, String classifier, String extension, String version) {

		/**
		 * Maven's spelling: {@code groupId:artifactId:extension[:classifier]:version}.
		 * @return the coordinates
		 */
		@Override
		public String toString() {
			return this.groupId + ":" + this.artifactId + ":" + this.extension
					+ (this.classifier.isEmpty() ? "" : ":" + this.classifier) + ":" + this.version;
		}

	}

	/**
	 * One dependency a POM declares, after Maven's model building.
	 *
	 * @param artifact what it names
	 * @param scope its scope, never empty
	 * @param optional whether it is optional
	 * @param exclusions what it excludes below it, each {@code groupId/artifactId}
	 */
	record MavenDependency(MavenArtifact artifact, String scope, boolean optional, List<String> exclusions) {

		/**
		 * Copies the list.
		 * @param artifact what it names
		 * @param scope its scope
		 * @param optional whether it is optional
		 * @param exclusions what it excludes
		 */
		public MavenDependency {
			exclusions = List.copyOf(exclusions);
		}

	}

	/**
	 * A fetch that cannot answer: no repository has the artifact or the commit, a
	 * repository failed, a request this host refuses by name.
	 */
	final class FetchFailure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/**
		 * Creates the failure.
		 * @param message what could not be fetched, and why
		 */
		public FetchFailure(String message) {
			super(message);
		}

	}

	/**
	 * The concrete version a Maven version names -- itself for a plain version; a range,
	 * {@code RELEASE} and {@code LATEST} resolved against the repositories' metadata, or
	 * refused by name where this host reads none (the oracle's {@code canonicalize}).
	 * @param source the repositories
	 * @param artifact the artifact, its version the one to resolve
	 * @return the version
	 */
	String mavenVersion(MavenSource source, MavenArtifact artifact);

	/**
	 * The dependencies a Maven artifact's POM declares, as its descriptor converts them
	 * (each artifact's classifier and extension the ones its type implies), every scope
	 * and the optional ones included: the oracle's {@code coord-deps :mvn} keeps what it
	 * keeps of them.
	 * @param source the repositories
	 * @param artifact the artifact
	 * @return the dependencies, in the POM's order
	 */
	List<MavenDependency> mavenDependencies(MavenSource source, MavenArtifact artifact);

	/**
	 * The dependencies a POM's text declares -- one a jar ships -- as its model holds
	 * them (each classifier the one written), its parents and imports from the
	 * repositories: what the oracle's {@code coord-deps :jar} reads.
	 * @param source the repositories
	 * @param pom the POM's text
	 * @return the dependencies, in the model's order
	 */
	List<MavenDependency> pomDependencies(MavenSource source, String pom);

	/**
	 * A Maven artifact's file, fetched into the local repository first when it is not
	 * there.
	 * @param source the repositories
	 * @param artifact the artifact
	 * @return the file's path
	 */
	String mavenArtifact(MavenSource source, MavenArtifact artifact);

	/**
	 * The full sha of the commit a revision -- a tag, a branch, a full or abbreviated sha
	 * -- names in a git repository, or {@code null} when it names none.
	 * @param url the repository URL
	 * @param revision the revision
	 * @return the full sha, or {@code null}
	 */
	@Nullable String gitCommit(String url, String revision);

	/**
	 * Whether a git repository has a tag.
	 * @param url the repository URL
	 * @param tag the tag
	 * @return whether it does
	 */
	boolean gitTag(String url, String tag);

	/**
	 * The checkout of a commit: a directory holding the committed tree.
	 * @param url the repository URL
	 * @param sha the full sha
	 * @return the directory, or {@code null} when the repository has no such commit
	 */
	@Nullable String gitCheckout(String url, String sha);

	/**
	 * Which of two commits of a git repository descends from the other.
	 * @param url the repository URL
	 * @param x one full sha
	 * @param y the other
	 * @return {@code x} or {@code y}, or {@code null} when neither descends from the
	 * other or the repository lacks one
	 */
	@Nullable String gitDescendant(String url, String x, String y);

}
