package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.eval.FfiInterop;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.ShimLibraries;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real groveller ({@code .kb/cffi.md}, "cffi-grovel"): upstream cffi-grovel and
 * cffi-toolchain, vendored unmodified under {@code src/test/resources/cffi}, load the
 * {@code grovel-demo} fixture the way osicat is written -- a grovel file whose constant,
 * type, structure layout and enum a C program compiled by the host's {@code cc} reads
 * from the headers, and a wrapper file compiled into a library of the system's own -- on
 * the interpreter, and into a compiled class whose build image did the grovelling. Both
 * answer what the headers say on Linux x86-64 and macOS arm64 alike.
 *
 * <p>
 * Skipped on a host without a C compiler or native access. The fixture is copied first: a
 * build writes its outputs beside the files it reads.
 */
class CffiGrovelE2eTest {

	private static final String EXERCISE = """
			(asdf:load-system "grovel-demo")
			(print (grovel-demo:report))
			""";

	private static final String EXPECTED = "(2 NIL 8 16 8 0 5 42)";

	@TempDir
	Path work;

	@BeforeEach
	void needsTheToolchain() {
		assumeTrue(ShimLibraries.refusalReason("cffi-grovel") == null, "no C compiler on PATH");
		assumeTrue(FfiInterop.available(), FfiInterop.description());
	}

	private List<String> systemPath() throws IOException {
		Path fixture = this.work.resolve("cffi-grovel-demo");
		Files.createDirectories(fixture);
		try (Stream<Path> files = Files.list(Path.of("src", "test", "resources", "cffi-grovel-demo"))) {
			for (Path file : files.toList()) {
				Files.copy(file, fixture.resolve(file.getFileName().toString()));
			}
		}
		return List.of(fixture.toString(), Path.of("src", "test", "resources", "cffi").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "alexandria").toAbsolutePath().toString());
	}

	@Test
	void grovelsOnTheInterpreter() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(systemPath());
		for (LispVal expr : LispReader.readAllFromString(EXERCISE)) {
			evaluator.eval(expr);
		}
		// What the toolchain runs is logged on *debug-io* first, "; cc ...".
		assertThat(out.toString(StandardCharsets.UTF_8)
			.lines()
			.filter(line -> !line.startsWith(";"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())).containsExactly(EXPECTED);
	}

	@Test
	void grovelsInTheBuildImageOfACompiledClass() throws Exception {
		JvmSourceCompiler.Result compiled = new JvmSourceCompiler("GrovelDemo").systemPath(systemPath())
			.compile(EXERCISE, null);
		Path classes = Files.createDirectories(this.work.resolve("classes"));
		Files.write(classes.resolve("GrovelDemo.class"), compiled.classBytes());
		for (Map.Entry<String, byte[]> file : compiled.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Method main = loader.loadClass("GrovelDemo").getMethod("main", String[].class);
			try (var _ = ThreadStdio.out(out)) {
				main.invoke(null, (Object) new String[0]);
			}
		}
		assertThat(out.toString().trim()).isEqualTo(EXPECTED);
	}

}
