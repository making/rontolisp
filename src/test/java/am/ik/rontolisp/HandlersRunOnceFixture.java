package am.ik.rontolisp;

/**
 * A program whose {@code handler-bind} handlers must run once per condition while other
 * conditions come and go around it: one handled, declined or abandoned in an
 * {@code unwind-protect} cleanup the first is on its way out through, one a report
 * handles while the message is built, one handled between the two pads, and a
 * {@code handler-case} that declines the first on its way. Whether a condition's handlers
 * ran rides its own throw; one global mark held it until 2026-09-27, the other condition
 * replaced it, and the outer pair ran twice on all four backends (the raw failure handled
 * in a cleanup: on the interpreter, which walks it at the signal point). The expected
 * text is SBCL's. Shared by the backend suites, so every backend is held to one expected
 * text; {@code ci-spec.yaml}'s {@code handlers-run-once-while-a-cleanup-signals} pins one
 * row per mechanism but the report's on the native binary, over standard condition
 * classes (a {@code define-condition} costs the JVM corpus class's constant pool what its
 * tripwire no longer has).
 */
public final class HandlersRunOnceFixture {

	private HandlersRunOnceFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(define-condition hro-typed (error) ())
			(define-condition hro-other (error) ())
			(define-condition hro-note (condition) ())
			(define-condition hro-reporting (error) ()
			  (:report (lambda (c s)
			             (declare (ignore c))
			             (format s "reported ~a" (handler-case (error 'hro-other) (hro-other () :inner))))))
			(defvar *hro-log* nil)
			(defun hro-bad (x) (car x))
			(defun hro-scenario (n)
			  (case n
			    (1 (unwind-protect (error 'hro-typed)
			         (handler-case (error 'hro-other) (hro-other () nil))))
			    (2 (unwind-protect (hro-bad "a")
			         (handler-case (hro-bad "b") (error () nil))))
			    (3 (unwind-protect (error 'hro-typed)
			         (signal 'hro-note)))
			    (4 (unwind-protect (error 'hro-typed)
			         (block hro-b
			           (unwind-protect (error 'hro-other)
			             (return-from hro-b nil)))))
			    (5 (error 'hro-reporting))
			    (6 (handler-case
			           (unwind-protect (error 'hro-typed)
			             (handler-case (error 'hro-other) (hro-other () nil)))
			         (hro-other () :declined)))
			    (7 (handler-case (hro-bad "a") (hro-other () :declined)))
			    (t (hro-bad "a"))))
			(defun hro-run (n)
			  (setq *hro-log* nil)
			  (print (list (handler-case
			                   (handler-bind ((error (lambda (c) (push (list :outer (type-of c)) *hro-log*))))
			                     (unwind-protect
			                          (handler-bind ((error (lambda (c) (push (list :inner (type-of c)) *hro-log*))))
			                            (hro-scenario n))
			                       (when (= n 8)
			                         (handler-case (error "x") (error () nil)))))
			                 (error (e) (type-of e)))
			               (reverse *hro-log*))))
			(dotimes (i 8) (hro-run (+ i 1)))
			""";

	/** What {@link #SOURCE} prints, one line per case. */
	public static final String EXPECTED = """
			(HRO-TYPED ((:INNER HRO-TYPED) (:OUTER HRO-TYPED)))
			(TYPE-ERROR ((:INNER TYPE-ERROR) (:OUTER TYPE-ERROR)))
			(HRO-TYPED ((:INNER HRO-TYPED) (:OUTER HRO-TYPED)))
			(HRO-TYPED ((:INNER HRO-TYPED) (:OUTER HRO-TYPED) (:INNER HRO-OTHER) (:OUTER HRO-OTHER)))
			(HRO-REPORTING ((:INNER HRO-REPORTING) (:OUTER HRO-REPORTING)))
			(HRO-TYPED ((:INNER HRO-TYPED) (:OUTER HRO-TYPED)))
			(TYPE-ERROR ((:INNER TYPE-ERROR) (:OUTER TYPE-ERROR)))
			(TYPE-ERROR ((:INNER TYPE-ERROR) (:OUTER TYPE-ERROR)))""";

}
