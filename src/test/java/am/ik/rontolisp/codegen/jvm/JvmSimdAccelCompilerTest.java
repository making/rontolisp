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
import am.ik.rontolisp.eval.SimdLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code --simd} JVM acceleration path: the six vectorizable {@code simd:} kernels
 * ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code dot}/{@code sum}) are routed
 * to the embedded {@link JvmSimdVectorTemplate jdk.incubator.vector bridge} over the
 * packed {@code double[]} representation instead of the scalar {@code simd.lisp}
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
		List<LispVal> program = SimdLibrary.process(LispReader.readAllFromString(lispCode));
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

	@Test
	void acceleratedKernelsMatchTheScalarReferenceByteForByte() throws Exception {
		// Small arrays (n < THRESHOLD) exercise the scalar tail of each kernel; the
		// accelerated output must be identical to the spliced simd.lisp reference,
		// including the packed-array print format.
		for (String expr : List.of("(print (simd:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))",
				"(print (simd:sub #d(10.0 20.0 30.0) #d(1.0 2.0 3.0)))",
				"(print (simd:mul #d(2.0 3.0 4.0) #d(5.0 6.0 7.0)))", "(print (simd:scale #d(1.0 2.0 3.0) 10.0))",
				"(print (simd:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))", "(print (simd:sum #d(1.0 2.0 3.0 4.0 5.0)))",
				"(print (simd:mean #d(2.0 4.0 6.0)))", "(print (simd:norm #d(3.0 4.0)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedResultVectorsInteroperateWithThePackedArraySurface() throws Exception {
		// A bridge result is a plain rank-1 packed double[]: simd:aref / simd:length and
		// the
		// downstream simd: kernels consume it identically to a make-array double-float.
		assertThat(accel("(print (simd:aref (simd:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) 1))")).isEqualTo("7.0");
		assertThat(accel("(print (simd:length (simd:mul #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))))")).isEqualTo("3");
		assertThat(accel("(print (simd:sum (simd:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))))")).isEqualTo("21.0");
		assertThat(accel("(print (aref (simd:scale #d(2.0 4.0) 3.0) 1))")).isEqualTo("12.0");
	}

	@Test
	void acceleratedLargeArraysMatchTheScalarReferenceOverTheVectorLoop() throws Exception {
		// n >= THRESHOLD drives the real SPECIES_PREFERRED loop + f64-exact reductions,
		// so
		// the vector path stays byte-identical to the left-to-right scalar reference.
		for (String expr : List.of("(print (simd:sum (simd:arange 1000)))",
				"(print (simd:dot (simd:arange 1000) (simd:arange 1000)))",
				"(print (simd:sum (simd:add (simd:arange 1000) (simd:arange 1000))))",
				"(print (simd:sum (simd:scale (simd:ones 256) 3.0)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedReductionsComputeTheExpectedValuesOverTheVectorLoop() throws Exception {
		// sum(0..999) = 999*1000/2 = 499500; dot = sum of i^2 for i in 0..999 =
		// 999*1000*1999/6 = 332833500 (both f64-exact; the JVM prints the large dot
		// magnitude in Double.toString scientific form).
		assertThat(accel("(print (simd:sum (simd:arange 1000)))")).isEqualTo("499500.0");
		assertThat(accel("(print (simd:dot (simd:arange 1000) (simd:arange 1000)))")).isEqualTo("3.328335E8");
	}

	@Test
	void acceleratedResultsAreObservationallyIdenticalToTheScalarReference() throws Exception {
		// A #f/all-double literal, simd:zeros/ones/arange/from-list and every kernel
		// result
		// are the same packed double[] on both paths. Reading
		// (aref/print/length/to-list),
		// mutating (simd:aset writes the packed store in place) and the whole surface
		// must
		// match the scalar reference byte-for-byte. Each program uses a kernel so the
		// accelerated build routes through the bridge.
		for (String expr : List.of(
				// mutate a kernel result through simd:aref, then read it back and reduce
				"(let ((v (simd:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))) (setf (simd:aref v 0) 99.0)"
						+ " (print v) (print (simd:aref v 0)) (print (simd:sum v)))",
				// arange/from-list build via make-array double-float + aset
				"(print (simd:to-list (simd:scale (simd:arange 5) 2.0)))",
				"(print (simd:sum (simd:from-list '(1.0 2.0 3.0 4.0 5.0))))",
				"(print (simd:mean (simd:from-list '(2.0 4.0 6.0 8.0))))",
				// length/aref on a bare #f literal
				"(print (length #d(1.0 2.0 3.0 4.0))) (print (aref #d(5.0 6.0 7.0) 2))",
				// a large (vector-loop) array, mutated then reduced
				"(let ((v (simd:arange 1000))) (setf (simd:aref v 0) 5.0) (print (simd:sum v)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void generalHeterogeneousArraysAreUnaffectedByTheSimdFlag() throws Exception {
		// The packed float array is a separate type: a general (non-double-float) array
		// still accepts heterogeneous stores and prints identically under --simd. The
		// leading simd kernel turns the bridge on without touching the general array.
		for (String expr : List.of(
				"(simd:sum #d(1.0)) (let ((v (make-array 3 :initial-element 0)))"
						+ " (setf (aref v 1) \"x\") (print v) (print (aref v 1)))",
				"(simd:sum #d(1.0)) (let ((v (make-array 3 :initial-element 0)))"
						+ " (setf (aref v 0) 42) (print v))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void theBridgeIsEmbeddedOnlyUnderTheSimdFlag() {
		// The opt-in guarantee: default compilation is unchanged (scalar simd.lisp, no
		// incubator-module dependency, runs on any JRE); the bridge is embedded only
		// under
		// --simd, for a program that uses the simd package at all (the always-spliced
		// mean/norm bodies call sum/dot, so any simd usage pulls it in).
		assertThat(embedsBridge(compile("(print (simd:sum #d(1.0 2.0 3.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (simd:length #d(1.0 2.0 3.0)))", true))).isTrue();
		// Default (no --simd): never embedded, even with an accelerated kernel.
		assertThat(embedsBridge(compile("(print (simd:sum #d(1.0 2.0 3.0)))", false))).isFalse();
		// A non-simd program: no bridge even under --simd (nothing to accelerate).
		assertThat(embedsBridge(compile("(print (+ 1 2))", true))).isFalse();
	}

	// --- single-float (#f) width-polymorphism (todo 95 Part 1 Phase 3) ---------------

	@Test
	void acceleratedSingleFloatKernelsMatchTheScalarReferenceByteForByte() throws Exception {
		// #f (single-float, float[]) inputs run the FloatVector kernels; the accelerated
		// output must be byte-identical to the width-preserving scalar simd.lisp
		// reference.
		// Element-wise ops keep the #f width; reductions fold to an f64 scalar. Integer
		// f32
		// values are exact, so the vector and scalar paths agree.
		for (String expr : List.of("(print (simd:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)))",
				"(print (simd:sub #f(10.0 20.0 30.0) #f(1.0 2.0 3.0)))",
				"(print (simd:mul #f(2.0 3.0 4.0) #f(5.0 6.0 7.0)))", "(print (simd:scale #f(1.0 2.0 3.0) 10.0))",
				"(print (simd:dot #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)))", "(print (simd:sum #f(1.0 2.0 3.0 4.0 5.0)))",
				"(print (simd:mean #f(2.0 4.0 6.0)))", "(print (simd:norm #f(3.0 4.0)))")) {
			assertThat(accel(expr)).as(expr).isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedSingleFloatElementWiseKernelsPreserveTheSingleFloatWidth() throws Exception {
		// Width preservation (result = input width): a #f element-wise result prints with
		// the #f prefix on BOTH the accelerated and the scalar path -- it never widens to
		// #d. This is what makes --simd a pure acceleration of the scalar reference here.
		for (String expr : List.of("(print (simd:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)))",
				"(print (simd:sub #f(9.0 8.0 7.0) #f(1.0 2.0 3.0)))", "(print (simd:mul #f(2.0 3.0) #f(4.0 5.0)))",
				"(print (simd:scale #f(2.0 4.0) 3.0))")) {
			assertThat(accel(expr)).as(expr).startsWith("#f(").isEqualTo(scalar(expr));
		}
	}

	@Test
	void acceleratedSingleFloatResultsInteroperateWithThePackedArraySurface() throws Exception {
		// A #f bridge result is a plain rank-1 packed float[]: simd:aref / simd:length /
		// aref and the downstream kernels consume it, widening on read to an f64 scalar.
		assertThat(accel("(print (simd:aref (simd:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0)) 1))")).isEqualTo("7.0");
		assertThat(accel("(print (simd:length (simd:mul #f(1.0 2.0 3.0) #f(4.0 5.0 6.0))))")).isEqualTo("3");
		assertThat(accel("(print (simd:sum (simd:add #f(1.0 2.0 3.0) #f(4.0 5.0 6.0))))")).isEqualTo("21.0");
		assertThat(accel("(print (aref (simd:scale #f(2.0 4.0) 3.0) 1))")).isEqualTo("12.0");
	}

	@Test
	void acceleratedSingleFloatLargeArraysMatchTheScalarReferenceOverTheVectorLoop() throws Exception {
		// n >= THRESHOLD drives the real FloatVector loop (element-wise) and the
		// F2D-widen
		// f64 accumulation (reductions). Integer values 0..199 stay f64-exact, so the
		// vector
		// and scalar paths are byte-identical.
		String build = "(let ((a (make-array 200 :element-type 'single-float :initial-element 0.0)))"
				+ " (dotimes (i 200) (setf (aref a i) (float i))) ";
		for (String tail : List.of("(print (simd:sum a)))", "(print (simd:dot a a)))",
				"(print (simd:sum (simd:add a a))))", "(print (simd:sum (simd:scale a 2.0))))",
				"(print (simd:aref (simd:mul a a) 199)))")) {
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
		assertThat(accel(build + "(print (simd:sum a)))")).isEqualTo("19900.0");
		assertThat(accel(build + "(print (simd:dot a a)))")).isEqualTo("2646700.0");
	}

	@Test
	void mixedWidthOperandsAreAHardErrorUnderTheSimdFlag() {
		// simd is the fixed-contract, no-fallback package: mixing #d and #f operands in
		// one
		// accelerated kernel is a hard error. (The scalar reference would silently
		// promote
		// to double, so this divergence is by design and is not compared against scalar.)
		assertThatThrownBy(() -> accel("(print (simd:add #d(1.0 2.0) #f(3.0 4.0)))"))
			.hasStackTraceContaining("share an element type");
		assertThatThrownBy(() -> accel("(print (simd:dot #f(1.0 2.0) #d(3.0 4.0)))"))
			.hasStackTraceContaining("share an element type");
	}

	@Test
	void theSingleFloatBridgeIsEmbeddedOnlyUnderTheSimdFlag() {
		// The dead-flag proof, extended to #f: a single-float simd program embeds the
		// bridge
		// under --simd and never without it, independent of runtime output parity.
		assertThat(embedsBridge(compile("(print (simd:sum #f(1.0 2.0 3.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (simd:add #f(1.0 2.0) #f(3.0 4.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (simd:sum #f(1.0 2.0 3.0)))", false))).isFalse();
	}

	private static boolean embedsBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmSimdRuntimeBuilder.BRIDGE_NAME);
	}

}
