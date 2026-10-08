package am.ik.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import am.ik.artifact.Downloader;
import am.ik.artifact.HttpStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The local repository and the remote ones behind it: a file already local is used as it
 * is, a missing one comes from the first repository whose copy matches its {@code .sha1},
 * and what cannot be verified or is refused by name is never written; metadata is cached
 * per repository and asked for again, like a file no repository had, under the update
 * policy; snapshots, {@code LATEST}, {@code RELEASE} and ranges resolve through it.
 */
class MavenRepositoryTest {

	private static final String CENTRAL = "https://central.example/maven2/";

	private static final String MIRROR = "https://mirror.example/repo/";

	private static final Artifact LIB = Artifact.parse("org.example:lib:1.0");

	private static final byte[] JAR = "jar bytes".getBytes(StandardCharsets.UTF_8);

	private static final String LIB_METADATA = "org/example/lib/maven-metadata.xml";

	@TempDir
	Path local;

	/** Serves a map of URLs, a 404 for anything else, and records every request. */
	private static final class Served implements Downloader {

		final Map<String, Object> answers = new HashMap<>();

		final List<String> requested = new ArrayList<>();

		Served file(String url, byte[] bytes) {
			this.answers.put(url, bytes);
			this.answers.put(url + ".sha1", MavenTestRepository.sha1(bytes).getBytes(StandardCharsets.US_ASCII));
			return this;
		}

		Served answer(String url, Object answer) {
			this.answers.put(url, answer);
			return this;
		}

		@Override
		public byte[] get(String url) throws IOException {
			this.requested.add(url);
			Object answer = this.answers.get(url);
			if (answer instanceof byte[] bytes) {
				return bytes;
			}
			if (answer instanceof IOException failure) {
				throw failure;
			}
			throw new HttpStatusException(404, url);
		}

	}

	private MavenResolver resolver(Downloader downloader, String... urls) {
		return resolver(downloader, MavenSettings.none(), urls);
	}

	private MavenResolver resolver(Downloader downloader, MavenSettings settings, String... urls) {
		return configured(downloader, settings, "daily", urls);
	}

	private MavenResolver policyResolver(Downloader downloader, String policy, String... urls) {
		return configured(downloader, MavenSettings.none(), policy, urls);
	}

	private MavenResolver configured(Downloader downloader, MavenSettings settings, String policy, String... urls) {
		List<RemoteRepository> repositories = new ArrayList<>();
		for (int i = 0; i < urls.length; i++) {
			repositories.add(new RemoteRepository(i == 0 ? "central" : "repo" + i, urls[i]));
		}
		return MavenResolver.builder()
			.localRepository(this.local)
			.repositories(repositories)
			.downloader(downloader)
			.settings(settings)
			.updatePolicy(policy)
			.systemProperties(MavenTestRepository.SYSTEM)
			.build();
	}

	private Path localFile(Artifact artifact) {
		return this.local.resolve(artifact.path());
	}

	@Test
	void anArtifactAlreadyLocalIsUsedWithoutAnyRequest() throws IOException {
		// What mvn or clj downloaded before: no .sha1 is needed, no _remote.repositories
		// read.
		Files.createDirectories(localFile(LIB).getParent());
		Files.write(localFile(LIB), JAR);
		Served served = new Served();

		assertThat(resolver(served, CENTRAL).artifact(LIB)).hasBinaryContent(JAR);
		assertThat(served.requested).isEmpty();
	}

	@Test
	void aDownloadIsVerifiedAgainstItsSha1AndStoredWithIt() throws IOException {
		Served served = new Served().file(CENTRAL + LIB.path(), JAR);

		Path file = resolver(served, CENTRAL).artifact(LIB);

		assertThat(file).isEqualTo(localFile(LIB)).hasBinaryContent(JAR);
		assertThat(file.resolveSibling("lib-1.0.jar.sha1")).hasContent(MavenTestRepository.sha1(JAR));
		assertThat(served.requested).containsExactly(CENTRAL + LIB.path(), CENTRAL + LIB.path() + ".sha1");
		assertThat(entriesOf(this.local.resolve("org/example/lib/1.0"))).containsExactly("lib-1.0.jar",
				"lib-1.0.jar.sha1");
	}

