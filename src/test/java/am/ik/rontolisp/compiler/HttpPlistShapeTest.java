package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the HTTP plist shape — the request/response records every backend derives its
 * plist builders and readers from. A change here is a user-facing change to the
 * {@code rontolisp:http-handler} / {@code rontolisp:fetch} value model, so it must fail
 * loudly.
 */
class HttpPlistShapeTest {

	@Test
	void requestRecordPinsTheHandlerRequestPlist() {
		assertThat(HttpPlistShape.requestFields()).extracting(HttpPlistShape.Field::name)
			.containsExactly("method", "path", "query", "headers", "body");
		assertThat(HttpPlistShape.requestFields()).extracting(HttpPlistShape.Field::keyword)
			.containsExactly(":method", ":path", ":query", ":headers", ":body");
	}

	@Test
	void responseRecordPinsTheResponseAndFetchPlist() {
		assertThat(HttpPlistShape.responseFields()).extracting(HttpPlistShape.Field::name)
			.containsExactly("status", "headers", "body");
		assertThat(HttpPlistShape.responseFields()).extracting(HttpPlistShape.Field::keyword)
			.containsExactly(":status", ":headers", ":body");
	}

	@Test
	void responseDefaultsAreDeclaredOnce() {
		assertThat(HttpPlistShape.RESPONSE_STATUS_DEFAULT).isEqualTo(200);
		assertThat(HttpPlistShape.RESPONSE_BODY_DEFAULT).isEmpty();
	}

	@Test
	void responseFieldResolvesByName() {
		assertThat(HttpPlistShape.responseField("status").keyword()).isEqualTo(":status");
		assertThatThrownBy(() -> HttpPlistShape.responseField("trailers")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("trailers");
	}

	@Test
	void requireResponseHandledAcceptsTheRecordAndRejectsDrift() {
		HttpPlistShape.requireResponseHandled(Set.of("status", "headers", "body"));
		assertThatThrownBy(() -> HttpPlistShape.requireResponseHandled(Set.of("status", "body")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("headers");
		assertThatThrownBy(() -> HttpPlistShape.requireResponseHandled(Set.of("status", "headers", "body", "cookie")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cookie");
	}

	@Test
	void lispHelpersFollowTheRecords() {
		String source = HttpPlistShape.lispHelpersSource();
		List<String> lines = source.lines().toList();
		assertThat(lines).contains("(defun %http-request-plist (method path query headers body)",
				"  (list :method method :path path :query query :headers headers :body body))",
				"(defun %http-response-plist (status headers body)",
				"  (list :status status :headers headers :body body))");
		assertThat(source).contains("(defun %http-response-status (plist)\n  (or (getf plist :status) 200))")
			.contains("(defun %http-response-body (plist)\n  (or (getf plist :body) \"\"))")
			.contains("(defun %http-response-headers (plist)\n  (getf plist :headers))")
			.contains("(defun %http-request-body (plist)\n  (getf plist :body))");
	}

}
