package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoWasiFilesystemStubsTest {

	@Test
	void anOpenCallBecomesACallTimeStubThatStillEvaluatesItsArguments() {
		// The real form computes its arguments and then opens, so the stub keeps the
		// argument expressions and replaces only the opening.
		assertThat(rewrite("(open (path-for x) :direction :input)")).contains("(PATH-FOR X)")
			.contains("has no filesystem");
	}

	@Test
	void withOpenFileKeepsItsPathAndDropsTheBodyItCouldNeverRun() {
		String out = rewrite("(with-open-file (s (path-for x) :direction :input) (read-line s))");
		assertThat(out).contains("(PATH-FOR X)").contains("has no filesystem").doesNotContain("READ-LINE");
	}

	@Test
	void aVariableNamedOpenIsNotACallToOpen() {
		// Only OPERATOR position is a call. `open` is an ordinary variable name as well
		// as a CL function, and walking a list's TAIL as if it were a form rewrote the
		// (OPEN T) tail of (setq open t) into a stub -- leaving a setq with ONE
		// argument, which the WASM backend rejects at compile time.
		String source = """
				(defvar open nil)
				(defun f () (setq open t) open)
				""";
		assertThat(rewrite(source)).doesNotContain("has no filesystem").contains("(SETQ OPEN T)");
	}

	@Test
	void theNameTakenAsAValueIsNotACall() {
		// (function open) has the same tail shape, and rewriting it produced a
		// (function (progn ...)) that names no function at all.
		assertThat(rewrite("(mapcar #'open paths)")).doesNotContain("has no filesystem").contains("(FUNCTION OPEN)");
	}

	@Test
	void aProgramThatDefinesItsOwnOpenKeepsIt() {
		String source = """
				(defun open (p) p)
				(open "x")
				""";
		assertThat(rewrite(source)).doesNotContain("has no filesystem");
	}

	private static String rewrite(String source) {
		List<LispVal> out = NoWasiFilesystemStubs.rewrite(LispReader.readAllFromString(source));
		StringBuilder sb = new StringBuilder();
		for (LispVal form : out) {
			sb.append(form.print()).append('\n');
		}
		return sb.toString();
	}

}
