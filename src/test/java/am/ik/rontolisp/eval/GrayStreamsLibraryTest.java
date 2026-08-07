package am.ik.rontolisp.eval;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compile-path splice PLACEMENT {@link GrayStreamsLibrary} owns: the Gray protocol
 * definitions must precede every form that subclasses them, no matter which pass put
 * either there.
 *
 * <p>
 * The collision this pins is the real one from the Clack ecosystem: a program that loads
 * a Gray shim (which splices the protocol at the shim's own splice site, mid-program) AND
 * serves a buffered {@code :raw-body} (whose {@code http-request-body-stream} class
 * {@link HttpServerLibrary} prepends at index 0) had its subclass ahead of its base class
 * and failed to compile. It needs no container and no network, unlike the ecosystem-level
 * {@code LackEcosystemE2eTest}, which is why it lives here.
 */
class GrayStreamsLibraryTest {

	@TempDir
	Path tempDir;

	/**
	 * The program half of the collision: the buffered {@code :raw-body} stream, read back
	 * both ways through the Gray dispatch.
	 */
	private static final String BODY_STREAM_PROGRAM = """
			(let ((s (rontolisp::%http-body-stream "hi")))
			  (print (read-byte s))
			  (print (read-char s)))
			""";

	private static final String BODY_STREAM_EXPECTED = """
			104
			#\\i
			""";

	@Test
	void grayProtocolIsHoistedAboveTheServerLibrarysSubclass() throws Exception {
		// The shim splice lands the protocol MID-program (ShimLibraries prepends
		// protocolForms() to the shim's own forms, at wherever the quickload sat).
		List<LispVal> program = new ArrayList<>();
		program.addAll(LispReader.readAllFromString("(defvar *anchor* 1)"));
		program.addAll(ShimLibraries.forms("trivial-gray-streams", Features.INTERPRETER));
		program.addAll(LispReader.readAllFromString(BODY_STREAM_PROGRAM));
		assertThat(compileAndRun(process(program))).isEqualToNormalizingWhitespace(BODY_STREAM_EXPECTED);
	}

	/**
	 * The no-shim path stays exactly as it was: nothing defines the protocol, so it is
	 * prepended as one block at the very front and no form moves.
	 */
	@Test
	void programWithoutAGrayShimKeepsTheProtocolSpliceAtTheFront() throws Exception {
		List<LispVal> program = new ArrayList<>(LispReader.readAllFromString(BODY_STREAM_PROGRAM));
		List<LispVal> processed = process(program);
		List<LispVal> protocol = GrayStreamsLibrary.protocolForms();
		assertThat(processed.subList(0, protocol.size())).isEqualTo(protocol);
		assertThat(compileAndRun(processed)).isEqualToNormalizingWhitespace(BODY_STREAM_EXPECTED);
	}

	// The CLI pipeline's two splices, in its order: the server value model first, then
	// the Gray placement + call-site rewrite.
	private static List<LispVal> process(List<LispVal> program) {
		return GrayStreamsLibrary.process(HttpServerLibrary.process(program, true));
	}

	private String compileAndRun(List<LispVal> program) throws Exception {
		byte[] classBytes = new JvmLispCompiler("GrayPlacement", false, OptimizeLevel.NONE)
			.compile(LispPreludeLibrary.process(program));
		Files.write(this.tempDir.resolve("GrayPlacement.class"), classBytes);
		java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
		java.io.PrintStream original = System.out;
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Method main = loader.loadClass("GrayPlacement").getMethod("main", String[].class);
			System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(original);
			}
		}
		return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

}
