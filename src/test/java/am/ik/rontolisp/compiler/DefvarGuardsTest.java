package am.ik.rontolisp.compiler;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which names a {@code defvar} of tests its variable at run time: a valued one below the
 * top level makes every {@code defvar} of the name test, and so does a store that can
 * have run before its first top-level one. Every other {@code defvar} keeps the
 * compile-time decision, so a program without such a name compiles as before.
 */
class DefvarGuardsTest {

	private static String guarded(String source) {
		return String.join(" ", DefvarGuards.collect(LispReader.readAllFromString(source)));
	}

	@Test
	void aDefvarTheTopLevelOrderCannotDecideTestsItsVariable() {
		// Below the top level: a conditional, a loop, a function body, a binding form.
		assertThat(guarded("(when (f) (defvar *a* 1)) (defvar *a* 2) (dotimes (i 2) (defvar *b* i))"))
			.isEqualTo("*A* *B*");
		assertThat(guarded("(defun f () (defvar *c* 1)) (defvar *c* 2) (let ((x 1)) (defvar *d* x))"))
			.isEqualTo("*C* *D*");
		// A store before the first top-level defvar: a top-level one, one in a lambda,
		// a modify macro, a nested definer, a store by a computed name.
		assertThat(guarded("(setq *a* 1) (mapc (lambda (x) (push x *b*)) '(1)) (when (f) (defparameter *c* 1))"
				+ " (defvar *a* 2) (defvar *b* nil) (defvar *c* 3)"))
			.isEqualTo("*A* *B* *C*");
		assertThat(guarded("(set (f) 1) (defvar *a* 2)")).isEqualTo("*A*");
		// A defun's store, once a form that can call it runs before the defvar.
		assertThat(guarded("(defun s () (incf *a*)) (s) (defvar *a* 2)")).isEqualTo("*A*");
		// makunbound can take the value between two defvars.
		assertThat(guarded("(defvar *a* 1) (makunbound '*a*) (defvar *a* 2)")).isEqualTo("*A*");
	}

	@Test
	void aDefvarTheTopLevelOrderDecidesKeepsTheCompileTimeDecision() {
		assertThat(guarded("""
				(declaim (special *a*) (type fixnum *b*))
				(defvar *c*)
				(defparameter *b* 1)
				(defun s () (setq *a* 3 *b* 4) *c* *d*)
				(defvar *e* '(1))
				(defvar *a* 1)
				(defvar *b* 2)
				(defvar *d* '(x))
				(s)
				(defvar *a* 5)
				(setq *d* 6)
				'(defvar *q* 1)
				(defvar *print-base* 10)
				""")).isEmpty();
	}

}
