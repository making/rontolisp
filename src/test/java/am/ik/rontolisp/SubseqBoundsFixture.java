package am.ik.rontolisp;

/**
 * {@code subseq}'s bounds check, shared by the backend suites: a
 * {@code start}/{@code end} pair with {@code start &lt; 0}, {@code end} past the
 * sequence's length, or {@code start &gt; end} is an error with the SAME text on every
 * backend, whatever representation the sequence is in -- a literal string, a built
 * (mutable) string, a list, a general vector, a packed integer vector, a fill-pointer
 * vector (its length is the fill pointer). The error is a {@code type-error} (CLHS
 * 17.1.1) whose datum is the first bound outside its range -- {@code start} outside
 * {@code [0, length]}, else {@code end} outside {@code [start, length]} -- and whose
 * expected type is that range, {@code (INTEGER LO HI)}. The bounds are computed at run
 * time so no backend can fold them; before the check reached every representation, the
 * compile paths answered a truncated list, a raw {@code AREF} report, a
 * {@code ClassCastException} or a wasm trap.
 */
public final class SubseqBoundsFixture {

	private SubseqBoundsFixture() {
	}

	/** The program; a plain {@code handler-case} needs no file or stream setup. */
	public static final String PROGRAM = """
			(print (handler-case (subseq "abc" 2 1) (error (e) (princ-to-string e))))
			(print (handler-case (subseq "abc" 0 5) (error (e) (princ-to-string e))))
			(defun subseq-probe (s i &optional e)
			  (handler-case (if e (subseq s i e) (subseq s i)) (error (c) (princ-to-string c))))
			(defvar *probe-fp* (make-array 4 :fill-pointer 2 :initial-contents '(1 2 3 4)))
			(dolist (s (list (list 1 2 3) (vector 1 2 3) (concatenate 'string "ab" "c")
			                 (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3))
			                 *probe-fp*))
			  (print (list (subseq-probe s -1) (subseq-probe s 1 5) (subseq-probe s 2 1) (subseq-probe s 4)
			               (subseq-probe s 1 2) (subseq-probe s 2))))
			(defun subseq-type-probe (s i &optional e)
			  (handler-case (if e (subseq s i e) (subseq s i))
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(dolist (s (list "abc" (list 1 2 3) (vector 1 2 3) (concatenate 'string "ab" "c")
			                 (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3))
			                 *probe-fp*))
			  (print (list (subseq-type-probe s -1) (subseq-type-probe s 1 5) (subseq-type-probe s 2 1)
			               (subseq-type-probe s 4))))
			""";

	/** What {@link #PROGRAM} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "\"SUBSEQ: invalid bounds 2, 1 for string of length 3\"",
			"\"SUBSEQ: invalid bounds 0, 5 for string of length 3\"", row("list", 3, "(2)", "(3)"),
			row("vector", 3, "#(2)", "#(3)"), row("string", 3, "\"b\"", "\"c\""), row("vector", 3, "#(2)", "#(3)"),
			row("vector", 2, "#(2)", "#()"), typeRow(3), typeRow(3), typeRow(3), typeRow(3), typeRow(3), typeRow(2));

	// One printed row: the four refused ranges (-1 / 1 5 / 2 1 / 4) and the two answers.
	private static String row(String kind, int length, String oneToTwo, String fromTwo) {
		String tail = " for " + kind + " of length " + length + "\"";
		return "(\"SUBSEQ: invalid bounds -1, " + length + tail + " \"SUBSEQ: invalid bounds 1, 5" + tail
				+ " \"SUBSEQ: invalid bounds 2, 1" + tail + " \"SUBSEQ: invalid bounds 4, " + length + tail + " "
				+ oneToTwo + " " + fromTwo + ")";
	}

	// One printed type-error row: each refused range's datum and expected type.
	private static String typeRow(int length) {
		return "((-1 (INTEGER 0 " + length + ")) (5 (INTEGER 1 " + length + ")) (1 (INTEGER 2 " + length
				+ ")) (4 (INTEGER 0 " + length + ")))";
	}

}
