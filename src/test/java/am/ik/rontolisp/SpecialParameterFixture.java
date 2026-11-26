package am.ik.rontolisp;

/**
 * A program whose functions bind a special through a PARAMETER of every lambda-list
 * shape: CL binds a parameter whose name is proclaimed special dynamically (CLHS
 * 3.1.2.1.1.2), so a function the body calls reads the argument, a default form sees the
 * binding of an earlier parameter, and the binding is undone on every exit. The
 * interpreter binds such a parameter itself; the compilers lower it into the special
 * {@code let} ({@code LambdaLists.toNative}), so a self tail call or a tail-group sibling
 * call inside such a function is a real call. Until 2026-10-03 the JVM and both WASM
 * backends bound the parameter lexically: {@code (spp-req 1)} answered {@code :TOP}, the
 * desugaring dropped the binding of a special supplied-p variable the body only tests (or
 * never names), and a {@code setq} of the parameter wrote the global. The stream case was
 * the interpreter's own: a parameter was no binding its special collector saw, so
 * {@code *standard-output*} named as one stayed lexical there too. The expected text is
 * SBCL's: a closure called after the parameter's extent reads the global, as every
 * special reference does ({@code SpecialBindingScopeFixture}). Shared by the backend
 * suites, so every backend is held to one expected text; {@code ci-spec.yaml}'s
 * {@code special-parameters-bind-dynamically} runs the same program on the native binary.
 */
public final class SpecialParameterFixture {

	private SpecialParameterFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *spp* :top)
			(defvar *spp-y* :top-y)
			(defun spp-show () *spp*)
			(defun spp-both () (list *spp* *spp-y*))
			(defun spp-req (*spp*) (spp-show))
			(defun spp-two (a *spp* b *spp-y*) (list a (spp-both) b))
			(defun spp-opt (&optional (*spp* :default)) (spp-show))
			(defun spp-key (&key ((:x *spp*) :kdefault)) (spp-show))
			(defun spp-rest (&rest *spp*) (spp-show))
			(defun spp-aux (n &aux (*spp* (* n 10))) (spp-show))
			(defun spp-dflt (*spp* &optional (seen (spp-show)) &key (also (spp-show))) (list seen also))
			(defun spp-sp (&optional (v 1 *spp-y*)) (list v (spp-both)))
			(print (list (spp-req 1) (spp-two :a 2 :b 3) (spp-opt) (spp-opt 4) (spp-key) (spp-key :x 5)
			             (spp-rest 6 7) (spp-aux 8) (spp-dflt 9) (spp-sp) (spp-sp 0) (spp-both)))
			(print (list ((lambda (*spp*) (spp-show)) 10)
			             (funcall (lambda (*spp*) (spp-show)) 11)
			             (flet ((fl (*spp*) (spp-show))) (fl 12))
			             (labels ((lb (*spp* n) (if (= n 0) (spp-show) (lb (cons n *spp*) (- n 1))))) (lb nil 3))
			             (spp-show)))
			(defun spp-self (*spp* n) (if (= n 0) (spp-show) (spp-self (list *spp*) (- n 1))))
			(defun spp-ev (*spp* n) (if (= n 0) (list :ev (spp-show)) (spp-od n (- n 1))))
			(defun spp-od (k n) (if (= n 0) (list :od k (spp-show)) (spp-ev n (- n 1))))
			(print (list (spp-self :s 3) (spp-ev :e 4) (spp-ev :e 5) (spp-show)))
			(defun spp-err (*spp*) (error "boom"))
			(defun spp-throw (*spp*) (throw 'spp-tag (spp-show)))
			(defun spp-ret (*spp*) (dolist (i '(1 2)) (return-from spp-ret (list i (spp-show)))))
			(print (list (handler-case (spp-err :err) (error () (spp-show)))
			             (catch 'spp-tag (spp-throw :thrown))
			             (spp-ret :ret)
			             (spp-show)))
			(defun spp-set (*spp*) (setq *spp* :set) (spp-show))
			(defun spp-close (*spp*) (lambda () *spp*))
			(print (list (spp-set :a) (spp-show) (funcall (spp-close :captured)) (spp-show)))
			(defun spp-out (*standard-output*) (princ "redirected") :done)
			(print (let ((s (make-string-output-stream)))
			         (list (spp-out s) (get-output-stream-string s))))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n",
			"(1 (:A (2 3) :B) :DEFAULT 4 :KDEFAULT 5 (6 7) 80 (9 9) (1 (:TOP NIL)) (0 (:TOP T)) (:TOP :TOP-Y))",
			"(10 11 12 (1 2 3) :TOP)", "((((:S))) (:EV 1) (:OD 1 2) :TOP)", "(:TOP :THROWN (1 :RET) :TOP)",
			"(:SET :TOP :TOP :TOP)", "(:DONE \"redirected\")");

}
