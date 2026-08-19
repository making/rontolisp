package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter's opt-in {@code --simd} acceleration ({@link VecSimd}): the seven
 * vectorizable {@code vec:} kernels run on {@code jdk.incubator.vector} instead of the
 * scalar {@code vec.lisp} defuns, while the default interpreter keeps the scalar
 * reference (the cross-backend byte-identity oracle).
 *
 * <p>
 * Every kernel is checked against the scalar reference on f64-exact inputs (integers), at
 * both widths and on both sides of the {@code THRESHOLD = 128} lane-loop gate -- so the
 * scalar tail and the vector loop are both exercised, and reduction associativity cannot
 * make the two disagree. Requires {@code --add-modules jdk.incubator.vector} (the
 * surefire {@code argLine} supplies it).
 */
class VecSimdTest {

	private LispVal eval(String input, boolean simd) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSimd(simd);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	/** Asserts that the accelerated result is identical to the scalar reference's. */
	private void assertMatchesScalarOracle(String input) {
		assertThat(eval(input, true).print()).isEqualTo(eval(input, false).print());
	}

	@Test
	void theVectorApiIsAvailableUnderTheSurefireAddModules() {
		assertThat(VecSimd.available()).isTrue();
	}

	// --- interception fired (the "dead flag" guard) ------------------------------

	@Test
	void simdReplacesTheVectorizableDefunsWithNativeFunctions() {
		// A vec.lisp defun is a LispLambda ("#<lambda>"); the installed kernel is a
		// native LispFunction. Without this the flag could be silently dead and every
		// numeric assertion below would still pass on the scalar reference.
		assertThat(eval("(vec:dot #d(1.0) #d(1.0)) #'vec:dot", true).print()).isEqualTo("#<function VEC:DOT>");
		assertThat(eval("(vec:dot #d(1.0) #d(1.0)) #'vec:dot", false).print()).isEqualTo("#<lambda>");
	}

	@Test
	void nonVectorizableVecFunctionsStayOnTheScalarDefuns() {
		// Construction / access / list conversion are untouched by --simd.
		assertThat(eval("(vec:zeros 1) #'vec:from-list", true).print()).isEqualTo("#<lambda>");
		assertThat(eval("(vec:zeros 1) #'vec:aref", true).print()).isEqualTo("#<lambda>");
	}

	// --- reductions --------------------------------------------------------------

	@Test
	void dotAndSumMatchTheScalarOracleBelowTheLaneLoopThreshold() {
		assertMatchesScalarOracle("(vec:dot (vec:arange 7) (vec:arange 7))");
		assertMatchesScalarOracle("(vec:sum (vec:arange 7))");
	}

	@Test
	void dotAndSumMatchTheScalarOracleAboveTheLaneLoopThreshold() {
		// n = 200 > THRESHOLD (128), and not a multiple of any lane count, so the vector
		// loop AND its scalar tail both run.
		assertMatchesScalarOracle("(vec:dot (vec:arange 200) (vec:arange 200))");
		assertMatchesScalarOracle("(vec:sum (vec:arange 200))");
	}

	@Test
	void dotAndSumMatchTheScalarOracleForSingleFloatVectors() {
		// These inputs stay below 2^24, so a single-precision accumulator is exact and
		// they match the scalar oracle. They do NOT pin the accumulator width -- that is
		// what singleFloatReductionsAccumulateInSinglePrecisionUnderSimd below is for.
		assertMatchesScalarOracle(
				"(vec:dot (vec:arange 200 :element-type 'single-float) (vec:arange 200 :element-type 'single-float))");
		assertMatchesScalarOracle("(vec:sum (vec:arange 200 :element-type 'single-float))");
		assertMatchesScalarOracle(
				"(vec:dot (vec:arange 7 :element-type 'single-float) (vec:arange 7 :element-type 'single-float))");
	}

