package am.ik.rontolisp.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link ReactorEnvelope}'s key lists against {@code http-reactor.lisp}, the file
 * that really reads and writes them. Nothing else can: the envelope is a JSON document
 * built and consumed in Lisp, and the Java record exists only so the compile path and the
 * generated host glue can name its keys without copying them by eye -- which is exactly
 * the drift this asserts away.
 *
 * <p>
 * A key that gains a reader in the Lisp but no entry here would leave the generated
 * {@code worker()} silently not filling it; an entry here with no reader there would have
 * it fill a key nothing looks at. Both directions are checked.
 */
class ReactorEnvelopeTest {

	// The transport reads every request key with a literal (gethash "name" req) in
	// %http-reactor-request-tuple, and writes every response key as a plist keyword in
	// %http-reactor-envelope. Both are exhaustive by construction -- there is one reader
	// and one writer.
	private static final Pattern REQUEST_READ = Pattern.compile("\\(gethash \"([a-z-]+)\" req\\)");

	// The head plist's own keywords, inside %http-reactor-envelope and nowhere else --
	// the 500 arm's :error rides a DIFFERENT plist (the error document's) and is not an
	// envelope key, which is why the scan is scoped to one defun.
	private static final Pattern RESPONSE_WRITE = Pattern.compile(":([a-z-]+) ");

	@Test
	void theRequestKeysAreExactlyWhatTheTransportReads() throws IOException {
		String lisp = transport();
		java.util.Set<String> read = new java.util.LinkedHashSet<>();
		Matcher matcher = REQUEST_READ.matcher(lisp);
		while (matcher.find()) {
			read.add(matcher.group(1));
		}
		assertThat(read).as("http-reactor.lisp reads exactly the request keys ReactorEnvelope declares")
			.containsExactlyInAnyOrderElementsOf(ReactorEnvelope.REQUEST_KEYS);
	}

	@Test
	void theResponseKeysAreExactlyWhatTheTransportWrites() throws IOException {
		// EXACTLY, in both directions: a key %http-reactor-envelope starts writing but
		// RESPONSE_KEYS omits would be dropped from the generated Response in silence,
		// which is the failure the emitter's `default -> throw` arms exist to prevent --
		// and they can only fire on a key that reaches them.
		String envelope = defun(transport(), "%http-reactor-envelope");
		Set<String> written = new java.util.LinkedHashSet<>();
		Matcher matcher = RESPONSE_WRITE.matcher(envelope);
		while (matcher.find()) {
			written.add(matcher.group(1));
		}
		assertThat(written).as("%%http-reactor-envelope writes exactly the response keys ReactorEnvelope declares")
			.containsExactlyInAnyOrderElementsOf(ReactorEnvelope.RESPONSE_KEYS);
		assertThat(envelope).as("the body key is absent, not empty, when a sink took it")
			.contains("(if sink head (append head (list :body body)))");
	}

	@Test
	void theEnvelopeIsTheFALLBACKTheDispatcherHasAlwaysDocumented() throws IOException {
		// The envelope boundary (HostBoundary.ENVELOPE) is not a second code path: it is
		// this &optional pair left OUT. The transport's own contract is what makes that
		// legal, so the two halves of it are pinned here -- if the body source and sink
		// ever stop being optional, an in-band build stops compiling and this says why.
		assertThat(transport()).contains("(defun rontolisp::%http-reactor-dispatch (request-json &optional body sink)")
			.contains("a host that leaves them out keeps the envelope's own \\\"body\\\"\nkey in both directions");
	}

	// One top-level defun's text: from its own opening line to the blank line before the
	// next one. Enough to scope a scan, and it fails loudly if the defun is renamed.
	private static String defun(String lisp, String name) {
		int start = lisp.indexOf("(defun rontolisp::" + name + " ");
		assertThat(start).as("http-reactor.lisp still defines %s", name).isNotNegative();
		int end = lisp.indexOf("\n\n", start);
		return end < 0 ? lisp.substring(start) : lisp.substring(start, end);
	}

	private static String transport() throws IOException {
		try (InputStream in = ReactorEnvelope.class.getClassLoader()
			.getResourceAsStream("am/ik/rontolisp/eval/http-reactor.lisp")) {
			assertThat(in).as("http-reactor.lisp is on the classpath").isNotNull();
			return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
