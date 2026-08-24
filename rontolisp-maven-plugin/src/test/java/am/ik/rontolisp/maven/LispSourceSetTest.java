package am.ik.rontolisp.maven;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import am.ik.rontolisp.cli.JvmSourceCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code compile} goal's actual work, driven the way a build drives it: a scratch
 * project whose {@code src/main/lisp} kernel is compiled into {@code target/classes},
 * then called from a {@code src/main/java} class compiled against exactly that directory.
 * <p>
 * javac against the output directory is the point rather than a convenience: it is the
 * proof that the handle type a {@code :float-vector} export hands out is written there
 * too, and written BEFORE javac would run in a real build.
 */
class LispSourceSetTest {

	@TempDir
	Path project;

	@Test
	void aKernelInSrcMainLispIsCalledFromSrcMainJava() throws Exception {
		Path classes = writeKernels();
		LispSourceSet.Result result = compile(classes, compiler -> compiler.simd(true));

		assertThat(result.upToDate()).isFalse();
		assertThat(result.classes()).containsExactly("com.example.Kernels");
		assertThat(classes.resolve("com/example/Kernels.class")).exists();
		// The packed-float handle travels at its canonical name, beside the class.
		assertThat(classes.resolve("am/ik/rontolisp/runtime/RontoFloatArray.class")).exists();

		Path java = this.project.resolve("src/main/java/app/App.java");
		Files.createDirectories(java.getParent());
		Files.writeString(java, """
				package app;

				import am.ik.rontolisp.runtime.RontoFloatArray;
				import com.example.Kernels;

				public class App {
					public static void main(String[] args) {
						System.out.println(Kernels.scaledSum(2.5, 3.5));
						System.out.println(Kernels.norm2(RontoFloatArray.of(new double[] { 3.0, 4.0 })));
					}
				}
				""");
		javac(java, classes);
		// scaledSum reads a defvar, so the class initializer must have run before the
		// first typed call; norm2 crosses the handle.
		assertThat(run(classes, "app.App").lines().toList()).containsExactly("12.0", "5.0");
	}

