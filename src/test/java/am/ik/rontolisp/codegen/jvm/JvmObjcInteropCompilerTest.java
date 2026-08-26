package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import am.ik.objc.ObjcRuntime;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.AppKitLibrary;
import am.ik.rontolisp.eval.ObjcInterop;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code objc:} verbs compiled to a JVM {@code .class} (the embedded
 * {@code am.ik.objc} blob plus the {@link JvmObjcTemplate} bridge). Mirrors the
 * interpreter's {@code ObjcInteropTest} case for case so the two stay behaviorally
 * identical: everything here is headless -- Foundation objects and a class defined at run
 * time, never a window -- so the Mac half runs under a plain {@code ./mvnw test} on a
 * Mac, and the platform-independent half pins what a machine without the runtime must do:
 * compile every verb and SIGNAL at the call.
 *
 * <p>
 * Every compiled program carries its OWN copy of the binding (renamed into its own
 * package, defined into its own class loader), so a class a program defines at run time
 * must have a name no other program in this JVM -- the interpreter's tests included --
 * has defined: the Objective-C runtime cannot unregister one.
 */
class JvmObjcInteropCompilerTest {

	@TempDir
	Path tempDir;

	private byte[] compile(String lispCode) {
		List<LispVal> program = AppKitLibrary.process(LispReader.readAllFromString(lispCode));
		return new JvmLispCompiler("Test").compile(program);
	}

