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
import am.ik.rontolisp.eval.VecLibrary;
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

	/**
	 * Whether this machine's device has a {@code double}. It does on CUDA and does not on
	 * Metal, so every assertion below that needs the device to ACCEPT is written at
	 * {@link #TYPE}; the ones that need it to DECLINE hold at either width.
	 */
	private static final boolean DOUBLES = am.ik.gpu.GpuThresholds.supportsDouble();

	/** The element type an ACCEPTED call has to be written at on this machine. */
	private static final String TYPE = DOUBLES ? "" : " :element-type 'single-float";

	/** Comfortably above the element-wise threshold in force: 32768 or 262144. */
	private static final int MAP_N = (int) am.ik.gpu.GpuThresholds.mapMinElements() * 2;

	/**
	 * The side of the smallest square product this machine accepts, with a safety factor.
	 * 64 on CUDA and 208 on Metal, whose floor is five times higher.
	 */
	private static final int SIDE = squareSide();

	private static int squareSide() {
		int n = (int) Math.ceil(Math.cbrt((double) am.ik.gpu.GpuThresholds.minWork()));
		return Math.max(64, (n + n / 4 + 15) / 16 * 16);
	}

	/**
	 * A product of operands that are exact at BOTH widths AT ANY SHAPE: the left operand
	 * is the sign of a sine, so every cell is +1 or -1, and the right is
	 * {@code 4 * sign(sin i) + sign(cos i)}, so every cell is one of -5, -3, 3, 5. Every
	 * partial sum is then an integer under {@code 5 * m}, far inside what f32 holds
	 * exactly, so the device's reordered fold does not merely come close to the compiled
	 * defun's answer -- it equals it, and the bound does not grow with the shape. An
	 * index ramp, which these tests used to build, is exact only up to a size, which is
	 * why they could not be resized off {@link #SIDE} as they stood.
	 * @param leftDims the left operand's shape, as a Lisp dimension list
	 * @param leftCount how many elements that shape holds
	 * @param rightDims the right operand's shape
	 * @param rightCount how many elements that shape holds
	 * @param option the {@code :element-type} to build at, or the empty string
	 * @return the program, printing the product's sum
	 */
	private static String exactProduct(String leftDims, int leftCount, String rightDims, int rightCount,
			String option) {
		return """
				(defparameter *a* (linalg:reshape (linalg:sign (linalg:sin (linalg:arange 1 %d%s))) '(%s)))
				(defparameter *b* (linalg:reshape
				                   (linalg:add (linalg:mul (linalg:sign (linalg:sin (linalg:arange 1 %d%s))) 4)
				                               (linalg:sign (linalg:cos (linalg:arange 1 %d%s))))
				                   '(%s)))
				(print (linalg:matmul *a* *b*))
				""".formatted(leftCount + 1, option, leftDims, rightCount + 1, option, rightCount + 1, option,
				rightDims);
	}

	/**
	 * Asserts that a product of {@code work} multiply-adds really is one this machine's
	 * device accepts, so that the equality after it is a claim about the KERNEL and not
	 * about the decline protocol.
	 *
	 * <p>
	 * The guard is threshold arithmetic rather than the residency census
	 * {@code LinalgGpuTest} uses, and it has to be: a compiled class carries its OWN copy
	 * of the library, defined into its own loader, so this process cannot watch that
	 * copy's cache. It is the same device and therefore the same threshold -- and the
	 * threshold is exactly what the shapes here used to get wrong, a hard-coded 64-cube
	 * being 262144 multiply-adds against Metal's floor of 4194304.
	 * @param work the product's {@code batch * n * m * p}
	 */
	private static void assertTheDeviceTakesTheProduct(long work) {
		assertThat(am.ik.gpu.GpuThresholds.acceptedForSize(am.ik.gpu.GpuThresholds.minWork(), work))
			.as("a product of %d multiply-adds is offered here", work)
			.isTrue();
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

	/** {@link #compile} with the {@code vec:} library spliced in as well. */
	private byte[] compileWithVec(String lispCode, boolean gpu, boolean simd) {
		List<LispVal> program = VecLibrary.process(LinalgLibrary.process(LispReader.readAllFromString(lispCode)));
		return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, simd, false, gpu).compile(program);
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

	/**
	 * Runs a {@code --gpu} class in a loader of its own and answers whether the library
	 * EMBEDDED in it -- defined into that loader by {@code _gpuInit} under its renamed
	 * name -- found a device. The one observable that says the compiled program really
	 * accelerates, which no printed value can: every member is written to land on the
	 * oracle's bits, so a class whose embedded probe failed prints the same thing.
	 */
	private boolean embeddedDeviceAvailable(byte[] classBytes) throws Exception {
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(new ByteArrayOutputStream()));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			Class<?> gpu = loader.loadClass(JvmGpuRuntimeBuilder.GPU_PREFIX + "Gpu");
			return (boolean) gpu.getMethod("available").invoke(null);
		}
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theEmbeddedLibraryFindsTheDeviceThisMachineHas() throws Exception {
		// The dead-flag guard for the compiled backend: _gpuInit hands over the PTX, asks
		// for lazy results, then hands over the MSL -- and until 2026-08-23 the middle
		// step ran the probe, so on a Mac the embedded Metal half had no source to
		// compile and every class declined for its whole life while printing the right
		// answers. The embedded copy must find what the test JVM's copy found.
		assertThat(embeddedDeviceAvailable(compile("(print (linalg:matmul #d((1.0)) #d((2.0))))", true))).isTrue();
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
		// And over the STRIDED tier, whose members reach the device at their BROADCAST
		// and OPTION-FORM call shapes -- the axis folds and the axes transpose have no
		// base-shape kernel at all, so the gate is the only thing that puts them in.
		assertThat(embedsGpuBridge(compile("(print (linalg:sub #d((1.0)) #d(2.0)))", true))).isTrue();
		assertThat(embedsGpuBridge(compile("(print (linalg:sum #d((1.0)) :axis 0))", true))).isTrue();
		assertThat(embedsGpuBridge(compile("(print (linalg:transpose #d((1.0)) '(1 0)))", true))).isTrue();
		// And over vec:matvec, the one member outside linalg: -- a program whose only
		// device member it is (a decode loop) must embed the bridge. Any vec program
		// pulls
		// it in, as any linalg program does, because the spliced vec.lisp holds the
		// matvec defun; and none of them does without the flag.
		assertThat(embedsGpuBridge(compileWithVec("(print (vec:matvec #d((1.0)) #d(2.0)))", true, false))).isTrue();
		assertThat(embedsGpuBridge(compileWithVec("(print (vec:matvec #d((1.0)) #d(2.0)))", false, false))).isFalse();
		assertThat(embedsGpuBridge(compileWithVec("(print (vec:dot #d(1.0) #d(2.0)))", true, false))).isTrue();
		assertThat(embedsGpuBridge(compileWithVec("(print (vec:dot #d(1.0) #d(2.0)))", false, false))).isFalse();
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

	/**
	 * How many times the compiled TOPLEVEL calls the materialize guard -- the emitted
	 * call sites' own guards, not the ones the spliced defuns and the array accessors
	 * carry.
	 */
	private static int materializeCallsInTheToplevel(byte[] classBytes) {
		int calls = 0;
		for (java.lang.classfile.MethodModel method : java.lang.classfile.ClassFile.of().parse(classBytes).methods()) {
			if (!method.methodName().stringValue().startsWith("_top$")) {
				continue;
			}
			for (java.lang.classfile.CodeElement element : method.code().orElseThrow()) {
				if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
						&& invoke.name().stringValue().equals(JvmGpuRuntimeBuilder.MATERIALIZE_METHOD)) {
					calls++;
				}
			}
		}
		return calls;
	}

	@Test
	void aDeclineMaterializesOnlyWhereAHostKERNELRungFollowsIt() {
		// The guard brings a result the device holds the only bytes of home, because the
		// lane and library kernels below read raw storage past every access hook. The
		// scalar DEFUN does not need it: it is compiled Lisp, and reports each of its
		// own reads through the same guards. So a chain whose only fallback is the defun
		// must not carry one -- materializing there drags home a score whose first
		// reader is another device member, which stages it straight back
		// (.kb/gpu.md, "The chapter-2 step re-measured").
		String add = """
				(defparameter *a* (linalg:zeros '(2 2)))
				(defparameter *b* (linalg:zeros '(2 2)))
				(print (linalg:add *a* *b*))
				""";
		assertThat(materializeCallsInTheToplevel(compile(add, true, false, true))).as("--gpu --simd: the lane rung")
			.isEqualTo(2);
		assertThat(materializeCallsInTheToplevel(compile(add, true, false, false))).as("--gpu: no lane rung emitted")
			.isZero();
		// And a DEVICE-ONLY member -- the fused tier has no lane kernel at any flag --
		// carries none however the build is flagged. This is the attention softmax's
		// call site: 96 of its declines a step were a 25.9 MB round trip each way.
		String fused = """
				(defparameter *a* (linalg:zeros '(2 2)))
				(print (linalg::%la-scaled-masked-softmax *a* nil nil 0.0 1))
				""";
		assertThat(materializeCallsInTheToplevel(compile(fused, true, false, true))).as("--gpu --simd: no lane kernel")
			.isZero();
		assertThat(materializeCallsInTheToplevel(compile(fused, true, true, true))).as("--gpu --blas --simd").isZero();
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
			.contains(".visible .entry map_f32")
			.contains(".visible .entry bcast_f64")
			.contains(".visible .entry bcast_f32")
			.contains(".visible .entry gather_f64")
			.contains(".visible .entry gather_f32")
			.contains(".visible .entry fold_f64")
			.contains(".visible .entry fold_f32")
			.contains(".visible .entry gemv_f64")
			.contains(".visible .entry gemv_f32")
			.contains(".visible .entry zip_f32")
			.contains(".visible .entry scal_f32")
			.contains(".visible .entry where_f32")
			.contains(".visible .entry adam_f64");
		// ... and the MSL beside it, for the same reason and one more: the machine that
		// COMPILES a program is not the machine that runs it, so both texts travel in
		// every --gpu class and a Linux build has to carry the Apple half too.
		assertThat(bytes).contains("kernel void gemm_batched_f32")
			.contains("kernel void map_f32")
			.contains("kernel void bcast_f32")
			.contains("kernel void gather_f32");
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

	@Test
	void aDeclinedStridedCallRunsTheSameProgramToTheSameOutputOnAnyMachine() throws Exception {
		// An EQUAL-shaped binary pair is not a member at any size (the CPU lane loop wins
		// it), and every strided shape below its threshold is untouched.
		assertMatchesScalarReference("""
				(defparameter *a* (linalg:reshape (linalg:linspace 0.01 9.0 262144) '(64 4096)))
				(defparameter *b* (linalg:reshape (linalg:linspace 0.02 3.0 262144) '(64 4096)))
				(print (list (linalg:sum (linalg:add *a* *b*)) (linalg:sum (linalg:sub *a* *b*))
				             (linalg:sum (linalg:mul *a* *b*)) (linalg:sum (linalg:div *a* *b*))))
				""");
		assertMatchesScalarReference("""
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 32000) '(50 640)))
				(defparameter *m* (linalg:amax *x* :axis 1 :keepdims t))
				(print (list (linalg:sum (linalg:sub *x* *m*))
				             (linalg:sum (linalg:sum *x* :axis 1 :keepdims t))
				             (linalg:sum (linalg:transpose *x* '(1 0)))))
				""");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theGeneratorFillIsByteIdenticalToTheScalarReferenceOnTheDevice() throws Exception {
		// The seeded generator through the compiled backend: the device fill and the
		// sequential walk print the same bytes at both widths and every rule, and the
		// generator continues where the walk would have. On a backend that is not a
		// member of this tier at all -- Metal, whose rng threshold is the "never for its
		// size" sentinel -- every fill declines and the two sides are the same walk, so
		// the claim holds there without the device having been asked.
		assertMatchesScalarReference("""
				(linalg:seed 7)
				(defparameter *a* (linalg:rand 100000))
				(defparameter *b* (linalg:randn 50000))
				(defparameter *c* (linalg:uniform -2 3 70000))
				(defparameter *d* (linalg:rand '(200 300) :element-type 'single-float))
				(defparameter *e* (linalg:randn 30000 :element-type 'single-float))
				(print (list (linalg:sum (linalg:mul *a* *a*)) (linalg:sum (linalg:mul *b* *b*))
				             (linalg:sum (linalg:mul *c* *c*)) (linalg:sum (linalg:mul *d* *d*))
				             (linalg:sum (linalg:mul *e* *e*))
				             (aref *a* 8191) (aref *a* 8192) (aref *b* 49999) (aref *d* 199 299)
				             (aref *e* 12345) (linalg::%la-rng-next) (linalg:rand 3)))
				""");
	}

	@Test
	void aDeclinedGeneratorFillRunsTheSameProgramToTheSameOutputOnAnyMachine() throws Exception {
		// Below the threshold, and with a state word outside the generator's range: the
		// device is never asked, and a --gpu class prints what a plain one prints.
		assertMatchesScalarReference("(linalg:seed 3) (print (list (linalg:rand 8191) (linalg::%la-rng-next)))");
		assertMatchesScalarReference("(linalg:seed 4) (let ((s (linalg::%la-rng-state))) (setf (aref s 0) -3.0)"
				+ " (print (linalg::%la-rng-fill (linalg:zeros 20000) s 0 0.0 1.0)))");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theStridedTierIsBitIdenticalToTheScalarReference() throws Exception {
		// The tier's whole claim, on the compiled backend: a BROADCAST binary op, an AXIS
		// fold and an axes TRANSPOSE are bit-identical to the defun at both widths, over
		// INEXACT data, above the thresholds. Unlike the element-wise tier there is no
		// libm in them and nothing to diverge.
		//
		// The #d block below is CUDA's half and declines outright on Metal, which has no
		// double; the #f block is what runs on both. The axis FOLDS in either block are
		// Metal's decline too -- it is not a member of that tier for size at all -- so
		// there the equality they assert is the defun against itself. Deliberate: this
		// file may not drop the #d coverage to make one backend's shapes universal.
		assertMatchesScalarReference("""
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 262144) '(64 4096)))
				(defparameter *m* (linalg:amax *x* :axis 1 :keepdims t))
				(defparameter *s* (linalg:sub *x* *m*))
				(print (list (linalg:sum *s*)
				             (linalg:sum (linalg:div *s* (linalg:sum *s* :axis 1 :keepdims t)))
				             (linalg:sum (linalg:mul *x* *m*)) (linalg:sum (linalg:add *x* *m*))
				             (linalg:sum (linalg:maximum *x* *m*)) (linalg:sum (linalg:minimum *x* *m*))
				             (linalg:sum (linalg:amin *x* :axis 1))
				             (linalg:sum (linalg:sum *x* :axis 0))
				             (linalg:sum (linalg:transpose *x* '(1 0)))))
				""");
		assertMatchesScalarReference("""
				(defparameter *y* (linalg:reshape
				                   (linalg:linspace 0.013 3.7 262144 :element-type 'single-float)
				                   '(4 64 1024)))
				(defparameter *r* (linalg:mean *y* :axis 2 :keepdims t))
				(print (list (linalg:sum (linalg:sub *y* *r*)) (linalg:sum (linalg:div *y* *r*))
				             (linalg:sum (linalg:var *y* :axis 2 :keepdims t))
				             (linalg:sum (linalg:transpose *y* '(0 2 1)))
				             (linalg:shape (linalg:transpose *y* '(2 0 1)))))
				""");
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void anOptionFormArgumentIsEvaluatedExactlyOnceEvenWhenTheDeviceDeclines() throws Exception {
		// The device rung now sits at the EXTENDED call sites too, so the evaluate-once
		// guard has to hold there: every decline branch re-reads the temps, and
		// recompiling the argument forms would repeat their side effects.
		assertMatchesScalarReference("""
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(print (linalg:sum (bump) :axis 0))
				(print *n*)
				(defparameter *m* 0)
				(defun bump2 () (setq *m* (+ *m* 1)) (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(print (linalg:transpose (bump2) '(1 0)))
				(print *m*)
				""");
	}

	// --- the accelerated shapes (needs a device on this machine) ----------------------

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theProductMatchesTheScalarReferenceAtBothWidthsOverExactInputs() throws Exception {
		// Exact at the operand width, so the device's fused multiply-adds cannot show
		// and the answer must be the defun's EXACTLY -- the tolerance case is the
		// library's own (am/ik/gpu/GpuTest), not this seam's.
		//
		// Sized off SIDE and written at TYPE, both of which this file already defines:
		// at the hard-coded 64-cubes and the default width these used to build, every
		// assertion here was the compiled defun against itself on a backend whose floor
		// is higher, or that has no double.
		assertTheDeviceTakesTheProduct((long) SIDE * SIDE * SIDE);
		assertMatchesScalarReference(
				exactProduct(SIDE + " " + SIDE, SIDE * SIDE, SIDE + " " + SIDE, SIDE * SIDE, TYPE));
		// The width the hardware is for, on every backend rather than only on the one
		// whose default is double.
		assertMatchesScalarReference(exactProduct(SIDE + " " + SIDE, SIDE * SIDE, SIDE + " " + SIDE, SIDE * SIDE,
				" :element-type 'single-float"));
		// A rectangular shape whose three dimensions are each not a multiple of the
		// 16x16 tile.
		int n = SIDE + 2, m = SIDE + 6, p = SIDE - 4;
		assertTheDeviceTakesTheProduct((long) n * m * p);
		assertMatchesScalarReference(exactProduct(n + " " + m, n * m, m + " " + p, m * p, TYPE));
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
		// Sized off SIDE for the same reason the rank-2 product above is.
		String stack = "2 " + SIDE + " " + SIDE;
		assertTheDeviceTakesTheProduct(2L * SIDE * SIDE * SIDE);
		assertMatchesScalarReference(exactProduct(stack, 2 * SIDE * SIDE, stack, 2 * SIDE * SIDE, TYPE));
		// A BROADCAST right operand, which is a 0 batch stride on the device.
		assertMatchesScalarReference(exactProduct(stack, 2 * SIDE * SIDE, SIDE + " " + SIDE, SIDE * SIDE, TYPE));
		// Rank 4 with two leading axes, and single width.
		int h = SIDE / 2;
		assertTheDeviceTakesTheProduct(6L * h * SIDE * h);
		assertMatchesScalarReference(exactProduct("2 3 " + h + " " + SIDE, 6 * h * SIDE, "2 3 " + SIDE + " " + h,
				6 * SIDE * h, " :element-type 'single-float"));
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
					(defparameter *a* (linalg:linspace %s %s %d%s))
					(print (linalg:sum (linalg:abs (linalg:%s *a*))))
					""".replace("%d", Integer.toString(MAP_N));
			for (String width : DOUBLES ? new String[] { "", " :element-type 'single-float" }
					: new String[] { " :element-type 'single-float" }) {
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
		// The guard on the tolerance above: over an array the device accepts, it and the
		// CPU cannot land on the same bits everywhere, so a dead interception would show
		// here and only here.
		String program = """
				(defparameter *a* (linalg:linspace -5.0 5.0 %d%s))
				(print (linalg:to-list (linalg:erf *a*)))
				""".formatted(MAP_N, TYPE);
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
		int n = Math.max(192, SIDE);
		String inexact = """
				(defparameter *a* (linalg:reshape (linalg:emap #'sin (linalg:arange 0 %d%s)) '(%d %d)))
				(defparameter *b* (linalg:reshape (linalg:emap #'cos (linalg:arange 0 %d%s)) '(%d %d)))
				(defparameter *c* (linalg:matmul *a* *b*))
				(print (aref *c* 5 7))
				""".formatted(n * n, TYPE, n, n, n * n, TYPE, n, n);
		String device = run(compile(inexact, true, false, false));
		String library = run(compile(inexact, false, true, false));
		// A tolerance-free order pin needs the two to round differently, and whether they
		// do is the machine's answer: on some pairings a tuned library and the device
		// agree bit for bit at this size, and then the assertion below would be a
		// tautology rather than a statement about which one ran.
		assumeTrue(!device.equals(library), "the library and the device round differently here");
		assertThat(run(compile(inexact, true, true, false))).isEqualTo(device);
		assertThat(run(compile(inexact, true, true, true))).isEqualTo(device);
	}

	// --- the matrix-by-vector product (vec:matvec, 2026-08-22) -----------------------

	private static boolean takesMatvec() {
		return aDeviceIsAvailable() && am.ik.gpu.GpuThresholds.matvecMinElements() < Long.MAX_VALUE;
	}

	/** The side of a square matrix comfortably over the GEMV threshold in force. */
	private static int matvecSide() {
		return 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.matvecMinElements()) / 16);
	}

	/**
	 * {@code W x} with {@code W} of exact +1 / -1 entries and {@code x} an index ramp --
	 * every row's dot an exact integer far under 2^24 -- taken twice, because the first
	 * sight of a matrix declines; the second call's elements are printed.
	 */
	private static String exactMatvec(int side, String option) {
		return """
				(defparameter *w* (linalg:reshape (linalg:sign (linalg:sin (linalg:arange 1 %d%s))) '(%d %d)))
				(defparameter *x* (linalg:arange 0 %d%s))
				(vec:matvec *w* *x*)
				(print (linalg:to-list (vec:matvec *w* *x*)))
				""".formatted(side * side + 1, option, side, side, side, option);
	}

	@Test
	@EnabledIf("takesMatvec")
	void theMatrixByVectorProductMatchesTheScalarReferenceOnceResident() throws Exception {
		int side = matvecSide();
		for (String option : new String[] { TYPE, " :element-type 'single-float" }) {
			String program = exactMatvec(side, option);
			assertThat(run(compileWithVec(program, true, false))).as(option)
				.isEqualTo(run(compileWithVec(program, false, false)));
		}
	}

	@Test
	@EnabledIf("takesMatvec")
	void theDeviceIsAskedOnTheSecondSightAndTheLaneKernelOnTheFirst() throws Exception {
		// .kb/vec.md's f32 reduction probe as a matrix row: the defun (double
		// accumulation, narrowed) prints 16778240 and the lane kernel 16777984. The
		// device accumulates in double and answers the DEFUN's figure, and only from the
		// second sight of the matrix on -- so the chain is legible: (lane device).
		int rows = (int) Math.max(128, (am.ik.gpu.GpuThresholds.matvecMinElements() + 1023) / 1024);
		String program = """
				(defparameter *w* (linalg:zeros '(%d 1024) :element-type 'single-float))
				(setf (aref *w* 0 0) 4096.0)
				(dotimes (j 1023) (setf (aref *w* 0 (+ j 1)) 1.0))
				(defparameter *x* (linalg:ones '(1024) :element-type 'single-float))
				(setf (aref *x* 0) 4096.0)
				(defun probe () (round (aref (vec:matvec *w* *x*) 0)))
				(print (list (probe) (probe)))
				""".formatted(rows);
		assertThat(run(compileWithVec(program, true, true))).as("--gpu --simd").isEqualTo("(16777984 16778240)");
		assertThat(run(compileWithVec(program, false, true))).as("--simd").isEqualTo("(16777984 16777984)");
		assertThat(run(compileWithVec(program, true, false))).as("--gpu").isEqualTo("(16778240 16778240)");
		assertThat(run(compileWithVec(program, false, false))).as("scalar").isEqualTo("(16778240 16778240)");
	}

	@Test
	void aMatvecArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines() throws Exception {
		// The chain reads temps: with or without --simd, a declined device attempt (here
		// by size, on every machine) must not re-evaluate the argument forms.
		String program = """
				(defparameter *n* 0)
				(defparameter *w* (linalg:reshape (linalg:arange 1 257) '(16 16)))
				(defparameter *x* (linalg:arange 1 17))
				(vec:matvec (progn (setq *n* (+ *n* 1)) *w*) (progn (setq *n* (+ *n* 10)) *x*))
				(print *n*)
				""";
		assertThat(run(compileWithVec(program, true, false))).isEqualTo("11");
		assertThat(run(compileWithVec(program, true, true))).isEqualTo("11");
		assertThat(run(compileWithVec(program, false, false))).isEqualTo("11");
	}

	@Test
	void aDeclinedMatrixByVectorProductRunsTheSameProgramToTheSameOutputOnAnyMachine() throws Exception {
		// Below the threshold the device is never asked; with a device the first sight
		// declines as well. Both calls print what the defun prints, everywhere.
		for (String option : new String[] { "", " :element-type 'single-float" }) {
			String program = """
					(defparameter *w* (linalg:reshape (linalg:sin (linalg:arange 1 257%s)) '(16 16)))
					(defparameter *x* (linalg:cos (linalg:arange 1 17%s)))
					(print (list (vec:matvec *w* *x*) (vec:matvec *w* *x*)))
					""".formatted(option, option);
			assertThat(run(compileWithVec(program, true, false))).as(option)
				.isEqualTo(run(compileWithVec(program, false, false)));
		}
	}

	// --- device residency (2026-08-22) ------------------------------------------------

	/**
	 * Every in-place writer of a packed float array there is, each followed by the same
	 * bit-identical device member over the array it wrote, so that a writer the residency
	 * invalidation does not see shows up as a wrong sum. The first call makes both
	 * operands resident; {@code check} then prints a sum the oracle must print too.
	 * {@code %la-scale} / {@code %la-scatter-rows} / {@code %la-adam-step} /
	 * {@code %la-rng-fill} and the {@code vec:} {@code -into} family are the kernels that
	 * bypass the element setter under {@code --simd}, which is why the program is run
	 * with that flag as well as without it. {@code fill} and {@code replace} are not here
	 * because the interpreter does not accept a packed array for either; on the JVM they
	 * expand to the setter this program already exercises. {@code read-sequence} over a
	 * binary file IS here: its bulk primitive writes the storage behind the setter's back
	 * on both backends, and it is how a model's weights arrive ({@code examples/llama2}).
	 */
	private static String residencyWriters(int side, String type, String file) {
		int n = side * side;
		return """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(defparameter *v* (linalg:arange 1 %d%s))
				(defparameter *one* (linalg:ones '(1)%s))
				(defun check (tag)
				  (format t "~a ~a ~a~%%" tag (linalg:sum (linalg:add *a* *row*)) (linalg:sum (linalg:add *v* *one*))))
				(check "resident")
				(setf (aref *a* 3 4) 0.5) (check "aset")
				(setf (row-major-aref *a* 777) -1.25) (check "row-major-aset")
				(setf (aref *row* 0 5) 100) (check "aset-other-operand")
				(setf (aref *v* 10) 3) (check "aset-vector")
				(setf (row-major-aref *v* 11) 4) (check "row-major-aset-vector")
				(linalg::%%la-scale *a* 3) (check "la-scale")
				(linalg::%%la-scale *v* 0.5) (check "la-scale-vector")
				(linalg::%%la-scatter-rows *a* (linalg:ones '(2 %d)%s) #d(1.0 5.0)) (check "la-scatter-rows")
				(linalg::%%la-adam-step *a* (linalg:ones '(%d %d)%s) (linalg:zeros '(%d %d)%s) (linalg:zeros '(%d %d)%s)
				                        #d(0.01 0.0 0.0 0.9 0.1 0.999 0.001 0.00000001 1.0 1.0 0.0))
				(check "la-adam-step")
				(linalg::%%la-rng-fill *a* #d(11.0 22.0 33.0) 0 0.0 1.0) (check "la-rng-fill")
				(linalg::%%la-rng-fill *v* #d(44.0 55.0 66.0) 1 0.0 1.0) (check "la-rng-fill-vector")
				(vec:scale-into *v* *v* 2) (check "vec-scale-into")
				(vec:add-into *v* *v* *v*) (check "vec-add-into")
				(vec:negative-into *v* *v*) (check "vec-negative-into")
				(vec:clip-into *v* *v* -1 1) (check "vec-clip-into")
				(vec:matvec-into *v* (linalg:ones '(%d 1)%s) *one*) (check "vec-matvec-into")
				(with-open-file (s "%s" :element-type '(unsigned-byte 8)) (read-sequence *v* s)) (check "read-sequence")
				""".formatted(n + 1, type, side, side, side + 1, type, side, n + 1, type, type, side, type, side, side,
				type, side, side, type, side, side, type, n, type, file);
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void everyEnumeratedWriterInvalidatesTheResidentCopy() throws Exception {
		// The compiled half of the invalidation enumeration: _fvAset1/2/N report through
		// _gpuWritten, the in-place --simd kernels' call sites report the arrays they
		// wrote, every vec: -into call site reports its destination, and a bulk
		// read-sequence reports the array it filled. Any one of them missing leaves a
		// stale device copy and a sum the oracle does not print.
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		Path file = this.tempDir.resolve("resident.bin");
		java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(side * (DOUBLES ? 8 : 4))
			.order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < side; i++) {
			if (DOUBLES) {
				bytes.putDouble(7.0);
			}
			else {
				bytes.putFloat(7.0f);
			}
		}
		Files.write(file, bytes.array());
		String program = residencyWriters(side, TYPE, file.toString());
		String oracle = run(compileWithVec(program, false, false));
		assertThat(oracle).contains("resident ").contains("vec-matvec-into ").contains("read-sequence ");
		assertThat(run(compileWithVec(program, true, false))).as("--gpu").isEqualTo(oracle);
		// The --simd leg against a --simd oracle, as in the interpreter's test: the lane
		// sum folds in its own order, and the device must be the only variable.
		assertThat(run(compileWithVec(program, true, true))).as("--gpu --simd")
			.isEqualTo(run(compileWithVec(program, false, true)));
	}

	// --- lazy results and the resident tier (.todo/491) -------------------------------

	/**
	 * The compiled half of the READER enumeration: every host read of packed-array
	 * storage the class output has, each over a result the device produced and left there
	 * -- {@code _fvAref1/2/N}, {@code _fvToGeneral} and its print twin, the scalar defun
	 * rung and the lane rung of an accelerated call site, the {@code vec:} bridge and
	 * defun, a typed {@code dotimes}, {@code _writeSeqPacked}, and the writes that must
	 * bring the result home BEFORE they land ({@code _fvAset*}, an in-place
	 * {@code --simd} kernel's call site, an {@code -into} call site). A reader the
	 * materialization misses prints zeros. The program is the interpreter test's.
	 */
	private static String residencyReaders(int side, String type, String file) {
		int n = side * side;
		return """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(defparameter *v* (linalg:arange 1 %d%s))
				(defparameter *one* (linalg:ones '(1)%s))
				(defparameter *r* (linalg:add *a* *row*))
				(defparameter *rv* (linalg:add *v* *one*))
				(format t "aref ~a ~a ~a ~a~%%" (aref *r* 3 4) (row-major-aref *r* 777) (aref *rv* 10) (row-major-aref *rv* 11))
				(format t "print ~a~%%" (subseq (format nil "~a" *rv*) 0 24))
				(format t "prin1 ~a~%%" (subseq (prin1-to-string *r*) 0 24))
				(format t "array-equal ~a~%%" (linalg:array-equal *r* (linalg:add *a* *row*)))
				(format t "sum ~a ~a~%%" (linalg:sum *r*) (linalg:sum *rv*))
				(format t "vec ~a ~a~%%" (vec:sum *rv*) (vec:dot *rv* *rv*))
				(let ((rv *rv*) (acc 0.0))
				  (dotimes (i %d) (setq acc (+ acc (aref rv i))))
				  (format t "loop ~a~%%" acc))
				(format t "to-list ~a~%%" (subseq (linalg:to-list *rv*) 0 4))
				(format t "length ~a ~a~%%" (length *rv*) (array-dimensions *r*))
				(with-open-file (s "%s" :direction :output :element-type '(unsigned-byte 8) :if-exists :supersede)
				  (write-sequence *rv* s))
				(defparameter *back* (linalg:zeros '(%d)%s))
				(with-open-file (s "%s" :element-type '(unsigned-byte 8)) (read-sequence *back* s))
				(format t "write-sequence ~a ~a~%%" (aref *back* 7) (linalg:sum *back*))
				(defparameter *r2* (linalg:add *a* *row*))
				(setf (aref *r2* 0 0) -1.0)
				(format t "aset-into-result ~a ~a ~a~%%" (aref *r2* 0 0) (aref *r2* 1 1) (linalg:sum *r2*))
				(defparameter *r3* (linalg:add *a* *row*))
				(linalg::%%la-scale *r3* 2)
				(format t "scale-result ~a~%%" (linalg:sum *r3*))
				(defparameter *r4* (linalg:add *v* *one*))
				(vec:scale-into *r4* *r4* 0.5)
				(format t "into-result ~a~%%" (vec:sum *r4*))
				(format t "chain ~a~%%" (linalg:sum (linalg:add (linalg:add *a* *row*) *row*)))
				(format t "transpose ~a~%%" (linalg:sum (linalg:transpose (linalg:add *a* *row*) '(1 0))))
				"""
			.formatted(n + 1, type, side, side, side + 1, type, side, n + 1, type, type, side, file, n, type, file);
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void everyEnumeratedReaderMaterializesTheDeviceResult() throws Exception {
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		Path file = this.tempDir.resolve("lazy.bin");
		String program = residencyReaders(side, TYPE, file.toString());
		String oracle = run(compileWithVec(program, false, false));
		assertThat(oracle).contains("aref ").contains("write-sequence ").contains("transpose ");
		assertThat(run(compileWithVec(program, true, false))).as("--gpu").isEqualTo(oracle);
		assertThat(run(compileWithVec(program, true, true))).as("--gpu --simd")
			.isEqualTo(run(compileWithVec(program, false, true)));
	}

	/**
	 * The compiled half of {@code .todo/492}: a lazy result's host array is a STUB -- the
	 * header alone -- and the elements are allocated only when something reads them. The
	 * one observable the class output has is MEMORY, so the pin is a run of the class in
	 * a JVM too small to hold the results: forty-eight 16 MB activations kept reachable
	 * on a 256 MB heap, which fits only if none of them has a host array, with the one
	 * that IS read landing on the oracle's bits -- and the same program without the flag
	 * failing for want of heap, so the bound has teeth.
	 */
	@Test
	@EnabledIf("aDeviceIsAvailable")
	void aLazyResultAllocatesNoHostArrayOnTheCompiledBackend() throws Exception {
		assumeTrue(am.ik.gpu.GpuThresholds.lazyResultsPay(), "lazy results pay on this backend");
		int side = 2048;
		int n = side * side;
		String program = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d :element-type 'single-float) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d :element-type 'single-float) '(1 %d)))
				(defparameter *keep* nil)
				(dotimes (i 48) (setq *keep* (cons (linalg:add *a* *row*) *keep*)))
				(format t "kept ~a~%%" (length *keep*))
				(format t "sum ~a ~a~%%" (aref (car *keep*) 7 9) (aref (car (last *keep*)) %d %d))
				""".formatted(n + 1, side, side, side + 1, side, side - 1, side - 1);
		String oracle = run(compileWithVec(program.replace("(dotimes (i 48)", "(dotimes (i 1)"), false, false));
		assertThat(oracle).contains("kept 1").contains("sum ");
		Process gpu = runInASmallJvm(compileWithVec(program, true, false));
		String out = new String(gpu.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String err = new String(gpu.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(gpu.waitFor()).as("--gpu on a 256 MB heap:%n%s%s", out, err).isZero();
		assertThat(out.trim()).contains("kept 48").endsWith(oracle.substring(oracle.indexOf("sum ")));
		Process cpu = runInASmallJvm(compileWithVec(program, false, false));
		String cpuErr = new String(cpu.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(cpu.waitFor()).as("the same program with host arrays does not fit").isNotZero();
		assertThat(cpuErr).contains("OutOfMemoryError");
	}

	/** Runs a compiled class in a child JVM with a 256 MB heap. */
	private Process runInASmallJvm(byte[] classBytes) throws Exception {
		Path dir = Files.createDirectories(this.tempDir.resolve("small-" + System.nanoTime()));
		Files.write(dir.resolve("Test.class"), classBytes);
		String java = ProcessHandle.current().info().command().orElse("java");
		return new ProcessBuilder(java, "-Xmx256m", "--enable-native-access=ALL-UNNAMED", "-cp", dir.toString(), "Test")
			.start();
	}

	/** The interpreter test's resident-tier program, verbatim. */
	private static String residentTier(int side, String type) {
		int n = side * side;
		return """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(defparameter *r* (linalg:add *a* *row*))
				(defparameter *b* (linalg:reshape (linalg:linspace 0.5 9.5 %d%s) '(%d %d)))
				(defun s (x) (linalg:sum x))
				(format t "equal ~a ~a ~a ~a ~a ~a~%%" (s (linalg:add *r* *b*)) (s (linalg:sub *b* *r*)) (s (linalg:mul *r* *b*))
				        (s (linalg:div *b* *r*)) (s (linalg:maximum *r* *b*)) (s (linalg:minimum *b* *r*)))
				(format t "compare ~a ~a ~a ~a ~a~%%" (s (linalg:greater *r* *b*)) (s (linalg:greater-equal *b* *r*))
				        (s (linalg:less *r* *b*)) (s (linalg:less-equal *b* *r*)) (s (linalg:equal *r* *r*)))
				(format t "scalar ~a ~a ~a ~a ~a~%%" (s (linalg:mul *r* 0.3)) (s (linalg:div *r* 7)) (s (linalg:sub 2.5 *r*))
				        (s (linalg:div 1 *r*)) (s (linalg:greater *r* 100)))
				(format t "unary ~a ~a ~a ~a~%%" (s (linalg:sqrt *r*)) (s (linalg:abs (linalg:sub 100 *r*)))
				        (s (linalg:negative *r*)) (s (linalg:sign (linalg:sub *r* 50))))
				(format t "where ~a ~a~%%" (s (linalg:where (linalg:greater *row* 3) *r* -1.5)) (s (linalg:where *r* 2.0 *b*)))
				(defparameter *g* (linalg:mul *r* 0.01))
				(defparameter *x* (linalg:reshape (linalg:linspace -1.0 1.0 %d%s) '(%d %d)))
				(defparameter *m* (linalg:zeros '(%d %d)%s))
				(defparameter *v* (linalg:zeros '(%d %d)%s))
				(linalg::%%la-adam-step *x* *g* *m* *v* #d(0.01 0.001 0.1 0.9 0.1 0.999 0.001 0.00000001 0.1 0.001 2.0))
				(linalg::%%la-adam-step *x* *g* *m* *v* #d(0.01 0.001 0.1 0.9 0.1 0.999 0.001 0.00000001 0.19 0.001999 1.0))
				(format t "adam ~a ~a ~a~%%" (s *x*) (s *m*) (s *v*))
				(format t "chain ~a~%%" (s (linalg:div (linalg:sub (linalg:mul *r* 2) *b*) (linalg:add *b* 0.5))))
				(format t "reshape ~a ~a~%%" (s (linalg:mul (linalg:reshape *r* (list (* %d 2) (/ %d 2))) 1.5))
				        (linalg:to-list (linalg:slice (linalg:reshape *r* (list (* %d 2) (/ %d 2))) '((1 3) (3 7)))))
				(format t "transpose ~a~%%" (linalg:to-list (linalg:slice (linalg:transpose *r*) '((2 4) (0 3)))))
				(format t "slice ~a ~a~%%" (s (linalg:slice *r* '((1 %d 3) (5 nil 2))))
				        (linalg:to-list (linalg:slice *r* '((10 2 -4) (7 1 -3)))))
				(format t "cat ~a ~a~%%" (s (linalg:concatenate (list *r* *b* *r*) :axis 0))
				        (linalg:to-list (linalg:slice (linalg:concatenate (list *b* *r*) :axis 1) (list '(3 4) (list (- %d 2) (+ %d 2))))))
				(defparameter *g2* (linalg:mul *r* 0.5))
				(linalg::%%la-scale *g2* 0.125)
				(format t "scale ~a ~a~%%" (s *g2*) (aref *g2* 2 3))
				"""
			.formatted(n + 1, type, side, side, side + 1, type, side, n, type, side, side, n, type, side, side, side,
					side, type, side, side, type, side, side, side, side, side, side, side);
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theResidentTierRunsOverAResidentOperandAndLandsOnTheCpuKernelsBits() throws Exception {
		// The compiled half: the emit gate names the new members, the call sites chain
		// the device rung ahead of the lane kernel and the defun, and every one lands on
		// the oracle's bits -- the interpreter test is where "really ran" is pinned, by
		// the residency hit count the embedded copy of the library does not expose.
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		String program = residentTier(side, TYPE);
		String oracle = scalar(program);
		assertThat(oracle).contains("equal ").contains("adam ").contains("chain ");
		assertThat(embedsGpuBridge(compile(program, true))).isTrue();
		assertThat(accel(program)).as("--gpu").isEqualTo(oracle);
		assertThat(run(compile(program, true, false, true))).as("--gpu --simd")
			.isEqualTo(run(compile(program, false, false, true)));
	}

	@Test
	void theResidentTierAndWhereAndTheAdamUpdateAreInTheEmitGate() {
		// A program whose only linalg: call is one of the new members embeds the bridge:
		// where, the Adam update and the comparison masks alone.
		for (String call : new String[] { "(linalg:where #d(1.0 0.0) #d(1.0 2.0) 0.0)",
				"(linalg:greater #d(1.0 2.0) 1.5)", "(linalg:sqrt #d(4.0 9.0))",
				"(linalg::%la-adam-step #d(1.0) #d(0.5) #d(0.0) #d(0.0) #d(0.01 0.001 0.1 0.9 0.1 0.999 0.001 0.00000001 0.1 0.001 0.0))" }) {
			assertThat(embedsGpuBridge(compile("(print " + call + ")", true))).as(call).isTrue();
			assertThat(embedsGpuBridge(compile("(print " + call + ")", false))).as(call).isFalse();
		}
	}

	/**
	 * The index tier's program, the interpreter test's own: a resident table, the lookup,
	 * the per-row pick, and the scatter-add over an index vector whose values repeat.
	 */
	private static String indexTier(int side, String type) {
		int n = side * side;
		return """
				(defun ix (m k)
				  (let ((v (linalg:zeros (list m)%s)))
				    (dotimes (i m v) (setf (aref v i) (mod (* i 7) k)))))
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(defparameter *t* (linalg:add *a* *row*))
				(defparameter *idx* (ix %d %d))
				(defparameter *col* (ix %d %d))
				(defun s (x) (linalg:sum x))
				(format t "take ~a ~a~%%" (s (linalg:take-rows *t* *idx*))
				        (linalg:to-list (linalg:slice (linalg:take-rows *t* *idx*) '((2 4) (0 3)))))
				(format t "pick ~a ~a~%%" (s (linalg:gather *t* *col*))
				        (linalg:to-list (linalg:slice (linalg:gather *t* *col*) '((0 5)))))
				(defparameter *z* (linalg:mul *t* 0.5))
				(format t "scatter ~a ~a~%%"
				        (s (linalg::%%la-scatter-rows *z* (linalg:take-rows *t* *idx*) *idx*)) (aref *z* 3 4))
				""".formatted(type, n + 1, type, side, side, side + 1, type, side, 4 * side, side, side, side);
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theIndexTierRunsOverAResidentTableAndLandsOnTheCpuKernelsBits() throws Exception {
		// The compiled half of the index tier: the embedding lookup, its
		// scatter-add adjoint and the cross-entropy pick, all three index-driven copies,
		// so all three land on the lane kernels' bits.
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		String program = indexTier(side, TYPE);
		String oracle = scalar(program);
		assertThat(oracle).contains("take ").contains("pick ").contains("scatter ");
		assertThat(embedsGpuBridge(compile(program, true))).isTrue();
		assertThat(accel(program)).as("--gpu").isEqualTo(oracle);
		assertThat(run(compile(program, true, false, true))).as("--gpu --simd")
			.isEqualTo(run(compile(program, false, false, true)));
	}

	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theClipNormFoldsInBlocksOnTheDeviceCloseToTheSequentialSumAndReproducibly() throws Exception {
		// The one member of this bridge whose fold ORDER is not the defun's, so the
		// assertion is closeness rather than equality -- and reproducibility, because the
		// block count is a function of the length and not of the schedule.
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		String program = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *g* (linalg:mul *a* 0.001))
				(format t "norm ~a~%%" (sqrt (linalg::%%la-sum-squares *g* 0.25)))
				(linalg::%%la-scale *g* 0.5)
				(format t "scaled ~a~%%" (sqrt (linalg::%%la-sum-squares *g* 0.0)))
				""".formatted(side * side + 1, TYPE, side, side);
		double[] oracle = numbers(scalar(program));
		double[] device = numbers(accel(program));
		assertThat(device).hasSameSizeAs(oracle);
		for (int i = 0; i < oracle.length; i++) {
			assertThat(device[i]).as("value %d", i).isCloseTo(oracle[i], within(Math.abs(oracle[i]) * 1e-9));
		}
		assertThat(accel(program)).isEqualTo(accel(program));
	}

	/** The doubles of a printed program's output, in order. */
	private static double[] numbers(String printed) {
		java.util.List<Double> values = new java.util.ArrayList<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+\\.\\d+(?:[eEdD]-?\\d+)?").matcher(printed);
		while (m.find()) {
			values.add(Double.parseDouble(m.group().replace('d', 'e').replace('D', 'E')));
		}
		double[] out = new double[values.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = values.get(i);
		}
		return out;
	}

	@Test
	void theIndexTierAndTheClipNormAreInTheEmitGate() {
		// A program whose only linalg: call is one of the index tier's members embeds the
		// bridge: the lookup, the pick, the scatter-add and the sum of squares alone.
		for (String call : new String[] { "(linalg:take-rows #d((1.0 2.0) (3.0 4.0)) #d(1 0))",
				"(linalg:gather #d((1.0 2.0) (3.0 4.0)) #d(1 0))",
				"(linalg::%la-scatter-rows #d((1.0 2.0) (3.0 4.0)) #d((1.0 1.0)) #d(0))",
				"(linalg::%la-sum-squares #d(1.0 2.0) 0.0)" }) {
			assertThat(embedsGpuBridge(compile("(print " + call + ")", true))).as(call).isTrue();
			assertThat(embedsGpuBridge(compile("(print " + call + ")", false))).as(call).isFalse();
		}
	}

	// --- the fused tier (.todo/499) --------------------------------------------------

	@Test
	void theFusedTierIsInTheEmitGate() {
		// A program whose only linalg: call is one of the fused members embeds the
		// bridge -- softmax in its :axis form among them, which is the one shape the
		// device takes it at.
		for (String call : new String[] { "(linalg:softmax #d((1.0 2.0) (3.0 4.0)) :axis -1)",
				"(linalg::%la-softmax-grad #d((1.0 2.0)) #d((0.5 0.5)) 1)",
				"(linalg:log-softmax #d((1.0 2.0) (3.0 4.0)) :axis -1)",
				"(linalg::%la-log-softmax-grad #d((1.0 2.0)) #d((0.5 0.5)) 1)", "(linalg::%la-gelu #d(1.0 2.0))",
				"(linalg::%la-gelu-grad #d(1.0) #d(2.0) nil)", "(linalg::%la-layer-norm #d((1.0 2.0)) 1.0e-5)",
				"(linalg::%la-layer-norm-grad #d((1.0 2.0)) #d((3.0 4.0)) 1.0e-5 nil)",
				"(linalg::%la-layer-norm-affine #d((1.0 2.0)) #d(1.0 1.0) #d(0.0 0.0) 1.0e-5)",
				"(linalg::%la-layer-norm-affine-grad #d((1.0 2.0)) #d((3.0 4.0)) #d(1.0 1.0) 1.0e-5 nil)",
				"(linalg::%la-dropout-mask '(2) 0.5 (linalg::%la-rng-state) nil)",
				"(linalg::%la-scaled-masked-softmax #d((1.0 2.0)) 8.0 #d((0.0 1.0)) -1.0 1)",
				"(linalg::%la-scaled-masked-softmax-grad #d((1.0 2.0)) #d((0.5 0.5)) 1 8.0 nil)" }) {
			assertThat(embedsGpuBridge(compile("(print " + call + ")", true))).as(call).isTrue();
			assertThat(embedsGpuBridge(compile("(print " + call + ")", false))).as(call).isFalse();
		}
	}

	@Test
	void theFusedTierRunsOnTheCompiledBackendAndLandsOnTheChainsBits() throws Exception {
		// Each fused member against the chain of members it replaces, written out and
		// run through the same call sites: T with the flag (fused kernel against device
		// chain, bit for bit, exp and erf included) and T without it (defun against
		// defun). The libm-free three are also byte-identical to the unflagged run.
		//
		// log-softmax is the ONE member pinned as a bound rather than as bit-identity,
		// and the reason is structural rather than numerical: the chain it replaces ends
		// in a linalg:log over the ROW SUMS, an array of `rows` elements, which is three
		// orders of magnitude under the element-wise threshold and so runs on the host on
		// any backend whose chain is not otherwise fully accepted. The fused kernel takes
		// its log on the device. Two different logs, so the two sides cannot be the same
		// bits, and asking them to be says nothing about the kernel. Measured on Metal
		// (2026-09-03): this line answered NIL the moment the tier was sized so that it
		// ran at all -- it had been sized off the fold threshold, which is the sentinel
		// there, and the whole test was defun against defun.
		// Sized off the FUSED threshold in force, which is the fold threshold on CUDA and
		// the MAP threshold on Metal, whose fold threshold is the "never for its size"
		// sentinel. Sized off the fold threshold -- as this was -- that arithmetic
		// overflows there, Math.max hands back the floor of 256, and 256 x 384 sits under
		// the fused threshold: every array-equal below then printed T from defun against
		// defun. This is todo-495's fix, applied to the compiled sibling it missed.
		int rows = (int) Math.max(256, (am.ik.gpu.GpuThresholds.fusedMinElements() + 383) / 384);
		int n = rows * 384;
		assertThat(am.ik.gpu.GpuThresholds.acceptedForSize(am.ik.gpu.GpuThresholds.fusedMinElements(), n))
			.as("the fused tier is offered at %d elements here", n)
			.isTrue();
		String program = """
				(defparameter *x* (linalg:reshape (linalg:linspace -3.0 3.0 %d%s) '(%d 384)))
				(defparameter *g* (linalg:reshape (linalg:linspace 1.0 -2.0 %d%s) '(%d 384)))
				(defun softmax-chain (a)
				  (let ((e (linalg:exp (linalg:sub a (linalg:amax a :axis 1 :keepdims t)))))
				    (linalg:div e (linalg:sum e :axis 1 :keepdims t))))
				(defun gelu-chain (x)
				  (linalg:mul (linalg:mul x 0.5) (linalg:add 1.0 (linalg:erf (linalg:div x 1.4142135623730951)))))
				(defun gelu-grad-chain (g x old)
				  (let* ((t1 (linalg:mul x 0.5)) (t2 (linalg:div x 1.4142135623730951))
				         (t4 (linalg:add 1.0 (linalg:erf t2)))
				         (g1 (linalg:mul g t4)) (g4 (linalg:mul g t1))
				         (g2 (linalg:mul g4 (linalg:mul 1.1283791670955126
				                                        (linalg:exp (linalg:negative (linalg:mul t2 t2))))))
				         (b (linalg:div g2 1.4142135623730951)) (a (linalg:mul g1 0.5)))
				    (linalg:add (if (null old) b (linalg:add old b)) a)))
				(defun log-softmax-chain (a)
				  (let ((s (linalg:sub a (linalg:amax a :axis 1 :keepdims t))))
				    (linalg:sub s (linalg:log (linalg:sum (linalg:exp s) :axis 1 :keepdims t)))))
				(defun log-softmax-grad-chain (g out)
				  (linalg:sub g (linalg:mul (linalg:exp out) (linalg:sum g :axis 1 :keepdims t))))
				(defparameter *m* (linalg:reshape (linalg:greater (linalg:sin (linalg:arange 384)) 0.3) '(1 384)))
				(defparameter *w* (linalg:linspace 0.5 1.5 384%s))
				(defparameter *b* (linalg:linspace -0.3 0.3 384%s))
				(defun attention-chain (x) (softmax-chain (linalg:where *m* (/ -1.0 0.0) (linalg:div x 8.0))))
				(defun attention-grad-chain (g out)
				  (linalg:div (linalg:where *m* 0.0 (linalg:mul out (linalg:sub g (linalg:sum (linalg:mul g out) :axis 1 :keepdims t)))) 8.0))
				(print (linalg:array-equal (linalg:softmax *x* :axis -1) (softmax-chain *x*)))
				(print (linalg:array-equal (linalg::%%la-scaled-masked-softmax *x* 8.0 *m* (/ -1.0 0.0) 1) (attention-chain *x*)))
				(print (linalg:array-equal (linalg::%%la-scaled-masked-softmax-grad *g* (attention-chain *x*) 1 8.0 *m*)
				                           (attention-grad-chain *g* (attention-chain *x*))))
				(print (< (linalg:amax (linalg:abs (linalg:sub (linalg:log-softmax *x* :axis -1)
				                                               (log-softmax-chain *x*)))) 1.0e-5))
				(print (linalg:array-equal
				        (linalg::%%la-log-softmax-grad *g* (linalg:log-softmax *x* :axis -1) 1)
				        (log-softmax-grad-chain *g* (linalg:log-softmax *x* :axis -1))))
				(print (linalg:array-equal (linalg::%%la-gelu *x*) (gelu-chain *x*)))
				(print (linalg:array-equal (linalg::%%la-gelu-grad *g* *x* nil) (gelu-grad-chain *g* *x* nil)))
				(print (linalg:array-equal (linalg::%%la-gelu-grad *g* *x* *g*) (gelu-grad-chain *g* *x* *g*)))
				(print (linalg:sum (linalg::%%la-layer-norm *x* 1.0e-5)))
				(print (linalg:sum (linalg::%%la-layer-norm-grad *g* *x* 1.0e-5 *g*)))
				(print (linalg:sum (linalg::%%la-layer-norm-affine *x* *w* *b* 1.0e-5)))
				(let ((r (linalg::%%la-layer-norm-affine-grad *g* *x* *w* 1.0e-5 *g*)))
				  (print (linalg:sum (car r)))
				  (print (linalg:sum (car (cdr r)))))
				(print (linalg:sum (linalg::%%la-softmax-grad *g* *x* 1)))
				(linalg:seed 9)
				(defparameter *st* (linalg::%%la-rng-state))
				(print (linalg:sum (linalg::%%la-dropout-mask '(%d 384) 0.25 *st* %s)))
				(print *st*)
				"""
			.formatted(n, TYPE, rows, n, TYPE, rows, TYPE, TYPE, rows, DOUBLES ? "nil" : "t");
		String oracle = scalar(program);
		assertThat(oracle).startsWith("T\nT\nT\nT\nT\nT\nT\nT\n");
		assertThat(accel(program)).as("--gpu").isEqualTo(oracle);
		assertThat(run(compile(program, true, false, true))).as("--gpu --simd")
			.isEqualTo(run(compile(program, false, false, true)));
	}

	// MethodHandles.Lookup.defineClass(byte[]) requires the defined class to share the
	// lookup class's package; every test above compiles into the default package, so this
	// one alone proves the whole embedded library -- the bridge glue AND every renamed
	// am.ik.gpu class file -- is renamed into a NON-default package too. Runs on any
	// machine: a below-threshold product declines regardless of whether a device exists.
	@Test
	void theLibraryIsRenamedIntoTheGeneratedClassOwnPackageAndRunsThere() throws Exception {
		String lispCode = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(print (linalg:matmul *a* *a*))
				""";
		String expected = scalar(lispCode);
		List<LispVal> program = LinalgLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] classBytes = new JvmLispCompiler("com/example/Test", false, OptimizeLevel.NONE, false, false, true)
			.compile(program);

		String bridgeName = "com/example/" + JvmGpuRuntimeBuilder.BRIDGE_NAME;
		String gpuPrefix = "com/example/" + JvmGpuRuntimeBuilder.GPU_PREFIX;
		String bytesAsText = new String(classBytes, StandardCharsets.ISO_8859_1);
		assertThat(bytesAsText).contains(bridgeName).contains(gpuPrefix);

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
