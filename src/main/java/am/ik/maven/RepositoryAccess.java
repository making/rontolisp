package am.ik.maven;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import am.ik.artifact.AtomicInstall;
import am.ik.artifact.Checksum;
import am.ik.artifact.Downloader;
import am.ik.artifact.HttpStatusException;
import org.jspecify.annotations.Nullable;

/**
 * The local repository, in the {@code ~/.m2/repository} layout {@code mvn} and
 * {@code clj} share, filled from the remote repositories on demand -- Maven Resolver's
 * artifact, metadata, version and version-range resolvers over it.
 *
 * <ul>
 * <li>An artifact file already there is used as it is, whoever put it there. A missing
 * one is looked up in each remote repository in order: a {@code 404} moves on to the next
 * (and is recorded, Maven's {@code FILE.lastUpdated}, so the repository is not asked
 * again until the update policy says so), the first copy whose {@code .sha1} matches is
 * written into the local repository (the file, then its {@code .sha1}, each by an atomic
 * rename), and a copy that cannot be verified -- no {@code .sha1}, a mismatch, a transfer
 * failure -- is never written.</li>
 * <li>A {@code maven-metadata.xml} is cached per repository as
 * {@code maven-metadata-ID.xml} beside the local repository's own
 * {@code maven-metadata-local.xml}, and fetched again (verified the same way) when the
 * update policy says so, a {@code 404} deleting the cached copy; offline, the cached
 * copies alone are read.</li>
 * <li>{@code SNAPSHOT}, {@code LATEST} and {@code RELEASE} resolve to a version through
 * that metadata, newest record first, and a timestamped snapshot is copied to its
 * {@code -SNAPSHOT} name once fetched; a version range resolves to every version the
 * repositories' metadata lists that it contains.</li>
 * </ul>
 */
final class RepositoryAccess implements ModelBuilder.PomSource {

	/**
	 * A Maven checksum file in the {@code digest  file} or {@code ALG(file)= digest}
	 * forms.
	 */
	private static final Pattern OPENSSL_FORM = Pattern.compile(".+= [0-9A-Fa-f]+");

	private static final String METADATA = "maven-metadata.xml";

	private static final String SNAPSHOT = "SNAPSHOT";

	/**
	 * Where a version came from: a remote repository's metadata, or the local
	 * repository's ({@code remote} null).
	 *
	 * @param remote the repository, or {@code null} for the local one
	 */
	record Origin(@Nullable RemoteRepository remote) {

		/** The local repository. */
		static final Origin LOCAL = new Origin(null);

	}

	/**
	 * Maven's version resolution of one artifact.
	 *
	 * @param version the concrete version
	 * @param origin whose metadata named it, or {@code null} when no metadata did
	 */
	record ResolvedVersion(String version, @Nullable Origin origin) {
	}

	/**
	 * One repository's copy of one metadata file, cached in the local repository.
	 *
	 * @param origin the repository
	 * @param file the copy, or {@code null} when there is none
	 * @param exception why there is none, in Maven's words, or {@code null}
	 * @param failed whether that is a failure (a transfer, offline mode) rather than the
	 * repository not having the file
	 */
	private record MetadataFile(Origin origin, @Nullable Path file, @Nullable String exception, boolean failed) {
	}

	private final Path root;

	private final List<RemoteRepository> repositories;

	private final Downloader downloader;

	private final MavenSettings settings;

	private final UpdatePolicy policy;

	/**
	 * What this resolver already asked a repository for -- Maven's session record: asked
	 * once per resolver, whatever the policy.
	 */
	private final Set<String> asked = new HashSet<>();

	private final Map<Artifact, ResolvedVersion> resolvedVersions = new HashMap<>();

	private final Map<Artifact, VersionRangeResult> ranges = new HashMap<>();

	RepositoryAccess(Path localRepository, List<RemoteRepository> repositories, Downloader downloader,
			MavenSettings settings, UpdatePolicy policy) {
		this.root = localRepository.toAbsolutePath().normalize();
		this.repositories = List.copyOf(repositories);
		this.downloader = downloader;
		this.settings = settings;
		this.policy = policy;
	}

