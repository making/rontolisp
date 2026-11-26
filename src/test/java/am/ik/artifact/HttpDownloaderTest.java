package am.ik.artifact;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The network downloader against a local {@link HttpServer}: the body of a 200, a failing
 * status, a redirect, and the idle timeout that keeps a stalled server from hanging the
 * caller -- before the headers and in the middle of the body.
 */
class HttpDownloaderTest {

	private static final Duration IDLE = Duration.ofMillis(300);

	private final CountDownLatch release = new CountDownLatch(1);

	private HttpServer server;

	private ExecutorService executor;

	private String base;

	@BeforeEach
	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.executor = Executors.newCachedThreadPool();
		this.server.setExecutor(this.executor);
		this.server.createContext("/ok", exchange -> {
			byte[] body = "artifact bytes".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		this.server.createContext("/missing", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});
		this.server.createContext("/broken", exchange -> {
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		this.server.createContext("/moved", exchange -> {
			exchange.getResponseHeaders().add("Location", "/ok");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		this.server.createContext("/silent", exchange -> {
			awaitRelease();
			exchange.close();
		});
		this.server.createContext("/stalls-mid-body", exchange -> {
			exchange.sendResponseHeaders(200, 1000);
			OutputStream out = exchange.getResponseBody();
			out.write(new byte[100]);
			out.flush();
			awaitRelease();
			exchange.close();
		});
		this.server.start();
		this.base = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		this.release.countDown();
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	private void awaitRelease() {
		try {
			this.release.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private HttpDownloader downloader() {
		return new HttpDownloader(Duration.ofSeconds(5), IDLE);
	}

	@Test
	void answersTheBodyOfA200() throws IOException {
		assertThat(new String(downloader().get(this.base + "/ok"), StandardCharsets.UTF_8)).isEqualTo("artifact bytes");
	}

	@Test
	void followsARedirect() throws IOException {
		assertThat(new String(downloader().get(this.base + "/moved"), StandardCharsets.UTF_8))
			.isEqualTo("artifact bytes");
	}

	@Test
	void anyOtherStatusFails() {
		assertThatThrownBy(() -> downloader().get(this.base + "/missing")).isInstanceOf(IOException.class)
			.hasMessage("HTTP 404 for " + this.base + "/missing");
	}

	@Test
	void theStatusTellsNotFoundFromAFailure() {
		// A consumer searching several repositories moves on after a 404 and nothing
		// else.
		assertThatThrownBy(() -> downloader().get(this.base + "/missing")).isInstanceOfSatisfying(
				HttpStatusException.class, ex -> assertThat(ex.isNotFound()).as("404 is not found").isTrue());
		assertThatThrownBy(() -> downloader().get(this.base + "/broken"))
			.isInstanceOfSatisfying(HttpStatusException.class, ex -> {
				assertThat(ex.statusCode()).isEqualTo(500);
				assertThat(ex.isNotFound()).isFalse();
			});
	}

	@Test
	void aServerThatNeverAnswersTimesOut() {
		assertThatThrownBy(() -> downloader().get(this.base + "/silent")).isInstanceOf(IOException.class)
			.hasMessage("no data from " + this.base + "/silent for 300 ms (server stalled)");
	}

	@Test
	void aBodyThatStopsArrivingTimesOut() {
		assertThatThrownBy(() -> downloader().get(this.base + "/stalls-mid-body")).isInstanceOf(IOException.class)
			.hasMessage("no data from " + this.base + "/stalls-mid-body for 300 ms (server stalled)");
	}

	@Test
	void aMalformedUrlIsAnIoFailure() {
		assertThatThrownBy(() -> downloader().get("http://bad host/")).isInstanceOf(IOException.class)
			.hasMessage("not a URL: http://bad host/");
	}

}