	@Test
	void aChecksumMismatchIsNeverStoredAndTheNextRepositoryIsTried() throws IOException {
		String other = "https://other.example/repo/";
		Served served = new Served().answer(CENTRAL + LIB.path(), "tampered".getBytes(StandardCharsets.UTF_8))
			.answer(CENTRAL + LIB.path() + ".sha1", MavenTestRepository.sha1(JAR).getBytes(StandardCharsets.US_ASCII))
			.file(other + LIB.path(), JAR);

		assertThatThrownBy(() -> resolver(served, CENTRAL).artifact(LIB)).isInstanceOf(MavenResolutionException.class)
			.hasMessageContaining("cannot fetch org.example:lib:jar:1.0 (central: SHA-1 mismatch for " + CENTRAL
					+ LIB.path() + ": expected " + MavenTestRepository.sha1(JAR));
		assertThat(localFile(LIB)).doesNotExist();

		assertThat(resolver(served, CENTRAL, other).artifact(LIB)).hasBinaryContent(JAR);
	}

	@Test
	void aCopyWithoutASha1IsNotTrusted() {
		Served served = new Served().answer(CENTRAL + LIB.path(), JAR);

		assertThatThrownBy(() -> resolver(served, CENTRAL).artifact(LIB)).isInstanceOf(MavenResolutionException.class)
			.hasMessage(
					"cannot fetch org.example:lib:jar:1.0 (central: publishes no org/example/lib/1.0/lib-1.0.jar.sha1"
							+ " to verify it against)");
		assertThat(localFile(LIB)).doesNotExist();
	}

	@Test
	void theSha1sumAndOpensslChecksumFormsAreRead() {
		String sha1 = MavenTestRepository.sha1(JAR);

		assertThat(RepositoryAccess.checksum((sha1 + "  lib-1.0.jar\n").getBytes(StandardCharsets.UTF_8)))
			.isEqualTo(sha1);
		assertThat(RepositoryAccess.checksum(("\n\r\nSHA1(lib-1.0.jar)= " + sha1).getBytes(StandardCharsets.UTF_8)))
			.isEqualTo(sha1);
		assertThat(RepositoryAccess.checksum(sha1.toUpperCase().getBytes(StandardCharsets.UTF_8)))
			.isEqualTo(sha1.toUpperCase());
	}

	@Test
	void a404MovesOnAndAFailureIsReportedOnlyWhenNoRepositoryAnswers() throws IOException {
		String broken = "https://broken.example/repo/";
		String other = "https://other.example/repo/";
		Served served = new Served().answer(broken + LIB.path(), new HttpStatusException(500, broken + LIB.path()))
			.file(other + LIB.path(), JAR);

		// central answers 404 everywhere: not there, so the next repository is asked.
		assertThatThrownBy(() -> resolver(served, CENTRAL, broken).artifact(LIB))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("cannot fetch org.example:lib:jar:1.0 (repo1: HTTP 500 for " + broken + LIB.path() + ")");
		assertThatThrownBy(() -> resolver(served, CENTRAL).artifact(LIB)).isInstanceOf(MavenResolutionException.class)
			.hasMessage(
					"org.example:lib:jar:1.0 is in neither the local repository nor any of: central (" + CENTRAL + ")");
		assertThat(localFile(LIB)).doesNotExist();

		assertThat(resolver(served, CENTRAL, broken, other).artifact(LIB)).hasBinaryContent(JAR);
	}

	@Test
	void theUrlIsTheLayoutPathPercentEncoded() {
		Served served = new Served();
		MavenResolver resolver = resolver(served, "https://central.example/maven2");

		assertThatThrownBy(() -> resolver.artifact(new Artifact("org.example", "lib", "1 0", "sources", "jar")))
			.isInstanceOf(MavenResolutionException.class);
		assertThat(served.requested)
			.containsExactly("https://central.example/maven2/org/example/lib/1%200/lib-1%200-sources.jar");
	}

	@Test
	void coordinatesThatWouldLeaveTheLocalRepositoryAreNeverFetched() {
		Served served = new Served();

		assertThatThrownBy(() -> resolver(served, CENTRAL).artifact(new Artifact("g", "..", "..", "", "jar")))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessageContaining("is in neither the local repository nor any of");
		assertThat(served.requested).isEmpty();
	}

