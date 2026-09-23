package am.ik.rontolisp;

/**
 * The `read-sequence` / `write-sequence` argument checks shared by the backend suites and
 * mirrored by the `read-and-write-sequence-signal-type-error-for-a-bad-sequence-
 * or-bound` ci-spec case. The answers are sbcl's: a dotted-list buffer, a negative,
 * non-integer or symbolic bound, and a range outside the buffer are all `type-error`s --
 * for a string buffer, a general vector and a list alike.
 */
public final class SequenceBoundsFixture {

	private SequenceBoundsFixture() {
	}

	/** The program; string streams only, so no file is needed. */
	public static final String PROGRAM = """
			(print (handler-case (with-input-from-string (s "abc") (read-sequence '(a . b) s))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :start -1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :end -1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :start 'x))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :start 1.5))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :end 9))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-input-from-string (s "abc") (read-sequence (make-array 3) s :start 2 :end 1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence '(a . b) o))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :start -1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :end -1))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :start 'x))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :end 1.5))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :end 9))
			         (type-error () :type-error) (error () :other-error)))
			(print (handler-case (with-output-to-string (o) (write-sequence #(1 2 3) o :start 2 :end 1))
			         (type-error () :type-error) (error () :other-error)))
			""";

	/** What {@link #PROGRAM} prints, one value per line (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR",
			":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR",
			":TYPE-ERROR", ":TYPE-ERROR", ":TYPE-ERROR");

}
