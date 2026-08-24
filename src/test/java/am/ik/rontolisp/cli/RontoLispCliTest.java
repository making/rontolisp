package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.HostGlueEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RontoLispCliTest {

	@TempDir
	Path tempDir;

	private String runCli(String input, String... args) {
		ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(in, new PrintStream(out));
		cli.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	/**
	 * Drives the reporting wrapper {@code main} runs the CLI through, answering
	 * {@code {exitCode, stdout, stderr}}. {@code main} itself ends in
	 * {@code System.exit}, so it cannot be called from a test JVM.
	 */
	private String[] runReporting(String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out));
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		int code;
		try {
			code = RontoLispCli.runReporting(cli, args);
		}
		finally {
			System.setErr(oldErr);
		}
		return new String[] { String.valueOf(code), out.toString(StandardCharsets.UTF_8),
				err.toString(StandardCharsets.UTF_8) };
	}

	@Test
	void compileOutputCreatesMissingPackageDirectories() throws Exception {
		// -o com/acme/Kernels.class places the class in a package via its path, so the
		// directory is part of the request (it used to be a NoSuchFileException).
		Path program = this.tempDir.resolve("kernels.lisp");
		Files.writeString(program, "(defun norm (x) x)\n");
		Path output = this.tempDir.resolve("com/acme/Kernels.class");
		runCli("", program.toString(), "-o", output.toString());
		assertThat(output).exists();
	}

	@Test
	void noMainCompilesALibraryClass() throws Exception {
		Path program = this.tempDir.resolve("lib.lisp");
		Files.writeString(program,
				"(defun twice (x) (* x 2))\n" + "(rontolisp:jvm-export 'twice :params '(:s64) :returns :s64)\n");
		Path output = this.tempDir.resolve("Lib.class");
		runCli("", program.toString(), "-o", output.toString(), "--no-main");
		assertThat(output).exists();
	}

	@Test
	void noMainNeedsAClassOutput() throws Exception {
		Path program = this.tempDir.resolve("lib.lisp");
		Files.writeString(program, "(defun twice (x) (* x 2))\n");
		assertThatThrownBy(
				() -> runCli("", program.toString(), "-o", this.tempDir.resolve("lib.wasm").toString(), "--no-main"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--no-main")
			.hasMessageContaining(".class");
		assertThatThrownBy(() -> runCli("", program.toString(), "--no-main"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--no-main");
	}

	/** The library the jar tests below compile: an export of each interesting shape. */
	private Path kernelsLibrary() throws Exception {
		Path program = this.tempDir.resolve("kernels.lisp");
		Files.writeString(program, """
				(defvar *scale* 2.0)
				(defun scaled-sum (a b) (* *scale* (+ a b)))
				(defun norm2 (x) (sqrt (vec:dot x x)))
				(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)
				(rontolisp:jvm-export 'norm2 :params '(:float-vector) :returns :float)
				""");
		return program;
	}

	private static Map<String, byte[]> entries(Path jar) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		return entries;
	}

	@Test
	void aLibraryJarCarriesTheClassTheHandleRuntimeAndItsOwnCoordinates() throws Exception {
		Path jar = this.tempDir.resolve("acme-kernels-1.0.0.jar");
		runCli("", kernelsLibrary().toString(), "-o", jar.toString(), "--class-name", "com.acme.Kernels",
				"--maven-coordinates", "com.acme:acme-kernels:1.0.0", "--no-main");
		Map<String, byte[]> entries = entries(jar);
		// The handle runtime travels INSIDE the artifact: the .class path writes it
		// beside the output class, and a jar that left it out is a NoClassDefFoundError
		// in the consumer rather than an error at compile time.
		assertThat(entries.keySet()).containsExactly("META-INF/MANIFEST.MF",
				"META-INF/maven/com.acme/acme-kernels/pom.xml", "META-INF/maven/com.acme/acme-kernels/pom.properties",
				"com/acme/Kernels.class", "am/ik/rontolisp/runtime/RontoBoundary.class",
				"am/ik/rontolisp/runtime/RontoFloatArray$Width.class", "am/ik/rontolisp/runtime/RontoFloatArray.class");
		String manifest = new String(entries.get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8);
		// A library is not a program: nobody should java -jar it, so it carries no
		// Main-Class.
		assertThat(manifest).contains("Manifest-Version: 1.0")
			.contains("Created-By: rontolisp ")
			.doesNotContain("Main-Class");
		assertThat(new String(entries.get("META-INF/maven/com.acme/acme-kernels/pom.xml"), StandardCharsets.UTF_8))
			.contains("<artifactId>acme-kernels</artifactId>");
		assertThat(
				new String(entries.get("META-INF/maven/com.acme/acme-kernels/pom.properties"), StandardCharsets.UTF_8))
			.isEqualTo("groupId=com.acme\nartifactId=acme-kernels\nversion=1.0.0\n");
	}

	@Test
	void aLibraryJarIsSelfContainedOnAClasspathThatCarriesNothingOfRontolisp() throws Exception {
		Path jar = this.tempDir.resolve("kernels.jar");
		runCli("", kernelsLibrary().toString(), "-o", jar.toString(), "--class-name", "com.acme.Kernels", "--no-main");
		// The platform loader as parent, so nothing of rontolisp is visible except what
		// the jar itself carries -- which is the whole claim the artifact makes.
		try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() },
				ClassLoader.getPlatformClassLoader())) {
			Class<?> kernels = loader.loadClass("com.acme.Kernels");
			assertThat(kernels.getMethod("scaledSum", double.class, double.class).invoke(null, 2.5, 3.5))
				.isEqualTo(12.0);
			Class<?> handle = loader.loadClass("am.ik.rontolisp.runtime.RontoFloatArray");
			Object x = handle.getMethod("of", double[].class, int[].class)
				.invoke(null, new double[] { 3.0, 4.0 }, new int[0]);
			assertThat(kernels.getMethod("norm2", handle).invoke(null, x)).isEqualTo(5.0);
		}
	}

	@Test
	void aProgramJarKeepsItsMainClassSoJavaJarStillRunsIt() throws Exception {
		Path program = this.tempDir.resolve("prog.lisp");
		Files.writeString(program, "(print (+ 1 2))\n");
		Path jar = this.tempDir.resolve("prog.jar");
		runCli("", program.toString(), "-o", jar.toString(), "--class-name", "com.example.Prog");
		assertThat(new String(entries(jar).get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8))
			.contains("Main-Class: com.example.Prog");
	}

	@Test
	void aJarOutputNeedsAClassNameBecauseItsPathNamesNoClass() throws Exception {
		assertThatThrownBy(() -> runCli("", kernelsLibrary().toString(), "-o",
				this.tempDir.resolve("kernels.jar").toString(), "--no-main"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--class-name");
	}

	@Test
	void theClassNameFlagReplacesTheNameTheOutputPathWouldGive() throws Exception {
		// -o names the FILE; --class-name names the class inside it, so a build directory
		// no longer has to be shaped like the package.
		Path output = this.tempDir.resolve("build/K.class");
		runCli("", kernelsLibrary().toString(), "-o", output.toString(), "--class-name", "com.acme.Kernels",
				"--no-main");
		assertThat(Files.readString(output, StandardCharsets.ISO_8859_1)).contains("com/acme/Kernels");
		// The handle runtime has no package path to hang off here, so it lands beside the
		// output file rather than nowhere.
		assertThat(this.tempDir.resolve("build/am/ik/rontolisp/runtime/RontoFloatArray.class")).exists();
	}

	@Test
	void theArtifactFlagsAreRefusedOnAnOutputThatCannotCarryThem() throws Exception {
		Path program = kernelsLibrary();
		Path wasm = this.tempDir.resolve("k.wasm");
		Path classFile = this.tempDir.resolve("K.class");
		Path jar = this.tempDir.resolve("k.jar");
		assertThatThrownBy(
				() -> runCli("", program.toString(), "-o", wasm.toString(), "--class-name", "com.acme.Kernels"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--class-name");
		// A bare .class has no META-INF to carry the pair in.
		assertThatThrownBy(() -> runCli("", program.toString(), "-o", classFile.toString(), "--maven-coordinates",
				"com.acme:kernels:1.0.0"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--maven-coordinates");
		assertThatThrownBy(() -> runCli("", program.toString(), "-o", jar.toString(), "--class-name",
				"com.acme.Kernels", "--no-main", "--emit-pom"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--maven-coordinates");
		assertThatThrownBy(() -> runCli("", program.toString(), "--class-name", "com.acme.Kernels"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("-o");
		assertThatThrownBy(() -> runCli("", program.toString(), "-o", jar.toString(), "--class-name", "9lives"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not a Java identifier");
	}

	@Test
	void emitPomWritesThePomBesideTheJarAndRefusesToOverwriteAFileItDidNotWrite() throws Exception {
		Path program = kernelsLibrary();
		Path jar = this.tempDir.resolve("kernels-1.0.0.jar");
		Path pom = this.tempDir.resolve("kernels-1.0.0.pom");
		runCli("", program.toString(), "-o", jar.toString(), "--class-name", "com.acme.Kernels", "--maven-coordinates",
				"com.acme:kernels:1.0.0", "--no-main", "--emit-pom");
		assertThat(Files.readString(pom)).contains("<artifactId>kernels</artifactId>");
		// Rewriting our own pom is the ordinary rebuild; a hand-written one is the
		// build's, and the jar is already committed by the time we get here.
		runCli("", program.toString(), "-o", jar.toString(), "--class-name", "com.acme.Kernels", "--maven-coordinates",
				"com.acme:kernels:1.0.0", "--no-main", "--emit-pom");
		Files.writeString(pom, "<project>hand written</project>");
		assertThatThrownBy(() -> runCli("", program.toString(), "-o", jar.toString(), "--class-name",
				"com.acme.Kernels", "--maven-coordinates", "com.acme:kernels:1.0.0", "--no-main", "--emit-pom"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-pom would overwrite");
	}

	@Test
	void twoCompilesOfOneProgramProduceByteIdenticalJars() throws Exception {
		// The jar is emitted output like every other artifact: one fixed entry timestamp
		// and a fixed entry order (.kb/emitted-output-determinism.md).
		Path program = kernelsLibrary();
		Path first = this.tempDir.resolve("a.jar");
		Path second = this.tempDir.resolve("b.jar");
		for (Path jar : List.of(first, second)) {
			runCli("", program.toString(), "-o", jar.toString(), "--class-name", "com.acme.Kernels",
					"--maven-coordinates", "com.acme:kernels:1.0.0", "--no-main");
		}
		assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
	}

	@Test
	void anUncaughtConditionReportsOneLineAndExitsOne() throws Exception {
		// The interpreter's half of the cross-backend contract: the condition's report,
		// once, on standard error -- not the 16 (212 for a cl-postgres connect) frames
		// of LispEvaluator the default handler used to print. The program's own output
		// still comes out.
		Path program = this.tempDir.resolve("boom.lisp");
		Files.writeString(program, "(print \"before\")\n(error \"boom: ~a\" 42)\n");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[1]).isEqualTo("\"before\"\n");
		assertThat(result[2].trim()).isEqualTo("Unhandled condition: boom: 42");
	}

	@Test
	void aRontolispDiagnosticIsNotDressedUpAsACondition() throws Exception {
		// Only a signaled condition takes the cross-backend wording; a read error, a
		// compile failure or a bad command line is the COMPILER talking and says
		// "error:", keeping the file:line:column: prefix the frontend put on it.
		Path program = this.tempDir.resolve("unbalanced.lisp");
		Files.writeString(program, "(print (+ 1 2)\n");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[2].trim()).startsWith("error: ").doesNotContain("Unhandled condition");
	}

	@Test
	void replEvaluatesExpression() {
		String output = runCli("(+ 1 2)\n");
		assertThat(output).contains("3");
	}

	@Test
	void replEchoesEveryValueOnItsOwnLine() {
		// As in any CL REPL: (floor 10 3) echoes the quotient AND the remainder.
		assertThat(runCli("(floor 10 3)\n")).contains("3\n1\n");
		assertThat(runCli("(values 1 2 3)\n")).contains("1\n2\n3\n");
		assertThat(runCli("(defun f () (values 1 2))\n(f)\n")).contains("1\n2\n");
		// No values at all echoes nothing, and a single value stays a single line.
		assertThat(runCli("(values)\n")).isEqualTo("> > ");
		assertThat(runCli("(+ 1 2)\n")).isEqualTo("> 3\n> ");
		// Two forms on one line echo twice, as SBCL does reading them one at a time,
		// and each form's own output precedes its own value.
		assertThat(runCli("(values 1 2) (+ 3 4)\n")).isEqualTo("> 1\n2\n7\n> ");
		assertThat(runCli("(print 'a) (print 'b)\n")).isEqualTo("> A\nA\nB\nB\n> ");
	}

	@Test
	void replWithSimdInterceptsVecKernels() {
		// A vec.lisp defun prints as #<lambda>; the installed Vector API kernel prints
		// as #<function vec:dot>. The surefire JVM has jdk.incubator.vector on the
		// module path, so VecSimd.available() is true here.
		String output = runCli("(vec:dot #d(1.0) #d(1.0)) #'vec:dot\n", "--simd");
		assertThat(output).contains("#<function VEC:DOT>");
	}

	@Test
	void replWithBlasInterceptsTheLinalgProductWhenTheMachineHasATunedLibrary() {
		// Conditional on the machine, not on the build: with a tuned CBLAS the
		// linalg.lisp
		// defun (#<lambda>) is replaced by the library kernel, and without one the REPL
		// says so on stderr and keeps running the defun. Both are correct outcomes, so
		// this pins the pair rather than one of them.
		String output = runCli("(linalg:zeros 1) #'linalg:dot\n", "--blas");
		if (am.ik.rontolisp.eval.LinalgBlas.available()) {
			assertThat(output).contains("#<function LINALG:DOT>");
		}
		else {
			assertThat(output).contains("#<lambda>").doesNotContain("#<function LINALG:DOT>");
		}
	}

	@Test
	void replWithGpuInterceptsTheLinalgProductWhenTheMachineHasADevice() {
		// The same pair --blas pins, one layer up and conditional on the machine rather
		// than on the build: with a device the linalg.lisp defun (#<lambda>) is replaced
		// by the interceptor, and without one the REPL says so on stderr and keeps
		// running the defun. Both are correct outcomes.
		String output = runCli("(linalg:zeros 1) #'linalg:dot\n", "--gpu");
		if (am.ik.rontolisp.eval.LinalgGpu.available()) {
			assertThat(output).contains("#<function LINALG:DOT>");
		}
		else {
			assertThat(output).contains("#<lambda>").doesNotContain("#<function LINALG:DOT>");
		}
	}

	@Test
	void replWithParallelKeepsTheSimdNativesAndRefusesToRunWithoutThem() {
		// --parallel modifies the --simd natives (vec:matvec stays the native function,
		// now splitting its rows) and intercepts nothing of its own, so without --simd it
		// is the dead flag CliOptionsTest is about -- a hard error, not a silent no-op.
		String output = runCli("(vec:zeros 1) #'vec:matvec\n", "--simd", "--parallel");
		assertThat(output).contains("#<function VEC:MATVEC>");
		assertThatThrownBy(() -> runCli("(vec:zeros 1) #'vec:matvec\n", "--parallel"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--parallel splits the --simd kernels across threads, so it needs --simd");
	}

	@Test
	void parallelOnAWasmOutputIsAHardErrorWhileTheClassOutputBindsTheParallelEntries() throws Exception {
		// WASM has no threads, so a .wasm build could only ignore the flag; the JVM class
		// output binds the matrix products to the bridge entries that split their rows.
		Path file = tempDir.resolve("prog.lisp");
		Files.writeString(file, "(print (vec:matvec #d((1 2) (3 4)) #d(5 6)))\n");
		assertThatThrownBy(
				() -> runCli("", file.toString(), "-o", tempDir.resolve("p.wasm").toString(), "--simd", "--parallel"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--parallel reaches the interpreter and the JVM class output only");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", tempDir.resolve("P.class").toString(), "--parallel"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("needs --simd");
		Path classFile = tempDir.resolve("P.class");
		runCli("", file.toString(), "-o", classFile.toString(), "--simd", "--parallel");
		assertThat(Files.readString(classFile, java.nio.charset.StandardCharsets.ISO_8859_1))
			.contains("simdMatvecParallel");
	}

	@Test
	void gpuOnAWasmOutputIsAHardErrorWhileTheClassOutputEmbedsTheBridge() throws Exception {
		// WASM has no foreign function API, so a .wasm build could only IGNORE the flag
		// -- and silently running unaccelerated is exactly what an acceleration flag
		// exists to make visible. The JVM class output HAS one: it carries the whole
		// device binding into the emitted class instead.
		Path file = tempDir.resolve("prog.lisp");
		// A program that reaches the matrix product: the bridge is embedded only for one
		// that does, exactly as --blas's and --simd's are.
		Files.writeString(file, "(print (linalg:matmul (linalg:eye 2) (linalg:eye 2)))\n");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", tempDir.resolve("p.wasm").toString(), "--gpu"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--gpu reaches the interpreter and the JVM class output only");
		Path classFile = tempDir.resolve("P.class");
		runCli("", file.toString(), "-o", classFile.toString(), "--gpu");
		// The bridge's own name is an ordinary class constant; the CUDA binding it calls
		// is base64 in the blob beside it, and the PTX kernels are there verbatim.
		assertThat(Files.readString(classFile, java.nio.charset.StandardCharsets.ISO_8859_1))
			.contains("RontoLispGpuBridge")
			.contains(".visible .entry gemm_f64");
	}

	@Test
	void replWithoutSimdKeepsScalarVecKernels() {
		String output = runCli("(vec:dot #d(1.0) #d(1.0)) #'vec:dot\n");
		assertThat(output).contains("#<lambda>").doesNotContain("#<function VEC:DOT>");
	}

	@Test
	void interpretFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		String output = runCli("", file.toString());
		assertThat(output).contains("3");
	}

	@Test
	void evaluateInlineProgram() {
		// -e runs like a file, not like the REPL: nothing is echoed, so the only output
		// is what the program prints.
		assertThat(runCli("", "-e", "(print (+ 1 2))")).isEqualTo("3\n");
		assertThat(runCli("", "--eval", "(print (+ 1 2))")).isEqualTo("3\n");
		assertThat(runCli("", "-e", "(+ 1 2)")).isEmpty();
	}

	@Test
	void inlineProgramsShareOneEnvironmentInOrder() {
		// Every -e/--eval appends to the same program, so a definition in one is visible
		// to the next.
		assertThat(runCli("", "-e", "(defun f (x) (* x x))", "--eval", "(print (f 5))")).isEqualTo("25\n");
	}

	@Test
	void inlineProgramBesideAnInputFileIsAClearError() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print 1)");
		assertThatThrownBy(() -> runCli("", file.toString(), "-e", "(print 2)"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("-e/--eval cannot be combined with the input file");
	}

	@Test
	void compileInlineProgramToClassFile() throws Exception {
		// The inline program is the same program a file holds, so -o compiles it.
		Path classFile = tempDir.resolve("Inline.class");
		runCli("", "-e", "(print (+ 1 2))", "-o", classFile.toString());
		assertThat(Files.readAllBytes(classFile)).startsWith((byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE);
	}

	@Test
	void compileToClassFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		Path classFile = tempDir.resolve("Test.class");
		runCli("", file.toString(), "-o", classFile.toString());
		assertThat(Files.exists(classFile)).isTrue();
		byte[] bytes = Files.readAllBytes(classFile);
		// Check class file magic number
		assertThat(bytes[0]).isEqualTo((byte) 0xCA);
		assertThat(bytes[1]).isEqualTo((byte) 0xFE);
		assertThat(bytes[2]).isEqualTo((byte) 0xBA);
		assertThat(bytes[3]).isEqualTo((byte) 0xBE);
	}

	@Test
	void compileToWasmFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		Path wasmFile = tempDir.resolve("test.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString());
		assertThat(Files.exists(wasmFile)).isTrue();
		byte[] bytes = Files.readAllBytes(wasmFile);
		// Check WASM magic number
		assertThat(bytes[0]).isEqualTo((byte) 0x00);
		assertThat(bytes[1]).isEqualTo((byte) 'a');
		assertThat(bytes[2]).isEqualTo((byte) 's');
		assertThat(bytes[3]).isEqualTo((byte) 'm');
	}

	@Test
	void clackRontolispBackendUnderNoWasiSynthesizesTheReactorExport() throws Exception {
		// The one-clackup-source contract (.kb/clack.md): under --no-wasi the
		// clack-handler-rontolisp shim is read with #+rontolisp-reactor, its run
		// carries the %http-reactor marker instead of the http-handler directive, and
		// the compiler answers with the synthesized handle-request export over the
		// shared http-reactor.lisp dispatcher. quickloading the shim directly (a
		// BUILT-IN system, no network) and calling its run is the clackup shape
		// minus the clack dist.
		Path file = tempDir.resolve("worker.lisp");
		Files.writeString(file, """
				(ql:quickload "clack-handler-rontolisp")
				(defun app (env) (list 200 (list :content-type "text/plain") (list "ok")))
				(clack.handler.rontolisp:run #'app :port 8080)
				""");
		Path wasmFile = tempDir.resolve("worker.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi");
		String bytes = new String(Files.readAllBytes(wasmFile), StandardCharsets.ISO_8859_1);
		// The export section carries the name; the marker string itself is consumed.
		assertThat(bytes).contains("handle-request");
	}

	@Test
	void httpHandlerDirectiveUnderNoWasiLowersToTheReactorExport() throws Exception {
		// A reactor owns no socket, so the directive means the host-driven transport
		// there: the same handle-request export + JSON envelope clack:clackup takes.
		// The handler is an async-defun (the fetch-capable shape) -- the transport
		// resolves its future at the boundary.
		Path file = tempDir.resolve("app.lisp");
		Files.writeString(file, """
				(rontolisp:async-defun handle (env)
				  (list 200 (list :content-type "text/plain") (list "ok")))
				(rontolisp:http-handler 'handle 8080)
				""");
		Path wasmFile = tempDir.resolve("app.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi");
		String bytes = new String(Files.readAllBytes(wasmFile), StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains("handle-request");
	}

	@Test
	void hostFetchGuardsNameTheBackendConflicts() throws Exception {
		Path file = tempDir.resolve("f.lisp");
		Files.writeString(file, "(print 1)");
		assertThatThrownBy(
				() -> runCli("", file.toString(), "-o", tempDir.resolve("f.class").toString(), "--host-fetch"))
			.hasMessageContaining("--host-fetch requires a .wasm output");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", tempDir.resolve("f.wasm").toString(), "--no-wasi",
				"--no-gc", "--host-fetch"))
			.hasMessageContaining("--host-fetch cannot be combined with --no-gc");
	}

	@Test
	void aComponentBuildReadsTheSourceWithTheComponentFeature() throws Exception {
		// #+rontolisp-reactor cannot say "not a component" -- a --component --no-wasi
		// build IS a reactor and carries it too -- so the component BOUNDARY has its own
		// feature. A source that declares a core wasm-import (which a component refuses
		// outright, its host functions crossing the canonical ABI) guards it with
		// #-rontolisp-component and still compiles both ways;
		// examples/cloudflare-workers/httpbin/worker.lisp is built both ways on exactly
		// this line.
		Path file = tempDir.resolve("both.lisp");
		Files.writeString(file, """
				#-rontolisp-component
				(rontolisp:wasm-import 'pull :from "env" :as "pull" :params '() :returns :int)
				#-rontolisp-component
				(defun body () (pull))
				#+rontolisp-component
				(defun body () 0)
				(rontolisp:wasm-export 'body :params '() :returns :int)
				""");
		Path core = tempDir.resolve("core.wasm");
		runCli("", file.toString(), "-o", core.toString(), "--no-wasi");
		assertThat(new String(Files.readAllBytes(core), StandardCharsets.ISO_8859_1)).contains("envpull");

		Path component = tempDir.resolve("component.wasm");
		runCli("", file.toString(), "-o", component.toString(), "--component", "--no-wasi");
		assertThat(new String(Files.readAllBytes(component), StandardCharsets.ISO_8859_1)).doesNotContain("envpull");
	}

	@Test
	void hostFetchCompilesAFetchingReactorEndToEnd() throws Exception {
		// The full CLI pipeline: the HostFetchLibrary splice, the JSON library and the
		// prelude behind it, and the env.fetch import in the emitted module (the import
		// section spells module and field as length-prefixed names).
		Path file = tempDir.resolve("dog.lisp");
		Files.writeString(file, """
				(rontolisp:async-defun dog ()
				  (let ((res (rontolisp:await (rontolisp:fetch "https://example.com/"))))
				    (rontolisp:await (rontolisp:read-all (getf res :body)))))
				(defun run () (rontolisp::%future-force (dog)))
				(rontolisp:wasm-export 'run :params '() :returns :string)
				""");
		Path wasmFile = tempDir.resolve("dog.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--host-fetch");
		String bytes = new String(Files.readAllBytes(wasmFile), StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains("\u0003env\u0005fetch");
	}

	@Test
	void clackRontolispBackendWithoutNoWasiKeepsTheSocketDirective() throws Exception {
		// The same source on a WASI target reads the #-rontolisp-reactor leg: the
		// http-handler directive (a call-time error on Preview 1, the wasi:http
		// serve wiring under --component) and no reactor export.
		Path file = tempDir.resolve("worker.lisp");
		Files.writeString(file, """
				(ql:quickload "clack-handler-rontolisp")
				(defun app (env) (list 200 (list :content-type "text/plain") (list "ok")))
				(clack.handler.rontolisp:run #'app :port 8080)
				""");
		Path wasmFile = tempDir.resolve("worker.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString());
		String bytes = new String(Files.readAllBytes(wasmFile), StandardCharsets.ISO_8859_1);
		assertThat(bytes).doesNotContain("handle-request");
	}

	@Test
	void compileToComponentWithWitWritesTheWitFileNextToTheWasm() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, """
				(defun pure-add (a b) (+ a b))
				(rontolisp:wasm-export 'pure-add :params '(:int :int) :returns :int)
				""");
		Path wasmFile = tempDir.resolve("test.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString(), "--component", "--emit-wit");
		assertThat(Files.exists(wasmFile)).isTrue();
		String wit = Files.readString(tempDir.resolve("test.wit"));
		assertThat(wit).startsWith("package root:component;\n")
			.contains("  export pure-add: func(p0: s32, p1: s32) -> s32;");
	}

	@Test
	void witWithoutComponentIsAClearError() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print 1)");
		Path wasmFile = tempDir.resolve("test.wasm");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", wasmFile.toString(), "--emit-wit"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-wit requires --component");
		Path classFile = tempDir.resolve("Test.class");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", classFile.toString(), "--component", "--emit-wit"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-wit requires --component");
	}

	@Test
	void compileWithJsGlueWritesTheHostGlueNextToTheWasm() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, """
				(rontolisp:wasm-import 'pull :from "net" :as "pull" :params '(:string) :returns :string :async t)
				(rontolisp:async-defun grab (u) (rontolisp:await (pull u)))
				(rontolisp:wasm-export 'grab :params '(:string) :returns :string)
				""");
		Path wasmFile = tempDir.resolve("test.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--emit-js-glue");
		assertThat(Files.exists(wasmFile)).isTrue();
		// The glue is named after the module and knows it: the import object it writes
		// carries the declared field, and the entry point the build listed is the one it
		// enters through promising.
		String glue = Files.readString(tempDir.resolve("test.js"));
		assertThat(glue).startsWith("// GENERATED by rontolisp --emit-js-glue -- do not edit.")
			.contains("from \"./test.js\"")
			.contains("pull: bind(\"net\", \"pull\"")
			.contains("WebAssembly.promising(exports[\"grab\"])");
	}

	@Test
	void jsGlueOutsideANoWasiCoreModuleIsAClearError() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print 1)");
		Path wasmFile = tempDir.resolve("test.wasm");
		// A WASI module's host is wasmtime, a component's is its bindings generator, and
		// --no-gc imports nothing at all -- none of the three has this glue to write.
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", wasmFile.toString(), "--emit-js-glue"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-js-glue requires --no-wasi");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--component",
				"--emit-js-glue"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-js-glue requires --no-wasi");
		assertThatThrownBy(
				() -> runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--no-gc", "--emit-js-glue"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-js-glue requires --no-wasi");
	}

	@Test
	void anUnknownHostBoundaryIsRefusedByName() throws Exception {
		// The flag used to be spelled --emit-js-glue=envelope, which PARSED (the key is
		// in CliOptions.noValueKeys, which still takes an `=` form) and was thrown away:
		// a build script asking for one boundary silently got the other. Both spellings
		// of that mistake now say so.
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print 1)");
		Path wasmFile = tempDir.resolve("test.wasm");
		for (String bad : List.of("simple", "in-band", "ENVELOPE", "")) {
			assertThatThrownBy(
					() -> runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--host-boundary=" + bad))
				.as("--host-boundary=%s", bad)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unknown --host-boundary '" + bad + "'")
				.hasMessageContaining("streaming, envelope");
		}
		assertThatThrownBy(
				() -> runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--emit-js-glue=envelope"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--emit-js-glue takes no value")
			.hasMessageContaining("--host-boundary=");
	}

	@Test
	void aHostBoundaryOutsideANoWasiCoreModuleIsAClearError() throws Exception {
		// A reactor component is in band already, --no-gc imports nothing, and a WASI
		// command module has no host to import from -- so on none of the three is there a
		// boundary to choose. Refusing beats accepting a flag that would do nothing.
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print 1)");
		Path wasmFile = tempDir.resolve("test.wasm");
		Path classFile = tempDir.resolve("Test.class");
		List<String[]> refused = List.of(new String[] { "-o", wasmFile.toString() },
				new String[] { "-o", wasmFile.toString(), "--no-wasi", "--component" },
				new String[] { "-o", wasmFile.toString(), "--no-wasi", "--no-gc" },
				new String[] { "-o", classFile.toString(), "--no-wasi" });
		for (String[] flags : refused) {
			List<String> args = new java.util.ArrayList<>(List.of(file.toString()));
			args.addAll(List.of(flags));
			args.add("--host-boundary=envelope");
			assertThatThrownBy(() -> runCli("", args.toArray(new String[0]))).as("%s", String.join(" ", args))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining("--host-boundary requires --no-wasi");
		}
	}

	@Test
	void theEnvelopeBoundaryBuildsAModuleWithNoBodyImports() throws Exception {
		// The boundary is a MODULE decision, and this is what it decides: the same source
		// compiled twice, and only the build that ASKS for streaming declares the body
		// imports -- the one that says nothing gets the envelope. The
		// checks are on the emitted names in the import section, the way
		// aComponentBuildReadsTheSourceWithTheComponentFeature reads envpull.
		Path file = tempDir.resolve("w.lisp");
		Files.writeString(file, """
				(rontolisp:async-defun app (env)
				  (declare (ignore env))
				  (let ((res (rontolisp:await (rontolisp:fetch "https://example.test/"))))
				    (list (getf res :status) nil (list "ok"))))
				(rontolisp:http-handler 'app)
				""");
		Path streaming = tempDir.resolve("streaming.wasm");
		runCli("", file.toString(), "-o", streaming.toString(), "--no-wasi", "--host-fetch",
				"--host-boundary=streaming");
		String split = new String(Files.readAllBytes(streaming), StandardCharsets.ISO_8859_1);
		assertThat(split).contains("readRequestBody").contains("writeResponseBody").contains("readResponseBody");

		// ...and the DEFAULT is the envelope, so saying nothing is the second leg.
		Path envelope = tempDir.resolve("envelope.wasm");
		runCli("", file.toString(), "-o", envelope.toString(), "--no-wasi", "--host-fetch");
		String inBand = new String(Files.readAllBytes(envelope), StandardCharsets.ISO_8859_1);
		// The import section is length-prefixed, so env.fetch reads as "\3env\5fetch".
		assertThat(inBand).as("one import, and it is env.fetch")
			.contains("\u0003env\u0005fetch")
			.doesNotContain("readRequestBody")
			.doesNotContain("writeResponseBody")
			.doesNotContain("readResponseBody");
	}

	@Test
	void theStreamingBoundaryComposesWithReentrant() throws Exception {
		// The composed-boundary wiring pin: the CLI threads --reentrant into the
		// body-import
		// synthesis, so the composed build declares the ID-CARRYING shape. Without
		// that threading the synthesized imports are id-less and the compiler's own
		// "call identity" refusal fires -- which is exactly what this would catch.
		Path file = tempDir.resolve("wr.lisp");
		Files.writeString(file, """
				(rontolisp:async-defun app (env)
				  (declare (ignore env))
				  (let ((res (rontolisp:await (rontolisp:fetch "https://example.test/"))))
				    (list (getf res :status) nil (list "ok"))))
				(rontolisp:http-handler 'app)
				""");
		Path wasm = tempDir.resolve("wr.wasm");
		runCli("", file.toString(), "-o", wasm.toString(), "--no-wasi", "--host-fetch", "--host-boundary=streaming",
				"--reentrant");
		assertThat(new String(Files.readAllBytes(wasm), StandardCharsets.ISO_8859_1)).contains("readRequestBody")
			.contains("writeResponseBody")
			.contains("readResponseBody");
	}

	@Test
	void theStreamingBoundaryReadsTheSourceWithTheBodyImportsFeature() throws Exception {
		// The feature a HAND-WRITTEN reactor guards its own body imports with
		// (examples/cloudflare-workers/httpbin/worker.lisp). Pinned the way
		// aComponentBuildReadsTheSourceWithTheComponentFeature pins
		// #-rontolisp-component:
		// one source read twice, the guarded declaration surviving in exactly one build.
		// Without this, dropping the Features.BODY_IMPORTS widening would stay green and
		// silently flip that example to its in-band arm on every build.
		Path file = tempDir.resolve("hand.lisp");
		Files.writeString(file, """
				#+rontolisp-body-imports
				(rontolisp:wasm-import '%pull :from "env" :as "readRequestBody"
				                       :params '() :returns :bytes :async t)
				#+rontolisp-body-imports
				(defun body (buf) (%pull buf))
				#-rontolisp-body-imports
				(defun body (buf) (declare (ignore buf)) nil)
				(defun handle (json) (if (body nil) json json))
				(rontolisp:wasm-export 'handle :params '(:string) :returns :string)
				""");
		Path streaming = tempDir.resolve("hand-streaming.wasm");
		runCli("", file.toString(), "-o", streaming.toString(), "--no-wasi", "--host-boundary=streaming");
		assertThat(new String(Files.readAllBytes(streaming), StandardCharsets.ISO_8859_1))
			.as("the streaming boundary HAS the body imports, so the guarded declaration is read")
			.contains("readRequestBody");

		Path envelope = tempDir.resolve("hand-envelope.wasm");
		runCli("", file.toString(), "-o", envelope.toString(), "--no-wasi");
		assertThat(new String(Files.readAllBytes(envelope), StandardCharsets.ISO_8859_1))
			.as("the DEFAULT boundary has none, so the same source takes its #- arm")
			.doesNotContain("readRequestBody");

		// And nor does any target that could not carry them whatever the flag said.
		Path component = tempDir.resolve("hand-component.wasm");
		runCli("", file.toString(), "-o", component.toString(), "--no-wasi", "--component");
		assertThat(new String(Files.readAllBytes(component), StandardCharsets.ISO_8859_1))
			.doesNotContain("readRequestBody");
	}

	@Test
	void jsGlueRefusesToOverwriteAFileItDidNotWrite() throws Exception {
		// The glue is named after the module, so `-o src/index.wasm` in a Worker
		// directory aims straight at a hand-written src/index.js. Regenerating over the
		// glue's OWN output is the normal case and stays silent.
		Path file = tempDir.resolve("w.lisp");
		Files.writeString(file, """
				(defun twice (n) (* n 2))
				(rontolisp:wasm-export 'twice :params '(:int) :returns :int)
				""");
		Path wasmFile = tempDir.resolve("w.wasm");
		Path glue = tempDir.resolve("w.js");
		Files.writeString(glue, "export default { fetch() {} };\n");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--emit-js-glue"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("would overwrite")
			.hasMessageContaining("w.js");
		assertThat(Files.readString(glue)).isEqualTo("export default { fetch() {} };\n");
		// Its own output is rewritten without complaint, twice over.
		Files.delete(glue);
		runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--emit-js-glue");
		runCli("", file.toString(), "-o", wasmFile.toString(), "--no-wasi", "--emit-js-glue");
		assertThat(Files.readString(glue)).startsWith(HostGlueEmitter.MARKER);
	}

	@Test
	void aSideArtifactFlagWithoutAnOutputIsAClearError() throws Exception {
		// Without -o there is no output to write the file beside, and interpreting the
		// program instead is the least useful answer -- a Worker source would try to
		// bind a socket.
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print 1)");
		assertThatThrownBy(() -> runCli("", file.toString(), "--no-wasi", "--emit-js-glue"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-js-glue")
			.hasMessageContaining("needs -o");
		assertThatThrownBy(() -> runCli("", file.toString(), "--component", "--emit-wit"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-wit")
			.hasMessageContaining("needs -o");
	}

	@Test
	void helpOption() {
		String output = runCli("", "-h");
		assertThat(output).contains("Usage:");
		assertThat(output).contains("rontolisp");
	}

	@Test
	void theTestSubcommandHasItsOwnHelp() {
		assertThat(runCli("", "test", "--help")).contains("Usage: rontolisp test")
			.contains("--reporter")
			.doesNotContain("--scaffold-wit");
		// ...and the top-level usage says the subcommand exists at all.
		assertThat(runCli("", "-h")).contains("test TARGET");
	}

	@Test
	void aTestCommandLineThatCannotNameATargetExitsTwo() {
		// 2, not 1: "the command was wrong" has to stay distinct from "a test failed",
		// which is the whole point of the subcommand's exit code (as with `format`).
		for (String[] args : List.of(new String[] { "test" }, new String[] { "test", "nope.lisp" },
				new String[] { "test", "-r", "not a style", "x.lisp" })) {
			String[] result = runReporting(args);
			assertThat(result[0]).as("%s", String.join(" ", args)).isEqualTo("2");
			assertThat(result[2]).as("%s", String.join(" ", args)).startsWith("rontolisp: test: ");
		}
	}

	@Test
	void versionOption() {
		String output = runCli("", "-v");
		assertThat(output).contains("\"version\":");
		assertThat(output).contains("\"buildTimestamp\":");
		assertThat(output).contains("\"gitCommit\":");
	}

	@Test
	void interpretFileUsingPackages() throws Exception {
		Path file = tempDir.resolve("pkg.lisp");
		Files.writeString(file, """
				(print *package*)
				(in-package :rontolisp)
				(cl:print (cl:car (version)))
				""");
		String output = runCli("", file.toString());
		assertThat(output).contains("CL-USER").contains(":VERSION");
	}

	// -- frontend source positions ------------------------

	@Test
	void aMacroThatSignalsWhileExpandingNamesItsCallSiteInTheLoadedFile() throws Exception {
		// The error comes out of the macro-time evaluator, whose forms the macro BUILT:
		// nothing in the exception knows a file. The nearest enclosing form that WAS read
		// is the call site, and that is what has to be reported -- in the spliced file's
		// own coordinates, not the flattened entry program's.
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defmacro twice (x)
				  (error "twice: bad argument ~a" x))

				(defun f (n)
				  (twice n))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, "(load \"lib.lisp\")\n(print (f 1))\n");
		assertThatThrownBy(() -> runCli("", main.toString(), "-o", this.tempDir.resolve("Main.class").toString()))
			.isInstanceOf(LispCompileException.class)
			.hasMessageContaining("lib.lisp:5:3: twice: bad argument N")
			.hasCauseInstanceOf(RuntimeException.class);
	}

	@Test
	void aMalformedFormDeepInsideADefunNamesItsOwnLineOnBothCompileBackends() throws Exception {
		// The position must survive the whole pipeline -- inliner, resolver, expander,
		// lambda-list desugaring, the cross-lambda exit lowering -- down to the form that
		// actually fails, on the JVM and the WASM backend alike.
		Path file = this.tempDir.resolve("bad.lisp");
		Files.writeString(file, """
				(defun g (x)
				  (let ((a 1) 2)
				    a))
				(print (g 1))
				""");
		for (String output : new String[] { "Bad.class", "bad.wasm" }) {
			assertThatThrownBy(() -> runCli("", file.toString(), "-o", this.tempDir.resolve(output).toString()))
				.isInstanceOf(LispCompileException.class)
				.hasMessageContaining("bad.lisp:2:3:");
		}
	}

	@Test
	void aCallToAnUndefinedFunctionWarnsAtItsCallSite() throws Exception {
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defun broken (y)
				  (no-such-function y))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, "(load \"lib.lisp\")\n(print (broken 1))\n");
		PrintStream savedErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
			runCli("", main.toString(), "-o", this.tempDir.resolve("Main.class").toString());
		}
		finally {
			System.setErr(savedErr);
		}
		assertThat(captured.toString(StandardCharsets.UTF_8))
			.contains("lib.lisp:2:3: warning: the function NO-SUCH-FUNCTION is undefined");
	}

	@Test
	void aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice() throws Exception {
		// The cons-identity rule, end to end: a program that pulls in a spliced library
		// runs the whole rewrite chain over EVERY form, and a pass that rebuilt an
		// untouched form used to erase the position of everything below the top level --
		// which is exactly the multi-library program the feature exists for. One case per
		// pass that used to rebuild unconditionally: the JSON call-site rewrite, the Gray
		// binding-form walk, the component sockets/async rewrite (which also runs
		// ShadowedBuiltins with a non-empty alias map, the arity bundler and the
		// cross-lambda lowering over the spliced library).
		record Case(String trigger, String output, String[] flags) {
		}
		Case[] cases = { new Case("(print (rontolisp:json-parse \"{}\"))", "Json.class", new String[0]),
				new Case("(defclass s (rontolisp:fundamental-character-output-stream) ())", "Gray.class",
						new String[0]),
				new Case("(print (rontolisp:tcp-connect \"localhost\" 80))", "tcp.wasm",
						new String[] { "--component" }),
				new Case("(print (rontolisp:fetch \"http://example.com/\"))", "fetch.wasm",
						new String[] { "--component" }) };
		for (Case testCase : cases) {
			Path file = this.tempDir.resolve("bad.lisp");
			Files.writeString(file, """
					(defun g (x)
					  (let ((a 1) 2)
					    a))
					%s
					(print (g 1))
					""".formatted(testCase.trigger()));
			String[] args = new String[3 + testCase.flags().length];
			args[0] = file.toString();
			args[1] = "-o";
			args[2] = this.tempDir.resolve(testCase.output()).toString();
			System.arraycopy(testCase.flags(), 0, args, 3, testCase.flags().length);
			assertThatThrownBy(() -> runCli("", args)).as("%s", testCase.trigger())
				.isInstanceOf(LispCompileException.class)
				.hasMessageContaining("bad.lisp:2:3:");
		}
	}

	@Test
	void aMalformedFormKeepsItsLineInsideAPackageQualifiedFile() throws Exception {
		// The OTHER half of the same rule: the package resolver genuinely REWRITES
		// every form that names something the current package resolves differently
		// -- *package*, an unqualified name of a non-cl-user package -- so the rebuilt
		// cons has to INHERIT the position of the one it replaces. That is not a rare
		// shape: it is every form of every file that says (in-package :foo) and then
		// names anything qualified, i.e. the whole of every quickloaded library, which
		// used to report with no position at all -- neither its own line nor the
		// top-level one.
		Path file = this.tempDir.resolve("bad.lisp");
		Files.writeString(file, """
				(defpackage :probe
				  (:use :cl))
				(in-package :probe)
				(defun g (x)
				  (let ((a *package*) (b (helper x)) 2)
				    (list a b)))
				(defun helper (x) x)
				(print (g 1))
				""");
		for (String output : new String[] { "Bad.class", "bad.wasm" }) {
			assertThatThrownBy(() -> runCli("", file.toString(), "-o", this.tempDir.resolve(output).toString()))
				.isInstanceOf(LispCompileException.class)
				.hasMessageContaining("bad.lisp:5:3:");
		}
	}

	@Test
	void aMalformedFormKeepsItsLineUnderALetBoundDesignator() throws Exception {
		// The same inheriting half for the designator propagation: the binding leaves and
		// its funcall site is rewritten, which rebuilds every cons from the outer let
		// down
		// to the mapcar -- the malformed let among them. Without the inherit, that let is
		// a cons the position table has never seen and the failure loses its own line.
		Path file = this.tempDir.resolve("bad.lisp");
		Files.writeString(file, """
				(defun dbl (x) (* x 2))
				(defun g (xs)
				  (let ((f #'dbl))
				    (let ((a 1) 2)
				      (mapcar f xs))))
				(print (g '(1 2)))
				""");
		for (String output : new String[] { "Bad.class", "bad.wasm" }) {
			assertThatThrownBy(() -> runCli("", file.toString(), "-o", this.tempDir.resolve(output).toString()))
				.isInstanceOf(LispCompileException.class)
				.hasMessageContaining("bad.lisp:4:5:");
		}
	}

	@Test
	void aMalformedTopLevelCheckTypeNamesItsOwnLine() throws Exception {
		// A top-level check-type/assert is expanded by the free-variable walk, before any
		// pass with a position hook has looked at it, so its complaint used to arrive
		// with
		// no position at all.
		Path file = this.tempDir.resolve("ct.lisp");
		Files.writeString(file, """
				(print 1)
				(check-type 1)
				""");
		for (String output : new String[] { "Ct.class", "ct.wasm" }) {
			assertThatThrownBy(() -> runCli("", file.toString(), "-o", this.tempDir.resolve(output).toString()))
				.isInstanceOf(LispCompileException.class)
				.hasMessageContaining("ct.lisp:2:1: check-type expects a place");
		}
	}

	@Test
	void anUndefinedFunctionWarnsExactlyOncePerCallSite() throws Exception {
		// The JVM backend re-runs the whole compile when a runtime-helper gate was
		// under-predicted, and the discarded attempt used to have printed its warnings
		// already -- two identical lines for one call site. (An undefined function pulls
		// in the eval group, so this program takes the retry.)
		Path file = this.tempDir.resolve("warn.lisp");
		Files.writeString(file, """
				(defun broken (y)
				  (no-such-function y))
				(print (broken 1))
				""");
		PrintStream savedErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
			runCli("", file.toString(), "-o", this.tempDir.resolve("Warn.class").toString());
		}
		finally {
			System.setErr(savedErr);
		}
		assertThat(captured.toString(StandardCharsets.UTF_8)
			.lines()
			.filter(line -> line.contains("the function NO-SUCH-FUNCTION is undefined"))
			.count()).isEqualTo(1);
	}

	@Test
	void theSourcePositionLiteralsNameTheLoadedFileNotTheEntryFile() throws Exception {
		// A file pulled in by load names ITSELF -- the reason these
		// exist at all is a program assembled from many files -- and the interpreter and
		// the compile path must agree, which they do by construction (one reader).
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defun where ()
				  (list rontolisp:current-file rontolisp:current-line))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, """
				(load "lib.lisp")
				(print (where))
				(print (list rontolisp:current-file rontolisp:current-line))
				""");
		// The file is spelled exactly as the frontend saw it (the path load resolved
		// here), which is also how the reader's own error prefixes spell it.
		assertThat(runCli("", main.toString()).strip().lines()).satisfiesExactly(
				first -> assertThat(first).endsWith("lib.lisp\" 2)"),
				second -> assertThat(second).endsWith("main.lisp\" 3)"));
		// The compile path splices lib.lisp into main.lisp but reads it under its OWN
		// name, so both literals are baked into the class. (That the backends agree on
		// the VALUES is pinned end-to-end by ci-spec.yaml's source-position-literals
		// case; here the point is that the load splice does not relabel them.)
		Path classFile = this.tempDir.resolve("Main.class");
		runCli("", main.toString(), "-o", classFile.toString());
		String constants = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
		assertThat(constants).contains(this.tempDir.resolve("lib.lisp").toString())
			.contains(this.tempDir.resolve("main.lisp").toString());
	}

	@Test
	void theRecordingScopeIsClosedEvenWhenTheCompileFails() throws Exception {
		// The table holds the whole program's conses alive; a scope left open on a failed
		// compile would keep them for the life of the thread.
		Path file = this.tempDir.resolve("bad.lisp");
		Files.writeString(file, "(defun g (x) (let ((a 1) 2) a))\n");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", this.tempDir.resolve("Bad.class").toString()))
			.isInstanceOf(LispCompileException.class);
		assertThat(SourceProvenance.isRecording()).isFalse();
	}

	@Test
	void theInterpreterKeepsItsBareErrorText() throws Exception {
		// The deliberate divergence: the interpreter reaches the same expander at
		// EVALUATION time, so a position prefix there would land on ordinary runtime
		// error text (which ci-spec.yaml and the doc examples pin byte for byte). It
		// records nothing, and its message stays exactly as it was.
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defmacro twice (x)
				  (error "twice: bad argument ~a" x))

				(defun f (n)
				  (twice n))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, "(load \"lib.lisp\")\n(print (f 1))\n");
		assertThatThrownBy(() -> runCli("", main.toString())).hasMessage("twice: bad argument N");
		assertThat(SourceProvenance.isRecording()).isFalse();
	}

	@Test
	void distSpecsReadTheOptionThenTheEnvironment() {
		// Comma-separated (a distinfo URL contains the path separator --system-path
		// joins on), newline-separated where a repeated --dist accumulated, and
		// deduplicated while keeping the order the search will follow.
		assertThat(RontoLispCli.distSpecs("ultralisp", null)).containsExactly("ultralisp");
		assertThat(RontoLispCli.distSpecs("ultralisp,http://dist.example.org/x.txt", null)).containsExactly("ultralisp",
				"http://dist.example.org/x.txt");
		assertThat(RontoLispCli.distSpecs("ultralisp\nquicklisp", null)).containsExactly("ultralisp", "quicklisp");
		assertThat(RontoLispCli.distSpecs(" ultralisp , ", "ultralisp,other")).containsExactly("ultralisp", "other");
		assertThat(RontoLispCli.distSpecs(null, null)).isEmpty();
	}

}