	@Test
	void singleFloatReductionsAccumulateInSinglePrecisionUnderSimd() {
		// The contract that an #f reduction accumulates in single precision under --simd
		// on every backend (.kb/vec.md), and the ONLY test that pins it.
		//
		// v = #f(4096.0 1.0 1.0 ... 1.0), 1024 elements. dot(v,v) = 4096^2 + 1023 =
		// 16778239 exactly. 4096^2 is 2^24, where the f32 spacing is 2, so whichever of
		// the four pinned lanes holds it swallows every 1.0 added to it (16777217 ties to
		// even). The other three lanes each fold 256 ones, giving 2^24 + 768 = 16777984.
		//
		// The value therefore depends on the lane count, which is why the reduction
		// kernels pin FloatVector.SPECIES_128: 8 lanes would print 16778112 and 16 lanes
		// 16778176. All four --simd backends print 16777984; the scalar reference stays
		// the exact oracle. Same story for sum with 2^24 in element 0.
		assertThat(eval(probe32("4096.0", "(vec:dot v v)"), true).print()).isEqualTo("16777984");
		assertThat(eval(probe32("4096.0", "(vec:dot v v)"), false).print()).isEqualTo("16778239");
		assertThat(eval(probe32("16777216.0", "(vec:sum v)"), true).print()).isEqualTo("16777984");
		assertThat(eval(probe32("16777216.0", "(vec:sum v)"), false).print()).isEqualTo("16778239");
		// matvec is a dot per row, so its rows accumulate in f32 too. The scalar path
		// accumulates 16778239 in f64 and narrows on store: an odd multiple of the f32
		// spacing at 2^24, so it ties to even -> 16778240, not 16778239.
		String gemv = """
				(let ((m (make-array '(1 1024) :element-type 'single-float :initial-element 1.0))
				      (v (vec:ones 1024 :element-type 'single-float)))
				  (setf (aref m 0 0) 4096.0)
				  (setf (aref v 0) 4096.0)
				  (round (aref (vec:matvec m v) 0)))
				""";
		assertThat(eval(gemv, true).print()).isEqualTo("16777984");
		assertThat(eval(gemv, false).print()).isEqualTo("16778240");
		// The #d control: double-float reductions are untouched, exact on both paths.
		String probe64 = """
				(let ((v (vec:ones 1024)))
				  (setf (aref v 0) 4096.0)
				  (round (vec:dot v v)))
				""";
		assertThat(eval(probe64, true).print()).isEqualTo("16778239");
		assertThat(eval(probe64, false).print()).isEqualTo("16778239");
	}

	/** A 1024-element {@code #f} vector of ones with {@code elem0} in element 0. */
	private String probe32(String elem0, String reduction) {
		return """
				(let ((v (vec:ones 1024 :element-type 'single-float)))
				  (setf (aref v 0) %s)
				  (round %s))
				""".formatted(elem0, reduction);
	}

	@Test
	void meanAndNormAreAcceleratedTransitivelyThroughSumAndDot() {
		// vec:mean / vec:norm keep their scalar bodies; the vec:sum / vec:dot they call
		// resolve to the installed natives (Lisp-2 global function namespace).
		assertThat(eval("(vec:mean #d(1.0 2.0 3.0 4.0))", true).print()).isEqualTo("2.5");
		assertThat(eval("(vec:norm #d(3.0 4.0))", true).print()).isEqualTo("5.0");
		assertMatchesScalarOracle("(vec:mean (vec:arange 200))");
		assertMatchesScalarOracle("(vec:norm (vec:arange 200))");
	}

	// --- element-wise ------------------------------------------------------------

	@Test
	void elementWiseKernelsMatchTheScalarOracleAtBothSizes() {
		for (String n : new String[] { "7", "200" }) {
			assertMatchesScalarOracle("(vec:add (vec:arange %s) (vec:ones %s))".formatted(n, n));
			assertMatchesScalarOracle("(vec:sub (vec:arange %s) (vec:ones %s))".formatted(n, n));
			assertMatchesScalarOracle("(vec:mul (vec:arange %s) (vec:arange %s))".formatted(n, n));
			assertMatchesScalarOracle("(vec:scale (vec:arange %s) 0.5)".formatted(n));
		}
	}

