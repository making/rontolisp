package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The embedded compile seam: source text in, class bytes out, no command line and no
 * file. What must hold is that it is the SAME compile the CLI runs -- an embedder that
 * got a different program from the same source would be a second compiler.
 */
class JvmSourceCompilerTest {

	private static final String LIBRARY = """
			(defvar *scale* 2.0)
			(defun scaled-sum (a b) (* *scale* (+ a b)))
			(defun norm2 (x) (sqrt (vec:dot x x)))
			(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)
			(rontolisp:jvm-export 'norm2 :params '(:float-vector) :returns :float)
			""";

	@TempDir
	Path tempDir;

	@Test
	void theEmbeddedCompileIsByteIdenticalToTheCommandLines() throws Exception {
		Path source = this.tempDir.resolve("kernels.lisp");
		Files.writeString(source, LIBRARY);
		Path output = this.tempDir.resolve("com/example/Kernels.class");
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream()));
		// --class-name, because the -o path here is an absolute temp directory and the
		// path IS the class name without it.
		cli.run(new String[] { source.toString(), "-o", output.toString(), "--class-name", "com.example.Kernels",
				"--no-main", "--simd" });

		JvmSourceCompiler.Result compiled = new JvmSourceCompiler("com.example.Kernels").noMain(true)
			.simd(true)
			.baseDir(this.tempDir.toString())
			.compile(LIBRARY, source.toString());

		assertThat(compiled.internalClassName()).isEqualTo("com/example/Kernels");
		assertThat(compiled.className()).isEqualTo("com.example.Kernels");
		assertThat(compiled.classBytes()).isEqualTo(Files.readAllBytes(output));
		// The :float-vector export's handle class travels with the artifact, so an
		// embedder that writes only the class bytes would hand its consumer a
		// NoClassDefFoundError.
		assertThat(compiled.runtimeClasses()).containsKey("am/ik/rontolisp/runtime/RontoFloatArray.class");
	}

	@Test
	void aFailureCarriesTheFrontendsPositionPrefix() {
		assertThatThrownBy(() -> new JvmSourceCompiler("com.example.Broken").compile("""
				(defmacro m () (error "cannot expand"))
				(defun f (x) (m))
				""", "broken.lisp")).isInstanceOf(LispCompileException.class)
			.hasMessageStartingWith("broken.lisp:2:14:")
			.hasMessageContaining("cannot expand");
	}

	@Test
	void aLibraryWithNoExportCannotDropItsMain() {
		assertThatThrownBy(() -> new JvmSourceCompiler("com.example.Empty").noMain(true)
			.compile("(defun f (x) x)\n", "empty.lisp")).hasMessageContaining("jvm-export");
	}

}
