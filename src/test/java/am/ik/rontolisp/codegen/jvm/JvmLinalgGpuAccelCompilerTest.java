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
import java.util.stream.Stream;

import am.ik.gpu.Gpu;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.LinalgBlas;
import am.ik.rontolisp.eval.LinalgGpu;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code --gpu} JVM acceleration of the {@code linalg:} matrix product, the sibling
 * of {@link JvmLinalgBlasAccelCompilerTest} and the compiled half of
 * {@code eval/LinalgGpuTest} / {@code eval/LinalgGpuDeclineTest}. The {@code linalg:dot}
 * and {@code linalg::%la-matmul-nd} call sites are routed to the embedded
 * {@link JvmGpuTemplate} bridge over an injected copy of {@code am.ik.gpu}, which offers
 * the product to an NVIDIA device and declines to whatever is below it.
 *
 * <p>
 * Two halves, as everywhere over this seam: the half that must hold on EVERY machine --
 * the emit gate, the class list the blob carries, and the fact that a machine with no
 * device runs the same programs to the same output -- and the half that needs a device.
 */
class JvmLinalgGpuAccelCompilerTest {

	static boolean aDeviceIsAvailable() {
		return LinalgGpu.available();
	}

	static boolean aDeviceAndATunedBlasAreAvailable() {
		return LinalgGpu.available() && LinalgBlas.available();
	}

	@TempDir
	Path tempDir;

	private byte[] compile(String lispCode, boolean gpu) {
		return compile(lispCode, gpu, false, false);
	}

	private byte[] compile(String lispCode, boolean gpu, boolean blas, boolean simd) {
		List<LispVal> program = LinalgLibrary.process(LispReader.readAllFromString(lispCode));
		return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, simd, blas, gpu).compile(program);
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

