package am.ik.rontolisp.codegen.jvm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code --simd} compiled class must DEGRADE, not hard-fail, on a JVM without
 * {@code jdk.incubator.vector} in its module graph -- the asymmetry the interpreter never
 * had ({@code RontoLispCli#enableSimd} already warns and falls back). {@code _simdInit}
 * (see {@link JvmSimdRuntimeBuilder}) resolves the embedded bridge's verifier-visible
 * types at {@code Lookup.defineClass}, before any bridge method ever runs, so the only
 * way to reproduce the bug this item fixes is a FRESH child JVM started deliberately
 * WITHOUT {@code --add-modules jdk.incubator.vector}: the Surefire config that lets
 * {@link JvmSimdAccelCompilerTest} define the bridge in-process adds that flag to THIS
 * JVM, but a plain {@code java -cp} launch of a child process does not inherit it.
 */
class JvmSimdModuleFallbackTest {

	@TempDir
	Path tempDir;

	private byte[] compileVec(String lispCode, boolean parallel) {
		List<LispVal> program = VecLibrary.process(LispReader.readAllFromString(lispCode));
		return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, true, false, false, parallel).compile(program);
	}

	private byte[] compileLinalg(String lispCode, boolean parallel) {
		List<LispVal> program = LinalgLibrary.process(LispReader.readAllFromString(lispCode));
		return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, true, false, false, parallel).compile(program);
	}

	/** Runs a compiled class in a fresh child JVM that never sees --add-modules. */
	private Process runWithoutTheIncubatorModule(byte[] classBytes) throws Exception {
		Path dir = Files.createDirectories(this.tempDir.resolve("no-vector-" + System.nanoTime()));
		Files.write(dir.resolve("Test.class"), classBytes);
		String java = ProcessHandle.current().info().command().orElse("java");
		return new ProcessBuilder(java, "-cp", dir.toString(), "Test").start();
	}

	private record Result(int exitCode, String out, String err) {
	}

	private Result run(byte[] classBytes) throws Exception {
		Process process = runWithoutTheIncubatorModule(classBytes);
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int exitCode = process.waitFor();
		return new Result(exitCode, out.trim(), err);
	}

	private static void assertWarnsOnce(String err) {
		assertThat(err).as(err)
			.contains("rontolisp: warning: --simd:")
			.contains("jdk.incubator.vector")
			.contains("--add-modules jdk.incubator.vector");
		Matcher m = Pattern.compile("rontolisp: warning: --simd:").matcher(err);
		int count = 0;
		while (m.find()) {
			count++;
		}
		assertThat(count).as("warns exactly once:%n%s", err).isEqualTo(1);
	}

	@Test
	void aVecKernelDegradesToTheScalarDefunWithoutTheIncubatorModule() throws Exception {
		Result result = run(compileVec("(print (vec:sum #d(1.0 2.0 3.0 4.0 5.0)))", false));
		assertThat(result.exitCode()).as("stdout:%n%s%nstderr:%n%s", result.out(), result.err()).isZero();
		assertThat(result.out()).isEqualTo("15.0");
		assertWarnsOnce(result.err());
	}

	@Test
	void aLinalgKernelDegradesToTheScalarDefunWithoutTheIncubatorModule() throws Exception {
		Result result = run(compileLinalg("(print (linalg:sum #d((1.0 2.0) (3.0 4.0))))", false));
		assertThat(result.exitCode()).as("stdout:%n%s%nstderr:%n%s", result.out(), result.err()).isZero();
		assertThat(result.out()).isEqualTo("10.0");
		assertWarnsOnce(result.err());
	}

	/**
	 * {@code --parallel} only changes which bridge method name a call site binds
	 * ({@code simdMatvecParallel} instead of {@code simdMatvec}, {@code laDotParallel}
	 * instead of {@code laDot}): the fix generalizes to it automatically (it never
	 * bypasses {@code _simdReady()}), which this pins for both the {@code vec:} call site
	 * and the {@code linalg:} chain.
	 */
	@Test
	void parallelDegradesTheSameWayForBothVecAndLinalg() throws Exception {
		Result vec = run(compileVec("(print (vec:matvec #d((1 2) (3 4)) #d(5 6)))", true));
		assertThat(vec.exitCode()).as("stdout:%n%s%nstderr:%n%s", vec.out(), vec.err()).isZero();
		assertThat(vec.out()).isEqualTo("#d(17.0 39.0)");
		assertWarnsOnce(vec.err());

		Result linalg = run(compileLinalg("(print (linalg:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)))", true));
		assertThat(linalg.exitCode()).as("stdout:%n%s%nstderr:%n%s", linalg.out(), linalg.err()).isZero();
		assertThat(linalg.out()).isEqualTo("32.0");
		assertWarnsOnce(linalg.err());
	}

	@Test
	void theWarningPrintsOnceEvenAcrossManyAcceleratedCallSites() throws Exception {
		String program = "(print (+ (vec:sum #d(1.0 2.0)) (vec:dot #d(1.0 2.0) #d(3.0 4.0)) "
				+ "(vec:sum (vec:add #d(1.0 2.0) #d(3.0 4.0)))))";
		Result result = run(compileVec(program, false));
		assertThat(result.exitCode()).as("stdout:%n%s%nstderr:%n%s", result.out(), result.err()).isZero();
		assertThat(result.out()).isEqualTo("24.0");
		assertWarnsOnce(result.err());
	}

}
