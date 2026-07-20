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
 * {@code asdf:load-system} and parse/stringify JSON on ALL FOUR backends. The dependency
 * systems {@code closer-mop}/{@code flexi-streams}/{@code float-features}/
 * {@code trivial-gray-streams}/{@code uiop} resolve to the built-in shim libraries, and
 * the numeric leaf components ({@code eisel-lemire.lisp}/{@code ratio-to-double.lisp}/
 * {@code schubfach.lisp}) are replaced by the rontolisp-native leaf-module shims (both in
 * {@code eval.ShimLibraries}) -- so the float text is rontolisp's cross-backend-identical
 * shape, not schubfach's shortest-round-trip string. The library exercises the deep end
 * of the CL subset: multi-parameter method dispatch with {@code :around} + eql
 * specializers, inline {@code (:method ...)} clauses, {@code tagbody}/{@code go} via
 * {@code prog}, {@code macrolet} templates carrying {@code #.} read-time-eval markers,
 * reader labels ({@code #1=}/{@code #1#}), {@code shiftf}, {@code (setf (values ...))},
 * fill-pointered adjustable strings, runtime {@code subtypep}/condition-type
 * {@code error} dispatch, runtime-slot-name {@code slot-value}/{@code slot-boundp} over
 * {@code closer-mop:class-slots}, a multi-valued {@code :replacer}, and rontolisp's
 * Gray-stream protocol (stringify into a user-supplied adjustable string, {@code format}
 * to a CLOS stream instance).
 *
 * <p>
 * The exercise avoids the two accepted cross-backend divergences: the WASM large-float
 * print shape and hash-table iteration order over multiple keys (plus non-ASCII
 * {@code \\u} escapes, whose {@code code-char} is byte-oriented on WASM) -- those stay
 * pinned by the interpreter-only README walkthrough below.
 */
class JzonE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "jzon", "src")
		.toAbsolutePath()
		.toString();

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String artifactName() {
		return "JzonProgram";
	}

	@Override
	protected String exercise() {
		return """
				(asdf:load-system :com.inuoe.jzon)
				(print (com.inuoe.jzon:parse "3.14"))
				(print (com.inuoe.jzon:parse "[1, 2, 3]"))
				(print (com.inuoe.jzon:parse "\\"a\\\\nb\\\\u0041\\""))
				(let ((h (com.inuoe.jzon:parse "{\\"x\\": {\\"y\\": [1.5, \\"z\\"]}}")))
				  (print (gethash "y" (gethash "x" h))))
				(print (com.inuoe.jzon:stringify 0.1d0))
				(print (com.inuoe.jzon:stringify "he\\"llo"))
				(print (com.inuoe.jzon:stringify (com.inuoe.jzon:parse "{\\"a\\": [1, 2.5, \\"x\\", true, null]}")))
				(print (com.inuoe.jzon:stringify #(1 2 3) :pretty t))
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
				  (setf (gethash '|noChange| ht) '|when used|)
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
				(let ((buf (make-array 0 :element-type 'character :adjustable t :fill-pointer 0)))
				  (com.inuoe.jzon:stringify (com.inuoe.jzon:parse "{\\"k\\": [1, true]}") :stream buf)
				  (print buf))
				(print (handler-case (com.inuoe.jzon:parse "{\\"a\\": ")
				         (com.inuoe.jzon:json-eof-error (e) :caught-eof)))
				""";
	}

	@Override
	protected List<String> expected() {
		return List.of("3.14", "#(1 2 3)", "\"a", "bA\"", "#(1.5 \"z\")", "\"0.1\"", "\"\"he\\\"llo\"\"",
				"\"{\"a\":[1,2.5,\"x\",true,null]}\"", "\"[", "1,", "2,", "3", "]\"",
				// the README hash-table equalp series
				"t", "t", "t", "t", "t", "t", "t",
				// stringify #(null nil t 42 3.14 "Hello, world!") :stream t :pretty t
				"[", "null,", "false,", "true,", "42,", "3.14,", "\"Hello, world!\"", "]",
				// allow-multiple-content signals json-parse-error
				":CAUGHT-MULTIPLE-CONTENT",
				// :replacer (returned string, echoed via print)
				"\"[", "\"second\",", "\"Lupin the third\"", "]\"",
				// a |...|-escaped symbol hash key (verbatim case)
				"{", "\"noChange\": \"when used\"", "}",
				// CLOS instance serialized as an object (alias is nil-bound, so null)
				"{", "\"name\": \"Anya\",", "\"alias\": null,", "\"job\": null,", "\"married\": false,",
				"\"children\": []", "}",
				// stringify into a user-supplied fill-pointered adjustable string
				"\"{\"k\":[1,true]}\"", ":CAUGHT-EOF");
	}

	// The README walkthrough pieces the four-backend exercise cannot carry -- a
	// non-ASCII \\u escape (code-char is byte-oriented on WASM), a large-float print
	// (the WASM print shape differs) and a multi-key hash table (iteration order is
	// backend-local) -- pinned on the interpreter. Known deviations from the README's
	// output: symbol values keep rontolisp's verbatim case, and the never-initialized
	// alias slot appears as null (slots have no unbound state -- they default to nil).
	private static final String INTERPRETER_RESIDUE_EXERCISE = """
			(asdf:load-system :com.inuoe.jzon)
			(print (com.inuoe.jzon:parse "\\"a\\\\nb\\\\u00e9\\""))
			(print (com.inuoe.jzon:parse "-1.5e10"))
			(let ((ht (make-hash-table :test 'equal)))
			  (setf (gethash 'only-keys ht) 'are-affected)
			  (setf (gethash '|noChange| ht) '|when used|)
			  (setf (gethash "AS A" ht) '|value|)
			  (com.inuoe.jzon:stringify ht :pretty t :stream t))
			(terpri)
			""";

	private static final List<String> INTERPRETER_RESIDUE_EXPECTED = List.of("\"a", "bé\"", "-1.5E10", "{",
			"\"only-keys\": \"ARE-AFFECTED\",", "\"noChange\": \"when used\",", "\"AS A\": \"value\"", "}");

	@Test
	void interpreterResidueCases() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(List.of(SYSTEM_DIR));
		for (LispVal expr : LispReader.readAllFromString(INTERPRETER_RESIDUE_EXERCISE)) {
			evaluator.eval(expr);
		}
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim))
			.containsExactlyElementsOf(INTERPRETER_RESIDUE_EXPECTED);
	}

}
