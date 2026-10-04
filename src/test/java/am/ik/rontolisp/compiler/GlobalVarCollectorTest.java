package am.ik.rontolisp.compiler;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which names a function body's assignments make globals: those no lexical binding in
 * scope holds, and nothing else, so a program whose functions assign only their own
 * variables gets no new backing store.
 */
class GlobalVarCollectorTest {

	private static String freeAssigned(String source) {
		return String.join(" ",
				GlobalVarCollector.collectFreeAssignedInFunctionBodies(LispReader.readAllFromString(source)));
	}

	@Test
	void anAssignmentWithNoLexicalBindingInScopeIsAGlobal() {
		assertThat(freeAssigned("(defun f () (setq *a* 1) (setf *b* 2))")).isEqualTo("*A* *B*");
		assertThat(freeAssigned("(defun f () (psetq *a* 1 *b* 2) (psetf *c* 3))")).isEqualTo("*A* *B* *C*");
		assertThat(freeAssigned("(defun f () (multiple-value-setq (*q* *r*) (floor 7 2)))")).isEqualTo("*Q* *R*");
		// Inside the function's lambdas, local functions and nested defuns too.
		assertThat(freeAssigned("(defun f (l) (mapc (lambda (x) (setq *last* x)) l))")).isEqualTo("*LAST*");
		assertThat(freeAssigned("(defun f () (labels ((g (n) (setq *depth* n))) (g 1)))")).isEqualTo("*DEPTH*");
		assertThat(freeAssigned("(defun f () (defun g () (setq *in* 1)))")).isEqualTo("*IN*");
		// A built-in's name is a variable in an assignment's place (Lisp-2).
		assertThat(freeAssigned("(defun f () (setq list 1))")).isEqualTo("LIST");
	}

	@Test
	void anAssignmentOfALexicalVariableIsNot() {
		assertThat(freeAssigned("""
				(defun f (p &optional (o 1) &rest r)
				  (let ((x 0)) (setq x 1))
				  (setq p 2 o 3 r 4)
				  (dolist (i '(1 2)) (setq i 0))
				  (mapc (lambda (y) (setq y 0)) '(1))
				  (let ((c 0)) (defun g () (setq c 1)))
				  (labels ((h (z) (setq z 0))) (h 1))
				  (multiple-value-bind (m n) (floor 7 2) (setq m n)))
				""")).isEmpty();
		// Quoted data assigns nothing; a top-level form is collect()'s, not this walk's.
		assertThat(freeAssigned("(defun f () '(setq *a* 1)) (setq *b* 2) (let () (setq *c* 3))")).isEmpty();
		// The standard stream variables and the multiple-value channel keep their own
		// representation on the backends.
		assertThat(freeAssigned("(defun f (s) (setq *standard-output* s *error-output* s))")).isEmpty();
	}

	@Test
	void aNameReadFreeAndAssignedUnderABindingStillCounts() {
		// The free read had no store to reach before; the lexical assignment still
		// resolves its own binding first.
		assertThat(freeAssigned("(defun f () (list *y* (let ((*y* 1)) (setq *y* 2))))")).isEqualTo("*Y*");
	}

	/**
	 * The globals a literal {@code boundp} reads off their own variable when the program
	 * has no eval mirror: probed, no definer gives them a value, not a {@code cl} symbol,
	 * and no {@code progv} in the program. The gate's set, read before injection, holds
	 * the bound specials besides and only names the program certainly makes globals.
	 */
	@Test
	void aLiteralProbeOfAGlobalWithoutAValueIsReadOffItsVariable() {
		java.util.List<am.ik.rontolisp.LispVal> program = LispReader.readAllFromString("""
				(defvar *a*)
				(defvar *b* 1)
				(defun f () (setq *c* 1) (setq *d* 2) (let ((*l* 0)) (setq *l* 1)))
				(defun p () (list (boundp '*a*) (boundp '*b*) (boundp '*c*) (boundp '*print-base*) (boundp '*l*)
				                  '(boundp '*d*)))
				(setq *e* 0)
				(defun q () (boundp '*e*))
				""");
		java.util.SequencedSet<String> specials = new java.util.LinkedHashSet<>(java.util.List.of("*A*", "*B*"));
		java.util.SequencedSet<String> globals = new java.util.LinkedHashSet<>(
				java.util.List.of("*E*", "*A*", "*B*", "*C*", "*D*", "*PRINT-BASE*"));
		assertThat(GlobalVarCollector.collectProbedUnbound(program, program, globals)).containsExactly("*E*", "*A*",
				"*C*");
		assertThat(
				GlobalVarCollector.collectProbedUnboundBeforeInjection(program, java.util.List.of(), specials, false))
			.containsExactlyInAnyOrder("*A*", "*C*", "*E*");
		java.util.List<am.ik.rontolisp.LispVal> progv = new java.util.ArrayList<>(program);
		progv.addAll(LispReader.readAllFromString("(progv '(*x*) '(1) (q))"));
		assertThat(GlobalVarCollector.collectProbedUnbound(progv, progv, globals)).isEmpty();
	}

}
