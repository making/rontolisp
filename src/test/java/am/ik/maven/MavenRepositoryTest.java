package am.ik.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * and what cannot be verified or is refused by name is never written.
 */
class MavenRepositoryTest {

	private static final String CENTRAL = "https://central.example/maven2/";

	private static final String MIRROR = "https://mirror.example/repo/";

	private static final Artifact LIB = Artifact.parse("org.example:lib:1.0");

	private static final byte[] JAR = "jar bytes".getBytes(StandardCharsets.UTF_8);

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
		List<RemoteRepository> repositories = new ArrayList<>();
		for (int i = 0; i < urls.length; i++) {
			repositories.add(new RemoteRepository(i == 0 ? "central" : "repo" + i, urls[i]));
		}
		return MavenResolver.builder()
			.localRepository(this.local)
			.repositories(repositories)
			.downloader(downloader)
			.settings(settings)
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
	void versionsThatNeedMetadataAreRefusedByName() {
		MavenResolver resolver = resolver(new Served(), CENTRAL);

		assertThatThrownBy(() -> resolver.artifact(Artifact.parse("g:a:1.0-SNAPSHOT")))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("g:a:jar:1.0-SNAPSHOT: SNAPSHOT versions are not supported (resolving one needs "
					+ "maven-metadata.xml)");
		assertThatThrownBy(() -> resolver.descriptor(Artifact.parse("g:a:[1,2)")))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("g:a:jar:[1,2): version ranges are not supported (resolving one needs maven-metadata.xml)");
		assertThatThrownBy(() -> resolver.collect(List.of(Dependency.of(Artifact.parse("g:a:LATEST"))), List.of()))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("while collecting g:a:jar:LATEST: the LATEST and RELEASE meta versions are not supported "
					+ "(resolving one needs maven-metadata.xml)");
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

	private static List<String> entriesOf(Path dir) throws IOException {
		try (Stream<Path> entries = Files.list(dir)) {
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

}
