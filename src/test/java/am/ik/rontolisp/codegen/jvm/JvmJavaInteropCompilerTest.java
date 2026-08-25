package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@code java:} interop functions compiled to a JVM {@code .class} (the
 * embedded {@link JavaBridgeTemplate} bridge). Mirrors the interpreter's
 * {@code JavaInteropTest} cases so the two backends stay behaviorally identical; every
 * case uses headless, deterministic JDK classes.
 */
class JvmJavaInteropCompilerTest {

	@TempDir
	Path tempDir;

	// Compiles the program, runs its main, and returns the captured stdout. A runtime
	// exception thrown by the program (e.g. a bridge validation error) is unwrapped and
	// rethrown so tests can assert on the original message.
	private String compileAndRun(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
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

	@Test
	void newAndInstanceCall() throws Exception {
		assertThat(compileAndRun("""
				(setq sb (java:new "java.lang.StringBuilder" "hi"))
				(java:call sb "append" "!")
				(print (java:call sb "toString"))
				""")).isEqualTo("\"hi!\"");
	}

	@Test
	void staticCall() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Integer\" \"parseInt\" \"100\"))")).isEqualTo("100");
	}

	@Test
	void staticReadsField() throws Exception {
		assertThat(compileAndRun("(print (java:field \"java.lang.Integer\" \"MAX_VALUE\"))")).isEqualTo("2147483647");
	}

	@Test
	void instanceReadsField() throws Exception {
		// java.awt.Point has public int fields x/y, and works headless.
		assertThat(compileAndRun("""
				(setq p (java:new "java.awt.Point" 3 4))
				(print (java:field p "y"))
				""")).isEqualTo("4");
	}

	// An integer argument prefers the int overload (returning an integer), not the
	// long/float/double ones -- the same deterministic overload resolution as the
	// interpreter.
	@Test
	void overloadResolutionPrefersIntForAnInteger() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Math\" \"max\" 3 7))")).isEqualTo("7");
	}

	@Test
	void overloadResolutionPrefersDoubleForAFloat() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Math\" \"abs\" -5.5))")).isEqualTo("5.5");
	}

	@Test
	void integerConvertsToDoubleWhenNoIntegerOverload() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Math\" \"sqrt\" 16))")).isEqualTo("4.0");
	}

	// The symbol t marshals to a boolean parameter (the compiled true is the bare
	// symbol "T", which is also what a boolean answer comes back as).
	@Test
	void trueMarshalsToABooleanParameter() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.String\" \"valueOf\" t))")).isEqualTo("\"true\"");
		assertThat(compileAndRun("(print (java:static \"java.lang.Boolean\" \"logicalAnd\" t t))")).isEqualTo("T");
	}

	// A boolean Java return (ArrayList.add) surfaces as t.
	@Test
	void booleanReturnSurfacesAsTrue() throws Exception {
		assertThat(compileAndRun("""
				(setq lst (java:new "java.util.ArrayList"))
				(print (java:call lst "add" 42))
				""")).isEqualTo("T");
	}

	@Test
	void collectionRoundTrip() throws Exception {
		assertThat(compileAndRun("""
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 10)
				(java:call lst "add" 20)
				(print (list (java:call lst "size") (java:call lst "get" 1)))
				""")).isEqualTo("(2 20)");
	}

	// A rontolisp lambda becomes a Runnable through the embedded bridge's _apply
	// callback; calling run() runs the lambda (void return).
	@Test
	void proxyVoidReturnRunsTheLambda() throws Exception {
		assertThat(compileAndRun("""
				(setq fired nil)
				(setq r (java:proxy "java.lang.Runnable" (lambda (method) (setq fired t))))
				(java:call r "run")
				(print fired)
				""")).isEqualTo("T");
	}

	@Test
	void proxyValueReturn() throws Exception {
		assertThat(compileAndRun("""
				(setq s (java:proxy "java.util.function.Supplier" (lambda (method) 42)))
				(print (java:call s "get"))
				""")).isEqualTo("42");
	}

	// A proxy that receives arguments: a Comparator drives Collections.sort.
	@Test
	void proxyReceivesArguments() throws Exception {
		assertThat(compileAndRun("""
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 30)
				(java:call lst "add" 10)
				(java:call lst "add" 20)
				(java:static "java.util.Collections" "sort" lst
				  (java:proxy "java.util.Comparator" (lambda (method a b) (- a b))))
				(print (list (java:call lst "get" 0) (java:call lst "get" 2)))
				""")).isEqualTo("(10 30)");
	}

	// A defun'd function value is auto-proxied when passed where an interface is
	// expected.
	@Test
	void functionValueAutoProxies() throws Exception {
		assertThat(compileAndRun("""
				(defun desc (method a b) (- b a))
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 10)
				(java:call lst "add" 30)
				(java:call lst "add" 20)
				(java:static "java.util.Collections" "sort" lst #'desc)
				(print (java:call lst "get" 0))
				""")).isEqualTo("30");
	}

	// A proper list marshals to a primitive array parameter; the int elements prefer
	// the int[] overload of binarySearch.
	@Test
	void listMarshalsToPrimitiveArrayParameter() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.util.Arrays\" \"binarySearch\" (list 10 20 30 40) 30))"))
			.isEqualTo("2");
	}

	@Test
	void listMarshalsToCollectionParameter() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.util.Collections\" \"max\" (list 3 9 4)))"))
			.isEqualTo("9");
	}

	@Test
	void nestedListMarshalsRecursively() throws Exception {
		assertThat(
				compileAndRun("(print (java:static \"java.util.Arrays\" \"deepToString\" (list (list 1 2) (list 3))))"))
			.isEqualTo("\"[[1, 2], [3]]\"");
	}

	// A rank-1 vector (the runtime ArrayList representation) marshals like a list;
	// string elements select the CharSequence[] varargs parameter of String.join.
	@Test
	void vectorMarshalsToArrayParameter() throws Exception {
		assertThat(compileAndRun("""
				(setq v (make-array 2))
				(setf (aref v 0) "a")
				(setf (aref v 1) "b")
				(print (java:static "java.lang.String" "join" "-" v))
				""")).isEqualTo("\"a-b\"");
	}

	@Test
	void fillPointerVectorMarshalsUpToFillPointer() throws Exception {
		// The fill pointer bounds the marshaled sequence, matching length/printing.
		assertThat(compileAndRun("""
				(setq v (make-array 3 :fill-pointer 0))
				(vector-push "a" v)
				(vector-push "b" v)
				(print (java:static "java.lang.String" "join" "-" v))
				""")).isEqualTo("\"a-b\"");
	}

	// A dotted (improper) list is not a sequence, so no overload matches.
	@Test
	void dottedListDoesNotMarshal() {
		assertThatThrownBy(() -> compileAndRun("(java:static \"java.util.Collections\" \"max\" (cons 1 2))"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("No matching method");
	}

	@Test
	void varargsPacksTrailingArguments() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.String\" \"format\" \"%s-%s\" 1 \"x\"))"))
			.isEqualTo("\"1-x\"");
	}

	// Eleven arguments exceed every fixed-arity List.of overload, forcing the varargs
	// one; and a varargs method also accepts an empty tail.
	@Test
	void varargsAcceptsAnyArity() throws Exception {
		assertThat(compileAndRun(
				"(print (java:call (java:static \"java.util.List\" \"of\" 1 2 3 4 5 6 7 8 9 10 11) \"size\"))"))
			.isEqualTo("11");
		assertThat(compileAndRun("(print (java:call (java:static \"java.util.List\" \"of\") \"size\"))"))
			.isEqualTo("0");
	}

	// A returned Java array surfaces as a Lisp list, and a list marshals back into an
	// array parameter -- Arrays.copyOf(int[], int) round-trips both directions.
	@Test
	void arrayRoundTripsThroughCopyOf() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.util.Arrays\" \"copyOf\" (list 1 2 3) 2))"))
			.isEqualTo("(1 2)");
	}

	// A returned primitive array unmarshals element-wise; the IntStream implementation
	// class is JDK-internal, so this also exercises the accessible-method resolution.
	@Test
	void returnedPrimitiveArrayUnmarshalsToList() throws Exception {
		assertThat(compileAndRun(
				"(print (java:call (java:call (java:new \"java.lang.StringBuilder\" \"ab\") \"chars\") \"toArray\"))"))
			.isEqualTo("(97 98)");
	}

	// A wrapped host object prints opaquely as #<java class>, matching the interpreter.
	@Test
	void printsOpaquely() throws Exception {
		assertThat(compileAndRun("(print (java:new \"java.lang.StringBuilder\"))"))
			.isEqualTo("#<java java.lang.StringBuilder>");
	}

	// java: interop composes with hash tables: the HashMap-based Lisp hash table keeps
	// working (and hash-table-p stays t) while host objects print opaquely.
	@Test
	void composesWithHashTables() throws Exception {
		assertThat(compileAndRun("""
				(setq h (make-hash-table))
				(setf (gethash 'k h) (java:static "java.lang.Math" "max" 1 2))
				(print (gethash 'k h))
				(print (hash-table-p h))
				""")).isEqualTo("2\nT");
	}

	@Test
	void unknownClassSignals() {
		assertThatThrownBy(() -> compileAndRun("(java:new \"no.such.Class\")")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("No such class");
	}

	@Test
	void noMatchingMethodSignals() {
		assertThatThrownBy(() -> compileAndRun("""
				(setq sb (java:new "java.lang.StringBuilder"))
				(java:call sb "noSuchMethod" 1 2 3)
				""")).isInstanceOf(RuntimeException.class).hasMessageContaining("No matching method");
	}

	@Test
	void callOnNonObjectSignals() {
		assertThatThrownBy(() -> compileAndRun("(java:call 42 \"toString\")")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("expects a java object");
	}

	@Test
	void proxyOnNonInterfaceSignals() {
		assertThatThrownBy(() -> compileAndRun("(java:proxy \"java.lang.String\" (lambda (m) nil))"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("expects an interface");
	}

	// Arity errors are caught at compile time.
	@Test
	void missingArgumentsFailAtCompileTime() {
		assertThatThrownBy(() -> compileAndRun("(java:new)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("java:new expects");
		assertThatThrownBy(() -> compileAndRun("(java:call 1)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("java:call expects");
		assertThatThrownBy(() -> compileAndRun("(java:field \"java.lang.Integer\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("java:field expects");
	}

	// The renamed bridge class stays structurally valid: this is implicitly covered by
	// every test above, but the rename itself must also leave non-matching classes
	// untouched.
	@Test
	void renameClassLeavesOtherUtf8EntriesIntact() throws Exception {
		byte[] original;
		try (var in = JavaBridgeTemplate.class.getResourceAsStream("JavaBridgeTemplate.class")) {
			original = java.util.Objects.requireNonNull(in).readAllBytes();
		}
		byte[] renamed = JvmJavaRuntimeBuilder.renameClass(original, "am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate",
				"RontoLispJavaBridge");
		String renamedText = new String(renamed, java.nio.charset.StandardCharsets.ISO_8859_1);
		assertThat(renamedText).doesNotContain("am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate");
		assertThat(renamedText).contains("RontoLispJavaBridge");
		// Renaming back round-trips to the original bytes.
		byte[] roundTripped = JvmJavaRuntimeBuilder.renameClass(renamed, "RontoLispJavaBridge",
				"am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate");
		assertThat(roundTripped).isEqualTo(original);
	}

	// MethodHandles.Lookup.defineClass(byte[]) requires the defined class to share the
	// lookup class's package, so a generated class that HAS a package must rename the
	// embedded bridge into that package too -- not into the default package, which is
	// what every other test above compiles into.
	@Test
	void theBridgeIsRenamedIntoTheGeneratedClassOwnPackage() throws Exception {
		List<LispVal> program = LispReader.readAllFromString("(print (java:static \"java.lang.Math\" \"max\" 3 7))");
		JvmLispCompiler compiler = new JvmLispCompiler("com/example/Test");
		byte[] classBytes = compiler.compile(program);
		Path packageDir = this.tempDir.resolve("com").resolve("example");
		Files.createDirectories(packageDir);
		Files.write(packageDir.resolve("Test.class"), classBytes);

		String bridgeName = "com/example/" + JvmJavaRuntimeBuilder.BRIDGE_NAME;
		assertThat(new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1)).contains(bridgeName);

		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("com.example.Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim()).isEqualTo("7");
		}
	}

}
