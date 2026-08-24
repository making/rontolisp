package am.ik.rontolisp.codegen.jvm;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compiles {@code examples/jvm/kernels-library.lisp} — the shipped
 * {@code rontolisp:jvm-export} library example — as the packaged, main-less class its
 * header describes, and calls every typed method from Java, so the example and the
 * feature cannot drift apart.
 */
class JvmExportExampleTest {

	@TempDir
	Path tempDir;

	@Test
	void theShippedKernelsLibraryExampleIsCallableFromJava() throws Exception {
		String source = Files.readString(Path.of("examples/jvm/kernels-library.lisp"));
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(source));
		byte[] classBytes = new JvmLispCompiler("com/example/Kernels", false, OptimizeLevel.DEFAULT).noMain(true)
			.compile(program);
		Path classFile = this.tempDir.resolve("com/example/Kernels.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, classBytes);

		URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader());
		Class<?> kernels = loader.loadClass("com.example.Kernels");
		// The first entry into the class is a typed call: the defvar must have been
		// initialized by <clinit>, with no main ever run (there is none).
		assertThat(kernels.getMethod("scaledSum", double.class, double.class).invoke(null, 2.5, 3.5)).isEqualTo(12.0);
		assertThat(kernels.getMethod("fact", long.class).invoke(null, 20L)).isEqualTo(2432902008176640000L);
		assertThat(kernels.getMethod("greet", String.class).invoke(null, "ron")).isEqualTo("hello, ron");
		assertThatThrownBy(() -> kernels.getMethod("main", String[].class)).isInstanceOf(NoSuchMethodException.class);
	}

}