	@Test
	void aSecondBuildWithNothingChangedCompilesNothing() throws Exception {
		Path classes = writeKernels();
		assertThat(compile(classes, compiler -> {
		}).upToDate()).isFalse();
		assertThat(compile(classes, compiler -> {
		}).upToDate()).isTrue();

		// A touched source makes the whole set stale again: a (load "...") splices one
		// file into another, so per-file timestamps cannot be trusted alone.
		Path source = this.project.resolve("src/main/lisp/com/example/Kernels.lisp");
		Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() + 2000));
		assertThat(compile(classes, compiler -> {
		}).upToDate()).isFalse();
	}

	@Test
	void aFailureCarriesTheRontolispDiagnosticWithItsPosition() throws Exception {
		Path source = this.project.resolve("src/main/lisp/com/example/Broken.lisp");
		Files.createDirectories(source.getParent());
		Files.writeString(source, """
				(defmacro broken-macro ()
				  (error "this macro cannot expand"))
				(defun broken (x)
				  (broken-macro))
				(rontolisp:jvm-export 'broken :params '(:float) :returns :float)
				""");
		Path classes = this.project.resolve("target/classes");
		assertThatThrownBy(() -> compile(classes, compiler -> {
		})).isInstanceOf(LispCompilationException.class)
			.hasMessageContaining("Broken.lisp:4:3:")
			.hasMessageContaining("this macro cannot expand");
	}

	@Test
	void aSourcePathThatIsNotAJavaIdentifierIsRefusedByNameOnceItExports() throws Exception {
		Path source = this.project.resolve("src/main/lisp/my-kernels/Vec.lisp");
		Files.createDirectories(source.getParent());
		Files.writeString(source, """
				(defun f (x) x)
				(rontolisp:jvm-export 'f :params '(:float) :returns :float)
				""");
		Path classes = this.project.resolve("target/classes");
		assertThatThrownBy(() -> compile(classes, compiler -> {
		})).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'my-kernels' is not a Java identifier");
	}

	@Test
	void aSourceWithNoExportIsOrdinaryLispAndProducesNoClass() throws Exception {
		// The Lisp convention, and not a class name: a file with no export never has to
		// be one, so this is the shape a source set is mostly made of.
		Path source = this.project.resolve("src/main/lisp/my-kernels/string-utils.lisp");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "(defun shout (s) (string-upcase s))\n");
		Path classes = this.project.resolve("target/classes");

		LispSourceSet.Result result = compile(classes, compiler -> {
		});

		assertThat(result.sources()).isEqualTo(1);
		assertThat(result.classes()).isEmpty();
		assertThat(result.uncompiled()).containsExactly("my-kernels/string-utils.lisp");
		assertThat(classes).doesNotExist();
	}

	@Test
	void anExportedKernelCompilesBesideTheHelperItLoads() throws Exception {
		Path helper = this.project.resolve("src/main/lisp/com/example/vector-helpers.lisp");
		Files.createDirectories(helper.getParent());
		Files.writeString(helper, "(defun square (x) (* x x))\n");
		Path source = this.project.resolve("src/main/lisp/com/example/Kernels.lisp");
		Files.writeString(source, """
				(load "vector-helpers.lisp")

				(defun sum-of-squares (a b)
				  (+ (square a) (square b)))

				(rontolisp:jvm-export 'sum-of-squares :params '(:float :float) :returns :float)
				""");
		Path classes = this.project.resolve("target/classes");

		LispSourceSet.Result result = compile(classes, compiler -> {
		});

		// The helper is spliced into the kernel by (load ...), not compiled on its own.
		assertThat(result.classes()).containsExactly("com.example.Kernels");
		assertThat(result.uncompiled()).containsExactly("com/example/vector-helpers.lisp");
		assertThat(classes.resolve("com/example")).isDirectoryContaining("glob:**/Kernels.class");
	}

	@Test
	void withNoMainOffEveryFileCompilesAsAProgram() throws Exception {
		Path source = this.project.resolve("src/main/lisp/com/example/Report.lisp");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "(print (+ 1 2))\n");
		Path classes = this.project.resolve("target/classes");

		LispSourceSet.Result result = new LispSourceSet(this.project.resolve("src/main/lisp"), classes,
				this.project.resolve("target/rontolisp/compile-status.txt"), false, compiler -> {
				})
			.compile();

		assertThat(result.classes()).containsExactly("com.example.Report");
		assertThat(run(classes, "com.example.Report")).isEqualTo("3\n");
	}

	private Path writeKernels() throws Exception {
		Path source = this.project.resolve("src/main/lisp/com/example/Kernels.lisp");
		Files.createDirectories(source.getParent());
		Files.writeString(source, """
				(defvar *scale* 2.0)

				(defun scaled-sum (a b)
				  (* *scale* (+ a b)))

				(defun norm2 (x)
				  (sqrt (vec:dot x x)))

				(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)
				(rontolisp:jvm-export 'norm2 :params '(:float-vector) :returns :float)
				""");
		return this.project.resolve("target/classes");
	}

	private LispSourceSet.Result compile(Path classes, Consumer<JvmSourceCompiler> configuration) throws Exception {
		return new LispSourceSet(this.project.resolve("src/main/lisp"), classes,
				this.project.resolve("target/rontolisp/compile-status.txt"), true, configuration)
			.compile();
	}

	private static void javac(Path source, Path classpath) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		int status = compiler.run(null, null, null, "-cp", classpath.toString(), "-d", classpath.toString(),
				source.toString());
		assertThat(status).describedAs("javac %s", source).isZero();
	}

	private static String run(Path classpath, String mainClass) throws Exception {
		// The --simd kernels need the incubator module; without it the class degrades to
		// the scalar reference and says so on stderr, which is not what this asserts.
		List<String> command = new ArrayList<>(
				List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "--add-modules",
						"jdk.incubator.vector", "-cp", classpath.toString(), mainClass));
		// Standard error stays separate: the JVM prints its own incubator-module warning
		// there, and this asserts on what the program wrote.
		Process process = new ProcessBuilder(command).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		int status = process.waitFor();
		assertThat(status).describedAs("%s exited %d:%n%s%n%s", command, status, output, errors).isZero();
		return output;
	}

}
