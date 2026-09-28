package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.eval.ObjcInterop;
import am.ik.rontolisp.reader.Features;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The new {@code objc} base compiled to a JVM class: {@code objc.lisp} spliced by the
 * compile front end and compiled like any Lisp, its primitive layer calls into the
 * {@link JvmObjcPrimitivesTemplate} copy shipped beside the program. The corpus prints
 * what the interpreter prints ({@code eval/ObjcBaseTest} pins that output).
 */
class JvmObjcBaseCompilerTest {

	@TempDir
	Path tempDir;

	private static String resource(String name) {
		try (InputStream in = JvmObjcBaseCompilerTest.class.getResourceAsStream("/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private byte[] compile(String source) {
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(CompileFrontendAccess.corpus(source, Features.JVM, false, false));
		TravellingClassFiles.write(compiler, this.tempDir);
		return classBytes;
	}

	private String run(byte[] classBytes) throws Exception {
		Files.write(this.tempDir.resolve("Test.class"), classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Method main = loader.loadClass("Test").getMethod("main", String[].class);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			catch (InvocationTargetException ex) {
				if (ex.getCause() instanceof RuntimeException re) {
					throw re;
				}
				throw ex;
			}
			finally {
				System.setOut(oldOut);
			}
			return out.toString(StandardCharsets.UTF_8);
		}
	}

	@Test
	void aProgramOnTheNewBaseShipsThePrimitiveLayerAndCompilesOnEveryMachine() {
		byte[] program = compile("(print (objc:invoke \"NSObject\" \"new\"))");
		String bytes = new String(program, StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains(JvmObjcRuntimeBuilder.primitivesName("Test"));
		assertThat(this.tempDir.resolve(JvmObjcRuntimeBuilder.primitivesName("Test") + ".class")).exists();
		// A program that names neither base carries none of it.
		assertThat(new String(compile("(print 1)"), StandardCharsets.ISO_8859_1))
			.doesNotContain(JvmObjcRuntimeBuilder.primitivesName("Test"));
	}

	@Test
	void aVariableTheBodyOfWithAutoreleasePoolNamesIsCaptured() {
		// The macro wraps its body in a lambda the compiler only sees once it expands it:
		// the capture analysis must expand it too, or the closure finds no cell.
		byte[] program = compile("""
				(defun object-description (object)
				  (objc:with-autorelease-pool ()
				    (objc:invoke-into 'string object "description")))
				""");
		assertThat(program).isNotEmpty();
	}

	@Test
	void aMachineWithoutTheRuntimeSignalsAtTheCall() throws Exception {
		assumeTrue(!ObjcInterop.available(), "this machine has the runtime");
		assertThat(run(compile("""
				(print (handler-case (objc:invoke "NSObject" "new") (error (e) (princ-to-string e))))
				"""))).contains("objc: Objective-C is not available");
	}

	@Test
	void aProgramThatOnlyCallsCarriesNoClassDefinition() {
		String calling = new String(compile("(print (objc:invoke \"NSObject\" \"new\"))"), StandardCharsets.ISO_8859_1);
		assertThat(calling).doesNotContain("STANDARD-OBJC-OBJECT");
		String defining = new String(compile("""
				(objc:define-objc-class probe () () (:objc-class-name "RontoLispProbe"))
				(objc:define-objc-method ("size" :int) ((self probe)) 42)
				"""), StandardCharsets.ISO_8859_1);
		assertThat(defining).contains("STANDARD-OBJC-OBJECT");
	}

	@Test
	void aProgramThatMakesNoBlockCarriesNoBlockHalf() {
		String calling = new String(compile("(print (objc:invoke \"NSObject\" \"new\"))"), StandardCharsets.ISO_8859_1);
		assertThat(calling).doesNotContain("OBJC-BLOCK");
		String blocking = new String(compile("""
				(objc:with-objc-block (b '(:void ()) (lambda () nil))
				  (print (objc:objc-block-live-p b)))
				"""), StandardCharsets.ISO_8859_1);
		assertThat(blocking).contains("OBJC-BLOCK");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theBlockCorpusPrintsWhatTheInterpreterPrints() throws Exception {
		// Blocks on a libdispatch worker run there, as on the interpreter.
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(run(compile(resource("objc-block-corpus.lisp")))).isEqualTo(resource("objc-block-corpus.expected"));
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theClassCorpusPrintsWhatTheInterpreterPrints() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(run(compile(resource("objc-class-corpus.lisp")))).isEqualTo(resource("objc-class-corpus.expected"));
	}

	@Test
	@EnabledOnOs(value = OS.MAC, architectures = "aarch64")
	void theExceptionCorpusPrintsWhatTheInterpreterPrints() throws Exception {
		// An NSException inside a send is objc:objc-exception through the catching
		// trampolines the shipped am.ik.objc copy writes; invoke-with-error's ns-error.
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(run(compile(resource("objc-exception-corpus.lisp"))))
			.isEqualTo(resource("objc-exception-corpus.expected"));
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theCorpusPrintsWhatTheInterpreterPrints() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(run(compile(resource("objc-base-corpus.lisp")))).isEqualTo(resource("objc-base-corpus.expected"));
	}

}
