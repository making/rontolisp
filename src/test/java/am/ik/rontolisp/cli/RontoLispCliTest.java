package am.ik.rontolisp.cli;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.HostGlueEmitter;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.SplitPrograms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CLI end to end, in process. The methods run CONCURRENTLY: each owns its
 * {@code @TempDir} and its streams. The ones that take over a process-wide stream --
 * {@code System.err} to read what the CLI reported there, or {@code System.out} -- are
 * {@link RontoLispCliStreamsTest}, a class of its own and so never beside these.
 */
@Execution(ExecutionMode.CONCURRENT)
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
	void anAbsoluteOutputPathEmitsALoadableClass() throws Exception {
		// -o /abs/dir/T2.class cannot mean a package (the leading separator opens the
		// name with an empty segment, which the JVM refuses at load time), so the
		// directory is just a directory and the class is T2 -- what `java -cp dir T2`
		// runs. It used to compile successfully into a class nothing could load.
		Path program = this.tempDir.resolve("prog.lisp");
		Files.writeString(program, "(print (+ 1 2))\n");
		Path output = this.tempDir.resolve("out/T2.class");
		runCli("", program.toString(), "-o", output.toString());
		assertThat(output).exists();
		assertThat(loadClassName(this.tempDir.resolve("out"), "T2")).isEqualTo("T2");
	}

	@Test
	void anAbsoluteOutputPathTakesItsPackageFromClassName() throws Exception {
		// The escape hatch the refusal message names: an absolute path that ends in the
		// package path still emits a packaged class, because --class-name says the
		// package the path cannot. (JvmArtifactOptions.classRoot then roots the output
		// at the package root, so the class loads from there.)
		Path program = this.tempDir.resolve("prog.lisp");
		Files.writeString(program, "(print (+ 1 2))\n");
		Path output = this.tempDir.resolve("classes/com/acme/Kernels.class");
		runCli("", program.toString(), "-o", output.toString(), "--class-name", "com.acme.Kernels");
		assertThat(loadClassName(this.tempDir.resolve("classes"), "com.acme.Kernels")).isEqualTo("com.acme.Kernels");
	}

	/**
	 * Loads the emitted class file by the name it is expected to carry, which is the
	 * check a compiled {@code .class} exists to pass: a name the JVM refuses fails here
	 * with {@code ClassFormatError} exactly as {@code java -cp root Name} would.
	 * @param root the class path root the emitted class file sits under
	 * @param binaryName the name the class is expected to answer to
	 * @return the loaded class's name
	 */
	private String loadClassName(Path root, String binaryName) throws Exception {
		try (URLClassLoader loader = new URLClassLoader(new URL[] { root.toUri().toURL() },
				ClassLoader.getPlatformClassLoader())) {
			return Class.forName(binaryName, false, loader).getName();
		}
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

	/**
	 * Runs the jar the way its user will -- {@code java -jar}, so the manifest is what
	 * finds the entry point -- and answers its output.
	 */
	private static String runJar(Path jar) throws Exception {
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
				jar.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).describedAs("java -jar %s said:%n%s", jar, output).isZero();
		return output;
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
		// in the consumer rather than an error at compile time. The complex holder
		// travels for the same reason: norm2 roots through sqrt, which can answer a
		// complex for a negative input (.kb/jvm-complex.md).
		assertThat(entries.keySet()).containsExactly("META-INF/MANIFEST.MF",
				"META-INF/maven/com.acme/acme-kernels/pom.xml", "META-INF/maven/com.acme/acme-kernels/pom.properties",
				"com/acme/Kernels.class", "am/ik/rontolisp/runtime/RontoBoundary.class",
				"am/ik/rontolisp/runtime/RontoComplex.class", "am/ik/rontolisp/runtime/RontoFloatArray$Width.class",
				"am/ik/rontolisp/runtime/RontoFloatArray.class");
		String manifest = new String(entries.get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8);
		// A library is not a program: nobody should java -jar it, so it carries no
		// Main-Class.
		assertThat(manifest).contains("Manifest-Version: 1.0")
			.contains("Created-By: rontolisp ")
			.doesNotContain("Main-Class");
		// Every jar enables native access for its unnamed module, like rontolisp's own
		// exec jar: an objc:/--blas/--gpu program then runs under java -jar without
		// the JDK's restricted-method warning, and the header is inert otherwise.
		assertThat(manifest).contains("Enable-Native-Access: ALL-UNNAMED");
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

	// A program too large for one class file is its class plus $PartN classes, and both
	// output shapes carry them: beside a .class (in its package directory), inside a jar
	// that `java -jar` still runs (.kb/jvm-method-size-limits.md).
	@Test
	void aProgramPastOneClassesPoolTravelsWithItsPartsInEveryOutputShape() throws Exception {
		Path program = this.tempDir.resolve("big.lisp");
		Files.writeString(program, SplitPrograms.pastOneClass());
		Path classes = this.tempDir.resolve("classes");
		runCli("", program.toString(), "-o", classes.resolve("com/acme/Big.class").toString(), "--class-name",
				"com.acme.Big");
		assertThat(classes.resolve("com/acme/Big$Part1.class")).exists();
		assertThat(loadClassName(classes, "com.acme.Big$Part1")).isEqualTo("com.acme.Big$Part1");

		Path jar = this.tempDir.resolve("big.jar");
		runCli("", program.toString(), "-o", jar.toString());
		assertThat(entries(jar)).containsKeys("Big.class", "Big$Part1.class");
		assertThat(runJar(jar).strip()).isEqualTo(SplitPrograms.pastOneClassOutput());
	}

	@Test
	void aProgramJarNeedsNoClassNameAndIsExecutable() throws Exception {
		// -o app.jar on its own has to produce something `java -jar` runs: the class a
		// program jar holds is an implementation detail behind the manifest, so there is
		// nothing for the user to name.
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, "(print (+ 1 2))\n");
		Path jar = this.tempDir.resolve("app.jar");
		runCli("", program.toString(), "-o", jar.toString());
		assertThat(entries(jar)).containsKey("App.class");
		assertThat(new String(entries(jar).get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8))
			.contains("Main-Class: App");
		assertThat(runJar(jar)).isEqualTo("3\n");
	}

	@Test
	void aServedProgramJarDrainsInFlightRequestsWhenTheProcessIsTerminated() throws Exception {
		// The deployment shape the graceful shutdown exists for: `java -jar app.jar`
		// serving, then SIGTERM from whatever is rolling it. The request the handler is
		// already inside has to come back whole -- before the drain the JVM halted where
		// it stood and the client read a connection reset instead of a response.
		int port = freePort();
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, """
				(defun handle (env)
				  (cond ((string= (getf env :path-info) "/slow")
				         (format t "slow-begin~%%")
				         (finish-output)
				         (sleep 1)
				         (list 200 '(:content-type "text/plain") (list "slow-done")))
				        (t (list 200 '(:content-type "text/plain") (list "ready")))))

				(rontolisp:http-handler 'handle %d)
				""".formatted(port));
		Path jar = this.tempDir.resolve("app.jar");
		runCli("", program.toString(), "-o", jar.toString());
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
				jar.toString())
			.redirectErrorStream(true)
			.start();
		StringBuilder output = new StringBuilder();
		Thread reader = Thread.ofVirtual().start(() -> readInto(process, output));
		try {
			awaitReady(port, process, output);
			HttpClient client = HttpClient.newHttpClient();
			CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
					HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/slow")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			// The signal must land while the handler is INSIDE the request; the handler
			// says so on stdout before it starts sleeping.
			assertThat(awaitOutput(output, "slow-begin", Duration.ofSeconds(20)))
				.describedAs("the served jar said:%n%s", output)
				.isTrue();
			process.destroy(); // SIGTERM
			HttpResponse<String> response = inFlight.get(30, TimeUnit.SECONDS);
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).isEqualTo("slow-done");
			assertThat(process.waitFor(30, TimeUnit.SECONDS)).describedAs("the terminated jar never exited").isTrue();
		}
		finally {
			process.destroyForcibly();
			reader.join();
		}
	}

	// A free TCP port (bound then released; a tiny race, acceptable for a test).
	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	// Drains the spawned jar's merged output into the buffer the test polls.
	private static void readInto(Process process, StringBuilder output) {
		try (BufferedReader lines = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line = lines.readLine(); line != null; line = lines.readLine()) {
				synchronized (output) {
					output.append(line).append('\n');
				}
			}
		}
		catch (IOException closed) {
			// The process went away mid-read; whatever it said is already in the buffer.
		}
	}

	private static boolean awaitOutput(StringBuilder output, String marker, Duration wait) throws Exception {
		long deadline = System.nanoTime() + wait.toNanos();
		while (System.nanoTime() < deadline) {
			synchronized (output) {
				if (output.indexOf(marker) >= 0) {
					return true;
				}
			}
			Thread.sleep(20);
		}
		return false;
	}

	// Polls the spawned server until it answers, so the test never races its start-up.
	private static void awaitReady(int port, Process process, StringBuilder output) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest ping = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready"))
			.timeout(Duration.ofSeconds(2))
			.GET()
			.build();
		long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
		while (System.nanoTime() < deadline) {
			assertThat(process.isAlive()).describedAs("the served jar died before serving:%n%s", output).isTrue();
			try {
				if (client.send(ping, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
					return;
				}
			}
			catch (IOException notYet) {
				Thread.sleep(50);
			}
		}
		throw new AssertionError("the served jar never came up on port " + port + ":%n" + output);
	}

	@Test
	void aProgramJarsClassNameIsItsFileNameInCamelCase() throws Exception {
		// The stem is a FILE name and may hold anything a file name may: a '.' read as a
		// package separator would leave the manifest naming a class the jar does not
		// have.
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, "(print 1)\n");
		Path jar = this.tempDir.resolve("build/my-app-1.0.0.jar");
		runCli("", program.toString(), "-o", jar.toString());
		assertThat(entries(jar)).containsKey("MyApp100.class");
		assertThat(new String(entries(jar).get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8))
			.contains("Main-Class: MyApp100");
		// A stem that starts with a digit is still not a class name after capitalizing.
		Path digits = this.tempDir.resolve("2048.jar");
		runCli("", program.toString(), "-o", digits.toString());
		assertThat(entries(digits)).containsKey("_2048.class");
	}

	@Test
	void aLibraryJarStillNeedsAClassNameBecauseItsClassIsItsApi() throws Exception {
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
	void nativeRefusesWhatAWasiCommandModuleCannotBeAndAnOutputNamedForAnotherBackend() throws Exception {
		Path program = this.tempDir.resolve("hello.lisp");
		Files.writeString(program, "(print 1)\n");
		String output = this.tempDir.resolve("hello").toString();
		for (List<String> flags : List.of(List.of("--component"), List.of("--no-wasi"), List.of("--no-gc"),
				List.of("--host-random"), List.of("--host-fetch"), List.of("--host-boundary=envelope"),
				List.of("--reentrant"), List.of("--emit-js-glue"))) {
			List<String> args = new ArrayList<>(List.of(program.toString(), "--native", "-o", output));
			args.addAll(flags);
			assertThatThrownBy(() -> runCli("", args.toArray(String[]::new)))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageStartingWith("--native cannot be combined with " + flags.getFirst().split("=")[0] + ": ");
		}
		for (String extension : List.of(".wasm", ".class", ".jar", ".war")) {
			assertThatThrownBy(() -> runCli("", program.toString(), "--native", "-o", output + extension))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining("names a " + extension + " output");
		}
		assertThatThrownBy(() -> runCli("", program.toString(), "--native"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--native writes a native executable, so it needs -o <file>");
		// Refused before anything is written.
		assertThat(this.tempDir).isDirectoryNotContaining(
				path -> path.getFileName().toString().startsWith("hello") && !path.equals(program));
	}

	@Test
	void nativeTargetAndCpuDescribeANativeOutputAndNameAKnownPlatform() throws Exception {
		Path program = this.tempDir.resolve("hello.lisp");
		Files.writeString(program, "(print 1)\n");
		String output = this.tempDir.resolve("hello").toString();
		for (String flag : List.of("--native-target=linux-aarch64", "--native-cpu=host")) {
			assertThatThrownBy(() -> runCli("", program.toString(), flag, "-o", output + ".wasm"))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage(flag.split("=")[0] + " describes a native executable, so it needs --native -o <file>");
			assertThatThrownBy(() -> runCli("", program.toString(), flag))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining("needs --native -o <file>");
		}
		assertThatThrownBy(
				() -> runCli("", program.toString(), "--native", "--native-target", "windows-x86_64", "-o", output))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("--native-target windows-x86_64: expected one of linux-x86_64, linux-aarch64, macos-aarch64,"
					+ " macos-x86_64");
		assertThatThrownBy(() -> runCli("", program.toString(), "--native", "--native-cpu=", "-o", output))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageStartingWith("--native-cpu needs a value");
		assertThat(this.tempDir).isDirectoryNotContaining(
				path -> path.getFileName().toString().startsWith("hello") && !path.equals(program));
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

	/** The serving program the war tests below compile. */
	private Path servedProgram() throws Exception {
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, """
				(defun handle (env)
				  (list 200 '(:content-type "text/plain") (list (getf env :path-info))))
				(rontolisp:http-handler 'handle)
				""");
		return program;
	}

	@Test
	void aWarCarriesTheProgramTheRuntimeAndTheOneLineServiceDeclaration() throws Exception {
		Path war = this.tempDir.resolve("app.war");
		runCli("", servedProgram().toString(), "-o", war.toString());
		Map<String, byte[]> entries = entries(war);
		// The class name is derived from the war's stem exactly as a program jar's is.
		assertThat(entries).containsKey("WEB-INF/classes/App.class");
		// The travelling closure plus the servlet transport, all under WEB-INF/classes.
		assertThat(entries).containsKey("WEB-INF/classes/am/ik/rontolisp/runtime/RontoHttpServer.class")
			.containsKey("WEB-INF/classes/am/ik/rontolisp/runtime/RontoHttpServlet.class")
			.containsKey("WEB-INF/classes/am/ik/rontolisp/runtime/RontoHttpServletInitializer.class");
		// The one non-class file: the same line in every war rontolisp ever emits. No
		// web.xml, and nothing naming the program class.
		assertThat(
				new String(entries.get("WEB-INF/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer"),
						StandardCharsets.UTF_8))
			.isEqualTo("am.ik.rontolisp.runtime.RontoHttpServletInitializer\n");
		assertThat(entries).doesNotContainKey("WEB-INF/web.xml");
		// No Main-Class: a war has no entry point. Enable-Native-Access stays (a
		// --blas/--gpu war still wants it; inert otherwise).
		String manifest = new String(entries.get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8);
		assertThat(manifest).doesNotContain("Main-Class").contains("Enable-Native-Access: ALL-UNNAMED");
	}

	@Test
	void twoCompilesOfOneProgramProduceByteIdenticalWars() throws Exception {
		Path program = servedProgram();
		Path first = this.tempDir.resolve("a.war");
		Path second = this.tempDir.resolve("b.war");
		for (Path war : List.of(first, second)) {
			runCli("", program.toString(), "-o", war.toString(), "--class-name", "com.example.App");
		}
		assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
	}

	@Test
	void aWarWithNothingToServeIsRefusedAtCompileTime() throws Exception {
		// There would be nothing for the container to call.
		Path program = this.tempDir.resolve("plain.lisp");
		Files.writeString(program, "(print (+ 1 2))\n");
		assertThatThrownBy(() -> runCli("", program.toString(), "-o", this.tempDir.resolve("app.war").toString()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("nothing for the container to call");
	}

	@Test
	void noMainIsRefusedByNameOnAWar() throws Exception {
		assertThatThrownBy(() -> runCli("", servedProgram().toString(), "-o",
				this.tempDir.resolve("app.war").toString(), "--no-main"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--no-main")
			.hasMessageContaining(".war");
	}

	@Test
	void aWarIsAMavenArtifactSoTheCoordinatesRideInItsMetaInf() throws Exception {
		Path war = this.tempDir.resolve("app-1.0.0.war");
		runCli("", servedProgram().toString(), "-o", war.toString(), "--class-name", "com.example.App",
				"--maven-coordinates", "com.example:app:1.0.0", "--emit-pom");
		Map<String, byte[]> entries = entries(war);
		assertThat(entries).containsKey("META-INF/maven/com.example/app/pom.xml")
			.containsKey("META-INF/maven/com.example/app/pom.properties");
		assertThat(Files.readString(this.tempDir.resolve("app-1.0.0.pom"))).contains("<artifactId>app</artifactId>");
	}

	@Test
	void clackRontolispBackendOnAWarRegistersWithoutBindingOrJoining() throws Exception {
		// The one-clackup-source contract's war leg (.kb/clack.md), the twin of
		// clackRontolispBackendUnderNoWasiSynthesizesTheReactorExport above: read with
		// #+rontolisp-servlet, the clack-handler-rontolisp shim's run hands the app to
		// the %http-server-start seam and RETURNS -- the container owns the port and the
		// top level has to finish. What the compiled program must NOT contain is the
		// socket leg: no startServer, and no joinServer to block <clinit> forever.
		// quickloading the shim directly (a BUILT-IN system, no network) and calling its
		// run is the clackup shape minus the clack dist.
		Path file = this.tempDir.resolve("app.lisp");
		Files.writeString(file, """
				(ql:quickload "clack-handler-rontolisp")
				(defun app (env) (list 200 (list :content-type "text/plain") (list "ok")))
				(clack.handler.rontolisp:run #'app :port 8080)
				""");
		Path war = this.tempDir.resolve("app.war");
		runCli("", file.toString(), "-o", war.toString());
		String program = new String(entries(war).get("WEB-INF/classes/App.class"), StandardCharsets.ISO_8859_1);
		assertThat(program).doesNotContain("startServer").doesNotContain("joinServer").doesNotContain("stopServer");
		// And it still IS a served program: the slot the servlet dispatches through.
		assertThat(program).contains("_httpHandlerFn");
	}

	@Test
	void replEvaluatesExpression() {
		String output = runCli("(+ 1 2)\n");
		assertThat(output).contains("3");
	}

	@Test
	void replEchoesEveryValueOnItsOwnLine() {
		// As in any CL REPL: (floor 10 3) echoes the quotient AND the remainder.
		assertThat(runCli("(floor 10 3)\n")).isEqualTo("3\n1\n");
		assertThat(runCli("(values 1 2 3)\n")).isEqualTo("1\n2\n3\n");
		assertThat(runCli("(defun f () (values 1 2))\n(f)\n")).isEqualTo("F\n1\n2\n");
		// No values at all echoes nothing, and a single value stays a single line.
		assertThat(runCli("(values)\n")).isEmpty();
		assertThat(runCli("(+ 1 2)\n")).isEqualTo("3\n");
		// Two forms on one line echo twice, as SBCL does reading them one at a time,
		// and each form's own output precedes its own value.
		assertThat(runCli("(values 1 2) (+ 3 4)\n")).isEqualTo("1\n2\n7\n");
		assertThat(runCli("(print 'a) (print 'b)\n")).isEqualTo("A\nA\nB\nB\n");
	}

	@Test
	void aPipedReplWritesNoPromptForEitherLanguage() {
		// A pipe is a script runner: stdout is the values and the program's own output,
		// nothing else -- not a prompt per form, and not one per blank or comment line.
		assertThat(runCli("\n; a comment\n(* 5 5)\n\n")).isEqualTo("25\n");
		assertThat(runCli("\n; a comment\n(* 5 5)\n\n", "--source-language", "scheme")).isEqualTo("25\n");
	}

	@Test
	void theSchemeReplDefinesRedefinesAssignsAndCallsAcrossPrompts() throws Exception {
		// One form per prompt, so no lowering ever sees two of them together -- and the
		// answers are the ones the same text gives as a file.
		String program = """
				(define (f x) (* x 2))
				(f 2)
				(set! f car)
				(f '(1 2))
				(define g 1)
				(define (g) 5)
				(g)
				(define (h x) (+ x 1))
				(map h '(1 2))
				(define (ev? n) (if (= n 0) #t (od? (- n 1))))
				(define (od? n) (if (= n 0) #f (ev? (- n 1))))
				(ev? 10)
				(set! od? (lambda (n) 'assigned))
				(ev? 10)
				(define (count i) (if (= i 0) 'done (count (- i 1))))
				(count 1000000)
				""";
		String answers = "4\n1\n5\n(2 3)\n#t\nassigned\ndone\n";
		assertThat(runCli(program, "--source-language", "scheme")).isEqualTo(answers);
		StringBuilder echoes = new StringBuilder();
		for (String line : program.lines().toList()) {
			echoes.append(
					line.startsWith("(define") || line.startsWith("(set!") ? line : "(write " + line + ") (newline)")
				.append('\n');
		}
		Path file = this.tempDir.resolve("transcript.scm");
		Files.writeString(file, echoes.toString());
		assertThat(runCli("", file.toString())).isEqualTo(answers);
	}

	@Test
	void theSchemeReplEchoesThroughTheSchemePrinter() {
		assertThat(runCli("(list #t #f '() 'Sym \"s\" #\\a 1.5)\n", "--source-language", "scheme"))
			.isEqualTo("(#t #f () Sym \"s\" #\\a 1.5)\n");
		// One value per line; no value, a definition and an effect echo nothing, and an
		// effect's own output still ends its line.
		assertThat(runCli("(values 1 'a)\n(values)\n(display \"hi\")\n(newline)\n", "--source-language", "scheme"))
			.isEqualTo("1\na\nhi\n\n");
		assertThat(runCli("(define-record-type point (make-point x) point? (x point-x))\n(point? (make-point 1))\n"
				+ "(point-x (make-point 7))\n", "--source-language", "scheme"))
			.isEqualTo("#t\n7\n");
		// An argument's extra values are not the call's: each form echoes one value.
		assertThat(runCli("(+ 1 (values 5 6))\n(car (list (values 5 6)))\n(define (two) (values 1 2))\n(list (two))\n",
				"--source-language", "scheme"))
			.isEqualTo("6\n5\n(1)\n");
	}

	@Test
	void theSchemeReplRunsATopLevelBeginAsOneStep() {
		// A top-level begin must still splice (its definitions need to land at the top
		// level), but the splicing must answer as ONE step: SchemeLowering#interact used
		// to return one SchemeTopLevel per spliced form, so ReplBuffer's per-step
		// fresh-line put each on its own line ("3", "4", "5") instead of running the
		// effects together and echoing only the last value -- as the Common Lisp REPL
		// does for (progn (princ 1) (princ 2)): "12" then "2".
		assertThat(runCli("(begin (display 3) (display 4) 5)\n", "--source-language", "scheme")).isEqualTo("34\n5\n");
		// A macro whose template is a begin of effects splices the same way, from
		// expansion instead of a typed begin.
		assertThat(
				runCli("(define-syntax two-effects (syntax-rules () ((_ a b v) (begin (display a) (display b) v))))\n"
						+ "(two-effects 3 4 5)\n", "--source-language", "scheme"))
			.isEqualTo("34\n5\n");
	}

	@Test
	void theSchemeReplEchoesNothingForTheUnspecifiedValue() {
		// What an effect answers is ONE object, not the value its Common Lisp half
		// happened to return ("a" from display, () from a one-armed if) -- and the echo
		// skips it however the procedure producing it was written.
		String input = """
				(if #f #f)
				(define (g) (display "a"))
				(g)
				(define (print-rat x) (display x) (newline))
				(print-rat 1/2)
				(define v 0)
				(set! v 1)
				(for-each display '(1 2))
				(when #f 1)
				(cond (#f 1))
				(case 1 ((2) 3))
				(vector-set! (make-vector 1) 0 v)
				(list (if #f #f))
				(length (list (if #f #f)))
				(if (if #f #f) 'true 'false)
				""";
		assertThat(runCli(input, "--source-language", "scheme")).isEqualTo("a\n1/2\n12\n(#!unspecific)\n1\ntrue\n");
	}

	@Test
	void theSchemeReplContinuesAnIncompleteDatumByTheSchemeReadersRules() {
		// A Common Lisp paren count would stop early on #\( and never on a #| comment.
		String input = "(list #\\(\n 1)\n#| (\n |# 2\n#;(a\n b) 3\n\"a\n(\"\n";
		assertThat(runCli(input, "--source-language", "scheme")).isEqualTo("(#\\( 1)\n2\n3\n\"a\\n(\"\n");
	}

	@Test
	void replWithSimdInterceptsVecKernels() {
		// The installed Vector API kernel prints #<function VEC:DOT> -- and so does the
		// vec.lisp defun it replaces, since defuns carry names now. The REPL text can no
		// longer tell the pair apart; VecSimdTest's type guards (LispFunction vs
		// LispLambda) hold the interception line. The surefire JVM has
		// jdk.incubator.vector on the module path, so VecSimd.available() is true here.
		String output = runCli("(vec:dot #d(1.0) #d(1.0)) #'vec:dot\n", "--simd");
		assertThat(output).contains("#<function VEC:DOT>");
	}

	@Test
	void replPrintsTheLinalgProductByNameWhetherOrNotTheMachineHasATunedLibrary() {
		// Whether a tuned CBLAS exists is a property of the MACHINE, and the printed
		// value may not depend on it: the library kernel and the linalg.lisp defun it
		// replaces answer the same #<function LINALG:DOT> now that defuns carry names.
		// Which of the two is installed is pinned in-process by LinalgBlasTest's type
		// guards (LispFunction vs LispLambda), not by the REPL text.
		String output = runCli("(linalg:zeros 1) #'linalg:dot\n", "--blas");
		assertThat(output).contains("#<function LINALG:DOT>");
	}

	@Test
	void replPrintsTheLinalgProductByNameWhetherOrNotTheMachineHasADevice() {
		// The same machine-independence --gpu installs buys for the REPL text: with a
		// device the defun is replaced by the interceptor, without one the REPL says so
		// on stderr and keeps the defun, and both print #<function LINALG:DOT>. The
		// interception itself is pinned by LinalgGpuTest's type guards.
		String output = runCli("(linalg:zeros 1) #'linalg:dot\n", "--gpu");
		assertThat(output).contains("#<function LINALG:DOT>");
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

	/**
	 * The surefire JVM always has {@code jdk.incubator.vector} on its module path (the
	 * {@code --add-modules} argLine), so {@code runCli} above can never reproduce the
	 * module-absent interpreter path; only a FRESH child JVM launched without that flag
	 * can. Mirrors {@code JvmSimdModuleFallbackTest}'s subprocess technique, one layer up
	 * (the interpreter's own process instead of a class it defines in-process).
	 */
	private static Process runWithoutTheIncubatorModule(String... args) throws Exception {
		String java = ProcessHandle.current().info().command().orElse("java");
		List<String> command = new java.util.ArrayList<>(
				List.of(java, "-cp", System.getProperty("java.class.path"), "am.ik.rontolisp.cli.RontoLispCli"));
		command.addAll(List.of(args));
		return new ProcessBuilder(command).start();
	}

	@Test
	void interpreterSimdParallelIsAHardErrorWithoutTheIncubatorModule() throws Exception {
		// .todo/700: on a JVM without jdk.incubator.vector, --simd --parallel used to
		// degrade to the SCALAR vec:/linalg: kernels split across threads -- a ~100x
		// slowdown that reads as a hang under a long program's own output, with only a
		// one-line warning (easily scrolled off) as the tell. --parallel is asked for
		// only by someone about to run something large, and unlike a compiled .class
		// (which may run on a different machine later, so JvmSimdModuleFallbackTest
		// keeps it degrading there), the interpreter knows RIGHT NOW whether the module
		// is there -- so it refuses instead.
		Path program = this.tempDir.resolve("prog.lisp");
		Files.writeString(program, "(print (vec:matvec #d((1 2) (3 4)) #d(5 6)))\n");
		Process process = runWithoutTheIncubatorModule(program.toString(), "--simd", "--parallel");
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as("stdout:%n%s%nstderr:%n%s", out, err).isEqualTo(1);
		assertThat(out).isEmpty();
		assertThat(err).contains("error:")
			.contains("--parallel")
			.contains("jdk.incubator.vector")
			.contains("--add-modules jdk.incubator.vector");
	}

	@Test
	void interpreterSimdAloneStillDegradesWithoutTheIncubatorModule() throws Exception {
		// The counterpart to the hard error above: plain --simd (no --parallel) keeps
		// degrading to the scalar kernels with a warning -- the same shape --blas/--gpu
		// give with no library/device, and the same the compiled .class output gives
		// (JvmSimdModuleFallbackTest). Only --parallel raises the stakes enough to
		// refuse outright.
		Path program = this.tempDir.resolve("prog.lisp");
		Files.writeString(program, "(print (vec:matvec #d((1 2) (3 4)) #d(5 6)))\n");
		Process process = runWithoutTheIncubatorModule(program.toString(), "--simd");
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as("stdout:%n%s%nstderr:%n%s", out, err).isZero();
		assertThat(out.trim()).isEqualTo("#d(17.0 39.0)");
		assertThat(err).contains("rontolisp: warning: --simd:").contains("jdk.incubator.vector");
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
		// The bridge's own name is an ordinary class constant and the PTX kernels are
		// there verbatim; the bridge and the CUDA binding it calls are class files of
		// their own beside the class.
		assertThat(Files.readString(classFile, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("P$GpuBridge")
			.contains(".visible .entry gemm_f64");
		assertThat(tempDir.resolve("P$GpuBridge.class")).exists();
		assertThat(tempDir.resolve("P$GpuGpu.class")).exists();
	}

	@Test
	void replPrintsVecDotByNameWithoutSimdToo() {
		// Without --simd the defun stays scalar -- and still prints #<function VEC:DOT>,
		// the same text the lane kernel answers with. The flag may not move the printed
		// name; whether the kernel is installed is pinned by VecSimdTest's type guards.
		String output = runCli("(vec:dot #d(1.0) #d(1.0)) #'vec:dot\n");
		assertThat(output).contains("#<function VEC:DOT>");
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
	void noGcComponentWithHostImportsWritesTheirWit() throws Exception {
		// --no-gc --component takes rontolisp:wasm-import: the reached host functions
		// become the component's imports, and --emit-wit describes them beside the
		// exports (a plain :from label prints as an inline interface).
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file,
				"""
						(rontolisp:wasm-import 'host-log :from "env" :as "host-log" :params '(:string) :param-names '(line) :returns :void)
						(defun run (n) (host-log "hello") n)
						(rontolisp:wasm-export 'run :params '(:int) :returns :int)
						""");
		Path wasmFile = tempDir.resolve("test.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString(), "--no-gc", "--component", "--emit-wit");
		assertThat(Files.exists(wasmFile)).isTrue();
		String wit = Files.readString(tempDir.resolve("test.wit"));
		assertThat(wit).contains("  import env: interface {\n    host-log: func(line: string);\n  }\n")
			.contains("  export run: func(p0: s32) -> s32;");
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
				(defun handle (json)
				  (if (body (make-array 0 :element-type '(unsigned-byte 8))) json json))
				(rontolisp:wasm-export 'handle :params '(:string) :returns :string)
				""");
		// The buffer is a real byte vector: handing the :bytes import nil would be a type
		// error the type-test fold proves, and the import -- unreachable past the trap --
		// would be shaken out of the module the assertion below reads.
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
	void sourceLanguageOverrideNamesTheEntryLanguage() throws Exception {
		// --source-language overrides the pick from the entry file's extension; with
		// the one language this build reads the program runs exactly as without it.
		Path program = this.tempDir.resolve("hello.lisp");
		Files.writeString(program, "(print (+ 1 2))\n");
		assertThat(runCli("", program.toString(), "--source-language=common-lisp")).contains("3");
	}

	@Test
	void aSchemeFileIsPickedByItsExtension() throws Exception {
		Path program = this.tempDir.resolve("hello.scm");
		Files.writeString(program, """
				(import (scheme base) (scheme write))
				(define (greet Name) (string-append "hello, " Name))
				(display (greet "scheme")) (newline)
				(write (list #t #f '() 'Sym)) (newline)
				""");
		assertThat(runCli("", program.toString())).isEqualTo("hello, scheme\n(#t #f () Sym)\n");
	}

	@Test
	void sourceLanguageSchemeReadsAnyExtensionAsScheme() throws Exception {
		Path program = this.tempDir.resolve("hello.txt");
		Files.writeString(program, "(display (if '() 'empty-list-is-true 'no)) (newline)\n");
		assertThat(runCli("", program.toString(), "--source-language=scheme")).isEqualTo("empty-list-is-true\n");
	}

	@Test
	void aCommonLispProgramLoadsASchemeFile() throws Exception {
		// The language is picked per FILE: the loaded .scm is Scheme, and its top-level
		// procedure is an ordinary function the Common Lisp side calls by its
		// case-sensitive name.
		Files.writeString(this.tempDir.resolve("lib.scm"), "(define (twice x) (* 2 x))\n");
		Path program = this.tempDir.resolve("main.lisp");
		Files.writeString(program, "(load \"lib.scm\")\n(print (|twice| 21))\n");
		assertThat(runCli("", program.toString()).trim()).isEqualTo("42");
		Path compiled = this.tempDir.resolve("MixedLanguages.class");
		runCli("", program.toString(), "-o", compiled.toString());
		assertThat(Files.size(compiled)).isPositive();
	}

	@Test
	void aSchemeProgramReadsItsLibraryFilesAndIncludesBesideItOnEveryPath() throws Exception {
		Files.createDirectories(this.tempDir.resolve("lib"));
		Files.writeString(this.tempDir.resolve("lib/m.sld"), """
				(define-library (lib m) (export twice) (import (scheme base) (scheme write))
				  (include "m-body.scm"))
				""");
		Files.writeString(this.tempDir.resolve("lib/m-body.scm"),
				"(define (twice x) (* 2 x))\n(display \"m\") (newline)\n");
		Files.writeString(this.tempDir.resolve("a.scm"), "(import (scheme write) (lib m)) (display (twice 1))\n");
		Files.writeString(this.tempDir.resolve("b.scm"), "(import (scheme write) (lib m)) (display (twice 2))\n");
		Path program = this.tempDir.resolve("main.scm");
		Files.writeString(this.tempDir.resolve("main-body.scm"), "(display (twice 1))\n");
		Files.writeString(program, "(import (scheme base) (scheme write) (lib m))\n(include \"main-body.scm\")\n");
		assertThat(runCli("", program.toString())).isEqualTo("m\n2");
		Path compiled = this.tempDir.resolve("Libraries.class");
		runCli("", program.toString(), "-o", compiled.toString());
		assertThat(Files.size(compiled)).isPositive();
		// Two separately lowered files importing one library: it runs once.
		Path mixed = this.tempDir.resolve("mixed.lisp");
		Files.writeString(mixed, "(load \"a.scm\")\n(load \"b.scm\")\n");
		assertThat(runCli("", mixed.toString())).isEqualTo("m\n24");
		// A session reads what it names relative to the working directory.
		Files.writeString(this.tempDir.resolve("lib/pure.scm"), "(define (thrice x) (* 3 x))\n");
		assertThat(runCli("(include \"" + this.tempDir.resolve("lib/pure.scm") + "\")\n(thrice 21)\n",
				"--source-language", "scheme"))
			.isEqualTo("63\n");
	}

	@Test
	void theHelpSaysSchemeIsExperimental() {
		assertThat(runCli("", "-h")).contains("scheme (.scm) is EXPERIMENTAL");
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
	void aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice() throws Exception {
		// The cons-identity rule, end to end: a program that pulls in a spliced library
		// runs the whole rewrite chain over EVERY form, and a pass that rebuilt an
		// untouched form used to erase the position of everything below the top level --
		// which is exactly the multi-library program the feature exists for. One case per
		// pass that used to rebuild unconditionally: the JSON call-site rewrite, the Gray
		// binding-form walk, the component sockets/async rewrite (which also runs
		// ShadowedBuiltins with a non-empty alias map, the arity bundler and the
		// cross-lambda lowering over the spliced library), and the unread-char pushback
		// rewrite -- both naming unread-char directly and through read, the prelude Lisp
		// spliced over it (LispPreludeLibrary), so the whole program (this malformed form
		// included) goes through UnreadCharLibrary's rewrite.
		record Case(String trigger, String output, String[] flags) {
		}
		Case[] cases = { new Case("(print (rontolisp:json-parse \"{}\"))", "Json.class", new String[0]),
				new Case("(defclass s (rontolisp:fundamental-character-output-stream) ())", "Gray.class",
						new String[0]),
				new Case("(print (rontolisp:tcp-connect \"localhost\" 80))", "tcp.wasm",
						new String[] { "--component" }),
				new Case("(print (rontolisp:fetch \"http://example.com/\"))", "fetch.wasm",
						new String[] { "--component" }),
				new Case("(print (unread-char #\\a))", "UnreadChar.class", new String[0]),
				new Case("(print (read))", "read.wasm", new String[0]) };
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
		// The interpreter reaches the same expander at EVALUATION time, so a position
		// prefix there would land on runtime error text a program can read. It opens no
		// recording scope and its message stays exactly as it was; where it happened
		// goes UNDER the uncaught report instead (RontoLispCliStreamsTest#anUncaught*).
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
	void declaredFeaturesAreParsedLikeDistsAndRefuseTheBuildsOwnNames() {
		// Comma-separated, newline-separated where a repeated --feature accumulated,
		// spelled with or without the keyword colon, downcased and deduplicated.
		assertThat(RontoLispCli.declaredFeatures("sbcl")).containsExactly("sbcl");
		assertThat(RontoLispCli.declaredFeatures(":SBCL,#:x86-64")).containsExactly("sbcl", "x86-64");
		assertThat(RontoLispCli.declaredFeatures("sbcl\nclisp")).containsExactly("sbcl", "clisp");
		assertThat(RontoLispCli.declaredFeatures(" sbcl , sbcl ")).containsExactly("sbcl");
		assertThat(RontoLispCli.declaredFeatures(null)).isEmpty();
		// The names that describe the BUILD are refused: -o and the flags beside it are
		// what decide the backend and the boundary, and a source that read
		// #+rontolisp-wasm because the command line said so and then compiled to a class
		// would be broken by its own conditional.
		assertThatThrownBy(() -> RontoLispCli.declaredFeatures("rontolisp-wasm"))
			.hasMessageContaining("--feature cannot declare 'rontolisp-wasm'");
		assertThatThrownBy(() -> RontoLispCli.declaredFeatures(":rontolisp"))
			.hasMessageContaining("--feature cannot declare 'rontolisp'");
	}

	@Test
	void aDeclaredFeatureReachesTheReadTheLoadedFileAndTheRuntimeFeaturesList() throws Exception {
		// What the flag is FOR: a portable library whose #+ chain predates rontolisp and
		// so names none of our features, leaving every call in its #-(or ...) else
		// branch. The claim reaches the entry file, the files it loads -- and the
		// run-time *features*, so a (member :sbcl *features*) and the #+sbcl beside it
		// cannot disagree.
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defun greet ()
				  #+sbcl "sbcl-branch"
				  #-sbcl (error "not implemented"))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, """
				(load "lib.lisp")
				(princ (greet))
				(terpri)
				(princ (if (member :sbcl *features*) "declared" "absent"))
				(terpri)
				""");
		assertThat(runCli("", main.toString(), "--feature", "sbcl")).isEqualTo("sbcl-branch\ndeclared\n");
		// Without it the library falls into its else branch, which is the whole
		// complaint -- and *features* says so too.
		assertThatThrownBy(() -> runCli("", main.toString())).hasMessage("not implemented");
	}

	@Test
	void aDeclaredFeatureReachesTheCompiledBackendsToo() throws Exception {
		// The read is the frontend's, which every backend shares, so the flag selects
		// the same branch in a compiled artifact -- and the program's *features* is
		// seeded from the set it was READ with, target features and declared ones alike.
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, """
				(princ #+sbcl "sbcl-branch" #-sbcl "portable-branch")
				(terpri)
				(princ (if (member :sbcl *features*) "declared" "absent"))
				(terpri)
				""");
		Path jar = this.tempDir.resolve("app.jar");
		runCli("", program.toString(), "-o", jar.toString(), "--feature", "sbcl");
		assertThat(runJar(jar)).isEqualTo("sbcl-branch\ndeclared\n");
		// And on the WASM backend, which no test here runs: the branch it kept is the
		// one whose string constant travels.
		Path wasm = this.tempDir.resolve("app.wasm");
		runCli("", program.toString(), "-o", wasm.toString(), "--feature", "sbcl");
		String bytes = new String(Files.readAllBytes(wasm), StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains("sbcl-branch").doesNotContain("portable-branch");
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

	@Test
	void theStackOptionIsReadOffTheRawArgumentsAndConsumed() {
		// No parser downstream knows --stack exists, so it is removed from the arguments
		// the CLI goes on to read -- in either spelling.
		RontoLispCli.LaunchStack stack = RontoLispCli.LaunchStack.of(new String[] { "--stack", "64", "prog.lisp" });
		assertThat(stack.bytes()).isEqualTo(64L << 20);
		assertThat(stack.args()).containsExactly("prog.lisp");
		stack = RontoLispCli.LaunchStack.of(new String[] { "--stack=8", "prog.lisp" });
		assertThat(stack.bytes()).isEqualTo(8L << 20);
		assertThat(stack.args()).containsExactly("prog.lisp");
		// The default is the one ceiling every platform gets.
		stack = RontoLispCli.LaunchStack.of(new String[] { "prog.lisp" });
		assertThat(stack.bytes()).isEqualTo(16L << 20);
		assertThat(stack.args()).containsExactly("prog.lisp");
		// The in-process test legs run on the same ceiling.
		assertThat(CliStack.BYTES).isEqualTo(stack.bytes());
		// Everything after the bare -- is the interpreted program's own argument vector,
		// including a word that would otherwise be this option.
		stack = RontoLispCli.LaunchStack.of(new String[] { "prog.lisp", "--", "--stack", "9" });
		assertThat(stack.bytes()).isEqualTo(16L << 20);
		assertThat(stack.args()).containsExactly("prog.lisp", "--", "--stack", "9");
	}

	@Test
	void theStackOptionRefusesASizeNoThreadCanBeGiven() {
		assertThatThrownBy(() -> RontoLispCli.LaunchStack.of(new String[] { "--stack", "huge", "prog.lisp" }))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--stack expects a stack size in MiB");
		assertThatThrownBy(() -> RontoLispCli.LaunchStack.of(new String[] { "--stack=0", "prog.lisp" }))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RontoLispCli.LaunchStack.of(new String[] { "--stack=1048576", "prog.lisp" }))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