	Path root() {
		return this.root;
	}

	List<RemoteRepository> repositories() {
		return this.repositories;
	}

	/**
	 * Returns a file of the local repository, fetching it when it is not there yet -- its
	 * version resolved first ({@link #resolveVersion}), and a timestamped snapshot copied
	 * to its {@code -SNAPSHOT} name, which is the path answered.
	 * @param requested the artifact
	 * @return its local path, or {@code null} when no repository has it
	 * @throws MavenResolutionException if a repository fails, the version cannot be
	 * resolved, or the settings refuse the access
	 */
	@Nullable Path fetch(Artifact requested) throws MavenResolutionException {
		ResolvedVersion resolved = resolveVersion(requested);
		Artifact artifact = requested.withVersion(resolved.version());
		Path target = localPath(artifact.path());
		if (target == null) {
			// No repository layout holds a path that leaves its root.
			return null;
		}
		if (Files.isRegularFile(target)) {
			return normalized(artifact, target);
		}
		List<RemoteRepository> candidates = this.repositories;
		if (resolved.origin() != null) {
			RemoteRepository remote = resolved.origin().remote();
			// a version the local repository's metadata named is a local file or nothing
			candidates = remote == null ? List.of() : List.of(remote);
		}
		if (candidates.isEmpty()) {
			return null;
		}
		if (this.settings.offline()) {
			throw new MavenResolutionException("offline (settings.xml <offline>true</offline>): " + artifact
					+ " is not in the local repository " + this.root);
		}
		Path tracking = target.resolveSibling(target.getFileName() + ".lastUpdated");
		List<String> failures = new ArrayList<>();
		for (RemoteRepository repository : candidates) {
			String dataKey = normalizedUrl(repository);
			if (!this.asked.add(target + "|" + repository.id()) || notFoundBefore(tracking, dataKey)) {
				continue;
			}
			this.settings.checkRemoteAccess(repository);
			String failure = fetchFrom(repository, artifact, target);
			if (failure == null) {
				Map<String, @Nullable String> updates = new HashMap<>();
				updates.put(dataKey + ".error", null);
				updates.put(dataKey + ".lastUpdated", null);
				TrackingFile.update(tracking, updates);
				if (!hasErrors(tracking)) {
					deleteQuietly(tracking);
				}
				return normalized(artifact, target);
			}
			if (failure.isEmpty()) {
				Map<String, @Nullable String> updates = new HashMap<>();
				updates.put(dataKey + ".error", "");
				updates.put(dataKey + ".lastUpdated", Long.toString(System.currentTimeMillis()));
				TrackingFile.update(tracking, updates);
			}
			else {
				failures.add(repository.id() + ": " + failure);
			}
		}
		if (!failures.isEmpty()) {
			throw new MavenResolutionException("cannot fetch " + artifact + " (" + String.join("; ", failures) + ")");
		}
		return null;
	}

	/**
	 * Whether a repository answered "not found" for a file before, recently enough by the
	 * update policy that it is not asked again (Maven's cached not-found failure).
	 */
	private boolean notFoundBefore(Path tracking, String dataKey) {
		Properties properties = TrackingFile.read(tracking);
		String error = properties.getProperty(dataKey + ".error");
		return error != null && error.isEmpty()
				&& !this.policy.updateRequired(TrackingFile.lastUpdated(properties, dataKey));
	}