	@Test
	void offlineSettingsRefuseWhatTheLocalRepositoryLacks() throws IOException {
		MavenSettings offline = new MavenSettings(null, true, List.of(), List.of(), List.of());
		Served served = new Served().file(CENTRAL + LIB.path(), JAR);

		assertThatThrownBy(() -> resolver(served, offline, CENTRAL).artifact(LIB))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("offline (settings.xml <offline>true</offline>): org.example:lib:jar:1.0 is not in the local "
					+ "repository " + this.local.toAbsolutePath().normalize());
		assertThat(served.requested).isEmpty();

		Files.createDirectories(localFile(LIB).getParent());
		Files.write(localFile(LIB), JAR);
		assertThat(resolver(served, offline, CENTRAL).artifact(LIB)).hasBinaryContent(JAR);
	}

	@Test
	void aMirrorCoveringTheRepositoryIsRefusedByNameAndNeverBypassed() {
		MavenSettings mirrored = new MavenSettings(null, false,
				List.of(new MavenSettings.Mirror("corporate", MIRROR, "*")), List.of(), List.of());
		Served served = new Served().file(CENTRAL + LIB.path(), JAR);

		assertThatThrownBy(() -> resolver(served, mirrored, CENTRAL).artifact(LIB))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("settings.xml mirrors repository central (" + CENTRAL + ") to 'corporate' (" + MIRROR
					+ ", mirrorOf *); mirrors are not supported, so it is not contacted");
		assertThat(served.requested).isEmpty();
	}

	@Test
	void aProxyRoutingTheRepositoryIsRefusedByName() {
		MavenSettings proxied = new MavenSettings(null, false, List.of(),
				List.of(new MavenSettings.Proxy("office", true, "http", "proxy.example", 3128, "")), List.of());

		assertThatThrownBy(() -> resolver(new Served(), proxied, CENTRAL).artifact(LIB))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("settings.xml routes central (" + CENTRAL
					+ ") through proxy 'office' (http://proxy.example:3128); proxies are not supported, so it is not "
					+ "contacted");
	}

	@Test
	void credentialsAreNeverSentAndARepositoryAskingForThemSaysSo() {
		MavenSettings withServer = new MavenSettings(null, false, List.of(), List.of(), List.of("central"));
		Served served = new Served().answer(CENTRAL + LIB.path(), new HttpStatusException(401, CENTRAL + LIB.path()));

		assertThatThrownBy(() -> resolver(served, withServer, CENTRAL).artifact(LIB))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("cannot fetch org.example:lib:jar:1.0 (central: HTTP 401 for " + CENTRAL + LIB.path()
					+ ": the repository asks for credentials, which are not sent (settings.xml <server> 'central' is "
					+ "not supported))");
	}

	@Test
	void aVersionRangeNamesNoSingleArtifact() {
		MavenResolver resolver = resolver(new Served(), CENTRAL);

		assertThatThrownBy(() -> resolver.artifact(Artifact.parse("g:a:[1,2)")))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("g:a:jar:[1,2): a version range names no single artifact (versions(..) resolves it)");
		assertThatThrownBy(() -> resolver.descriptor(Artifact.parse("g:a:(,2]")))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessageStartingWith("g:a:jar:(,2]: a version range names no single artifact");
		assertThatThrownBy(() -> resolver.version(Artifact.parse("g:a:[1,2)")))
			.isInstanceOf(MavenResolutionException.class);
	}

