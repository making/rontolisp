package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import am.ik.rontolisp.BFloat16;
import am.ik.rontolisp.LispBFloat16Array;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispQuantizedMatrix;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.QuantizedFormat;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The block-quantized weight matrix on the interpreter ({@code .kb/quantized-matrix.md}):
 * its surface, ggml's {@code quantize_row_q8_0_ref} as the byte oracle of
 * {@code rontolisp:quantize}, the bulk transfer that makes a GGUF tensor its storage, and
 * the contract of {@code vec:matvec} over it -- the scalar defun and the {@code --simd}
 * integer-dot kernel are one value BIT FOR BIT, serial and {@code --parallel}, at both
 * activation widths.
 */
class QuantizedMatrixTest {

	@TempDir
	Path tempDir;

	private static LispVal eval(String input, boolean simd, boolean parallel) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSimd(simd);
		evaluator.setParallel(parallel);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private static String eval(String input) {
		return eval(input, false, false).print();
	}

	/**
	 * A deterministic {@code rows x cols} single-float matrix bound to {@code *w*}, of
	 * gaussian-looking values at the 0.02 scale published weights have, and a vector
	 * {@code *x*} of {@code cols} unit-scale values.
	 */
	private static String fixture(int rows, int cols, String xType) {
		// A linear congruential generator, so the values are pseudo-random (no
		// structure a sum could cancel) and identical on every backend: exact integer
		// arithmetic below 2^62.
		return """
				(defparameter *seed* 20260905)
				(defun next-unit ()
				  (setq *seed* (mod (+ (* *seed* 1103515245) 12345) 2147483648))
				  (- (/ *seed* 2147483648.0) 0.5))
				(defparameter *w* (make-array '(%d %d) :element-type 'single-float :initial-element 0.0))
				(dotimes (i %d) (dotimes (j %d) (setf (aref *w* i j) (* 0.07 (next-unit)))))
				(defparameter *m* (rontolisp:quantize *w* 'q8-0))
				(defparameter *x* (make-array %d :element-type '%s :initial-element 0.0))
				(dotimes (j %d) (setf (aref *x* j) (* 3.0 (next-unit))))
				""".formatted(rows, cols, rows, cols, cols, xType, cols);
	}

	// --- the type ------------------------------------------------------------------

	@Test
	void printsOpaquelyAndAnswersTheArrayInquiriesButIsNotAnArray() {
		String program = "(defparameter *m* (rontolisp:make-quantized-matrix 'q8-0 '(3 64)))\n";
		assertThat(eval(program + "*m*")).isEqualTo("#<quantized-matrix q8-0 (3 64)>");
		assertThat(eval(program + "(list (array-dimensions *m*) (array-rank *m*) (array-total-size *m*)"
				+ " (array-dimension *m* 1) (array-element-type *m*))"))
			.isEqualTo("((3 64) 2 192 64 Q8-0)");
		assertThat(eval(program + "(list (arrayp *m*) (vectorp *m*) (typep *m* 'array)"
				+ " (rontolisp:quantized-matrix-p *m*) (length (rontolisp:make-quantized-matrix 'q8-0 96))"
				+ " (typep *m* 'rontolisp:quantized-matrix) (typep *m* 'quantized-matrix) (type-of *m*)"
				+ " (rontolisp:quantized-matrix-p #f(1.0)) (typep 3 'rontolisp:quantized-matrix))"))
			.isEqualTo("(NIL NIL NIL T 96 T T QUANTIZED-MATRIX NIL NIL)");
		assertThatThrownBy(() -> eval(program + "(length *m*)")).hasMessageContaining("not a sequence");
		assertThat(eval("(rontolisp:make-quantized-matrix 'q8-0 32)")).isEqualTo("#<quantized-matrix q8-0 (32)>");
		assertThat(eval("(array-dimensions (rontolisp:make-quantized-matrix :q8-0 96))")).isEqualTo("(96)");
		assertThat(eval("(array-element-type (rontolisp:make-quantized-matrix 'rontolisp:q8-0 '(1 32)))"))
			.isEqualTo("Q8-0");
	}

