package am.ik.rontolisp;

/**
 * Programs that take {@code boundp} and {@code fboundp} as function values -- through
 * {@code mapcar}, {@code funcall} and {@code apply} -- over a global, a special declared
 * without a value (bound by a {@code let} around the call), an undefined name, the
 * self-bound constants, and functions of every kind -- standard functions a library
 * defines (alphanumericp, equalp) included, and a reference-gated one (aref) and a
 * car/cdr composition (cadr) the program names only in quoted data, which {@code funcall}
 * and {@code symbol-function} reach too. The CL functions the expander rewrites in call
 * position are values too: {@code complement}, {@code make-condition} and
 * {@code make-sequence} over a computed type, {@code documentation}, {@code proclaim},
 * {@code change-class} with initargs and the four {@code slot-} functions, named in
 * quoted data and called through it; an unknown condition type or sequence type signals.
 * The expected texts are SBCL's, whose {@code fboundp} answers the function object, so a
 * generalized boolean is normalized with {@code (and ... t)}. Shared by the backend
 * suites; {@code ci-spec.yaml}'s {@code boundp-and-fboundp-as-function-values} runs the
 * same program on the native binary.
 */
public final class BoundpFunctionValueFixture {

	private BoundpFunctionValueFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *fv* 1)
			(defvar *fv-nv*)
			(defun fv-fn () 1)
			(print (mapcar #'boundp '(*fv* *fv-none* *fv-nv*)))
			(print (let ((*fv-nv* 1)) (mapcar #'boundp '(*fv-nv* *fv*))))
			(print (list (funcall #'boundp '*fv*) (apply #'boundp '(*fv-nv*))))
			(print (mapcar #'boundp '(t nil :fv-kw)))
			(print (mapcar #'fboundp '(fv-none)))
			(print (and (funcall #'fboundp 'car) t))
			(print (mapcar (lambda (s) (and (funcall #'fboundp s) t)) '(fv-fn car fv-none alphanumericp equalp string< aref cadr)))
			(print (and (apply #'fboundp '(fv-fn)) t))
			(print (let ((fs '(aref cadr))) (list (funcall (first fs) #(7 8) 1) (funcall (symbol-function (second fs)) '(1 2)))))
			(defclass fvb-pt () ((x :initarg :x) (y)))
			(defclass fvb-pt3 (fvb-pt) ((z :initform 9) (w :initarg :w)))
			(define-condition fvb-cond (error) ((a :initarg :a :reader fvb-cond-a) (b :initform 4 :initarg :b :reader fvb-cond-b)))
			(print (mapcar (lambda (s) (and (fboundp s) t)) '(complement make-condition make-sequence documentation proclaim change-class slot-value slot-boundp slot-exists-p slot-makunbound)))
			(print (let ((c (funcall #'make-condition 'fvb-cond :a 5))) (list (fvb-cond-a c) (fvb-cond-b c) (typep c 'error))))
			(print (princ-to-string (apply #'make-condition 'simple-error '(:format-control "x ~a" :format-arguments (1)))))
			(print (let ((fs '(complement make-sequence documentation proclaim))) (list (funcall (funcall (first fs) #'evenp) 3) (funcall (second fs) 'list 2 :initial-element 7) (funcall (second fs) '(vector t) 2 :initial-element #\\a) (funcall (third fs) 'fv-fn 'function) (null (funcall (fourth fs) '(optimize speed))))))
			(print (let ((p (make-instance 'fvb-pt :x 1)) (fs '(slot-value slot-boundp slot-exists-p slot-makunbound change-class))) (list (funcall (first fs) p 'x) (funcall (second fs) p 'y) (funcall (third fs) p 'q) (eq p (funcall (fourth fs) p 'x)) (slot-boundp p 'x) (eq p (funcall (fifth fs) p 'fvb-pt3 :w 3)) (slot-value p 'z) (slot-value p 'w))))
			(print (list (handler-case (funcall #'make-condition 'fv-none) (error () :err)) (handler-case (funcall #'make-sequence 'hash-table 1) (error () :err))))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(T NIL NIL)", "(T T)", "(T NIL)", "(T T T)", "(NIL)", "T",
			"(T T NIL T T T T T)", "T", "(8 2)", "(T T T T T T T T T T)", "(5 4 T)", "\"x 1\"",
			"(T (7 7) #(#\\a #\\a) NIL T)", "(1 NIL NIL T NIL T 9 3)", "(:ERR :ERR)");

}