	@Test
	void aRangeAdmitsWhatTheMetadataOfEveryRepositoryAndTheLocalOneLists() throws IOException {
		String other = "https://other.example/repo/";
		Served served = new Served().file(CENTRAL + LIB_METADATA, versions("2.0", "1.0", "1.5"))
			.file(other + LIB_METADATA, versions("1.2", "3.0"));
		write(this.local.resolve("org/example/lib/maven-metadata-local.xml"), versions("1.7"));

		assertThat(resolver(served, CENTRAL, other).versions(Artifact.parse("org.example:lib:[1.0,3.0)")))
			.containsExactly("1.0", "1.2", "1.5", "1.7", "2.0");
		// each repository's copy is cached under its id, beside its .sha1 and Maven's
		// record
		Path directory = this.local.resolve("org/example/lib");
		assertThat(entriesOf(directory)).containsExactly("maven-metadata-central.xml",
				"maven-metadata-central.xml.sha1", "maven-metadata-local.xml", "maven-metadata-repo1.xml",
				"maven-metadata-repo1.xml.sha1", "resolver-status.properties");
		assertThat(TrackingFile.read(directory.resolve("resolver-status.properties")))
			.containsKeys("maven-metadata-central.xml.lastUpdated", "maven-metadata-repo1.xml.lastUpdated");
		// a plain version, and a range of one, need no metadata
		Served none = new Served();
		assertThat(resolver(none, CENTRAL).versions(Artifact.parse("g:a:1.0"))).containsExactly("1.0");
		assertThat(resolver(none, CENTRAL).versions(Artifact.parse("g:a:[1.0]"))).containsExactly("1.0");
		assertThat(none.requested).isEmpty();
		assertThatThrownBy(() -> resolver(none, CENTRAL).versions(Artifact.parse("g:a:[1.0,2.0")))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("Unbounded version range [1.0,2.0");
	}

	@Test
	void metadataIsAskedForAgainOnlyUnderTheUpdatePolicy() throws IOException {
		Served served = new Served().file(CENTRAL + LIB_METADATA, versions("1.0"));
		Artifact range = Artifact.parse("org.example:lib:[1,2)");
		assertThat(resolver(served, CENTRAL).versions(range)).containsExactly("1.0");
		assertThat(served.requested).hasSize(2);

		// the repository publishes 1.1: daily, the cached copy answers until midnight
		served.file(CENTRAL + LIB_METADATA, versions("1.0", "1.1"));
		served.requested.clear();
		assertThat(resolver(served, CENTRAL).versions(range)).containsExactly("1.0");
		assertThat(served.requested).isEmpty();
		assertThat(policyResolver(served, "never", CENTRAL).versions(range)).containsExactly("1.0");
		assertThat(served.requested).isEmpty();

		// one resolver asks once, whatever the policy
		MavenResolver always = policyResolver(served, "always", CENTRAL);
		assertThat(always.versions(range)).containsExactly("1.0", "1.1");
		assertThat(always.versions(Artifact.parse("org.example:lib:[1,3)"))).containsExactly("1.0", "1.1");
		assertThat(served.requested).hasSize(2);

		// a record from yesterday is asked again under daily
		served.file(CENTRAL + LIB_METADATA, versions("1.0", "1.1", "1.2"));
		Path tracking = this.local.resolve("org/example/lib/resolver-status.properties");
		TrackingFile.update(tracking, Map.of("maven-metadata-central.xml.lastUpdated",
				Long.toString(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)));
		assertThat(resolver(served, CENTRAL).versions(range)).containsExactly("1.0", "1.1", "1.2");
	}

	@Test
	void metadataGoneFromTheRepositoryLeavesTheCacheAndIsRemembered() throws IOException {
		Served served = new Served().file(CENTRAL + LIB_METADATA, versions("1.0"));
		Artifact range = Artifact.parse("org.example:lib:[1,2)");
		assertThat(resolver(served, CENTRAL).versions(range)).containsExactly("1.0");

		served.answers.clear();
		served.requested.clear();
		assertThat(policyResolver(served, "always", CENTRAL).versions(range)).isEmpty();
		assertThat(this.local.resolve("org/example/lib/maven-metadata-central.xml")).doesNotExist();
		assertThat(served.requested).containsExactly(CENTRAL + LIB_METADATA);

		// "not found" is remembered under the policy
		served.requested.clear();
		assertThat(resolver(served, CENTRAL).versions(range)).isEmpty();
		assertThat(served.requested).isEmpty();
	}

	@Test
	void metadataThatCannotBeVerifiedIsNotTakenAndTheFailureIsNamed() {
		Served served = new Served().answer(CENTRAL + LIB_METADATA, versions("1.0"));

		assertThatThrownBy(() -> resolver(served, CENTRAL)
			.collect(List.of(Dependency.of(Artifact.parse("org.example:lib:[1,2)"))), List.of()))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("while collecting org.example:lib:jar:[1,2): No versions available for "
					+ "org.example:lib:jar:[1,2) within specified range (central: publishes no " + LIB_METADATA
					+ ".sha1 to verify it against)");
		assertThat(this.local.resolve("org/example/lib/maven-metadata-central.xml")).doesNotExist();
	}

