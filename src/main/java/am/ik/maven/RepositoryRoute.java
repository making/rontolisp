package am.ik.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import am.ik.artifact.HttpAccess;
import org.jspecify.annotations.Nullable;

/**
 * A repository as Maven contacts it ({@code DefaultRemoteRepositoryManager
 * .aggregateRepositories}): through the {@code settings.xml} mirror that covers it, with
 * the proxy that serves the URL contacted and the {@code <server>} of the id contacted.
 *
 * @param repository the repository contacted: the mirror, or the repository itself
 * @param mirrored the repositories a mirror stands for, in order; empty for no mirror
 * @param unavailable why the repository is never contacted -- a blocked mirror, a layout
 * this resolver does not read -- in Maven's words, or {@code null}
 * @param proxy the proxy, or {@code null}
 * @param server the server, or {@code null}
 */
record RepositoryRoute(RemoteRepository repository, List<RemoteRepository> mirrored, @Nullable String unavailable,
		MavenSettings.@Nullable Proxy proxy, MavenSettings.@Nullable Server server) {

	/**
	 * Routes the repositories: each through its mirror, the repositories one mirror
	 * covers merged into one (where the first of them stood), and any later repository
	 * reusing an id dropped.
	 * @param repositories the repositories, in search order
	 * @param settings the settings
	 * @return the routes, in search order
	 * @throws MavenResolutionException if a mirror's URL is one this resolver cannot read
	 */
	static List<RepositoryRoute> of(List<RemoteRepository> repositories, MavenSettings settings)
			throws MavenResolutionException {
		List<RemoteRepository> contacted = new ArrayList<>();
		List<MavenSettings.@Nullable Mirror> mirrors = new ArrayList<>();
		List<List<RemoteRepository>> mirroredLists = new ArrayList<>();
		next: for (RemoteRepository repository : repositories) {
			MavenSettings.Mirror mirror = settings.mirrorFor(repository);
			RemoteRepository target = repository;
			if (mirror != null) {
				try {
					// the mirror serves what the repository it stands for serves
					target = new RemoteRepository(mirror.id(), mirror.url(), repository.releases(),
							repository.snapshots());
				}
				catch (IllegalArgumentException ex) {
					throw new MavenResolutionException(
							"settings.xml mirror '" + mirror.id() + "' of " + repository + ": " + ex.getMessage(), ex);
				}
			}
			for (int i = 0; i < contacted.size(); i++) {
				if (contacted.get(i).id().equals(target.id())) {
					if (mirror != null && mirrors.get(i) != null && !contains(mirroredLists.get(i), repository)) {
						mirroredLists.get(i).add(repository);
						// mergeMirrors: the policies of the repositories one mirror
						// covers
						RemoteRepository dominant = contacted.get(i);
						contacted.set(i, dominant
							.withReleases(RepositoryPolicy.merge(dominant.releases(), repository.releases()))
							.withSnapshots(RepositoryPolicy.merge(dominant.snapshots(), repository.snapshots())));
					}
					continue next;
				}
			}
			contacted.add(target);
			mirrors.add(mirror);
			List<RemoteRepository> mirrored = new ArrayList<>();
			if (mirror != null) {
				mirrored.add(repository);
			}
			mirroredLists.add(mirrored);
		}
		List<RepositoryRoute> routes = new ArrayList<>();
		for (int i = 0; i < contacted.size(); i++) {
			RemoteRepository repository = contacted.get(i);
			MavenSettings.Mirror mirror = mirrors.get(i);
			List<RemoteRepository> mirrored = mirroredLists.get(i);
			String unavailable = null;
			if (mirror != null && mirror.blocked()) {
				StringJoiner names = new StringJoiner(", ", "[", "]");
				for (RemoteRepository each : mirrored) {
					names.add(each.toString());
				}
				unavailable = "Blocked mirror for repositories: " + names;
			}
			else if (mirror != null && !mirror.layout().equals("default")) {
				unavailable = "Cannot access " + repository + " with type " + mirror.layout()
						+ " (settings.xml mirror layout); only the default layout is read";
			}
			else if (mirror == null && !settings.layoutOf(repository.id()).equals("default")) {
				// a profile repository of a layout other than default (Maven's legacy)
				String layout = settings.layoutOf(repository.id());
				unavailable = "Cannot access " + repository + " with type " + layout
						+ " using the available layout factories: Unsupported repository layout " + layout;
			}
			routes.add(new RepositoryRoute(repository, List.copyOf(mirrored), unavailable,
					settings.proxyFor(repository), settings.server(repository.id())));
		}
		return routes;
	}

	private static boolean contains(List<RemoteRepository> repositories, RemoteRepository repository) {
		for (RemoteRepository each : repositories) {
			if (each.id().equals(repository.id())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What a request may ask of this repository: Maven Resolver's
	 * {@code getPolicy(session, repository, releases, snapshots)}. A request for releases
	 * (an artifact that is not a snapshot, a {@code RELEASE} lookup) consults the release
	 * policy, one for snapshots the snapshot policy, one for either (a version range,
	 * {@code LATEST}) both: enabled when either is, updated as often as the more
	 * frequent.
	 * @param releases whether releases are wanted
	 * @param snapshots whether snapshots are wanted
	 * @param override the update policy of the whole session, which replaces the
	 * repository's own, or {@code null}
	 * @return the repository's answer
	 */
	Effective effective(boolean releases, boolean snapshots, @Nullable UpdatePolicy override) {
		RepositoryPolicy policy = RepositoryPolicy.merge(this.repository.policy(snapshots),
				this.repository.policy(!releases));
		return new Effective(policy.enabled(), override != null ? override : policy.updatePolicy());
	}

	/**
	 * A repository's answer to one kind of request.
	 *
	 * @param enabled whether it is asked at all
	 * @param update when something cached from it is asked for again
	 */
	record Effective(boolean enabled, UpdatePolicy update) {
	}

	/**
	 * How the downloader reaches this repository: its proxy and the proxy's credentials,
	 * its server's credentials, headers and timeouts.
	 * @return the access
	 */
	HttpAccess access() {
		HttpAccess.Proxy httpProxy = null;
		if (this.proxy != null) {
			httpProxy = new HttpAccess.Proxy(this.proxy.host(), this.proxy.port(), credentials(this.proxy.login()));
		}
		if (this.server == null) {
			return httpProxy == null ? HttpAccess.DIRECT : new HttpAccess(httpProxy, null, Map.of(), null, null);
		}
		return new HttpAccess(httpProxy, credentials(this.server.login()), this.server.headers(),
				this.server.connectTimeout(), this.server.requestTimeout());
	}

	private static HttpAccess.@Nullable Credentials credentials(MavenSettings.@Nullable Login login) {
		return login == null ? null : new HttpAccess.Credentials(login.username(), login.password());
	}

	/**
	 * {@code id (url)} of the repository contacted.
	 * @return the repository
	 */
	@Override
	public String toString() {
		return this.repository.toString();
	}

}
