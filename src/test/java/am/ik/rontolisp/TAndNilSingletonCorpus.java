package am.ik.rontolisp;

/**
 * The symbol-by-name operators over the spellings {@code "T"} and {@code "NIL"}, which
 * name the {@code t} / {@code nil} singletons rather than symbols of those names. One
 * corpus because the interpreter and both compiled harnesses must answer the same line,
 * and every value is SBCL's.
 */
public final class TAndNilSingletonCorpus {

	private TAndNilSingletonCorpus() {
	}

	/** One form: a list of the probes' answers. */
	public static final String FORM = """
			(list (eq (intern "T") t) (eq (intern "NIL") nil) (null (intern "NIL"))
			      (multiple-value-list (intern "NIL"))
			      (eq (find-symbol "T") t) (multiple-value-list (find-symbol "T"))
			      (multiple-value-list (find-symbol "NIL"))
			      (eq (find-symbol "T" "CL") t) (eq (find-symbol "NIL" "CL") nil)
			      (multiple-value-list (find-symbol "T" "CL-USER"))
			      (eq (intern "T" "CL") t) (eq (intern "NIL" "CL") nil) (eq (intern "NIL" :cl-user) nil)
			      (let ((n "NIL")) (eq (find-symbol n) nil))
			      (let ((s (make-string 3 :initial-element #\\N)))
			        (setf (char s 1) #\\I)
			        (setf (char s 2) #\\L)
			        (null (intern s)))
			      (listp (intern "NIL"))
			      (eq (intern "FOO") 'foo)
			      (if (member nil (find-all-symbols "NIL")) t nil)
			      (let ((found nil))
			        (do-external-symbols (s "CL") (when (null s) (setq found t)))
			        found))""";

	/** The compiled harnesses' program: the form, printed. */
	public static final String SOURCE = "(print " + FORM + ")";

	/** What {@link #FORM} answers. */
	public static final String EXPECTED = "(T T T (NIL :INHERITED) T (T :INHERITED) (NIL :INHERITED) T T (T :INHERITED) T T T T T T T T T)";

}