	// Compiles the program, runs its main, and returns the captured stdout. A runtime
	// exception thrown by the program is unwrapped and rethrown so tests can assert on
	// the original message.
	private String compileAndRun(String lispCode) throws Exception {
		byte[] classBytes = compile(lispCode);
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
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
			return baos.toString().trim();
		}
	}

	private static boolean embedsObjcBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmObjcRuntimeBuilder.BRIDGE_NAME);
	}

	@Test
	void everyVerbCompilesOnEveryMachineAndAMachineWithoutTheRuntimeSignalsAtTheCall() throws Exception {
		// The nine verbs compile everywhere; the call is what needs the runtime.
		byte[] all = compile("""
				(defun f (x) (list (objc:class x) (objc:send x "length" 1) (objc:define-class x "NSObject" nil)
				                   (objc:on-main x) (objc:string x) (objc:data x) (objc:bytes x)
				                   (objc:address x) (objc:objectp x)))
				""");
		assertThat(embedsObjcBridge(all)).isTrue();
		if (!ObjcInterop.available()) {
			// The condition is an ordinary error naming the verb and the reason, so a
			// program fails at the call that needed the runtime rather than somewhere
			// downstream with an undefined function.
			assertThat(
					compileAndRun("(print (handler-case (objc:class \"NSObject\") (error (e) (princ-to-string e))))"))
				.startsWith("\"objc:class: Objective-C is not available");
			assertThat(compileAndRun("(print (handler-case (objc:string \"x\") (error (e) (princ-to-string e))))"))
				.startsWith("\"objc:string: Objective-C is not available");
		}
	}

	@Test
	void theVerbsThatNeedNoRuntimeAnswerEverywhere() throws Exception {
		assertThat(compileAndRun("(print (objc:objectp 42))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (objc:send nil \"length\"))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (handler-case (objc:address 42) (error (e) (princ-to-string e))))"))
			.startsWith("\"objc:address expects an Objective-C object");
		assertThat(compileAndRun("(print (handler-case (objc:data '(1 2 3)) (error (e) (princ-to-string e))))"))
			.startsWith("\"objc:data expects a packed float array");
		assertThat(compileAndRun("(print (handler-case (objc:bytes 42) (error (e) (princ-to-string e))))"))
			.startsWith("\"objc:bytes expects an Objective-C object");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aPackedBufferCrossesAsAnNsdataAndComesBackTheSameBytes() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// The compiled packed float array carries its dimension header IN the array
		// ([rank, dims..., elements...]) and the packed integer vector its width, so the
		// twin has to skip what the interpreter's data() never included. These are the
		// interpreter's expectations, byte for byte (ObjcInteropTest).
		assertThat(compileAndRun("""
				(print (objc:bytes (objc:data (make-array 3 :element-type 'single-float
				                                         :initial-contents '(1.0 2.0 3.0)))))
				(print (objc:bytes (objc:data (make-array '(2 2) :element-type 'double-float
				                                         :initial-contents '((1.0d0 2.0d0) (3.0d0 4.0d0))))))
				(print (objc:bytes (objc:data (make-array 3 :element-type '(unsigned-byte 16)
				                                         :initial-contents '(1 258 65535)))))
				(print (objc:bytes (objc:data "hi")))
				(print (objc:bytes (objc:data (make-array 0 :element-type '(unsigned-byte 8)))))
				(print (objc:send (objc:data "hello") "length"))
				(print (objc:send (objc:data "hello") "isKindOfClass:" (objc:class "NSData")))
				(print (integerp (objc:send (objc:data "hello") "mutableBytes")))
				""")).isEqualTo("""
				#(0 0 128 63 0 0 0 64 0 0 64 64)
				#(0 0 0 0 0 0 240 63 0 0 0 0 0 0 0 64 0 0 0 0 0 0 8 64 0 0 0 0 0 0 16 64)
				#(1 0 2 1 255 255)
				#(104 105)
				#()
				5
				T
				T""");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theErrorSlotRaisesWhatTheNserrorSaysAndIsSilentWhenTheCallSucceeded() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(compileAndRun("""
				(print (handler-case (objc:send "NSJSONSerialization" "JSONObjectWithData:options:error:"
				                                (objc:data "not json") 0 :error)
				         (error (e) (princ-to-string e))))
				""")).contains("objc:send: JSONObjectWithData:options:error:").contains("NSCocoaErrorDomain");
		assertThat(compileAndRun("""
				(print (objc:send (objc:send "NSJSONSerialization" "JSONObjectWithData:options:error:"
				                             (objc:data "[1,2,3]") 0 :error)
				                  "count"))
				""")).isEqualTo("3");
	}

	@Test
	void theBlobIsEmbeddedOnlyWhenTheProgramReachesTheObjcPackage() {
		// The gate is the nine verbs, qualified; an appkit: program reaches them
		// through the spliced widget layer, whose every widget is objc:send.
		assertThat(embedsObjcBridge(compile("(print (+ 1 2))"))).isFalse();
		assertThat(embedsObjcBridge(compile("(print (objc:objectp 1))"))).isTrue();
		byte[] appkit = compile("(print (appkit:visible-p nil))");
		assertThat(embedsObjcBridge(appkit)).isTrue();
		// The spliced library is pruned to what the program reaches, like every
		// library (.kb/library-defun-pruning.md): the widget it calls stays, the
		// window it never opens goes.
		assertThat(new String(appkit, StandardCharsets.ISO_8859_1)).contains("APPKIT$colonVISIBLE-P")
			.doesNotContain("APPKIT$colonWINDOW");
	}

	@Test
	void theBlobCarriesTheWholeLibrary() throws Exception {
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
		// The bridge and the handle are ONE class file each: a nested class, or the
		// synthetic $1 an enum switch lowers to, would be a file the blob does not carry
		// (found the hard way: NoClassDefFoundError RontoLispObjcBridge$1).
		try (Stream<Path> files = Files.list(classes.resolve("am/ik/rontolisp/codegen/jvm"))) {
			assertThat(files.map(p -> p.getFileName().toString())
				.filter(name -> name.startsWith("JvmObjcTemplate$") || name.startsWith("JvmObjcHandle$"))).isEmpty();
		}
		// And nothing of the library's own package name survives the rename: the
		// emitted class resolves the blob under its own package or not at all.
		String bytes = new String(compile("(print (objc:objectp 1))"), StandardCharsets.ISO_8859_1);
		assertThat(bytes).doesNotContain("am/ik/objc/").doesNotContain("am/ik/rontolisp/codegen/jvm/JvmObjc");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aFoundationObjectRoundTripsThroughTheSelectorsOwnEncoding() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// Each answer is marshalled by the kind the runtime declared: an integer, a C
		// string, a struct as a list, a double, an object that prints its class.
		assertThat(compileAndRun("""
				(print (objc:send (objc:string "hello") "length"))
				(print (objc:send (objc:string "hello") "UTF8String"))
				(print (objc:send (objc:string "hello world") "rangeOfString:" "world"))
				(print (objc:send (objc:send "NSNumber" "numberWithDouble:" 2.5) "doubleValue"))
				(print (objc:class "NSString"))
				(print (objc:send (objc:send (objc:string "abc") "uppercaseString") "UTF8String"))
				(print (list (objc:objectp (objc:string "x")) (integerp (objc:address (objc:string "x")))))
				(print (objc:send (objc:string "x") "isKindOfClass:" (objc:class "NSString")))
				(print (objc:send (objc:string "x") "respondsToSelector:" "length"))
				(print (equal (objc:class "NSString") (objc:class "NSString")))
				(print (objc:send "NSString" "stringWithUTF8String:" "a longer string than a tagged one"))
				""")).startsWith("""
				5
				"hello"
				(6 5)
				2.5
				#<objc NSString>
				"ABC"
				(T T)
				T
				T
				T
				#<objc\s""");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aClassDefinedAtRunTimeRunsItsLispMethodOnTheMainThread() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// The method is applied from an upcall, with the receiver and the argument
		// wrapped; the answer is the closure's side effect, since performSelector:'s own
		// answer is that of a void method. Re-defining the class (a program may run its
		// definitions twice) rebinds the method rather than failing on a name the
		// runtime cannot unregister; and a callback that errors is reported, never
		// thrown into the native frame.
		assertThat(compileAndRun("""
				(defvar *seen* nil)
				(defvar *cls* (objc:define-class "RontoLispObjcCompiledA" "NSObject"
				                 (list (list "invoke:" (lambda (self sender)
				                                         (setq *seen* (list (objc:objectp self)
				                                                            (objc:send sender "UTF8String"))))))))
				(defvar *obj* (objc:send (objc:send *cls* "alloc") "init"))
				(objc:send *obj* "performSelector:withObject:" "invoke:" (objc:string "from lisp"))
				(print *seen*)
				(print (objc:define-class "RontoLispObjcCompiledA" "NSObject"
				         (list (list "invoke:" (lambda (self x) nil)))))
				(objc:define-class "RontoLispObjcCompiledA" "NSObject"
				  (list (list "invoke:" (lambda (self x) (error "boom")))))
				(objc:send (objc:send (objc:send (objc:class "RontoLispObjcCompiledA") "alloc") "init")
				           "performSelector:withObject:" "invoke:" nil)
				(print :survived)
				""")).isEqualTo("""
				(T "from lisp")
				#<objc RontoLispObjcCompiledA>
				:SURVIVED""".stripIndent().trim());
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aWrongSelectorArityOrOperandIsAConditionNotACrash() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(compileAndRun("""
				(print (handler-case (objc:send (objc:string "x") "nope:" 1) (error (e) (princ-to-string e))))
				(print (handler-case (objc:send (objc:string "x") "length" 1) (error (e) (princ-to-string e))))
				(print (handler-case (objc:send (objc:string "x") "rangeOfString:" 42) (error (e) (princ-to-string e))))
				(print (handler-case (objc:class "NoSuchClassAnywhere") (error (e) (princ-to-string e))))
				(print (handler-case (objc:define-class "RontoLispObjcCompiledB" "NSObject"
				                       (list (list "a:b:c:d:e:" (lambda () nil))))
				         (error (e) (princ-to-string e))))
				""")).contains("does not respond to nope:")
			.contains("length takes 0 argument(s), got 1")
			.contains("argument 1 must be an object")
			.contains("no Objective-C class named NoSuchClassAnywhere")
			.contains("outside the supported set");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void onMainAnswersTheBodysValueAndPropagatesItsError() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// Nested: a body already on thread 0 runs the inner one inline (the re-entrancy
		// rule), so this returns instead of deadlocking on the queue it is draining.
		assertThat(compileAndRun("""
				(print (objc:on-main (lambda () (+ 1 2))))
				(print (handler-case (objc:on-main (lambda () (error "inside"))) (error (e) (princ-to-string e))))
				(print (objc:on-main (lambda () (objc:on-main (lambda () :nested)))))
				""")).isEqualTo("""
				3
				"inside"
				:NESTED""".stripIndent().trim());
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theWidgetLayerCompilesAndItsHeadlessVerbsRun() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// appkit.lisp is spliced by the frontend and compiles as ordinary Lisp; the
		// verbs that need no window exercise its objc:send plumbing. (A window is never
		// opened by a test: examples/macos/counter.lisp is the visible check.)
		assertThat(compileAndRun("""
				(print (appkit:visible-p nil))
				(print (appkit:text nil))
				(print (appkit:set-text nil "x"))
				""")).isEqualTo("""
				NIL
				NIL
				"x\"""".stripIndent().trim());
	}

}
