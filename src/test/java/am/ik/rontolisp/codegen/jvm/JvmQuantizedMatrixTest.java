package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The block-quantized weight matrix on the JVM backend ({@code .kb/quantized-matrix.md}):
 * a bare {@code byte[]} of ggml blocks behind an int header, reached through the
 * {@code _qm*} helpers ({@link JvmQuantizedMatrixRuntimeBuilder}) and the {@code byte[]}
 * arms of the {@code _fv*} tier. Every case runs on BOTH backends and compares their
 * output -- a header-offset mistake in hand-written bytecode is a plausible value, not an
 * exception, and only the interpreter (which has no header) catches it -- then some
 * assert the agreed text against a hand-written expectation.
 *
 * <p>
 * The {@code --simd} section pins the compiled integer-dot kernel against the
 * interpreter's scalar defun bit for bit, serially and under {@code --parallel}; the
 * shape past 32767 rows and columns pins the int header; and the gate-off program pins
 * that a program which can build no matrix compiles the dead arms to a call-time signal
 * and {@code quantized-matrix-p} to nil.
 */
class JvmQuantizedMatrixTest {

	@TempDir
	Path tempDir;

	private String interpret(String lispCode) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		for (LispVal expr : LispReader.readAllFromString(lispCode)) {
			evaluator.eval(expr);
		}
		return baos.toString().trim();
	}

	private byte[] compile(String lispCode, boolean simd, boolean parallel) {
		List<LispVal> program = LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		program = LinalgLibrary.process(VecLibrary.process(program));
		if (parallel) {
			return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, true, false, false, true).compile(program);
		}
		return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, simd).compile(program);
	}

	private String run(byte[] classBytes) throws Exception {
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	/** The PARITY claim: both backends agree; answers the text they agreed on. */
	private String both(String lispCode) throws Exception {
		String interpreted = interpret(lispCode);
		String compiled = run(compile(lispCode, false, false));
		assertThat(compiled).as("PARITY: JVM output must equal the interpreter's for:%n%s", lispCode)
			.isEqualTo(interpreted);
		return compiled;
	}

	/**
	 * The ORACLE claim, kept apart: the agreed text against a hand-written expectation.
	 */
	private void assertAgreedText(String lispCode, String expected) throws Exception {
		assertThat(both(lispCode)).as("ORACLE for:%n%s", lispCode).isEqualTo(expected);
	}

	/**
	 * A deterministic quantized matrix {@code *m*} from a {@code *w*} of published-weight
	 * scale.
	 */
	private static String fixture(int rows, int cols, String xType) {
		return """
				(defparameter *w* (make-array '(%d %d) :element-type 'single-float :initial-element 0.0))
				(dotimes (i %d) (dotimes (j %d)
				  (setf (aref *w* i j) (* 0.02 (sin (+ (* 0.37 i) (* 1.91 j) (* 0.01 i j)))))))
				(defparameter *m* (rontolisp:quantize *w* 'q8-0))
				(defparameter *x* (make-array %d :element-type '%s :initial-element 0.0))
				(dotimes (j %d) (setf (aref *x* j) (cos (* 0.73 j))))
				""".formatted(rows, cols, rows, cols, cols, xType, cols);
	}

	// --- the surface, on both backends ---------------------------------------------

	@Test
	void printsOpaquelyAndAnswersTheInquiriesOnBothBackends() throws Exception {
		String program = "(defparameter *m* (rontolisp:make-quantized-matrix 'q8-0 '(3 64)))\n";
		assertAgreedText(program + "(print *m*)", "#<quantized-matrix q8-0 (3 64)>");
		assertAgreedText(program + "(print (list (array-dimensions *m*) (array-rank *m*) (array-total-size *m*)"
				+ " (array-dimension *m* 1) (array-element-type *m*)))", "((3 64) 2 192 64 Q8-0)");
		assertAgreedText(
				program + "(print (list (arrayp *m*) (vectorp *m*) (typep *m* 'array)"
						+ " (rontolisp:quantized-matrix-p *m*) (typep *m* 'rontolisp:quantized-matrix) (type-of *m*)"
						+ " (rontolisp:quantized-matrix-p #f(1.0)) (typep 3 'rontolisp:quantized-matrix)))",
				"(NIL NIL NIL T T QUANTIZED-MATRIX NIL NIL)");
		assertAgreedText("(print (rontolisp:make-quantized-matrix 'q8-0 32))", "#<quantized-matrix q8-0 (32)>");
		assertAgreedText("(print (length (rontolisp:make-quantized-matrix 'q8-0 96)))", "96");
		assertAgreedText("(princ (rontolisp:make-quantized-matrix :q8-0 '(1 32)))", "#<quantized-matrix q8-0 (1 32)>");
		assertAgreedText("(print (typecase (rontolisp:make-quantized-matrix 'q8-0 32)"
				+ " (rontolisp:quantized-matrix :qm) (t :other)))", ":QM");
	}

	@Test
	void arefDequantizesOnBothBackendsAndStoresAreRefused() throws Exception {
		String program = fixture(3, 64, "single-float");
		assertAgreedText(
				program + "(print (list (aref *m* 0 0) (aref *m* 1 5) (aref *m* 2 63)"
						+ " (row-major-aref *m* 69) (rontolisp::%quantized-quant *m* 1 5)"
						+ " (rontolisp::%quantized-scale *m* 1 1)))",
				interpret(program + "(print (list (aref *m* 0 0) (aref *m* 1 5) (aref *m* 2 63)"
						+ " (row-major-aref *m* 69) (rontolisp::%quantized-quant *m* 1 5)"
						+ " (rontolisp::%quantized-scale *m* 1 1)))"));
		// Every element, both backends: the header arithmetic at every block boundary.
		both(program + "(dotimes (i 3) (dotimes (j 64) (print (aref *m* i j))))");
		both(program + "(dotimes (k 192) (print (row-major-aref *m* k)))");
		assertAgreedText(program + "(print (handler-case (setf (aref *m* 0 0) 1.0) (error (e) (princ-to-string e))))",
				"\"%ASET: a quantized matrix is immutable (dequantize it into a packed float array to change it)\"");
		assertAgreedText("(print (handler-case (aref (rontolisp:make-quantized-matrix 'q8-0 '(2 32)) 2 0)"
				+ " (error (e) (princ-to-string e))))", "\"aref: index out of bounds\"");
	}

	@Test
	void quantizeAndDequantizeAgreeOnBothBackends() throws Exception {
		String program = fixture(3, 64, "single-float");
		both(program + "(print (rontolisp:dequantize *m* 'single-float))");
		both(program + "(print (rontolisp:dequantize *m* 'double-float))");
		both(program + "(print (rontolisp:dequantize *m* 'bfloat16))");
		assertAgreedText(program + "(print (array-dimensions (rontolisp:dequantize *m* 'single-float)))", "(3 64)");
		// A double-float and a bfloat16 source quantize through their f32 values.
		both("(defparameter *d* (make-array 32 :element-type 'double-float :initial-element 0.0))"
				+ "(dotimes (j 32) (setf (aref *d* j) (* 0.001 (- j 15.5))))"
				+ "(print (rontolisp:dequantize (rontolisp:quantize *d* 'q8-0) 'single-float))");
		both("(print (rontolisp:dequantize (rontolisp:quantize #bf16(1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0 10.0"
				+ " 11.0 12.0 13.0 14.0 15.0 16.0 17.0 18.0 19.0 20.0 21.0 22.0 23.0 24.0 25.0 26.0 27.0 28.0 29.0"
				+ " 30.0 31.0 32.0) 'q8-0) 'single-float))");
		// The tie case: amax 127 puts d at exactly 1 and 2.5 rounds AWAY from zero.
		assertAgreedText("(print (let ((a (make-array 32 :element-type 'single-float :initial-element 0.0)))"
				+ " (setf (aref a 0) 127.0 (aref a 1) 2.5 (aref a 2) -2.5)" + " (let ((m (rontolisp:quantize a 'q8-0)))"
				+ " (list (aref m 1) (aref m 2) (rontolisp::%quantized-scale m 0 0)))))", "(3.0 -3.0 1.0)");
		assertAgreedText("(print (handler-case (rontolisp:quantize #f(1.0 2.0) 'q8-0) (error (e) :refused)))",
				":REFUSED");
		assertAgreedText("(print (handler-case (rontolisp:make-quantized-matrix 'q4-0 32) (error (e) :refused)))",
				":REFUSED");
	}

	@Test
	void linalgRowOfAQuantizedMatrixIsASingleFloatVectorOnBothBackends() throws Exception {
		String program = fixture(3, 64, "single-float");
		assertAgreedText(program + "(print (array-element-type (linalg:row *m* 2)))", "SINGLE-FLOAT");
		both(program + "(print (linalg:row *m* 2))");
	}

	// --- the GEMV: defun == compiled kernel, bit for bit
	// --------------------------------

	@Test
	void theIntegerDotGemvIsTheInterpretersDefunBitForBitUnderSimdAndParallel() throws Exception {
		for (String xType : new String[] { "single-float", "double-float" }) {
			for (int[] shape : new int[][] { { 1, 32 }, { 3, 64 }, { 17, 256 }, { 64, 1024 }, { 300, 512 } }) {
				String program = fixture(shape[0], shape[1], xType) + "(print (vec:matvec *m* *x*))";
				String defun = interpret(program);
				assertThat(run(compile(program, false, false))).as("plain JVM %dx%d %s", shape[0], shape[1], xType)
					.isEqualTo(defun);
				assertThat(run(compile(program, true, false))).as("--simd %dx%d %s", shape[0], shape[1], xType)
					.isEqualTo(defun);
				assertThat(run(compile(program, true, true)))
					.as("--simd --parallel %dx%d %s", shape[0], shape[1], xType)
					.isEqualTo(defun);
				String into = fixture(shape[0], shape[1], xType)
						+ "(defparameter *out* (make-array %d :element-type '%s :initial-element 0.0))"
							.formatted(shape[0], xType)
						+ "(print (vec:matvec-into *out* *m* *x*))";
				assertThat(run(compile(into, true, false))).as("-into --simd").isEqualTo(defun);
				assertThat(run(compile(into, true, true))).as("-into --simd --parallel").isEqualTo(defun);
			}
		}
	}

	@Test
	void aMixedWidthDestinationDeclinesToTheDefunUnderSimd() throws Exception {
		String program = fixture(2, 32, "single-float")
				+ "(defparameter *out* (make-array 2 :element-type 'double-float :initial-element 0.0))"
				+ "(print (vec:matvec-into *out* *m* *x*))";
		assertThat(run(compile(program, true, false))).isEqualTo(interpret(program));
	}

	// --- the dimension past 32767 ----------------------------------------------------

	@Test
	void dimensionsPast32767RoundTripThroughTheIntHeader() throws Exception {
		assertAgreedText(
				"(let ((m (rontolisp:make-quantized-matrix 'q8-0 '(40000 32))))"
						+ " (print (list (array-dimensions m) (aref m 39999 31) (array-total-size m))))",
				"((40000 32) 0.0 1280000)");
		assertAgreedText("(let ((a (make-array '(2 40000) :element-type 'single-float :initial-element 0.0)))"
				+ " (setf (aref a 1 39999) 127.0 (aref a 0 39968) -127.0)" + " (let ((m (rontolisp:quantize a 'q8-0)))"
				+ " (print (list (array-dimensions m) (aref m 1 39999) (aref m 0 39968) (aref m 1 39998)))))",
				"((2 40000) 127.0 -127.0 0.0)");
	}

	// --- the bulk transfer -------------------------------------------------------------

	@Test
	void readSequenceAndWriteSequenceMoveTheBlocksOnBothBackends() throws Exception {
		String path = this.tempDir.resolve("blocks.bin").toString().replace("\\", "\\\\");
		String program = fixture(3, 64, "single-float") + """
				(with-open-file (out "%s" :direction :output :if-exists :supersede
				                     :element-type '(unsigned-byte 8))
				  (print (write-sequence *m* out)))
				(defparameter *m2* (rontolisp:make-quantized-matrix 'q8-0 '(3 64)))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (print (read-sequence *m2* in)))
				(print (equalp (rontolisp:dequantize *m* 'single-float) (rontolisp:dequantize *m2* 'single-float)))
				(print (aref *m2* 2 63))
				""".formatted(path, path);
		assertThat(both(program)).startsWith("#<quantized-matrix q8-0 (3 64)>\n204\nT\n");
	}

	// --- the gate: a program that can build no matrix
	// ------------------------------------

	@Test
	void aProgramThatCannotBuildAMatrixCompilesTheDeadArmsAndAnswersNilToThePredicate() throws Exception {
		// vec:matvec's spliced defun carries the quantized arm; here it is dead.
		assertAgreedText("(print (vec:matvec #f((1.0 2.0) (3.0 4.0)) #f(1.0 1.0)))", "#f(3.0 7.0)");
		assertAgreedText("(print (list (rontolisp:quantized-matrix-p 3) (rontolisp:quantized-matrix-p #f(1.0))))",
				"(NIL NIL)");
		String message = run(compile(
				"(print (handler-case (rontolisp:dequantize 3 'single-float)" + " (error (e) (princ-to-string e))))",
				false, false));
		assertThat(message).contains("no quantized matrix can exist");
		assertThatThrownBy(() -> compile("(rontolisp:quantize)", false, false)).hasMessageContaining("expects 2");
	}

}
