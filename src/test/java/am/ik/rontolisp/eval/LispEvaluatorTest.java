package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LispEvaluatorTest {

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		return evaluator.eval(LispReader.readFromString(input));
	}

	private LispVal evalMulti(String input) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	@Test
	void evalInteger() {
		assertThat(eval("(+ 1 2)")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalSubtraction() {
		assertThat(eval("(- 10 3)")).isEqualTo(new LispInteger(7));
	}

	@Test
	void evalMultiplication() {
		assertThat(eval("(* 3 4)")).isEqualTo(new LispInteger(12));
	}

	@Test
	void evalDivision() {
		assertThat(eval("(/ 10 3)")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalMod() {
		assertThat(eval("(mod 10 3)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalNestedArithmetic() {
		assertThat(eval("(+ (* 3 4) (- 10 5))")).isEqualTo(new LispInteger(17));
	}

	@Test
	void evalMultiArgAdd() {
		assertThat(eval("(+ 1 2 3 4)")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalIfTrue() {
		assertThat(eval("(if t 1 2)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalIfFalse() {
		assertThat(eval("(if nil 1 2)")).isEqualTo(new LispInteger(2));
	}

	@Test
	void evalIfNoElse() {
		assertThat(eval("(if nil 1)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalQuote() {
		LispVal result = eval("(quote (1 2 3))");
		assertThat(result.print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalLet() {
		assertThat(eval("(let ((x 10) (y 20)) (+ x y))")).isEqualTo(new LispInteger(30));
	}

	@Test
	void evalDefunAndCall() {
		assertThat(evalMulti("(defun square (x) (* x x)) (square 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalLambda() {
		assertThat(eval("((lambda (x) (* x x)) 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalProgn() {
		assertThat(eval("(progn 1 2 3)")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalSetq() {
		assertThat(eval("(progn (setq x 10) x)")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalRecursion() {
		assertThat(evalMulti("(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1))))) (fact 5)"))
			.isEqualTo(new LispInteger(120));
	}

	@Test
	void evalComparison() {
		assertThat(eval("(= 1 1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(< 1 2)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(> 2 1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(<= 1 1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(>= 2 1)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalPrint() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(print 42)"));
		assertThat(baos.toString()).contains("42");
	}

	@Test
	void evalSetqLambdaAndCall() {
		assertThat(evalMulti("(setq square (lambda (x) (* x x))) (square 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalNullPredicate() {
		assertThat(eval("(null nil)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(null 1)")).isSameAs(LispNil.INSTANCE);
	}

}
