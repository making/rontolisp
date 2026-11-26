package am.ik.servlet.httpserver.keyvalue;

import java.net.CookieManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import am.ik.rontolisp.wit.WitException;
import am.ik.servlet.httpserver.ServletHttpServer;
import am.ik.servlet.httpserver.SessionData;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyValueSessionStoreTest {

	@Test
	void aServerKeepsItsSessionsHereWithNoCallOfItsOwn() throws Exception {
		try (ServletHttpServer server = ServletHttpServer.builder()
			.address(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
			.servlet("count", (request, response) -> {
				HttpSession session = request.getSession();
				Integer n = (Integer) session.getAttribute("n");
				session.setAttribute("n", n == null ? 1 : n + 1);
				if (request.getParameter("object") != null) {
					try {
						session.setAttribute("n", new Object());
					}
					catch (IllegalArgumentException ex) {
						response.getWriter().print(ex.getMessage() + "; ");
					}
				}
				response.getWriter().print("n=" + session.getAttribute("n"));
			}, servlet -> servlet.urlPatterns("/count"))
			.start()) {
			HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
			String base = "http://127.0.0.1:" + server.port() + "/count";
			assertThat(get(client, base)).isEqualTo("n=1");
			assertThat(get(client, base)).isEqualTo("n=2");
			// The store found is this one, which keeps no plain object.
			assertThat(get(client, base + "?object")).isEqualTo("the session attribute n is a java.lang.Object, "
					+ "which a session in a key-value store cannot hold: it holds " + SessionCodec.KEPT + "; n=3");
		}
	}

	private static String get(HttpClient client, String uri) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofString())
			.body();
	}

	@Test
	void aSessionIsReadBackAsItWasWrittenAndTheExpiredOnesAreLookedForATurnAtATime() {
		MemoryKeyValueStore memory = new MemoryKeyValueStore(2);
		KeyValueSessionStore store = new KeyValueSessionStore(memory);
		SessionData written = new SessionData("A", 1_000, 5_000, 3_000, 6_000, false);
		Map<String, Object> attributes = written.getAttributes();
		List<Object> list = new ArrayList<>(Arrays.asList("x", null, 2));
		Map<Object, Object> map = new LinkedHashMap<>();
		map.put("k", List.of(1L, 2.5));
		map.put(7, "v");
		attributes.putAll(Map.of("text", "é ü 😀", "yes", true, "char", 'q', "byte", (byte) -56, "short", (short) 300,
				"int", -7, "long", 1L << 40, "float", 1.5f, "double", -0.25, "list", list));
		attributes.put("set", new LinkedHashSet<>(List.of("b", "a")));
		attributes.put("map", map);
		attributes.put("octets", new byte[] { 0, -1, 127 });
		store.save(written);
		SessionData read = store.load("A");
		assertThat(read.getId()).isEqualTo("A");
		assertThat(List.of(read.getCreationTime(), read.getAccessedTime(), read.getLastAccessedTime()))
			.containsExactly(1_000L, 5_000L, 3_000L);
		assertThat(read.getMaxInactiveInterval()).isEqualTo(6_000);
		assertThat(read.isFresh()).isFalse();
		assertThat(read.getAttributes()).usingRecursiveComparison().isEqualTo(attributes);
		assertThat(read.getAttributes().get("set")).isInstanceOf(LinkedHashSet.class)
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ITERABLE)
			.containsExactly("b", "a");
		assertThat(store.load("B")).isNull();
		// A request that only reads leaves the access time as kept until it moved by a
		// tenth of the interval, at most a minute.
		store.saveAccess(new SessionData("A", 1_000, 64_999, 5_000, 6_000, false));
		assertThat(store.load("A").getAccessedTime()).isEqualTo(5_000L);
		store.saveAccess(new SessionData("A", 1_000, 65_000, 5_000, 6_000, false));
		assertThat(store.load("A").getAccessedTime()).isEqualTo(65_000L);
		store.saveAccess(new SessionData("A", 1_000, 65_999, 65_000, 10, false));
		assertThat(store.load("A").getAccessedTime()).isEqualTo(65_000L);
		store.saveAccess(new SessionData("A", 1_000, 66_000, 65_000, 10, false));
		assertThat(store.load("A").getAccessedTime()).isEqualTo(66_000L);
		store.save(written);
		assertThatThrownBy(() -> {
			written.getAttributes().put("bad", List.of(new Object()));
			store.save(written);
		}).isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("the session attribute bad is a java.");
		// The expired: over pages of two keys, a pass every ten minutes; what is no
		// session under the prefix is deleted, the keys of others are left.
		store.save(new SessionData("B", 0, 1_000, 0, 1, true));
		store.save(new SessionData("C", 0, 9_000, 0, 1, true));
		store.save(new SessionData("D", 0, 1_000, 0, 0, true));
		try (KeyValueStore.Bucket bucket = memory.open("")) {
			bucket.set(KeyValueSessionStore.PREFIX + "E", new byte[] { 9 });
			bucket.set("other", new byte[] { 1 });
		}
		assertThat(store.expired(9_500)).extracting(SessionData::getId).containsExactly("B");
		assertThat(store.expired(609_499)).isEmpty();
		assertThat(store.expired(609_500)).extracting(SessionData::getId).containsExactly("B", "C");
		store.delete("B");
		assertThatThrownBy(() -> memory.open("other")).isInstanceOf(WitException.class)
			.extracting(ex -> ((WitException) ex).error())
			.isEqualTo(new KeyValueStore.Error.NoSuchStore());
		try (KeyValueStore.Bucket bucket = memory.open("")) {
			assertThat(keys(bucket)).containsExactly("other", KeyValueSessionStore.PREFIX + "A",
					KeyValueSessionStore.PREFIX + "C", KeyValueSessionStore.PREFIX + "D", KeyValueSessionStore.SWEEP);
			assertThat(sweep(bucket)).isEqualTo("1209500");
		}
		// A turn reads sixteen sessions at most, and the next, a minute later, goes on
		// from the key it stopped at: the twentieth expired session is found by the
		// second turn of the pass.
		for (int i = 0; i < 20; i++) {
			store.save(new SessionData("F%02d".formatted(i), 0, 0, 0, 1, true));
		}
		assertThat(store.expired(1_209_500)).extracting(SessionData::getId)
			.containsExactly("C", "F00", "F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11",
					"F12");
		try (KeyValueStore.Bucket bucket = memory.open("")) {
			assertThat(sweep(bucket)).isEqualTo("1269500 17");
		}
		assertThat(store.expired(1_269_499)).isEmpty();
		assertThat(store.expired(1_269_500)).extracting(SessionData::getId)
			.containsExactly("F13", "F14", "F15", "F16", "F17", "F18", "F19");
		try (KeyValueStore.Bucket bucket = memory.open("")) {
			assertThat(sweep(bucket)).isEqualTo("1869500");
		}
	}

	private static String sweep(KeyValueStore.Bucket bucket) {
		return new String(bucket.get(KeyValueSessionStore.SWEEP).get(), StandardCharsets.UTF_8);
	}

	private static List<String> keys(KeyValueStore.Bucket bucket) {
		List<String> keys = new ArrayList<>();
		Optional<String> cursor = Optional.empty();
		do {
			KeyValueStore.KeyResponse page = bucket.listKeys(cursor);
			assertThat(page.keys()).hasSizeLessThanOrEqualTo(2);
			keys.addAll(page.keys());
			cursor = page.cursor();
		}
		while (cursor.isPresent());
		return keys;
	}

}
