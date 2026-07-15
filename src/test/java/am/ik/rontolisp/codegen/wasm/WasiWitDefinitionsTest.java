package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import am.ik.wit.WitPrinter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every {@link WasiWitDefinitions} document byte-for-byte against its fixture under
 * {@code src/test/resources/.../component/wit/} — the verbatim capture of
 * {@code wasm-tools component wit} on a reference component of that blob variant (with
 * the {@code use} clause restored on the http-server variants; see
 * {@code src/wasm-component/regen-wit.sh}). This is the always-on oracle for the
 * generated definitions; the live tool diff is {@code WitOracleE2eTest}.
 */
class WasiWitDefinitionsTest {

	private static final Path FIXTURES = Path.of("src/test/resources/am/ik/rontolisp/codegen/wasm/component/wit");

	@Test
	void everyVariantMatchesItsCapturedFixtureByteForByte() throws IOException {
		for (String variant : new String[] { WitEmitter.VARIANT_BASE, WitEmitter.VARIANT_SOCKETS,
				WitEmitter.VARIANT_HTTP_SERVER, WitEmitter.VARIANT_HTTP_SERVER_CLIENT, WitEmitter.VARIANT_NOGC,
				WitEmitter.VARIANT_NOGC_PRINT }) {
			String fixture = Files.readString(FIXTURES.resolve(variant + ".wit"), StandardCharsets.UTF_8);
			assertThat(WitPrinter.print(WasiWitDefinitions.document(variant))).as(variant).isEqualTo(fixture);
		}
	}

}
