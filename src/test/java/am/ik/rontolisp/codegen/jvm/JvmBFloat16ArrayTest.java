package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import am.ik.rontolisp.BFloat16;
import am.ik.rontolisp.FloatText;
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
 * The {@code bfloat16} packed array on the JVM backend ({@code .todo/485}): a
 * {@code #bf16(...)} literal and {@code make-array :element-type 'bfloat16} compile to a
 * bare {@code short[]} carrying the two-slots-per-dimension header
 * {@link JvmPackedFloatWidth#BFLOAT16} lays out, and every array op routes through the
 * {@code _fv*} helpers' third arm.
 *
 * <p>
 * Three things this class pins that the {@code #d}/{@code #f} tests do not:
 * <ul>
 * <li><b>Both backends, every case.</b> A header-offset mistake in a hand-written
 * bytecode reader shows up as a plausible value, not an exception, and only a comparison
 * against the interpreter -- which has no header at all -- catches it. So every program
 * here runs on both and the outputs are compared, with the length-1 and rank-2/3 shapes
 * the trap surfaced on.
 * <li><b>The dimension that does not fit a short.</b> A rank-1 vector of 40000 and a
 * rank-2/3 array with a 40000 dimension: {@code aref} at the last index must answer the
 * value stored there. This is the regression the two-slot header exists for.
 * <li><b>The emitted conversion pair against the authority, exhaustively.</b>
 * {@code _bf16Value} / {@code _bf16Bits} are {@link BFloat16} copied instruction for
 * instruction (the authority cannot travel with a compiled program), and every earlier
 * copy of this arithmetic that broke broke in the narrow / NaN direction -- so the narrow
 * copy is swept over ALL 2^32 f32 bit patterns and the double NaN space, not merely the
 * 65536 representable round trips.
 * </ul>
 */
class JvmBFloat16ArrayTest {

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

	private byte[] compile(String lispCode, boolean accel) {
		List<LispVal> program = LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		program = LinalgLibrary.process(VecLibrary.process(program));
		return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, accel).compile(program);
	}

	private Class<?> load(byte[] classBytes) throws Exception {
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader());
		return loader.loadClass("Test");
	}

	private String run(byte[] classBytes) throws Exception {
		Class<?> clazz = load(classBytes);
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

	private String compileAndRun(String lispCode) throws Exception {
		return run(compile(lispCode, false));
	}

	/**
	 * The PARITY claim: runs the program on both backends, asserts they agree with each
	 * other, and answers the text they agreed on. A failure here names the two backends
	 * and nothing else.
	 */
	private String both(String lispCode) throws Exception {
		String interpreted = interpret(lispCode);
		String compiled = compileAndRun(lispCode);
		assertThat(compiled).as("PARITY: JVM output must equal the interpreter's for:%n%s", lispCode)
			.isEqualTo(interpreted);
		return compiled;
	}

	/**
	 * The ORACLE claim, kept apart from the parity one (.kb/measurement-probes.md, rule
	 * 5): the text both backends agreed on, against a hand-written expectation. A failure
	 * here means the expectation is what disagrees -- both backends already matched.
	 */
	private void assertAgreedText(String lispCode, String expected) throws Exception {
		String agreed = both(lispCode);
		assertThat(agreed)
			.as("ORACLE: the text both backends agree on, against the hand-written expectation for:%n%s", lispCode)
			.isEqualTo(expected);
	}

	// --- the #f mirror ---------------------------------------------------------

	@Test
	void rank1LiteralPrintsWithBf16SyntaxOnBothBackends() throws Exception {
		assertAgreedText("(print #bf16(1.0 2.0 3.0))", "#bf16(1.0 2.0 3.0)");
		assertAgreedText("(print #bf16(1 2 3))", "#bf16(1.0 2.0 3.0)");
		assertAgreedText("(print (quote #bf16(1.0 2.0 3.0)))", "#bf16(1.0 2.0 3.0)");
	}

	@Test
	void aLengthOneLiteralHoldsItsElementRatherThanAHeaderWord() throws Exception {
		// The regression shape: an off-by-header read answers the rank word 1.0 here.
		assertAgreedText("(print (aref #bf16(7.0) 0))", "7.0");
		assertAgreedText("(print #bf16(7.0))", "#bf16(7.0)");
		assertAgreedText("(print (length #bf16(7.0)))", "1");
		assertAgreedText("(let ((v (make-array 1 :element-type 'bfloat16))) (setf (aref v 0) 2.5) (print (aref v 0)))",
				"2.5");
	}

	@Test
	void rank2AndRank3LiteralsIndexThroughTheTwoSlotHeader() throws Exception {
		assertAgreedText("(print #bf16((1.0 2.0) (3.0 4.0)))", "#bf16((1.0 2.0) (3.0 4.0))");
		assertAgreedText("(print (aref #bf16((1.0 2.0) (3.0 4.0)) 1 0))", "3.0");
		assertAgreedText("(print (row-major-aref #bf16((1.0 2.0) (3.0 4.0)) 2))", "3.0");
		assertAgreedText("(print (aref #bf16(((1.0 2.0) (3.0 4.0)) ((5.0 6.0) (7.0 8.0))) 1 0 1))", "6.0");
		assertAgreedText("(print (array-dimensions #bf16(((1.0 2.0) (3.0 4.0)) ((5.0 6.0) (7.0 8.0)))))", "(2 2 2)");
	}

	@Test
	void theBf16PrefixIsReadBeforeTheBinaryRadixPrefix() throws Exception {
		assertAgreedText("(print (list #b1010 #bf16(1.0) #B101))", "(10 #bf16(1.0) 5)");
	}

	@Test
	void arefWidensAndSetfArefNarrows() throws Exception {
		assertAgreedText("(print (aref #bf16(1.5 2.5 3.5) 1))", "2.5");
		// 0.1 is not representable at this width; the widened value is what aref
		// answers, and the print of the array is the shortest text that re-reads to it.
		assertAgreedText("(print (aref #bf16(0.1) 0))", "0.10009765625");
		assertAgreedText("(print #bf16(0.1))", "#bf16(0.1)");
		assertAgreedText("(let ((v #bf16(1.0 2.0 3.0))) (setf (aref v 1) 9.0) (print v))", "#bf16(1.0 9.0 3.0)");
		assertAgreedText("(let ((v #bf16(1.0))) (setf (aref v 0) 0.1) (print (aref v 0)))", "0.10009765625");
		assertAgreedText("(let ((v #bf16(1.0 2.0))) (print (setf (aref v 0) 0.1)))", "0.10009765625");
		assertAgreedText("(let ((v #bf16(1.0 2.0))) (setf (aref v 0) 5) (print (aref v 0)))", "5.0");
		assertAgreedText("(let ((m #bf16((1.0 2.0) (3.0 4.0)))) (setf (aref m 1 0) 7.0) (print m))",
				"#bf16((1.0 2.0) (7.0 4.0))");
		assertAgreedText("(let ((c (make-array '(2 2 2) :element-type 'bfloat16))) (setf (aref c 1 1 1) 8.0)"
				+ " (print (list (aref c 1 1 1) (row-major-aref c 7) (aref c 0 0 0))))", "(8.0 8.0 0.0)");
	}

	@Test
	void makeArrayWithBFloat16ElementTypeIsPacked() throws Exception {
		assertAgreedText("(print (make-array 3 :element-type 'bfloat16))", "#bf16(0.0 0.0 0.0)");
		assertAgreedText("(print (make-array 3 :element-type 'bfloat16 :initial-element 2.0))", "#bf16(2.0 2.0 2.0)");
		assertAgreedText("(print (make-array '(2 2) :element-type 'bfloat16))", "#bf16((0.0 0.0) (0.0 0.0))");
		assertAgreedText("(print (make-array '(2 3) :element-type 'bfloat16 :initial-element 0.1))",
				"#bf16((0.1 0.1 0.1) (0.1 0.1 0.1))");
		assertAgreedText("(print (list (length (make-array 5 :element-type 'bfloat16))"
				+ " (array-dimensions (make-array '(2 3) :element-type 'bfloat16))"
				+ " (array-total-size (make-array '(2 3) :element-type 'bfloat16))))", "(5 (2 3) 6)");
	}

	@Test
	void predicatesElementTypeAndTypeOfNameTheWidth() throws Exception {
		assertAgreedText("(print (list (arrayp #bf16(1.0)) (vectorp #bf16(1.0))"
				+ " (typep #bf16(1.0) '(array bfloat16)) (typep #bf16(1.0) '(simple-array bfloat16 (1)))"
				+ " (typep 1.0 'bfloat16)))", "(T T T T NIL)");
		both("(print (list (typep #bf16(1.0) 'simple-array) (typep #bf16(1.0) 'vector) (typep #bf16(1.0) 'simple-vector)))");
		assertAgreedText("(print (array-element-type #bf16(1.0)))", "BFLOAT16");
		assertAgreedText("(print (type-of #bf16(1.0 2.0)))", "(SIMPLE-ARRAY BFLOAT16 (2))");
	}

	@Test
	void theThreeWidthsCoexistWithDistinctPrefixes() throws Exception {
		assertAgreedText("(print (list #d(1.0) #f(1.0) #bf16(1.0)))", "(#d(1.0) #f(1.0) #bf16(1.0))");
		assertAgreedText("(print (list (array-element-type #d(1.0)) (array-element-type #f(1.0))"
				+ " (array-element-type #bf16(1.0))))", "(DOUBLE-FLOAT SINGLE-FLOAT BFLOAT16)");
	}

	@Test
	void nonRealStoreIsATypeErrorOnBothBackends() {
		String program = "(let ((v #bf16(1.0))) (setf (aref v 0) \"x\"))";
		assertThatThrownBy(() -> interpret(program)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> compileAndRun(program)).isInstanceOf(Exception.class);
	}

	// --- the dimension that does not fit a short -------------------------------

	@Test
	void aDimensionAbove32767IndexesToItsLastElement() throws Exception {
		// Option (c) of .todo/485 -- keeping the one-slot header -- reads the wrong data
		// offset silently on exactly these shapes; the interpreter comparison is what
		// says the answer is the stored value and not another slot's.
		assertAgreedText(
				"(let ((v (make-array 40000 :element-type 'bfloat16)))"
						+ " (setf (aref v 39999) 3.0) (setf (aref v 0) 1.5)"
						+ " (print (list (length v) (aref v 39999) (aref v 0) (aref v 1) (array-dimensions v))))",
				"(40000 3.0 1.5 0.0 (40000))");
		assertAgreedText("(let ((m (make-array '(2 40000) :element-type 'bfloat16)))"
				+ " (setf (aref m 1 39999) 5.0) (setf (aref m 0 0) 1.0) (setf (aref m 0 39999) 2.0)"
				+ " (print (list (aref m 1 39999) (aref m 0 0) (aref m 0 39999) (aref m 1 0)"
				+ " (array-dimensions m) (row-major-aref m 79999) (row-major-aref m 39999) (array-total-size m))))",
				"(5.0 1.0 2.0 0.0 (2 40000) 5.0 2.0 80000)");
		assertAgreedText(
				"(let ((c (make-array '(2 3 40000) :element-type 'bfloat16)))"
						+ " (setf (aref c 1 2 39999) 9.0) (setf (aref c 0 1 39999) 4.0)"
						+ " (print (list (aref c 1 2 39999) (aref c 0 1 39999) (aref c 1 2 0)"
						+ " (array-dimensions c) (array-total-size c) (row-major-aref c 239999))))",
				"(9.0 4.0 0.0 (2 3 40000) 240000 9.0)");
		// A dimension exactly at the boundary on either side.
		assertAgreedText(
				"(let ((v (make-array 32768 :element-type 'bfloat16)) (w (make-array 32767 :element-type 'bfloat16)))"
						+ " (setf (aref v 32767) 1.0) (setf (aref w 32766) 2.0)"
						+ " (print (list (aref v 32767) (aref w 32766) (length v) (length w))))",
				"(1.0 2.0 32768 32767)");
	}

	// --- every pattern, through the array ---------------------------------------

	@Test
	void everyPatternStoredThroughSetfArefReadsBackThroughAref() throws Exception {
		// The 126 signalling NaNs included: a store narrows through _bf16Bits and a
		// read widens through _bf16Value, neither crossing the f32, so the pattern
		// survives the array on both backends.
		assertAgreedText("""
				(let ((v (make-array 65536 :element-type 'bfloat16)) (bad 0))
				  (dotimes (i 65536) (setf (aref v i) (rontolisp:bits-bfloat16 i)))
				  (dotimes (i 65536)
				    (unless (= i (rontolisp:bfloat16-bits (aref v i))) (incf bad)))
				  (print bad))
				""", "0");
	}

	@Test
	void everyPatternPrintsTheSameTextOnBothBackends() throws Exception {
		// One 65536-element array printed whole: the JVM's _bf16Print must choose the
		// digits FloatText.bfloat16Text chooses for every pattern, NaN and the
		// infinities included.
		String program = """
				(let ((v (make-array 65536 :element-type 'bfloat16)))
				  (dotimes (i 65536) (setf (aref v i) (rontolisp:bits-bfloat16 i)))
				  (print v))
				""";
		String text = both(program);
		assertThat(text).startsWith("#bf16(0.0 ");
		// And the element text is the printer half's, not merely self-consistent.
		String[] elements = text.substring("#bf16(".length(), text.length() - 1).split(" ");
		assertThat(elements).hasSize(65536);
		for (int pattern = 0; pattern < 65536; pattern++) {
			assertThat(elements[pattern]).as("pattern %04x", pattern)
				.isEqualTo(FloatText.bfloat16Text(BFloat16.value(pattern)));
		}
	}

	// --- the emitted conversion pair against the authority -----------------------

	private MethodHandle helper(Class<?> clazz, String name, Class<?>... params) throws Exception {
		Method m = clazz.getDeclaredMethod(name, params);
		m.setAccessible(true);
		return MethodHandles.lookup().unreflect(m);
	}

	@Test
	void theEmittedValueMatchesTheAuthorityOnEveryPattern() throws Throwable {
		Class<?> clazz = load(compile("(print #bf16(1.0))", false));
		MethodHandle value = helper(clazz, JvmFloatArrayRuntimeBuilder.BF16_VALUE, int.class);
		for (int pattern = 0; pattern < 65536; pattern++) {
			double got = (double) value.invokeExact(pattern);
			assertThat(Double.doubleToRawLongBits(got)).as("pattern %04x", pattern)
				.isEqualTo(Double.doubleToRawLongBits(BFloat16.value(pattern)));
		}
	}

	@Test
	void theEmittedNarrowingMatchesTheAuthorityOnEveryF32PatternAndEveryDoubleNaN() throws Throwable {
		Class<?> clazz = load(compile("(print #bf16(1.0))", false));
		MethodHandle bits = helper(clazz, JvmFloatArrayRuntimeBuilder.BF16_BITS, double.class);
		// All 2^32 f32 patterns, widened to the double the array's callers hand in.
		// Chunked over the common pool; each chunk answers its first mismatch or -1.
		long mismatches = IntStream.range(0, 1 << 12).parallel().mapToLong(chunk -> {
			long bad = 0;
			int from = chunk << 20;
			for (int p = from; p != from + (1 << 20); p++) {
				double v = Float.intBitsToFloat(p);
				int got;
				try {
					got = (int) bits.invokeExact(v);
				}
				catch (Throwable t) {
					throw new AssertionError(t);
				}
				if (got != BFloat16.bits(v)) {
					bad++;
				}
			}
			return bad;
		}).sum();
		assertThat(mismatches).as("f32 patterns whose narrowing differs from BFloat16.bits").isZero();
		// The double NaN arm: every sign x every top-seven-bit payload x low bits of
		// the payload zero, one, all ones, and the f32-shaped shift of the top bits
		// (the double an f32 signalling NaN becomes when widened by hand). This is the
		// arm the f32 sweep cannot reach, since a widened f32 NaN arrives quieted.
		long nanMismatches = 0;
		for (int sign = 0; sign < 2; sign++) {
			for (int top = 0; top < 128; top++) {
				for (long low : new long[] { 0L, 1L, (1L << 45) - 1, 0x1234_5678_9abL, 1L << 44 }) {
					long raw = ((long) sign << 63) | 0x7ff0000000000000L | ((long) top << 45) | low;
					double v = Double.longBitsToDouble(raw);
					if (!Double.isNaN(v)) {
						continue; // top == 0 && low == 0 is an infinity, covered above
					}
					int got = (int) bits.invokeExact(v);
					if (got != BFloat16.bits(v)) {
						nanMismatches++;
					}
				}
			}
		}
		assertThat(nanMismatches).as("double NaNs whose narrowing differs from BFloat16.bits").isZero();
	}

	@Test
	void theEmittedPrintBoxMatchesTheTextAuthorityOnEveryPattern() throws Throwable {
		Class<?> clazz = load(compile("(print #bf16(1.0))", false));
		MethodHandle print = helper(clazz, JvmFloatArrayRuntimeBuilder.BF16_PRINT, int.class);
		for (int pattern = 0; pattern < 65536; pattern++) {
			Float box = (Float) print.invokeExact(pattern);
			assertThat(Float.toString(box).replace('E', 'e')).as("pattern %04x", pattern)
				.isEqualTo(FloatText.bfloat16Text(BFloat16.value(pattern)));
		}
	}

	// --- the surfaces around the accessors ---------------------------------------

	@Test
	void aDisplacedViewOverABf16TargetReadsAndWritesThroughIt() throws Exception {
		// The view declares the target's element type, as CL requires (and as the
		// existing #f / (unsigned-byte n) view tests do): the JVM's general
		// array-element-type arm is gated on some make-array in the program asking for
		// a type, so an undeclared view answers T on this backend at every width.
		assertAgreedText("(let* ((v #bf16(1.0 2.0 3.0 4.0))"
				+ " (d (make-array 2 :element-type 'bfloat16 :displaced-to v :displaced-index-offset 1)))"
				+ " (setf (aref d 0) 9.0) (print (list (aref d 1) (aref v 1) (length d) (array-element-type d) v)))",
				"(3.0 9.0 2 BFLOAT16 #bf16(1.0 9.0 3.0 4.0))");
		assertAgreedText("(let* ((v (make-array 40000 :element-type 'bfloat16))"
				+ " (d (make-array 2 :element-type 'bfloat16 :displaced-to v :displaced-index-offset 39998)))"
				+ " (setf (aref d 1) 0.1) (setf (aref d 0) 0.1) (print (list (aref v 39999) (aref d 0) (aref v 39998))))",
				"(0.10009765625 0.10009765625 0.10009765625)");
		// Not asserted: the VALUE a store through a view answers. The interpreter
		// answers the value as given and the JVM the value as stored, at every packed
		// width (.kb/adjustable-arrays.md, "A PACKED vector is a displacement target").
	}

	@Test
	void theRuntimeReaderReadsTheLiteralOnBothBackends() throws Exception {
		assertAgreedText("(print (read-from-string \"#bf16(1.5 2.5)\"))", "#bf16(1.5 2.5)");
		assertAgreedText("(print (read-from-string \"(#b101 #BF16((0.1) (2.0)) #bf16(7.0))\"))",
				"(5 #bf16((0.1) (2.0)) #bf16(7.0))");
		assertAgreedText("(let ((r (read-from-string \"#bf16(1.0 2.0 3.0)\")))"
				+ " (print (list (aref r 2) (length r) (array-element-type r))))", "(3.0 3 BFLOAT16)");
	}

	@Test
	void theRoutedPrinterRendersTheWidthLikeTheRawOne() throws Exception {
		// A program that also reads (or defines print-object) routes every print through
		// the Lisp-level %print-object-str walk, whose vector arm excludes the packed
		// float widths BY NAME -- the width was missing there and a #bf16 array printed
		// as a general #(...) of widened doubles, found by the -o Prog.class E2E and not
		// by any test that printed without reading. prin1-to-string and format ~a take
		// that walk in every program.
		assertAgreedText("(print #bf16(0.1)) (print (list #bf16(1.5) 1)) (print (read-from-string \"1\"))",
				"#bf16(0.1)\n(#bf16(1.5) 1)\n1");
		assertAgreedText("(print (prin1-to-string #bf16(0.1 2.0)))", "\"#bf16(0.1 2.0)\"");
		assertAgreedText("(print (format nil \"~a|~s\" #bf16(0.1) #bf16((1.0) (2.0))))",
				"\"#bf16(0.1)|#bf16((1.0) (2.0))\"");
		assertAgreedText("(defclass pos-box () ((v :initarg :v)))"
				+ " (defmethod print-object ((b pos-box) s) (format s \"<box ~a>\" (slot-value b 'v)))"
				+ " (print (make-instance 'pos-box :v #bf16(0.5 0.1)))", "<box #bf16(0.5 0.1)>");
	}

	@Test
	void loopsOverABf16ArrayAgreeWithTheInterpreter() throws Exception {
		// A typed loop specializes on float[]/double[] at run time and bails to the
		// boxed path for anything else; the sums must be the interpreter's. 499.5 is
		// not representable at this width (the ulp on [256, 512) is 2) and rounds to
		// nearest even, 500.0 -- a store that skipped the narrowing would answer 499.5.
		assertAgreedText("""
				(let ((v (make-array 1000 :element-type 'bfloat16)) (acc 0.0))
				  (dotimes (i 1000) (setf (aref v i) (* i 0.5)))
				  (dotimes (i 1000) (setq acc (+ acc (aref v i))))
				  (print (list acc (aref v 999) (aref v 1))))
				""", "(249750.0 500.0 0.5)");
	}

	@Test
	void theBulkBitsPairDeclinesABf16ArrayWithTheInterpretersWords() {
		String widen = "(rontolisp:widen-float-bits (make-array 2 :element-type '(unsigned-byte 16)) :bfloat16"
				+ " (make-array 2 :element-type 'bfloat16))";
		String narrow = "(rontolisp:narrow-float-bits #bf16(1.0 2.0) :bfloat16"
				+ " (make-array 2 :element-type '(unsigned-byte 16)))";
		assertThatThrownBy(() -> interpret(widen)).hasMessageContaining("does not yet write a bfloat16 destination");
		assertThatThrownBy(() -> compileAndRun(widen)).rootCause()
			.hasMessageContaining("does not yet write a bfloat16 destination");
		assertThatThrownBy(() -> interpret(narrow)).hasMessageContaining("does not yet read a bfloat16 source");
		assertThatThrownBy(() -> compileAndRun(narrow)).rootCause()
			.hasMessageContaining("does not yet read a bfloat16 source");
	}

	// --- vec: carries the width; --simd declines to the defun --------------------

	/** Exactly representable operands, so the text can be pinned. */
	private static final String VEC_EXACT_PROGRAM = """
			(print (vec:add #bf16(1.0 2.0) #bf16(3.0 4.0)))
			(print (vec:scale #bf16(1.0 2.0) 3.0))
			(print (vec:sum #bf16(1.0 2.0 3.5)))
			(print (vec:dot #bf16(1.0 2.0) #bf16(3.0 4.0)))
			(print (vec:matvec #bf16((1.0 2.0) (3.0 4.0)) #bf16(1.0 1.0)))
			(print (vec:clip #bf16(-1.0 0.5 2.0) 0.0 1.0))
			(print (vec:zeros 3 :element-type 'bfloat16))
			(let ((out (vec:zeros 2 :element-type 'bfloat16)))
			  (vec:add-into out #bf16(1.0 2.0) #bf16(0.5 0.5))
			  (print out))
			""";

	/** Operands the width rounds, and lengths above the lane kernels' thresholds. */
	private static final String VEC_ROUNDING_PROGRAM = """
			(print (vec:exp #bf16(0.0 1.0 -0.5)))
			(let ((v (vec:zeros 300 :element-type 'bfloat16)) (w (vec:zeros 300 :element-type 'bfloat16)))
			  (dotimes (i 300) (setf (aref v i) (* i 0.37)) (setf (aref w i) 1.5))
			  (print (list (vec:sum v) (vec:dot v w) (aref (vec:mul v w) 299) (vec:scale v 0.1)
			               (vec:matvec (make-array '(3 300) :element-type 'bfloat16 :initial-element 0.3) v))))
			""";

	@Test
	void vecKernelsPreserveTheBf16WidthOnTheJvm() throws Exception {
		assertAgreedText(VEC_EXACT_PROGRAM, """
				#bf16(4.0 6.0)
				#bf16(3.0 6.0)
				6.5
				11.0
				#bf16(3.0 7.0)
				#bf16(0.0 0.5 1.0)
				#bf16(0.0 0.0 0.0)
				#bf16(1.5 2.5)""");
		both(VEC_ROUNDING_PROGRAM);
	}

	@Test
	void aSimdBuildDeclinesABf16OperandToTheDefunBitForBit() throws Exception {
		// The lane kernels carry double[] and float[] only; a bfloat16 operand takes
		// the spliced vec.lisp defun over the packed representation, so --simd is a
		// speed flag and not a semantics flag at this width too.
		assertThat(run(compile(VEC_EXACT_PROGRAM, true))).isEqualTo(interpret(VEC_EXACT_PROGRAM));
		assertThat(run(compile(VEC_ROUNDING_PROGRAM, true))).isEqualTo(interpret(VEC_ROUNDING_PROGRAM));
		// The single-float operands beside it are still the lane kernels' (the same
		// bits as the defun, which is the contract that lets this be asserted).
		String mixedProgram = "(print (vec:add #f(1.0 2.0) #f(3.0 4.0))) (print (vec:sum #bf16(1.0 2.0)))";
		assertThat(run(compile(mixedProgram, true))).isEqualTo("#f(4.0 6.0)\n3.0");
	}

	@Test
	void linalgDeclinesTheWidthOnBothBackends() {
		String program = "(print (linalg:add #bf16(1.0 2.0) #bf16(3.0 4.0)))";
		assertThatThrownBy(() -> interpret(program)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> compileAndRun(program)).isInstanceOf(Exception.class);
	}

}
