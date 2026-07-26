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
		assertThat(expanded).isEqualTo("(PRINT 42)\n(PRINT (QUOTE (A 6 C)))");
	}

	@Test
	void readEvalMarkerDatumSeesPrecedingDefuns() {
		String expanded = UserMacroExpander
			.expand(LispReader.readAllWithReadEvalMarkers("(defun re-h (x) (* x 10)) (print #.(re-h 5))",
					am.ik.rontolisp.reader.Features.JVM))
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(expanded).isEqualTo("(DEFUN RE-H (X) (* X 10))\n(PRINT 50)");
	}

	@Test
	void defmacroIsConsumedAndCallSitesExpanded() {
		assertThat(expand("""
				(defmacro my-when2 (test &body body) `(if ,test (progn ,@body) nil))
				(print (my-when2 (> 3 1) 10 20))
				""")).isEqualTo("(PRINT (IF (> 3 1) (PROGN 10 20) NIL))");
	}

	@Test
	void macroCallsInsideDefunBodiesAreExpanded() {
		assertThat(expand("""
				(defmacro twice (x) `(* 2 ,x))
				(defun f (n) (twice n))
				""")).isEqualTo("(DEFUN F (N) (* 2 N))");
	}

	@Test
	void expansionOfMacroIntoMacroIsFullyExpanded() {
		assertThat(expand("""
				(defmacro inner (x) `(+ ,x 1))
				(defmacro outer (x) `(inner ,x))
				(print (outer 41))
				""")).isEqualTo("(PRINT (+ 41 1))");
	}

	@Test
	void macroBodyMayCallHelperDefun() {
		assertThat(expand("""
				(defun expand-helper (n) (* n 2))
				(defmacro with-doubled (n x) `(+ ,(expand-helper n) ,x))
				(print (with-doubled 5 1))
				""")).isEqualTo("(DEFUN EXPAND-HELPER (N) (* N 2))\n(PRINT (+ 10 1))");
	}

	@Test
	void quotedDataIsNotExpanded() {
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(print '(m 1))
				""")).isEqualTo("(PRINT (QUOTE (M 1)))");
	}

	@Test
	void bindingNamesAreNotMistakenForMacroCalls() {
		// A let binding (m 1) binds the variable m; it is not a call of the macro m.
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(print (let ((m (m 1))) m))
				""")).isEqualTo("(PRINT (LET ((M (+ 1 1))) M))");
	}

	@Test
	void lambdaAndDefunParameterListsAreNotWalked() {
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(defun f (m) (funcall (lambda (m) m) m))
				""")).isEqualTo("(DEFUN F (M) (FUNCALL (LAMBDA (M) M) M))");
	}

	@Test
	void caseKeysAreNotWalked() {
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(print (case 'm ((m) (m 1)) (otherwise 0)))
				""")).isEqualTo("(PRINT (CASE (QUOTE M) ((M) (+ 1 1)) (OTHERWISE 0)))");
	}

	@Test
	void dolistSpecKeepsVariableButExpandsListForm() {
		assertThat(expand("""
				(defmacro m (x) `(list ,x))
				(dolist (m (m 3)) (print m))
				""")).isEqualTo("(DOLIST (M (LIST 3)) (PRINT M))");
	}

	@Test
	void macroExpandingToDefmacroIsConsumedToo() {
		// Nested backquote is unsupported, so the generated defmacro is built with
		// list/quote instead.
		assertThat(expand("""
				(defmacro def-twice () '(defmacro twice2 (x) (list '* 2 x)))
				(def-twice)
				(print (twice2 21))
				""")).isEqualTo("(PRINT (* 2 21))");
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
				""")).isEqualTo("(DEFSTRUCT PT (X 1) (Y (G 2)))");
	}

	@Test
	void macroExpandingToDefstructIsKept() {
		assertThat(expand("""
				(defmacro defpair (name) (list 'defstruct name 'left 'right))
				(defpair pair)
				""")).isEqualTo("(DEFSTRUCT PAIR LEFT RIGHT)");
	}

	@Test
	void defmethodLambdaListStaysVerbatimButBodyExpands() {
		// The specializer (x (eql :br)) must not be mistaken for a call of a user
		// macro named eql/x; the body is expressions.
		assertThat(expand("""
				(defmacro x (v) `(g ,v))
				(defmethod f ((x (eql :br)) y) (x y))
				""")).isEqualTo("(DEFMETHOD F ((X (EQL :BR)) Y) (G Y))");
	}

	@Test
	void defclassKeepsNamesAndOptionsButExpandsInitforms() {
		// The rebuilt form prints the empty superclass list as nil (same datum).
		assertThat(expand("""
				(defmacro dflt () '(h))
				(defclass c () ((s :initarg :s :initform (dflt) :accessor c-s)))
				""")).isEqualTo("(DEFCLASS C NIL ((S :INITARG :S :INITFORM (H) :ACCESSOR C-S)))");
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
				""")).isEqualTo(
				"(DEFGENERIC CONV (TAG))\n(DEFMETHOD CONV (TAG) (LIST TAG :END))\n(DEFMETHOD CONV ((TAG (EQL :BR))) (LIST :BR-TAG))\n(PRINT (LIST :P :END))\n(PRINT (LIST :BR-TAG))");
	}

	// --- macrolet (local macros consumed at compile time) ---

	@Test
	void macroletExpandsLocalMacroCallsAndDropsTheWrapper() {
		// The pass activates on macrolet alone (no defmacro); the macrolet is dropped and
		// its body's local macro calls are expanded. A single body form is returned
		// unwrapped (no needless progn); multiple forms are wrapped in progn.
		assertThat(expand("(macrolet ((sq (x) `(* ,x ,x))) (print (sq 6)))")).isEqualTo("(PRINT (* 6 6))");
		assertThat(expand("(macrolet ((sq (x) `(* ,x ,x))) (print (sq 2)) (print (sq 3)))"))
			.isEqualTo("(PROGN (PRINT (* 2 2)) (PRINT (* 3 3)))");
	}

	@Test
	void macroletIsLexicalAndDoesNotLeakToSiblingForms() {
		// The local macro m is only active inside the macrolet body; the sibling (m 1)
		// stays an ordinary call (m resolves in the function namespace later).
		assertThat(expand("""
				(macrolet ((m (x) `(+ ,x 1))) (print (m 5)))
				(print (m 5))
				""")).isEqualTo("(PRINT (+ 5 1))\n(PRINT (M 5))");
	}

	@Test
	void macroletNestedInADefunBodyIsExpanded() {
		assertThat(expand("""
				(defun f (n) (macrolet ((dbl (x) `(* 2 ,x))) (dbl n)))
				""")).isEqualTo("(DEFUN F (N) (* 2 N))");
	}

	@Test
	void macroletMacroSeesGlobalHelperDefun() {
		assertThat(expand("""
				(defun h (n) (* n 10))
				(macrolet ((m (n) `(+ ,(h n) 1))) (print (m 4)))
				""")).isEqualTo("(DEFUN H (N) (* N 10))\n(PRINT (+ 40 1))");
	}

	// --- macroexpand / macroexpand-1 folding ---

	@Test
	void macroexpand1WithALiteralQuotedUserMacroCallIsFolded() {
		assertThat(expand("""
				(defmacro my-when2 (test &body body) `(if ,test (progn ,@body) nil))
				(print (macroexpand-1 '(my-when2 a b)))
				""")).isEqualTo("(PRINT (QUOTE (IF A (PROGN B) NIL)))");
	}

	@Test
	void macroexpandFoldsBuiltinMacrosWithoutAnyDefmacro() {
		// The pass must activate on macroexpand alone (no defmacro in the program).
		assertThat(expand("(print (macroexpand-1 '(unless c x)))")).isEqualTo("(PRINT (QUOTE (IF C NIL X)))");
		assertThat(expand("(print (macroexpand '(outer 1)))")).isEqualTo("(PRINT (QUOTE (OUTER 1)))");
	}

	@Test
	void macroexpandFoldsToAFixpoint() {
		assertThat(expand("""
				(defmacro inner (x) `(+ ,x 1))
				(defmacro outer (x) `(inner ,x))
				(print (macroexpand '(outer 41)))
				(print (macroexpand-1 '(outer 41)))
				""")).isEqualTo("(PRINT (QUOTE (+ 41 1)))\n(PRINT (QUOTE (INNER 41)))");
	}

	@Test
	void macroexpandOfANonMacroFormFoldsToTheFormItself() {
		assertThat(expand("(print (macroexpand-1 '(+ 1 2)))")).isEqualTo("(PRINT (QUOTE (+ 1 2)))");
		assertThat(expand("(print (macroexpand-1 'x))")).isEqualTo("(PRINT (QUOTE X))");
	}

	@Test
	void macroexpandWithAComputedArgumentIsLeftAlone() {
		// Only a literal quoted argument can be folded; a computed one stays and fails
		// in the compilers ("Cannot compile").
		assertThat(expand("(setq f '(when a b))\n(print (macroexpand-1 f))"))
			.isEqualTo("(SETQ F (QUOTE (WHEN A B)))\n(PRINT (MACROEXPAND-1 F))");
	}

	@Test
	void improperCallFormIsLeftVerbatimNotTruncated() {
		// An improper list is never a call form, but it can be legitimate data (a
		// loop destructuring pattern like (value . remaining)), so the walk keeps it
		// verbatim -- the compilers/evaluator still reject a genuine dotted call.
		assertThat(expand("""
				(defmacro twice (x) `(* 2 ,x))
				(print (+ 1 . 2))
				""")).isEqualTo("(PRINT (+ 1 . 2))");
	}

	@Test
	void improperQuotedDataIsLeftAlone() {
		assertThat(expand("""
				(defmacro twice (x) `(* 2 ,x))
				(print (twice (car '((a . 1)))))
				""")).isEqualTo("(PRINT (* 2 (CAR (QUOTE ((A . 1))))))");
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
				""")).isEqualTo(
				"(DEFVAR *MODE* :A)\n(DEFUN (SETF MODE) (V) (SETF *MODE* V))\n(SETF (MODE) :B)\n(PRINT (QUOTE :B))");
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
				""")).endsWith("(PRINT \">\")");
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
				""")).endsWith("(PRINT (QUOTE :A))");
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
				""")).endsWith("(PRINT (QUOTE :A))");
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
				""")).endsWith("(PRINT (QUOTE 1))");
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
				""")).isEqualTo("(SETF (FOO) 3)\n(PRINT (+ 1 1))");
	}

	// --- lazy macro-time globals (defvar value expressions run only if read) ---

	@Test
	void defvarValueExpressionIsNotEvaluatedWhenNoMacroReadsIt() {
		// The init form of *TABLE* would set *WITNESS*; nothing reads *TABLE* at
		// expansion time, so the init never runs and the macro sees *WITNESS* untouched.
		// This is the whole compile-time win: a library's load-time table building stays
		// in the compiled program instead of also running in the macro-time interpreter.
		assertThat(expand("""
				(defvar *witness* :untouched)
				(defvar *table* (progn (setq *witness* :init-ran) 42))
				(defmacro witness () (list 'quote *witness*))
				(print (witness))
				""")).endsWith("(PRINT (QUOTE :UNTOUCHED))");
	}

	@Test
	void defvarValueExpressionIsEvaluatedWhenAMacroReadsIt() {
		// The converse, and the reason the registration cannot simply be skipped: a macro
		// body reading the global at expansion time still gets the computed value.
		assertThat(expand("""
				(defvar *n* (+ 40 2))
				(defmacro n () *n*)
				(print (n))
				""")).endsWith("(PRINT 42)");
	}

	@Test
	void forcingOneMacroTimeGlobalForcesTheOnesItsValueReads() {
		// A pending value expression that reads another pending global forces it in turn.
		assertThat(expand("""
				(defvar *a* 6)
				(defvar *b* (* *a* 7))
				(defmacro b () *b*)
				(print (b))
				""")).endsWith("(PRINT 42)");
	}

	@Test
	void aSecondDefvarDoesNotReplaceAPendingValueExpression() {
		// defvar is idempotent whether or not the first value expression has been forced
		// yet -- a pending registration counts as bound.
		assertThat(expand("""
				(defvar *x* 1)
				(defvar *x* 2)
				(defmacro x () *x*)
				(print (x))
				""")).endsWith("(PRINT 1)");
	}

	@Test
	void defparameterReplacesAPendingValueExpression() {
		// defparameter always (re)assigns, so its expression supersedes the pending one.
		assertThat(expand("""
				(defvar *x* 1)
				(defparameter *x* 2)
				(defmacro x () *x*)
				(print (x))
				""")).endsWith("(PRINT 2)");
	}

	@Test
	void readEvalMarkerForcesAMacroTimeGlobal() {
		// The #. channel is the second reader of macro-time globals (cl-ppcre's
		// (declare #.*standard-optimize-settings*)), and it forces like a macro body.
		String expanded = UserMacroExpander.expand(LispReader.readAllWithReadEvalMarkers("""
				(defvar *settings* '(speed 3))
				(defmacro m (x) x)
				(print (m '#.*settings*))
				""", am.ik.rontolisp.reader.Features.JVM))
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(expanded).endsWith("(PRINT (QUOTE (SPEED 3)))");
	}

	@Test
	void aValueExpressionThatCannotEvaluateLeavesTheNameUnbound() {
		// The error contract is unchanged, only relocated: the expression is reported and
		// skipped, and the reading macro sees an unbound variable.
		assertThatThrownBy(() -> expand("""
				(defvar *broken* (no-such-function-here))
				(defmacro use () *broken*)
				(print (use))
				""")).hasMessageContaining("*BROKEN*").hasMessageContaining("unbound");
	}

	@Test
	void aValueExpressionThatCannotEvaluateIsHarmlessWhenNoMacroReadsIt() {
		// ... and a program that never reads it compiles cleanly, where the eager
		// registration used to warn about every such definition.
		assertThat(expand("""
				(defvar *broken* (no-such-function-here))
				(defmacro m (x) `(+ ,x 1))
				(print (m 1))
				""")).endsWith("(PRINT (+ 1 1))");
	}

}
