package am.ik.rontolisp;

/**
 * A program that times the same package walk before and after it assigns 4,000 globals
 * through {@code set}, which also gives it the eval runtime and so the global environment
 * the compiled backends mirror every assignment into. It prints the two timings (each the
 * best of three probes, in internal time units) and then the last global's value,
 * {@code 3999}. Shared by the backend suites, which bound the second timing by the first.
 */
public final class EvalMirrorFixture {

	private EvalMirrorFixture() {
	}

	/** The probe program. */
	public static final String MANY_GLOBALS_PROBE = """
			(defun gm-probe ()
			  (let ((t0 (get-internal-real-time)))
			    (dotimes (k 3) (apropos-list "CAR" :cl))
			    (- (get-internal-real-time) t0)))
			(defun gm-best () (min (gm-probe) (gm-probe) (gm-probe)))
			(gm-probe)
			(princ (gm-best)) (terpri)
			(dotimes (i 4000) (set (intern (format nil "GM-~D" i)) i))
			(princ (gm-best)) (terpri)
			(princ (symbol-value (intern "GM-3999"))) (terpri)
			""";

}
