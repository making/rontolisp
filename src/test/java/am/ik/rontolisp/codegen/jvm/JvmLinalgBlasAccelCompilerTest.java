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
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.LinalgBlas;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code --blas} JVM acceleration of the matrix product, the sibling of
 * {@link JvmLinalgSimdAccelCompilerTest}. The {@code linalg:dot} call site and the
 * {@code vec:matvec} / {@code vec:matvec-into} ones are routed to the embedded
 * {@link JvmBlasTemplate} bridge, which binds a tuned CBLAS out of the operating system
 * at run time and declines to whatever is below it.
 *
 * <p>
 * Two halves, as with {@code --simd}: the accelerated results, which must match the
 * scalar reference, and the declined ones, which must behave exactly as they do without
 * the flag. The gate assertions run everywhere; the ones that need a library on the
 * machine are in the conditional half.
 */
class JvmLinalgBlasAccelCompilerTest {

	static boolean tunedBlasIsAvailable() {
		return LinalgBlas.available();
	}

	@TempDir
	Path tempDir;

	private byte[] compile(String lispCode, boolean blas) {
		return compile(lispCode, blas, false);
	}

	private byte[] compile(String lispCode, boolean blas, boolean simd) {
		List<LispVal> program = VecLibrary.process(LinalgLibrary.process(LispReader.readAllFromString(lispCode)));
		return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, simd, blas).compile(program);
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

	private String accel(String lispCode) throws Exception {
		return run(compile(lispCode, true));
	}

	private String scalar(String lispCode) throws Exception {
		return run(compile(lispCode, false));
	}

	private void assertMatchesScalarReference(String lispCode) throws Exception {
		assertThat(accel(lispCode)).as(lispCode).isEqualTo(scalar(lispCode));
	}

