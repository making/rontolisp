package am.ik.rontolisp;

/**
 * A non-float first argument or a non-integer second argument to {@code scale-float},
 * shared by the backend suites (mirrored by the
 * `scale-float-refuses-a-non-float-or-non-integer-argument` ci-spec case). Either is a
 * {@code type-error} whose datum is the argument and whose expected type is {@code FLOAT}
 * or {@code INTEGER}, the float refused first -- a complex, an integer, a ratio, a symbol
 * and nil included -- in call position and first class, with run-time and literal
 * arguments. The last rows are answers, including an exponent no fixnum holds. The
 * arguments are read at run time so no backend can fold them.
 */
public final class ScaleFloatOperandsFixture {

	private ScaleFloatOperandsFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defvar *sfo-c* (complex (read-from-string "2.0") 4.0))
			(defvar *sfo-3* (read-from-string "3"))
			(defvar *sfo-1* (read-from-string "1"))
			(defvar *sfo-r* (read-from-string "1/2"))
			(defvar *sfo-a* (read-from-string "a"))
			(defvar *sfo-nil* (read-from-string "nil"))
			(defvar *sfo-f* (read-from-string "1.5"))
			(defvar *sfo-nbig* (- (expt 2 70)))
			(defun sfo-probe (thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)))
			    (error () :other-error)))
			(defmacro sfo-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(sfo-probe (lambda () ,f))) forms))))
			(sfo-row (scale-float *sfo-c* *sfo-1*) (scale-float *sfo-3* *sfo-1*) (scale-float *sfo-r* *sfo-1*) (scale-float *sfo-a* *sfo-1*))
			(sfo-row (scale-float *sfo-nil* *sfo-1*) (scale-float *sfo-f* *sfo-f*) (scale-float *sfo-f* *sfo-nil*) (scale-float *sfo-f* *sfo-r*))
			(sfo-row (scale-float *sfo-f* *sfo-c*) (scale-float *sfo-f* *sfo-a*) (scale-float *sfo-a* *sfo-a*) (scale-float *sfo-c* *sfo-f*))
			(sfo-row (funcall #'scale-float *sfo-3* *sfo-1*) (apply #'scale-float (list *sfo-c* *sfo-1*)) (funcall #'scale-float *sfo-f* *sfo-f*) (mapcar #'scale-float (list *sfo-f* 2.5) (list 1 2)))
			(sfo-row (scale-float 3 1) (scale-float 1/2 1) (scale-float #c(1.0 2.0) 1) (scale-float 'a 1))
			(sfo-row (scale-float 1.5 1.5) (scale-float 1.5 'a) (scale-float 1.5 nil) (scale-float 1.5 #c(1.0 2.0)))
			(sfo-row (scale-float 1.5 3) (scale-float *sfo-f* *sfo-1*) (scale-float 0.0 5) (scale-float -1.5 -1))
			(sfo-row (scale-float *sfo-f* *sfo-nbig*) (scale-float *sfo-f* (- (expt 2 40))) (scale-float 1.5 -1000000) (scale-float *sfo-f* 0))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n",
			"((:TYPE-ERROR #C(2.0 4.0) FLOAT) (:TYPE-ERROR 3 FLOAT) (:TYPE-ERROR 1/2 FLOAT) (:TYPE-ERROR A FLOAT))",
			"((:TYPE-ERROR NIL FLOAT) (:TYPE-ERROR 1.5 INTEGER) (:TYPE-ERROR NIL INTEGER) (:TYPE-ERROR 1/2 INTEGER))",
			"((:TYPE-ERROR #C(2.0 4.0) INTEGER) (:TYPE-ERROR A INTEGER) (:TYPE-ERROR A FLOAT) (:TYPE-ERROR #C(2.0 4.0) FLOAT))",
			"((:TYPE-ERROR 3 FLOAT) (:TYPE-ERROR #C(2.0 4.0) FLOAT) (:TYPE-ERROR 1.5 INTEGER) ((3.0 10.0)))",
			"((:TYPE-ERROR 3 FLOAT) (:TYPE-ERROR 1/2 FLOAT) (:TYPE-ERROR #C(1.0 2.0) FLOAT) (:TYPE-ERROR A FLOAT))",
			"((:TYPE-ERROR 1.5 INTEGER) (:TYPE-ERROR A INTEGER) (:TYPE-ERROR NIL INTEGER) (:TYPE-ERROR #C(1.0 2.0) INTEGER))",
			"((12.0) (3.0) (0.0) (-0.75))", "((0.0) (0.0) (0.0) (1.5))");

}
