package am.ik.rontolisp.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code --host-fetch} lowering against {@link FetchResponseShape}: the
 * generated Lisp AND the shipped host ({@code examples/cloudflare-workers/dog-fetcher})
 * both carry the envelope the records declare, so the two sides of the {@code env.fetch}
 * boundary cannot drift apart -- the {@code GlImportObjectTest} rule, applied to the
 * fetch envelope instead of eyeballs.
 */
class HostFetchLibraryTest {

	// The shipped host half. GENERATED since the glue learned to write it on both
	// boundaries (src/index.js is three lines now), which is why this reads worker.js:
	// the file that really speaks the envelope is the one to hold to the record.
	private static final Path WORKER_HOST = Path.of("examples/cloudflare-workers/dog-fetcher/src/worker.js");

	@Test
	void spliceHappensExactlyWhenTheProgramFetches() {
		List<LispVal> fetches = LispReader.readAllFromString("(defun f () (rontolisp:fetch \"https://x\"))",
				Features.WASM_REACTOR);
		assertThat(HostFetchLibrary.process(fetches, HostBoundary.STREAMING)).hasSizeGreaterThan(fetches.size());
		// The zero-import contract: a program that never fetches is returned as-is.
		List<LispVal> plain = LispReader.readAllFromString("(defun f () 1)", Features.WASM_REACTOR);
		assertThat(HostFetchLibrary.process(plain, HostBoundary.STREAMING)).isSameAs(plain);
	}

	// The envelope is the record's, whichever boundary carries the body -- that is the
	// whole point of deriving it -- so both shapes are held to the same assertions.
	@ParameterizedTest
	@EnumSource(HostBoundary.class)
	void generatedLoweringCarriesTheDerivedEnvelope(HostBoundary boundary) {
		String source = HostFetchLibrary.source(boundary);
		// The head's import, under the documented host module/field.
		assertThat(source).contains(":from \"" + HostFetchLibrary.IMPORT_MODULE + "\"")
			.contains(":as \"" + HostFetchLibrary.IMPORT_FIELD + "\"");
		// Request side: every record field crosses, as its own JSON key.
		for (FetchResponseShape.Field field : FetchResponseShape.requestFields()) {
			assertThat(source).as("request field '%s' is carried", field.name()).contains(field.keyword());
		}
		// Response side: every record field is read back by its JSON key, in record
		// order inside the result plist, plus the reserved error arm. The body's key is
		// read on BOTH boundaries: on the streaming one it is the in-band fallback a host
		// may still fill, on the envelope one it is where the whole reply arrived.
		for (FetchResponseShape.Field field : FetchResponseShape.responseFields()) {
			assertThat(source).as("response field '%s' is read", field.name())
				.contains("(gethash \"" + field.name() + "\" envelope)");
		}
		assertThat(source).contains("(gethash \"" + FetchResponseShape.HOST_ENVELOPE_ERROR_KEY + "\" envelope)");
		assertThat(source).as("the body is the stream every backend's :body is")
			.contains("(rontolisp::%host-fetch-body (gethash \"" + FetchResponseShape.responseField("body").name()
					+ "\" envelope))");
	}

	@Test
	void theEnvelopeBoundaryDropsTheBodyImportAndTheCursorBehindIt() {
		String envelope = HostFetchLibrary.source(HostBoundary.ENVELOPE);
		String streaming = HostFetchLibrary.source(HostBoundary.STREAMING);
		// The reply-body import, the generation counter that tells a stream its reply was
		// superseded, the pull thunk over the import, and the mid-body error channel the
		// split needs -- all four exist only because the body left the head.
		assertThat(streaming).contains(":as \"" + HostFetchLibrary.BODY_IMPORT_FIELD + "\"")
			.contains("%host-fetch-open")
			.contains("%host-fetch-pull")
			.contains("failed mid-transfer");
		assertThat(envelope).doesNotContain(HostFetchLibrary.BODY_IMPORT_FIELD)
			.doesNotContain("%host-fetch-open")
			.doesNotContain("%host-fetch-pull")
			.doesNotContain("failed mid-transfer")
			// ...and the reactor's byte-shaped receive machinery is not named either.
			.doesNotContain("%http-reactor-buffer")
			.doesNotContain("%http-reactor-chunk");
		// What is left is one stream over the head's own key -- no default written
		// here, because the transport normalizes an absent/empty/null key into the
		// empty stream itself, which is what the record's declared default means.
		assertThat(envelope).contains("(rontolisp::%http-reactor-body-stream in-band)");
		assertThat(FetchResponseShape.RESPONSE_BODY_DEFAULT).as("an empty stream drains to the declared default")
			.isEmpty();
		// The streaming arm normalizes BEFORE choosing the pull thunk, so a host
		// answering `"body": null` -- JSON for "not here", not for "empty" -- still
		// reaches the import rather than short-circuiting on a non-nil symbol.
		assertThat(streaming).contains("(or (rontolisp::%http-reactor-source in-band)");
	}

	@Test
	void theShippedWorkerHostSpeaksTheSameEnvelope() throws IOException {
		String host = Files.readString(WORKER_HOST, StandardCharsets.UTF_8);
		// The host reads every request field the record declares...
		for (FetchResponseShape.Field field : FetchResponseShape.requestFields()) {
			assertThat(host).as("the worker host reads request.%s", field.name()).contains("request." + field.name());
		}
		// ...and answers every response field, plus the error arm -- the head's fields
		// as JSON keys, the BODY through its own import, which is the split this
		// boundary is. A record that grows a field fails here until the host carries it.
		for (FetchResponseShape.Field field : FetchResponseShape.responseFields()) {
			if ("body".equals(field.name())) {
				assertThat(host).as("the worker host provides the body import")
					.contains(HostFetchLibrary.BODY_IMPORT_FIELD);
			}
			else {
				assertThat(host).as("the worker host answers '%s'", field.name()).contains(field.name() + ":");
			}
		}
		assertThat(host).contains(FetchResponseShape.HOST_ENVELOPE_ERROR_KEY + ":");
		// And the file the deployment actually imports is now three lines over it.
		assertThat(Files.readString(Path.of("examples/cloudflare-workers/dog-fetcher/src/index.js"),
				StandardCharsets.UTF_8))
			.contains("export default worker(module);");
	}

	@ParameterizedTest
	@EnumSource(HostBoundary.class)
	void loweringParsesInCanonicalShape(HostBoundary boundary) {
		// The generated source must read cleanly with the reactor feature set; a parse
		// error here would otherwise surface as a confusing CLI failure. Cached per
		// boundary, so a second compile in the same JVM must not be served the first
		// boundary's parse -- which is what asking for both here would catch.
		assertThat(HostFetchLibrary.forms(boundary)).isNotEmpty();
		assertThat(HostFetchLibrary.forms(boundary)).isSameAs(HostFetchLibrary.forms(boundary));
	}

}
