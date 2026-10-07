package am.ik.rontolisp.ansi;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AnsiChapterRunnerTest {

	/**
	 * {@code setf} on an unrecognized place throws a raw
	 * {@code UnsupportedOperationException} at macroexpansion time (LispMacroExpander),
	 * which the suite shim's own {@code handler-case} cannot catch -- exactly the failure
	 * mode .todo/681 is about. Before the fix, this form escapes
	 * {@code AnsiChapterRunner}'s outer catch as {@code %%%EVAL}, which
	 * {@code ChapterResult.parse} counts into neither {@code pass}, {@code fail} nor
	 * {@code error} -- the passing test right after it is the control that shows the loss
	 * costs exactly one test, not the rest of the chapter.
	 */
	@Test
	void aRawExceptionEscapingADeftestIsCountedAsAnErrorNotALostForm(@TempDir Path tempDir) throws Exception {
		Path suite = tempDir.resolve("suite");
		Path chapter = suite.resolve("mychapter");
		Files.createDirectories(chapter);
		Files.writeString(chapter.resolve("load.lsp"), "(load \"test.lsp\")\n");
		Files.writeString(chapter.resolve("test.lsp"),
				"(deftest my-broken-test (setf (length x) 1) nil)\n" + "(deftest my-ok-test (+ 1 2) 3)\n");

		String output = runChapter(suite, "mychapter", Path.of("ansi-test/rt-shim.lisp"));

		assertThat(output).isEqualToNormalizingWhitespace("""
				%%%FILE mychapter/test.lsp
				%%%AT 0
				ERROR MY-BROKEN-TEST UnsupportedOperationException: setf does not support place: LENGTH
				%%%AT 1
				PASS MY-OK-TEST
				%%%END 2
				""");

		AnsiCompliance.ChapterResult result = AnsiCompliance.ChapterResult.parse("mychapter", output, List.of());
		assertThat(result.pass()).isEqualTo(1);
		assertThat(result.error()).isEqualTo(1);
		assertThat(result.fail()).isZero();
		assertThat(result.evalFailures()).isZero();
		assertThat(result.readFailures()).isZero();
		assertThat(result.total()).isEqualTo(2);
	}

	private static String runChapter(Path suite, String chapter, Path shim) throws Exception {
		PrintStream original = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
		try {
			AnsiChapterRunner.main(new String[] { suite.toString(), chapter, shim.toString() });
		}
		finally {
			System.setOut(original);
		}
		return captured.toString(StandardCharsets.UTF_8);
	}

}
