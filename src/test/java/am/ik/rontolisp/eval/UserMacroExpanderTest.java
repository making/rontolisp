package am.ik.rontolisp.eval;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
		// Nothing asks what a package holds beyond its externals, or by a name the fold
		// would have to know.
		for (String asksNothing : List.of("(do-external-symbols (s :cl) (print s))", "(print (find-symbol name))",
				"(print (find-symbol \"A\" :p))", "(print 'do-external-symbols)")) {
			List<LispVal> forms = LispReader.readAllFromString(asksNothing);
			assertThat(UserMacroExpander.expand(forms)).isSameAs(forms);
		}
		assertThat(expand("(defmacro m () 'only-read) (print (find-symbol name))")).doesNotContain("%READ-SYMBOLS");
	}

	@ParameterizedTest
	@ValueSource(strings = { "(do-symbols (s :p) (print s))", "(do-all-symbols (s) (print s))",
			"(print (find-all-symbols 'a))", "(print (apropos-list \"A\"))", "(apropos \"A\")",
			"(with-package-iterator (next :p :internal) (print (next)))", "(print (find-symbol \"A\"))",
			"(print (loop for s being each symbol in :p collect s))",
			"(print (loop for s being each present-symbol in :p collect s))",
			"(print (uiop:package-definition-form :p))" })
	void aProgramAskingWhatAPackageHoldsCarriesWhatItRead(String asks) {
		// Read in a macro body the backends never see, in a package, by the form that
		// asks; what the compile path spliced in front of the program is no reading.
		List<LispVal> spliced = LispReader.readAllFromString("(print 'implementation-name)");
		List<LispVal> program = LispReader.readAllFromString("""
				(defpackage :p (:use :cl))
				(defmacro m () 'in-a-macro-body)
				(print 'p::in-p)
				""" + asks);
		List<LispVal> forms = new java.util.ArrayList<>(spliced);
		forms.addAll(program);
		LispVal marker = UserMacroExpander.expand(forms, program.get(0)).getLast();
		assertThat(marker.print()).startsWith("(%READ-SYMBOLS ")
			.contains("\"IN-A-MACRO-BODY\"")
			.contains("(\"P\" ")
			.contains("\"IN-P\"")
			.doesNotContain("IMPLEMENTATION-NAME")
			.doesNotContain("\"PRINT\"");
	}

	@Test
	void readEvalMarkersResolveAgainstTheMacroTimeEvaluator() {
		// The marker read wraps #. datums; expand() resolves them per top-level form. A
		// progn one completes is spliced after that, so a macro its member defines
		// reaches the next.
		String expanded = UserMacroExpander.expand(LispReader.readAllWithReadEvalMarkers("""
				(print #.(+ 40 2)) (print '(a #.(* 2 3) c))
				(progn . #.(list '(defmacro re-m () 1) '(print (re-m))))
				""", am.ik.rontolisp.reader.Features.JVM))
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(expanded).isEqualTo("(PRINT 42)\n(PRINT '(A 6 C))\n(PRINT 1)");
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

	@ParameterizedTest
	@ValueSource(strings = { "(cl:defmacro m (x) x)", "(common-lisp:defmacro m (x) x)",
			"(cl:progn (cl:defmacro m (x) x))", "(cl:eval-when (:execute) (cl:defmacro m (x) x))",
			"(cl:defsetf m set-m)", "(cl:define-modify-macro m () cl:1+)", "(cl:define-compiler-macro m (x) x)",
			"(cl:define-setf-expander m (x) x)" })
	void aDefinitionSpelledClActivatesThePass(String definition) {
		// A package using no cl spells them so: until the pass ran the definition reached
		// a backend, whose call of DEFMACRO was an undefined function.
		assertThat(UserMacroExpander.expand(LispReader.readAllFromString(definition))).isEmpty();
	}

	@Test
	void aMacrosFormsSpelledClAreExpandedAndEmittedForTheCompilersRead() {
		// Everything spelled cl:, under a package using no cl, in the shapes the pass
		// emits and appends: its own forms must re-read as cl's through the compilers'
		// resolver. The tables land under the trailing in-package.
		List<LispVal> emitted = UserMacroExpander.expand(LispReader.readAllFromString("""
				(cl:defpackage :ume-nocl (:use))
				(cl:in-package :ume-nocl)
				(cl:deftype pair (x) (cl:list 'cl:cons x x))
				(cl:defmacro m (x) `(cl:car ,x))
				(cl:print (cl:list (m '(1)) (cl:macrolet ((n (y) `(cl:cdr ,y))) (n '(2)))))
				(cl:print (cl:list (cl:typep 1 '(pair cl:integer)) (cl:macro-function 'm)))
				"""));
		List<LispVal> resolved = new am.ik.rontolisp.PackageResolver().resolveProgram(emitted);
		assertThat(resolved.stream().map(LispVal::print).collect(Collectors.joining("\n")))
			.contains("(PRINT (LIST (CAR '(1)) (CDR '(2))))")
			.contains("(DEFTYPE UME-NOCL::PAIR NIL '")
			.contains("(DEFUN MACRO-FUNCTION (")
			.contains("(DECLAIM (")
			.doesNotContain("UME-NOCL::DEFUN")
			.doesNotContain("UME-NOCL::DEFTYPE")
			.doesNotContain("UME-NOCL::DECLAIM");
	}

	@Test
	void anExpansionKeepsItsSymbolsInAPackageThatReadsThemAsOthers() {
		// A macro of cl-user expanded elsewhere: the canonical spelling of its symbols is
		// bare, and a qualifier it spells may be a nickname of the package it lands in.
		// Only what a source form spells reads through the nickname.
		List<LispVal> emitted = UserMacroExpander.expand(LispReader.readAllFromString("""
				(defpackage :ume-target (:use :cl) (:export #:run))
				(defpackage :ume-other (:use :cl) (:export #:run))
				(defun helper () 1)
				(defmacro m () `(list (helper) (ume-target:run) 'helper))
				(defpackage :ume-there (:use :cl) (:local-nicknames (:ume-target :ume-other)))
				(in-package :ume-there)
				(print (cl-user::m))
				(print (ume-target:run))
				"""));
		List<LispVal> resolved = new am.ik.rontolisp.PackageResolver().resolveProgram(emitted);
		assertThat(resolved.stream().map(LispVal::print).toList())
			.contains("(PRINT (LIST (HELPER) (UME-TARGET:RUN) 'HELPER))", "(PRINT (UME-OTHER:RUN))");
	}

	@Test
	void aPackageDirectiveAMacroExpandsToGovernsTheFormsAfterIt() {
		List<LispVal> emitted = UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro define (name) `(defpackage ,name (:use :cl)))
				(defmacro enter (name) `(in-package ,name))
				(define :ume-directive)
				(enter :ume-directive)
				(defmacro here () ''x)
				(print (here))
				"""));
		List<LispVal> resolved = new am.ik.rontolisp.PackageResolver().resolveProgram(emitted);
		assertThat(resolved.stream().map(LispVal::print).toList()).contains("(PRINT 'UME-DIRECTIVE::X)");
	}

	@Test
	void anExpansionKeepsClsWhileAfterThePackageTakesTheName() {
		// The compilers' resolver re-reads the emitted forms after the %own-symbol claim:
		// cl's while, quoted or not, must still read as cl's there.
		List<LispVal> resolved = new am.ik.rontolisp.PackageResolver()
			.resolveProgram(UserMacroExpander.expand(LispReader.readAllFromString("""
					(defpackage :ume-own-while (:use :cl))
					(in-package :ume-own-while)
					(defmacro while (test &body body) `(list ,test ,@body))
					(print (list (while 1 2) (eq 'while 'cl:while) (cl:while nil)))
					""")));
		assertThat(resolved.get(resolved.size() - 1).print())
			.isEqualTo("(PRINT (LIST (LIST 1 2) (EQ 'UME-OWN-WHILE::WHILE 'WHILE) (WHILE NIL)))");
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
				""")).isEqualTo("(PRINT '(M 1))");
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
	void lambdaListNamesStayButDefaultFormsExpand() {
		// A parameter named like the macro is no call; an &optional/&key/&aux default
		// is an expression (lisp-binary's read-bytes method defaults :element-type
		// through quasiquote-2.0's macros).
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(defun f (m &optional (o (m 1) o-p) &key ((:k m) (m 2)) &aux (a (m 3)))
				  (funcall (lambda (m &optional (p (m 4))) m) m))
				(flet ((g (&key (q (m 5))) q)) (g))
				(destructuring-bind (a (b &optional (c (m 6)))) (list 1 (list 2)) (list a b c))
				(destructuring-bind (&optional ((a &optional (b (m 7))))) (list (list 1)) (list a b))
				""")).isEqualTo("""
				(DEFUN F (M &OPTIONAL (O (+ 1 1) O-P) &KEY ((:K M) (+ 2 1)) &AUX (A (+ 3 1))) \
				(FUNCALL (LAMBDA (M &OPTIONAL (P (+ 4 1))) M) M))
				(FLET ((G (&KEY (Q (+ 5 1))) Q)) (G))
				(DESTRUCTURING-BIND (A (B &OPTIONAL (C (+ 6 1)))) (LIST 1 (LIST 2)) (LIST A B C))
				(DESTRUCTURING-BIND (&OPTIONAL ((A &OPTIONAL (B (+ 7 1))))) (LIST (LIST 1)) (LIST A B))""");
	}

	@Test
	void caseKeysAreNotWalked() {
		assertThat(expand("""
				(defmacro m (x) `(+ ,x 1))
				(print (case 'm ((m) (m 1)) (otherwise 0)))
				""")).isEqualTo("(PRINT (CASE 'M ((M) (+ 1 1)) (OTHERWISE 0)))");
	}

	@Test
	void dolistSpecKeepsVariableButExpandsListForm() {
		assertThat(expand("""
				(defmacro m (x) `(list ,x))
				(dolist (m (m 3)) (print m))
				""")).isEqualTo("(DOLIST (M (LIST 3)) (PRINT M))");
	}

	@Test
	void aSplicedSystemReplaysAtMacroTimeButNotAComponentItsLoadLeavesUnrun() {
		// A test file (prove-asdf's load-op only remembers it) is no part of the loaded
		// system: its forms run when a test asks, never while the program compiles.
		assertThat(expand("""
				(%begin-system "lib")
				(defvar *seen* nil)
				(setq *seen* (cons :loaded *seen*))
				(%asdf-component "lib" 0 :deferred)
				(setq *seen* (cons :deferred *seen*))
				(%asdf-component-end)
				(%asdf-component "lib" 1)
				(setq *seen* (cons :component *seen*))
				(%asdf-component-end)
				(%end-system)
				(defmacro seen () (list 'quote *seen*))
				(print (seen))
				""")).isEqualTo("""
				(%BEGIN-SYSTEM "lib")
				(DEFVAR *SEEN* NIL)
				(SETQ *SEEN* (CONS :LOADED *SEEN*))
				(%ASDF-COMPONENT "lib" 0 :DEFERRED)
				(SETQ *SEEN* (CONS :DEFERRED *SEEN*))
				(%ASDF-COMPONENT-END)
				(%ASDF-COMPONENT "lib" 1)
				(SETQ *SEEN* (CONS :COMPONENT *SEEN*))
				(%ASDF-COMPONENT-END)
				(%END-SYSTEM)
				(PRINT '(:COMPONENT :LOADED))""");
	}

	@Test
	void macroExpandingToDefmacroIsConsumedToo() {
		// Nested backquote is unsupported, so the generated defmacro is built with
		// list/quote instead. The second shape is fiveam's def-special-environment: the
		// definitions inside an flet of the eval-when it expands to, the flet's value
		// after them -- left in place, it compiles to a call of DEFMACRO.
		assertThat(expand("""
				(defmacro def-twice () '(defmacro twice2 (x) (list '* 2 x)))
				(def-twice)
				(print (twice2 21))
				(defmacro def-thrice ()
				  '(eval-when (:compile-toplevel :load-toplevel :execute)
				     (flet () (defmacro thrice (x) (list '* 3 x)) 'thrice)))
				(def-thrice)
				(print (thrice 7))
				""")).isEqualTo("(PRINT (* 2 21))\n(PRINT (* 3 7))");
		// hu.dwim.def's (def macro ...): the definition inside a locally, the package
		// directive beside it a top-level form all the same.
		assertThat(expand("""
				(defmacro def-in-locally ()
				  '(locally (declare (optimize speed))
				     (export 'quadruple)
				     (defmacro quadruple (x) (list '* 4 x))))
				(def-in-locally)
				(print (quadruple 5))
				""")).isEqualTo("(EXPORT 'QUADRUPLE)\n(PRINT (* 4 5))");
		// The members of an expansion are top-level forms in order: the next one reaches
		// the macro, under a macrolet too. One a definition empties emits nothing.
		assertThat(expand("""
				(defmacro def-and-use () '(progn (defmacro five () 5) (print (five))))
				(def-and-use)
				(macrolet ((local () '(defmacro six () 6))) (local) (print (six)))
				(macrolet ((local () '(defmacro seven () 7))) (local))
				(print (seven))
				""")).isEqualTo("(PROGN (PRINT 5))\n(PRINT 6)\n(PRINT 7)");
		assertThat(expand("""
				(macrolet ((local () '(defsetf place set-place))) (local))
				(setf (place) 8)
				""")).contains("(SET-PLACE ").doesNotContain("DEFSETF");
	}

	@Test
	void setfMacroFunctionIsAMacroDefinitionWhereverATopLevelFormLeavesIt() {
		// Consumed like a defmacro: bare, among the members of a progn a macro built
		// (log4cl's forward-macro, what is left of it stays), and closing over a let. A
		// program with no defmacro activates the pass for it, however deep it sits.
		assertThat(expand("""
				(defun twice-form (form) (list '* 2 (second form)))
				(setf (macro-function 'fn-twice) (lambda (form env) (declare (ignore env)) (twice-form form)))
				(print (fn-twice 1))
				(let ((n 3))
				  (setf (macro-function 'closed) (lambda (form env) (declare (ignore form env)) n)))
				(print (closed))
				""")).isEqualTo("(DEFUN TWICE-FORM (FORM) (LIST '* 2 (SECOND FORM)))\n(PRINT (* 2 1))\n(PRINT 3)");
		assertThat(expand("""
				(defmacro forward (name from)
				  `(progn (print :kept)
				          (setf (macro-function ',name) (lambda (&rest args) (apply (macro-function ',from) args)))))
				(defmacro target (x) `(list :target ,x))
				(forward fwd target)
				(setf (macro-function 'fwd2) (macro-function 'fwd) pair 1)
				(print (fwd2 1))
				""")).isEqualTo("(PROGN (PRINT :KEPT))\n(PROGN (SETF PAIR 1))\n(PRINT (LIST :TARGET 1))");
		// Anywhere else the program runs it, its subforms walked with the code around
		// them.
		assertThat(expand("""
				(defmacro one () 1)
				(defun install (f) (setf (macro-function f) (lambda (form env) (one))))
				(let ((n 2)) (setf (macro-function 'mixed) (lambda (form env) n)) (print n))
				""")).contains("(DEFUN INSTALL (F) (SETF (MACRO-FUNCTION F) (LAMBDA (FORM ENV) 1)))")
			.contains("(LET ((N 2)) (SETF (MACRO-FUNCTION 'MIXED) (LAMBDA (FORM ENV) N)) (PRINT N))")
			.contains("(%MACRO-FN SYMBOL '(CL-USER::ONE))");
	}

	@Test
	void setfMacroFunctionTheCompilePathCannotCarryOutIsAnError() {
		// A value form the macro-time evaluator cannot run is named, not skipped: every
		// call site of the macro would otherwise compile to an undefined function.
		assertThatThrownBy(() -> expand("(setf (macro-function 'broken) (no-such-function-here))"))
			.hasMessageContaining("(SETF (MACRO-FUNCTION 'BROKEN) (NO-SUCH-FUNCTION-HERE))")
			.hasMessageContaining("NO-SUCH-FUNCTION-HERE");
		assertThatThrownBy(() -> expand("(setf (macro-function 'broken) 42)"))
			.hasMessageContaining("(SETF (MACRO-FUNCTION 'BROKEN) 42)");
	}

	@Test
	void macroArgumentErrorsSurfaceAtCompileTime() {
		assertThatThrownBy(() -> expand("""
				(defmacro m (a b) `(+ ,a ,b))
				(print (m 1))
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("expects 2 arguments");
		// A destructuring lambda list refuses a surplus argument the same way.
		assertThatThrownBy(() -> expand("""
				(defmacro m (a &optional b) `(+ ,a ,b))
				(print (m 1 2 3))
				""")).hasMessageContaining("Function expects at most 2 arguments, got 3");
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
				(defmethod f ((x (eql :br)) y &key (z (x 1))) (x y))
				""")).isEqualTo("(DEFMETHOD F ((X (EQL :BR)) Y &KEY (Z (G 1))) (G Y))");
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

	// The fold answers BOTH of CL's values: the expansion, and the expanded-p flag the
	// interpreter publishes through the spill channel. A literal (values ...) is what a
	// consumer's syntactic lowering recognizes, so the second value survives the
	// compilers.
	@Test
	void macroexpand1WithALiteralQuotedUserMacroCallIsFolded() {
		assertThat(expand("""
				(defmacro my-when2 (test &body body) `(if ,test (progn ,@body) nil))
				(print (macroexpand-1 '(my-when2 a b)))
				""")).isEqualTo("(PRINT (VALUES '(IF A (PROGN B) NIL) T))");
	}

	@Test
	void macroexpandFoldsBuiltinMacrosWithoutAnyDefmacro() {
		// The pass must activate on macroexpand alone (no defmacro in the program).
		assertThat(expand("(print (macroexpand-1 '(unless c x)))")).isEqualTo("(PRINT (VALUES '(IF C NIL X) T))");
		assertThat(expand("(print (macroexpand '(outer 1)))")).isEqualTo("(PRINT (VALUES '(OUTER 1) NIL))");
	}

	@Test
	void macroexpandFoldsToAFixpoint() {
		assertThat(expand("""
				(defmacro inner (x) `(+ ,x 1))
				(defmacro outer (x) `(inner ,x))
				(print (macroexpand '(outer 41)))
				(print (macroexpand-1 '(outer 41)))
				""")).isEqualTo("(PRINT (VALUES '(+ 41 1) T))\n(PRINT (VALUES '(INNER 41) T))");
	}

	@Test
	void macroexpandOfANonMacroFormFoldsToTheFormItself() {
		// Nothing expanded, so expanded-p is nil -- the flag is decided by the expander
		// answering the SAME reference it was handed.
		assertThat(expand("(print (macroexpand-1 '(+ 1 2)))")).isEqualTo("(PRINT (VALUES '(+ 1 2) NIL))");
		assertThat(expand("(print (macroexpand-1 'x))")).isEqualTo("(PRINT (VALUES 'X NIL))");
	}

	@Test
	void macroexpandWithAComputedArgumentIsLeftAlone() {
		// Only a literal quoted argument can be folded; a computed one stays and reaches
		// the prelude identity defun, which answers the form unchanged with expanded-p
		// nil (a compiled program has no macro table left).
		assertThat(expand("(setq f '(when a b))\n(print (macroexpand-1 f))"))
			.isEqualTo("(SETQ F '(WHEN A B))\n(PRINT (MACROEXPAND-1 F))");
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
				""")).isEqualTo("(PRINT (* 2 (CAR '((A . 1)))))");
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
				"""))
			.isEqualTo("(DEFVAR *MODE* :A)\n(DEFUN (SETF MODE) (V) (SETF *MODE* V))\n(SETF (MODE) :B)\n(PRINT ':B)");
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
				""")).endsWith("(PRINT ':A)");
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
				""")).endsWith("(PRINT ':A)");
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
				""")).endsWith("(PRINT '1)");
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
				""")).endsWith("(PRINT ':UNTOUCHED)");
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
		assertThat(expanded).endsWith("(PRINT '(SPEED 3))");
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
