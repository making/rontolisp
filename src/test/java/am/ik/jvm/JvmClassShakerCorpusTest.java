package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decoder-completeness guard for the JVM class shaker, the JVM counterpart of
 * {@code WasmTreeShakerCorpusTest}. The shaker has to walk every instruction and
 * constant-pool tag the code generator emits; anything it does not recognize makes
 * {@link JvmClassShaker#shake} throw (the safe failure), and a compaction bug would
 * produce a class the JVM verifier rejects or that misbehaves. This test compiles the
 * whole {@code ci-spec.yaml} corpus (the cross-backend feature catalogue) with
 * {@code --optimize} and asserts that shaking never throws, strictly shrinks the class,
 * and that the optimized class runs with output identical to the unoptimized one (the
 * corpus is deterministic: its random/getenv cases assert only deterministic properties,
 * and its file-stream cases write scratch files relative to the process working
 * directory, which this test deletes afterwards by diffing a before/after snapshot of the
 * project root -- see {@code CorpusFixtures.snapshotTopLevel}/{@code
 * removeNewEntries}; a hand-maintained name list went stale as ci-spec.yaml grew).
 */
class JvmClassShakerCorpusTest {

	@TempDir
	Path workDir;

	private static String corpusSource() throws IOException {
		return am.ik.rontolisp.testsupport.YamlResources.corpusSource();
	}

	@Test
	void optimizesTheWholeCorpusWithoutDecoderGapsAndBehavesIdentically() throws Exception {
		// Snapshot before staging or running anything, so every file or directory the
		// corpus run creates at a relative path -- by name or not -- is caught below.
		java.util.Set<String> before = am.ik.rontolisp.testsupport.CorpusFixtures.snapshotTopLevel(Path.of("."));
		// The `wild-pathnames` case walks a bounded ./wpc-sub/ tree the driver must
		// stage (see CorpusFixtures); this run's working directory is the project
		// root, so the tree is removed again below.
		am.ik.rontolisp.testsupport.CorpusFixtures.stageWildPathnameTree(Path.of("."));
		// The CLI's own pass pipeline, not a copy of it: CompileFrontendAccess calls
		// CompileFrontend.expand, so the shaker decodes exactly the class the real CLI
		// emits and no pass or ordering can drift out of this test again. It used to be
		// spelled out here and had fallen ten passes behind (.todo/688).
		List<LispVal> program = am.ik.rontolisp.cli.CompileFrontendAccess.corpus(corpusSource(),
				am.ik.rontolisp.reader.Features.JVM, false, false);

		byte[] plain = withoutUndefinedWarnings(() -> JvmLispCompiler.builder()
			.className("Test")
			.optimize(OptimizeLevel.NONE)
			.build()
			.compile(program));
		// The corpus class is the one that once crossed the JVM 65535 constant-pool
		// ceiling. The LibraryDefunPruner keeps the pool small by dropping
		// unreachable spliced library defuns; guard the headroom so a growing corpus
		// or library fails loudly here, not with a corrupt class in CI.
		int constantPoolEntries = (((plain[8] & 0xff) << 8) | (plain[9] & 0xff)) - 1;
		System.out.println("corpus class constant-pool entries: " + constantPoolEntries + " / 65534");
		assertThat(constantPoolEntries)
			.as("constant-pool headroom (was 65520/65534 before the "
					+ "LibraryDefunPruner and ConstantPool deduplication)")
			.isLessThanOrEqualTo(52000);
		// A decoder gap (unrecognized opcode / constant tag) throws here, by design.
		byte[] optimized = withoutUndefinedWarnings(() -> JvmLispCompiler.builder()
			.className("Test")
			.optimize(OptimizeLevel.DEFAULT)
			.build()
			.compile(program));

		assertThat(optimized.length).as("optimized should shrink the class").isLessThan(plain.length);
		try {
			assertThat(run(optimized)).isEqualTo(run(plain));
		}
		finally {
			am.ik.rontolisp.testsupport.CorpusFixtures.removeWildPathnameTree(Path.of("."));
			am.ik.rontolisp.testsupport.CorpusFixtures.removeNewEntries(Path.of("."), before);
		}
	}

	// Loads the class in a fresh loader (the JVM verifier checks the shaken bytecode),
	// runs its main, and returns the captured stdout.
	private String run(byte[] classBytes) throws Exception {
		Path classFile = this.workDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.workDir.toUri().toURL() },
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
			return baos.toString();
		}
	}

	/**
	 * Compiles with {@code System.err} captured and fails on any
	 * {@code warning: ... is undefined} line.
	 *
	 * <p>
	 * <b>A test that prints a compile warning must assert on it.</b> When the corpus lost
	 * its {@code TokenizersLibrary} splice, the WASM guard compiled fifteen
	 * undefined-call warnings to standard output and PASSED -- the program was broken,
	 * the breakage was on the console, and nobody reads the output of a green test
	 * (.todo/688). Warnings reach {@code System.err} whether a backend buffers them per
	 * attempt or prints them straight through ({@code compiler/CompileWarnings}), so
	 * capturing that stream catches both.
	 * @param compile the compile to run
	 * @return whatever it produced
	 */
	private static <T> T withoutUndefinedWarnings(java.util.function.Supplier<T> compile) {
		java.io.PrintStream saved = System.err;
		java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
		T result;
		try {
			System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
			result = compile.get();
		}
		finally {
			System.setErr(saved);
		}
		String warnings = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
		saved.print(warnings);
		assertThat(
				warnings.lines().filter(line -> line.contains("warning: ") && line.contains("is undefined")).toList())
			.as("undefined-function warnings from the corpus compile: a name the corpus "
					+ "reaches is not being spliced, so the pass pipeline is wrong")
			.isEmpty();
		return result;
	}

}
