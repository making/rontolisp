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

import am.ik.ffi.FfiRuntime;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.FfiInterop;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code ffi:} verbs compiled to a JVM {@code .class} (the embedded {@code am.ik.ffi}
 * blob plus the {@link JvmFfiTemplate} bridge). Mirrors the interpreter's {@code FfiTest}
 * case for case so the two stay behaviorally identical -- and unlike the {@code objc:}
 * twin it RUNS on this machine: the exercising tests need only libc/libm and are skipped
 * where the JVM denies native access.
 */
class JvmFfiInteropCompilerTest {

	@TempDir
	Path tempDir;

	private static byte[] compile(String lispCode) {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		return new JvmLispCompiler("Test").compile(program);
	}

	// Compiles the program, runs its main, and returns the captured stdout. A runtime
	// exception thrown by the program is unwrapped and rethrown so tests can assert on
	// the original message.
	private String compileAndRun(String lispCode) throws Exception {
		// The CLI runs UserMacroExpander in CompileFrontend before the backend; doing
		// the same here is what lets a test program carry defmacro/eval-when.
		byte[] classBytes = new JvmLispCompiler("Test")
			.compile(UserMacroExpander.expand(LispReader.readAllFromString(lispCode)));
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

	private static boolean embedsFfiBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmFfiRuntimeBuilder.BRIDGE_NAME);
	}

	private static String libm() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac") ? "libm.dylib"
				: "libm.so.6";
	}

	@Test
	void everyVerbCompilesOnEveryMachine() {
		byte[] all = compile("""
				(defun f (x) (list (ffi:open x) (ffi:symbol 0 x) (ffi:call x :int '(:int) 1)
				                   (ffi:%apply-call x :int '(:int) (list 1))
				                   (ffi:callback x :int '(:pointer)) (ffi:alloc x) (ffi:free x)
				                   (ffi:peek x :int) (ffi:poke x :int 1 4) (ffi:size :int) (ffi:align :int)
				                   (ffi:pointerp x) (ffi:address x) (ffi:errno)))
				""");
		assertThat(embedsFfiBridge(all)).isTrue();
	}

	@Test
	void theVerbsThatNeedNoRuntimeAnswerEverywhere() throws Exception {
		assertThat(compileAndRun("(print (ffi:pointerp 42))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (handler-case (ffi:address \"x\") (error (e) (princ-to-string e))))"))
			.startsWith("\"ffi:address expects a pointer or an integer address");
		assertThat(compileAndRun("(print (handler-case (ffi:peek 0 :int) (error (e) (princ-to-string e))))"))
			.contains("null pointer");
	}

	@Test
	void theVerbsRunAgainstLibcExactlyAsTheInterpreterDoes() throws Exception {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// The interpreter's FfiTest expectations, on the compiled twin: dlopen, symbol,
		// a canonicalised downcall, alloc/peek/poke at every width, a string round
		// trip, the pointer's print and equal, errno.
		assertThat(compileAndRun("""
				(let* ((lib (ffi:open "LIBM"))
				       (cosfn (ffi:symbol lib "cos")))
				  (print (ffi:pointerp cosfn))
				  (print (ffi:call cosfn :double '(:double) 0.0)))
				(let ((strlen (ffi:symbol (ffi:open) "strlen")))
				  (print (ffi:call strlen :long '(:string) "hello, world"))
				  (print (ffi:%apply-call strlen :long '(:string) (list "abc"))))
				(let ((p (ffi:alloc 32)))
				  (ffi:poke p :char -1)
				  (print (ffi:peek p :char))
				  (print (ffi:peek p :uchar))
				  (ffi:poke p :double 2.5 8)
				  (print (ffi:peek p :double 8))
				  (print (equal (ffi:address (ffi:address p)) p))
				  (ffi:free p))
				(print (ffi:size :pointer))
				(print (list (ffi:size '(:struct :int :char)) (ffi:align '(:struct :int :char))))
				""".replace("LIBM", libm()))).isEqualTo("""
				T
				1.0
				12
				3
				-1
				255
				2.5
				T
				8
				(8 4)""".stripIndent().trim());
	}

	@Test
	void aProducerBuiltStringCrossesTheFfiBoundary() throws Exception {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// A library name, a symbol name and a :string argument built by concatenate /
		// format nil are MUTABLE character vectors on this backend
		// (.kb/string-write-runtime.md): the template's lispString renders one through
		// the reflectively bound _strv, so the FFM boundary accepts it exactly as the
		// interpreter's FfiBridge accepts a LispString. This is the unit pin for the
		// examples/jvm/cffi-sqlite.lisp canary, whose sqlite3-lib name is a
		// concatenate result.
		String m = libm();
		assertThat(compileAndRun("""
				(let* ((lib (ffi:open (concatenate 'string "%s" "%s")))
				       (cosfn (ffi:symbol lib (format nil "co~a" "s"))))
				  (print (ffi:pointerp cosfn))
				  (print (ffi:call cosfn :double '(:double) 0.0)))
				(let ((strlen (ffi:symbol (ffi:open) "strlen")))
				  (print (ffi:call strlen :long '(:string) (concatenate 'string "abc" "def"))))
				""".formatted(m.substring(0, 1), m.substring(1)))).isEqualTo("T\n1.0\n6");
	}

	@Test
	void aForeignPointerPrintsAndComparesLikeTheInterpreters() throws Exception {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		assertThat(compileAndRun("(print (ffi:address 291))")).isEqualTo("#<pointer #x123>");
		assertThat(compileAndRun("(print (equal (ffi:address 7) (ffi:address 7)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (ffi:pointerp (ffi:address 0)))")).isEqualTo("T");
		// The unsigned-64-bit round trip, case for case with the interpreter
		// (JvmFfiTemplate is the hand-kept twin of eval/FfiBridge).
		assertThat(compileAndRun("(print (ffi:address (ffi:address #xFFFFFFFFFFFFFFFF)))"))
			.isEqualTo("18446744073709551615");
	}

	@Test
	void aCallbackAppliesItsLispFunctionFromTheUpcall() throws Exception {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// qsort through a compiled Lisp comparator: the blob upcalls into the
		// program's _apply, the same seam the interpreter's FfiTest exercises.
		assertThat(compileAndRun("""
				(let* ((qsort (ffi:symbol (ffi:open) "qsort"))
				       (buf (ffi:alloc 16)))
				  (ffi:poke buf :int 5)
				  (ffi:poke buf :int 3 4)
				  (ffi:poke buf :int 9 8)
				  (ffi:poke buf :int 1 12)
				  (let ((cmp (ffi:callback (lambda (a b) (- (ffi:peek a :int) (ffi:peek b :int)))
				                           :int '(:pointer :pointer))))
				    (ffi:call qsort :void '(:pointer :ulong :ulong :pointer) buf 4 4 cmp))
				  (print (list (ffi:peek buf :int) (ffi:peek buf :int 4)
				               (ffi:peek buf :int 8) (ffi:peek buf :int 12)))
				  (ffi:free buf))
				""")).isEqualTo("(1 3 5 9)");
	}

	@Test
	void theBlobIsEmbeddedOnlyWhenTheProgramReachesTheFfiPackage() {
		assertThat(embedsFfiBridge(compile("(print (+ 1 2))"))).isFalse();
		assertThat(embedsFfiBridge(compile("(print (ffi:pointerp 1))"))).isTrue();
	}

	@Test
	void theBlobCarriesTheWholeLibrary() throws Exception {
		// The compiled backend runs am.ik.ffi's own bytes rather than a hand-kept copy
		// of them, so a class file added to the library must be added to the list that
		// travels (the JvmObjcRuntimeBuilder rule).
		Path classes = Path.of(FfiRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assumeTrue(Files.isDirectory(classes), "the library must be on the classpath as class files");
		List<String> onDisk;
		try (Stream<Path> files = Files.list(classes.resolve("am/ik/ffi"))) {
			onDisk = files.map(p -> p.getFileName().toString())
				.filter(name -> name.endsWith(".class"))
				.map(name -> name.substring(0, name.length() - ".class".length()))
				.filter(name -> !name.equals("package-info"))
				.sorted()
				.toList();
		}
		assertThat(JvmFfiRuntimeBuilder.embeddedFfiClasses()).containsExactlyInAnyOrderElementsOf(onDisk);
		// The bridge and the handle are ONE class file each: a nested class, or the
		// synthetic $1 an enum switch lowers to, would be a file the blob does not
		// carry.
		try (Stream<Path> files = Files.list(classes.resolve("am/ik/rontolisp/codegen/jvm"))) {
			assertThat(files.map(p -> p.getFileName().toString())
				.filter(name -> name.startsWith("JvmFfiTemplate$") || name.startsWith("JvmFfiHandle$"))).isEmpty();
		}
		// And nothing of the library's own package name survives the rename: the
		// emitted class resolves the blob under its own package or not at all.
		String bytes = new String(compile("(print (ffi:pointerp 1))"), StandardCharsets.ISO_8859_1);
		assertThat(bytes).doesNotContain("am/ik/ffi/").doesNotContain("am/ik/rontolisp/codegen/jvm/JvmFfi");
	}

	@Test
	void aGuardedFallbackDefmacroCollapsesBecauseFboundpOfAMacroIsACompileTimeT() throws Exception {
		// cffi's functions.lisp guards its fallback %foreign-funcall-varargs with
		// (eval-when (...) (unless (fboundp '...) (defmacro ...))). A macro does not
		// exist in the compiled program, so only the compile-time fold can answer the
		// probe the way CL (and the interpreter) do -- unfolded, the kept load-toplevel
		// member calls DEFMACRO at run time and dies.
		assertThat(compileAndRun("""
				(defmacro m (x) `(+ ,x 1))
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (unless (fboundp 'm)
				    (this-would-be-an-undefined-function)))
				(print (m 41))
				""")).isEqualTo("42");
	}

	@Test
	void setfOfApplyArefIsTheRuntimeRankPlace() throws Exception {
		// (setf (apply #'aref a subs) v) -- CLHS 5.1.2.5, what cffi's
		// foreign-array-to-lisp spells for an array whose rank is a runtime value.
		assertThat(compileAndRun("""
				(let ((a (make-array '(2 3) :initial-element 0)))
				  (setf (apply #'aref a (list 1 2)) 42)
				  (setf (apply #'aref a 0 (list 1)) 7)
				  (print (list (aref a 1 2) (aref a 0 1))))
				""")).isEqualTo("(42 7)");
	}

}
