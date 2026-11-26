package am.ik.rontolisp;

/**
 * The two built-ins whose function VALUE calls a prelude helper
 * ({@code compiler.BuiltinFunctionWrappers}): {@code #'make-broadcast-stream} builds the
 * Gray broadcast stream the call position builds, with components or without, and
 * {@code #'write-to-string} binds the printer variables its runtime keyword tail names --
 * rejecting a bad tail with the call position's report. Shared by the backend suites, so
 * every backend is held to one expected text.
 */
public final class HelperWrapperFixture {

	private HelperWrapperFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defun hw-call (f &rest a) (apply f a))
			(defun hw-report (thunk)
			  (handler-case (funcall thunk) (program-error (e) (princ-to-string e))))
			(let* ((a (make-string-output-stream))
			       (b (make-string-output-stream))
			       (s (hw-call #'make-broadcast-stream a b)))
			  (format s "hi~a" 1)
			  (write-string "!" s)
			  (print (list (get-output-stream-string a) (get-output-stream-string b)
			               (length (broadcast-stream-streams s)) (typep s 'broadcast-stream))))
			(let ((s (funcall 'make-broadcast-stream)))
			  (write-string "dropped" s)
			  (print (list (typep s 'broadcast-stream) (broadcast-stream-streams s) (file-length s))))
			(print (list (hw-call #'write-to-string 10 :base 2) (hw-call #'write-to-string 10 :base 16 :radix t)
			             (hw-call #'write-to-string '(a b c d) :length 2 :case :downcase)
			             (hw-call #'write-to-string "s" :escape nil) (hw-call #'write-to-string "s")
			             (hw-call #'write-to-string 5 :bogus 1 :allow-other-keys t)
			             (mapcar #'write-to-string '(1 x "y"))))
			(print (hw-report (lambda () (hw-call #'write-to-string 10 :bogus 2))))
			(print (hw-report (lambda () (hw-call #'write-to-string 10 :base))))
			(print (hw-report (lambda () (hw-call #'write-to-string))))
			""";

	/** What {@link #PROGRAM} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(\"hi1!\" \"hi1!\" 2 T)", "(T NIL 0)",
			"(\"1010\" \"#xA\" \"(a b ...)\" \"s\" \"\\\"s\\\"\" \"5\" (\"1\" \"X\" \"\\\"y\\\"\"))",
			"\"WRITE-TO-STRING expects keyword arguments :ESCAPE/:READABLY/:PRETTY/:CIRCLE/:RIGHT-MARGIN"
					+ "/:MISER-WIDTH/:LINES/:PPRINT-DISPATCH/:LENGTH/:LEVEL/:BASE/:RADIX/:CASE/:GENSYM/:ARRAY, got: :BOGUS\"",
			"\"WRITE-TO-STRING expects a value after :BASE\"",
			"\"WRITE-TO-STRING expects at least 1 argument, got 0\"");

}