	@Test
	void offlineTheCachedMetadataAloneIsRead() throws IOException {
		MavenSettings offline = new MavenSettings(null, true, List.of(), List.of(), List.of());
		Served served = new Served().file(CENTRAL + LIB_METADATA, versions("1.0", "1.1"));
		Artifact range = Artifact.parse("org.example:lib:[1,2)");

		assertThat(resolver(served, offline, CENTRAL).versions(range)).isEmpty();
		assertThat(served.requested).isEmpty();

		resolver(served, CENTRAL).versions(range);
		served.requested.clear();
		assertThat(resolver(served, offline, CENTRAL).versions(range)).containsExactly("1.0", "1.1");
		assertThat(served.requested).isEmpty();
	}

	@Test
	void aFileNoRepositoryHadIsAskedForAgainOnlyUnderTheUpdatePolicy() throws IOException {
		Served served = new Served();
		assertThatThrownBy(() -> resolver(served, CENTRAL).artifact(LIB)).isInstanceOf(MavenResolutionException.class)
			.hasMessageStartingWith("org.example:lib:jar:1.0 is in neither the local repository");
		Path tracking = this.local.resolve("org/example/lib/1.0/lib-1.0.jar.lastUpdated");
		assertThat(TrackingFile.read(tracking)).containsEntry(CENTRAL + ".error", "")
			.containsKey(CENTRAL + ".lastUpdated");

		// what mvn and clj record is honored: no second request today
		served.file(CENTRAL + LIB.path(), JAR);
		served.requested.clear();
		assertThatThrownBy(() -> resolver(served, CENTRAL).artifact(LIB)).isInstanceOf(MavenResolutionException.class);
		assertThat(served.requested).isEmpty();
		// a missing POM is no request either: a second run needs no network
		ArtifactDescriptor missing = resolver(served, CENTRAL).descriptor(Artifact.parse("org.example:gone:1"));
		assertThat(missing.warnings()).hasSize(1);
		served.requested.clear();
		assertThat(resolver(served, CENTRAL).descriptor(Artifact.parse("org.example:gone:1")).warnings()).hasSize(1);
		assertThat(served.requested).isEmpty();

		assertThat(policyResolver(served, "always", CENTRAL).artifact(LIB)).hasBinaryContent(JAR);
		assertThat(tracking).doesNotExist();
	}

	@Test
	void aSnapshotResolvesToItsLatestBuildAcrossRepositories() throws IOException {
		String other = "https://other.example/repo/";
		Artifact snapshot = Artifact.parse("org.example:lib:1.0-SNAPSHOT");
		String directory = "org/example/lib/1.0-SNAPSHOT/";
		Served served = new Served()
			.file(CENTRAL + directory + "maven-metadata.xml", snapshots("1.0-20240101.000000-1", "20240101000000"))
			.file(other + directory + "maven-metadata.xml", snapshots("1.0-20240202.000000-2", "20240202000000"))
			.file(other + directory + "lib-1.0-20240202.000000-2.jar", JAR);
		MavenResolver resolver = resolver(served, CENTRAL, other);

		assertThat(resolver.version(snapshot)).isEqualTo("1.0-20240202.000000-2");
		Path file = resolver.artifact(snapshot);

		// fetched from the repository that named the build, copied to the -SNAPSHOT name
		assertThat(file).isEqualTo(this.local.resolve(directory + "lib-1.0-SNAPSHOT.jar")).hasBinaryContent(JAR);
		assertThat(this.local.resolve(directory + "lib-1.0-20240202.000000-2.jar")).hasBinaryContent(JAR);
		assertThat(served.requested).noneMatch(url -> url.startsWith(CENTRAL + directory + "lib-"));
		assertThat(resolver.artifact(Artifact.parse("org.example:lib:1.0-20240202.000000-2")))
			.isEqualTo(this.local.resolve(directory + "lib-1.0-SNAPSHOT.jar"));
	}

