package am.ik.rontolisp.compiler;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which functions a {@code defun} below the top level defines live in the run-time
 * function namespace: those a name resolved at run time can reach -- a data evaluator, a
 * quoted symbol held as a value, {@code fmakunbound}'s, a {@code symbol-function} place,
 * and a string beside a symbol builder. A program whose every reference the compile
 * resolves keeps the global.
 */
class NestedDefunNamespaceTest {

	private static String held(String source) {
		return String.join(" ", NestedDefunNamespace.heldNames(LispReader.readAllFromString(source)));
	}

	@Test
	void aProgramWhoseReferencesTheCompileResolvesKeepsTheGlobal() {
		assertThat(held("(let () (defun f () 1)) (print (list (f) (fboundp 'f) (funcall 'f) (apply 'f nil) #'f"
				+ " (symbol-function 'f) (fdefinition 'f)))"))
			.isEmpty();
		// A string is no name without a symbol builder, and a retirement of another name
		// does not reach this one.
		assertThat(held("(let () (defun f () 1)) (print \"F\") (fmakunbound 'g)")).isEmpty();
		// A top-level defun is no nested one.
		assertThat(held("(defun f () 1) (eval '(f))")).isEmpty();
	}

	@Test
	void aRunTimeNameOfTheFunctionMovesItIntoTheNamespace() {
		assertThat(held("(let () (defun f () 1) (defun g () 2)) (fmakunbound 'f)")).isEqualTo("F");
		assertThat(held("(let () (defun f () 1)) (let ((s 'f)) (funcall s))")).isEqualTo("F");
		assertThat(held("(let () (defun f () 1)) (mapcar #'funcall '(f))")).isEqualTo("F");
		assertThat(held("(let () (defun f () 1)) (funcall (intern \"F\"))")).isEqualTo("F");
		assertThat(held("(let () (defun f () 1)) (setf (symbol-function 'f) #'car)")).isEqualTo("F");
		// A (setf name) writer is reached through its place.
		assertThat(held("(let () (defun |%setf-F| (v) v)) (fdefinition (list 'setf (intern \"F\")))"))
			.isEqualTo("%setf-F");
		// Data an evaluator runs can spell any of them.
		assertThat(held("(let () (defun f () 1) (defun g () 2)) (eval (read))")).isEqualTo("F G");
	}

	@Test
	void aMethodBodyAnEnclosedDefmethodDefinesKeepsItsGlobal() {
		// The generic's dispatcher reads the body's global and tests it for the method
		// having been added.
		assertThat(held("(let () (defun |%AREA--m0| (x) x)) (eval (read))")).isEmpty();
	}

}
