package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicIntegerArray;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter's {@code --parallel}: {@link SimdParallel}'s row dispatch, and the
 * {@code --simd} matrix products run through it ({@link VecSimd} / {@link LinalgSimd}
 * with the flag) against the same products without it. The contract is bit-identity --
 * the rows are independent chains -- so every kernel case compares printed results of the
 * same program evaluated both ways, at shapes above the work threshold and over inexact
 * data. Requires {@code --add-modules jdk.incubator.vector} (the surefire {@code argLine}
 * supplies it).
 */
class SimdParallelTest {

	private LispVal eval(String input, boolean parallel) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSimd(true);
		evaluator.setParallel(parallel);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private void assertMatchesSerial(String input) {
		String serial = eval(input, false).print();
		assertThat(serial).as("the program answers something").isNotEmpty();
		assertThat(eval(input, true).print()).as(input).isEqualTo(serial);
	}

	private static String inexact(int rows, int cols, String option) {
		return """
				(defparameter *w* (linalg:reshape (linalg:sqrt (linalg:arange 1 %d%s)) '(%d %d)))
				(defparameter *x* (linalg:sqrt (linalg:arange 2 %d%s)))
				""".formatted(rows * cols + 1, option, rows, cols, cols + 2, option);
	}

	private static final String DOUBLE = "";

	private static final String SINGLE = " :element-type 'single-float";

	@Test
	void theRowDispatchCoversEveryRowExactlyOnceAndJoinsBeforeReturning() {
		// Enough rows and work to pass the threshold and split into many leaves; every
		// row is counted once, by whichever thread, and all of them before rows()
		// returns.
		int rows = 10000;
		AtomicIntegerArray seen = new AtomicIntegerArray(rows);
		assertThat(SimdParallel.worth(rows, 1000)).isTrue();
		SimdParallel.rows(rows, 1000, (from, to) -> {
			for (int r = from; r < to; r++) {
				seen.incrementAndGet(r);
			}
		});
		for (int r = 0; r < rows; r++) {
			assertThat(seen.get(r)).as("row " + r).isEqualTo(1);
		}
	}

	@Test
	void aLeafThatFailsSurfacesOnTheCallerInsteadOfHanging() {
		assertThatThrownBy(() -> SimdParallel.rows(10000, 1000, (from, to) -> {
			if (from > 0) {
				throw new IllegalStateException("leaf " + from);
			}
		})).isInstanceOf(IllegalStateException.class).hasMessageStartingWith("leaf ");
	}

	@Test
	void tooLittleWorkOrOneRowIsNotWorthSplitting() {
		assertThat(SimdParallel.worth(128, 128)).isFalse();
		assertThat(SimdParallel.worth(1, 1 << 20)).isFalse();
		assertThat(SimdParallel.worth(288, 288)).isEqualTo(SimdParallel.threads() > 1);
	}

	@Test
	void theMatrixByVectorProductsAreBitIdenticalToTheSerialKernels() {
		for (String option : new String[] { DOUBLE, SINGLE }) {
			assertMatchesSerial(inexact(600, 300, option) + "(linalg:to-list (vec:matvec *w* *x*))");
			assertMatchesSerial(inexact(4000, 130, option) + "(linalg:to-list (vec:matvec *w* *x*))");
			assertMatchesSerial(inexact(600, 300, option) + "(linalg:to-list (linalg:dot *w* *x*))");
			assertMatchesSerial(inexact(600, 300, option) + """
					(defparameter *out* (linalg:zeros '(600)%s))
					(vec:matvec-into *out* *w* *x*)
					(linalg:to-list *out*)
					""".formatted(option));
		}
	}

	@Test
	void theMatrixProductsAreBitIdenticalToTheSerialKernels() {
		for (String option : new String[] { DOUBLE, SINGLE }) {
			assertMatchesSerial(inexact(600, 300, option) + """
					(defparameter *b* (linalg:reshape (linalg:sqrt (linalg:arange 3 %d%s)) '(600 40)))
					(linalg:to-list (linalg:dot (linalg:transpose *w*) *b*))
					""".formatted(600 * 40 + 3, option));
			assertMatchesSerial(inexact(400, 300, option) + """
					(defparameter *b* (linalg:reshape (linalg:sqrt (linalg:arange 3 %d%s)) '(300 40)))
					(linalg:to-list (linalg:flatten (linalg:matmul (linalg:reshape *w* '(8 50 300)) *b*)))
					""".formatted(300 * 40 + 3, option));
		}
	}

	@Test
	void theFlagIsInertWithoutSimdAndRebindsNothingElse() {
		// --parallel modifies the --simd natives; the reductions keep their serial
		// bindings and values, and a small shape stays serial.
		assertMatchesSerial("(vec:sum (vec:arange 1000))");
		assertMatchesSerial("(vec:matvec #d((1 2 3) (4 5 6)) #d(1 2 3))");
		LispEvaluator scalar = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		scalar.setParallel(true);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString("(vec:zeros 1) #'vec:matvec")) {
			result = scalar.eval(expr);
		}
		assertThat(result.print()).isEqualTo("#<lambda>");
	}

}
