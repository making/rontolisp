package am.ik.rontolisp;

/**
 * A program that shifts by counts no backend can build a result for -- a fixnum count
 * past the {@code int} range, a bignum count, the {@code int} range's own edge -- through
 * the plain call, a fused integer tree, a function value, {@code apply} and a literal
 * count, and prints what each answers: a left shift of a non-zero value signals a
 * {@code simple-error} reporting {@code ASH: shift count too large: <count>}, a zero
 * stays zero, a right shift answers the value's sign, and a non-integer value is still a
 * {@code type-error} naming {@code ash}. Shared by the backend suites, so every backend
 * is held to one expected text; {@code ci-spec.yaml}'s
 * {@code a-runaway-ash-count-signals-a-simple-error} pins the same rows on the native
 * binary.
 */
public final class AshCountFixture {

	private AshCountFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *ac-40* (expt 2 40))
			(defvar *ac-70* (expt 2 70))
			(defvar *ac-int-max* 2147483647)
			(defvar *ac-wide* (expt 10 30))
			(defvar *ac-one* 1)
			(defvar *ac-string* "x")
			(defun ac (thunk)
			  (handler-case (list :value (funcall thunk))
			    (simple-error (e) (list :simple-error (princ-to-string e) (typep e 'arithmetic-error)))
			    (error (e) (list :other (type-of e) (princ-to-string e)))))
			(print (ac (lambda () (ash 1 *ac-40*))))
			(print (ac (lambda () (ash 1 *ac-70*))))
			(print (ac (lambda () (ash -3 *ac-40*))))
			(print (ac (lambda () (ash *ac-wide* *ac-40*))))
			(print (ac (lambda () (ash (- *ac-wide*) *ac-70*))))
			(print (ac (lambda () (ash 1 *ac-int-max*))))
			(print (ac (lambda () (ash -1 *ac-int-max*))))
			(print (ac (lambda () (ash 0 *ac-40*))))
			(print (ac (lambda () (ash 0 *ac-70*))))
			(print (ac (lambda () (ash 0 *ac-int-max*))))
			(print (ac (lambda () (ash 5 (- *ac-40*)))))
			(print (ac (lambda () (ash -5 (- *ac-40*)))))
			(print (ac (lambda () (ash 5 (- *ac-70*)))))
			(print (ac (lambda () (ash -5 (- *ac-70*)))))
			(print (ac (lambda () (ash *ac-wide* (- *ac-70*)))))
			(print (ac (lambda () (ash (- *ac-wide*) (- *ac-70*)))))
			(print (ac (lambda () (+ *ac-one* (ash *ac-one* *ac-40*)))))
			(print (ac (lambda () (logand 255 (ash *ac-one* *ac-70*)))))
			(print (ac (lambda () (funcall #'ash *ac-one* *ac-70*))))
			(print (ac (lambda () (apply #'ash (list *ac-one* *ac-40*)))))
			(print (ac (lambda () (ash 1 1180591620717411303424))))
			(print (ac (lambda () (ash 0 1180591620717411303424))))
			(print (ac (lambda () (ash 7 -1180591620717411303424))))
			(print (ac (lambda () (ash *ac-string* *ac-70*))))
			(print (ac (lambda () (ash *ac-string* (- *ac-70*)))))
			(print (multiple-value-list (ignore-errors (ash *ac-one* *ac-40*))))""";

	/** What every backend prints. */
	public static final String EXPECTED = """
			(:SIMPLE-ERROR "ASH: shift count too large: 1099511627776" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 1180591620717411303424" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 1099511627776" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 1099511627776" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 1180591620717411303424" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 2147483647" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 2147483647" NIL)
			(:VALUE 0)
			(:VALUE 0)
			(:VALUE 0)
			(:VALUE 0)
			(:VALUE -1)
			(:VALUE 0)
			(:VALUE -1)
			(:VALUE 0)
			(:VALUE -1)
			(:SIMPLE-ERROR "ASH: shift count too large: 1099511627776" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 1180591620717411303424" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 1180591620717411303424" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 1099511627776" NIL)
			(:SIMPLE-ERROR "ASH: shift count too large: 1180591620717411303424" NIL)
			(:VALUE 0)
			(:VALUE 0)
			(:OTHER TYPE-ERROR "ASH: The value \\"x\\" is not of type INTEGER")
			(:OTHER TYPE-ERROR "ASH: The value \\"x\\" is not of type INTEGER")
			(NIL #<SIMPLE-ERROR :FORMAT-CONTROL "ASH: shift count too large: 1099511627776" :FORMAT-ARGUMENTS NIL>)""";

}