	@Test
	void elementWiseKernelsPreserveTheOperandWidth() {
		assertThat(eval("(vec:add #d(1.0 2.0) #d(3.0 4.0))", true)).isInstanceOf(LispDoubleFloatArray.class);
		assertThat(eval("(vec:add #f(1.0 2.0) #f(3.0 4.0))", true)).isInstanceOf(LispSingleFloatArray.class);
		assertThat(eval("(vec:scale #f(1.0 2.0) 2)", true).print()).isEqualTo("#f(2.0 4.0)");
		assertMatchesScalarOracle(
				"(vec:mul (vec:arange 200 :element-type 'single-float) (vec:arange 200 :element-type 'single-float))");
	}

	// --- matvec (GEMV) -----------------------------------------------------------

	@Test
	void matvecMatchesTheScalarOracle() {
		assertThat(eval("(vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0))", true).print()).isEqualTo("#d(17.0 39.0)");
		assertMatchesScalarOracle("(vec:matvec #d((1.0 2.0 3.0) (4.0 5.0 6.0)) #d(1.0 2.0 3.0))");
		assertMatchesScalarOracle("(vec:matvec #f((1.0 2.0) (3.0 4.0)) #f(5.0 6.0))");
	}

	@Test
	void matvecMatchesTheScalarOracleWithARowLongerThanTheLaneLoopThreshold() {
		String matrix = """
				(let ((w (make-array '(3 200) :element-type 'double-float :initial-element 0.0))
				      (x (vec:arange 200)))
				  (dotimes (i 3)
				    (dotimes (j 200)
				      (setf (aref w i j) (float (+ (* i 200) j)))))
				  (vec:matvec w x))
				""";
		assertMatchesScalarOracle(matrix);
	}

	@Test
	void matvecRejectsARank1Matrix() {
		assertThatThrownBy(() -> eval("(vec:matvec #d(1.0 2.0) #d(1.0 2.0))", true))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a rank-2 matrix");
	}

	// --- destination-passing kernels ----------------------------------------------

	@Test
	void simdReplacesTheIntoDefunsWithNativeFunctionsToo() {
		assertThat(eval("(vec:zeros 1) #'vec:add-into", true).print()).isEqualTo("#<function VEC:ADD-INTO>");
		assertThat(eval("(vec:zeros 1) #'vec:matvec-into", true).print()).isEqualTo("#<function VEC:MATVEC-INTO>");
		assertThat(eval("(vec:zeros 1) #'vec:add-into", false).print()).isEqualTo("#<lambda>");
	}

	@Test
	void intoKernelsMatchTheirAllocatingSiblingsAtBothSizes() {
		for (String n : new String[] { "7", "200" }) {
			assertIntoMatchesAllocating("(vec:add-into (vec:zeros %s) (vec:arange %s) (vec:ones %s))",
					"(vec:add (vec:arange %s) (vec:ones %s))", n);
			assertIntoMatchesAllocating("(vec:sub-into (vec:zeros %s) (vec:arange %s) (vec:ones %s))",
					"(vec:sub (vec:arange %s) (vec:ones %s))", n);
			assertIntoMatchesAllocating("(vec:mul-into (vec:zeros %s) (vec:arange %s) (vec:arange %s))",
					"(vec:mul (vec:arange %s) (vec:arange %s))", n);
		}
		// scale-into takes a scalar third argument, so it does not fit the pattern above.
		for (String n : new String[] { "7", "200" }) {
			String into = "(vec:scale-into (vec:zeros %s) (vec:arange %s) 0.5)".formatted(n, n);
			String alloc = "(vec:scale (vec:arange %s) 0.5)".formatted(n);
			assertThat(eval(into, true).print()).isEqualTo(eval(alloc, false).print());
			assertMatchesScalarOracle(into);
		}
	}

	@Test
	void intoKernelsPreserveTheDestinationWidth() {
		assertThat(
				eval("(vec:add-into (vec:zeros 2 :element-type 'single-float) #f(1.0 2.0) #f(3.0 4.0))", true).print())
			.isEqualTo("#f(4.0 6.0)");
		assertMatchesScalarOracle(
				"(vec:mul-into (vec:zeros 200 :element-type 'single-float) (vec:arange 200 :element-type 'single-float) (vec:arange 200 :element-type 'single-float))");
		assertMatchesScalarOracle(
				"(vec:scale-into (vec:zeros 200 :element-type 'single-float) (vec:arange 200 :element-type 'single-float) 0.5)");
	}

