package am.ik.rontolisp;

/**
 * Programs that catch the {@code unbound-variable} a read of an unbound name signals and
 * read its {@code cell-error-name}: {@code symbol-value} of a literal and of a computed
 * name, {@code #'symbol-value} called and mapped, inside a binding of an unrelated
 * special, and caught as {@code cell-error} and as {@code error}. Every backend used to
 * fill no {@code name} slot, so the reader answered NIL where SBCL answers the name, and
 * the wasm-GC backends trapped instead of signalling. {@link #RESTART} adds a
 * {@code handler-bind}, which puts the compiled backends in restart mode. The expected
 * text is SBCL's. {@link #MESSAGE} pins the condition's report, rontolisp's text (SBCL
 * ends it with a period). Shared by the backend suites, so every backend is held to one
 * expected text; {@code ci-spec.yaml}'s {@code unbound-variable-carries-its-name} runs
 * the programs on the native binary.
 */
public final class UnboundVariableNameFixture {

	private UnboundVariableNameFixture() {
	}

	/** The program without a {@code handler-bind}. */
	public static final String PLAIN = """
			(defun uvn-name (thunk)
			  (handler-case (funcall thunk)
			    (unbound-variable (c) (list (cell-error-name c) (type-of c)))))
			(defvar *uvn-special* 1)
			(print (list (uvn-name (lambda () (symbol-value 'uvn-nope)))
			             (uvn-name (lambda () (symbol-value (intern "UVN-COMPUTED"))))
			             (uvn-name (lambda () (funcall #'symbol-value 'uvn-nope)))
			             (uvn-name (lambda () (mapcar #'symbol-value '(uvn-mapped))))
			             (uvn-name (lambda () (let ((*uvn-special* 2)) (symbol-value 'uvn-inside))))))
			(print (eq (cell-error-name (handler-case (symbol-value 'uvn-nope) (unbound-variable (c) c)))
			           'uvn-nope))
			(print (handler-case (symbol-value 'uvn-nope)
			         (cell-error (c) (list :cell (cell-error-name c) (typep c 'unbound-variable)))))
			(print (handler-case (symbol-value 'uvn-nope)
			         (error (c) (list :error (type-of c)))))
			""";

	/** What {@link #PLAIN} prints, one value per line. */
	public static final String PLAIN_EXPECTED = String.join("\n",
			"((UVN-NOPE UNBOUND-VARIABLE) (UVN-COMPUTED UNBOUND-VARIABLE) (UVN-NOPE UNBOUND-VARIABLE)"
					+ " (UVN-MAPPED UNBOUND-VARIABLE) (UVN-INSIDE UNBOUND-VARIABLE))",
			"T", "(:CELL UVN-NOPE T)", "(:ERROR UNBOUND-VARIABLE)");

	/** The program whose {@code handler-bind} sees the condition first. */
	public static final String RESTART = """
			(defvar *uvn-seen* nil)
			(defun uvn-bound (thunk)
			  (handler-case
			      (handler-bind ((unbound-variable (lambda (c) (push (cell-error-name c) *uvn-seen*))))
			        (funcall thunk))
			    (unbound-variable (c) (list (cell-error-name c) (type-of c)))))
			(print (list (uvn-bound (lambda () (symbol-value 'uvn-nope)))
			             (uvn-bound (lambda () (symbol-value (intern "UVN-OTHER"))))
			             *uvn-seen*))
			""";

	/** What {@link #RESTART} prints. */
	public static final String RESTART_EXPECTED = "((UVN-NOPE UNBOUND-VARIABLE) (UVN-OTHER UNBOUND-VARIABLE)"
			+ " (UVN-OTHER UVN-NOPE))";

	/** The program printing the report of a caught {@code unbound-variable}. */
	public static final String MESSAGE = """
			(print (handler-case (symbol-value 'uvn-nope) (error (c) (princ-to-string c))))
			""";

	/** What {@link #MESSAGE} prints: the interpreter's text, on every backend. */
	public static final String MESSAGE_EXPECTED = "\"The variable UVN-NOPE is unbound\"";

}
