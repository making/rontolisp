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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import am.ik.objc.ObjcRuntime;
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
 * The {@code objc} package compiled to a JVM class: {@code objc.lisp} spliced by the
 * compile front end and compiled like any Lisp, its primitive layer calls into the
 * {@link JvmObjcPrimitivesTemplate} copy shipped beside the program, with the
 * {@code am.ik.objc} library. The corpora print what the interpreter prints
 * ({@code eval/ObjcBaseTest} and its siblings pin that output).
 *
 * <p>
 * Every compiled program carries its OWN copy of the binding (renamed after it, loaded
 * into its own class loader), so a class a program defines at run time must have a name
 * no other program in this JVM has defined, or be one a definition in this process made
 * (which is then reused).
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
	void aProgramShipsThePrimitiveLayerAndCompilesOnEveryMachine() {
		byte[] program = compile("(print (objc:invoke \"NSObject\" \"new\"))");
		String bytes = new String(program, StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains(JvmObjcRuntimeBuilder.primitivesName("Test"));
		assertThat(this.tempDir.resolve(JvmObjcRuntimeBuilder.primitivesName("Test") + ".class")).exists();
		// A program that names no macOS package carries none of it.
		assertThat(new String(compile("(print 1)"), StandardCharsets.ISO_8859_1))
			.doesNotContain(JvmObjcRuntimeBuilder.primitivesName("Test"));
	}

	@Test
	void theProgramShipsTheWholeLibrary() throws Exception {
		// The compiled backend runs am.ik.objc's own bytes rather than a hand-kept copy
		// of them, so a class file added to the library must be added to the list that
		// travels -- there is no way to enumerate a package from a classpath, let alone
		// from inside a native image (.kb/objc.md, .kb/template-class-embedding.md).
		Path classes = Path.of(ObjcRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assumeTrue(Files.isDirectory(classes), "the library must be on the classpath as class files");
		List<String> onDisk;
		try (Stream<Path> files = Files.list(classes.resolve("am/ik/objc"))) {
			onDisk = files.map(p -> p.getFileName().toString())
				.filter(name -> name.endsWith(".class"))
				.map(name -> name.substring(0, name.length() - ".class".length()))
				.filter(name -> !name.equals("package-info"))
				.sorted()
				.toList();
		}
		assertThat(JvmObjcRuntimeBuilder.embeddedObjcClasses()).containsExactlyInAnyOrderElementsOf(onDisk);
		// The primitive layer is ONE class file: a nested class, or the synthetic $1 an
		// enum switch lowers to, would be a file the builder does not ship.
		try (Stream<Path> files = Files.list(classes.resolve("am/ik/rontolisp/codegen/jvm"))) {
			assertThat(files.map(p -> p.getFileName().toString())
				.filter(name -> name.startsWith("JvmObjcPrimitivesTemplate$"))).isEmpty();
		}
		// And nothing of the library's own package name survives the rename: the
		// emitted class resolves the shipped files under its own names or not at all.
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		String bytes = new String(compiler.compile(
				CompileFrontendAccess.corpus("(print (objc:invoke \"NSObject\" \"new\"))", Features.JVM, false, false)),
				StandardCharsets.ISO_8859_1);
		assertThat(bytes).doesNotContain("am/ik/objc/").doesNotContain("am/ik/rontolisp/codegen/jvm/JvmObjc");
		List<Map.Entry<String, byte[]>> shipped = compiler.runtimeClassFiles()
			.entrySet()
			.stream()
			.filter(file -> file.getKey().startsWith("Test$Objc"))
			.toList();
		assertThat(shipped).hasSize(onDisk.size() + 1);
		for (Map.Entry<String, byte[]> file : shipped) {
			assertThat(new String(file.getValue(), StandardCharsets.ISO_8859_1)).as(file.getKey())
				.doesNotContain("am/ik/objc/")
				.doesNotContain("am/ik/rontolisp/codegen/jvm/JvmObjc");
		}
	}

	@Test
	void theWidgetLayerGatesTheLibraryOnAndIsPrunedToWhatTheProgramCalls() {
		// appkit.lisp is spliced by the front end and compiles as ordinary Lisp over
		// objc.lisp, whose primitive calls gate the shipped library on; like every
		// library it is pruned to what the program reaches
		// (.kb/library-defun-pruning.md):
		// the widget it calls stays, the window it never opens goes.
		String appkit = new String(compile("(print (appkit:visible-p nil))"), StandardCharsets.ISO_8859_1);
		assertThat(appkit).contains(JvmObjcRuntimeBuilder.primitivesName("Test"))
			.contains("APPKIT$colonVISIBLE-P")
			.doesNotContain("APPKIT$colonWINDOW");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theWidgetLayersHeadlessFunctionsRun() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// A window is never opened by a test: examples/macos/counter.lisp is the visible
		// check. A nil receiver answers nil.
		assertThat(run(compile("""
				(print (appkit:visible-p nil))
				(print (appkit:text nil))
				(print (appkit:set-text nil "x"))
				"""))).isEqualTo("NIL\nNIL\n\"x\"\n");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aProducerBuiltStringCrossesTheObjcBoundary() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// A class name, a selector and an NSString's content built by concatenate /
		// format nil are MUTABLE character vectors on this backend
		// (.kb/string-write-runtime.md): the primitive layer renders one through the
		// reflectively bound _strv, so the Objective-C boundary accepts it exactly as the
		// interpreter accepts a string. The unit pin for the
		// examples/macos/system-frameworks.lisp canary.
		assertThat(run(compile("""
				(print (objc:invoke (objc:string-to-ns-string (concatenate 'string "ronto" "lisp"))
				                    (format nil "len~a" "gth")))
				(print (objc:objectp (objc:coerce-to-objc-class (concatenate 'string "NS" "Object"))))
				"""))).isEqualTo("9\nT\n");
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