	private static boolean hasErrors(Path tracking) {
		for (Object key : TrackingFile.read(tracking).keySet()) {
			if (key.toString().endsWith(".error")) {
				return true;
			}
		}
		return false;
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			// a stale record only costs a request
		}
	}

	/**
	 * A timestamped snapshot under its {@code -SNAPSHOT} name, copied when that copy
	 * differs in size or time (Maven's snapshot normalization); any other file itself.
	 */
	private static Path normalized(Artifact artifact, Path file) throws MavenResolutionException {
		String version = artifact.version();
		String base = artifact.baseVersion();
		if (!artifact.isSnapshot() || version.equals(base)) {
			return file;
		}
		Path normalized = file.resolveSibling(file.getFileName().toString().replace(version, base));
		try {
			FileTime time = Files.getLastModifiedTime(file);
			if (!Files.isRegularFile(normalized) || Files.size(normalized) != Files.size(file)
					|| !Files.getLastModifiedTime(normalized).equals(time)) {
				AtomicInstall.writeFile(normalized, Files.readAllBytes(file));
				Files.setLastModifiedTime(normalized, time);
			}
		}
		catch (IOException ex) {
			throw new MavenResolutionException("cannot copy " + file + " to " + normalized + ": " + ex.getMessage(),
					ex);
		}
		return normalized;
	}

	/**
	 * Reads a file of the local repository, fetching it first when needed.
	 * @param artifact the artifact
	 * @return its bytes, or {@code null} when no repository has it
	 * @throws MavenResolutionException if a repository fails or the local file cannot be
	 * read
	 */
	@Override
	public byte @Nullable [] read(Artifact artifact) throws MavenResolutionException {
		Path path = fetch(artifact);
		if (path == null) {
			return null;
		}
		try {
			return Files.readAllBytes(path);
		}
		catch (IOException ex) {
			throw new MavenResolutionException("cannot read " + path + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * Maven's version resolution ({@code DefaultVersionResolver}): {@code RELEASE} and
	 * {@code LATEST} (falling back to the release) from the artifact's
	 * {@code maven-metadata.xml}, a {@code -SNAPSHOT} from its version's -- the build its
	 * file was deployed as, or the version itself when no metadata names one -- the
	 * newest record across the local repository and the remote ones winning; any other
	 * version is itself. The local repository's own metadata, when updated within the
	 * update policy, spares the remote ones a request.
	 * @param artifact the artifact
	 * @return the version and whose metadata named it
	 * @throws MavenResolutionException if {@code RELEASE} or {@code LATEST} resolves to
	 * nothing
	 */
	ResolvedVersion resolveVersion(Artifact artifact) throws MavenResolutionException {
		String version = artifact.version();
		boolean release = version.equals("RELEASE");
		boolean latest = version.equals("LATEST");
		if (!release && !latest && !version.endsWith(SNAPSHOT)) {
			return new ResolvedVersion(version, null);
		}
		ResolvedVersion cached = this.resolvedVersions.get(artifact);
		if (cached != null) {
			return cached;
		}
		ResolvedVersion resolved = resolveVersion(artifact, this.repositories);
		this.resolvedVersions.put(artifact, resolved);
		return resolved;
	}

	private ResolvedVersion resolveVersion(Artifact artifact, List<RemoteRepository> repositories)
			throws MavenResolutionException {
		String version = artifact.version();
		boolean release = version.equals("RELEASE");
		boolean latest = version.equals("LATEST");
		// Maven's exceptions, in its order: each repository's, then its file's
		List<String> exceptions = new ArrayList<>();
		Map<String, VersionInfo> infos = new HashMap<>();
		for (MetadataFile file : metadata(artifact.groupId(), artifact.artifactId(), release || latest ? "" : version,
				true, repositories)) {
			if (file.exception() != null) {
				exceptions.add(file.exception());
			}
			merge(artifact, infos, versioning(file, exceptions), file.origin());
		}
		VersionInfo info;
		if (release) {
			info = infos.get("RELEASE");
		}
		else if (latest) {
			info = infos.get("LATEST");
			if (info == null) {
				info = infos.get("RELEASE");
			}
			if (info != null && info.version.endsWith(SNAPSHOT)) {
				RemoteRepository remote = info.origin.remote();
				return resolveVersion(artifact.withVersion(info.version),
						remote != null ? List.of(remote) : repositories);
			}
		}
		else {
			String key = SNAPSHOT + artifact.classifier() + ':' + artifact.extension();
			VersionInfo source = infos.get(SNAPSHOT);
			VersionInfo target = infos.get(key);
			if (target == null || (source != null && target.isOutdated(source.timestamp)
					&& !source.origin.equals(target.origin))) {
				target = source;
			}
			if (target == null) {
				return new ResolvedVersion(version, null);
			}
			info = target;
		}
		if (info == null || info.version.isEmpty()) {
			throw new MavenResolutionException("Failed to resolve version for " + artifact
					+ (exceptions.isEmpty() ? "" : ": " + exceptions.get(0)));
		}
		return new ResolvedVersion(info.version, info.origin);
	}

	/** {@code DefaultVersionResolver.VersionInfo}. */
	private static final class VersionInfo {

		String timestamp;

		String version;

		Origin origin;

		VersionInfo(@Nullable String timestamp, String version, Origin origin) {
			this.timestamp = timestamp == null ? "" : timestamp;
			this.version = version;
			this.origin = origin;
		}

		boolean isOutdated(@Nullable String other) {
			return other != null && other.compareTo(this.timestamp) > 0;
		}

	}

	private static void merge(Artifact artifact, Map<String, VersionInfo> infos, MavenMetadata versioning,
			Origin origin) {
		if (versioning.release() != null && !versioning.release().isEmpty()) {
			merge("RELEASE", infos, versioning.lastUpdated(), versioning.release(), origin);
		}
		if (versioning.latest() != null && !versioning.latest().isEmpty()) {
			merge("LATEST", infos, versioning.lastUpdated(), versioning.latest(), origin);
		}
		for (MavenMetadata.SnapshotVersion entry : versioning.snapshotVersions()) {
			String value = entry.value();
			if (value != null && !value.isEmpty()) {
				merge(SNAPSHOT + entry.classifier() + ':' + entry.extension(), infos, entry.updated(), value, origin);
			}
		}
		MavenMetadata.Snapshot snapshot = versioning.snapshot();
		if (snapshot != null && versioning.snapshotVersions().isEmpty()) {
			String version = artifact.version();
			if (snapshot.timestamp() != null && snapshot.buildNumber() > 0) {
				version = version.substring(0, version.length() - SNAPSHOT.length()) + snapshot.timestamp() + '-'
						+ snapshot.buildNumber();
			}
			merge(SNAPSHOT, infos, versioning.lastUpdated(), version, origin);
		}
	}

	private static void merge(String key, Map<String, VersionInfo> infos, @Nullable String timestamp, String version,
			Origin origin) {
		VersionInfo info = infos.get(key);
		if (info == null) {
			infos.put(key, new VersionInfo(timestamp, version, origin));
		}
		else if (info.isOutdated(timestamp)) {
			info.version = version;
			info.origin = origin;
			info.timestamp = timestamp == null ? "" : timestamp;
		}
	}

	/**
	 * Maven's version range resolution ({@code DefaultVersionRangeResolver}): a plain
	 * version, or a range of exactly one version ({@code [1.0]}), is itself; any other
	 * range admits every version the artifact's {@code maven-metadata.xml} lists -- the
	 * local repository's and each remote one's -- that it contains, ascending.
	 * @param artifact the artifact, its version the constraint
	 * @return the versions
	 * @throws MavenResolutionException if the version is not a valid range
	 */
	@Override
	public VersionRangeResult versionRange(Artifact artifact) throws MavenResolutionException {
		VersionRangeResult cached = this.ranges.get(artifact);
		if (cached != null) {
			return cached;
		}
		VersionConstraint constraint = VersionConstraint.parse(artifact.version());
		VersionRangeResult range;
		if (!constraint.isRange()) {
			range = new VersionRangeResult(constraint, List.of(artifact.version()), List.of());
		}
		else if (constraint.lowerBound() != null && constraint.lowerBound().equals(constraint.upperBound())) {
			range = new VersionRangeResult(constraint, List.of(constraint.lowerBound().version().toString()),
					List.of());
		}
		else {
			List<String> problems = new ArrayList<>();
			Map<String, Origin> index = new HashMap<>();
			for (MetadataFile file : metadata(artifact.groupId(), artifact.artifactId(), "", false,
					this.repositories)) {
				if (file.failed()) {
					problems.add(String.valueOf(file.exception()));
				}
				for (String version : versioning(file, problems).versions()) {
					index.putIfAbsent(version, file.origin());
				}
			}
			List<GenericVersion> versions = new ArrayList<>();
			for (String version : index.keySet()) {
				GenericVersion parsed = GenericVersion.parse(version);
				if (constraint.contains(parsed)) {
					versions.add(parsed);
				}
			}
			versions.sort(null);
			List<String> texts = new ArrayList<>();
			for (GenericVersion version : versions) {
				texts.add(version.toString());
			}
			range = new VersionRangeResult(constraint, texts, problems);
		}
		this.ranges.put(artifact, range);
		return range;
	}

	/** A metadata file's versioning; an unreadable one contributes none. */
	private static MavenMetadata versioning(MetadataFile metadata, List<String> problems) {
		Path file = metadata.file();
		if (file == null) {
			return MavenMetadata.EMPTY;
		}
		MavenMetadata versioning;
		try {
			versioning = MavenMetadata.read(Files.readAllBytes(file));
		}
		catch (IOException | XmlParser.Malformed ex) {
			problems.add("invalid " + file + ": " + ex.getMessage());
			return MavenMetadata.EMPTY;
		}
		MavenMetadata.Snapshot snapshot = versioning.snapshot();
		if (metadata.origin().remote() == null && snapshot != null && snapshot.buildNumber() > 0) {
			// a remote repository using the id "local" wrote this: Maven reads it as a
			// local snapshot of the version itself
			problems.add("invalid " + file + ": snapshot information corrupted with remote repository data, "
					+ "please verify that no remote repository uses the id 'local'");
			return new MavenMetadata(null, null, List.of(), versioning.lastUpdated(),
					new MavenMetadata.Snapshot(null, 0, true), List.of());
		}
		return versioning;
	}

	/**
	 * Maven's metadata resolution ({@code DefaultMetadataResolver}): the local
	 * repository's {@code maven-metadata-local.xml}, then each remote repository's copy
	 * cached as {@code maven-metadata-ID.xml}, fetched again first when the update policy
	 * says so (or never, offline).
	 * @param groupId the group id
	 * @param artifactId the artifact id
	 * @param version the snapshot version whose metadata it is, or the empty string for
	 * the artifact's
	 * @param favorLocal whether a local file updated within the policy spares the remote
	 * repositories a request (version resolution, not range resolution)
	 * @param repositories the remote repositories
	 * @return one entry per repository, the local one first
	 */
	private List<MetadataFile> metadata(String groupId, String artifactId, String version, boolean favorLocal,
			List<RemoteRepository> repositories) throws MavenResolutionException {
		String directory = groupId.replace('.', '/') + '/' + artifactId + (version.isEmpty() ? "" : '/' + version);
		Path dir = localPath(directory);
		if (dir == null) {
			return List.of();
		}
		// as Maven names it in a message
		String metadata = groupId + ':' + artifactId + (version.isEmpty() ? "" : ':' + version) + '/' + METADATA;
		List<MetadataFile> files = new ArrayList<>();
		Path local = dir.resolve("maven-metadata-local.xml");
		boolean hasLocal = Files.isRegularFile(local);
		files.add(new MetadataFile(Origin.LOCAL, hasLocal ? local : null,
				hasLocal ? null : "Could not find metadata " + metadata + " in local (" + this.root + ")", false));
		long localUpdated = 0;
		if (favorLocal && hasLocal) {
			try {
				localUpdated = Files.getLastModifiedTime(local).toMillis();
			}
			catch (IOException ex) {
				localUpdated = 0;
			}
		}
		Path tracking = dir.resolve("resolver-status.properties");
		for (RemoteRepository repository : repositories) {
			Path cached = dir.resolve("maven-metadata-" + repository.id() + ".xml");
			String exception = null;
			boolean failed = false;
			if (this.settings.offline()) {
				if (!Files.isRegularFile(cached)) {
					exception = "Cannot access " + repository + " in offline mode (settings.xml <offline>true"
							+ "</offline>) and the metadata " + metadata + " has not been downloaded from it before";
					failed = true;
				}
			}
			else if ((localUpdated == 0 || this.policy.updateRequired(localUpdated))
					&& checkRequired(cached, tracking, repository)) {
				this.settings.checkRemoteAccess(repository);
				String failure = fetchMetadata(repository, directory, cached, tracking);
				if (failure != null && failure.isEmpty()) {
					exception = "Could not find metadata " + metadata + " in " + repository;
				}
				else if (failure != null) {
					exception = repository.id() + ": " + failure;
					failed = true;
				}
			}
			else if (!Files.isRegularFile(cached)
					&& "".equals(TrackingFile.read(tracking).getProperty(cached.getFileName() + ".error"))) {
				exception = metadata + " was not found in " + repository.url() + " during a previous attempt. This "
						+ "failure was cached in the local repository and resolution is not reattempted until the "
						+ "update interval of " + repository.id() + " has elapsed (update policy " + this.policy + ")";
			}
			files.add(new MetadataFile(new Origin(repository), Files.isRegularFile(cached) ? cached : null, exception,
					failed));
		}
		return files;
	}

	/** {@code DefaultUpdateCheckManager.checkMetadata} under Maven's error policy. */
	private boolean checkRequired(Path cached, Path tracking, RemoteRepository repository) {
		if (this.asked.contains(cached + "|" + repository.id())) {
			return false;
		}
		Properties properties = TrackingFile.read(tracking);
		String name = cached.getFileName().toString();
		String error = properties.getProperty(name + ".error");
		boolean exists = Files.isRegularFile(cached);
		long lastUpdated;
		if (error == null) {
			lastUpdated = exists ? TrackingFile.lastUpdated(properties, name) : 0;
		}
		else if (error.isEmpty()) {
			lastUpdated = TrackingFile.lastUpdated(properties, name);
		}
		else {
			lastUpdated = TrackingFile.lastUpdated(properties,
					name + "/@default-" + repository.id() + "-" + normalizedUrl(repository));
		}
		if (lastUpdated == 0 || this.policy.updateRequired(lastUpdated)) {
			return true;
		}
		// a "not found" is remembered; a transfer failure is retried
		return !exists && error != null && !error.isEmpty();
	}

	/**
	 * Fetches one repository's metadata into its cached copy.
	 * @return {@code null} once fetched, the empty string when the repository has none
	 * (the cached copy deleted), else why the repository's copy was not taken
	 */
	private @Nullable String fetchMetadata(RemoteRepository repository, String directory, Path cached, Path tracking)
			throws MavenResolutionException {
		this.asked.add(cached + "|" + repository.id());
		String path = directory + "/" + METADATA;
		String name = cached.getFileName().toString();
		byte[] bytes;
		byte[] checksumFile;
		try {
			bytes = get(repository, path);
			checksumFile = bytes == null ? null : get(repository, path + ".sha1");
		}
		catch (IOException ex) {
			return failure(repository, ex);
		}
		Map<String, @Nullable String> updates = new HashMap<>();
		updates.put(name + ".lastUpdated", Long.toString(System.currentTimeMillis()));
		if (bytes == null) {
			deleteQuietly(cached);
			deleteQuietly(cached.resolveSibling(name + ".sha1"));
			updates.put(name + ".error", "");
			TrackingFile.update(tracking, updates);
			return "";
		}
		String failure = verify(repository, path, bytes, checksumFile, cached);
		if (failure != null) {
			return failure;
		}
		updates.put(name + ".error", null);
		TrackingFile.update(tracking, updates);
		return null;
	}

	/**
	 * Fetches from one repository.
	 * @return {@code null} once the verified file is in place, the empty string when the
	 * repository does not have it, else why the repository's copy was not taken
	 */
	private @Nullable String fetchFrom(RemoteRepository repository, Artifact artifact, Path target)
			throws MavenResolutionException {
		String path = artifact.path();
		byte[] bytes;
		byte[] checksumFile;
		try {
			bytes = get(repository, path);
			if (bytes == null) {
				return "";
			}
			checksumFile = get(repository, path + ".sha1");
		}
		catch (IOException ex) {
			return failure(repository, ex);
		}
		return verify(repository, path, bytes, checksumFile, target);
	}

	/**
	 * Writes a file and its {@code .sha1} once the file matches the checksum the
	 * repository publishes.
	 * @return {@code null} once written, else why it was not
	 */
	private static @Nullable String verify(RemoteRepository repository, String path, byte[] bytes,
			byte @Nullable [] checksumFile, Path target) throws MavenResolutionException {
		if (checksumFile == null) {
			return "publishes no " + path + ".sha1 to verify it against";
		}
		String expected = checksum(checksumFile);
		Checksum sha1;
		try {
			sha1 = Checksum.sha1(expected);
		}
		catch (IllegalArgumentException ex) {
			return path + ".sha1 holds no SHA-1 digest: '" + expected + "'";
		}
		try {
			sha1.verify(bytes, repository.resolve(path).toString());
		}
		catch (IOException ex) {
			return ex.getMessage();
		}
		try {
			AtomicInstall.writeFile(target, bytes);
			AtomicInstall.writeFile(target.resolveSibling(target.getFileName() + ".sha1"),
					sha1.hex().getBytes(StandardCharsets.US_ASCII));
		}
		catch (IOException ex) {
			throw new MavenResolutionException("cannot write " + target + ": " + ex.getMessage(), ex);
		}
		return null;
	}

	private String failure(RemoteRepository repository, IOException ex) {
		String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
		if (ex instanceof HttpStatusException status && (status.statusCode() == 401 || status.statusCode() == 403)) {
			return message + ": the repository asks for credentials, which are not sent"
					+ (this.settings.hasServer(repository.id())
							? " (settings.xml <server> '" + repository.id() + "' is not supported)" : "");
		}
		return message;
	}

	/** A repository's URL ending in {@code /}, Maven's key for it in a tracking file. */
	private static String normalizedUrl(RemoteRepository repository) {
		return repository.url().endsWith("/") ? repository.url() : repository.url() + "/";
	}

	/**
	 * Reads one file of a repository.
	 * @return its bytes, or {@code null} when the repository has no such file
	 */
	private byte @Nullable [] get(RemoteRepository repository, String path) throws IOException {
		URI uri = repository.resolve(path);
		URI base = repository.resolve("");
		if (!uri.getPath().startsWith(base.getPath())) {
			return null;
		}
		if (repository.protocol().equals("file")) {
			Path file;
			try {
				file = Path.of(uri);
			}
			catch (IllegalArgumentException | FileSystemNotFoundException ex) {
				throw new IOException("not a file URL: " + uri, ex);
			}
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try {
				return Files.readAllBytes(file);
			}
			catch (NoSuchFileException ex) {
				return null;
			}
		}
		try {
			return this.downloader.get(uri.toString());
		}
		catch (HttpStatusException ex) {
			if (ex.isNotFound()) {
				return null;
			}
			throw ex;
		}
	}

	/**
	 * Maven's reading of a checksum file: the first non-blank line, the digest after the
	 * last space in the {@code ALG(file)= digest} form, else before the first space.
	 */
	static String checksum(byte[] file) {
		String text = new String(file, StandardCharsets.UTF_8);
		String line = "";
		for (String candidate : text.split("\n")) {
			if (!candidate.trim().isEmpty()) {
				line = candidate.trim();
				break;
			}
		}
		if (OPENSSL_FORM.matcher(line).matches()) {
			return line.substring(line.lastIndexOf(' ') + 1);
		}
		int space = line.indexOf(' ');
		return space < 0 ? line : line.substring(0, space);
	}

	/**
	 * The local path of a repository-relative path, or {@code null} when it would leave
	 * the local repository.
	 */
	@Nullable Path localPath(String relative) {
		try {
			Path path = this.root.resolve(relative).normalize();
			return path.startsWith(this.root) && !path.equals(this.root) ? path : null;
		}
		catch (InvalidPathException ex) {
			return null;
		}
	}

}
