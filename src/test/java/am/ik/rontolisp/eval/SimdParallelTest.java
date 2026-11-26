package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerArray;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.Concurrently;
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

	private void assertMatchesSerial(String... inputs) {
		// Every program's two runs at once: each builds its own evaluator, and the
		// row dispatch takes one call at a time whoever calls it, so what runs beside a
		// --parallel product is only another program's (interpreted) operand setup.
		List<Callable<String>> runs = new ArrayList<>();
		for (String input : inputs) {
			runs.add(() -> eval(input, false).print());
			runs.add(() -> eval(input, true).print());
		}
		List<String> printed = Concurrently.all(runs);
		for (int i = 0; i < inputs.length; i++) {
			String serial = printed.get(2 * i);
			assertThat(serial).as("the program answers something").isNotEmpty();
			assertThat(printed.get(2 * i + 1)).as(inputs[i]).isEqualTo(serial);
		}
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
	void theDefaultThreadCountIsHalfTheBoxAndNeverFillsIt() {
		// A pool as wide as the machine buys nothing and makes every call wait on the
		// slowest of as many threads as the box can run: half is the measured default
		// (.kb/simd-parallel.md), and the emitted JVM twin computes the same number
		// (JvmSimdParallelCompilerTest#theEmittedPoolDefaultsToHalfTheBoxToo).
		int cpus = Runtime.getRuntime().availableProcessors();
		assertThat(SimdParallel.defaultThreads()).isEqualTo(Math.min(cpus, Math.max(2, cpus / 2)));
		if (cpus >= 4) {
			assertThat(SimdParallel.defaultThreads()).isLessThan(cpus);
		}
		// Two on any box that has two, so --parallel is never silently serial where it
		// has something to split.
		assertThat(SimdParallel.defaultThreads()).isGreaterThanOrEqualTo(Math.min(2, cpus));
	}

	@Test
	void tooLittleWorkOrOneRowIsNotWorthSplitting() {
		assertThat(SimdParallel.worth(128, 128)).isFalse();
		assertThat(SimdParallel.worth(1, 1 << 20)).isFalse();
		assertThat(SimdParallel.worth(288, 288)).isEqualTo(SimdParallel.threads() > 1);
	}

	@Test
	void theMatrixByVectorProductsAreBitIdenticalToTheSerialKernels() {
		List<String> programs = new ArrayList<>();
		for (String option : new String[] { DOUBLE, SINGLE }) {
			programs.add(inexact(600, 300, option) + "(linalg:to-list (vec:matvec *w* *x*))");
			programs.add(inexact(4000, 130, option) + "(linalg:to-list (vec:matvec *w* *x*))");
			programs.add(inexact(600, 300, option) + "(linalg:to-list (linalg:dot *w* *x*))");
			programs.add(inexact(600, 300, option) + """
					(defparameter *out* (linalg:zeros '(600)%s))
					(vec:matvec-into *out* *w* *x*)
					(linalg:to-list *out*)
					""".formatted(option));
		}
		assertMatchesSerial(programs.toArray(String[]::new));
	}

	@Test
	void theMatrixProductsAreBitIdenticalToTheSerialKernels() {
		List<String> programs = new ArrayList<>();
		for (String option : new String[] { DOUBLE, SINGLE }) {
			programs.add(inexact(600, 300, option) + """
					(defparameter *b* (linalg:reshape (linalg:sqrt (linalg:arange 3 %d%s)) '(600 40)))
					(linalg:to-list (linalg:dot (linalg:transpose *w*) *b*))
					""".formatted(600 * 40 + 3, option));
			programs.add(inexact(400, 300, option) + """
					(defparameter *b* (linalg:reshape (linalg:sqrt (linalg:arange 3 %d%s)) '(300 40)))
					(linalg:to-list (linalg:flatten (linalg:matmul (linalg:reshape *w* '(8 50 300)) *b*)))
					""".formatted(300 * 40 + 3, option));
		}
		assertMatchesSerial(programs.toArray(String[]::new));
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
		// The defun's own name tag -- identical to what the lane kernel prints, since
		// defuns carry names now; the pair is told apart by type, not text.
		assertThat(result.print()).isEqualTo("#<function VEC:MATVEC>");
	}

}
