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
 * adjustable string). Deliberately NOT migrated onto {@code AsdfLibraryE2eSupport}: the
 * compiled backends cannot run the full library -- its float reader/printer does
 * 64-bit/bignum bit arithmetic beyond the WASM numeric model, and its buffers need the
 * interpreter's mutable fill-pointered strings. The isolated language features it forced
 * are compiled everywhere and pinned by the {@code jzon-residue-features} /
 * {@code lite-builtins-residue} ci-spec cases instead.
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

	// The jzon README walkthrough: parse into a hash table with equalp-verifiable
	// values (vectors included), :stream t as a destination (doubles take schubfach's
	// check-type'd write path), :allow-multiple-content signalling a json-parse-error
	// (whose report applies #'format), a multi-valued :replacer, |...|-escaped symbol
	// hash keys, and CLOS instances serialized as objects via coerced-fields over the
	// closer-mop shim. Known deviations from the README's output: symbol values keep
	// rontolisp's verbatim case ("are-affected", "when used"), and the never-initialized
	// alias slot appears as null (slots have no unbound state -- they default to nil).
	private static final String README_EXERCISE = """
			(asdf:load-system :com.inuoe.jzon)
			(defparameter *readme-ht* (com.inuoe.jzon:parse "{
			  \\"license\\": null,
			  \\"active\\": false,
			  \\"important\\": true,
			  \\"id\\": 1,
			  \\"xp\\": 3.2,
			  \\"name\\": \\"Rock\\",
			  \\"tags\\":  [
			    \\"alone\\"
			  ]
			}"))
			(print (equalp 'null       (gethash "license" *readme-ht*)))
			(print (equalp nil         (gethash "active" *readme-ht*)))
			(print (equalp t           (gethash "important" *readme-ht*)))
			(print (equalp 1           (gethash "id" *readme-ht*)))
			(print (equalp 3.2d0       (gethash "xp" *readme-ht*)))
			(print (equalp "Rock"      (gethash "name" *readme-ht*)))
			(print (equalp #("alone")  (gethash "tags" *readme-ht*)))
			(com.inuoe.jzon:stringify #(null nil t 42 3.14 "Hello, world!") :stream t :pretty t)
			(terpri)
			(print (handler-case (com.inuoe.jzon:parse "123[1, 2, 3]" :allow-multiple-content t)
			         (com.inuoe.jzon:json-parse-error (e) :caught-multiple-content)))
			(print (com.inuoe.jzon:stringify #("first" "second" "third")
			                :pretty t
			                :replacer (lambda (key value)
			                            (case key
			                              ((nil) t)
			                              (0 nil)
			                              (1 t)
			                              (2 (values t (format nil "Lupin the ~A" value)))))))
			(let ((ht (make-hash-table :test 'equal)))
			  (setf (gethash 'only-keys ht) 'are-affected)
			  (setf (gethash '|noChange| ht) '|when used|)
			  (setf (gethash "AS A" ht) '|value|)
			  (com.inuoe.jzon:stringify ht :pretty t :stream t))
			(terpri)
			(defclass readme-person ()
			  ((name :initarg :name :reader name)
			   (alias :initarg :alias)
			   (job :initarg :job :reader job)
			   (married :initarg :married :type boolean)
			   (children :initarg :children :type list)))
			(com.inuoe.jzon:stringify (make-instance 'readme-person :name "Anya" :job nil
			                               :married nil :children nil)
			                :pretty t :stream t)
			(terpri)
			""";

	private static final List<String> README_EXPECTED = List.of("t", "t", "t", "t", "t", "t", "t",
			// stringify #(null nil t 42 3.14 "Hello, world!") :stream t :pretty t
			"[", "null,", "false,", "true,", "42,", "3.14,", "\"Hello, world!\"", "]",
			// allow-multiple-content signals json-parse-error
			":caught-multiple-content",
			// :replacer (returned string, echoed via print)
			"\"[", "\"second\",", "\"Lupin the third\"", "]\"",
			// symbol/string hash keys (verbatim case; |...| escapes)
			"{", "\"only-keys\": \"are-affected\",", "\"noChange\": \"when used\",", "\"AS A\": \"value\"", "}",
			// CLOS instance serialized as an object (alias is nil-bound, so null)
			"{", "\"name\": \"Anya\",", "\"alias\": null,", "\"job\": null,", "\"married\": false,", "\"children\": []",
			"}");

	@Test
	void readmeWalkthroughRunsOnTheInterpreter() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(List.of(SYSTEM_DIR));
		for (LispVal expr : LispReader.readAllFromString(README_EXERCISE)) {
			evaluator.eval(expr);
		}
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim))
			.containsExactlyElementsOf(README_EXPECTED);
	}

}
