package am.ik.rontolisp;

/**
 * A program whose {@code progv} sites name more symbols than they give values, catching
 * the {@code unbound-variable} a read of each extra symbol signals and reading its
 * {@code cell-error-name}: a special with a value and one without, read through a
 * function, by {@code symbol-value}, by {@code eval} and in the frame of a {@code let} of
 * the same special; {@code boundp} of each, literal and through {@code #'boundp}; a
 * {@code setq}, a nested {@code progv} with a value, a {@code let} and a {@code throw}
 * inside the extent; and a name no declaration makes special. Each extra symbol is
 * unbound for the extent and its previous binding back after it. Every backend used to
 * bind each extra symbol to nil. The expected text is SBCL's. Shared by the backend
 * suites, so every backend is held to one expected text; {@code ci-spec.yaml}'s
 * {@code a-progv-short-of-values-leaves-the-extra-symbols-unbound} runs the program on
 * the native binary.
 */
public final class ProgvShortOfValuesFixture {

	private ProgvShortOfValuesFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *psv-a* 1)
			(defvar *psv-b*)
			(defun psv-try (thunk)
			  (handler-case (funcall thunk)
			    (unbound-variable (c) (list :unbound (cell-error-name c)))))
			(defun psv-a () *psv-a*)
			(defun psv-b () *psv-b*)
			(print (progv '(*psv-a* *psv-b*) '()
			         (list (psv-try #'psv-a) (psv-try #'psv-b) (boundp '*psv-a*) (boundp '*psv-b*)
			               (psv-try (lambda () (symbol-value '*psv-a*))))))
			(print (list (psv-a) (psv-try #'psv-b) (boundp '*psv-a*) (boundp '*psv-b*)))
			(print (progv '(*psv-a* *psv-b*) '(10)
			         (list (psv-a) (psv-try #'psv-b) (mapcar #'boundp '(*psv-a* *psv-b*)))))
			(print (progv '(*psv-a*) '()
			         (setq *psv-a* 7)
			         (list (psv-a) (boundp '*psv-a*))))
			(print (list (psv-a) (boundp '*psv-a*)))
			(print (progv '(*psv-a*) '()
			         (list (progv '(*psv-a*) '(8) (psv-a))
			               (let ((*psv-a* 9)) (psv-a))
			               (catch 'psv (progv '(*psv-a*) '(5) (throw 'psv (psv-a))))
			               (psv-try #'psv-a))))
			(print (let ((*psv-a* 3))
			         (list (progv '(*psv-a*) '()
			                 (handler-case *psv-a*
			                   (unbound-variable (c) (list :unbound (cell-error-name c)))))
			               *psv-a*)))
			(print (progv '(psv-free) '()
			         (list (boundp 'psv-free) (psv-try (lambda () (symbol-value 'psv-free))))))
			(print (progv '(psv-free) '(4) (list (boundp 'psv-free) (symbol-value 'psv-free))))
			(print (list (boundp 'psv-free) (psv-try (lambda () (eval '*psv-a*)))))
			(print (progv (list '*psv-a*) nil (psv-try (lambda () (eval '*psv-a*)))))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n",
			"((:UNBOUND *PSV-A*) (:UNBOUND *PSV-B*) NIL NIL (:UNBOUND *PSV-A*))", "(1 (:UNBOUND *PSV-B*) T NIL)",
			"(10 (:UNBOUND *PSV-B*) (T NIL))", "(7 T)", "(1 T)", "(8 9 5 (:UNBOUND *PSV-A*))", "((:UNBOUND *PSV-A*) 3)",
			"(NIL (:UNBOUND PSV-FREE))", "(T 4)", "(NIL 1)", "(:UNBOUND *PSV-A*)");

}
