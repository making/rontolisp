package am.ik.maven;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a {@code settings.xml} contributes: the local repository and offline mode honored,
 * mirrors and proxies matched the way Maven matches them -- so that the refusal fires
 * exactly when Maven would have gone through one.
 */
class MavenSettingsTest {

	private static final RemoteRepository CENTRAL = RemoteRepository.CENTRAL;

	private static final RemoteRepository CLOJARS = RemoteRepository.CLOJARS;

	private static final RemoteRepository LOCAL_FILE = new RemoteRepository("disk", "file:///srv/repo/");

	private static final RemoteRepository LOCAL_HOST = new RemoteRepository("lan", "https://localhost:8443/repo/");

	private static final RemoteRepository PLAIN_HTTP = new RemoteRepository("legacy", "http://old.example/repo/");

	private static MavenSettings parse(String xml) throws MavenResolutionException {
		return MavenSettings.parse(xml.getBytes(StandardCharsets.UTF_8), "settings.xml",
				Map.of("user.home", "/home/dev"), Map.of("REPO_ROOT", "/data"));
	}

	private static MavenSettings mirrors(String mirrorOf) {
		return new MavenSettings(null, false,
				List.of(new MavenSettings.Mirror("m", "https://mirror.example/", mirrorOf)), List.of(), List.of());
	}

	@Test
	void readsTheEntriesThatBearOnResolution() throws MavenResolutionException {
		MavenSettings settings = parse(
				"""
						<settings>
						  <localRepository>${user.home}/maven-cache</localRepository>
						  <offline>true</offline>
						  <mirrors><mirror><id>nexus</id><url>https://nexus.example/</url><mirrorOf>*</mirrorOf></mirror></mirrors>
						  <proxies><proxy><id>office</id><host>proxy.example</host></proxy></proxies>
						  <servers><server><id>clojars</id><username>me</username></server></servers>
						  <profiles/>
						</settings>
						""");

		assertThat(settings.localRepository()).isEqualTo(Path.of("/home/dev/maven-cache"));
		assertThat(settings.offline()).isTrue();
		assertThat(settings.mirrors())
			.containsExactly(new MavenSettings.Mirror("nexus", "https://nexus.example/", "*"));
		assertThat(settings.proxies())
			.containsExactly(new MavenSettings.Proxy("office", true, "http", "proxy.example", 8080, ""));
		assertThat(settings.hasServer("clojars")).isTrue();
		assertThat(
				parse("<settings><localRepository>${env.REPO_ROOT}/m2</localRepository></settings>").localRepository())
			.isEqualTo(Path.of("/data/m2"));
	}

	@Test
	void anUnreadableFileIsRefused() {
		assertThatThrownBy(() -> parse("<settings><mirrors></settings>")).isInstanceOf(MavenResolutionException.class)
			.hasMessageStartingWith("settings.xml is not well-formed: end tag name </settings> must match");
	}

	@Test
	void mirrorOfMatchesAsMavenMatchesIt() {
		assertThat(mirrors("*").mirrorFor(LOCAL_FILE)).isNotNull();
		assertThat(mirrors("central").mirrorFor(CENTRAL)).isNotNull();
		assertThat(mirrors("central").mirrorFor(CLOJARS)).isNull();
		assertThat(mirrors("central,clojars").mirrorFor(CLOJARS)).isNotNull();
		assertThat(mirrors("*,!central").mirrorFor(CENTRAL)).isNull();
		assertThat(mirrors("*,!central").mirrorFor(CLOJARS)).isNotNull();
		assertThat(mirrors("external:*").mirrorFor(CENTRAL)).isNotNull();
		assertThat(mirrors("external:*").mirrorFor(LOCAL_FILE)).isNull();
		assertThat(mirrors("external:*").mirrorFor(LOCAL_HOST)).isNull();
		assertThat(mirrors("external:http:*").mirrorFor(PLAIN_HTTP)).isNotNull();
		assertThat(mirrors("external:http:*").mirrorFor(CENTRAL)).isNull();
	}

	@Test
	void anExactMirrorOfWinsOverAPattern() {
		MavenSettings settings = new MavenSettings(null, false,
				List.of(new MavenSettings.Mirror("all", "https://all.example/", "*"),
						new MavenSettings.Mirror("only-clojars", "https://clojars-mirror.example/", "clojars")),
				List.of(), List.of());

		assertThat(settings.mirrorFor(CLOJARS)).extracting(MavenSettings.Mirror::id).isEqualTo("only-clojars");
		assertThat(settings.mirrorFor(CENTRAL)).extracting(MavenSettings.Mirror::id).isEqualTo("all");
	}

	@Test
	void aProxyServesItsProtocolAndAnHttpOneServesHttpsToo() {
		MavenSettings http = proxies(new MavenSettings.Proxy("p", true, "http", "proxy.example", 3128, ""));
		MavenSettings inactive = proxies(new MavenSettings.Proxy("p", false, "http", "proxy.example", 3128, ""));
		MavenSettings bypassed = proxies(
				new MavenSettings.Proxy("p", true, "https", "proxy.example", 3128, "*.maven.org|localhost"));

		assertThat(http.proxyFor(CENTRAL)).isNotNull();
		assertThat(http.proxyFor(LOCAL_FILE)).isNull();
		assertThat(inactive.proxyFor(CENTRAL)).isNull();
		assertThat(bypassed.proxyFor(CENTRAL)).isNull();
		assertThat(bypassed.proxyFor(CLOJARS)).isNotNull();
		assertThat(bypassed.proxyFor(PLAIN_HTTP)).isNull();
	}

	private static MavenSettings proxies(MavenSettings.Proxy proxy) {
		return new MavenSettings(null, false, List.of(), List.of(proxy), List.of());
	}

}
