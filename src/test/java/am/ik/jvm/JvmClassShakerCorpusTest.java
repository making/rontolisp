package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
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
 * directory, which this test deletes afterwards -- see {@code CORPUS_SCRATCH_FILES}).
 */
class JvmClassShakerCorpusTest {

	@TempDir
	Path workDir;

	private record Case(String name, String source, @Nullable String expected,
			@Nullable Map<String, String> expectedByBackend) {
	}

	private record Spec(List<Case> cases) {
	}

	private static String corpusSource() throws IOException {
		try (InputStream in = JvmClassShakerCorpusTest.class.getResourceAsStream("/ci-spec.yaml")) {
			assertThat(in).as("ci-spec.yaml test resource").isNotNull();
			Spec spec = new tools.jackson.dataformat.yaml.YAMLMapper().readValue(in, Spec.class);
			StringBuilder sb = new StringBuilder();
			for (Case c : spec.cases()) {
				sb.append(c.source());
				if (!c.source().endsWith("\n")) {
					sb.append('\n');
				}
			}
			return sb.toString();
		}
	}

	// Scratch files the ci-spec file-stream cases create relative to the process
	// working directory (the corpus main runs in-process, so relative paths resolve
	// against the project dir, not the @TempDir). Keep in sync with the file names
	// used by the ci-spec.yaml binary/stream cases.
	private static final List<String> CORPUS_SCRATCH_FILES = List.of("bin.dat", "seq.dat", "crlf.dat");

	@Test
	void optimizesTheWholeCorpusWithoutDecoderGapsAndBehavesIdentically() throws Exception {
		// Mirror the CLI compile path: user macros (defmacro) are expanded and the
		// JSON, linalg and URL libraries are spliced by the pre-passes before the
		// compiler ever sees the program.
		List<LispVal> program = am.ik.rontolisp.eval.UrlLibrary
			.process(am.ik.rontolisp.eval.LinalgLibrary.process(am.ik.rontolisp.eval.JsonLibrary
				.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString(corpusSource())))));

		byte[] plain = new JvmLispCompiler("Test", false, false).compile(program);
		// A decoder gap (unrecognized opcode / constant tag) throws here, by design.
		byte[] optimized = new JvmLispCompiler("Test", false, true).compile(program);

		assertThat(optimized.length).as("optimized should shrink the class").isLessThan(plain.length);
		try {
			assertThat(run(optimized)).isEqualTo(run(plain));
		}
		finally {
			for (String name : CORPUS_SCRATCH_FILES) {
				Files.deleteIfExists(Path.of(name));
			}
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

}
