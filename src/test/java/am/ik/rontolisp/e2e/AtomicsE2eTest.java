package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * atomics (vendored unmodified under {@code src/test/resources/atomics}, zlib, with its
 * dependencies documentation-utils and trivial-indent) on all four backends via
 * {@link AsdfLibraryE2eSupport}. Its rontolisp arm is the derived form
 * {@code eval/AtomicsForms} ({@code .kb/asdf.md}, tier 4), whose markers this pins
 * against the release. Every line but the two threaded ones, which SBCL's program spells
 * differently, is SBCL 2.2.9's answer to the same program. Eight threads counting through
 * {@code cas} lose nothing on the backends that have threads, on a {@code car} and under
 * the lock of any other place.
 */
class AtomicsE2eTest extends AsdfLibraryE2eSupport {

	private static final String RESOURCES = Path.of("src", "test", "resources").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :atomics)
			(defstruct box value)
			(defclass holder () ((slot :initform 1)))
			(defvar *special* 1)
			(defmacro my-place (x) `(car (cdr ,x)))
			(defvar *calls* 0)
			(defun cell-of (c) (incf *calls*) c)
			(let ((c (cons 1 2)))
			  (print (list (atomics:cas (car c) 1 10) (atomics:cas (car c) 1 11) c))
			  (print (list (atomics:cas (cdr c) 2 20) (atomics:cas (cdr c) 2 21) c))
			  (print (list (atomics:cas (first c) 10 12) (atomics:cas (rest c) 20 22) c)))
			(let ((c (list 1 2)))
			  (print (list (atomics:cas (my-place c) 2 3) c)))
			(let ((c (cons 1 2)))
			  (print (list (atomics:cas (car (cell-of c)) 1 2) *calls* c)))
			(print (handler-case (atomics:cas (car nil) nil 1) (type-error () :type-error)))
			(let ((v (vector 1 2 3)))
			  (print (list (atomics:cas (svref v 1) 2 20) (atomics:cas (svref v 1) 2 21) v)))
			(let ((b (make-box :value 1)))
			  (print (list (atomics:cas (box-value b) 1 2) (atomics:cas (box-value b) 1 3) (box-value b))))
			(let ((h (make-instance 'holder)))
			  (print (list (atomics:cas (slot-value h 'slot) 1 2) (atomics:cas (slot-value h 'slot) 1 3)
			               (slot-value h 'slot))))
			(print (list (atomics:cas *special* 1 2) (atomics:cas *special* 1 3) *special*))
			(print (list (atomics:cas (symbol-value '*special*) 2 5) *special*))
			(let ((c (list 5)))
			  (print (list (atomics:atomic-incf (car c)) (atomics:atomic-incf (car c) 10)
			               (atomics:atomic-decf (car c)) (atomics:atomic-decf (car c) 2) c)))
			(let ((c (list nil)))
			  (print (list (atomics:atomic-push 1 (car c)) (atomics:atomic-push 2 (car c))
			               (atomics:atomic-pop (car c)) c)))
			(let ((c (list 1)))
			  (atomics:atomic-update (car c) #'1+)
			  (print c))
			(print (list (find :atomics-cas-car *features*) (find :atomics-cas-svref *features*)
			             (find :atomics-cas-memref *features*)))
			(let* ((big (expt 10 30)) (c (list big)))
			  (print (list (atomics:cas (car c) big 1) c)))
			(let* ((s "x") (c (list s)))
			  (print (list (atomics:cas (car c) "y" 1) (atomics:cas (car c) s 1) c)))
			(defun count-up (increment)
			  #+thread-support
			  (mapc #'rontolisp:join-thread
			        (loop repeat 8
			              collect (rontolisp:make-thread
			                       (lambda () (loop repeat 5000 do (funcall increment))))))
			  #-thread-support
			  (loop repeat 40000 do (funcall increment)))
			(let ((c (list 0)))
			  (count-up (lambda () (atomics:atomic-incf (car c))))
			  (print c))
			(let ((v (vector 0)))
			  (count-up (lambda () (atomics:atomic-incf (svref v 0))))
			  (print v))
			""";

	private static final List<String> EXPECTED = List.of("(T NIL (10 . 2))", "(T NIL (10 . 20))", "(T T (12 . 22))",
			"(T (1 3))", "(T 1 (2 . 2))", ":TYPE-ERROR", "(T NIL #(1 20 3))", "(T NIL 2)", "(T NIL 2)", "(T NIL 2)",
			"(T 5)", "(6 16 15 13 (13))", "((1) (2 1) 2 ((1)))", "(2)", "(:ATOMICS-CAS-CAR :ATOMICS-CAS-SVREF NIL)",
			"(T (1))", "(NIL T (1))", "(40000)", "#(40000)");

	@Override
	protected String systemDir() {
		return RESOURCES + "/atomics";
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(RESOURCES + "/documentation-utils", RESOURCES + "/trivial-indent");
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "AtomicsE2e";
	}

}