	@Test
	void anIntoKernelReturnsTheVeryDestinationItWasGiven() {
		// (eq out (vec:add-into out a b)) -- the natives must return args.get(0), not a
		// fresh wrapper around the same backing array, or in-place accumulation loops
		// silently rebind to a different value each iteration.
		assertThat(eval("(let ((o (vec:zeros 2))) (eq o (vec:add-into o #d(1.0 2.0) #d(3.0 4.0))))", true).print())
			.isEqualTo("T");
		assertThat(eval("(let ((o (vec:zeros 2))) (eq o (vec:scale-into o #d(1.0 2.0) 2.0)))", true).print())
			.isEqualTo("T");
	}

	@Test
	void anElementWiseIntoKernelToleratesAliasingItsDestinationWithAnOperand() {
		// (vec:add-into acc acc d) -- element i depends only on element i, so in-place
		// accumulation is well-defined at both sizes (vector loop and scalar tail).
		for (String n : new String[] { "7", "200" }) {
			String inPlace = """
					(let ((acc (vec:zeros %s)) (d (vec:arange %s)))
					  (dotimes (i 4) (vec:add-into acc acc d))
					  acc)
					""".formatted(n, n);
			String fresh = """
					(let ((acc (vec:zeros %s)) (d (vec:arange %s)))
					  (dotimes (i 4) (setq acc (vec:add acc d)))
					  acc)
					""".formatted(n, n);
			assertThat(eval(inPlace, true).print()).isEqualTo(eval(fresh, false).print());
		}
	}

	@Test
	void matvecIntoMatchesTheScalarOracleAtBothSizes() {
		assertThat(eval("(vec:matvec-into (vec:zeros 2) #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0))", true).print())
			.isEqualTo("#d(17.0 39.0)");
		assertMatchesScalarOracle(
				"(vec:matvec-into (vec:zeros 2 :element-type 'single-float) #f((1.0 2.0) (3.0 4.0)) #f(5.0 6.0))");
		String wideRows = """
				(let ((w (make-array '(3 200) :element-type 'double-float :initial-element 0.0))
				      (x (vec:arange 200))
				      (o (vec:zeros 3)))
				  (dotimes (i 3)
				    (dotimes (j 200)
				      (setf (aref w i j) (float (+ (* i 200) j)))))
				  (vec:matvec-into o w x))
				""";
		assertMatchesScalarOracle(wideRows);
	}

	@Test
	void matvecIntoRejectsADestinationAliasingItsOperands() {
		// out[row] folds over ALL of x, so storing it would clobber an element a later
		// row still has to read. Checked on BOTH the accelerated and the scalar path --
		// the natives replace the vec.lisp defun that carries the eq guard.
		for (boolean simd : new boolean[] { true, false }) {
			assertThatThrownBy(
					() -> eval("(let ((x #d(1.0 2.0))) (vec:matvec-into x #d((1.0 1.0) (1.0 1.0)) x))", simd))
				.isInstanceOf(LispEvalException.class)
				.hasMessageContaining("must not be the same");
		}
	}

	@Test
	void mixingWidthsInAnIntoKernelIsAnError() {
		assertThatThrownBy(() -> eval("(vec:add-into (vec:zeros 1) #d(1.0) #f(1.0))", true))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("must share an element type");
		assertThatThrownBy(() -> eval("(vec:add-into (vec:zeros 1 :element-type 'single-float) #d(1.0) #d(1.0))", true))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("must share an element type");
	}

	/** Asserts an -into call (accelerated) equals its allocating sibling (scalar). */
	private void assertIntoMatchesAllocating(String intoFormat, String allocFormat, String n) {
		String into = intoFormat.replace("%s", n);
		String alloc = allocFormat.replace("%s", n);
		assertThat(eval(into, true).print()).isEqualTo(eval(alloc, false).print());
		assertMatchesScalarOracle(into);
	}

	// --- element-wise unary ufuncs -------------------------------------------------

