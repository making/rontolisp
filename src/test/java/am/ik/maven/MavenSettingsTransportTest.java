package am.ik.maven;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.artifact.HttpDownloader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A corporate {@code settings.xml} end to end, over the network downloader and a local
 * {@link HttpServer} standing for the office proxy: every repository mirrored to one
 * repository manager behind it, which wants its {@code <server>}'s credentials and
 * headers, the proxy wanting its own. The manager's host does not exist, so only the
 * proxy can answer for it.
 */
class MavenSettingsTransportTest {

	private static final String NEXUS = "http://nexus.invalid/repository/maven/";

	private static final Artifact LIB = Artifact.parse("org.example:lib:1.0");

	private static final byte[] JAR = "jar bytes".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path local;

	private final List<String> log = Collections.synchronizedList(new ArrayList<>());

	private final Map<String, byte[]> files = new HashMap<>();

	private HttpServer proxy;

	@BeforeEach
	void start() throws IOException {
		this.files.put(NEXUS + LIB.path(), JAR);
		this.files.put(NEXUS + LIB.path() + ".sha1", MavenTestRepository.sha1(JAR).getBytes(StandardCharsets.US_ASCII));
		this.proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.proxy.createContext("/", this::serve);
		this.proxy.start();
	}

	@AfterEach
	void stop() {
		this.proxy.stop(0);
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private void serve(HttpExchange exchange) throws IOException {
		URI uri = exchange.getRequestURI();
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		this.log.add(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1) + " auth=" + (authorization != null)
				+ " token=" + exchange.getRequestHeaders().getFirst("X-Token"));
		if (!basic("office", "proxy-pass").equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization"))) {
			respond(exchange, 407, null);
		}
		else if (!uri.isAbsolute() || !uri.getHost().equals("nexus.invalid")) {
			respond(exchange, 502, null);
		}
		else if (!basic("deployer", "secret").equals(authorization)) {
			exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Sonatype Nexus Repository Manager\"");
			respond(exchange, 401, null);
		}
		else {
			byte[] body = this.files.get(uri.toString());
			respond(exchange, body == null ? 404 : 200, body);
		}
	}

	private static void respond(HttpExchange exchange, int status, byte @Nullable [] body) throws IOException {
		exchange.sendResponseHeaders(status, body == null ? -1 : body.length);
		if (body != null) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
		exchange.close();
	}

	private MavenResolver resolver(String proxyPassword) {
		MavenSettings settings = new MavenSettings(null, false, List.of(new MavenSettings.Mirror("nexus", NEXUS, "*")),
				List.of(new MavenSettings.Proxy("office", true, "http", "127.0.0.1", this.proxy.getAddress().getPort(),
						"", new MavenSettings.Login("office", proxyPassword, null))),
				List.of(new MavenSettings.Server("nexus", new MavenSettings.Login("deployer", "secret", null),
						Map.of("X-Token", "t"), null, null)));
		return MavenResolver.builder()
			.localRepository(this.local)
			.settings(settings)
			.downloader(new HttpDownloader(Duration.ofSeconds(5), Duration.ofSeconds(5)))
			.systemProperties(MavenTestRepository.SYSTEM)
			.build();
	}

	@Test
	void everyRepositoryResolvesThroughTheMirrorBehindTheProxyWithTheMirrorsCredentials()
			throws MavenResolutionException {
		assertThat(resolver("proxy-pass").artifact(LIB)).hasBinaryContent(JAR);

		// Central and Clojars are the one mirror; its credentials answer its challenge
		// once, then go with every request
		assertThat(this.log).containsExactly("lib-1.0.jar auth=false token=t", "lib-1.0.jar auth=true token=t",
				"lib-1.0.jar.sha1 auth=true token=t");
	}

	@Test
	void aProxyRefusingItsCredentialsNamesItsEntry() {
		assertThatThrownBy(() -> resolver("wrong").artifact(LIB)).isInstanceOf(MavenResolutionException.class)
			.hasMessage("cannot fetch org.example:lib:jar:1.0 (nexus: HTTP 407 for " + NEXUS + LIB.path()
					+ " (settings.xml proxy 'office', user 'office'))");
	}

}
