package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionNameUsesTest {

	private static final Set<String> READERS = Set.of("SET", "SYMBOL-VALUE", "FBOUNDP");

	@Test
	void aCallAReferenceADesignatorOrAPlaceIsAUseAndAVariableIsNot() {
		for (String source : List.of("(set 'x 1)", "(defun f (set) (set set 1))", "(funcall #'set 'x 1)",
				"(funcall 'fboundp 'car)", "(apply (car '(set)) args)", "(setf (symbol-value 'x) 1)",
				"(incf (symbol-value v))", "(let ((y (fboundp 'car))) y)", "(defmacro m (x) (list 'symbol-value x))",
				"(lambda (&optional (a (set 'x 1))) a)")) {
			assertThat(FunctionNameUses.any(LispReader.readAllFromString(source), READERS)).as(source).isTrue();
		}
		for (String source : List.of("(defun a (c set) (list c set))", "(defun a (set) set)",
				"(let ((set 1) (symbol-value 2)) (+ set symbol-value))", "(lambda (&optional (fboundp t)) fboundp)",
				"(dolist (set l) (print set))", "(multiple-value-bind (set b) (f) (list set b))", "(case k (set 1))",
				"(handler-case (f) (error (set) set))")) {
			assertThat(FunctionNameUses.any(LispReader.readAllFromString(source), READERS)).as(source).isFalse();
		}
	}

}