	@Test
	void simdReplacesTheUnaryUfuncDefunsWithNativeFunctions() {
		for (String member : new String[] { "exp", "log", "tanh", "sin", "cos", "tan", "asin", "acos", "atan", "sinh",
				"cosh", "sqrt", "abs", "negative", "sign", "reciprocal", "exp-into", "log-into", "tanh-into",
				"sin-into", "cos-into", "tan-into", "asin-into", "acos-into", "atan-into", "sinh-into", "cosh-into",
				"sqrt-into", "abs-into", "negative-into", "sign-into", "reciprocal-into" }) {
			String form = "(vec:zeros 1) #'vec:" + member;
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function VEC:" + member.toUpperCase(java.util.Locale.ROOT) + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	@Test
	void squareIsAcceleratedTransitivelyThroughMul() {
		// vec:square's defun body is (vec:mul v v), which resolves to the installed
		// native -- like mean/norm. So there is no square kernel and no override.
		assertThat(eval("(vec:zeros 1) #'vec:square", true).print()).isEqualTo("#<lambda>");
		assertThat(eval("(vec:zeros 1) #'vec:square-into", true).print()).isEqualTo("#<lambda>");
		assertMatchesScalarOracle("(vec:square (vec:arange 200))");
		assertMatchesScalarOracle(
				"(vec:square-into (vec:zeros 200 :element-type 'single-float) (vec:arange 200 :element-type 'single-float))");
	}

	@Test
	void unaryUfuncsMatchTheScalarOracleAtBothSizesAndWidths() {
		// mixed signs (arange - 100), sizes on both sides of THRESHOLD, both widths.
		// exp runs over reciprocal's (0, 1] range so the values stay bounded.
		for (String op : new String[] { "sqrt", "abs", "square", "negative", "sign", "reciprocal" }) {
			for (String n : new String[] { "7", "200" }) {
				String signed = "(vec:%s (vec:sub (vec:arange %s) (vec:scale (vec:ones %s) 100.0)))".formatted(op, n,
						n);
				if (!op.equals("sqrt")) {
					assertMatchesScalarOracle(signed);
				}
				assertMatchesScalarOracle("(vec:%s (vec:add (vec:arange %s) (vec:ones %s)))".formatted(op, n, n));
			}
			assertMatchesScalarOracle(
					"(vec:%s (vec:add (vec:arange 200 :element-type 'single-float) (vec:ones 200 :element-type 'single-float)))"
						.formatted(op));
		}
		assertMatchesScalarOracle("(vec:exp (vec:reciprocal (vec:add (vec:arange 200) (vec:ones 200))))");
		assertMatchesScalarOracle("(vec:exp (vec:reciprocal (vec:add (vec:arange 7) (vec:ones 7))))");
		assertMatchesScalarOracle(
				"(vec:exp (vec:reciprocal (vec:add (vec:arange 200 :element-type 'single-float) (vec:ones 200 :element-type 'single-float))))");
		// log over strictly positive inputs, tanh over the signed range (both are
		// Math.log / Math.tanh scalar loops on this backend).
		for (String n : new String[] { "7", "200" }) {
			assertMatchesScalarOracle("(vec:log (vec:add (vec:arange %s) (vec:ones %s)))".formatted(n, n));
			assertMatchesScalarOracle(
					"(vec:tanh (vec:scale (vec:sub (vec:arange %s) (vec:scale (vec:ones %s) 100.0)) 0.03))".formatted(n,
							n));
		}
		assertMatchesScalarOracle(
				"(vec:log (vec:add (vec:arange 200 :element-type 'single-float) (vec:ones 200 :element-type 'single-float)))");
		assertMatchesScalarOracle("(vec:tanh (vec:arange 200 :element-type 'single-float))");
		// sin / cos / tan over the signed range (Math.sin / Math.cos / Math.tan
		// scalar loops on this backend).
		for (String op : new String[] { "sin", "cos", "tan" }) {
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarOracle(
						"(vec:%s (vec:sub (vec:arange %s) (vec:scale (vec:ones %s) 100.0)))".formatted(op, n, n));
			}
			assertMatchesScalarOracle("(vec:%s (vec:arange 200 :element-type 'single-float))".formatted(op));
		}
		// asin / acos over the scaled [-0.5, 0.5) domain, atan / sinh / cosh over the
		// sign-mixed range (Math.asin / Math.acos / Math.atan / Math.sinh / Math.cosh
		// scalar loops on this backend).
		for (String op : new String[] { "asin", "acos" }) {
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarOracle(
						"(vec:%s (vec:scale (vec:sub (vec:arange %s) (vec:scale (vec:ones %s) 100.0)) 0.005))"
							.formatted(op, n, n));
			}
			assertMatchesScalarOracle(
					"(vec:%s (vec:scale (vec:arange 200 :element-type 'single-float) 0.005))".formatted(op));
		}
		for (String op : new String[] { "atan", "sinh", "cosh" }) {
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarOracle(
						"(vec:%s (vec:scale (vec:sub (vec:arange %s) (vec:scale (vec:ones %s) 100.0)) 0.05))"
							.formatted(op, n, n));
			}
			assertMatchesScalarOracle(
					"(vec:%s (vec:scale (vec:arange 200 :element-type 'single-float) 0.05))".formatted(op));
		}
		assertMatchesScalarOracle("(vec:asin #d(0.0 -0.0 1.0 -1.0 0.5))");
		assertMatchesScalarOracle("(vec:acos #d(1.0 -1.0 0.0 0.5))");
		assertMatchesScalarOracle("(vec:atan (vec:reciprocal #d(0.0 -0.0)))");
		assertMatchesScalarOracle("(vec:sinh #d(0.0 -0.0 0.25 -0.25 0.3))");
		assertMatchesScalarOracle("(vec:cosh #d(0.0 -0.0 1.0))");
	}

	@Test
	void unaryUfuncsMatchTheScalarOracleOnSignedZeroEdges() {
		// Math.abs / true negation / Math.signum on both paths, so the -0.0 edges agree
		// with the defun (which is the per-backend contract; the WASM defun's edges
		// differ from these and its kernels mirror those instead).
		assertMatchesScalarOracle("(vec:negative #d(0.0 -0.0 1.0))");
		assertMatchesScalarOracle("(vec:abs #d(-0.0 0.0 -2.5))");
		assertMatchesScalarOracle("(vec:sign #d(-0.0 0.0 -3.5 3.5))");
		assertThat(eval("(vec:negative #d(0.0))", true).print()).isEqualTo("#d(-0.0)");
		assertThat(eval("(vec:sign #d(-0.0))", true).print()).isEqualTo("#d(-0.0)");
	}

	@Test
	void unaryIntoKernelsMatchTheirAllocatingSiblingsAndReturnTheDestination() {
		for (String op : new String[] { "exp", "log", "tanh", "sin", "cos", "tan", "asin", "acos", "atan", "sinh",
				"cosh", "sqrt", "abs", "negative", "sign", "reciprocal" }) {
			for (String n : new String[] { "7", "200" }) {
				assertIntoMatchesAllocating(
						"(vec:" + op + "-into (vec:zeros %s) (vec:add (vec:arange %s) (vec:ones %s)))",
						"(vec:" + op + " (vec:add (vec:arange %s) (vec:ones %s)))", n);
			}
			assertThat(eval("(let ((o (vec:zeros 2))) (eq o (vec:" + op + "-into o #d(1.0 2.0))))", true).print())
				.as(op)
				.isEqualTo("T");
		}
		assertMatchesScalarOracle(
				"(vec:sqrt-into (vec:zeros 200 :element-type 'single-float) (vec:add (vec:arange 200 :element-type 'single-float) (vec:ones 200 :element-type 'single-float)))");
	}

	@Test
	void aUnaryIntoKernelToleratesAliasingItsDestinationWithTheOperand() {
		// (vec:sqrt-into v v) -- element i depends only on element i (the add-into
		// rule), so the in-place update is well-defined at both sizes.
		for (String n : new String[] { "7", "200" }) {
			String inPlace = """
					(let ((v (vec:add (vec:arange %s) (vec:ones %s))))
					  (vec:sqrt-into v v)
					  v)
					""".formatted(n, n);
			String fresh = "(vec:sqrt (vec:add (vec:arange %s) (vec:ones %s)))".formatted(n, n);
			assertThat(eval(inPlace, true).print()).isEqualTo(eval(fresh, false).print());
		}
	}

	@Test
	void mixingWidthsInAUnaryIntoKernelIsAnError() {
		assertThatThrownBy(() -> eval("(vec:sqrt-into (vec:zeros 1) #f(1.0))", true))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("must share an element type");
	}

	// --- comparison-select ufuncs --------------------------------------------------

	@Test
	void simdReplacesTheComparisonSelectDefunsWithNativeFunctions() {
		for (String member : new String[] { "maximum", "minimum", "relu", "clip", "maximum-into", "minimum-into",
				"relu-into", "clip-into" }) {
			String form = "(vec:zeros 1) #'vec:" + member;
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function VEC:" + member.toUpperCase(java.util.Locale.ROOT) + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	@Test
	void comparisonSelectsMatchTheScalarOracleAtBothSizesAndWidths() {
		// a ascends through zero, b is its mirror image, so the winner flips
		// mid-vector; sizes on both sides of THRESHOLD, both widths (all inputs
		// integer-valued, so every intermediate is exact at either width).
		for (String op : new String[] { "maximum", "minimum" }) {
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarOracle(
						"(let ((a (vec:sub (vec:arange %1$s) (vec:scale (vec:ones %1$s) 100.0)))) (vec:%2$s a (vec:negative a)))"
							.formatted(n, op));
			}
			assertMatchesScalarOracle(
					"(let ((a (vec:sub (vec:arange 200 :element-type 'single-float) (vec:scale (vec:ones 200 :element-type 'single-float) 100.0)))) (vec:%s a (vec:negative a)))"
						.formatted(op));
		}
		for (String n : new String[] { "7", "200" }) {
			assertMatchesScalarOracle(
					"(vec:relu (vec:sub (vec:arange %1$s) (vec:scale (vec:ones %1$s) 100.0)))".formatted(n));
			assertMatchesScalarOracle(
					"(vec:clip (vec:sub (vec:arange %1$s) (vec:scale (vec:ones %1$s) 100.0)) -50.0 50.0)".formatted(n));
		}
		assertMatchesScalarOracle(
				"(vec:relu (vec:sub (vec:arange 200 :element-type 'single-float) (vec:scale (vec:ones 200 :element-type 'single-float) 100.0)))");
		// The f32 clip bounds are deliberately NOT f32-representable: the kernel must
		// compare the widened element against the full double bound, like the defun.
		assertMatchesScalarOracle(
				"(vec:clip (vec:sub (vec:arange 200 :element-type 'single-float) (vec:scale (vec:ones 200 :element-type 'single-float) 100.0)) -50.3 50.3)");
	}

	@Test
	void comparisonSelectsFollowTheStrictComparisonNotMathMax() {
		// The contract: (if (> x y) x y) and its mirrors -- the SECOND operand (or the
		// bound) wins any false comparison, unlike Math.max/Math.min (which propagate a
		// NaN from either side and order -0.0 below 0.0).
		assertMatchesScalarOracle("(vec:maximum #d(-0.0 0.0) #d(0.0 -0.0))");
		assertMatchesScalarOracle("(vec:minimum #d(-0.0 0.0) #d(0.0 -0.0))");
		assertThat(eval("(vec:maximum #d(-0.0) #d(0.0))", true).print()).isEqualTo("#d(0.0)");
		assertThat(eval("(vec:maximum #d(0.0) #d(-0.0))", true).print()).isEqualTo("#d(-0.0)");
		assertThat(eval("(vec:minimum #d(0.0) #d(-0.0))", true).print()).isEqualTo("#d(-0.0)");
		// NaN: (> NaN y) is false, so maximum(NaN, y) takes y; (> x NaN) is false, so
		// maximum(x, NaN) keeps the NaN.
		assertMatchesScalarOracle("(vec:maximum (vec:scale (vec:ones 3) (/ 0.0 0.0)) #d(1.0 2.0 3.0))");
		assertMatchesScalarOracle("(vec:maximum #d(1.0 2.0 3.0) (vec:scale (vec:ones 3) (/ 0.0 0.0)))");
		assertThat(eval("(vec:maximum (vec:scale (vec:ones 1) (/ 0.0 0.0)) #d(7.0))", true).print())
			.isEqualTo("#d(7.0)");
		assertThat(eval("(vec:maximum #d(7.0) (vec:scale (vec:ones 1) (/ 0.0 0.0)))", true).print())
			.isEqualTo("#d(NaN)");
		// relu: -0.0 and NaN both fall to the 0.0 arm.
		assertMatchesScalarOracle("(vec:relu #d(-0.0 0.0))");
		assertThat(eval("(vec:relu #d(-0.0))", true).print()).isEqualTo("#d(0.0)");
		assertThat(eval("(vec:relu (vec:scale (vec:ones 1) (/ 0.0 0.0)))", true).print()).isEqualTo("#d(0.0)");
		// clip: a NaN element becomes lo (the first select's comparison is false), and
		// inverted bounds (lo > hi) end at hi -- the min(max(x, lo), hi) composition.
		assertMatchesScalarOracle("(vec:clip (vec:scale (vec:ones 2) (/ 0.0 0.0)) -1.0 1.0)");
		assertThat(eval("(vec:clip (vec:scale (vec:ones 1) (/ 0.0 0.0)) -1.0 1.0)", true).print())
			.isEqualTo("#d(-1.0)");
		assertMatchesScalarOracle("(vec:clip #d(0.0 3.0 -3.0) 2.0 1.0)");
	}

	@Test
	void comparisonSelectIntoKernelsMatchTheirAllocatingSiblings() {
		for (String n : new String[] { "7", "200" }) {
			String operands = "(vec:sub (vec:arange %1$s) (vec:scale (vec:ones %1$s) 100.0))".formatted(n);
			for (String op : new String[] { "maximum", "minimum" }) {
				String into = "(vec:%s-into (vec:zeros %s) %s (vec:negative %s))".formatted(op, n, operands, operands);
				String alloc = "(vec:%s %s (vec:negative %s))".formatted(op, operands, operands);
				assertThat(eval(into, true).print()).as(op + " n=" + n).isEqualTo(eval(alloc, true).print());
			}
			assertThat(eval("(vec:relu-into (vec:zeros %s) %s)".formatted(n, operands), true).print())
				.isEqualTo(eval("(vec:relu %s)".formatted(operands), true).print());
			assertThat(eval("(vec:clip-into (vec:zeros %s) %s -50.0 50.0)".formatted(n, operands), true).print())
				.isEqualTo(eval("(vec:clip %s -50.0 50.0)".formatted(operands), true).print());
		}
		// -into returns the very destination and tolerates aliasing (the add-into rule).
		assertThat(eval("(let ((o (vec:zeros 2))) (eq o (vec:maximum-into o #d(1.0 2.0) #d(2.0 1.0))))", true).print())
			.isEqualTo("T");
		assertThat(eval("(let ((v (vec:from-list '(-1.0 2.0)))) (vec:relu-into v v) v)", true).print())
			.isEqualTo(eval("(vec:relu (vec:from-list '(-1.0 2.0)))", false).print());
		assertThat(eval("(let ((v (vec:from-list '(-9.0 9.0)))) (vec:clip-into v v -1.0 1.0) v)", true).print())
			.isEqualTo("#d(-1.0 1.0)");
	}

	// --- fixed-width contract ----------------------------------------------------

	@Test
	void mixingSingleAndDoubleFloatOperandsIsAnError() {
		assertThatThrownBy(() -> eval("(vec:add #d(1.0) #f(1.0))", true)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("must share an element type");
		assertThatThrownBy(() -> eval("(vec:dot #f(1.0) #d(1.0))", true)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("must share an element type");
	}

	@Test
	void aNonPackedArgumentIsAClearError() {
		assertThatThrownBy(() -> eval("(vec:sum '(1 2 3))", true)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a packed float array");
	}

}
