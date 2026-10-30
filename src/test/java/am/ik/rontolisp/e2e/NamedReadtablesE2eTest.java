package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * named-readtables 0.9 (vendored unmodified under
 * {@code src/test/resources/named-readtables}, BSD) on the interpreter
 * ({@code .kb/readtables.md}): the system loads -- its readtable iterator is the derived
 * form {@code eval/NamedReadtablesForms}, whose markers this pins against the release --
 * {@code defreadtable} merges the standard readtable into a new one and adds a macro
 * character and a case, and a loaded file that selects it with {@code in-readtable} reads
 * the rest of itself with it, the readtable dying with the load. Interpreter only: the
 * compile paths read source with the standard readtable ({@code .kb/readtables.md}).
 */
class NamedReadtablesE2eTest {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "named-readtables")
		.toAbsolutePath()
		.toString();

	@Test
	void aLoadedFileReadsTheRestOfItselfWithTheReadtableItSelects(@TempDir Path dir) throws Exception {
		Path user = dir.resolve("user.lisp");
		Files.writeString(user, """
				(named-readtables:in-readtable nr-e2e)
				(defparameter cl-user::*read* (list !foo '!(a b) (quote |Mixed|) 'Mixed))
				""");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(List.of(SYSTEM_DIR));
		for (LispVal form : LispReader.readAllFromString("""
				(asdf:load-system :named-readtables)
				(named-readtables:defreadtable nr-e2e
				  (:merge :standard)
				  (:macro-char #\\! (lambda (s c) (declare (ignore c)) (list 'quote (list :bang (read s t nil t)))))
				  (:case :invert))
				(load "%s")
				(print *read*)
				(print (named-readtables:readtable-name (named-readtables:find-readtable 'nr-e2e)))
				(print (list (readtable-case *readtable*) (get-macro-character #\\!)))
				(print (eq (named-readtables:find-readtable :standard) (named-readtables:find-readtable nil)))
				""".formatted(user.toString().replace("\\", "\\\\")))) {
			evaluator.eval(form);
		}
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim))
			.containsExactly("((:BANG FOO) '(:BANG (A B)) |Mixed| |Mixed|)", "NR-E2E", "(:UPCASE NIL)", "T");
	}

}
