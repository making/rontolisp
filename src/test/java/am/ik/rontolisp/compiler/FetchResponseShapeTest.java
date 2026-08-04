package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the fetch result shape — the response record every backend derives its plist
 * builder and readers from. A change here is a user-facing change to the
 * {@code rontolisp:fetch} value model, so it must fail loudly. (The server side has no
 * plist shape of its own since the Clack cutover: a handler receives the Clack
 * environment, {@link ClackEnvTest}.)
 */
class FetchResponseShapeTest {

	@Test
	void responseRecordPinsTheFetchPlist() {
		assertThat(FetchResponseShape.responseFields()).extracting(FetchResponseShape.Field::name)
			.containsExactly("status", "headers", "body");
		assertThat(FetchResponseShape.responseFields()).extracting(FetchResponseShape.Field::keyword)
			.containsExactly(":STATUS", ":HEADERS", ":BODY");
	}

	@Test
	void responseDefaultsAreDeclaredOnce() {
		assertThat(FetchResponseShape.RESPONSE_STATUS_DEFAULT).isEqualTo(200);
		assertThat(FetchResponseShape.RESPONSE_BODY_DEFAULT).isEmpty();
	}

	@Test
	void responseFieldResolvesByName() {
		assertThat(FetchResponseShape.responseField("status").keyword()).isEqualTo(":STATUS");
		assertThatThrownBy(() -> FetchResponseShape.responseField("trailers")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("trailers");
	}

	@Test
	void requireResponseHandledAcceptsTheRecordAndRejectsDrift() {
		FetchResponseShape.requireResponseHandled(Set.of("status", "headers", "body"));
		assertThatThrownBy(() -> FetchResponseShape.requireResponseHandled(Set.of("status", "body")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("headers");
		assertThatThrownBy(
				() -> FetchResponseShape.requireResponseHandled(Set.of("status", "headers", "body", "cookie")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cookie");
	}

	@Test
	void lispHelpersFollowTheRecord() {
		String source = FetchResponseShape.lispHelpersSource();
		List<String> lines = source.lines().toList();
		assertThat(lines).contains("(defun %http-response-plist (status headers body)",
				"  (list :STATUS status :HEADERS headers :BODY body))");
		assertThat(source).contains("(defun %http-response-status (plist)\n  (or (getf plist :STATUS) 200))")
			.contains("(defun %http-response-body (plist)\n  (or (getf plist :BODY) \"\"))")
			.contains("(defun %http-response-headers (plist)\n  (getf plist :HEADERS))")
			.doesNotContain("%http-request-");
	}

}