	@Test
	void aFreshMatrixIsAllZeroAndEveryElementIsReadableButNoneIsWritable() {
		String program = "(defparameter *m* (rontolisp:make-quantized-matrix 'q8-0 '(2 32)))\n";
		assertThat(eval(program + "(list (aref *m* 0 0) (aref *m* 1 31) (row-major-aref *m* 63))"))
			.isEqualTo("(0.0 0.0 0.0)");
		assertThatThrownBy(() -> eval(program + "(setf (aref *m* 0 0) 1.0)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("a quantized matrix is immutable");
		assertThatThrownBy(() -> eval(program + "(setf (row-major-aref *m* 0) 1.0)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("a quantized matrix is immutable");
		assertThatThrownBy(() -> eval(program + "(aref *m* 2 0)")).hasMessageContaining("out of bounds");
		assertThatThrownBy(() -> eval(program + "(aref *m* 0)")).hasMessageContaining("expected 2 subscripts");
	}

	@Test
	void theShapeMustHoldWholeBlocksAndTheFormatMustBeKnown() {
		assertThatThrownBy(() -> eval("(rontolisp:make-quantized-matrix 'q8-0 '(2 33))"))
			.hasMessageContaining("multiple of 32");
		assertThatThrownBy(() -> eval("(rontolisp:make-quantized-matrix 'q8-0 '(2 2 32))"))
			.hasMessageContaining("rank 1 or 2");
		assertThatThrownBy(() -> eval("(rontolisp:make-quantized-matrix 'q4-0 '(2 32))"))
			.hasMessageContaining("format must be q8-0");
		assertThatThrownBy(() -> eval("(rontolisp:quantize #f(1.0 2.0 3.0) 'q8-0)"))
			.hasMessageContaining("multiple of 32");
		assertThatThrownBy(() -> eval("(rontolisp:quantize #(1 2 3) 'q8-0)"))
			.hasMessageContaining("expects a packed float array");
		assertThatThrownBy(() -> eval("(rontolisp:dequantize #f(1.0) 'single-float)"))
			.hasMessageContaining("expects a quantized matrix");
	}

	// --- ggml's quantize_row_q8_0_ref is the byte oracle ------------------------------

	/**
	 * {@code quantize_row_q8_0_ref} from ggml-quants.c, transcribed a second time and
	 * independently of {@code QuantizedMatrices}: f32 absmax, {@code d = amax / 127},
	 * {@code id = 1/d}, {@code y.d = fp16(d)}, {@code y.qs[j] = roundf(x[j] * id)}.
	 */
	private static byte[] ggmlReference(float[] x) {
		int nb = x.length / 32;
		byte[] y = new byte[nb * 34];
		for (int i = 0; i < nb; i++) {
			float amax = 0.0f;
			for (int j = 0; j < 32; j++) {
				float v = x[i * 32 + j];
				float av = Math.abs(v);
				amax = Math.max(amax, av);
			}
			float d = amax / ((1 << 7) - 1);
			float id = d != 0 ? 1.0f / d : 0.0f;
			short h = Float.floatToFloat16(d);
			y[i * 34] = (byte) (h & 0xff);
			y[i * 34 + 1] = (byte) ((h >>> 8) & 0xff);
			for (int j = 0; j < 32; j++) {
				float x0 = x[i * 32 + j] * id;
				// C's roundf: ties away from zero.
				y[i * 34 + 2 + j] = (byte) (int) (x0 >= 0 ? Math.floor(x0 + 0.5) : -Math.floor(-x0 + 0.5));
			}
		}
		return y;
	}

	private static LispQuantizedMatrix quantize(float[] data, int rows, int cols) {
		LispVal m = QuantizedMatrices.quantize("RONTOLISP:QUANTIZE",
				List.of(new LispSingleFloatArray(data, new int[] { rows, cols }), new LispSymbol("Q8-0")));
		return (LispQuantizedMatrix) m;
	}

	@Test
	void quantizeProducesGgmlsBytes() {
		Random random = new Random(672);
		for (int[] shape : new int[][] { { 1, 32 }, { 3, 64 }, { 17, 256 }, { 64, 1024 } }) {
			int n = shape[0] * shape[1];
			float[] data = new float[n];
			for (int i = 0; i < n; i++) {
				data[i] = (float) (random.nextGaussian() * 0.02);
			}
			assertThat(quantize(data, shape[0], shape[1]).blocks()).as("%dx%d", shape[0], shape[1])
				.isEqualTo(ggmlReference(data));
		}
	}

	@Test
	void quantizeRoundsATieAwayFromZeroLikeRoundf() {
		// amax 127 makes d exactly 1 and id exactly 1, so the values are their own
		// quotients: 2.5 -> 3 and -2.5 -> -3 (Math.round alone would give 3 and -2).
		float[] data = new float[32];
		data[0] = 127.0f;
		data[1] = 2.5f;
		data[2] = -2.5f;
		data[3] = 0.5f;
		data[4] = -0.5f;
		data[5] = -127.0f;
		LispQuantizedMatrix m = quantize(data, 1, 32);
		assertThat(m.blocks()).isEqualTo(ggmlReference(data));
		assertThat(m.quant(1)).isEqualTo(3);
		assertThat(m.quant(2)).isEqualTo(-3);
		assertThat(m.quant(3)).isEqualTo(1);
		assertThat(m.quant(4)).isEqualTo(-1);
		assertThat(m.quant(5)).isEqualTo(-127);
		assertThat(m.scale(0)).isEqualTo(1.0f);
		// The stored scale is the block's binary16: 0.023 does not survive it exactly.
		float[] small = new float[32];
		small[7] = 0.023f;
		assertThat(quantize(small, 1, 32).scale(0)).isEqualTo(Float.float16ToFloat(Float.floatToFloat16(0.023f / 127)));
	}

	@Test
	void anAllZeroBlockQuantizesToZeroScaleAndZeroQuants() {
		float[] data = new float[64];
		data[40] = 1.0f;
		LispQuantizedMatrix m = quantize(data, 2, 32);
		assertThat(m.scale(0)).isEqualTo(0.0f);
		for (int i = 0; i < 32; i++) {
			assertThat(m.quant(i)).isZero();
		}
		assertThat(m.elementAt(40)).isEqualTo(127 * Float.float16ToFloat(Float.floatToFloat16(1.0f / 127)));
	}

	// --- dequantize --------------------------------------------------------------------

	@Test
	void dequantizeIsWithinHalfAQuantOfTheSourceEverywhere() {
		Random random = new Random(7);
		int rows = 64, cols = 256;
		float[] data = new float[rows * cols];
		for (int i = 0; i < data.length; i++) {
			data[i] = (float) (random.nextGaussian() * 0.02);
		}
		LispQuantizedMatrix m = quantize(data, rows, cols);
		for (int b = 0; b < m.blockCount(); b++) {
			float amax = 0.0f;
			for (int k = 0; k < 32; k++) {
				amax = Math.max(amax, Math.abs(data[b * 32 + k]));
			}
			// Half a quant (amax / 254), plus what rounding the scale to binary16
			// (11 significant bits) moves a full-magnitude element by.
			double bound = amax / 254.0 + amax * Math.scalb(1.0, -11);
			for (int k = 0; k < 32; k++) {
				int i = b * 32 + k;
				assertThat(Math.abs(m.elementAt(i) - data[i])).as("element %d", i).isLessThanOrEqualTo(bound);
			}
		}
	}

	@Test
	void dequantizeAnswersEachOfTheThreeWidths() {
		String program = "(defparameter *m* (rontolisp:quantize #f(1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0 10.0 11.0 12.0"
				+ " 13.0 14.0 15.0 16.0 17.0 18.0 19.0 20.0 21.0 22.0 23.0 24.0 25.0 26.0 27.0 28.0 29.0 30.0 31.0 32.0)"
				+ " 'q8-0))\n";
		String single = eval(program + "(rontolisp:dequantize *m* 'single-float)");
		String dbl = eval(program + "(rontolisp:dequantize *m* 'double-float)");
		assertThat(single).startsWith("#f(");
		assertThat(dbl).startsWith("#d(");
		// Every dequantized element is q * a binary16, exact in f32: the three widths
		// agree with aref, and 32.0 comes back as 127 * fp16(32 / 127).
		assertThat(eval(program + "(list (aref (rontolisp:dequantize *m* 'single-float) 31)"
				+ " (aref (rontolisp:dequantize *m* 'double-float) 31) (aref *m* 31))"))
			.isEqualTo("(31.998046875 31.998046875 31.998046875)");
		assertThat(eval(program + "(array-element-type (rontolisp:dequantize *m* 'bfloat16))")).isEqualTo("BFLOAT16");
		// The bf16 copy narrows each dequantized value with the one authority.
		LispVal bf16 = eval(program + "(rontolisp:dequantize *m* 'bfloat16)", false, false);
		LispQuantizedMatrix m = (LispQuantizedMatrix) eval(program + "*m*", false, false);
		short[] narrowed = ((LispBFloat16Array) bf16).data();
		for (int i = 0; i < 32; i++) {
			assertThat(narrowed[i]).isEqualTo((short) BFloat16.bits(m.elementAt(i)));
		}
		assertThat(eval(program + "(array-dimensions (rontolisp:dequantize *m* 'single-float))")).isEqualTo("(32)");
		assertThatThrownBy(() -> eval(program + "(rontolisp:dequantize *m* 'fixnum)"))
			.hasMessageContaining("element-type must be");
	}

	// --- the bulk transfer -------------------------------------------------------------

	@Test
	void readSequenceAndWriteSequenceMoveTheBlocksVerbatim() {
		String path = this.tempDir.resolve("blocks.bin").toString().replace("\\", "\\\\");
		String program = fixture(3, 64, "single-float") + """
				(with-open-file (out "%s" :direction :output :if-exists :supersede
				                     :element-type '(unsigned-byte 8))
				  (write-sequence *m* out))
				(defparameter *m2* (rontolisp:make-quantized-matrix 'q8-0 '(3 64)))
				(defparameter *n* (with-open-file (in "%s" :element-type '(unsigned-byte 8))
				                    (read-sequence *m2* in)))
				""".formatted(path, path);
		assertThat(eval(program + "*n*")).isEqualTo("204");
		assertThat(eval(program + "(equalp (rontolisp:dequantize *m* 'single-float)"
				+ " (rontolisp:dequantize *m2* 'single-float))"))
			.isEqualTo("T");
		LispQuantizedMatrix a = (LispQuantizedMatrix) eval(program + "*m*", false, false);
		LispQuantizedMatrix b = (LispQuantizedMatrix) eval(program + "*m2*", false, false);
		assertThat(b.blocks()).isEqualTo(a.blocks());
		assertThat(b.format()).isEqualTo(QuantizedFormat.Q8_0);
	}

	// --- vec:matvec: the defun and the kernel are one value --------------------------

	@Test
	void theIntegerDotGemvIsTheDefunBitForBitAtEveryShapeAndWidth() {
		for (String xType : new String[] { "single-float", "double-float" }) {
			for (int[] shape : new int[][] { { 1, 32 }, { 3, 64 }, { 17, 256 }, { 64, 1024 }, { 300, 512 } }) {
				String program = fixture(shape[0], shape[1], xType) + "(vec:matvec *m* *x*)";
				String defun = eval(program, false, false).print();
				assertThat(defun).startsWith(xType.equals("single-float") ? "#f(" : "#d(");
				assertThat(eval(program, true, false).print()).as("--simd, %dx%d %s", shape[0], shape[1], xType)
					.isEqualTo(defun);
				assertThat(eval(program, true, true).print())
					.as("--simd --parallel, %dx%d %s", shape[0], shape[1], xType)
					.isEqualTo(defun);
				String into = fixture(shape[0], shape[1], xType)
						+ "(defparameter *out* (make-array %d :element-type '%s :initial-element 0.0))"
							.formatted(shape[0], xType)
						+ "(vec:matvec-into *out* *m* *x*)";
				assertThat(eval(into, false, false).print()).isEqualTo(defun);
				assertThat(eval(into, true, false).print()).as("-into --simd").isEqualTo(defun);
				assertThat(eval(into, true, true).print()).as("-into --simd --parallel").isEqualTo(defun);
			}
		}
	}

	@Test
	void theQuantizedGemvIsCloseToTheBf16GemvOverTheDequantizedMatrix() {
		// 7.6e-3 relative was the spike's number (.todo/672); the contract here is only
		// that the quantized path is a quantization error, not a bug: below 1e-2.
		String program = fixture(256, 1024, "single-float") + """
				(defparameter *q* (vec:matvec *m* *x*))
				(defparameter *b* (vec:matvec (rontolisp:dequantize *m* 'bfloat16) *x*))
				(defparameter *f* (vec:matvec *w* *x*))
				(defun rel (a b)
				  (let ((num 0.0) (den 0.0))
				    (dotimes (i (length a) (sqrt (/ num den)))
				      (setq num (+ num (expt (- (aref a i) (aref b i)) 2)))
				      (setq den (+ den (expt (aref b i) 2))))))
				(list (rel *q* *f*) (rel *b* *f*))
				""";
		String result = eval(program);
		String[] parts = result.substring(1, result.length() - 1).split(" ");
		double quantized = Double.parseDouble(parts[0]);
		double bf16 = Double.parseDouble(parts[1]);
		assertThat(quantized).as("Q8_0 against f32: %s", result).isLessThan(1e-2);
		assertThat(bf16).as("bf16 against f32: %s", result).isLessThan(quantized);
	}

	@Test
	void everyOtherVecMemberHandsAQuantizedMatrixToTheDefun() {
		String program = "(defparameter *m* (rontolisp:make-quantized-matrix 'q8-0 '(2 32)))\n";
		for (String form : new String[] { "(vec:sum *m*)", "(vec:add *m* *m*)", "(vec:dot *m* #f(1.0))" }) {
			String scalar = message(() -> eval(program + form, false, false));
			String simd = message(() -> eval(program + form, true, false));
			assertThat(simd).as(form).isEqualTo(scalar);
		}
		// matvec over a rank-1 matrix and matvec-into with a mismatched destination
		// width decline to the defun as well: the defun's own outcome, whatever it is.
		assertThat(message(() -> eval("(vec:matvec (rontolisp:make-quantized-matrix 'q8-0 32) #f(1.0))", true, false)))
			.isEqualTo(message(
					() -> eval("(vec:matvec (rontolisp:make-quantized-matrix 'q8-0 32) #f(1.0))", false, false)));
		String mixed = fixture(2, 32, "single-float")
				+ "(defparameter *out* (make-array 2 :element-type 'double-float :initial-element 0.0))"
				+ "(vec:matvec-into *out* *m* *x*)";
		assertThat(eval(mixed, true, false).print()).isEqualTo(eval(mixed, false, false).print());
	}

	private static String message(Runnable body) {
		try {
			body.run();
			return "no error";
		}
		catch (RuntimeException ex) {
			return String.valueOf(ex.getMessage());
		}
	}

	@Test
	void linalgRowOfAQuantizedMatrixIsASingleFloatVector() {
		String program = fixture(3, 64, "single-float");
		assertThat(eval(program + "(array-element-type (linalg:row *m* 1))")).isEqualTo("SINGLE-FLOAT");
		assertThat(
				eval(program + "(equalp (linalg:row *m* 1) (linalg:row (rontolisp:dequantize *m* 'single-float) 1))"))
			.isEqualTo("T");
	}

}
