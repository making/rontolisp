package am.ik.rontolisp;

/**
 * A program that reads globals no definer declares before their first store and catches
 * the {@code unbound-variable} each read signals, reading its {@code cell-error-name}: a
 * function called before the top-level {@code setq} that assigns it, a top-level read
 * before that {@code setq}, a global only a function assigns, a store whose value reads
 * the global through a function, the {@code satisfies} predicate of a {@code deftype} a
 * type test runs, a lambda {@code mapcar} calls from a function; then each read again
 * after the store, and a global assigned before every read. The compiled backends used to
 * read NIL in place of every signal (the {@code setq} global started as nil), and the
 * arithmetic over it then failed with a type error. The expected text is SBCL's. Shared
 * by the backend suites, so every backend is held to one expected text;
 * {@code ci-spec.yaml}'s {@code a-global-read-before-its-first-store-signals} runs the
 * program on the native binary.
 */
public final class ReadBeforeStoreFixture {

	private ReadBeforeStoreFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun rbs-name (thunk)
			  (handler-case (funcall thunk)
			    (unbound-variable (c) (list :unbound (cell-error-name c)))))
			(defun rbs-late () *rbs-late*)
			(print (rbs-name #'rbs-late))
			(setq *rbs-late* 1)
			(print (rbs-late))
			(print (rbs-name (lambda () *rbs-top*)))
			(setq *rbs-top* 2)
			(print *rbs-top*)
			(defun rbs-assign () (setq *rbs-assigned* 3))
			(defun rbs-assigned () *rbs-assigned*)
			(print (rbs-name #'rbs-assigned))
			(rbs-assign)
			(print (rbs-assigned))
			(defun rbs-next () (1+ *rbs-count*))
			(print (rbs-name (lambda () (setq *rbs-count* (rbs-next)))))
			(setq *rbs-count* 0)
			(setq *rbs-count* (rbs-next))
			(print *rbs-count*)
			(defun rbs-small-p (x) (< x *rbs-limit*))
			(deftype rbs-small () '(satisfies rbs-small-p))
			(print (rbs-name (lambda () (typep 3 'rbs-small))))
			(setq *rbs-limit* 10)
			(print (typep 3 'rbs-small))
			(defun rbs-sum (xs) (reduce #'+ (mapcar (lambda (x) (* x *rbs-scale*)) xs)))
			(print (rbs-name (lambda () (rbs-sum '(1 2)))))
			(setq *rbs-scale* 3)
			(print (rbs-sum '(1 2)))
			(setq *rbs-early* 4)
			(defun rbs-early () *rbs-early*)
			(print (list (rbs-early) (1+ *rbs-early*)))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(:UNBOUND *RBS-LATE*)", "1", "(:UNBOUND *RBS-TOP*)", "2",
			"(:UNBOUND *RBS-ASSIGNED*)", "3", "(:UNBOUND *RBS-COUNT*)", "1", "(:UNBOUND *RBS-LIMIT*)", "T",
			"(:UNBOUND *RBS-SCALE*)", "9", "(4 5)");

}