	private static boolean embedsBlasBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmBlasRuntimeBuilder.BRIDGE_NAME);
	}

	private static boolean embedsSimdBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmSimdRuntimeBuilder.BRIDGE_NAME);
	}

	/**
	 * Whether a CALL SITE for the named bridge entry point was emitted. The bridge's own
	 * bytes are base64 string constants, so its method names are invisible there; a
	 * Methodref in the generated class's constant pool is the interception itself.
	 */
	private static boolean callsBridgeMethod(byte[] classBytes, String bridgeMethod) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(bridgeMethod);
	}

	// --- the emit gate (runs on every machine) ---------------------------------------

	@Test
	void theBridgeIsEmbeddedOnlyUnderTheFlagAndOnlyForAProgramThatReachesTheProduct() {
		// Without this a dead interception would still pass every numeric assertion
		// below, because the scalar defun returns the right answer
		// ([[simd-shadow-and-dead-flag-lesson]]).
		assertThat(embedsBlasBridge(compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", true))).isTrue();
		assertThat(embedsBlasBridge(compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", false))).isFalse();
		// A program that never mentions the package keeps the bridge out; any linalg
		// program pulls it in, because the spliced linalg.lisp holds the dot call site.
		assertThat(embedsBlasBridge(compile("(print (+ 1 2))", true))).isFalse();
		assertThat(embedsBlasBridge(compile("(print (linalg:eye 2))", true))).isTrue();
	}

	@Test
	void theTwoFlagsAreOrthogonalAndEmbedTheirOwnBridges() {
		// --blas must not drag in the Vector API bridge: a class that did would need
		// java --add-modules jdk.incubator.vector to run, which --blas never requires.
		byte[] blasOnly = compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", true, false);
		assertThat(embedsBlasBridge(blasOnly)).isTrue();
		assertThat(embedsSimdBridge(blasOnly)).isFalse();
		byte[] simdOnly = compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", false, true);
		assertThat(embedsBlasBridge(simdOnly)).isFalse();
		assertThat(embedsSimdBridge(simdOnly)).isTrue();
		byte[] both = compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", true, true);
		assertThat(embedsBlasBridge(both)).isTrue();
		assertThat(embedsSimdBridge(both)).isTrue();
	}

	@Test
	void aDeclinedProductRunsTheSameProgramToTheSameOutputOnAnyMachine() throws Exception {
		// The half of the feature a machine with no tuned library still gets.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(print (linalg:matmul *a* *a*))
				""");
	}

	// --- the vec: GEMV ---------------------------------------------------------------

	/**
	 * An 8x8 matrix of exact small integers and the vector to hit it with, at either
	 * width.
	 */
	private static String gemvOperands(String elementType) {
		return """
				(defparameter *w* (make-array '(8 8) :element-type '%s :initial-element 0.0))
				(dotimes (i 8) (dotimes (j 8) (setf (aref *w* i j) (float (+ (* i 8) j 1)))))
				(defparameter *x* (vec:arange 8 :element-type '%s))
				""".formatted(elementType, elementType);
	}

	@Test
	void aVecProgramEmbedsTheBridgeAndGetsAGuardedGemvCallSiteWithNoSimdFlagAtAll() {
		// The design decision this member turned on: --blas alone has a vec: call site of
		// its own, so a --blas build accelerates the vec: examples without --simd. A
		// program that reaches neither GEMV keeps the bridge out.
		byte[] blasOnly = compile("(print (vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)))", true, false);
		assertThat(embedsBlasBridge(blasOnly)).isTrue();
		assertThat(embedsSimdBridge(blasOnly)).isFalse();
		assertThat(callsBridgeMethod(blasOnly, "blasMatvec")).isTrue();
		byte[] intoOnly = compile("(print (vec:matvec-into (vec:zeros 2) #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)))", true,
				false);
		assertThat(callsBridgeMethod(intoOnly, "blasMatvecInto")).isTrue();
		// Without the flag neither the bridge nor its call sites exist -- the dead-flag
		// guard, since every numeric assertion below passes on the defun alone
		// ([[simd-shadow-and-dead-flag-lesson]]).
		byte[] unflagged = compile("(print (vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)))", false, false);
		assertThat(embedsBlasBridge(unflagged)).isFalse();
		assertThat(callsBridgeMethod(unflagged, "blasMatvec")).isFalse();
		// A program that never mentions vec: or linalg: keeps the bridge out; any vec:
		// program pulls it in, because the spliced vec.lisp holds the two GEMV defuns
		// (the same reason (linalg:eye 2) does above).
		assertThat(embedsBlasBridge(compile("(print (+ 1 2))", true, false))).isFalse();
		assertThat(embedsBlasBridge(compile("(print (vec:sum (vec:arange 8)))", true, false))).isTrue();
	}

	@Test
	void aDeclinedVecGemvRunsTheSameProgramToTheSameOutputOnAnyMachine() throws Exception {
		// The half of the feature a machine with no tuned library still gets, at both
		// widths and in both forms.
		assertMatchesScalarReference(gemvOperands("double-float") + "(print (vec:matvec *w* *x*))");
		assertMatchesScalarReference(gemvOperands("single-float")
				+ "(print (vec:matvec-into (vec:zeros 8 :element-type 'single-float) *w* *x*))");
	}

	@Test
	@EnabledIf("tunedBlasIsAvailable")
	void theVecGemvMatchesTheScalarReferenceAtBothWidthsAndInBothForms() throws Exception {
		assertMatchesScalarReference(gemvOperands("double-float") + """
				(print (vec:matvec *w* *x*))
				(print (vec:matvec-into (vec:zeros 8) *w* *x*))
				(print (vec:to-list (vec:matvec *w* *x*)))
				""");
		assertMatchesScalarReference(gemvOperands("single-float") + """
				(print (vec:matvec *w* *x*))
				(print (vec:matvec-into (vec:zeros 8 :element-type 'single-float) *w* *x*))
				""");
		// Below the size threshold, and a mixed-width pair: both decline to the rung
		// below, which on a --blas-only build is the scalar defun.
		assertMatchesScalarReference("(print (vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)))");
		assertMatchesScalarReference(
				gemvOperands("double-float") + "(print (vec:matvec *w* (vec:arange 8 :element-type 'single-float)))");
	}

	@Test
	@EnabledIf("tunedBlasIsAvailable")
	void aVecGemvArgumentFormIsEvaluatedExactlyOnceWhetherTheLibraryTakesItOrNot() throws Exception {
		// The chain reloads the temps rather than recompiling the argument forms.
		String declined = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) #d((1.0 2.0) (3.0 4.0)))
				(vec:matvec (bump) #d(5.0 6.0))
				(print *n*)
				""";
		assertThat(accel(declined)).isEqualTo("1");
		String accepted = gemvOperands("double-float") + """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) *w*)
				(vec:matvec (bump) *x*)
				(print *n*)
				""";
		assertThat(accel(accepted)).isEqualTo("1");
	}

	@Test
	@EnabledIf("tunedBlasIsAvailable")
	void withSimdOnTooTheVecChainIsBlasThenLanesThenTheDefun() throws Exception {
		String program = gemvOperands("double-float") + """
				(print (vec:matvec *w* *x*))
				(print (vec:matvec-into (vec:zeros 8) *w* *x*))
				(print (vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)))
				(print (vec:sum *x*))
				""";
		assertThat(run(compile(program, true, true))).isEqualTo(scalar(program));
		byte[] both = compile(program, true, true);
		assertThat(callsBridgeMethod(both, "blasMatvec")).isTrue();
		assertThat(embedsSimdBridge(both)).isTrue();
	}

	// --- the accelerated shapes (needs a library on this machine) ---------------------

	@Test
	@EnabledIf("tunedBlasIsAvailable")
	void theProductMatchesTheScalarReferenceAtBothWidthsAndEveryShape() throws Exception {
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 25) '(4 6)))
				(defparameter *b* (linalg:reshape (linalg:arange 1 31) '(6 5)))
				(print (linalg:matmul *a* *b*))
				(print (linalg:dot *a* (linalg:arange 1 7)))
				(print (linalg:dot (linalg:arange 1 5) *a*))
				""");
		assertMatchesScalarReference("""
				(defparameter *a*
				  (linalg:reshape (linalg:arange 1 25 :element-type 'single-float) '(4 6)))
				(print (linalg:matmul *a* (linalg:transpose *a*)))
				(print (linalg:dot *a* (linalg:arange 1 7 :element-type 'single-float)))
				""");
	}

	@Test
	@EnabledIf("tunedBlasIsAvailable")
	void declinedOperandsRunTheScalarDefunUnchanged() throws Exception {
		// Boxed arrays, a mixed-width pair, a rank-3 stacked product, a vector-vector
		// dot, and a product below the size threshold.
		assertMatchesScalarReference("(print (linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8))))");
		assertMatchesScalarReference("""
				(defparameter *d* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(defparameter *f* (linalg:reshape (linalg:arange 1 65 :element-type 'single-float) '(8 8)))
				(print (linalg:matmul *d* *f*))
				""");
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 33) '(2 4 4)))
				(print (linalg:matmul *a* *a*))
				""");
		assertMatchesScalarReference("(print (linalg:dot (linalg:arange 1 100) (linalg:arange 1 100)))");
		assertMatchesScalarReference("(print (linalg:matmul #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0))))");
	}

	@Test
	@EnabledIf("tunedBlasIsAvailable")
	void anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines() throws Exception {
		// The chain reloads the temps rather than recompiling the argument forms; a
		// second evaluation would bump the counter to 2. Both halves matter here, since
		// --blas adds an attempt AHEAD of the ones that were already pinned.
		String declined = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) #2A((1 2) (3 4)))
				(linalg:dot (bump) #2A((1 2) (3 4)))
				(print *n*)
				""";
		assertThat(accel(declined)).isEqualTo("1");
		String accepted = """
				(defparameter *n* 0)
				(defparameter *m* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(defun bump () (setq *n* (+ *n* 1)) *m*)
				(linalg:dot (bump) *m*)
				(print *n*)
				""";
		assertThat(accel(accepted)).isEqualTo("1");
	}

	@Test
	@EnabledIf("tunedBlasIsAvailable")
	void withSimdOnTooTheChainIsBlasThenLanesThenTheDefun() throws Exception {
		String program = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(print (linalg:matmul *a* *a*))
				(print (linalg:dot (linalg:arange 1 100) (linalg:arange 1 100)))
				(print (linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8))))
				""";
		assertThat(run(compile(program, true, true))).isEqualTo(scalar(program));
	}

	// MethodHandles.Lookup.defineClass(byte[]) requires the defined class to share the
	// lookup class's package; every test above compiles into the default package, so this
	// one alone proves the embedded bridge is renamed into a NON-default package too --
	// runs on any machine, since a below-threshold product declines regardless.
	@Test
	void theBridgeIsRenamedIntoTheGeneratedClassOwnPackageAndRunsThere() throws Exception {
		String lispCode = "(print (linalg:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))";
		String expected = scalar(lispCode);
		List<LispVal> program = LinalgLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] classBytes = new JvmLispCompiler("com/example/Test", false, OptimizeLevel.NONE, false, true)
			.compile(program);

		String bridgeName = "com/example/" + JvmBlasRuntimeBuilder.BRIDGE_NAME;
		assertThat(new String(classBytes, StandardCharsets.ISO_8859_1)).contains(bridgeName);

		Path packageDir = this.tempDir.resolve("com").resolve("example");
		Files.createDirectories(packageDir);
		Files.write(packageDir.resolve("Test.class"), classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("com.example.Test");
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
			assertThat(baos.toString().trim()).isEqualTo(expected);
		}
	}

}