	@Test
	void aSnapshotInstalledTodaySparesTheRemoteRepositories() throws IOException {
		Artifact snapshot = Artifact.parse("org.example:lib:1.0-SNAPSHOT");
		String directory = "org/example/lib/1.0-SNAPSHOT/";
		write(this.local.resolve(directory + "maven-metadata-local.xml"), snapshots("1.0-SNAPSHOT", "20200101000000"));
		write(this.local.resolve(directory + "lib-1.0-SNAPSHOT.jar"), JAR);
		Served served = new Served().file(CENTRAL + directory + "maven-metadata.xml",
				snapshots("1.0-20240202.000000-2", "20240202000000"));

		assertThat(resolver(served, CENTRAL).artifact(snapshot)).hasBinaryContent(JAR);
		assertThat(served.requested).isEmpty();
		// installed before today, the newer deployed build wins
		Files.setLastModifiedTime(this.local.resolve(directory + "maven-metadata-local.xml"),
				FileTime.fromMillis(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L));
		assertThat(resolver(served, CENTRAL).version(snapshot)).isEqualTo("1.0-20240202.000000-2");
	}

	@Test
	void latestAndReleaseResolveThroughTheMetadata() throws MavenResolutionException {
		Served served = new Served().file(CENTRAL + LIB_METADATA, ("""
				<metadata><versioning><latest>2.0-beta</latest><release>1.5</release>
				<lastUpdated>20240101000000</lastUpdated></versioning></metadata>
				""").getBytes(StandardCharsets.UTF_8));
		MavenResolver resolver = resolver(served, CENTRAL);

		assertThat(resolver.version(Artifact.parse("org.example:lib:LATEST"))).isEqualTo("2.0-beta");
		assertThat(resolver.version(Artifact.parse("org.example:lib:RELEASE"))).isEqualTo("1.5");
		assertThat(resolver.version(Artifact.parse("org.example:lib:1.0"))).isEqualTo("1.0");
		assertThatThrownBy(() -> resolver.version(Artifact.parse("org.example:none:RELEASE")))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("Failed to resolve version for org.example:none:jar:RELEASE: Could not find metadata "
					+ "org.example:none/maven-metadata.xml in local (" + this.local.toAbsolutePath().normalize() + ")");
	}

