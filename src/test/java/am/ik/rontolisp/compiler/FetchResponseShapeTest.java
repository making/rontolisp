package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.Version;
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
	void requestRecordPinsTheHostFetchEnvelope() {
		// The --host-fetch request JSON: field name = JSON key, in record order. A
		// change here changes what every env.fetch host receives, so it must fail
		// loudly (the host halves are pinned against it in HostFetchLibraryTest).
		assertThat(FetchResponseShape.requestFields()).extracting(FetchResponseShape.Field::name)
			.containsExactly("url", "method", "headers", "body");
		assertThat(FetchResponseShape.requestFields()).extracting(FetchResponseShape.Field::keyword)
			.containsExactly(":URL", ":METHOD", ":HEADERS", ":BODY");
		assertThat(FetchResponseShape.HOST_ENVELOPE_ERROR_KEY).isEqualTo("error");
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

	@Test
	void theDefaultUserAgentIsDeclaredOnce() {
		// The one request header fetch adds on the caller's behalf. A change here is a
		// change to what every backend puts on the wire.
		assertThat(FetchResponseShape.USER_AGENT_HEADER).isEqualTo("User-Agent");
		assertThat(FetchResponseShape.defaultUserAgent()).startsWith("rontolisp/" + Version.getVersion());
		// HTTP field names are case-insensitive, so the "did the caller set one" test is.
		assertThat(FetchResponseShape.isUserAgentHeader("user-agent")).isTrue();
		assertThat(FetchResponseShape.isUserAgentHeader("USER-AGENT")).isTrue();
		assertThat(FetchResponseShape.isUserAgentHeader("X-User-Agent")).isFalse();
	}

	@Test
	void theUserAgentNamesTheBuildNotJustTheRelease() {
		assertThat(FetchResponseShape.userAgent("1.2.3", "af7bf32")).isEqualTo("rontolisp/1.2.3 (af7bf32)");
		// No git repository at build time: no comment at all rather than "(unknown)".
		assertThat(FetchResponseShape.userAgent("1.2.3", Version.UNKNOWN)).isEqualTo("rontolisp/1.2.3");
		assertThat(FetchResponseShape.userAgent("1.2.3", "")).isEqualTo("rontolisp/1.2.3");
		// Anything that is not a plain hash is left off rather than escaped: a
		// parenthesis would end the header comment and a quote would end the generated
		// Lisp literal.
		assertThat(FetchResponseShape.userAgent("1.2.3", "af7(bf32)\"")).isEqualTo("rontolisp/1.2.3");
	}

	@Test
	void lispHelpersCarryTheUserAgentLiterals() {
		// The component has no Version to read at run time, so http.lisp spells neither
		// the field name nor the value: both cross as generated defuns.
		assertThat(FetchResponseShape.lispHelpersSource())
			.contains("(defun %http-user-agent-header ()\n  \"" + FetchResponseShape.USER_AGENT_HEADER + "\")")
			.contains("(defun %http-default-user-agent ()\n  \"" + FetchResponseShape.defaultUserAgent() + "\")");
	}

}
