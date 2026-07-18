package am.ik.rontolisp.eval;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMacroExpanderTest {

	private static String expand(String source) {
		return UserMacroExpander.expand(LispReader.readAllFromString(source))
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

	@Test
	void programWithoutDefmacroIsReturnedUnchanged() {
		List<LispVal> program = LispReader.readAllFromString("(print (+ 1 2))");
		assertThat(UserMacroExpander.expand(program)).isSameAs(program);
	}

	@Test
	void readEvalMarkersResolveAgainstTheMacroTimeEvaluator() {
		// The marker read wraps #. datums; expand() resolves them per top-level form.
		String expanded = UserMacroExpander
			.expand(LispReader.readAllWithReadEvalMarkers("(print #.(+ 40 2)) (print '(a #.(* 2 3) c))",
					am.ik.rontolisp.reader.Features.JVM))
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(expanded).isEqualTo("(print 42)\n(print (quote (a 6 c)))");
	}

	@Test
	void readEvalMarkerDatumSeesPrecedingDefuns() {
		String expanded = UserMacroExpander
			.expand(LispReader.readAllWithReadEvalMarkers("(defun re-h (x) (* x 10)) (print #.(re-h 5))",
					am.ik.rontolisp.reader.Features.JVM))
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(expanded).isEqualTo("(defun re-h (x) (* x 10))\n(print 50)");
	}

	@Test
	void defmacroIsConsumedAndCallSitesExpanded() {
		assertThat(expand("""
				(defmacro my-when2 (test &body body) `(if ,test (progn ,@body) nil))
				(print (my-when2 (> 3 1) 10 20))
				""")).isEqualTo("(print (if (> 3 1) (progn 10 20) nil))");
	}

	@Test
	void macroCallsInsideDefunBodiesAreExpanded() {
		assertThat(expand("""
				(defmacro twice (x) `(* 2 ,x))
				(defun f (n) (twice n))
				""")).isEqualTo("(defun f (n) (* 2 n))");
	}

	@Test
	void expansionOfMacroIntoMacroIsFullyExpanded() {
		assertThat(expand("""
				(defmacro inner (x) `(+ ,x 1))
				(defmacro outer (x) `(inner ,x))
				(print (outer 41))
				""")).isEqualTo("(print (+ 41 1))");
	}

	@Test
	void macroBodyMayCallHelperDefun() {
		assertThat(expand("""
				(defun expand-helper (n) (* n 2))
				(defmacro with-doubled (n x) `(+ ,(expand-helper n) ,x))
				(print (with-doubled 5 1))
				""")).isEqualTo("(defun expand-helper (n) (* n 2))\n(print (+ 10 1))");
	}

	@Test
	void quotedDataIsNotExpanded() {
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(print '(m 1))
				""")).isEqualTo("(print (quote (m 1)))");
	}

	@Test
	void bindingNamesAreNotMistakenForMacroCalls() {
		// A let binding (m 1) binds the variable m; it is not a call of the macro m.
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(print (let ((m (m 1))) m))
				""")).isEqualTo("(print (let ((m (+ 1 1))) m))");
	}

	@Test
	void lambdaAndDefunParameterListsAreNotWalked() {
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(defun f (m) (funcall (lambda (m) m) m))
				""")).isEqualTo("(defun f (m) (funcall (lambda (m) m) m))");
	}

	@Test
	void caseKeysAreNotWalked() {
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(print (case 'm ((m) (m 1)) (otherwise 0)))
				""")).isEqualTo("(print (case (quote m) ((m) (+ 1 1)) (otherwise 0)))");
	}

	@Test
	void dolistSpecKeepsVariableButExpandsListForm() {
		assertThat(expand("""
				(defmacro m (x) `(list ,x))
				(dolist (m (m 3)) (print m))
				""")).isEqualTo("(dolist (m (list 3)) (print m))");
	}

	@Test
	void macroExpandingToDefmacroIsConsumedToo() {
		// Nested backquote is unsupported, so the generated defmacro is built with
		// list/quote instead.
		assertThat(expand("""
				(defmacro def-twice () '(defmacro twice2 (x) (list '* 2 x)))
				(def-twice)
				(print (twice2 21))
				""")).isEqualTo("(print (* 2 21))");
	}

	@Test
	void macroArgumentErrorsSurfaceAtCompileTime() {
		assertThatThrownBy(() -> expand("""
				(defmacro m (a b) `(+ ,a ,b))
				(print (m 1))
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("expects 2 arguments");
	}

	@Test
	void defstructNamesAreNotMistakenForMacroCallsButSlotDefaultsExpand() {
		// The struct name and slot names stay verbatim even when a user macro shares
		// their name; a slot default is an expression and is expanded.
		assertThat(expand("""
				(defmacro pt () '(f))
				(defmacro x (v) `(g ,v))
				(defstruct pt (x 1) (y (x 2)))
				""")).isEqualTo("(defstruct pt (x 1) (y (g 2)))");
	}

	@Test
	void macroExpandingToDefstructIsKept() {
		assertThat(expand("""
				(defmacro defpair (name) (list 'defstruct name 'left 'right))
				(defpair pair)
				""")).isEqualTo("(defstruct pair left right)");
	}

	@Test
	void defmethodLambdaListStaysVerbatimButBodyExpands() {
		// The specializer (x (eql :br)) must not be mistaken for a call of a user
		// macro named eql/x; the body is expressions.
		assertThat(expand("""
				(defmacro x (v) `(g ,v))
				(defmethod f ((x (eql :br)) y) (x y))
				""")).isEqualTo("(defmethod f ((x (eql :br)) y) (g y))");
	}

	@Test
	void defclassKeepsNamesAndOptionsButExpandsInitforms() {
		// The rebuilt form prints the empty superclass list as nil (same datum).
		assertThat(expand("""
				(defmacro dflt () '(h))
				(defclass c () ((s :initarg :s :initform (dflt) :accessor c-s)))
				""")).isEqualTo("(defclass c nil ((s :initarg :s :initform (h) :accessor c-s)))");
	}

	@Test
	void macroBodyMayCallAGenericFunctionAtExpansionTime() {
		// The cl-who pattern: with-html-output's expansion-time chain calls the
		// generic convert-tag-to-string-list; the CLOS definitions register into the
		// macro-time evaluator AND stay in the program for the compilers.
		assertThat(expand("""
				(defgeneric conv (tag))
				(defmethod conv (tag) (list tag :end))
				(defmethod conv ((tag (eql :br))) (list :br-tag))
				(defmacro tag-of (tag) `(list ,@(conv tag)))
				(print (tag-of :p))
				(print (tag-of :br))
				""")).isEqualTo("""
				(defgeneric conv (tag))
				(defmethod conv (tag) (list tag :end))
				(defmethod conv ((tag (eql :br))) (list :br-tag))
				(print (list :p :end))
				(print (list :br-tag))""");
	}

	// --- macrolet (local macros consumed at compile time) ---

	@Test
	void macroletExpandsLocalMacroCallsAndDropsTheWrapper() {
		// The pass activates on macrolet alone (no defmacro); the macrolet is dropped and
		// its body's local macro calls are expanded. A single body form is returned
		// unwrapped (no needless progn); multiple forms are wrapped in progn.
		assertThat(expand("(macrolet ((sq (x) `(* ,x ,x))) (print (sq 6)))")).isEqualTo("(print (* 6 6))");
		assertThat(expand("(macrolet ((sq (x) `(* ,x ,x))) (print (sq 2)) (print (sq 3)))"))
			.isEqualTo("(progn (print (* 2 2)) (print (* 3 3)))");
	}

	@Test
	void macroletIsLexicalAndDoesNotLeakToSiblingForms() {
		// The local macro m is only active inside the macrolet body; the sibling (m 1)
		// stays an ordinary call (m resolves in the function namespace later).
		assertThat(expand("""
				(macrolet ((m (x) `(+ ,x 1))) (print (m 5)))
				(print (m 5))
				""")).isEqualTo("(print (+ 5 1))\n(print (m 5))");
	}

	@Test
	void macroletNestedInADefunBodyIsExpanded() {
		assertThat(expand("""
				(defun f (n) (macrolet ((dbl (x) `(* 2 ,x))) (dbl n)))
				""")).isEqualTo("(defun f (n) (* 2 n))");
	}

	@Test
	void macroletMacroSeesGlobalHelperDefun() {
		assertThat(expand("""
				(defun h (n) (* n 10))
				(macrolet ((m (n) `(+ ,(h n) 1))) (print (m 4)))
				""")).isEqualTo("(defun h (n) (* n 10))\n(print (+ 40 1))");
	}

	// --- macroexpand / macroexpand-1 folding ---

	@Test
	void macroexpand1WithALiteralQuotedUserMacroCallIsFolded() {
		assertThat(expand("""
				(defmacro my-when2 (test &body body) `(if ,test (progn ,@body) nil))
				(print (macroexpand-1 '(my-when2 a b)))
				""")).isEqualTo("(print (quote (if a (progn b) nil)))");
	}

	@Test
	void macroexpandFoldsBuiltinMacrosWithoutAnyDefmacro() {
		// The pass must activate on macroexpand alone (no defmacro in the program).
		assertThat(expand("(print (macroexpand-1 '(unless c x)))")).isEqualTo("(print (quote (if c nil x)))");
		assertThat(expand("(print (macroexpand '(outer 1)))")).isEqualTo("(print (quote (outer 1)))");
	}

	@Test
	void macroexpandFoldsToAFixpoint() {
		assertThat(expand("""
				(defmacro inner (x) `(+ ,x 1))
				(defmacro outer (x) `(inner ,x))
				(print (macroexpand '(outer 41)))
				(print (macroexpand-1 '(outer 41)))
				""")).isEqualTo("(print (quote (+ 41 1)))\n(print (quote (inner 41)))");
	}

	@Test
	void macroexpandOfANonMacroFormFoldsToTheFormItself() {
		assertThat(expand("(print (macroexpand-1 '(+ 1 2)))")).isEqualTo("(print (quote (+ 1 2)))");
		assertThat(expand("(print (macroexpand-1 'x))")).isEqualTo("(print (quote x))");
	}

	@Test
	void macroexpandWithAComputedArgumentIsLeftAlone() {
		// Only a literal quoted argument can be folded; a computed one stays and fails
		// in the compilers ("Cannot compile").
		assertThat(expand("(setq f '(when a b))\n(print (macroexpand-1 f))"))
			.isEqualTo("(setq f (quote (when a b)))\n(print (macroexpand-1 f))");
	}

	@Test
	void improperCallFormIsLeftVerbatimNotTruncated() {
		// An improper list is never a call form, but it can be legitimate data (a
		// loop destructuring pattern like (value . remaining)), so the walk keeps it
		// verbatim -- the compilers/evaluator still reject a genuine dotted call.
		assertThat(expand("""
				(defmacro twice (x) `(* 2 ,x))
				(print (+ 1 . 2))
				""")).isEqualTo("(print (+ 1 . 2))");
	}

	@Test
	void improperQuotedDataIsLeftAlone() {
		assertThat(expand("""
				(defmacro twice (x) `(* 2 ,x))
				(print (twice (car '((a . 1)))))
				""")).isEqualTo("(print (* 2 (car (quote ((a . 1))))))");
	}

	// --- pure-config-setter replay (macro-time (setf (place) ...) auto-detect) ---

	@Test
	void pureConfigSetterIsReplayedSoAMacroSeesTheNewValueAtExpansionTime() {
		// The cl-who (setf (html-mode) :html5) pattern: a (defun (setf mode) ...) whose
		// body only assigns a special variable is a pure config setter, so a top-level
		// (setf (mode) :b) is replayed into the macro-time evaluator and a macro reading
		// the special at expansion time sees :b (not the defvar default :a).
		assertThat(expand("""
				(defvar *mode* :a)
				(defun (setf mode) (v) (setf *mode* v))
				(defmacro current-mode () (list 'quote *mode*))
				(setf (mode) :b)
				(print (current-mode))
				""")).isEqualTo("""
				(defvar *mode* :a)
				(defun (setf mode) (v) (setf *mode* v))
				(setf (mode) :b)
				(print (quote :b))""");
	}

	@Test
	void configSetterUsingEcaseOverSpecialsIsReplayed() {
		// cl-who's real (setf html-mode) writer dispatches with ecase and assigns several
		// specials per branch; the purity walk must accept that shape.
		assertThat(expand("""
				(defvar *mode* :a)
				(defvar *end* " />")
				(defun (setf mode) (m)
				  (ecase m
				    ((:sgml) (setf *mode* :sgml *end* ">"))
				    ((:xml) (setf *mode* :xml *end* " />"))))
				(defmacro current-end () *end*)
				(setf (mode) :sgml)
				(print (current-end))
				""")).endsWith("(print \">\")");
	}

	@Test
	void impureSetterWithIoIsNotReplayed() {
		// The writer performs I/O (print), so it is NOT a pure config setter: the setf is
		// left for runtime only and the expansion-time read still sees the defvar
		// default.
		assertThat(expand("""
				(defvar *mode* :a)
				(defun (setf mode) (v) (print v) (setf *mode* v))
				(defmacro current-mode () (list 'quote *mode*))
				(setf (mode) :b)
				(print (current-mode))
				""")).endsWith("(print (quote :a))");
	}

	@Test
	void impureSetfValueIsNotReplayed() {
		// Even with a pure writer, a top-level setf whose VALUE has a side effect is not
		// replayed -- replaying evaluates the value and would double-run the effect.
		assertThat(expand("""
				(defvar *mode* :a)
				(defun (setf mode) (v) (setf *mode* v))
				(defmacro current-mode () (list 'quote *mode*))
				(setf (mode) (progn (print 'x) :b))
				(print (current-mode))
				""")).endsWith("(print (quote :a))");
	}

	@Test
	void setterMutatingANonSpecialDataStructureIsNotReplayed() {
		// A writer that mutates a data structure (rplaca) rather than a special/global
		// variable is impure config-wise and is not replayed.
		assertThat(expand("""
				(defvar *cell* (list 1))
				(defun (setf head) (v) (rplaca *cell* v))
				(defmacro current-head () (list 'quote (car *cell*)))
				(setf (head) 9)
				(print (current-head))
				""")).endsWith("(print (quote 1))");
	}

	@Test
	void setfOfAnUndefinedWriterIsNotReplayed() {
		// No (defun (setf foo) ...) writer exists: nothing to judge, so no replay (and
		// the
		// setf survives for the compilers/interpreter to reject or handle).
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(setf (foo) 3)
				(print (m 1))
				""")).isEqualTo("(setf (foo) 3)\n(print (+ 1 1))");
	}

}