	@Test
	void theUpdatePolicyIsSpelledAsMavenSpellsIt() {
		assertThat(MavenResolver.builder().updatePolicy("interval:30")).isNotNull();
		assertThatThrownBy(() -> MavenResolver.builder().updatePolicy("hourly"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a Maven update policy (always, daily, never or interval:MINUTES): 'hourly'");
	}

	private static byte[] versions(String... versions) {
		StringBuilder text = new StringBuilder(
				"<metadata><groupId>org.example</groupId><artifactId>lib</artifactId><versioning><versions>");
		for (String version : versions) {
			text.append("<version>").append(version).append("</version>");
		}
		return text.append("</versions></versioning></metadata>").toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] snapshots(String value, String updated) {
		return ("<metadata><versioning><snapshotVersions><snapshotVersion><extension>jar</extension><value>" + value
				+ "</value><updated>" + updated + "</updated></snapshotVersion></snapshotVersions></versioning>"
				+ "</metadata>")
			.getBytes(StandardCharsets.UTF_8);
	}

	private static void write(Path file, byte[] bytes) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	@Test
	void aPomFetchedOnceIsReadLocallyAfterwards(@TempDir Path remoteRoot) throws IOException {
		Path remote = MavenTestRepository.remote(remoteRoot);
		Artifact child = Artifact.parse("test.inherit:child:2");
		Served first = new Served();
		try (Stream<Path> files = Files.walk(remote)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				first.answer(CENTRAL + remote.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
			}
		}

		ArtifactDescriptor fetched = resolver(first, CENTRAL).descriptor(child);
		ArtifactDescriptor local = resolver(new Served(), CENTRAL).descriptor(child);

		assertThat(first.requested).contains(CENTRAL + "test/inherit/parent/2/parent-2.pom",
				CENTRAL + "test/inherit/grandparent/1/grandparent-1.pom.sha1");
		assertThat(local).isEqualTo(fetched);
		assertThat(local.dependencies()).hasSize(11);
	}

	@Test
	void theLocalRepositoryIsNeverGuessed(@TempDir Path settingsRepository) {
		assertThatThrownBy(() -> MavenResolver.builder().build()).isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("no local repository");
		MavenSettings settings = new MavenSettings(settingsRepository, false, List.of(), List.of(), List.of());

		assertThat(MavenResolver.builder().settings(settings).build().localRepository())
			.isEqualTo(settingsRepository.toAbsolutePath().normalize());
		assertThat(MavenResolver.builder().settings(settings).localRepository(this.local).build().localRepository())
			.isEqualTo(this.local.toAbsolutePath().normalize());
	}

	@Test
	void aPomGivenAsBytesAnswersItsModelsDependenciesWithTheClassifiersWritten() throws IOException {
		// What a jar ships as META-INF/maven/.../pom.xml: its parent comes from the
		// repositories, which manages one version and adds one dependency.
		byte[] parent = """
				<project><modelVersion>4.0.0</modelVersion>
				  <groupId>org.example</groupId><artifactId>parent</artifactId><version>1</version>
				  <packaging>pom</packaging>
				  <dependencyManagement><dependencies>
				    <dependency><groupId>org.example</groupId><artifactId>managed</artifactId><version>2.0</version></dependency>
				  </dependencies></dependencyManagement>
				  <dependencies>
				    <dependency><groupId>org.example</groupId><artifactId>inherited</artifactId><version>1.0</version></dependency>
				  </dependencies>
				</project>
				"""
			.getBytes(StandardCharsets.UTF_8);
		byte[] pom = """
				<project><modelVersion>4.0.0</modelVersion>
				  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1</version></parent>
				  <artifactId>shipped</artifactId><version>3.0</version>
				  <dependencies>
				    <dependency><groupId>org.example</groupId><artifactId>managed</artifactId></dependency>
				    <dependency><groupId>org.example</groupId><artifactId>helper</artifactId><version>1.0</version><type>test-jar</type></dependency>
				    <dependency><groupId>org.example</groupId><artifactId>native</artifactId><version>1.0</version><classifier>linux</classifier></dependency>
				    <dependency><groupId>org.example</groupId><artifactId>bom-like</artifactId><version>1.0</version><type>pom</type></dependency>
				    <dependency><groupId>org.example</groupId><artifactId>maybe</artifactId><version>1.0</version><optional>true</optional>
				      <exclusions><exclusion><groupId>org.example</groupId><artifactId>gone</artifactId></exclusion></exclusions></dependency>
				    <dependency><groupId>org.example</groupId><artifactId>tested</artifactId><version>1.0</version><scope>test</scope></dependency>
				  </dependencies>
				</project>
				"""
			.getBytes(StandardCharsets.UTF_8);
		Served served = new Served().file(CENTRAL + "org/example/parent/1/parent-1.pom", parent);
		// no repository has the parent yet, nor does the local repository
		assertThatThrownBy(() -> resolver(new Served(), CENTRAL).projectDependencies(pom))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessageContaining("Non-resolvable parent POM org.example:parent:1");

		// published since: asked again once the update policy allows
		List<String> dependencies = policyResolver(served, "always", CENTRAL).projectDependencies(pom)
			.stream()
			.map(MavenTestRepository::format)
			.toList();

		assertThat(dependencies).containsExactly("org.example:managed:jar::2.0 scope=compile optional=null",
				"org.example:helper:jar::1.0 scope=compile optional=null",
				"org.example:native:jar:linux:1.0 scope=compile optional=null",
				"org.example:bom-like:pom::1.0 scope=compile optional=null",
				"org.example:maybe:jar::1.0 scope=compile optional=true exclusions=org.example:gone",
				"org.example:tested:jar::1.0 scope=test optional=null",
				"org.example:inherited:jar::1.0 scope=compile optional=null");
		assertThatThrownBy(() -> resolver(new Served(), CENTRAL).projectDependencies(
				"<project><dependencies><dependency/></dependencies></project>".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessageStartingWith("the POM is invalid: ");
	}

	private static List<String> entriesOf(Path dir) throws IOException {
		try (Stream<Path> entries = Files.list(dir)) {
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

}
