package am.ik.rontolisp;

import java.util.Map;

/**
 * Programs that signal an UNCAUGHT {@code (error <computed type> initargs...)} and catch
 * nothing anywhere. The compile paths lower such a call to the shared
 * {@code %error-runtime} dispatch, whose per-class helper reads every initarg slot out of
 * the call's plist; the report must still name the initargs the call PASSED, and a
 * {@code :format-control} only when the call passed one -- the text the literal call, and
 * the interpreter, print, a nil one included. Shared by the backend suites, so every
 * backend is held to one expected line; the {@code ci-spec.yaml} standalone
 * {@code uncaught-computed-condition-type-report} case pins the first program on the
 * native binary.
 */
public final class ComputedConditionTypeReportFixture {

	private ComputedConditionTypeReportFixture() {
	}

	/** Each program, mapped to its first line of standard error on every backend. */
	public static final Map<String, String> PROGRAMS = Map.of("""
			(defun uc-boom (ty) (error ty :code 42))
			(uc-boom 'type-error)
			""", "Unhandled condition: Condition (TYPE-ERROR :CODE 42) was signalled.", """
			(defun uc-boom (ty) (error ty :datum 1 :expected-type 'string))
			(uc-boom 'type-error)
			""", "Unhandled condition: Condition (TYPE-ERROR :DATUM 1 :EXPECTED-TYPE STRING) was signalled.", """
			(define-condition uc-two-slots (error) ((a :initarg :a) (b :initarg :b)))
			(defun uc-boom (ty) (error ty :b 42))
			(uc-boom 'uc-two-slots)
			""", "Unhandled condition: Condition (UC-TWO-SLOTS :B 42) was signalled.", """
			(defun uc-boom (ty) (error ty :format-control "given ~a" :format-arguments (list 1)))
			(uc-boom 'simple-error)
			""", "Unhandled condition: given 1", """
			(defun uc-boom (ty) (error ty :format-control nil))
			(uc-boom 'simple-error)
			""", "Unhandled condition: NIL", """
			(define-condition uc-reported (error) ((text :initarg :text :reader uc-reported-text))
			  (:report (lambda (c s) (format s "reported: ~a" (uc-reported-text c)))))
			(defun uc-boom (ty) (error ty :text "pw"))
			(uc-boom 'uc-reported)
			""", "Unhandled condition: reported: pw");

}
