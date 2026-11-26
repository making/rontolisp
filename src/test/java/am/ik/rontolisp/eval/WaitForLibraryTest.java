package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class WaitForLibraryTest {

	@ParameterizedTest
	@ValueSource(strings = { "(rontolisp:async-defun main () (rontolisp:await (rontolisp:wait-for 10)))",
			// a library spliced later races its reads (sockets.lisp) or its handshake
			// (tls.lisp) against a wait-for timer: naming its surface is the reference
			"(print (rontolisp:tcp-connect \"127.0.0.1\" 1))", "(print (rontolisp:tls-connect \"127.0.0.1\" 1))" })
	void processSplicesWaitLibraryForCanonicalReference(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		List<LispVal> out = WaitForLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		assertThat(HttpLibraryTest.definesDefun(out, "RONTOLISP:WAIT-FOR")).isTrue();
	}

	@Test
	void processSplicesWaitLibraryForNicknameReference() {
		// rl: is a built-in nickname of rontolisp; the splice scan runs before
		// PackageResolver normalizes it, so the scan must normalize itself.
		List<LispVal> program = LispReader.readAllFromString("(rl:async-defun main () (rl:await (rl:wait-for 10)))");
		List<LispVal> out = WaitForLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		assertThat(HttpLibraryTest.definesDefun(out, "RONTOLISP:WAIT-FOR")).isTrue();
	}

	@Test
	void processDoesNotSpliceWhenProgramDefinesWaitForUnderNickname() {
		// The dedup guard must also recognize a user defun spelled through the
		// nickname, or the splice would collide with it.
		List<LispVal> program = LispReader.readAllFromString("(defun rl:wait-for (ms) ms)\n(rl:wait-for 10)");
		List<LispVal> out = WaitForLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		assertThat(out).isEqualTo(program);
	}

}
