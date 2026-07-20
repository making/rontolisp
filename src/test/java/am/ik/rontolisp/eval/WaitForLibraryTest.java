package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaitForLibraryTest {

	@Test
	void processSplicesWaitLibraryForCanonicalReference() {
		List<LispVal> program = LispReader
			.readAllFromString("(rontolisp:async-defun main () (rontolisp:await (rontolisp:wait-for 10)))");
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
