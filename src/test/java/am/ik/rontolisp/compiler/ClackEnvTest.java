package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the server-side HTTP value model's shape declaration: the Clack environment key
 * set and order every backend's construction follows. A change here is a user-facing
 * change to the {@code rontolisp:http-handler} contract, so it must fail loudly.
 */
class ClackEnvTest {

	@Test
	void fieldsPinTheClackEnvironmentShape() {
		assertThat(ClackEnv.FIELDS).containsExactly(":REQUEST-METHOD", ":SCRIPT-NAME", ":PATH-INFO", ":QUERY-STRING",
				":SERVER-NAME", ":SERVER-PORT", ":SERVER-PROTOCOL", ":REQUEST-URI", ":URL-SCHEME", ":REMOTE-ADDR",
				":REMOTE-PORT", ":HEADERS", ":CONTENT-TYPE", ":CONTENT-LENGTH", ":RAW-BODY");
	}

	@Test
	void usesBufferedBodyReadsTheDirectiveKeyword() {
		assertThat(ClackEnv.usesBufferedBody(program("(rontolisp:http-handler 'h 8080 :raw-body :buffered)"))).isTrue();
		assertThat(ClackEnv.usesBufferedBody(program("(rontolisp:http-handler 'h 8080 :raw-body :stream)"))).isFalse();
		assertThat(ClackEnv.usesBufferedBody(program("(rontolisp:http-handler 'h 8080)"))).isFalse();
		assertThat(ClackEnv.usesBufferedBody(program("(rontolisp:http-handler 'h :raw-body :buffered)"))).isTrue();
	}

	@Test
	void usesBufferedBodySeesTheNestedServerStartSeam() {
		// The clack-handler-rontolisp shim's interpreter/JVM leg: the seam call sits
		// inside a defun, with the mode as literal trailing keywords.
		assertThat(ClackEnv.usesBufferedBody(program("""
				(defun run (app port address)
				  (rontolisp::%http-server-start app port address :raw-body :buffered))
				"""))).isTrue();
		assertThat(ClackEnv.usesBufferedBody(program("""
				(defun run (app port address)
				  (rontolisp::%http-server-start app port address))
				"""))).isFalse();
	}

	@Test
	void usesBufferedBodyIgnoresQuotedData() {
		assertThat(ClackEnv.usesBufferedBody(program("(print '(rontolisp:http-handler 'h 8080 :raw-body :buffered))")))
			.isFalse();
	}

	private static List<am.ik.rontolisp.LispVal> program(String source) {
		return LispReader.readAllFromString(source, Features.INTERPRETER);
	}

}
