package am.ik.rontolisp;

/**
 * A program that reads specials from closures and through local
 * {@code (declare (special ...))} declarations. A reference to a special reads the
 * binding active WHEN IT RUNS, never one a closure captured: a closure over a
 * {@code defvar}'d variable or a special parameter, called after the binding's extent,
 * reads the global, and called inside another binding reads that one (trivia's
 * {@code assoc} pattern runs a handler built in one binding of its flag inside a second
 * one). A local special declaration is scoped as CLHS 3.3.4 scopes it: it makes the
 * binding it names special and the references in its body, and an inner binding of the
 * name without its own declaration is lexical -- what cl-ppcre's matcher closures capture
 * ({@code end-string}). The binding forms a declaration rides through are covered: a
 * {@code let*}, the optional and key parameters, a {@code flet} function, a
 * {@code multiple-value-bind}, the {@code do} family and a macro's expansion. The
 * expected text is SBCL's. Shared by the backend suites; {@code ci-spec.yaml}'s
 * {@code special-bindings-are-dynamic-and-declarations-scoped} runs the same program on
 * the native binary.
 */
public final class SpecialBindingScopeFixture {

	private SpecialBindingScopeFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *sds* :top)
			(defun sds-mk () (let ((*sds* :inner)) (lambda () *sds*)))
			(defun sds-param (*sds*) (lambda () *sds*))
			(defun sds-setter () (let ((*sds* :bound)) (lambda (v) (setq *sds* v))))
			(print (list (funcall (sds-mk)) (let ((*sds* :outer)) (funcall (sds-mk)))
			             (funcall (sds-param :arg)) (let ((*sds* :outer)) (funcall (sds-param :arg)))
			             (progn (funcall (sds-setter) :set) *sds*)))
			(setq *sds* :top)
			(defun sds-flag ()
			  (let ((sds-f nil))
			    (declare (special sds-f))
			    (block sds-blk
			      (handler-bind ((error (lambda (c) (declare (ignore c))
			                              (return-from sds-blk (if sds-f :inner :outer)))))
			        (let ((sds-f t))
			          (declare (special sds-f))
			          (error "boom"))))))
			(print (sds-flag))
			(defun sds-matcher (x)
			  (declare (special sds-end))
			  (let* ((sds-end (list :lexical sds-end x)))
			    (lambda () sds-end)))
			(defun sds-scanner ()
			  (let ((sds-end :special))
			    (declare (special sds-end))
			    (sds-matcher 1)))
			(print (list (funcall (sds-scanner))
			             (let ((sds-end :rebound)) (declare (special sds-end)) (funcall (sds-scanner)))))
			(defun sds-eg (sds-y)
			  (declare (special sds-y))
			  (let ((sds-y t))
			    (list sds-y (locally (declare (special sds-y)) sds-y))))
			(print (sds-eg nil))
			(defun sds-reader () (declare (special sds-n)) sds-n)
			(defun sds-binder (sds-n) (declare (special sds-n)) (sds-reader))
			(defun sds-shadow ()
			  (let ((sds-n :outer-special))
			    (declare (special sds-n))
			    (let ((sds-n :lexical))
			      (list sds-n (sds-reader) (symbol-value 'sds-n)))))
			(print (list (sds-binder 42) (sds-shadow)))
			(defun sds-peek () (declare (special sds-o sds-k)) (list sds-o sds-k))
			(defun sds-opt (a &optional (sds-o (list a)) &key (sds-k :kd))
			  (declare (special sds-o sds-k))
			  (sds-peek))
			(defun sds-peek-a () (declare (special sds-a)) sds-a)
			(defun sds-star () (let* ((sds-a 1) (b (sds-peek-a))) (declare (special sds-a)) (list b (sds-peek-a))))
			(print (list (sds-opt 1) (sds-opt 1 2 :sds-k 3)
			             (let ((sds-a :outer)) (declare (special sds-a)) (sds-star))
			             (flet ((sds-fl (sds-a) (declare (special sds-a)) (sds-peek-a))) (sds-fl :flet))
			             (multiple-value-bind (sds-a b) (values :mvb 2) (declare (special sds-a)) (list (sds-peek-a) b))))
			(print (list (let (acc) (dolist (sds-a '(1 2)) (declare (special sds-a)) (push (sds-peek-a) acc)) acc)
			             (let (acc) (dotimes (sds-a 2) (declare (special sds-a)) (push (sds-peek-a) acc)) acc)
			             (do ((sds-a 0 (1+ sds-a)) (acc nil (cons (sds-peek-a) acc)))
			                 ((= sds-a 2) acc)
			               (declare (special sds-a)))))
			(defmacro sds-with-a (v &body body) `(let ((sds-a ,v)) (declare (special sds-a)) ,@body))
			(print (list (sds-with-a :macro (sds-peek-a))
			             (let ((sds-a :special)) (declare (special sds-a))
			               (let ((sds-a :lex)) (setq sds-a :lex2) (list sds-a (sds-peek-a))))))
			(setq sds-g :global)
			(defun sds-mk2 () (let ((sds-g :bound)) (declare (special sds-g)) (lambda () sds-g)))
			(print (list (funcall (sds-mk2)) (let ((sds-g :other)) (declare (special sds-g)) (funcall (sds-mk2)))
			             (let ((sds-p :lex)) (progv '(sds-p) '(:dyn) sds-p))))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(:TOP :OUTER :TOP :OUTER :SET)", ":INNER",
			"((:LEXICAL :SPECIAL 1) (:LEXICAL :SPECIAL 1))", "(T NIL)", "(42 (:LEXICAL :OUTER-SPECIAL :OUTER-SPECIAL))",
			"(((1) :KD) (2 3) (1 1) :FLET (:MVB 2))", "((2 1) (1 0) (1 0))", "(:MACRO (:LEX2 :SPECIAL))",
			"(:GLOBAL :OTHER :LEX)");

}
