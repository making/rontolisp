package am.ik.rontolisp.codegen.jvm;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.runtime.RontoFloatArray;
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
		// The vec: kernels are spliced exactly as the CLI splices them on a compile path.
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(source)));
		JvmLispCompiler compiler = new JvmLispCompiler("com/example/Kernels", false, OptimizeLevel.DEFAULT)
			.noMain(true);
		byte[] classBytes = compiler.compile(program);
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
		// The packed float-array handle: held across calls, aliased at every crossing.
		RontoFloatArray x = RontoFloatArray.of(new double[] { 3.0, 4.0 });
		assertThat(kernels.getMethod("norm2", RontoFloatArray.class).invoke(null, x)).isEqualTo(5.0);
		Object sum = kernels.getMethod("axpy", double.class, RontoFloatArray.class, RontoFloatArray.class)
			.invoke(null, 2.0, x, RontoFloatArray.of(new double[] { 1.0, 1.0 }));
		assertThat(((RontoFloatArray) sum).toArray()).containsExactly(7.0, 9.0);
		RontoFloatArray matrix = RontoFloatArray.of(new double[] { 1, 2, 3, 4, 5, 6 }, 2, 3);
		assertThat(kernels.getMethod("cell", RontoFloatArray.class, int.class, int.class).invoke(null, matrix, 1, 2))
			.isEqualTo(6.0);
		// The handle's class files travel WITH the library, so the artifact a consumer
		// gets still has no dependency.
		assertThat(compiler.runtimeClassFiles()).containsKey("am/ik/rontolisp/runtime/RontoFloatArray.class");
		assertThatThrownBy(() -> kernels.getMethod("main", String[].class)).isInstanceOf(NoSuchMethodException.class);
	}

}
