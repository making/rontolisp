package am.ik.rontolisp.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code --host-fetch} lowering against {@link FetchResponseShape}: the
 * generated Lisp AND the shipped host ({@code examples/cloudflare-workers/dog-fetcher})
 * both carry the envelope the records declare, so the two sides of the {@code env.fetch}
 * boundary cannot drift apart -- the {@code GlImportObjectTest} rule, applied to the
 * fetch envelope instead of eyeballs.
 */
class HostFetchLibraryTest {

	private static final Path WORKER_HOST = Path.of("examples/cloudflare-workers/dog-fetcher/src/index.js");

	@Test
	void spliceHappensExactlyWhenTheProgramFetches() {
		List<LispVal> fetches = LispReader.readAllFromString("(defun f () (rontolisp:fetch \"https://x\"))",
				Features.WASM_REACTOR);
		assertThat(HostFetchLibrary.process(fetches)).hasSizeGreaterThan(fetches.size());
		// The zero-import contract: a program that never fetches is returned as-is.
		List<LispVal> plain = LispReader.readAllFromString("(defun f () 1)", Features.WASM_REACTOR);
		assertThat(HostFetchLibrary.process(plain)).isSameAs(plain);
	}

	@Test
	void generatedLoweringCarriesTheDerivedEnvelope() {
		String source = HostFetchLibrary.source();
		// The one import, under the documented host module/field.
		assertThat(source).contains(":from \"" + HostFetchLibrary.IMPORT_MODULE + "\"")
			.contains(":as \"" + HostFetchLibrary.IMPORT_FIELD + "\"");
		// Request side: every record field crosses, as its own JSON key.
		for (FetchResponseShape.Field field : FetchResponseShape.requestFields()) {
			assertThat(source).as("request field '%s' is carried", field.name()).contains(field.keyword());
		}
		// Response side: every record field is read back by its JSON key, in record
		// order inside the result plist, plus the reserved error arm.
		for (FetchResponseShape.Field field : FetchResponseShape.responseFields()) {
			assertThat(source).as("response field '%s' is read", field.name())
				.contains("(gethash \"" + field.name() + "\" envelope)");
		}
		assertThat(source).contains("(gethash \"" + FetchResponseShape.HOST_ENVELOPE_ERROR_KEY + "\" envelope)");
	}

	@Test
	void theShippedWorkerHostSpeaksTheSameEnvelope() throws IOException {
		String host = Files.readString(WORKER_HOST, StandardCharsets.UTF_8);
		// The host reads every request field the record declares...
		for (FetchResponseShape.Field field : FetchResponseShape.requestFields()) {
			assertThat(host).as("the worker host reads request.%s", field.name()).contains("request." + field.name());
		}
		// ...and answers every response field, plus the error arm, as JSON keys.
		for (FetchResponseShape.Field field : FetchResponseShape.responseFields()) {
			assertThat(host).as("the worker host answers '%s'", field.name()).contains(field.name() + ":");
		}
		assertThat(host).contains(FetchResponseShape.HOST_ENVELOPE_ERROR_KEY + ":");
	}

	@Test
	void loweringParsesInCanonicalShape() {
		// The generated source must read cleanly with the reactor feature set; a parse
		// error here would otherwise surface as a confusing CLI failure.
		assertThat(HostFetchLibrary.forms()).isNotEmpty();
	}

}