	private static boolean embedsGpuBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmGpuRuntimeBuilder.BRIDGE_NAME);
	}

	private static boolean embedsSimdBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmSimdRuntimeBuilder.BRIDGE_NAME);
	}

	private static boolean embedsBlasBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmBlasRuntimeBuilder.BRIDGE_NAME);
	}

	// --- the emit gate and the blob (run on every machine) ----------------------------

	@Test
	void theBridgeIsEmbeddedOnlyUnderTheFlagAndOnlyForAProgramThatReachesTheProduct() {
		// Without this a dead interception would still pass every numeric assertion
		// below, because the scalar defun returns the right answer
		// ([[simd-shadow-and-dead-flag-lesson]]).
		assertThat(embedsGpuBridge(compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", true))).isTrue();
		assertThat(embedsGpuBridge(compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", false))).isFalse();
		// A program that never mentions the package keeps the bridge out; any linalg
		// program pulls it in, because the spliced linalg.lisp holds the dot call site.
		assertThat(embedsGpuBridge(compile("(print (+ 1 2))", true))).isFalse();
		assertThat(embedsGpuBridge(compile("(print (linalg:eye 2))", true))).isTrue();
		// The gate is over BOTH members. A transformer reaches only the stacked one, so
		// a gate on dot alone would embed no bridge for exactly the program this flag is
		// for -- pinned here by asking for the stacked call site by its own name.
		assertThat(embedsGpuBridge(
				compile("(print (linalg::%la-matmul-nd (linalg:zeros '(1 1 1))" + " (linalg:zeros '(1 1 1))))", true)))
			.isTrue();
		// And over every ELEMENT-WISE member, for the same reason one step further: a
		// program whose only linalg call is a transcendental now reaches the device, and
		// a gate that named only the two products would emit no bridge for it.
		assertThat(embedsGpuBridge(compile("(print (linalg:erf #d(1.0)))", true))).isTrue();
		assertThat(embedsGpuBridge(compile("(print (linalg:erf #d(1.0)))", false))).isFalse();
		assertThat(embedsGpuBridge(compile("(print (linalg:tanh #d(1.0)))", true))).isTrue();
	}

	@Test
	void theThreeFlagsAreOrthogonalAndEmbedTheirOwnBridges() {
		// --gpu must not drag in the Vector API bridge: a class that did would need
		// java --add-modules jdk.incubator.vector to run, which --gpu never requires.
		byte[] gpuOnly = compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", true, false, false);
		assertThat(embedsGpuBridge(gpuOnly)).isTrue();
		assertThat(embedsBlasBridge(gpuOnly)).isFalse();
		assertThat(embedsSimdBridge(gpuOnly)).isFalse();
		byte[] all = compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", true, true, true);
		assertThat(embedsGpuBridge(all)).isTrue();
		assertThat(embedsBlasBridge(all)).isTrue();
		assertThat(embedsSimdBridge(all)).isTrue();
	}

	@Test
	void theBlobCarriesTheWholeLibraryAndItsKernels() throws Exception {
		// The point of the whole bridge decision: the compiled backend runs am.ik.gpu's
		// own bytes rather than a hand-kept copy of them, so a class file added to the
		// library must be added to the list that travels -- there is no way to enumerate
		// a package from a classpath, let alone from inside a native image
		// (.kb/gpu.md).
		Path classes = Path.of(Gpu.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assumeTrue(Files.isDirectory(classes), "the library must be on the classpath as class files");
		List<String> onDisk;
		try (Stream<Path> files = Files.list(classes.resolve("am/ik/gpu"))) {
			onDisk = files.map(p -> p.getFileName().toString())
				.filter(name -> name.endsWith(".class"))
				.map(name -> name.substring(0, name.length() - ".class".length()))
				.filter(name -> !name.equals("package-info"))
				.sorted()
				.toList();
		}
		assertThat(JvmGpuRuntimeBuilder.embeddedGpuClasses()).containsExactlyInAnyOrderElementsOf(onDisk);
		// And the kernels: a resource cannot follow the classes into the emitted
		// program's package, so the PTX text rides in the same blob -- verbatim, not
		// base64'd like the class files -- and is handed to Gpu.useKernels by _gpuInit.
		String bytes = new String(compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", true),
				StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains(".visible .entry gemm_f64")
			.contains(".visible .entry gemm_f32")
			.contains(".visible .entry gemm_batched_f64")
			.contains(".visible .entry gemm_batched_f32")
			.contains(".visible .entry map_f64")
			.contains(".visible .entry map_f32");
	}

	@Test
	void aDeclinedProductRunsTheSameProgramToTheSameOutputOnAnyMachine() throws Exception {
		// The half of the feature a machine with no device still gets: a product below
		// the size threshold, which no device is ever offered.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(print (linalg:matmul *a* *a*))
				""");
	}

	@Test
	void aDeclinedElementWiseCallRunsTheSameProgramToTheSameOutputOnAnyMachine() throws Exception {
		// Below the element threshold, which no device is ever offered -- and above it
		// for the tier's DECLINED half, which no device is offered at any size.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:linspace -3.0 3.0 16383))
				(print (linalg:sum (linalg:erf *a*)))
				""");
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:linspace 0.01 9.0 200000))
				(print (list (linalg:sum (linalg:sqrt *a*)) (linalg:sum (linalg:abs *a*))
				             (linalg:sum (linalg:negative *a*)) (linalg:sum (linalg:add *a* *a*))))
				""");
	}

	// --- the accelerated shapes (needs a device on this machine) ----------------------

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theProductMatchesTheScalarReferenceAtBothWidthsOverExactInputs() throws Exception {
		// Exact at the operand width, so the device's fused multiply-adds cannot show
		// and the answer must be the defun's EXACTLY -- the tolerance case is the
		// library's own (am/ik/gpu/GpuTest), not this seam's.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(defparameter *b* (linalg:reshape (linalg:arange 2 4098) '(64 64)))
				(print (linalg:matmul *a* *b*))
				""");
		// Single width, and the operands are chosen so the FOLD stays exact too: every
		// cell is a 64-long sum of integers under 2^24, where f32 addition is exact.
		// 1..4096 squared would not be -- the defun accumulates in f64 and would answer
		// something no f32 kernel can, which is the reduction contract, not this seam.
		assertMatchesScalarReference("""
				(defparameter *a*
				  (linalg:reshape (linalg:arange 1 4097 :element-type 'single-float) '(64 64)))
				(print (linalg:matmul (linalg:ones '(64 64) :element-type 'single-float) *a*))
				""");
		// A rectangular shape that is not a multiple of the 16x16 tile.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 5111) '(70 73)))
				(defparameter *b* (linalg:reshape (linalg:arange 1 5330) '(73 73)))
				(print (linalg:matmul *a* *b*))
				""");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void declinedOperandsRunTheScalarDefunUnchanged() throws Exception {
		// Boxed arrays, a mixed-width pair, a rank-3 stacked product, a vector-vector
		// dot, the two gemv shapes (which this flag deliberately does not take), and a
		// product below the size threshold.
		assertMatchesScalarReference("(print (linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8))))");
		assertMatchesScalarReference("""
				(defparameter *d* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(defparameter *f* (linalg:reshape (linalg:arange 1 4097 :element-type 'single-float) '(64 64)))
				(print (linalg:matmul *d* *f*))
				""");
		// A stack whose TOTAL work is under the threshold -- the shape every example in
		// the repository actually runs -- and a rank-1 operand against a stack, which
		// stays in the defun.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 257) '(4 8 8)))
				(print (linalg:matmul *a* *a*))
				""");
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(print (linalg:matmul *a* (linalg:arange 1 65)))
				""");
		// A batch whose offsets are not affine in the batch index: a broadcast axis
		// UNDER a non-broadcast one, which one stride cannot express.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 3201) '(2 1 40 40)))
				(defparameter *b* (linalg:reshape (linalg:arange 1 9601) '(2 3 40 40)))
				(print (linalg:sum (linalg:matmul *a* *b*)))
				""");
		assertMatchesScalarReference("(print (linalg:dot (linalg:arange 1 100) (linalg:arange 1 100)))");
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(print (linalg:dot *a* (linalg:arange 1 65)))
				(print (linalg:dot (linalg:arange 1 65) *a*))
				""");
		assertMatchesScalarReference("(print (linalg:matmul #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0))))");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theStackedProductMatchesTheScalarReferenceAtEveryBatchShape() throws Exception {
		// The shape the flag exists for: torch.bmm, every attention layer, every
		// torch:linear over a (B T C) activation. Exact at the operand width, so the
		// device's fused multiply-adds cannot show and the answer must be the defun's.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(defparameter *b* (linalg:add (linalg:ones '(2 64 64)) 2.0))
				(print (linalg:sum (linalg:matmul *a* *b*)))
				""");
		// A BROADCAST right operand, which is a 0 batch stride on the device.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(defparameter *b* (linalg:add (linalg:ones '(64 64)) 2.0))
				(print (linalg:sum (linalg:matmul *a* *b*)))
				""");
		// Rank 4 with two leading axes, and single width.
		assertMatchesScalarReference("""
				(defparameter *a*
				  (linalg:reshape (linalg:arange 1 12289 :element-type 'single-float) '(2 3 32 64)))
				(defparameter *b* (linalg:add (linalg:ones '(2 3 64 32) :element-type 'single-float) 1.0))
				(print (linalg:sum (linalg:matmul *a* *b*)))
				""");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines() throws Exception {
		// The chain reloads the temps rather than recompiling the argument forms; a
		// second evaluation would bump the counter to 2. Both halves matter, since --gpu
		// adds an attempt AHEAD of the ones already pinned.
		String declined = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) #2A((1 2) (3 4)))
				(linalg:dot (bump) #2A((1 2) (3 4)))
				(print *n*)
				""";
		assertThat(accel(declined)).isEqualTo("1");
		String accepted = """
				(defparameter *n* 0)
				(defparameter *m* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(defun bump () (setq *n* (+ *n* 1)) *m*)
				(linalg:dot (bump) *m*)
				(print *n*)
				""";
		assertThat(accel(accepted)).isEqualTo("1");
		// And the stacked call site, which is a second chain over its own temps.
		String stacked = """
				(defparameter *n* 0)
				(defparameter *m* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(defun bump () (setq *n* (+ *n* 1)) *m*)
				(linalg:matmul (bump) *m*)
				(print *n*)
				""";
		assertThat(accel(stacked)).isEqualTo("1");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void aDeclinedStackFallsOnTheLaneKernelWhenSimdIsOnAndOnTheDefunOtherwise() throws Exception {
		// --blas does not take this member, so the stacked chain is device -> lane
		// kernel -> defun. The probe is .kb/linalg-simd.md's f32 fold at rank 3: the lane
		// kernel accumulates in single precision and prints 16777216, the boxed defun
		// widens and prints 16778240. The stack is far below the size threshold, so the
		// device declines it whatever the flags are.
		String probe = """
				(defparameter *v*
				  (linalg:reshape (linalg:emap (lambda (x) (if (= x 0) 4096.0 1.0))
				                               (linalg:arange 0 1024 :element-type 'single-float))
				                  '(1 1 1024)))
				(print (round (row-major-aref
				               (linalg:matmul *v* (linalg:reshape *v* '(1 1024 1))) 0)))
				""";
		assertThat(run(compile(probe, true, false, false))).isEqualTo("16778240");
		assertThat(run(compile(probe, true, true, false))).isEqualTo("16778240");
		assertThat(run(compile(probe, true, false, true))).isEqualTo("16777216");
		assertThat(run(compile(probe, true, true, true))).isEqualTo("16777216");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void whatTheDeviceDeclinesFallsOnTheBestCpuPathEnabledAndNotOnTheDefun() throws Exception {
		// The composition rule, stated for three layers. The fallback target is legible
		// because .kb/linalg-simd.md's f32 v.M probe prints a different number on each
		// of them: the scalar defun 16778240, the lane kernel 16777216. The device
		// declines a v.M shape outright, so with --simd on the answer must be the lane
		// kernel's and NOT the defun's.
		String probe = """
				(defparameter *v*
				  (linalg:reshape (linalg:emap (lambda (x) (if (= x 0) 4096.0 1.0))
				                               (linalg:arange 0 1024 :element-type 'single-float))
				                  '(1024)))
				(print (round (aref (linalg:dot *v* (linalg:reshape *v* '(1024 1))) 0)))
				""";
		assertThat(run(compile(probe, true, false, false))).isEqualTo("16778240");
		assertThat(run(compile(probe, true, false, true))).isEqualTo("16777216");
		assertThat(run(compile(probe, false, false, true))).isEqualTo("16777216");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void everyElementWiseMemberAgreesWithTheScalarReferenceToARelativeTolerance() throws Exception {
		// The compiled half of the precision contract. Not an equality: the device has
		// its own libm, and at #f it evaluates AT the operand width where the defun
		// evaluates in double and narrows. The reference is the same program compiled
		// without the flag, so this pins the SEAM -- the library's own per-member
		// accuracy is am/ik/gpu/GpuTest's.
		// The sum is of the ABSOLUTE values: over a symmetric domain sin's own sum
		// cancels to 1e-16 and a relative tolerance on it would compare two roundings of
		// zero.
		String[][] members = { { "exp", "-5.0", "5.0" }, { "log", "0.01", "100.0" }, { "tanh", "-5.0", "5.0" },
				{ "sin", "-5.0", "5.0" }, { "cos", "-5.0", "5.0" }, { "tan", "-1.4", "1.4" },
				{ "asin", "-0.999", "0.999" }, { "acos", "-0.999", "0.999" }, { "atan", "-5.0", "5.0" },
				{ "sinh", "-5.0", "5.0" }, { "cosh", "-5.0", "5.0" }, { "erf", "-3.0", "3.0" } };
		for (String[] member : members) {
			String program = """
					(defparameter *a* (linalg:linspace %s %s 20000%s))
					(print (linalg:sum (linalg:abs (linalg:%s *a*))))
					""";
			for (String width : new String[] { "", " :element-type 'single-float" }) {
				String source = program.formatted(member[1], member[2], width, member[0]);
				double accelerated = Double.parseDouble(accel(source));
				double reference = Double.parseDouble(scalar(source));
				assertThat(accelerated).as("linalg:%s%s", member[0], width)
					.isCloseTo(reference, within(1e-6 * Math.abs(reference)));
			}
		}
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void anAcceleratedElementWiseCallReallyRanOnTheDevice() {
		// The guard on the tolerance above: over 20000 inexact elements the device and
		// the CPU cannot land on the same bits everywhere, so a dead interception would
		// show here and only here.
		String program = """
				(defparameter *a* (linalg:linspace -5.0 5.0 20000))
				(print (linalg:to-list (linalg:erf *a*)))
				""";
		assertThatCode(() -> assertThat(accel(program)).isNotEqualTo(scalar(program))).doesNotThrowAnyException();
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void anElementWiseArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines() throws Exception {
		// The temps, at a UNARY call site: every decline branch re-reads the operand
		// temp, so recompiling the argument form would run its side effects again. The
		// operand is below the threshold, so the kernel really does decline and the
		// defun really does run.
		String program = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) (linalg:linspace -1.0 1.0 100))
				(linalg:erf (bump))
				(print *n*)
				""";
		assertThat(accel(program)).isEqualTo("1");
		assertThat(scalar(program)).isEqualTo("1");
	}

	@Test
	@EnabledIf("aDeviceAndATunedBlasAreAvailable")
	void theDeviceIsAskedAheadOfATunedBlas() throws Exception {
		// n=192, not 64: a tuned BLAS fuses too, and on the calibration machine it and
		// the device agree BIT FOR BIT up to n=128 -- an order pin written there would
		// be a tautology (.kb/gpu.md). From n=192 the library blocks its k loop and the
		// two separate, so "--gpu --blas answers what --gpu answers, not what --blas
		// does" is a real assertion about which one ran.
		String inexact = """
				(defparameter *a* (linalg:reshape (linalg:emap #'sin (linalg:arange 0 36864)) '(192 192)))
				(defparameter *b* (linalg:reshape (linalg:emap #'cos (linalg:arange 0 36864)) '(192 192)))
				(defparameter *c* (linalg:matmul *a* *b*))
				(print (aref *c* 5 7))
				""";
		String device = run(compile(inexact, true, false, false));
		String library = run(compile(inexact, false, true, false));
		assertThat(device).isNotEqualTo(library);
		assertThat(run(compile(inexact, true, true, false))).isEqualTo(device);
		assertThat(run(compile(inexact, true, true, true))).isEqualTo(device);
	}

}
