package am.ik.rontolisp;

/**
 * Programs that probe {@code boundp} of a special declared without a value: inside a
 * binding of it (a {@code let}, a parameter, {@code progv}), after one, after a callee's
 * {@code setq}, a {@code set} or an {@code eval}'d {@code setq} inside one, through a
 * computed name, from a closure built inside the extent, and past every exit of the
 * binding. The answer is the variable's: T for the extent of any binding, NIL again once
 * it ends, T for good after a global assignment. On the compile paths {@code boundp} used
 * to answer from the eval runtime's mirror, which no binding writes and a store inside
 * one writes for good: NIL inside the binding, T after it once a callee assigned it.
 * {@link #SOURCE} uses neither {@code set}, {@code eval} nor {@code progv};
 * {@link #STORE_SOURCE} does. The expected texts are SBCL's. Shared by the backend
 * suites; {@code ci-spec.yaml}'s {@code boundp-of-a-special-inside-its-binding} runs both
 * on the native binary.
 */
public final class BoundpInBindingFixture {

	private BoundpInBindingFixture() {
	}

	/** The program without {@code set}, {@code eval} or {@code progv}. */
	public static final String SOURCE = """
			(defvar *bib*)
			(declaim (special *bib-d*))
			(defun bib-set (v) (setq *bib* v))
			(defun bib-probe () (boundp '*bib*))
			(defun bib-name () (intern "*BIB*"))
			(defun bib-param (*bib*) (list (boundp '*bib*) (bib-probe)))
			(print (list (boundp '*bib*) (let ((*bib* 1)) (list (boundp '*bib*) (bib-probe))) (boundp '*bib*) (bib-probe)))
			(print (progn (let ((*bib* 1)) (bib-set 2)) (list (boundp '*bib*) (bib-probe))))
			(print (list (bib-param 5) (boundp '*bib*)))
			(print (list (let ((*bib* 1)) (boundp (bib-name))) (boundp (bib-name))))
			(print (list (boundp '*bib-d*) (let ((*bib-d* 1)) (boundp '*bib-d*)) (boundp '*bib-d*)))
			(print (let ((f (let ((*bib* 1)) (lambda () (boundp '*bib*))))) (list (funcall f) (let ((*bib* 2)) (funcall f)))))
			(print (list (catch 'bib-tag (let ((*bib* 1)) (throw 'bib-tag (bib-probe)))) (bib-probe)))
			(print (list (handler-case (let ((*bib* 1)) (bib-set 3) (error "bib")) (error () (bib-probe))) (boundp '*bib*)))
			(print (list (let ((*bib* 1)) (let ((*bib* 2)) (bib-set 4)) (list *bib* (bib-probe))) (bib-probe)))
			(bib-set 9)
			(print (list (boundp '*bib*) (let ((*bib* 1)) (bib-probe)) (bib-probe) *bib* (boundp (bib-name))))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(NIL (T T) NIL NIL)", "(NIL NIL)", "((T T) NIL)",
			"(T NIL)", "(NIL T NIL)", "(NIL T)", "(T NIL)", "(NIL NIL)", "((1 T) NIL)", "(T T T 9 T)");

	/**
	 * {@link #SOURCE} with every probe a literal one: a program whose every
	 * {@code boundp} names such a special compiles without the eval runtime on the
	 * compile paths, so this pins that the variable alone gives every answer.
	 */
	public static final String LITERAL_SOURCE = """
			(defvar *bpl*)
			(declaim (special *bpl-d*))
			(defun bpl-set (v) (setq *bpl* v))
			(defun bpl-probe () (boundp '*bpl*))
			(defun bpl-param (*bpl*) (list (boundp '*bpl*) (bpl-probe)))
			(print (list (boundp '*bpl*) (let ((*bpl* 1)) (list (boundp '*bpl*) (bpl-probe))) (boundp '*bpl*) (bpl-probe)))
			(print (progn (let ((*bpl* 1)) (bpl-set 2)) (list (boundp '*bpl*) (bpl-probe))))
			(print (list (bpl-param 5) (boundp '*bpl*)))
			(print (list (boundp '*bpl-d*) (let ((*bpl-d* 1)) (boundp '*bpl-d*)) (boundp '*bpl-d*)))
			(print (let ((f (let ((*bpl* 1)) (lambda () (boundp '*bpl*))))) (list (funcall f) (let ((*bpl* 2)) (funcall f)))))
			(print (list (catch 'bpl-tag (let ((*bpl* 1)) (throw 'bpl-tag (bpl-probe)))) (bpl-probe)))
			(print (list (handler-case (let ((*bpl* 1)) (bpl-set 3) (error "bpl")) (error () (bpl-probe))) (boundp '*bpl*)))
			(print (list (let ((*bpl* 1)) (let ((*bpl* 2)) (bpl-set 4)) (list *bpl* (bpl-probe))) (bpl-probe)))
			(bpl-set 9)
			(print (list (boundp '*bpl*) (let ((*bpl* 1)) (bpl-probe)) (bpl-probe) *bpl*))
			""";

	/** What {@link #LITERAL_SOURCE} prints, one value per line. */
	public static final String LITERAL_EXPECTED = String.join("\n", "(NIL (T T) NIL NIL)", "(NIL NIL)", "((T T) NIL)",
			"(NIL T NIL)", "(NIL T)", "(T NIL)", "(NIL NIL)", "((1 T) NIL)", "(T T T 9)");

	/**
	 * The program that also stores through {@code set} and {@code eval} and binds through
	 * {@code progv}.
	 */
	public static final String STORE_SOURCE = """
			(defvar *bis*)
			(defun bis-probe () (boundp '*bis*))
			(defun bis-name () (intern "*BIS*"))
			(print (list (progv '(*bis*) '(3) (list (boundp '*bis*) (bis-probe) (symbol-value '*bis*))) (bis-probe)))
			(print (list (let ((*bis* 1)) (set '*bis* 4) (list *bis* (bis-probe))) (bis-probe)))
			(print (list (let ((*bis* 1)) (eval '(setq *bis* 7)) (list *bis* (bis-probe))) (boundp (bis-name))))
			(print (list (progv (list (bis-name)) '(5) (list (boundp (bis-name)) (bis-probe))) (bis-probe)))
			(set (bis-name) 8)
			(print (list (boundp '*bis*) (bis-probe) *bis* (let ((*bis* 2)) (bis-probe))))
			""";

	/** What {@link #STORE_SOURCE} prints, one value per line. */
	public static final String STORE_EXPECTED = String.join("\n", "((T T 3) NIL)", "((4 T) NIL)", "((7 T) NIL)",
			"((T T) NIL)", "(T T 8 T)");

	/**
	 * A read of such a special while it has no value signals the {@code unbound-variable}
	 * naming it, never answering the marker its variable holds -- a direct read,
	 * {@code symbol-value} of a literal and of a computed name, {@code eval}, a binding's
	 * init form, a {@code progv} short of values. The compile paths used to read nil for
	 * every one of them, and every backend bound the {@code progv}'s symbol to nil.
	 */
	public static final String UNBOUND_READ_SOURCE = """
			(defvar *bil*)
			(defun bil-probe () (boundp '*bil*))
			(defun bil-read (thunk)
			  (handler-case (funcall thunk) (unbound-variable (c) (list :unbound (cell-error-name c)))))
			(print (list (let ((*bil* 1)) (bil-probe)) (bil-probe)))
			(print (list (bil-read (lambda () *bil*)) (bil-read (lambda () (symbol-value '*bil*)))
			             (bil-read (lambda () (symbol-value (intern "*BIL*")))) (bil-read (lambda () (eval '*bil*)))))
			(print (bil-read (lambda () (let ((*bil* *bil*)) (list *bil* (bil-probe))))))
			(print (list (progv '(*bil*) '() (list (bil-read (lambda () *bil*)) (bil-probe))) (bil-probe)))
			""";

	/** What {@link #UNBOUND_READ_SOURCE} prints, one value per line. */
	public static final String UNBOUND_READ_EXPECTED = String.join("\n", "(T NIL)",
			"((:UNBOUND *BIL*) (:UNBOUND *BIL*) (:UNBOUND *BIL*) (:UNBOUND *BIL*))", "(:UNBOUND *BIL*)",
			"(((:UNBOUND *BIL*) NIL) NIL)");

}
