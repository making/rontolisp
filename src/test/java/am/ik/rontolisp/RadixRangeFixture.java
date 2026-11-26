package am.ik.rontolisp;

/**
 * A radix outside {@code 2..36} to {@code digit-char}, {@code digit-char-p} and
 * {@code parse-integer}, shared by the backend suites (mirrored by the
 * `digit-char-digit-char-p-and-parse-integer-refuse-a-radix-outside-2-to-36` ci-spec
 * case). The radix is a {@code type-error} whose datum is the radix and whose expected
 * type is {@code (INTEGER 2 36)} -- a non-integer one included -- before any character is
 * read, in call position and first class, with a run-time and a literal radix. A bad
 * character is still {@code digit-char-p}'s first refusal; {@code digit-char}'s weight is
 * an {@code unsigned-byte} refused before its radix. The last rows are answers in range.
 * The radixes are read at run time so no backend can fold them.
 */
public final class RadixRangeFixture {

	private RadixRangeFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defvar *rr-37* (read-from-string "37"))
			(defvar *rr-36* (read-from-string "36"))
			(defvar *rr-10* (read-from-string "10"))
			(defvar *rr-2* (read-from-string "2"))
			(defvar *rr-1* (read-from-string "1"))
			(defvar *rr-0* (read-from-string "0"))
			(defvar *rr-m1* (read-from-string "-1"))
			(defvar *rr-f* (read-from-string "10.5"))
			(defvar *rr-nil* (read-from-string "nil"))
			(defvar *rr-big* (expt 2 64))
			(defvar *rr-9* (read-from-string "9"))
			(defvar *rr-5* (read-from-string "5"))
			(defvar *rr-35* (read-from-string "35"))
			(defun rr-probe (thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)))
			    (error () :other-error)))
			(defmacro rr-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(rr-probe (lambda () ,f))) forms))))
			(rr-row (digit-char-p #\\2 *rr-37*) (digit-char-p #\\2 *rr-1*) (digit-char-p #\\2 *rr-0*) (digit-char-p #\\2 *rr-m1*))
			(rr-row (digit-char-p #\\2 *rr-big*) (digit-char-p #\\2 *rr-f*) (digit-char-p #\\2 *rr-nil*) (digit-char-p 1 *rr-37*))
			(rr-row (digit-char-p #\\1 *rr-2*) (digit-char-p #\\2 *rr-2*) (digit-char-p #\\z *rr-36*) (digit-char-p #\\7 *rr-10*))
			(rr-row (parse-integer "12" :radix *rr-37*) (parse-integer "12" :radix *rr-1*) (parse-integer "" :radix *rr-37*)
			        (parse-integer "12" :radix *rr-37* :junk-allowed t))
			(rr-row (parse-integer "12" :radix *rr-nil*) (parse-integer "12" :radix *rr-f*) (parse-integer "12" :end *rr-9* :radix *rr-37*)
			        (parse-integer "zz" :radix *rr-36*))
			(rr-row (funcall #'digit-char-p #\\2 *rr-37*) (funcall #'parse-integer "12" :radix *rr-37*)
			        (apply #'parse-integer "12" (list :radix *rr-1*)) (funcall #'parse-integer "zz" :radix *rr-36*))
			(rr-row (digit-char-p #\\2 37) (digit-char-p #\\2 1) (parse-integer "12" :radix 37) (parse-integer "12" :radix 0))
			(rr-row (digit-char-p #\\2 36) (parse-integer "12" :radix 2 :junk-allowed t) (parse-integer "12" :radix 36) (parse-integer "ff" :radix 16))
			(rr-row (digit-char *rr-5* *rr-37*) (digit-char *rr-5* *rr-1*) (digit-char *rr-35* *rr-36*) (digit-char 36 *rr-37*))
			(rr-row (digit-char *rr-5* *rr-big*) (digit-char *rr-5* *rr-f*) (digit-char *rr-5* *rr-nil*) (digit-char *rr-5* *rr-0*))
			(rr-row (digit-char *rr-m1* *rr-37*) (digit-char *rr-f* *rr-10*) (digit-char *rr-nil* *rr-37*) (digit-char *rr-big* *rr-10*))
			(rr-row (funcall #'digit-char *rr-5* *rr-37*) (apply #'digit-char (list *rr-5* *rr-1*)) (funcall #'digit-char *rr-m1* *rr-10*) (funcall #'digit-char *rr-35* *rr-36*))
			(rr-row (digit-char 5 37) (digit-char 5 1) (digit-char -1 10) (digit-char 1.5 10))
			(rr-row (digit-char 5) (digit-char 11 16) (digit-char 36 36) (digit-char 35 36))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n",
			"((:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 1 (INTEGER 2 36)) (:TYPE-ERROR 0 (INTEGER 2 36)) (:TYPE-ERROR -1 (INTEGER 2 36)))",
			"((:TYPE-ERROR 18446744073709551616 (INTEGER 2 36)) (:TYPE-ERROR 10.5 (INTEGER 2 36)) (:TYPE-ERROR NIL (INTEGER 2 36)) (:TYPE-ERROR 1 CHARACTER))",
			"((1) (NIL) (35) (7))",
			"((:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 1 (INTEGER 2 36)) (:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 37 (INTEGER 2 36)))",
			"((:TYPE-ERROR NIL (INTEGER 2 36)) (:TYPE-ERROR 10.5 (INTEGER 2 36)) (:TYPE-ERROR 37 (INTEGER 2 36)) (1295 2))",
			"((:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 1 (INTEGER 2 36)) (1295 2))",
			"((:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 1 (INTEGER 2 36)) (:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 0 (INTEGER 2 36)))",
			"((2) (1 1) (38 2) (255 2))",
			"((:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 1 (INTEGER 2 36)) (#\\Z) (:TYPE-ERROR 37 (INTEGER 2 36)))",
			"((:TYPE-ERROR 18446744073709551616 (INTEGER 2 36)) (:TYPE-ERROR 10.5 (INTEGER 2 36)) (:TYPE-ERROR NIL (INTEGER 2 36)) (:TYPE-ERROR 0 (INTEGER 2 36)))",
			"((:TYPE-ERROR -1 UNSIGNED-BYTE) (:TYPE-ERROR 10.5 UNSIGNED-BYTE) (:TYPE-ERROR NIL UNSIGNED-BYTE) (NIL))",
			"((:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 1 (INTEGER 2 36)) (:TYPE-ERROR -1 UNSIGNED-BYTE) (#\\Z))",
			"((:TYPE-ERROR 37 (INTEGER 2 36)) (:TYPE-ERROR 1 (INTEGER 2 36)) (:TYPE-ERROR -1 UNSIGNED-BYTE) (:TYPE-ERROR 1.5 UNSIGNED-BYTE))",
			"((#\\5) (#\\B) (NIL) (#\\Z))");

}
