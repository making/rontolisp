package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL com.inuoe.jzon v1.1.4
 * sources (vendored unmodified under {@code src/test/resources/jzon}, MIT -- the released
 * Quicklisp layout, whose {@code .asd} additionally depends on {@code uiop}) load via
 * {@code asdf:load-system} and parse/stringify JSON on the interpreter. The dependency
 * systems {@code closer-mop}/{@code flexi-streams}/{@code float-features}/
 * {@code trivial-gray-streams}/{@code uiop} resolve to the built-in shim libraries
 * ({@code eval.ShimLibraries}), and the library exercises the deep end of the CL subset:
 * multi-parameter method dispatch with {@code :around} + eql specializers, inline
 * {@code (:method ...)} clauses, {@code tagbody}/{@code go} via {@code prog},
 * {@code macrolet} templates carrying {@code #.} read-time-eval markers, reader labels
 * ({@code #1=}/{@code #1#}), {@code shiftf}, {@code (setf (values ...))}, fill-pointered
 * adjustable strings, the eisel-lemire/schubfach float reader/printer over IEEE 754 bit
 * primitives, and rontolisp's Gray-stream protocol (stringify into a user-supplied
 * adjustable string). Compiled-backend coverage is future work (the compile path lacks
 * these features; see {@code .todo}).
 */
class JzonE2eTest {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "jzon", "src")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :com.inuoe.jzon)
			(print (com.inuoe.jzon:parse "3.14"))
			(print (com.inuoe.jzon:parse "-1.5e10"))
			(print (com.inuoe.jzon:parse "[1, 2, 3]"))
			(print (com.inuoe.jzon:parse "\\"a\\\\nb\\\\u00e9\\""))
			(let ((h (com.inuoe.jzon:parse "{\\"x\\": {\\"y\\": [1.5, \\"z\\"]}}")))
			  (print (gethash "y" (gethash "x" h))))
			(print (com.inuoe.jzon:stringify 0.1d0))
			(print (com.inuoe.jzon:stringify "he\\"llo"))
			(print (com.inuoe.jzon:stringify (com.inuoe.jzon:parse "{\\"a\\": [1, 2.5, \\"x\\", true, null]}")))
			(print (com.inuoe.jzon:stringify #(1 2 3) :pretty t))
			(let ((buf (make-array 0 :element-type 'character :adjustable t :fill-pointer 0)))
			  (com.inuoe.jzon:stringify (com.inuoe.jzon:parse "{\\"k\\": [1, true]}") :stream buf)
			  (print buf))
			(print (handler-case (com.inuoe.jzon:parse "{\\"a\\": ")
			         (com.inuoe.jzon:json-eof-error (e) :caught-eof)))
			""";

	private static final List<String> EXPECTED = List.of("3.14", "-1.5E10", "#(1 2 3)", "\"a", "bé\"", "#(1.5 \"z\")",
			"\"0.1\"", "\"\"he\\\"llo\"\"", "\"{\"a\":[1,2.5,\"x\",true,null]}\"", "\"[", "1,", "2,", "3", "]\"",
			"\"{\"k\":[1,true]}\"", ":caught-eof");

	@Test
	void loadsAndRunsOnTheInterpreter() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(List.of(SYSTEM_DIR));
		for (LispVal expr : LispReader.readAllFromString(EXERCISE)) {
			evaluator.eval(expr);
		}
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim))
			.containsExactlyElementsOf(EXPECTED);
	}

}
