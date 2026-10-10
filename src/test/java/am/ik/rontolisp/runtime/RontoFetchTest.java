package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the field list every JDK-backed fetch answers as {@code :headers}, the timing of a
 * request that cannot be built, and the body stream filling in as the reply arrives. The
 * cross-backend behaviour is {@code FetchSpecE2eTest}'s; this covers what its local
 * HTTP/1.1 origin cannot send, and the transport's own queue.
 */
class RontoFetchTest {

	@Test
	void responseFieldsDropPseudoFieldsAndSortByNameKeepingEachValue() {
		Map<String, List<String>> wire = new LinkedHashMap<>();
		wire.put("x-b", List.of("2"));
		wire.put(":status", List.of("200"));
		wire.put("Set-Cookie", List.of("b=2", "a=1"));
		wire.put("content-type", List.of("text/plain"));
		HttpHeaders headers = HttpHeaders.of(wire, (name, value) -> true);

		assertThat(RontoFetch.responseFields(headers)).containsExactly(Map.entry("content-type", "text/plain"),
				Map.entry("set-cookie", "b=2"), Map.entry("set-cookie", "a=1"), Map.entry("x-b", "2"));
	}

	@Test
	void aUrlTheRequestCannotCarryFailsTheFutureInsteadOfThrowing() {
		Object future = RontoFetch.start("http://127.0.0.1/a b", "GET", List.of(), "", "rontolisp/test");

		assertThat(future).isInstanceOf(CompletableFuture.class);
		assertThat((CompletableFuture<?>) future).isCompletedExceptionally();
	}

	@Test
	void theBodyFillsInAsTheReplyArrivesAndAFailedTransferQueuesItsFailure() throws IOException, InterruptedException {
		// the future settles at the head, and each part of the body is a chunk of its
		// stream once it has arrived: the origin holds the rest of the reply until the
		// first part has been taken; a transfer cut short queues a failed future, which
		// the read taking it awaits, ahead of the end
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/held", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write("first".getBytes(StandardCharsets.UTF_8));
				body.flush();
				release.await(30, TimeUnit.SECONDS);
				body.write("second".getBytes(StandardCharsets.UTF_8));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		server.createContext("/cut-short", exchange -> {
			exchange.sendResponseHeaders(200, 1000);
			OutputStream body = exchange.getResponseBody();
			body.write(new byte[10]);
			body.flush();
			exchange.close();
		});
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		try {
			String origin = "http://127.0.0.1:" + server.getAddress().getPort();
			LinkedBlockingQueue<Object> held = bodyQueue(
					RontoFetch.start(origin + "/held", "GET", List.of(), "", "rontolisp/test"));
			assertThat(text(held, "first".length())).isEqualTo("first");
			release.countDown();
			assertThat(text(held, Integer.MAX_VALUE)).isEqualTo("second");
			LinkedBlockingQueue<Object> cutShort = bodyQueue(
					RontoFetch.start(origin + "/cut-short", "GET", List.of(), "", "rontolisp/test"));
			List<Object> cut = new ArrayList<>();
			Object item = cutShort.poll(30, TimeUnit.SECONDS);
			while (item != RontoFetch.STREAM_MARKER) {
				assertThat(item).as("an item within 30 seconds").isNotNull();
				cut.add(item);
				item = cutShort.poll(30, TimeUnit.SECONDS);
			}
			assertThat(cut).last()
				.isInstanceOfSatisfying(CompletableFuture.class,
						failed -> assertThat(failed).isCompletedExceptionally());
		}
		finally {
			release.countDown();
			server.stop(0);
		}
	}

	// The body stream's queue in the response plist the fetch future settles to: the
	// plist is cons cells, Object[2] {car, cdr}, the stream {SMARKER, queue, state}.
	private static LinkedBlockingQueue<Object> bodyQueue(Object future) {
		Object rest = ((CompletableFuture<?>) future).join();
		while (rest instanceof Object[] cell && !":BODY".equals(cell[0])) {
			rest = ((Object[]) cell[1])[1];
		}
		Object[] stream = (Object[]) ((Object[]) ((Object[]) rest)[1])[0];
		@SuppressWarnings("unchecked")
		LinkedBlockingQueue<Object> queue = (LinkedBlockingQueue<Object>) stream[1];
		return queue;
	}

	// The text of the octet chunks taken from the queue until it holds LENGTH
	// characters or the end of the stream is taken.
	private static String text(LinkedBlockingQueue<Object> queue, int length) throws InterruptedException {
		StringBuilder out = new StringBuilder();
		while (out.length() < length) {
			Object chunk = queue.poll(30, TimeUnit.SECONDS);
			assertThat(chunk).as("a chunk within 30 seconds").isNotNull();
			if (chunk == RontoFetch.STREAM_MARKER) {
				break;
			}
			// an octet chunk: the bare byte[] of its octets
			out.append(new String((byte[]) chunk, StandardCharsets.UTF_8));
		}
		return out.toString();
	}

}
