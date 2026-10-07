package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.SpecialVarCollector;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which globals that are no special a read can reach before their first store, so the
 * compile paths start them as the UNBOUND marker and check their reads: every one whose
 * first top-level occurrence is not an unconditional assignment of it, and every one that
 * something able to run before that assignment reads -- the forms before it, its own
 * values, the defuns their text names closed over their text, and, past an operator that
 * can run code the text does not name, every defun. Every other global keeps the plain
 * variable.
 */
class ReadBeforeStoreTest {

	private static String checked(String source) {
		return checked(source, new ClosRegistry());
	}

	private static String checked(String source, ClosRegistry closRegistry) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return String.join(" ",
				ReadBeforeStore.collect(program, List.of(), SpecialVarCollector.collect(program), closRegistry));
	}

	@Test
	void aGlobalAssignedBeforeAnythingCanReadItKeepsThePlainVariable() {
		assertThat(checked("(setq *a* 0) (defun f () *a*) (print (f))")).isEmpty();
		// A defun's place among the top-level forms is not when it runs: a call is.
		assertThat(checked("(defun f () *a*) (setq *a* 0) (print (f))")).isEmpty();
		// The forms before the store may call functions that do not read it.
		assertThat(checked("(defun f () *a*) (defun g (n) (list n)) (setq *b* (g 1)) (setq *a* 0) (print (f) *b*)"))
			.isEmpty();
		// A lambda in a later form does not exist before the store.
		assertThat(checked("(setq *a* 0) (print (mapcar (lambda (x) (+ x *a*)) '(1)))")).isEmpty();
		// A form that only declares a host function runs nothing.
		assertThat(checked("(rontolisp:wasm-import 'host-now :from \"env\" :as \"now\" :params '() :returns :int)"
				+ " (setq *a* 0) (defun f () *a*) (print (f))"))
			.isEmpty();
		// psetq evaluates every value first; a setq stores its pairs in order.
		assertThat(checked("(defun f () *a*) (psetq *b* 1 *a* 0) (print (f))")).isEmpty();
		assertThat(checked("(defun f () *a*) (setq *a* 0 *b* (f)) (print *b*)")).isEmpty();
	}

	@Test
	void aGlobalReadBeforeItsFirstStoreIsChecked() {
		// From a function the forms before the store call.
		assertThat(checked("(defun f () *a*) (f) (setq *a* 0)")).isEqualTo("*A*");
		assertThat(checked("(defun f () *a*) (defun g () (list (f))) (setq *b* (g)) (setq *a* 0) (print *b*)"))
			.isEqualTo("*A*");
		// By a top-level form before it, or a lambda an earlier form maps.
		assertThat(checked("(print *a*) (setq *a* 0)")).isEqualTo("*A*");
		assertThat(checked("(defun f (xs) (mapcar (lambda (x) (+ x *a*)) xs)) (print (f '(1))) (setq *a* 0)"))
			.isEqualTo("*A*");
		// By the value the store assigns, directly or through a function.
		assertThat(checked("(setq *a* (list *a*))")).isEqualTo("*A*");
		assertThat(checked("(defun f () *a*) (setq *a* (f))")).isEqualTo("*A*");
		assertThat(checked("(defun f () *a*) (setq *b* (f) *a* 0) (print *b*)")).isEqualTo("*A*");
		// Its first store is conditional, or no top-level form assigns it.
		assertThat(checked("(when (> (random 2) 0) (setq *a* 1)) (print *a*)")).isEqualTo("*A*");
		assertThat(checked("(defun s () (setq *a* 1)) (defun r () *a*) (s) (print (r))")).isEqualTo("*A*");
	}

	@Test
	void anOperatorThatCanRunUnnamedCodeMakesEveryFunctionCount() {
		// Printing can run a print-object method, and a method is a defun no text calls:
		// past it, a global any function reads is checked.
		assertThat(checked("(defun f () *a*) (print 1) (setq *a* 0) (print (f))")).isEqualTo("*A*");
		assertThat(checked("(defun f () *a*) (defun g () (print 1)) (g) (setq *a* 0) (print (f))")).isEqualTo("*A*");
		// So can a call of a host function or of a function the program does not define.
		assertThat(checked("(rontolisp:wasm-import 'host-now :from \"env\" :as \"now\" :params '() :returns :int)"
				+ " (defun f () *a*) (host-now) (setq *a* 0) (print (f))"))
			.isEqualTo("*A*");
		assertThat(checked("(defun f () *a*) (funcall 'print 1) (setq *a* 0) (print (f))")).isEqualTo("*A*");
		// A global only top-level forms read stays plain.
		assertThat(checked("(print 1) (setq *a* 0) (print *a*)")).isEmpty();
		// A local function is no call of the built-in of its name.
		assertThat(checked("(defun f () *a*) (flet ((print (x) x)) (print 1)) (setq *a* 0) (print (f))")).isEmpty();
	}

	@Test
	void aFunctionTheTextNamesOnlyIndirectlyIsFollowed() {
		// A type test runs the satisfies predicate of the deftype it names.
		String typeTest = "(defun small-p (x) (< x *a*)) (print (typep 3 'small)) (setq *a* 10)";
		ClosRegistry withDeftype = new ClosRegistry();
		withDeftype.registerDeftype("SMALL", LispReader.readAllFromString("(satisfies small-p)").getFirst());
		assertThat(checked(typeTest, withDeftype)).isEqualTo("*A*");
		assertThat(checked(typeTest.replace("(print (typep 3 'small))", "(setq *b* (typep 3 'small))"), withDeftype))
			.isEqualTo("*A*");
		assertThat(checked(typeTest.replace("(print (typep 3 'small))", "(setq *b* (typep 3 'integer))"), withDeftype))
			.isEmpty();
		// An assignment of a place calls its setf function.
		assertThat(checked("(defun |%setf-BOX| (v b) (list v b *a*)) (setf (box 1) 2) (setq *a* 0) (print *a*)"))
			.isEqualTo("*A*");
		// A function named in quoted data can be called through it.
		assertThat(checked("(defun f () *a*) (setq *b* (funcall 'f)) (setq *a* 0)")).isEqualTo("*A*");
	}

	@Test
	void onlyAVariableTheProgramReadsAsAGlobalIsAsked() {
		// The collectors take a let variable a setq assigns for a global; no read reaches
		// it as one, so it needs no marker.
		assertThat(checked("(let ((x 0)) (setq x 1) (print x))")).isEmpty();
		// Neither does a global nothing reads.
		assertThat(checked("(defun f () (setq *a* 1)) (f)")).isEmpty();
		// A special has the marker of its own where it needs one, a defun nested in a
		// form is a function, not a variable.
		assertThat(checked("(defvar *s*) (defun f () *s*) (print (f))")).isEmpty();
		assertThat(checked("(print (counter)) (let ((n 0)) (defun counter () (setq n (+ n 1))))")).isEmpty();
	}

}
