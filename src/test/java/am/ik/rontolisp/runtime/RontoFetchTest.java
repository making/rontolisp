package am.ik.rontolisp.runtime;

import java.net.http.HttpHeaders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the field list every JDK-backed fetch answers as {@code :headers} and the timing
 * of a request that cannot be built. The cross-backend behaviour is
 * {@code FetchSpecE2eTest}'s; this covers what its local HTTP/1.1 origin cannot send.
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

}
