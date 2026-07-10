package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code --simd} JVM acceleration path: the six vectorizable {@code vec:} kernels
 * ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code dot}/{@code sum}) are routed
 * to the embedded {@link JvmSimdVectorTemplate jdk.incubator.vector bridge} over the
 * packed {@code double[]} representation instead of the scalar {@code vec.lisp}
 * reference, and must produce byte-identical output. The bridge is defined in-process via
 * {@code Lookup.defineClass}; the Surefire config adds
 * {@code --add-modules jdk.incubator.vector} so the incubator module is in the test JVM's
 * module graph. Both a small-array (scalar-tail) and a large-array (Vector API loop) case
 * are exercised, plus the opt-in gating (no bridge unless {@code --simd} AND the simd
 * package is used) and interop with the ordinary packed-array surface.
 */
class JvmSimdAccelCompilerTest {

	@TempDir
	Path tempDir;

	private byte[] compile(String lispCode, boolean accel) {
		List<LispVal> program = VecLibrary.process(LispReader.readAllFromString(lispCode));
		return new JvmLispCompiler("Test", false, false, accel).compile(program);
	}

	private String run(byte[] classBytes) throws Exception {
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
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

	private String accel(String lispCode) throws Exception {
		return run(compile(lispCode, true));
	}

	private String scalar(String lispCode) throws Exception {
		return run(compile(lispCode, false));
	}

	/** Asserts the accelerated class prints exactly what the scalar one prints. */
	private void assertMatchesScalarReference(String lispCode) throws Exception {
		assertThat(accel(lispCode)).as(lispCode).isEqualTo(scalar(lispCode));
	}

	@Test
	void acceleratedKernelsMatchTheScalarReferenceByteForByte() throws Exception {
		// Small arrays (n < THRESHOLD) exercise the scalar tail of each kernel; the
		// accelerated output must be identical to the spliced vec.lisp reference,
		// including the packed-array print format.
		for (String expr : List.of("(print (vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))",
				"(print (vec:sub #d(10.0 20.0 30.0) #d(1.0 2.0 3.0)))",
				"(print (vec:mul #d(2.0 3.0 4.0) #d(5.0 6.0 7.0)))", "(print (vec:scale #d(1.0 2.0 3.0) 10.0))",
				"(print (vec:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))", "(print (vec:sum #d(1.0 2.0 3.0 4.0 5.0)))",
				"(print (vec:mean #d(2.0 4.0 6.0)))", "(print (vec:norm #d(3.0 4.0)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedResultVectorsInteroperateWithThePackedArraySurface() throws Exception {
		// A bridge result is a plain rank-1 packed double[]: vec:aref / vec:length and
		// the
		// downstream vec: kernels consume it identically to a make-array double-float.
		assertThat(accel("(print (vec:aref (vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) 1))")).isEqualTo("7.0");
		assertThat(accel("(print (vec:length (vec:mul #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))))")).isEqualTo("3");
		assertThat(accel("(print (vec:sum (vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))))")).isEqualTo("21.0");
		assertThat(accel("(print (aref (vec:scale #d(2.0 4.0) 3.0) 1))")).isEqualTo("12.0");
	}

	@Test
	void acceleratedLargeArraysMatchTheScalarReferenceOverTheVectorLoop() throws Exception {
		// n >= THRESHOLD drives the real SPECIES_PREFERRED loop + f64-exact reductions,
		// so
		// the vector path stays byte-identical to the left-to-right scalar reference.
		for (String expr : List.of("(print (vec:sum (vec:arange 1000)))",
				"(print (vec:dot (vec:arange 1000) (vec:arange 1000)))",
				"(print (vec:sum (vec:add (vec:arange 1000) (vec:arange 1000))))",
				"(print (vec:sum (vec:scale (vec:ones 256) 3.0)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedReductionsComputeTheExpectedValuesOverTheVectorLoop() throws Exception {
		// sum(0..999) = 999*1000/2 = 499500; dot = sum of i^2 for i in 0..999 =
		// 999*1000*1999/6 = 332833500 (both f64-exact; the JVM prints the large dot
		// magnitude in Double.toString scientific form).
		assertThat(accel("(print (vec:sum (vec:arange 1000)))")).isEqualTo("499500.0");
		assertThat(accel("(print (vec:dot (vec:arange 1000) (vec:arange 1000)))")).isEqualTo("3.328335E8");
	}

	@Test
	void acceleratedResultsAreObservationallyIdenticalToTheScalarReference() throws Exception {
		// A #f/all-double literal, vec:zeros/ones/arange/from-list and every kernel
		// result
		// are the same packed double[] on both paths. Reading
		// (aref/print/length/to-list),
		// mutating (vec:aset writes the packed store in place) and the whole surface
		// must
		// match the scalar reference byte-for-byte. Each program uses a kernel so the
		// accelerated build routes through the bridge.
		for (String expr : List.of(
				// mutate a kernel result through vec:aref, then read it back and reduce
				"(let ((v (vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))) (setf (vec:aref v 0) 99.0)"
						+ " (print v) (print (vec:aref v 0)) (print (vec:sum v)))",
				// arange/from-list build via make-array double-float + aset
				"(print (vec:to-list (vec:scale (vec:arange 5) 2.0)))",
				"(print (vec:sum (vec:from-list '(1.0 2.0 3.0 4.0 5.0))))",
				"(print (vec:mean (vec:from-list '(2.0 4.0 6.0 8.0))))",
				// length/aref on a bare #f literal
				"(print (length #d(1.0 2.0 3.0 4.0))) (print (aref #d(5.0 6.0 7.0) 2))",
				// a large (vector-loop) array, mutated then reduced
				"(let ((v (vec:arange 1000))) (setf (vec:aref v 0) 5.0) (print (vec:sum v)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void generalHeterogeneousArraysAreUnaffectedByTheSimdFlag() throws Exception {
		// The packed float array is a separate type: a general (non-double-float) array
		// still accepts heterogeneous stores and prints identically under --simd. The
		// leading simd kernel turns the bridge on without touching the general array.
		for (String expr : List.of(
				"(vec:sum #d(1.0)) (let ((v (make-array 3 :initial-element 0)))"
						+ " (setf (aref v 1) \"x\") (print v) (print (aref v 1)))",
				"(vec:sum #d(1.0)) (let ((v (make-array 3 :initial-element 0)))"
						+ " (setf (aref v 0) 42) (print v))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void theBridgeIsEmbeddedOnlyUnderTheSimdFlag() {
		// The opt-in guarantee: default compilation is unchanged (scalar vec.lisp, no
		// incubator-module dependency, runs on any JRE); the bridge is embedded only
		// under
		// --simd, for a program that uses the vec package at all (the always-spliced
		// mean/norm bodies call sum/dot, so any simd usage pulls it in).
		assertThat(embedsBridge(compile("(print (vec:sum #d(1.0 2.0 3.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (vec:length #d(1.0 2.0 3.0)))", true))).isTrue();
		// Default (no --simd): never embedded, even with an accelerated kernel.
		assertThat(embedsBridge(compile("(print (vec:sum #d(1.0 2.0 3.0)))", false))).isFalse();
		// A non-simd program: no bridge even under --simd (nothing to accelerate).
		assertThat(embedsBridge(compile("(print (+ 1 2))", true))).isFalse();
	}

	// --- single-float (#f) width-polymorphism (todo 95 Part 1 Phase 3) ---------------

	@Test
	void acceleratedSingleFloatKernelsMatchTheScalarReferenceByteForByte() throws Exception {
		// #f (single-float, float[]) inputs run the FloatVector kernels; the accelerated
		// output must be byte-identical to the width-preserving scalar vec.lisp
		// reference.
		// Element-wise ops keep the #f width; reductions fold to an f64 scalar. Integer
		// f32
		// values are exact, so the vector and scalar paths agree.
		for (String expr : List.of("(print (vec:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)))",
				"(print (vec:sub #f(10.0 20.0 30.0) #f(1.0 2.0 3.0)))",
				"(print (vec:mul #f(2.0 3.0 4.0) #f(5.0 6.0 7.0)))", "(print (vec:scale #f(1.0 2.0 3.0) 10.0))",
				"(print (vec:dot #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)))", "(print (vec:sum #f(1.0 2.0 3.0 4.0 5.0)))",
				"(print (vec:mean #f(2.0 4.0 6.0)))", "(print (vec:norm #f(3.0 4.0)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedSingleFloatElementWiseKernelsPreserveTheSingleFloatWidth() throws Exception {
		// Width preservation (result = input width): a #f element-wise result prints with
		// the #f prefix on BOTH the accelerated and the scalar path -- it never widens to
		// #d. This is what makes --simd a pure acceleration of the scalar reference here.
		for (String expr : List.of("(print (vec:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)))",
				"(print (vec:sub #f(9.0 8.0 7.0) #f(1.0 2.0 3.0)))", "(print (vec:mul #f(2.0 3.0) #f(4.0 5.0)))",
				"(print (vec:scale #f(2.0 4.0) 3.0))")) {
			assertThat(accel(expr)).as(expr).startsWith("#f(").isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedSingleFloatResultsInteroperateWithThePackedArraySurface() throws Exception {
		// A #f bridge result is a plain rank-1 packed float[]: vec:aref / vec:length /
		// aref and the downstream kernels consume it, widening on read to an f64 scalar.
		assertThat(accel("(print (vec:aref (vec:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)) 1))")).isEqualTo("7.0");
		assertThat(accel("(print (vec:length (vec:mul #f(1.0 2.0 3.0) #f(4.0 5.0 6.0))))")).isEqualTo("3");
		assertThat(accel("(print (vec:sum (vec:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0))))")).isEqualTo("21.0");
		assertThat(accel("(print (aref (vec:scale #f(2.0 4.0) 3.0) 1))")).isEqualTo("12.0");
	}

	@Test
	void acceleratedSingleFloatLargeArraysMatchTheScalarReferenceOverTheVectorLoop() throws Exception {
		// n >= THRESHOLD drives the real FloatVector loop (element-wise) and the f32
		// accumulation (reductions). Integer values 0..199 are exact at BOTH widths --
		// every partial sum stays under 2^24 -- so the vector and scalar paths are
		// byte-identical. Values that separate them are pinned by
		// singleFloatReductionsAccumulateInSinglePrecisionUnderSimd below.
		String build = "(let ((a (make-array 200 :element-type 'single-float :initial-element 0.0)))"
				+ " (dotimes (i 200) (setf (aref a i) (float i))) ";
		for (String tail : List.of("(print (vec:sum a)))", "(print (vec:dot a a)))", "(print (vec:sum (vec:add a a))))",
				"(print (vec:sum (vec:scale a 2.0))))", "(print (vec:aref (vec:mul a a) 199)))")) {
			String expr = build + tail;
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedSingleFloatReductionsComputeTheExpectedValues() throws Exception {
		// sum(0..199) = 199*200/2 = 19900; dot(a,a) = sum of i^2 = 199*200*399/6 =
		// 2646700
		// (both f64-exact).
		String build = "(let ((a (make-array 200 :element-type 'single-float :initial-element 0.0)))"
				+ " (dotimes (i 200) (setf (aref a i) (float i))) ";
		assertThat(accel(build + "(print (vec:sum a)))")).isEqualTo("19900.0");
		assertThat(accel(build + "(print (vec:dot a a)))")).isEqualTo("2646700.0");
	}

	@Test
	void singleFloatReductionsAccumulateInSinglePrecisionUnderSimd() throws Exception {
		// The compiled-path half of the todo-106 precision contract (the interpreter half
		// is eval/VecSimdTest, same probe, same numbers -- the two kernel files are
		// deliberate duplicates and must not drift).
		//
		// v = #f(4096.0 1.0 ... 1.0), 1024 elements: dot(v,v) = 4096^2 + 1023 = 16778239.
		// 4096^2 = 2^24, where the f32 spacing is 2, so the pinned lane holding it
		// swallows its 1.0s; the other three lanes fold 256 each -> 2^24 + 768.
		// FloatVector.SPECIES_128 is what makes 16777984 the answer on every host (8
		// lanes would give 16778112, 16 lanes 16778176).
		String v = "(let ((v (vec:ones 1024 'single-float))) (setf (aref v 0) 4096.0) ";
		assertThat(accel(v + "(print (round (vec:dot v v))))")).isEqualTo("16777984");
		assertThat(scalar(v + "(print (round (vec:dot v v))))")).isEqualTo("16778239");

		String s = "(let ((v (vec:ones 1024 'single-float))) (setf (aref v 0) 16777216.0) ";
		assertThat(accel(s + "(print (round (vec:sum v))))")).isEqualTo("16777984");
		assertThat(scalar(s + "(print (round (vec:sum v))))")).isEqualTo("16778239");

		// matvec is a dot per row. The scalar path accumulates 16778239 in f64 then
		// narrows on store; that is an odd multiple of the f32 spacing at 2^24, so it
		// ties to even -> 16778240.
		String gemv = "(let ((m (make-array '(1 1024) :element-type 'single-float :initial-element 1.0))"
				+ " (v (vec:ones 1024 'single-float)))" + " (setf (aref m 0 0) 4096.0) (setf (aref v 0) 4096.0)"
				+ " (print (round (aref (vec:matvec m v) 0))))";
		assertThat(accel(gemv)).isEqualTo("16777984");
		assertThat(scalar(gemv)).isEqualTo("16778240");

		// The #d control: double-float reductions are untouched and exact on both paths.
		String d = "(let ((v (vec:ones 1024))) (setf (aref v 0) 4096.0) (print (round (vec:dot v v))))";
		assertThat(accel(d)).isEqualTo("16778239");
		assertThat(scalar(d)).isEqualTo("16778239");
	}

	@Test
	void mixedWidthOperandsAreAHardErrorUnderTheSimdFlag() {
		// simd is the fixed-contract, no-fallback package: mixing #d and #f operands in
		// one
		// accelerated kernel is a hard error. (The scalar reference would silently
		// promote
		// to double, so this divergence is by design and is not compared against scalar.)
		assertThatThrownBy(() -> accel("(print (vec:add #d(1.0 2.0) #f(3.0 4.0)))"))
			.hasStackTraceContaining("share an element type");
		assertThatThrownBy(() -> accel("(print (vec:dot #f(1.0 2.0) #d(3.0 4.0)))"))
			.hasStackTraceContaining("share an element type");
	}

	@Test
	void theSingleFloatBridgeIsEmbeddedOnlyUnderTheSimdFlag() {
		// The dead-flag proof, extended to #f: a single-float simd program embeds the
		// bridge
		// under --simd and never without it, independent of runtime output parity.
		assertThat(embedsBridge(compile("(print (vec:sum #f(1.0 2.0 3.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (vec:add #f(1.0 2.0) #f(3.0 4.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (vec:sum #f(1.0 2.0 3.0)))", false))).isFalse();
	}

	// --- matvec (GEMV) f64 + f32 (todo 95 Part 2) ------------------------------------

	@Test
	void acceleratedMatvecMatchesTheScalarReferenceByteForByte() throws Exception {
		// Small matrices (n < THRESHOLD) exercise the scalar tail; the accelerated GEMV
		// (one vectorized dot per row) must be byte-identical to the spliced vec.lisp
		// reference, for both widths, including the packed-array print format. The last
		// case is a non-square 3x4 matrix, catching a row-stride bug the square cases
		// miss.
		for (String expr : List.of("(print (vec:matvec #d((1 2) (3 4)) #d(5 6)))",
				"(print (vec:matvec #d((1 2 3) (4 5 6)) #d(1 2 3)))", "(print (vec:matvec #f((1 2) (3 4)) #f(5 6)))",
				"(print (vec:matvec #f((1 2 3) (4 5 6)) #f(1 2 3)))",
				"(print (vec:matvec #d((1 2 3 4) (5 6 7 8) (9 10 11 12)) #d(1 1 1 1)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedMatvecComputesTheExpectedValues() throws Exception {
		// GEMV: y[i] = dot(row_i, x). #d((1 2)(3 4)) . #d(5 6) = (1*5+2*6, 3*5+4*6) =
		// (17, 39); the #f pair yields the same values at single-float width.
		assertThat(accel("(print (vec:matvec #d((1 2) (3 4)) #d(5 6)))")).isEqualTo("#d(17.0 39.0)");
		assertThat(accel("(print (vec:matvec #f((1 2) (3 4)) #f(5 6)))")).isEqualTo("#f(17.0 39.0)");
	}

	@Test
	void acceleratedMatvecPreservesTheSingleFloatWidth() throws Exception {
		// A #f matrix/vector yields a #f result on both the accelerated and the scalar
		// path -- GEMV is width-preserving via vec::%make-like, so --simd is a pure
		// acceleration of the scalar reference (never a silent widen to #d).
		String expr = "(print (vec:matvec #f((1 2 3) (4 5 6)) #f(1 2 3)))";
		assertThat(accel(expr)).startsWith("#f(").isEqualTo(scalar(expr));
	}

	@Test
	void acceleratedMatvecLargeMatricesMatchTheScalarReferenceOverTheVectorLoop() throws Exception {
		// n >= THRESHOLD (200-column rows) drives the real SPECIES/FSPECIES lane loop;
		// the
		// per-row dot stays byte-identical to the left-to-right scalar reference on the
		// f64-exact integer inputs. Row i sums to 19900 + 40000*i (sum(0..199) +
		// 200*i*200)
		// against an all-ones x, for both widths.
		for (String et : List.of("'double-float", "'single-float")) {
			String expr = "(let ((w (make-array (list 3 200) :element-type " + et + " :initial-element 0.0))"
					+ " (x (make-array 200 :element-type " + et + " :initial-element 1.0)))"
					+ " (dotimes (i 3) (dotimes (j 200) (setf (aref w i j) (+ j (* i 200)))))"
					+ " (print (vec:matvec w x)))";
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedMatvecLargeMatrixComputesTheExpectedValues() throws Exception {
		// The concrete large-matrix result: row0 = sum(0..199) = 19900, row1 =
		// 19900+40000
		// = 59900, row2 = 19900+80000 = 99900 (all f64-exact, so the vector loop
		// matches).
		String expr = "(let ((w (make-array (list 3 200) :element-type 'double-float :initial-element 0.0))"
				+ " (x (make-array 200 :element-type 'double-float :initial-element 1.0)))"
				+ " (dotimes (i 3) (dotimes (j 200) (setf (aref w i j) (+ j (* i 200)))))"
				+ " (print (vec:matvec w x)))";
		assertThat(accel(expr)).isEqualTo("#d(19900.0 59900.0 99900.0)");
	}

	@Test
	void acceleratedMatvecResultInteroperatesWithThePackedArraySurface() throws Exception {
		// A GEMV result is a plain rank-1 packed vector: aref / length / downstream vec:
		// kernels consume it identically to a make-array vector.
		assertThat(accel("(print (aref (vec:matvec #d((1 2) (3 4)) #d(5 6)) 1))")).isEqualTo("39.0");
		assertThat(accel("(print (length (vec:matvec #d((1 2) (3 4) (5 6)) #d(1 1))))")).isEqualTo("3");
		assertThat(accel("(print (vec:sum (vec:matvec #d((1 2) (3 4)) #d(5 6))))")).isEqualTo("56.0");
	}

	@Test
	void mixedWidthMatvecOperandsAreAHardErrorUnderTheSimdFlag() {
		// Mixing a #d matrix with a #f vector (or vice versa) is a hard error, like the
		// other kernels -- the fixed-contract package never silently promotes.
		assertThatThrownBy(() -> accel("(print (vec:matvec #d((1 2) (3 4)) #f(5 6)))"))
			.hasStackTraceContaining("share an element type");
		assertThatThrownBy(() -> accel("(print (vec:matvec #f((1 2) (3 4)) #d(5 6)))"))
			.hasStackTraceContaining("share an element type");
	}

	@Test
	void theMatvecBridgeIsEmbeddedOnlyUnderTheSimdFlag() {
		// Dead-flag proof for matvec: the bridge is embedded under --simd and never
		// without it, for both widths, independent of runtime output.
		assertThat(embedsBridge(compile("(print (vec:matvec #d((1 2) (3 4)) #d(5 6)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (vec:matvec #f((1 2) (3 4)) #f(5 6)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (vec:matvec #d((1 2) (3 4)) #d(5 6)))", false))).isFalse();
	}

	// --- destination-passing kernels (todo 103) --------------------------------------

	@Test
	void acceleratedIntoKernelsMatchTheirAllocatingSiblingsByteForByte() throws Exception {
		// Small arrays (scalar tail) and large ones (vector loop), both widths. The
		// -into result must print exactly like the allocating sibling's fresh vector.
		record Pair(String into, String alloc) {
		}
		for (Pair p : List.of(
				new Pair("(print (vec:add-into (vec:zeros 3) #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))",
						"(print (vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))"),
				new Pair("(print (vec:sub-into (vec:zeros 3) #d(10.0 20.0 30.0) #d(1.0 2.0 3.0)))",
						"(print (vec:sub #d(10.0 20.0 30.0) #d(1.0 2.0 3.0)))"),
				new Pair("(print (vec:mul-into (vec:zeros 3) #d(2.0 3.0 4.0) #d(5.0 6.0 7.0)))",
						"(print (vec:mul #d(2.0 3.0 4.0) #d(5.0 6.0 7.0)))"),
				new Pair("(print (vec:scale-into (vec:zeros 3) #d(1.0 2.0 3.0) 10.0))",
						"(print (vec:scale #d(1.0 2.0 3.0) 10.0))"),
				new Pair("(print (vec:sum (vec:add-into (vec:zeros 1000) (vec:arange 1000) (vec:arange 1000))))",
						"(print (vec:sum (vec:add (vec:arange 1000) (vec:arange 1000))))"),
				new Pair("(print (vec:add-into (vec:zeros 3 'single-float) #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)))",
						"(print (vec:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)))"),
				new Pair("(print (vec:scale-into (vec:zeros 3 'single-float) #f(1.0 2.0 3.0) 10.0))",
						"(print (vec:scale #f(1.0 2.0 3.0) 10.0))"),
				new Pair(
						"(print (vec:sum (vec:mul-into (vec:zeros 1000 'single-float) (vec:arange 1000 'single-float) (vec:ones 1000 'single-float))))",
						"(print (vec:sum (vec:mul (vec:arange 1000 'single-float) (vec:ones 1000 'single-float))))"))) {
			assertThat(accel(p.into())).as(p.into()).isEqualTo(scalar(p.alloc()));
			assertThat(accel(p.into())).as(p.into()).isEqualTo(scalar(p.into()));
		}
	}

	@Test
	void acceleratedIntoKernelsReturnTheDestinationAndAllocateNothingInALoop() throws Exception {
		// The point of -into: the loop rebinds nothing and the kernel returns the very
		// vector it was handed, so in-place accumulation matches the allocating form.
		String inPlace = """
				(let ((acc (vec:zeros 1000)) (d (vec:arange 1000)))
				  (dotimes (i 4) (vec:add-into acc acc d))
				  (print (vec:sum acc)))
				""";
		String fresh = """
				(let ((acc (vec:zeros 1000)) (d (vec:arange 1000)))
				  (dotimes (i 4) (setq acc (vec:add acc d)))
				  (print (vec:sum acc)))
				""";
		assertThat(accel(inPlace)).isEqualTo(scalar(fresh));
		assertThat(accel("(let ((o (vec:zeros 2))) (print (eq o (vec:add-into o #d(1.0 2.0) #d(3.0 4.0)))))"))
			.isEqualTo("t");
	}

	@Test
	void acceleratedMatvecIntoMatchesTheAllocatingSibling() throws Exception {
		assertThat(accel("(print (vec:matvec-into (vec:zeros 2) #d((1 2) (3 4)) #d(5 6)))")).isEqualTo("#d(17.0 39.0)");
		assertThat(accel("(print (vec:matvec-into (vec:zeros 2 'single-float) #f((1 2) (3 4)) #f(5 6)))"))
			.isEqualTo(scalar("(print (vec:matvec #f((1 2) (3 4)) #f(5 6)))"));
		String wide = """
				(let ((w (make-array '(3 200) :element-type 'double-float :initial-element 0.0))
				      (x (vec:arange 200))
				      (o (vec:zeros 3)))
				  (dotimes (i 3) (dotimes (j 200) (setf (aref w i j) (float (+ (* i 200) j)))))
				  (print (vec:sum (vec:matvec-into o w x))))
				""";
		assertThat(accel(wide)).isEqualTo(scalar(wide.replace("(vec:matvec-into o w x)", "(vec:matvec w x)")));
	}

	@Test
	void acceleratedMatvecIntoRejectsADestinationAliasingItsOperands() {
		// out[row] folds over ALL of x. The bridge replaces the vec.lisp defun that
		// carries the eq guard, so the guard is repeated in the bridge.
		assertThatThrownBy(() -> accel("(let ((x #d(1.0 2.0))) (print (vec:matvec-into x #d((1 1) (1 1)) x)))"))
			.hasStackTraceContaining("must not be the same array");
	}

	@Test
	void mixedWidthIntoOperandsAreAHardErrorUnderTheSimdFlag() {
		assertThatThrownBy(() -> accel("(print (vec:add-into (vec:zeros 1) #d(1.0) #f(1.0)))"))
			.hasStackTraceContaining("share an element type");
		assertThatThrownBy(() -> accel("(print (vec:add-into (vec:zeros 1 'single-float) #d(1.0) #d(1.0)))"))
			.hasStackTraceContaining("share an element type");
		assertThatThrownBy(() -> accel("(print (vec:scale-into (vec:zeros 1 'single-float) #d(1.0) 2.0))"))
			.hasStackTraceContaining("share an element type");
	}

	// --- element-wise unary ufuncs (todo 109) -----------------------------------------

	@Test
	void unaryUfuncsMatchTheScalarReferenceAtBothSizesAndWidths() throws Exception {
		for (String op : new String[] { "sqrt", "abs", "square", "negative", "sign", "reciprocal" }) {
			String inner = op.equals("sqrt") ? "(vec:add %v (vec:ones %n))"
					: "(vec:sub %v (vec:scale (vec:ones %n) 100.0))";
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarReference("(print (vec:" + op + " "
						+ inner.replace("%v", "(vec:arange " + n + ")").replace("%n", n) + "))");
			}
			assertMatchesScalarReference(
					"(print (vec:" + op + " (vec:add (vec:arange 200 'single-float) (vec:ones 200 'single-float))))");
		}
		// exp over reciprocal's (0, 1] range so the values stay bounded.
		assertMatchesScalarReference(
				"(print (round (* 1000000 (vec:sum (vec:exp (vec:reciprocal (vec:add (vec:arange 200) (vec:ones 200))))))))");
		assertMatchesScalarReference("(print (vec:exp #d(0.0 1.0)))");
		// log over strictly positive inputs, tanh over a sign-mixed range (todo 109
		// Phase 2 -- Math.log / Math.tanh scalar loops on this backend).
		for (String n : new String[] { "7", "200" }) {
			assertMatchesScalarReference("(print (vec:log (vec:add (vec:arange %s) (vec:ones %s))))".formatted(n, n));
			assertMatchesScalarReference(
					"(print (vec:tanh (vec:scale (vec:sub (vec:arange %s) (vec:scale (vec:ones %s) 100.0)) 0.03)))"
						.formatted(n, n));
		}
		assertMatchesScalarReference(
				"(print (vec:log (vec:add (vec:arange 200 'single-float) (vec:ones 200 'single-float))))");
		assertMatchesScalarReference("(print (vec:tanh (vec:arange 200 'single-float)))");
	}

	@Test
	void unaryIntoKernelsWriteIntoTheDestinationAndReturnIt() throws Exception {
		assertThat(accel("(let ((o (vec:zeros 3))) (print (eq o (vec:sqrt-into o #d(4.0 9.0 16.0)))) (print o))"))
			.isEqualTo("t\n#d(2.0 3.0 4.0)");
		// In-place update: out MAY alias the operand (the add-into rule).
		assertMatchesScalarReference("""
				(let ((v (vec:add (vec:arange 200) (vec:ones 200))))
				  (vec:sqrt-into v v)
				  (print v))
				""");
		assertMatchesScalarReference("""
				(let ((v (vec:sub (vec:arange 7 'single-float) (vec:scale (vec:ones 7 'single-float) 3.0))))
				  (vec:abs-into v v)
				  (print v))
				""");
		for (String op : new String[] { "exp", "negative", "sign", "reciprocal", "square", "tanh" }) {
			assertMatchesScalarReference(
					"(print (vec:" + op + "-into (vec:zeros 7) (vec:add (vec:arange 7) (vec:ones 7))))");
		}
		assertMatchesScalarReference("(print (vec:log-into (vec:zeros 7) (vec:add (vec:arange 7) (vec:ones 7))))");
	}

	@Test
	void mixedWidthUnaryIntoOperandsAreAHardErrorUnderTheSimdFlag() {
		assertThatThrownBy(() -> accel("(print (vec:sqrt-into (vec:zeros 1) #f(1.0)))"))
			.hasStackTraceContaining("share an element type");
		assertThatThrownBy(() -> accel("(print (vec:abs-into (vec:zeros 1 'single-float) #d(1.0)))"))
			.hasStackTraceContaining("share an element type");
	}

	@Test
	void theIntoBridgeIsEmbeddedOnlyUnderTheSimdFlag() {
		// Dead-flag proof: without this every numeric assertion above would still pass on
		// the scalar vec.lisp defuns, which -into also has.
		assertThat(embedsBridge(compile("(print (vec:add-into (vec:zeros 1) #d(1.0) #d(1.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (vec:matvec-into (vec:zeros 2) #d((1 2) (3 4)) #d(5 6)))", true)))
			.isTrue();
		assertThat(embedsBridge(compile("(print (vec:add-into (vec:zeros 1) #d(1.0) #d(1.0)))", false))).isFalse();
	}

	private static boolean embedsBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmSimdRuntimeBuilder.BRIDGE_NAME);
	}

}
