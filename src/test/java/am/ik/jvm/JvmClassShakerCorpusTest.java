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
import am.ik.rontolisp.compiler.OptimizeLevel;
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
		// Mirror the CLI compile path: user macros (defmacro) are expanded, and the
		// reactor transport (the corpus drives %http-reactor-dispatch), the server value
		// model, the JSON, linalg, URL, prelude (equalp/string<) and usocket libraries
		// are spliced
		// by the pre-passes, and the LibraryDefunPruner drops the spliced defuns the
		// corpus never reaches -- so the shaker decodes exactly the class the real CLI
		// emits (the per-backend unit tests keep full-library codegen coverage).
		// #. in the corpus rides the marker read (resolved in UserMacroExpander), like
		// the CLI.
		String source = corpusSource();
		List<LispVal> read = source.contains("#.")
				? LispReader.readAllWithReadEvalMarkers(source, am.ik.rontolisp.reader.Features.JVM)
				: LispReader.readAllFromString(source, am.ik.rontolisp.reader.Features.JVM);
		// LoadInliner splices the built-in ASDF shim systems the corpus load-systems
		// (bordeaux-threads' bt2 case), exactly like the CLI; the corpus references no
		// filesystem source, so the loader throws.
		List<LispVal> inlined = am.ik.rontolisp.cli.LoadInliner.inline(read, path -> {
			throw new java.io.FileNotFoundException(path);
		}, null, List.of(), am.ik.rontolisp.reader.Features.JVM);
		// The prelude splice takes the TARGET features, like the CLI: the uiop splice it
		// drives reads its resources with them, and uiop:featurep -- which the corpus
		// reaches -- is written over *features*, a symbol the compile backends have no
		// runtime binding for (.kb/uiop.md).
		List<LispVal> spliced = am.ik.rontolisp.eval.LispPreludeLibrary.process(
				am.ik.rontolisp.eval.UrlLibrary
					.process(am.ik.rontolisp.eval.LinalgLibrary.process(am.ik.rontolisp.eval.JsonLibrary
						.process(am.ik.rontolisp.eval.UserMacroExpander.expand(am.ik.rontolisp.eval.HttpServerLibrary
							.process(am.ik.rontolisp.eval.HttpReactorLibrary.process(inlined),
									am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(inlined)))))),
				am.ik.rontolisp.reader.Features.JVM);
		// UnreadCharLibrary runs LAST of the splices, over the Gray rewrite's output,
		// exactly like the CLI: the corpus unreads a character on a stream HANDLE, and
		// the pushback that carries it is spliced Lisp.
		List<LispVal> program = am.ik.rontolisp.eval.LibraryDefunPruner.prune(am.ik.rontolisp.eval.UnreadCharLibrary
			.process(am.ik.rontolisp.eval.UsocketLibrary.process(am.ik.rontolisp.eval.GrayStreamsLibrary
				.process(am.ik.rontolisp.eval.VecLibrary.process(spliced)))));

		byte[] plain = new JvmLispCompiler("Test", false, OptimizeLevel.NONE).compile(program);
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
		byte[] optimized = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);

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
