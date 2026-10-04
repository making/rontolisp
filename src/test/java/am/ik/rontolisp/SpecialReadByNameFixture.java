package am.ik.rontolisp;

/**
 * Programs that read a special BY NAME -- {@code symbol-value}, {@code eval} -- inside
 * and after a dynamic binding of it, and assign it through {@code eval}. A special has
 * one value on every backend: the current dynamic binding when one is active, else the
 * global. On the compile paths the eval runtime keeps a global environment mirror beside
 * the variable, and until 2026-10-04 a by-name read answered the mirror: the global
 * default inside a binding, and after a callee's {@code setq} (or a {@code set}) inside a
 * binding, the binding's value long after the extent ended; an {@code eval}'d
 * {@code setq} wrote the mirror only, so compiled code never saw it -- a special's
 * binding and a plain global alike. {@link #SOURCE} uses neither {@code set} nor
 * {@code progv}; {@link #SET_SOURCE} does. The expected texts are SBCL's. Shared by the
 * backend suites; {@code ci-spec.yaml}'s {@code eval-and-symbol-value-read-the-binding}
 * runs both on the native binary.
 */
public final class SpecialReadByNameFixture {

	private SpecialReadByNameFixture() {
	}

	/** The program without {@code set} or {@code progv}. */
	public static final String SOURCE = """
			(defvar *srn* :global)
			(defun srn-setq (v) (setq *srn* v))
			(defun srn-read () *srn*)
			(print (list (let ((*srn* :bound)) (srn-setq :callee) (list *srn* (symbol-value '*srn*) (eval '*srn*)))
			             *srn* (symbol-value '*srn*) (eval '*srn*) (symbol-value (intern "*SRN*"))))
			(print (let ((*srn* :inner)) (list (symbol-value '*srn*) (eval '*srn*) (symbol-value (intern "*SRN*")) (srn-read))))
			(print (list (catch 'srn-tag (let ((*srn* :thrown)) (srn-setq :callee) (throw 'srn-tag (eval '*srn*))))
			             (symbol-value '*srn*) (eval '*srn*)))
			(print (list (let ((*srn* :b2))
			               (eval '(setq *srn* :evalset))
			               (list *srn* (srn-read) (symbol-value '*srn*) (eval '*srn*)))
			             *srn* (eval '*srn*)))
			(eval '(setq *srn* :evalglobal))
			(print (list *srn* (srn-read) (symbol-value '*srn*) (eval '*srn*)))
			(defun srn-param (*srn*) (srn-setq :in-param) (list (symbol-value '*srn*) (eval '*srn*)))
			(print (list (srn-param :arg) (symbol-value '*srn*) (eval '*srn*)))
			(defvar *srn-unbound*)
			(print (list (boundp '*srn-unbound*)
			             (let ((*srn-unbound* :bound)) (list (symbol-value '*srn-unbound*) (eval '*srn-unbound*)))))
			(defvar *srn-count* 0)
			(print (list (let ((*srn-count* 1)) (eval '(setq *srn-count* (+ *srn-count* 10))) (eval '*srn-count*))
			             *srn-count* (eval '*srn-count*)))
			(defvar *srn-list* nil)
			(setq srn-plain 1)
			(defun srn-plain-read () srn-plain)
			(eval '(setq srn-plain 2))
			(eval '(push :a *srn-list*))
			(eval '(setf *srn-list* (cons :b *srn-list*)))
			(eval '(pop *srn-list*))
			(print (list srn-plain (srn-plain-read) (eval 'srn-plain) (symbol-value 'srn-plain) *srn-list*))
			(print (list (eval '(let ((srn-plain 10)) (setq srn-plain 11) srn-plain)) srn-plain (srn-plain-read)))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n",
			"((:CALLEE :CALLEE :CALLEE) :GLOBAL :GLOBAL :GLOBAL :GLOBAL)", "(:INNER :INNER :INNER :INNER)",
			"(:CALLEE :GLOBAL :GLOBAL)", "((:EVALSET :EVALSET :EVALSET :EVALSET) :GLOBAL :GLOBAL)",
			"(:EVALGLOBAL :EVALGLOBAL :EVALGLOBAL :EVALGLOBAL)", "((:IN-PARAM :IN-PARAM) :EVALGLOBAL :EVALGLOBAL)",
			"(NIL (:BOUND :BOUND))", "(11 0 0)", "(2 2 2 2 (:A))", "(11 2 2)");

	/** The program that also stores by name through {@code set} and {@code progv}. */
	public static final String SET_SOURCE = """
			(defvar *srs* 1)
			(defun srs-probe (name) (let ((*srs* 2)) (set name 3) (list (eval '*srs*) (symbol-value name))))
			(print (list (srs-probe '*srs*) *srs* (eval '*srs*) (symbol-value '*srs*) (symbol-value (intern "*SRS*"))))
			(print (list (progv '(*srs*) '(:pv)
			               (list (eval '*srs*) (symbol-value '*srs*) (eval '(setq *srs* :pv-set)) *srs*))
			             *srs* (eval '*srs*)))
			(print (list (progv '(srs-free) '(:free) (eval 'srs-free)) (boundp 'srs-free)))
			""";

	/** What {@link #SET_SOURCE} prints, one value per line. */
	public static final String SET_EXPECTED = String.join("\n", "((3 3) 1 1 1 1)", "((:PV :PV :PV-SET :PV-SET) 1 1)",
			"(:FREE NIL)");

}
