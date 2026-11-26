package am.ik.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The downloader reaching a URL as an {@link HttpAccess} says, against local servers
 * only: Basic credentials answered to a challenge and kept to their host, headers on
 * every request, an HTTP proxy with credentials, and an {@code https} URL tunnelled
 * through a proxy that takes Basic credentials on its {@code CONNECT} -- which the JDK
 * client refuses to send.
 */
class HttpDownloaderAccessTest {

	private static final String PASSWORD = "s3cr3t pässword";

	private static final HttpAccess.Credentials USER = new HttpAccess.Credentials("deployer", PASSWORD);

	private static final HttpAccess.Credentials PROXY_USER = new HttpAccess.Credentials("office", "proxy-pass");

	@TempDir
	Path temp;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	private final List<String> log = Collections.synchronizedList(new ArrayList<>());

	private final CountDownLatch release = new CountDownLatch(1);

	private HttpServer server;

	private String base;

	@BeforeEach
	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.setExecutor(this.executor);
		this.server.createContext("/", this::serve);
		this.server.start();
		this.base = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		this.release.countDown();
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	/**
	 * An origin server and an HTTP proxy at once: {@code /secret/**} wants the user's
	 * Basic credentials, {@code /moved} redirects to {@code /secret/file}, an absolute
	 * request URI is a proxied request (it wants the proxy's credentials and answers for
	 * its host), {@code /digest} challenges with Digest only.
	 */
	private void serve(HttpExchange exchange) throws IOException {
		String target = exchange.getRequestURI().toString();
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		String proxyAuthorization = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
		String token = exchange.getRequestHeaders().getFirst("X-Token");
		this.log.add(target + " auth=" + authorization + " proxy-auth=" + proxyAuthorization + " token=" + token);
		if (exchange.getRequestURI().isAbsolute()) {
			if (!HttpMessages.basic(PROXY_USER).equals(proxyAuthorization)) {
				exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"office\"");
				respond(exchange, 407, "");
				return;
			}
			respond(exchange, 200, "proxied " + exchange.getRequestURI().getHost());
			return;
		}
		switch (exchange.getRequestURI().getPath()) {
			case "/moved" -> {
				exchange.getResponseHeaders().add("Location", "/secret/file");
				respond(exchange, 302, "");
			}
			case "/elsewhere" -> {
				exchange.getResponseHeaders()
					.add("Location", "http://localhost:" + this.server.getAddress().getPort() + "/secret/file");
				respond(exchange, 302, "");
			}
			case "/digest" -> {
				exchange.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"r\", nonce=\"n\"");
				respond(exchange, 401, "");
			}
			default -> {
				if (!HttpMessages.basic(USER).equals(authorization)) {
					exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"repo\"");
					respond(exchange, 401, "");
					return;
				}
				respond(exchange, 200, "secret bytes");
			}
		}
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		exchange.close();
	}

	private static HttpDownloader downloader() {
		return new HttpDownloader(Duration.ofSeconds(5), Duration.ofSeconds(5));
	}

	private static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static HttpAccess credentials(HttpAccess.Credentials credentials) {
		return new HttpAccess(null, credentials, Map.of(), null, null);
	}

	@Test
	void credentialsAnswerABasicChallengeAndThenGoWithoutOne() throws IOException {
		HttpDownloader downloader = downloader();

		assertThat(text(downloader.get(this.base + "/secret/a.jar", credentials(USER)))).isEqualTo("secret bytes");
		assertThat(text(downloader.get(this.base + "/secret/b.jar", credentials(USER)))).isEqualTo("secret bytes");

		String sent = HttpMessages.basic(USER);
		assertThat(this.log).containsExactly("/secret/a.jar auth=null proxy-auth=null token=null",
				"/secret/a.jar auth=" + sent + " proxy-auth=null token=null",
				"/secret/b.jar auth=" + sent + " proxy-auth=null token=null");
	}

	@Test
	void theCredentialsAreEncodedAsMavensTransportEncodesThem() {
		assertThat(HttpMessages.basic(USER)).isEqualTo("Basic "
				+ Base64.getEncoder().encodeToString(("deployer:" + PASSWORD).getBytes(StandardCharsets.ISO_8859_1)));
		assertThat(HttpMessages.basic(new HttpAccess.Credentials("deployer", null)))
			.isEqualTo("Basic " + Base64.getEncoder().encodeToString("deployer:null".getBytes(StandardCharsets.UTF_8)));
		assertThat(USER.toString()).doesNotContain(PASSWORD);
	}

	@Test
	void withoutCredentialsTheChallengeIsTheAnswer() {
		assertThatThrownBy(() -> downloader().get(this.base + "/secret/a.jar"))
			.isInstanceOfSatisfying(HttpStatusException.class, ex -> assertThat(ex.statusCode()).isEqualTo(401));
	}

	@Test
	void refusedCredentialsAreAskedOnceAndThe401Stands() {
		HttpAccess wrong = credentials(new HttpAccess.Credentials("deployer", "wrong"));

		assertThatThrownBy(() -> downloader().get(this.base + "/secret/a.jar", wrong))
			.isInstanceOfSatisfying(HttpStatusException.class, ex -> assertThat(ex.statusCode()).isEqualTo(401));
		assertThat(this.log).hasSize(2);
	}

	@Test
	void aChallengeWithoutBasicIsRefusedByName() {
		assertThatThrownBy(() -> downloader().get(this.base + "/digest", credentials(USER)))
			.isInstanceOf(HttpStatusException.class)
			.hasMessage("HTTP 401 for " + this.base + "/digest: asks for Digest realm=\"r\", nonce=\"n\"; only Basic "
					+ "credentials are sent");
	}

	@Test
	void aRedirectKeepsTheCredentialsOnTheirHostAndTheHeadersOnEveryRequest() throws IOException {
		HttpAccess access = new HttpAccess(null, USER, Map.of("X-Token", "t1"), null, null);

		assertThat(text(downloader().get(this.base + "/moved", access))).isEqualTo("secret bytes");
		assertThatThrownBy(() -> downloader().get(this.base + "/elsewhere", access))
			.isInstanceOfSatisfying(HttpStatusException.class, ex -> assertThat(ex.statusCode()).isEqualTo(401));

		String sent = HttpMessages.basic(USER);
		assertThat(this.log).containsExactly("/moved auth=null proxy-auth=null token=t1",
				"/secret/file auth=null proxy-auth=null token=t1",
				"/secret/file auth=" + sent + " proxy-auth=null token=t1",
				"/elsewhere auth=null proxy-auth=null token=t1",
				// localhost is another host than 127.0.0.1: no credentials go there
				"/secret/file auth=null proxy-auth=null token=t1");
	}

	@Test
	void aHeaderTheTransportOwnsIsRefused() {
		HttpAccess host = new HttpAccess(null, null, Map.of("Host", "elsewhere"), null, null);

		assertThatThrownBy(() -> downloader().get(this.base + "/secret/a.jar", host)).isInstanceOf(IOException.class)
			.hasMessage("the header 'Host' cannot be set (fetching " + this.base + "/secret/a.jar)");
	}

	@Test
	void anHttpUrlGoesToTheProxyWithItsCredentials() throws IOException {
		int port = this.server.getAddress().getPort();
		HttpAccess proxied = new HttpAccess(new HttpAccess.Proxy("127.0.0.1", port, PROXY_USER), null, Map.of(), null,
				null);
		HttpAccess anonymous = new HttpAccess(new HttpAccess.Proxy("127.0.0.1", port, null), null, Map.of(), null,
				null);

		assertThat(text(downloader().get("http://repo.invalid/maven2/a.jar", proxied)))
			.isEqualTo("proxied repo.invalid");
		assertThatThrownBy(() -> downloader().get("http://repo.invalid/maven2/a.jar", anonymous))
			.isInstanceOfSatisfying(HttpStatusException.class, ex -> assertThat(ex.statusCode()).isEqualTo(407));
	}

	@Test
	void aDownloaderWithoutTransportSettingsRefusesThem() {
		Downloader plain = url -> new byte[0];

		assertThatThrownBy(() -> plain.get("https://repo.example/a.jar", credentials(USER)))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("cannot fetch https://repo.example/a.jar through a proxy, with credentials")
			.hasMessageNotContaining(PASSWORD);
	}

	@Test
	void anHttpsUrlIsTunnelledThroughAProxyThatTakesBasicCredentials() throws Exception {
		Tls tls = Tls.create(this.temp);
		HttpsServer origin = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		origin.setHttpsConfigurator(new HttpsConfigurator(tls.context()));
		origin.setExecutor(this.executor);
		origin.createContext("/", exchange -> {
			this.log.add("origin " + exchange.getRequestURI() + " auth="
					+ exchange.getRequestHeaders().getFirst("Authorization") + " token="
					+ exchange.getRequestHeaders().getFirst("X-Token"));
			switch (exchange.getRequestURI().getPath()) {
				case "/chunked" -> {
					exchange.sendResponseHeaders(200, 0);
					try (OutputStream out = exchange.getResponseBody()) {
						out.write("first ".getBytes(StandardCharsets.UTF_8));
						out.flush();
						out.write("second".getBytes(StandardCharsets.UTF_8));
					}
				}
				case "/silent" -> {
					awaitRelease();
					exchange.close();
				}
				default -> {
					if (!HttpMessages.basic(USER).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
						exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"repo\"");
						respond(exchange, 401, "");
						return;
					}
					respond(exchange, 200, "tunnelled bytes");
				}
			}
		});
		origin.start();
		ConnectProxy proxy = new ConnectProxy(this.executor, this.log);
		try {
			String url = "https://localhost:" + origin.getAddress().getPort();
			HttpDownloader downloader = new HttpDownloader(Duration.ofSeconds(5), Duration.ofMillis(500),
					tls.context());
			HttpAccess access = new HttpAccess(new HttpAccess.Proxy("127.0.0.1", proxy.port(), PROXY_USER), USER,
					Map.of("X-Token", "t2"), null, null);
			HttpAccess wrongProxyUser = new HttpAccess(
					new HttpAccess.Proxy("127.0.0.1", proxy.port(), new HttpAccess.Credentials("office", "no")), null,
					Map.of(), null, null);

			assertThat(text(downloader.get(url + "/secret/a.jar", access))).isEqualTo("tunnelled bytes");
			assertThat(text(downloader.get(url + "/chunked", access))).isEqualTo("first second");
			assertThatThrownBy(() -> downloader.get(url + "/secret/a.jar", wrongProxyUser))
				.isInstanceOfSatisfying(HttpStatusException.class, ex -> assertThat(ex.statusCode()).isEqualTo(407))
				.hasMessageStartingWith("HTTP 407 for " + url + "/secret/a.jar: the proxy 127.0.0.1:" + proxy.port()
						+ " refused the tunnel to localhost:" + origin.getAddress().getPort());
			assertThatThrownBy(() -> downloader.get(url + "/silent", access)).isInstanceOf(IOException.class)
				.hasMessage("no data from " + url + "/silent for 500 ms (server stalled)");

			String sent = HttpMessages.basic(USER);
			String authority = "localhost:" + origin.getAddress().getPort();
			assertThat(this.log).startsWith("CONNECT " + authority + " " + HttpMessages.basic(PROXY_USER),
					"origin /secret/a.jar auth=null token=t2",
					"CONNECT " + authority + " " + HttpMessages.basic(PROXY_USER),
					"origin /secret/a.jar auth=" + sent + " token=t2",
					"CONNECT " + authority + " " + HttpMessages.basic(PROXY_USER),
					"origin /chunked auth=" + sent + " token=t2");
		}
		finally {
			this.release.countDown();
			proxy.close();
			origin.stop(0);
		}
	}

	private void awaitRelease() {
		try {
			this.release.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** A self-signed {@code localhost} certificate, served and trusted. */
	private record Tls(SSLContext context) {

		static Tls create(Path directory) throws Exception {
			Path keystore = directory.resolve("localhost.p12");
			String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
			Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "EC",
					"-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12",
					"-keystore", keystore.toString(), "-storepass", "changeit")
				.redirectErrorStream(true)
				.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(process.waitFor()).as(output).isZero();
			KeyStore store = KeyStore.getInstance("PKCS12");
			try (InputStream in = Files.newInputStream(keystore)) {
				store.load(in, "changeit".toCharArray());
			}
			KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keys.init(store, "changeit".toCharArray());
			TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trust.init(store);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
			return new Tls(context);
		}

	}

	/**
	 * A {@code CONNECT}-only proxy wanting {@link #PROXY_USER}: it logs each request line
	 * with its credentials, then relays bytes both ways.
	 */
	private static final class ConnectProxy implements AutoCloseable {

		private final ServerSocket socket;

		ConnectProxy(ExecutorService executor, List<String> log) throws IOException {
			this.socket = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
			executor.execute(() -> {
				while (!this.socket.isClosed()) {
					try {
						Socket client = this.socket.accept();
						executor.execute(() -> relay(client, executor, log));
					}
					catch (IOException ex) {
						return;
					}
				}
			});
		}

		int port() {
			return this.socket.getLocalPort();
		}

		private static void relay(Socket client, ExecutorService executor, List<String> log) {
			try (client) {
				InputStream in = client.getInputStream();
				String requestLine = line(in);
				String authorization = null;
				for (String header = line(in); !header.isEmpty(); header = line(in)) {
					if (header.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) {
						authorization = header.substring(20).trim();
					}
				}
				String[] parts = requestLine.split(" ");
				log.add(parts[0] + " " + parts[1] + " " + authorization);
				OutputStream out = client.getOutputStream();
				if (!HttpMessages.basic(PROXY_USER).equals(authorization)) {
					out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"p\"\r\n"
							+ "Content-Length: 0\r\n\r\n")
						.getBytes(StandardCharsets.ISO_8859_1));
					out.flush();
					return;
				}
				String[] address = parts[1].split(":");
				try (Socket upstream = new Socket(address[0], Integer.parseInt(address[1]))) {
					out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
					out.flush();
					executor.execute(() -> pipe(in, upstream));
					upstream.getInputStream().transferTo(out);
				}
			}
			catch (IOException ex) {
				// the client went away
			}
		}

		private static void pipe(InputStream from, Socket to) {
			try {
				from.transferTo(to.getOutputStream());
			}
			catch (IOException ex) {
				// either side closed
			}
		}

		private static String line(InputStream in) throws IOException {
			StringBuilder line = new StringBuilder();
			for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
				if (b != '\r') {
					line.append((char) b);
				}
			}
			return line.toString();
		}

		@Override
		public void close() throws IOException {
			this.socket.close();
		}

	}

}
