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

}
