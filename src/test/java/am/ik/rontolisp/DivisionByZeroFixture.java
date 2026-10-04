package am.ik.rontolisp;

/**
 * A program that divides by an exact zero through every integer and ratio division path
 * -- {@code /} (unary, n-ary, ratio, wide-integer and complex dividends), the
 * two-argument rounding family (a float dividend included: an exact zero divisor signals,
 * a zero float divisor stays IEEE and fails the non-finite rounding),
 * {@code mod}/{@code rem} at each integer tier and over a ratio, a negative power of
 * zero, the fused fixnum trees and the function values -- and prints the
 * {@code division-by-zero} each signals: its report and that it is an
 * {@code arithmetic-error}. The wasm-GC backends trapped on every one of them, past any
 * handler, and {@code mod}/{@code rem} reported the host's {@code / by zero} or
 * {@code BigInteger divide by zero} on the interpreter and the JVM. Shared by the backend
 * suites, so every backend is held to one expected text; {@code ci-spec.yaml}'s
 * {@code division-by-zero-is-a-catchable-condition} pins the same rows on the native
 * binary.
 */
public final class DivisionByZeroFixture {

	private DivisionByZeroFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *dz-zero* 0)
			(defvar *dz-long* 10000000000)
			(defvar *dz-wide* 100000000000000000000000)
			(defvar *dz-fzero* 0.0)
			(defun dz (thunk)
			  (handler-case (list :value (funcall thunk))
			    (division-by-zero (e) (list :division-by-zero (princ-to-string e) (typep e 'arithmetic-error)))
			    (error (e) (list :other (type-of e) (princ-to-string e)))))
			(print (dz (lambda () (/ 1 *dz-zero*))))
			(print (dz (lambda () (/ *dz-zero*))))
			(print (dz (lambda () (/ 7 2 *dz-zero*))))
			(print (dz (lambda () (/ 1/2 *dz-zero*))))
			(print (dz (lambda () (/ *dz-long* *dz-zero*))))
			(print (dz (lambda () (/ *dz-wide* *dz-zero*))))
			(print (dz (lambda () (/ #c(1 2) *dz-zero*))))
			(print (dz (lambda () (floor 7 *dz-zero*))))
			(print (dz (lambda () (ceiling -7 *dz-zero*))))
			(print (dz (lambda () (truncate *dz-long* *dz-zero*))))
			(print (dz (lambda () (round *dz-wide* *dz-zero*))))
			(print (dz (lambda () (floor 1/2 *dz-zero*))))
			(print (dz (lambda () (ffloor 7 *dz-zero*))))
			(print (dz (lambda () (floor 7.5 *dz-zero*))))
			(print (dz (lambda () (ceiling -7.5 *dz-zero*))))
			(print (dz (lambda () (truncate 7.5 *dz-zero*))))
			(print (dz (lambda () (round 7.5 *dz-zero*))))
			(print (dz (lambda () (ffloor 7.5 *dz-zero*))))
			(print (dz (lambda () (floor 0.0 *dz-zero*))))
			(print (dz (lambda () (multiple-value-list (floor 7.5 *dz-zero*)))))
			(print (dz (lambda () (funcall #'floor 7.5 *dz-zero*))))
			(print (dz (lambda () (let ((a 7.5d0) (b *dz-zero*)) (declare (double-float a)) (floor a b)))))
			(print (dz (lambda () (floor 7.5 *dz-fzero*))))
			(print (dz (lambda () (floor 7 *dz-fzero*))))
			(print (dz (lambda () (floor (/ 1.0 *dz-fzero*) *dz-zero*))))
			(print (dz (lambda () (mod 7 *dz-zero*))))
			(print (dz (lambda () (rem *dz-long* *dz-zero*))))
			(print (dz (lambda () (mod *dz-wide* *dz-zero*))))
			(print (dz (lambda () (rem 1/2 *dz-zero*))))
			(print (dz (lambda () (expt *dz-zero* -1))))
			(print (dz (lambda () (let ((a 7) (b *dz-zero*)) (declare (fixnum a b)) (mod a b)))))
			(print (dz (lambda () (let ((s 0)) (dotimes (i 3) (setq s (+ s (rem (* i 3) (- i i))))) s))))
			(print (dz (lambda () (funcall #'/ 1 *dz-zero*))))
			(print (dz (lambda () (reduce #'mod (list 7 *dz-zero*)))))
			(print (dz (lambda () (multiple-value-list (floor 5 *dz-zero*)))))
			(print (multiple-value-list (ignore-errors (rem 5 *dz-zero*))))
			(print (handler-case (/ 1 *dz-zero*) (arithmetic-error (e) (type-of e))))""";

	/** What every backend prints. */
	public static final String EXPECTED = """
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:OTHER SIMPLE-ERROR "rounding a non-finite float to an integer is undefined")
			(:OTHER SIMPLE-ERROR "rounding a non-finite float to an integer is undefined")
			(:OTHER SIMPLE-ERROR "rounding a non-finite float to an integer is undefined")
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(:DIVISION-BY-ZERO "Division by zero" T)
			(NIL #<DIVISION-BY-ZERO :FORMAT-CONTROL "Division by zero" :FORMAT-ARGUMENTS NIL>)
			DIVISION-BY-ZERO""";

}
