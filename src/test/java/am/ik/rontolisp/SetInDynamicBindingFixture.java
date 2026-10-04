package am.ik.rontolisp;

/**
 * Programs that {@code set} (and {@code (setf (symbol-value ...))}) a special while a
 * dynamic binding of it is active: CL's {@code set} changes the symbol's CURRENT dynamic
 * value, exactly as {@code setq} does, so the binding takes the value, a callee reads it,
 * {@code symbol-value} answers it, and the global default is untouched once the binding
 * is undone -- on every exit. With no binding active the store reaches the global. Until
 * 2026-10-04 the backends answered three ways: the interpreter wrote the global
 * underneath its binding, the JVM wrote the {@code _g$} default beside the active
 * {@code _d$} cell, and wasm wrote the binding (shallow binding: the module global IS it)
 * but also the eval mirror, so {@code symbol-value} answered the binding's value after
 * the extent. {@link #SOURCE} has no {@code progv}; {@link #PROGV_SOURCE} binds the name
 * through {@code progv}, a special and a name nothing declares alike. The expected texts
 * are SBCL's. Shared by the backend suites; {@code ci-spec.yaml}'s
 * {@code set-writes-the-active-dynamic-binding} runs all three on the native binary.
 */
public final class SetInDynamicBindingFixture {

	private SetInDynamicBindingFixture() {
	}

	/** The program without {@code progv}. */
	public static final String SOURCE = """
			(defvar *sdb* :global)
			(defvar *sdb-other* :other)
			(defun sdb-show () *sdb*)
			(defun sdb-set (name value) (set name value))
			(defun sdb-probe (name)
			  (let ((*sdb* :bound))
			    (set name :set)
			    (list *sdb* (sdb-show) (symbol-value name))))
			(print (list (sdb-probe '*sdb*) *sdb* (sdb-show) (symbol-value '*sdb*) (symbol-value (intern "*SDB*"))))
			(print (list (let ((*sdb* :outer))
			               (list (let ((*sdb* :inner))
			                       (setf (symbol-value '*sdb*) :placed)
			                       (sdb-show))
			                     (sdb-show)))
			             (sdb-show)))
			(defun sdb-param (*sdb*) (sdb-set '*sdb* :param) (sdb-show))
			(print (list (sdb-param :arg) (sdb-show)))
			(print (list (catch 'sdb-tag (let ((*sdb* :bound)) (sdb-set '*sdb* :thrown) (throw 'sdb-tag (sdb-show))))
			             (handler-case (let ((*sdb* :bound)) (sdb-set '*sdb* :err) (error "boom"))
			               (error () (sdb-show)))
			             (sdb-show)))
			(print (list (let ((*sdb-other* :b)) (sdb-set '*sdb* :beside) *sdb-other*) (sdb-show) *sdb-other*))
			(sdb-set '*sdb* :new-global)
			(print (list *sdb* (symbol-value '*sdb*) (let ((*sdb* :b)) (sdb-set '*sdb* :c) (sdb-show)) *sdb*))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "((:SET :SET :SET) :GLOBAL :GLOBAL :GLOBAL :GLOBAL)",
			"((:PLACED :OUTER) :GLOBAL)", "(:PARAM :GLOBAL)", "(:THROWN :GLOBAL :GLOBAL)", "(:B :BESIDE :OTHER)",
			"(:NEW-GLOBAL :NEW-GLOBAL :C :NEW-GLOBAL)");

	/**
	 * The program whose only stores by a computed name are modify macros over a
	 * {@code symbol-value} place -- uiop's {@code register-hook-function} shape -- so the
	 * compilers must see them as {@code set} sites without a {@code set} spelled.
	 */
	public static final String PLACE_SOURCE = """
			(defvar *sdh* nil)
			(defun sdh-register (name hook) (pushnew hook (symbol-value name) :test 'equal))
			(defun sdh-show () *sdh*)
			(print (list (let ((*sdh* (list :outer)))
			               (sdh-register '*sdh* :in)
			               (list *sdh* (sdh-show) (symbol-value '*sdh*)))
			             *sdh* (symbol-value '*sdh*)))
			(sdh-register '*sdh* :global)
			(sdh-register '*sdh* :global)
			(print (list *sdh* (symbol-value '*sdh*)))
			""";

	/** What {@link #PLACE_SOURCE} prints, one value per line. */
	public static final String PLACE_EXPECTED = String.join("\n", "(((:IN :OUTER) (:IN :OUTER) (:IN :OUTER)) NIL NIL)",
			"((:GLOBAL) (:GLOBAL))");

	/** The program binding the name through {@code progv}. */
	public static final String PROGV_SOURCE = """
			(defvar *sbp* :global)
			(defun sbp-show () *sbp*)
			(defun sbp-probe (name)
			  (progv (list name) '(:bound)
			    (set name :set)
			    (list (symbol-value name) (sbp-show))))
			(print (list (sbp-probe '*sbp*) *sbp* (symbol-value '*sbp*)))
			(print (list (let ((*sbp* :let)) (list (sbp-probe '*sbp*) *sbp*)) *sbp*))
			(print (list (sbp-probe 'sbp-undeclared) (boundp 'sbp-undeclared)))
			(set 'sbp-free :free)
			(print (list (sbp-probe 'sbp-free) (symbol-value 'sbp-free)))
			""";

	/** What {@link #PROGV_SOURCE} prints, one value per line. */
	public static final String PROGV_EXPECTED = String.join("\n", "((:SET :SET) :GLOBAL :GLOBAL)",
			"(((:SET :SET) :LET) :GLOBAL)", "((:SET :GLOBAL) NIL)", "((:SET :GLOBAL) :FREE)");

}
