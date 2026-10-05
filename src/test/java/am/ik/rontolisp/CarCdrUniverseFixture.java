package am.ik.rontolisp;

/**
 * The car/cdr compositions ({@code caar} .. {@code cddddr}, 28 names) are external
 * symbols of {@code cl}, so every enumeration of its universe lists them: {@code
 * do-external-symbols} over {@code cl}, {@code do-symbols} in a package that uses it,
 * {@code with-package-iterator}, {@code do-all-symbols}, {@code find-all-symbols} and
 * {@code apropos-list}. Shared by the backend suites, so every backend is held to one
 * expected text (SBCL's output); the {@code ci-spec.yaml} case
 * {@code package-walks-list-the-car-cdr-compositions} pins the same program on the native
 * binary.
 */
public final class CarCdrUniverseFixture {

	private CarCdrUniverseFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defpackage :ccu-a (:use :cl))
			(defpackage :ccu-b (:use :ccu-a))
			(defun ccu-composition-p (name)
			  (and (<= 4 (length name) 6)
			       (char= (char name 0) #\\C)
			       (char= (char name (1- (length name))) #\\R)
			       (every (lambda (c) (find c "AD")) (subseq name 1 (1- (length name))))))
			(defun ccu-names (names)
			  (sort (remove-duplicates (remove-if-not #'ccu-composition-p names) :test #'string=) #'string<))
			(let ((names '()))
			  (do-external-symbols (s :cl) (push (symbol-name s) names))
			  (print (length (ccu-names names)))
			  (print (list (first (ccu-names names)) (car (last (ccu-names names))))))
			(let ((names '()))
			  (do-symbols (s :ccu-a) (push (symbol-name s) names))
			  (print (length (ccu-names names))))
			(let ((names '()))
			  (do-external-symbols (s :ccu-a) (push (symbol-name s) names))
			  (print (length (ccu-names names))))
			(let ((names '()))
			  (do-symbols (s :ccu-b) (push (symbol-name s) names))
			  (print (length (ccu-names names))))
			(let ((names '()))
			  (do-symbols (s :cl-user) (push (symbol-name s) names))
			  (print (length (ccu-names names))))
			(let ((names '()))
			  (with-package-iterator (next '(:ccu-a :cl) :internal :external :inherited)
			    (loop (multiple-value-bind (more s) (next)(unless more (return))
			            (push (symbol-name s) names))))
			  (print (length (ccu-names names))))
			(let ((names '()))
			  (do-all-symbols (s) (push (symbol-name s) names))
			  (print (length (ccu-names names))))
			(print (find-all-symbols "CADDR"))
			(print (find-all-symbols 'cdddr))
			(print (apropos-list "CADDDR"))
			(print (apropos-list "CADDDR" :cl))
			(print (apropos-list "CDAR" :ccu-a))
			(print (eq (car (find-all-symbols "CADDR")) 'caddr))
			(print (multiple-value-list (find-symbol "CAADR" :ccu-a)))
			""";

	/** What {@link #PROGRAM} prints on every backend. */
	public static final String EXPECTED = """
			28
			("CAAAAR" "CDDR")
			28
			0
			0
			28
			28
			28
			(CADDR)
			(CDDDR)
			(CADDDR)
			(CADDDR)
			(CDAR)
			T
			(CAADR :INHERITED)""";

}
